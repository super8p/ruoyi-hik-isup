package com.oldwei.isup.controller;

import com.oldwei.isup.config.HikStreamProperties;
import com.oldwei.isup.config.HikIsupProperties;
import com.oldwei.isup.model.R;
import com.oldwei.isup.model.vo.PlayURL;
import com.oldwei.isup.service.IGB28181StreamService;
import com.aizuda.zlm4j.core.ZLMApi;
import com.aizuda.zlm4j.callback.IMKWebRtcGetAnwerSdpCallBack;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * GB28181 视频流控制接口
 */
@Slf4j
@RestController
@RequestMapping("/api/gb28181/{deviceId}")
@RequiredArgsConstructor
public class GB28181StreamController {

    private final IGB28181StreamService gbStreamService;
    private final HikStreamProperties hikStreamProperties;
    private final HikIsupProperties hikIsupProperties;
    private final ZLMApi zlmApi;
    private final java.util.Set<Object> callbackKeepAlive = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 开始实时预览 (国标)
     * <p>
     * wvp 的 GB28181 流注册机制：设备推流 PS/RTP → ZLM 接收后注册为 rtp/{SSRC_HEX_大写}
     * 例如 SSRC=0123456789（十进制）→ ZLM 流 key = rtp/1CBE991（十六进制大写）
     */
    @PostMapping("/stream/play")
    public R<PlayURL> startPlay(@PathVariable("deviceId") String deviceId, @RequestParam("channelId") String channelId) {
        log.info("API request to start GB28181 video stream: device={}, channel={}", deviceId, channelId);
        try {
            // 等待 INVITE 200 OK，获取 ssrcHex（最多等 12 秒）
            String ssrcHex = gbStreamService.startPlay(deviceId, channelId)
                    .get(12, java.util.concurrent.TimeUnit.SECONDS);
            log.info("GB28181 INVITE OK, ssrcHex={}, building WebRTC URL with rtp/{}", ssrcHex, ssrcHex);

            // 对标 wvp: ZLM 接收 GB28181 PS 推流后注册为 rtp/{SSRC_HEX}
            PlayURL playURL = new PlayURL();
            playURL.setHttpFlv(buildFlvUrl("rtp", ssrcHex));
            playURL.setWebrtc(buildWebrtcUrl("rtp", ssrcHex));
            return R.ok(playURL);
        } catch (java.util.concurrent.TimeoutException e) {
            log.error("GB28181 INVITE 等待设备响应超时: device={}, channel={}", deviceId, channelId);
            return R.fail("设备未在规定时间内响应视频请求（12s超时），请确认设备在线");
        } catch (Exception e) {
            log.error("GB28181 startPlay failed: device={}, channel={}", deviceId, channelId, e);
            return R.fail("开启预览失败: " + e.getMessage());
        }
    }

    /**
     * 停止实时预览 (国标)
     */
    @PostMapping("/stream/stop")
    public R<String> stopPlay(@PathVariable("deviceId") String deviceId, @RequestParam("channelId") String channelId) {
        log.info("API request to stop GB28181 video stream: device={}, channel={}", deviceId, channelId);
        gbStreamService.stopPlay(deviceId, channelId);
        return R.ok("GB28181 video play signaling stopped.");
    }

    private String buildFlvUrl(String app, String streamKey) {
        Boolean isSSL = hikStreamProperties.getIsSSL();
        if (isSSL != null && isSSL) {
            return "https://" + hikStreamProperties.getDomain() + "/" + app + "/" + streamKey + ".live.flv";
        }
        return "http://" + hikStreamProperties.getHttp().getIp() + ":"
                + hikStreamProperties.getHttp().getPort() + "/" + app + "/" + streamKey + ".live.flv";
    }

    private String buildWebrtcUrl(String app, String streamKey) {
        Boolean isSSL = hikStreamProperties.getIsSSL();
        if (isSSL != null && isSSL) {
            return "https://" + hikStreamProperties.getDomain() + "/index/api/webrtc?app=" + app + "&stream=" + streamKey + "&type=play";
        }
        return "http://" + hikStreamProperties.getHttp().getIp() + ":"
                + hikStreamProperties.getHttp().getPort() + "/index/api/webrtc?app=" + app + "&stream=" + streamKey + "&type=play";
    }

