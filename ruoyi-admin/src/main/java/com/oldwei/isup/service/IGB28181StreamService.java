package com.oldwei.isup.service;

import java.util.concurrent.CompletableFuture;

public interface IGB28181StreamService {
    /** 发起实时预览 INVITE，返回 Future，完成时提供 ZLM 流的 ssrcHex（供 Controller 构建 WebRTC URL） */
    CompletableFuture<String> startPlay(String deviceId, String channelId);
    void stopPlay(String deviceId, String channelId);
    void startTalk(String deviceId, String channelId);
    void stopTalk(String deviceId, String channelId);
}
