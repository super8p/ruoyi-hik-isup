package com.oldwei.isup.controller;

import com.oldwei.isup.config.HikStreamProperties;
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
            return "https://" + hikStreamProperties.getDomain() + "/index/api/webrtc?app=" + prefix + "&stream=" + streamKey + "&type=play&secret=hik12345";
        }
        return "http://" + hikStreamProperties.getHttp().getIp() + ":"
                + hikStreamProperties.getHttp().getPort() + "/index/api/webrtc?app=" + prefix + "&stream=" + streamKey + "&type=play&secret=hik12345";
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
            String decodedTargetUrl = URLDecoder.decode(targetUrl, StandardCharsets.UTF_8);
            cn.hutool.http.HttpResponse resp = cn.hutool.http.HttpRequest.post(decodedTargetUrl)
                    .timeout(30000)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .body(sdp)
                    .execute();

            String body = resp.body();
            if (resp.getStatus() != 200) {
                return R.fail("流媒体服务请求失败，HTTP 状态码: " + resp.getStatus() + ", 响应: " + body);
            }

            if (body != null && body.trim().startsWith("{")) {
                com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(body);
                if (json.containsKey("sdp")) {
                    return R.ok(json.getString("sdp"));
                }
                if (json.containsKey("code") && json.getIntValue("code") != 0) {
                    return R.fail("流媒体服务返回错误: " + json.getString("msg"));
                }
            }
            return R.ok(body);
        } catch (Exception e) {
            log.error("webrtc sdp proxy failed, targetUrl={}", targetUrl, e);
            return R.fail("代理 WebRTC SDP 请求异常: " + e.getMessage());
        }
    }
}