    /**
     * 将 WebRTC SDP Offer 转发至 ZLM，避免浏览器跨域访问。
     */
    @PostMapping(value = "/webrtcSdp", consumes = "text/plain")
    public R<String> webrtcSdp(
            @PathVariable String deviceId,
            @RequestParam("targetUrl") String targetUrl,
            @RequestBody(required = false) String sdp) {
        if (sdp == null) {
            sdp = "";
        }
        try {
            String decodedTargetUrl = URLDecoder.decode(targetUrl, StandardCharsets.UTF_8.name());
            log.info("GB28181 webrtcSdp proxy, targetUrl={}, decodedTargetUrl={}", targetUrl, decodedTargetUrl);
            
            // 解析 targetUrl 获取 app, stream, type 参数
            java.net.URI uri = new java.net.URI(decodedTargetUrl);
            String query = uri.getQuery();
            String app = "live";
            String stream = "";
            String type = "play";
            if (query != null) {
                String[] pairs = query.split("&");
                for (String pair : pairs) {
                    int idx = pair.indexOf("=");
                    if (idx > 0) {
                        String key = pair.substring(0, idx);
                        String value = pair.substring(idx + 1);
                        if ("app".equalsIgnoreCase(key)) {
                            app = value;
                        } else if ("stream".equalsIgnoreCase(key)) {
                            stream = value;
                        } else if ("type".equalsIgnoreCase(key)) {
                            type = value;
                        }
                    }
                }
            }

            // 拼接 ZLM 内部 RTC URL 格式
            String rtcUrl = "rtc://__defaultVhost__/" + app + "/" + stream + "?app=" + app + "&stream=" + stream + "&type=" + type;
            log.info("GB28181 webrtcSdp JNA calling, app={}, stream={}, type={}, rtcUrl={}", app, stream, type, rtcUrl);

            String answerSdp = null;
            int maxRetries = 15;
            int retryCount = 0;
            while (retryCount < maxRetries) {
                // For play type, pre-check if the stream actually exists in ZLM to avoid JNA timeout
                if ("play".equalsIgnoreCase(type)) {
                    com.aizuda.zlm4j.structure.MK_MEDIA_SOURCE ctx = zlmApi.mk_media_source_find2("rtp", "__defaultVhost__", app, stream, 0);
                    if (ctx == null) {
                        ctx = zlmApi.mk_media_source_find2("rtsp", "__defaultVhost__", app, stream, 0);
                    }
                    if (ctx == null) {
                        ctx = zlmApi.mk_media_source_find2("rtmp", "__defaultVhost__", app, stream, 0);
                    }
                    if (ctx == null) {
                        retryCount++;
                        log.info("GB28181 webrtcSdp: stream {} not found in ZLM yet, waiting for push ({}/{})", stream, retryCount, maxRetries);
                        Thread.sleep(500);
                        continue;
                    }
                }

                CompletableFuture<String> future = new CompletableFuture<>();
                IMKWebRtcGetAnwerSdpCallBack callback = new IMKWebRtcGetAnwerSdpCallBack() {
                    @Override
                    public void invoke(com.sun.jna.Pointer user_data, String answer_sdp, String err) {
                        try {
                            if (err != null && !err.isEmpty()) {
                                future.completeExceptionally(new RuntimeException(err));
                            } else {
                                log.info("JNA WebRTC SDP callback succeed, answerSdp length: {}", answer_sdp != null ? answer_sdp.length() : 0);
                                future.complete(answer_sdp);
                            }
                        } catch (Exception e) {
                            future.completeExceptionally(e);
                        } finally {
                            callbackKeepAlive.remove(this);
                        }
                    }
                };

                callbackKeepAlive.add(callback);
                zlmApi.mk_webrtc_get_answer_sdp(null, callback, type, sdp, rtcUrl);

                try {
                    answerSdp = future.get(800, java.util.concurrent.TimeUnit.MILLISECONDS);
                    break;
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause != null && "stream not found".equals(cause.getMessage())) {
                        retryCount++;
                        log.info("GB28181 webrtcSdp: stream not found in ZLM yet, retrying ({}/{}) for stream {}", retryCount, maxRetries, stream);
                        Thread.sleep(500);
                    } else {
                        throw e;
                    }
                } catch (java.util.concurrent.TimeoutException e) {
                    retryCount++;
                    log.info("GB28181 webrtcSdp: JNA callback timeout, retrying ({}/{}) for stream {}", retryCount, maxRetries, stream);
                    Thread.sleep(500);
                }
            }

            if (answerSdp == null) {
                return R.fail("代理 WebRTC SDP 请求失败: 流未就绪 (stream not found)");
            }
            return R.ok("操作成功", answerSdp);
        } catch (Exception e) {
            log.error("GB28181 webrtc sdp proxy failed, targetUrl={}", targetUrl, e);
            return R.fail("代理 WebRTC SDP 请求异常: " + e.getMessage());
        }
    }
}
