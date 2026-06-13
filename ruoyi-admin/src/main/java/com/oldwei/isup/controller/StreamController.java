package com.oldwei.isup.controller;

import com.oldwei.isup.config.HikStreamProperties;
import com.oldwei.isup.config.HikIsupProperties;
import com.oldwei.isup.model.Device;
import com.oldwei.isup.model.R;
import com.oldwei.isup.model.vo.PlayURL;
import com.oldwei.isup.sdk.StreamManager;
import com.oldwei.isup.service.DeviceCacheService;
import com.oldwei.isup.service.IMediaStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.net.URLDecoder;
import com.aizuda.zlm4j.core.ZLMApi;
import com.aizuda.zlm4j.callback.IMKWebRtcGetAnwerSdpCallBack;
import java.util.concurrent.CompletableFuture;

/**
 * 流媒体控制接口（预览、回放）
 */
@Slf4j
@RestController
@RequestMapping("/api/devices/{deviceId}")
@RequiredArgsConstructor
public class StreamController {

    private final IMediaStreamService mediaStreamService;
    private final DeviceCacheService deviceCacheService;
    private final HikStreamProperties hikStreamProperties;
    private final HikIsupProperties hikIsupProperties;
    private final ZLMApi zlmApi;
    private final java.util.Set<Object> callbackKeepAlive = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 开始实时预览
     */
    @PostMapping("/preview")
    public R<PlayURL> startPreview(
            @PathVariable String deviceId,
            @RequestParam(required = false, defaultValue = "1") Integer channelId) {
        Optional<Device> deviceOpt = deviceCacheService.getByDeviceId(deviceId);
        if (deviceOpt.isEmpty()) {
            return R.fail("设备不存在，无法预览");
        }

        Device device = deviceOpt.get();
        // 查找指定的通道
        Device.Channel channel = device.getChannels().stream()
                .filter(ch -> ch.getChannelId().equals(channelId))
                .findFirst()
                .orElse(null);

        if (channel == null) {
            return R.fail("通道不存在: " + channelId);
        }

        String streamKey = deviceId + "_" + channelId;
        // 防重复：如果该通道已有RTP服务，直接返回播放地址
        if (StreamManager.deviceRTP.containsKey(streamKey)) {
            log.info("通道已在预览中，忽略重复开启: {}", streamKey);
            PlayURL playURL = new PlayURL();
            playURL.setHttpFlv(buildHttpFlvStreamUrl("live", streamKey));
            playURL.setWebrtc(buildWebrtcStreamUrl("live", streamKey));
            return R.ok(playURL);
        }

        mediaStreamService.preview(device, channelId);

        PlayURL playURL = new PlayURL();
        playURL.setHttpFlv(buildHttpFlvStreamUrl("live", streamKey));
        playURL.setWebrtc(buildWebrtcStreamUrl("live", streamKey));

        return R.ok(playURL);
    }

    /**
     * 停止实时预览
     */
    @DeleteMapping("/preview")
    public R<Boolean> stopPreview(
            @PathVariable String deviceId,
            @RequestParam(required = false, defaultValue = "1") Integer channelId) {
        Optional<Device> deviceOpt = deviceCacheService.getByDeviceId(deviceId);
        deviceOpt.ifPresent(device -> {
            log.debug("停止预览 - deviceId: {}, channelId: {}", deviceId, channelId);
            mediaStreamService.stopPreview(device, channelId);
        });
        return R.ok(true);
    }

