package com.oldwei.isup.service.impl;

import com.aizuda.zlm4j.core.ZLMApi;
import com.aizuda.zlm4j.structure.MK_RTP_SERVER;
import com.oldwei.isup.config.HikStreamProperties;
import com.oldwei.isup.config.SipConfig;
import com.oldwei.isup.mapper.GbDeviceChannelMapper;
import com.oldwei.isup.mapper.GbDeviceMapper;
import com.oldwei.isup.model.GbDevice;
import com.oldwei.isup.model.GbDeviceChannel;
import com.oldwei.isup.sdk.gb28181.GB28181SipListener;
import com.oldwei.isup.service.IGB28181StreamService;
import com.oldwei.isup.util.WebFluxHttpUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.sip.*;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.*;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class GB28181StreamServiceImpl implements IGB28181StreamService {
    private final SipConfig sipConfig;
    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final ZLMApi zlmApi;
    private final HikStreamProperties hikStreamProperties;
    private final GB28181SipListener sipListener;

    @Autowired
    @Lazy
    private com.oldwei.isup.sdk.gb28181.SipSender sipSender;

    // RTP 端口分配管理
    private static final int RTP_PORT_START = 35000;
    private static final int RTP_PORT_END = 35100;
    private final Map<Integer, Boolean> allocatedPorts = new ConcurrentHashMap<>();

    // 会话管理器映射
    private final Map<String, GB28181Session> sessions = new ConcurrentHashMap<>();

    @Data
    public static class GB28181Session {
        private String deviceId;
        private String channelId;
        private int rtpPort;
        private MK_RTP_SERVER rtpServer;
        private Dialog dialog;
        private String callId;
        private String ssrc;
        private boolean isTalk = false; // 是否为语音对讲
        /** 异步等待 INVITE 200 OK 后提供 ssrcHex（供 Controller 构建 WebRTC URL） */
        private CompletableFuture<String> ssrcFuture;
    }

    private synchronized int allocateRtpPort() {
        for (int port = RTP_PORT_START; port <= RTP_PORT_END; port++) {
            if (!allocatedPorts.getOrDefault(port, false)) {
                allocatedPorts.put(port, true);
                log.info("GB28181 allocated RTP port: {}", port);
                return port;
            }
        }
        log.error("No GB28181 RTP ports available in range {}-{}", RTP_PORT_START, RTP_PORT_END);
        return -1;
    }

    private synchronized void releaseRtpPort(int port) {
        if (allocatedPorts.remove(port) != null) {
            log.info("GB28181 released RTP port: {}", port);
        }
    }

    @Override
    public CompletableFuture<String> startPlay(String deviceId, String channelId) {
        String streamKey = deviceId + "_" + channelId;
        if (sessions.containsKey(streamKey)) {
            log.info("GB28181 play already in progress for stream: {}, ignore.", streamKey);
            // 已存在的 Session 直接返回其 ssrcHex
            GB28181Session exist = sessions.get(streamKey);
            String ssrcHex = Long.toHexString(Long.parseLong(exist.getSsrc())).toUpperCase();
            return CompletableFuture.completedFuture(ssrcHex);
        }

        GbDevice device = deviceMapper.selectByDeviceId(deviceId);
        if (device == null || !device.getOnLine()) {
            log.warn("GB28181 play failed: Device {} is offline or not found.", deviceId);
            return CompletableFuture.failedFuture(new RuntimeException("设备离线或不存在: " + deviceId));
        }

        GbDeviceChannel channel = channelMapper.selectByChannelId(channelId);
        if (channel == null) {
            log.warn("GB28181 play failed: Channel {} not found.", channelId);
            return CompletableFuture.failedFuture(new RuntimeException("通道不存在: " + channelId));
        }

        int rtpPort = allocateRtpPort();
        if (rtpPort == -1) {
            log.error("GB28181 play failed: unable to allocate RTP port.");
            return CompletableFuture.failedFuture(new RuntimeException("无可用 RTP 端口"));
        }

        // 创建 Future，等待 INVITE 200 OK 后 complete
        CompletableFuture<String> ssrcFuture = new CompletableFuture<>();

        try {
            // 1. 生成 10 位 SSRC (Live 视频首位为 0)
            String ssrcSeq = String.format("%05d", (int) (Math.random() * 100000));
            String ssrc = "0" + sipConfig.getId().substring(3, 8) + ssrcSeq;
            String ssrcHex = Long.toHexString(Long.parseLong(ssrc)).toUpperCase();

            // 2. 动态决定媒体传输模式与 SDP 字段
            int tcpMode = 0; // 默认 UDP
            String mLine = "m=video " + rtpPort + " RTP/AVP 96\r\n";
            String aSetup = "";

            String streamMode = device.getStreamMode();
            if ("TCP-PASSIVE".equalsIgnoreCase(streamMode)) {
                tcpMode = 1;
                mLine = "m=video " + rtpPort + " TCP/RTP/AVP 96\r\n";
                aSetup = "a=setup:passive\r\n";
            } else if ("TCP-ACTIVE".equalsIgnoreCase(streamMode)) {
                tcpMode = 2;
                mLine = "m=video " + rtpPort + " TCP/RTP/AVP 96\r\n";
                aSetup = "a=setup:active\r\n";
            }

            // 在 ZLMediaKit 创建 RTP 服务接收端口，根据 tcpMode 进行绑定
            MK_RTP_SERVER mkRtpServer = zlmApi.mk_rtp_server_create2((short) rtpPort, tcpMode, hikStreamProperties.getDomain(), "rtp", ssrcHex);
            if (mkRtpServer == null) {
                log.error("Failed to create ZLMediaKit RTP server on port {}", rtpPort);
                releaseRtpPort(rtpPort);
                return CompletableFuture.failedFuture(new RuntimeException("ZLM RTP 服务创建失败"));
            }

            // 3. 构建 SDP 描述信息
            String sdp = "v=0\r\n" +
                    "o=" + sipConfig.getId() + " 0 0 IN IP4 " + sipConfig.getIp() + "\r\n" +
                    "s=Play\r\n" +
                    "c=IN IP4 " + sipConfig.getIp() + "\r\n" +
                    "t=0 0\r\n" +
                    mLine +
                    "a=rtpmap:96 PS/90000\r\n" +
                    "a=recvonly\r\n" +
                    aSetup +
                    "y=" + ssrc + "\r\n";

            // 4. 构建 SIP INVITE 请求
            SipProvider provider = "tcp".equalsIgnoreCase(device.getTransport()) ?
                    sipListener.getTcpProvider() : sipListener.getUdpProvider();
            AddressFactory addressFactory = GB28181SipListener.getAddressFactory();
            HeaderFactory headerFactory = GB28181SipListener.getHeaderFactory();
            MessageFactory messageFactory = GB28181SipListener.getMessageFactory();

            SipURI requestUri = addressFactory.createSipURI(channelId, device.getIp() + ":" + device.getPort());
            CallIdHeader callIdHeader = provider.getNewCallId();
            CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(1L, Request.INVITE);

            SipURI fromUri = addressFactory.createSipURI(sipConfig.getId(), sipConfig.getIp() + ":" + sipConfig.getPort());
            Address fromAddress = addressFactory.createAddress(fromUri);
            FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, UUID.randomUUID().toString().replace("-", ""));

            SipURI toUri = addressFactory.createSipURI(channelId, device.getIp() + ":" + device.getPort());
            Address toAddress = addressFactory.createAddress(toUri);
            ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

            SipURI contactUri = addressFactory.createSipURI(sipConfig.getId(), sipConfig.getIp() + ":" + sipConfig.getPort());
            Address contactAddress = addressFactory.createAddress(contactUri);
            ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);

            MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);

            ArrayList<ViaHeader> viaHeaders = new ArrayList<>();
            ViaHeader viaHeader = headerFactory.createViaHeader(sipConfig.getIp(), sipConfig.getPort(), device.getTransport(), null);
            viaHeaders.add(viaHeader);

            Request request = messageFactory.createRequest(requestUri, Request.INVITE, callIdHeader, cSeqHeader,
                    fromHeader, toHeader, viaHeaders, maxForwards);
            request.addHeader(contactHeader);

            // GB28181 规范必填 Subject 头
            Header subjectHeader = headerFactory.createHeader("Subject", channelId + ":" + ssrc + "," + sipConfig.getId() + ":0");
            request.addHeader(subjectHeader);

            ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application", "sdp");
            request.setContent(sdp, contentTypeHeader);

            // 5. 发送 stateful 事务请求并记录 Session
            ClientTransaction transaction = provider.getNewClientTransaction(request);

            GB28181Session session = new GB28181Session();
            session.setDeviceId(deviceId);
            session.setChannelId(channelId);
            session.setRtpPort(rtpPort);
            session.setRtpServer(mkRtpServer);
            session.setCallId(callIdHeader.getCallId());
            session.setSsrc(ssrc);
            session.setDialog(transaction.getDialog());
            session.setTalk(false);
            session.setSsrcFuture(ssrcFuture);  // ★ 存入 Future

            sessions.put(callIdHeader.getCallId(), session);
            sessions.put(streamKey, session);

            transaction.sendRequest();
            log.info("Sent INVITE for GB28181 preview: device={}, channel={}, rtpPort={}, ssrc={}", deviceId, channelId, rtpPort, ssrc);

        } catch (Exception e) {
            log.error("Failed to start GB28181 play", e);
            releaseRtpPort(rtpPort);
            ssrcFuture.completeExceptionally(e);
        }

        return ssrcFuture;
    }

    @Override
    public void stopPlay(String deviceId, String channelId) {
        String streamKey = deviceId + "_" + channelId;
        GB28181Session session = sessions.remove(streamKey);
        if (session == null) {
            log.warn("GB28181 stopPlay failed: No active session found for {}", streamKey);
            return;
        }

        sessions.remove(session.getCallId());

        try {
            Dialog dialog = session.getDialog();
            if (dialog != null) {
                Request byeRequest = dialog.createRequest(Request.BYE);
                GbDevice device = deviceMapper.selectByDeviceId(session.getDeviceId());
                if (device != null) {
                    if (byeRequest.getRequestURI() instanceof SipURI) {
                        SipURI byeUri = (SipURI) byeRequest.getRequestURI();
                        if (device.getIp() != null) {
                            byeUri.setHost(device.getIp());
                        }
                        if (device.getPort() != null && device.getPort() > 0) {
                            byeUri.setPort(device.getPort());
                        }
                    }
                }
                SipProvider provider = (device != null && "tcp".equalsIgnoreCase(device.getTransport())) ? 
                        sipListener.getTcpProvider() : sipListener.getUdpProvider();
                ClientTransaction transaction = provider.getNewClientTransaction(byeRequest);
                dialog.sendRequest(transaction);
                log.info("Sent BYE to stop GB28181 stream: {}", streamKey);
            }
        } catch (Exception e) {
            log.error("Failed to send BYE for session " + streamKey, e);
        } finally {
            if (session.getRtpServer() != null) {
                zlmApi.mk_rtp_server_release(session.getRtpServer());
            }
            releaseRtpPort(session.getRtpPort());
            log.info("Stopped GB28181 preview stream and released resources: {}", streamKey);
        }
    }

    @Override
    public void startTalk(String deviceId, String channelId) {
        String sessionKey = deviceId + "_talk";
        if (sessions.containsKey(sessionKey)) {
            log.info("GB28181 voice intercom already in progress for device: {}, ignore.", deviceId);
            return;
        }

        GbDevice device = deviceMapper.selectByDeviceId(deviceId);
        if (device == null || !device.getOnLine()) {
            log.warn("GB28181 intercom failed: Device {} is offline or not found.", deviceId);
            return;
        }

        // 1. 发送广播 MESSAGE 通知
        sipSender.sendBroadcastNotify(device, channelId);

        // 2. 分配本地音频流接收端口
        int rtpPort = allocateRtpPort();
        if (rtpPort == -1) {
            log.error("GB28181 intercom failed: unable to allocate RTP port.");
            return;
        }

        try {
            // 3. 生成 10 位 SSRC (对讲首位为 0)
            String ssrcSeq = String.format("%05d", (int) (Math.random() * 100000));
            String ssrc = "0" + sipConfig.getId().substring(3, 8) + ssrcSeq;

            // 4. 构建音频 SDP，定义 PCMA/8000 双向/单向流协商
            String sdp = "v=0\n" +
                    "o=" + sipConfig.getId() + " 0 0 IN IP4 " + sipConfig.getIp() + "\n" +
                    "s=Play\n" +
                    "c=IN IP4 " + sipConfig.getIp() + "\n" +
                    "t=0 0\n" +
                    "m=audio " + rtpPort + " RTP/AVP 8\n" +
                    "a=rtpmap:8 PCMA/8000\n" +
                    "a=sendrecv\n" +
                    "y=" + ssrc + "\n";

            // 5. 构建 SIP INVITE
            SipProvider provider = "tcp".equalsIgnoreCase(device.getTransport()) ? 
                    sipListener.getTcpProvider() : sipListener.getUdpProvider();
            AddressFactory addressFactory = GB28181SipListener.getAddressFactory();
            HeaderFactory headerFactory = GB28181SipListener.getHeaderFactory();
            MessageFactory messageFactory = GB28181SipListener.getMessageFactory();

            SipURI requestUri = addressFactory.createSipURI(channelId, device.getIp() + ":" + device.getPort());
            CallIdHeader callIdHeader = provider.getNewCallId();
            CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(1L, Request.INVITE);

            SipURI fromUri = addressFactory.createSipURI(sipConfig.getId(), sipConfig.getIp() + ":" + sipConfig.getPort());
            Address fromAddress = addressFactory.createAddress(fromUri);
            FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, UUID.randomUUID().toString().replace("-", ""));

            SipURI toUri = addressFactory.createSipURI(channelId, device.getIp() + ":" + device.getPort());
            Address toAddress = addressFactory.createAddress(toUri);
            ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

            SipURI contactUri = addressFactory.createSipURI(sipConfig.getId(), sipConfig.getIp() + ":" + sipConfig.getPort());
            Address contactAddress = addressFactory.createAddress(contactUri);
            ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);

            MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);

            ArrayList<ViaHeader> viaHeaders = new ArrayList<>();
            ViaHeader viaHeader = headerFactory.createViaHeader(sipConfig.getIp(), sipConfig.getPort(), device.getTransport(), null);
            viaHeaders.add(viaHeader);

            Request request = messageFactory.createRequest(requestUri, Request.INVITE, callIdHeader, cSeqHeader, 
                    fromHeader, toHeader, viaHeaders, maxForwards);
            request.addHeader(contactHeader);

            // 对讲的 Subject 后缀为 2
            Header subjectHeader = headerFactory.createHeader("Subject", channelId + ":" + ssrc + ",2");
            request.addHeader(subjectHeader);

            ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application", "sdp");
            request.setContent(sdp, contentTypeHeader);

            ClientTransaction transaction = provider.getNewClientTransaction(request);

            GB28181Session session = new GB28181Session();
            session.setDeviceId(deviceId);
            session.setChannelId(channelId);
            session.setRtpPort(rtpPort);
            session.setCallId(callIdHeader.getCallId());
            session.setSsrc(ssrc);
            session.setDialog(transaction.getDialog());
            session.setTalk(true);

            sessions.put(callIdHeader.getCallId(), session);
            sessions.put(sessionKey, session);

            transaction.sendRequest();
            log.info("Sent INVITE for GB28181 talk: device={}, channel={}, rtpPort={}, ssrc={}", deviceId, channelId, rtpPort, ssrc);

        } catch (Exception e) {
            log.error("Failed to start GB28181 talkback", e);
            releaseRtpPort(rtpPort);
        }
    }

    @Override
    public void stopTalk(String deviceId, String channelId) {
        String sessionKey = deviceId + "_talk";
        GB28181Session session = sessions.remove(sessionKey);
        if (session == null) {
            log.warn("GB28181 stopTalk failed: No active intercom session for {}", sessionKey);
            return;
        }

        sessions.remove(session.getCallId());

        try {
            Dialog dialog = session.getDialog();
            if (dialog != null) {
                Request byeRequest = dialog.createRequest(Request.BYE);
                GbDevice device = deviceMapper.selectByDeviceId(session.getDeviceId());
                if (device != null) {
                    if (byeRequest.getRequestURI() instanceof SipURI) {
                        SipURI byeUri = (SipURI) byeRequest.getRequestURI();
                        if (device.getIp() != null) {
                            byeUri.setHost(device.getIp());
                        }
                        if (device.getPort() != null && device.getPort() > 0) {
                            byeUri.setPort(device.getPort());
                        }
                    }
                }
                SipProvider provider = (device != null && "tcp".equalsIgnoreCase(device.getTransport())) ? 
                        sipListener.getTcpProvider() : sipListener.getUdpProvider();
                ClientTransaction transaction = provider.getNewClientTransaction(byeRequest);
                dialog.sendRequest(transaction);
                log.info("Sent BYE to stop GB28181 talk session: {}", sessionKey);
            }
        } catch (Exception e) {
            log.error("Failed to send BYE for talk session " + sessionKey, e);
        } finally {
            triggerZlmStopSendRtp(deviceId);
            releaseRtpPort(session.getRtpPort());
            log.info("Stopped GB28181 talk session and released resources: {}", sessionKey);
        }
    }

    private void triggerZlmStopSendRtp(String deviceId) {
        String zlmUrl = "http://127.0.0.1:7788/index/api/stopSendRtp";
        Map<String, Object> params = new HashMap<>();
        params.put("secret", "hik12345");
        params.put("vhost", "__defaultVhost__");
        params.put("app", "live");
        params.put("stream", deviceId + "_talk");

        WebFluxHttpUtil.postAsync(zlmUrl, params, String.class).subscribe(
                resp -> log.info("ZLMediaKit stopSendRtp success: {}", resp),
                err -> log.error("ZLMediaKit stopSendRtp error: {}", err.getMessage())
        );
    }

    public void handleResponse(ResponseEvent responseEvent) {
        Response response = responseEvent.getResponse();
        int statusCode = response.getStatusCode();
        CallIdHeader callIdHeader = (CallIdHeader) response.getHeader(CallIdHeader.NAME);
        if (callIdHeader == null) {
            return;
        }

        GB28181Session session = sessions.get(callIdHeader.getCallId());
        if (session == null) {
            return;
        }

        CSeqHeader cSeqHeader = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
        if (cSeqHeader != null && Request.INVITE.equals(cSeqHeader.getMethod())) {
            if (statusCode == Response.OK) {
                try {
                    ClientTransaction clientTransaction = responseEvent.getClientTransaction();
                    if (clientTransaction != null) {
                        Dialog dialog = clientTransaction.getDialog();
                        session.setDialog(dialog);

                        // 发送 ACK 报文
                        Request ackRequest = dialog.createAck(cSeqHeader.getSeqNumber());
                        if (ackRequest.getRequestURI() instanceof SipURI) {
                            SipURI ackUri = (SipURI) ackRequest.getRequestURI();
                            GbDevice device = deviceMapper.selectByDeviceId(session.getDeviceId());
                            if (device != null && device.getIp() != null) {
                                ackUri.setHost(device.getIp());
                                if (device.getPort() != null && device.getPort() > 0) {
                                    ackUri.setPort(device.getPort());
                                }
                            }
                        }
                        dialog.sendAck(ackRequest);
                        log.info("Received 200 OK for INVITE, sent ACK: {}", session.getCallId());

                        // ★ INVITE OK 时完成 ssrcFuture（对标 wvp: ZLM 注册流为 rtp/{SSRC十六进制大写}）
                        if (!session.isTalk() && session.getSsrcFuture() != null) {
                            String ssrcHex = Long.toHexString(Long.parseLong(session.getSsrc())).toUpperCase();
                            log.info("[GB28181预览] INVITE 200 OK，ssrc={}, ssrcHex={}, ZLM流为 rtp/{}",
                                    session.getSsrc(), ssrcHex, ssrcHex);
                            session.getSsrcFuture().complete(ssrcHex);
                        }

                        // 如果该 Session 属于语音对讲
                        if (session.isTalk()) {
                            byte[] rawContent = response.getRawContent();
                            if (rawContent != null && rawContent.length > 0) {
                                String responseSdp = new String(rawContent, "UTF-8");
                                log.info("Intercom device SDP response:\n{}", responseSdp);

                                String dstIp = parseIpFromSdp(responseSdp);
                                int dstPort = parsePortFromSdp(responseSdp);

                                if (dstIp != null && dstPort > 0) {
                                    triggerZlmStartSendRtp(session.getDeviceId(), dstIp, dstPort, session.getSsrc());
                                } else {
                                    log.error("Failed to parse audio destination IP/Port from device SDP.");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to process INVITE OK response", e);
                    if (session.getSsrcFuture() != null) {
                        session.getSsrcFuture().completeExceptionally(e);
                    }
                }
            } else if (statusCode >= 300) {
                log.warn("GB28181 INVITE failed with status: {}", statusCode);
                if (session.getSsrcFuture() != null && !session.getSsrcFuture().isDone()) {
                    session.getSsrcFuture().completeExceptionally(new RuntimeException("INVITE failed: " + statusCode));
                }
                if (session.isTalk()) {
                    stopTalk(session.getDeviceId(), session.getChannelId());
                } else {
                    stopPlay(session.getDeviceId(), session.getChannelId());
                }
            }
        }
    }

    private String parseIpFromSdp(String sdp) {
        for (String line : sdp.split("\n")) {
            line = line.trim();
            if (line.startsWith("c=")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    return parts[parts.length - 1];
                }
            }
        }
        return null;
    }

    private int parsePortFromSdp(String sdp) {
        for (String line : sdp.split("\n")) {
            line = line.trim();
            if (line.startsWith("m=audio")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    try {
                        return Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            }
        }
        return -1;
    }

    private void triggerZlmStartSendRtp(String deviceId, String dstIp, int dstPort, String ssrc) {
        long ssrcLong = Long.parseLong(ssrc);
        String ssrcHex = Long.toHexString(ssrcLong).toUpperCase();

        String zlmUrl = "http://127.0.0.1:7788/index/api/startSendRtp";
        Map<String, Object> params = new HashMap<>();
        params.put("secret", "hik12345");
        params.put("vhost", "__defaultVhost__");
        params.put("app", "live");
        params.put("stream", deviceId + "_talk");
        params.put("dst_url", dstIp);
        params.put("dst_port", dstPort);
        params.put("ssrc", ssrcHex);
        params.put("is_udp", 1);
        params.put("pt", 8); // PCMA 音频格式 PayloadType 8
        params.put("use_ps", 0); // 直接推 PCMA 音频 ES 流，不要封装为 PS

        log.info("Triggering ZLMediaKit startSendRtp to {}:{} SSRC:{}", dstIp, dstPort, ssrcHex);
        WebFluxHttpUtil.postAsync(zlmUrl, params, String.class).subscribe(
                resp -> log.info("ZLMediaKit startSendRtp success: {}", resp),
                err -> log.error("ZLMediaKit startSendRtp error: {}", err.getMessage())
        );
    }
}
