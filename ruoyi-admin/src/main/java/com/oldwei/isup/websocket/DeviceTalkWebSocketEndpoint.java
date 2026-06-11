package com.oldwei.isup.websocket;

import com.oldwei.isup.sdk.StreamManager;
import com.oldwei.isup.sdk.service.IHikISUPStream;
import com.oldwei.isup.sdk.structure.BYTE_ARRAY;
import com.oldwei.isup.sdk.structure.NET_EHOME_VOICETALK_DATA;
import com.oldwei.isup.service.DeviceCacheService;
import com.oldwei.isup.service.impl.MediaStreamServiceImpl;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 设备语音对讲 WebSocket 端点
 *
 * @author oldwei
 */
@Slf4j
@Component
@ServerEndpoint("/api/devices/{deviceId}/talk")
public class DeviceTalkWebSocketEndpoint {

    private static DeviceCacheService deviceCacheService;
    private static MediaStreamServiceImpl mediaStreamService;
    private static IHikISUPStream hikISUPStream;

    @Autowired
    public void setDependencies(DeviceCacheService deviceCacheService,
                                MediaStreamServiceImpl mediaStreamService,
                                IHikISUPStream hikISUPStream) {
        DeviceTalkWebSocketEndpoint.deviceCacheService = deviceCacheService;
        DeviceTalkWebSocketEndpoint.mediaStreamService = mediaStreamService;
        DeviceTalkWebSocketEndpoint.hikISUPStream = hikISUPStream;
    }

    private Integer loginId;
    private int sessionId = -1;

    @OnOpen
    public void onOpen(Session session, @PathParam("deviceId") String deviceId) {
        log.info("WebSocket 对讲发起连接, deviceId: {}", deviceId);
        deviceCacheService.getByDeviceId(deviceId).ifPresentOrElse(device -> {
            this.loginId = device.getLoginId();
            if (this.loginId == null || this.loginId == -1) {
                closeSession(session, "设备未注册或未上线");
                return;
            }

            // NVR/摄像机数字对讲通道默认规则通常是：byStartDTalkChan + 3
            int talkChannel = 3; 
            byte encodingType = 3; // 3: G.711A (与浏览器 WebRTC 兼容性最好)

            // 发起对讲协商
            this.sessionId = mediaStreamService.startVoiceIntercom(this.loginId, talkChannel, encodingType);
            if (this.sessionId == -1) {
                closeSession(session, "CMS 开启对讲服务失败");
                return;
            }

            // 等待设备连接 Voice SMS (最长等待5秒)
            int waitCount = 0;
            while (StreamManager.lVoiceLinkHandle == -1 && waitCount < 50) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}
                waitCount++;
            }

            if (StreamManager.lVoiceLinkHandle == -1) {
                closeSession(session, "设备连接语音流媒体服务超时");
                return;
            }

            try {
                session.getBasicRemote().sendText("READY");
                log.info("对讲链路就绪, lVoiceLinkHandle: {}", StreamManager.lVoiceLinkHandle);
            } catch (IOException e) {
                log.error("发送就绪信号异常", e);
            }

        }, () -> closeSession(session, "设备不存在"));
    }

    @OnMessage
    public void onMessage(ByteBuffer message, Session session) {
        if (StreamManager.lVoiceLinkHandle == -1) return;

        // 接收来自浏览器的 G.711A 音频数据 (通常以 160 字节/帧 传输，对应 20ms 的 G.711 音频包)
        byte[] audioBytes = new byte[message.remaining()];
        message.get(audioBytes);

        // 构造海康对讲结构体发送数据
        BYTE_ARRAY ptrVoiceByte = new BYTE_ARRAY(audioBytes.length);
        System.arraycopy(audioBytes, 0, ptrVoiceByte.byValue, 0, audioBytes.length);
        ptrVoiceByte.write();

        NET_EHOME_VOICETALK_DATA talkData = new NET_EHOME_VOICETALK_DATA();
        talkData.pData = ptrVoiceByte.getPointer();
        talkData.dwDataLen = audioBytes.length;
        talkData.write();

        if (hikISUPStream.NET_ESTREAM_SendVoiceTalkData(StreamManager.lVoiceLinkHandle, talkData) <= -1) {
            log.error("对讲数据发送失败, error: {}", hikISUPStream.NET_ESTREAM_GetLastError());
        }
    }

    @OnClose
    public void onClose(Session session) {
        log.info("对讲连接关闭");
        if (loginId != null && sessionId != -1) {
            mediaStreamService.stopVoiceIntercom(loginId, sessionId, StreamManager.lVoiceLinkHandle);
            StreamManager.lVoiceLinkHandle = -1;
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("对讲连接异常", error);
    }

    private void closeSession(Session session, String reason) {
        try {
            session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, reason));
        } catch (IOException ignored) {}
    }
}