    /**
     * 开始回放
     */
    @PostMapping("/playback")
    public R<PlayURL> startPlayback(
            @PathVariable String deviceId,
            @RequestParam(required = false, defaultValue = "1") Integer channelId,
            @RequestParam String startTime,
            @RequestParam String endTime) {

        if (deviceId == null || deviceId.isEmpty()) {
            return R.fail("设备ID不能为空");
        }

        // URL 解码
        try {
            if (startTime != null) {
                startTime = URLDecoder.decode(startTime, StandardCharsets.UTF_8);
            }
            if (endTime != null) {
                endTime = URLDecoder.decode(endTime, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("时间参数解码失败: startTime={}, endTime={}", startTime, endTime, e);
        }

        Optional<Device> deviceOpt = deviceCacheService.getByDeviceId(deviceId);
        if (deviceOpt.isEmpty()) {
            log.warn("回放失败: 设备不存在, deviceId={}", deviceId);
            return R.fail("回放失败: 设备不存在");
        }

        Device device = deviceOpt.get();
        // 查找指定的通道
        Device.Channel channel = device.getChannels().stream()
                .filter(ch -> ch.getChannelId().equals(channelId))
                .findFirst()
                .orElse(null);
        if (channel == null) {
            return R.fail("通道不存在: " + channelId);
        }

        String streamKey = deviceId + "_" + channelId;
        // 防重复：如果该通道已有RTP服务，直接返回播放地址
        if (StreamManager.playbackDeviceRTP.containsKey(streamKey)) {
            log.info("通道已在回放中，忽略重复开启: {}", streamKey);
            PlayURL playURL = new PlayURL();
            playURL.setHttpFlv(buildHttpFlvStreamUrl("playback", streamKey));
            playURL.setWebrtc(buildWebrtcStreamUrl("playback", streamKey));
            return R.ok(playURL);
        }

        Integer loginId = device.getLoginId();
        mediaStreamService.playbackByTime(streamKey, loginId, channelId, startTime, endTime);

        PlayURL playURL = new PlayURL();
        playURL.setHttpFlv(buildHttpFlvStreamUrl("playback", streamKey));
        playURL.setWebrtc(buildWebrtcStreamUrl("playback", streamKey));

        return R.ok(playURL);
    }

    /**
     * 停止回放
     */
    @DeleteMapping("/playback")
    public R<String> stopPlayback(@PathVariable String deviceId, @RequestParam(required = false, defaultValue = "1") Integer channelId) {
        Optional<Device> deviceOpt = deviceCacheService.getByDeviceId(deviceId);
        if (deviceOpt.isPresent()) {
            Device device = deviceOpt.get();
            Integer loginId = device.getLoginId();
            log.debug("停止回放 - deviceId: {}", deviceId);
            mediaStreamService.stopPlayBackByTime(deviceId, loginId, channelId);
        }
        return R.ok();
    }

    private String buildHttpFlvStreamUrl(String prefix, String streamKey) {
        Boolean isSSL = hikStreamProperties.getIsSSL();
        if (isSSL != null && isSSL) {
            return "https://" + hikStreamProperties.getDomain() + "/" + prefix + "/" + streamKey + ".live.flv";
        }
        return "http://" + hikStreamProperties.getHttp().getIp() + ":"
                + hikStreamProperties.getHttp().getPort() + "/" + prefix + "/" + streamKey + ".live.flv";
    }

    private String buildWebrtcStreamUrl(String prefix, String streamKey) {
        Boolean isSSL = hikStreamProperties.getIsSSL();
        if (isSSL != null && isSSL) {
            return "https://" + hikStreamProperties.getDomain() + "/index/api/webrtc?app=" + prefix + "&stream=" + streamKey + "&type=play";
        }
        return "http://" + hikStreamProperties.getHttp().getIp() + ":"
                + hikStreamProperties.getHttp().getPort() + "/index/api/webrtc?app=" + prefix + "&stream=" + streamKey + "&type=play";
    }

    /**
     * 强制开启实时预览（不检查是否已在预览中）
     * 前端在获取到播放地址但画面为空时可以调用此接口重新拉流
     */
    @PostMapping("/preview/force")
    public R<PlayURL> forceStartPreview(@PathVariable String deviceId,
                                         @RequestParam(required = false, defaultValue = "1") Integer channelId) {
        Optional<Device> deviceOpt = deviceCacheService.getByDeviceId(deviceId);
        if (deviceOpt.isEmpty()) {
            return R.fail("设备不存在，无法预览");
        }
        Device device = deviceOpt.get();
        Device.Channel channel = device.getChannels().stream()
                .filter(ch -> ch.getChannelId().equals(channelId))
                .findFirst()
                .orElse(null);
        if (channel == null) {
            return R.fail("通道不存在: " + channelId);
        }
        String streamKey = deviceId + "_" + channelId;
        // 直接调用底层服务，覆盖原有的 RTP 条目（如果已存在）
        mediaStreamService.preview(device, channelId);
        PlayURL playURL = new PlayURL();
        playURL.setHttpFlv(buildHttpFlvStreamUrl("live", streamKey));
        playURL.setWebrtc(buildWebrtcStreamUrl("live", streamKey));
        return R.ok(playURL);
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
            log.info("webrtcSdp proxy, targetUrl={}, decodedTargetUrl={}", targetUrl, decodedTargetUrl);
            
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
            log.info("webrtcSdp JNA calling, app={}, stream={}, type={}, rtcUrl={}", app, stream, type, rtcUrl);

            String answerSdp = null;
            int maxRetries = 15;
            int retryCount = 0;
            while (retryCount < maxRetries) {
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
                        log.info("webrtcSdp: stream not found in ZLM yet, retrying ({}/{}) for stream {}", retryCount, maxRetries, stream);
                        Thread.sleep(500);
                    } else {
                        throw e;
                    }
                } catch (java.util.concurrent.TimeoutException e) {
                    retryCount++;
                    log.info("webrtcSdp: JNA callback timeout, retrying ({}/{}) for stream {}", retryCount, maxRetries, stream);
                    Thread.sleep(500);
                }
            }

            if (answerSdp == null) {
                return R.fail("代理 WebRTC SDP 请求失败: 流未就绪 (stream not found)");
            }
            return R.ok("操作成功", answerSdp);
        } catch (Exception e) {
            log.error("webrtc sdp proxy failed, targetUrl={}", targetUrl, e);
            return R.fail("代理 WebRTC SDP 请求异常: " + e.getMessage());
        }
    }
}
