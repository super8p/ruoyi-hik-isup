package com.oldwei.isup.sdk.gb28181.handler;

import com.oldwei.isup.config.SipConfig;
import com.oldwei.isup.mapper.GbDeviceMapper;
import com.oldwei.isup.model.GbDevice;
import com.oldwei.isup.sdk.gb28181.GB28181SipListener;
import cn.hutool.crypto.SecureUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.RequestEvent;
import javax.sip.SipProvider;
import javax.sip.header.*;
import javax.sip.address.SipURI;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SipRegisterHandler {
    private final SipConfig sipConfig;
    private final GbDeviceMapper deviceMapper;
    private final com.oldwei.isup.sdk.gb28181.DeviceStatusManager deviceStatusManager;

    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private com.oldwei.isup.sdk.gb28181.SipSender sipSender;

    private static final java.util.concurrent.ExecutorService catalogExecutor = 
        java.util.concurrent.Executors.newFixedThreadPool(2);

    public void handle(RequestEvent requestEvent) {
        try {
            Request request = requestEvent.getRequest();
            SipProvider provider = (SipProvider) requestEvent.getSource();
            MessageFactory messageFactory = GB28181SipListener.getMessageFactory();
            HeaderFactory headerFactory = GB28181SipListener.getHeaderFactory();

            // 获取Authorization头
            AuthorizationHeader authHeader = (AuthorizationHeader) request.getHeader(AuthorizationHeader.NAME);
            
            // 获取注册设备国标ID
            FromHeader fromHeader = (FromHeader) request.getHeader(FromHeader.NAME);
            SipURI fromUri = (SipURI) fromHeader.getAddress().getURI();
            String deviceId = fromUri.getUser().trim();

            // 获取注册设备IP与端口
            ViaHeader viaHeader = (ViaHeader) request.getHeader(ViaHeader.NAME);
            String deviceIp = viaHeader.getReceived();
            if (deviceIp == null) {
                deviceIp = viaHeader.getHost();
            }
            int devicePort = viaHeader.getRPort();
            if (devicePort <= 0) {
                devicePort = viaHeader.getPort();
            }

            // 获取信令传输协议
            String transport = viaHeader.getTransport();

            // 判断Expires头 (0代表注销)
            ExpiresHeader expiresHeader = (ExpiresHeader) request.getHeader(ExpiresHeader.NAME);
            int expires = (expiresHeader != null) ? expiresHeader.getExpires() : 3600;

            if (authHeader == null) {
                // 第一步：发送401带nonce
                Response response = messageFactory.createResponse(Response.UNAUTHORIZED, request);
                String nonce = UUID.randomUUID().toString().replace("-", "");
                WWWAuthenticateHeader wwwHeader = headerFactory.createWWWAuthenticateHeader("Digest");
                wwwHeader.setParameter("realm", sipConfig.getDomain());
                wwwHeader.setParameter("nonce", nonce);
                wwwHeader.setParameter("algorithm", "MD5");
                response.addHeader(wwwHeader);
                provider.sendResponse(response);
                log.info("Device {} registration initiated, replying 401 with nonce.", deviceId);
                return;
            }

            // 第二步：校验MD5 Response
            String username = authHeader.getUsername();
            String realm = authHeader.getRealm();
            String nonce = authHeader.getNonce();
            String uri = authHeader.getURI().toString();
            String clientResponse = authHeader.getResponse();

            // 计算期望的MD5 Response
            String A1 = SecureUtil.md5(username + ":" + realm + ":" + sipConfig.getPassword());
            String A2 = SecureUtil.md5("REGISTER:" + uri);
            String expectedResponse = SecureUtil.md5(A1 + ":" + nonce + ":" + A2);

            if (!expectedResponse.equalsIgnoreCase(clientResponse)) {
                log.warn("Device {} registration failed: digest authentication mismatch.", deviceId);
                Response response = messageFactory.createResponse(Response.FORBIDDEN, request);
                provider.sendResponse(response);
                return;
            }

            // 认证通过
            Response response = messageFactory.createResponse(Response.OK, request);
            ExpiresHeader exp = headerFactory.createExpiresHeader(expires);
            response.addHeader(exp);
            response.addHeader(headerFactory.createDateHeader(java.util.Calendar.getInstance()));
            provider.sendResponse(response);

            // 更新或保存设备状态
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String nowStr = sdf.format(new Date());

            GbDevice device = deviceMapper.selectByDeviceId(deviceId);
            boolean isNew = (device == null);
            if (isNew) {
                device = new GbDevice();
                device.setDeviceId(deviceId);
                device.setName(deviceId);            // 临时用ID当名称，等DeviceInfo响应后更新
                device.setCharset("GB2312");          // 默认编码
                device.setStreamMode("UDP");          // 默认流模式（对标 wvp TCP-PASSIVE，摄像机UDP推流）
                device.setHeartBeatInterval(60);      // 默认心跳间隔 60s
                device.setHeartBeatCount(3);          // 默认心跳超时次数 3次
                device.setPositionCapability(0);      // 默认不支持位置上报
                device.setOnLine(false);
                device.setCreateTime(nowStr);
            }
            
            if (expires <= 0) {
                device.setOnLine(false);
                device.setUpdateTime(nowStr);
                // ② 注销时从超时检测中移除（对标 wvp cleanOfflineDevice）
                deviceStatusManager.remove(deviceId);
                log.info("Device {} logged out successfully.", deviceId);
            } else {
                device.setOnLine(true);
                device.setIp(deviceIp);
                device.setPort(devicePort);
                device.setHostAddress(deviceIp + ":" + devicePort);  // ★ ip:port 拼接
                device.setSdpIp(deviceIp);                            // ★ sdp 中使用的 ip
                device.setTransport(transport);
                device.setRegisterTime(nowStr);
                device.setKeepaliveTime(nowStr);
                device.setExpires(expires);
                device.setUpdateTime(nowStr);
                // ① 注册时就加入超时检测（对标 wvp online() 最后一步）
                // heartBeatInterval/heartBeatCount 首次注册时可能为空，默认心跳间隔 60s×3次超时
                int hbInterval = device.getHeartBeatInterval() != null ? device.getHeartBeatInterval() : 60;
                int hbCount    = device.getHeartBeatCount()    != null ? device.getHeartBeatCount()    : 3;
                long expiresMs = Math.min(expires, (long) hbInterval * hbCount) * 1000L;
                deviceStatusManager.add(deviceId, expiresMs + System.currentTimeMillis());
                log.info("Device {} registered successfully from {}:{}, heartbeat timeout={}ms.", deviceId, deviceIp, devicePort, expiresMs);
            }

            try {
                if (isNew) {
                    deviceMapper.insert(device);
                } else {
                    deviceMapper.update(device);
                }
            } catch (Exception e) {
                log.warn("Insert/Update failed for device {}, attempting backup update (handling possible duplicate entry): {}", deviceId, e.getMessage());
                try {
                    deviceMapper.update(device);
                } catch (Exception ex) {
                    log.error("Backup update also failed for device " + deviceId, ex);
                }
            }

            // 如果是新注册上线，异步拉取 Catalog 与 DeviceInfo
            if (expires > 0) {
                final GbDevice queryDevice = device;
                catalogExecutor.submit(() -> {
                    try {
                        // 稍作延迟，确保设备已接收完 OK 响应
                        Thread.sleep(1000);
                        log.info("Automatically querying DeviceInfo and Catalog for registered device: {}", deviceId);
                        sipSender.sendDeviceInfoRequest(queryDevice);
                        sipSender.sendCatalogRequest(queryDevice);
                    } catch (Exception e) {
                        log.error("Failed to query catalog/deviceInfo for " + deviceId, e);
                    }
                });
            }

        } catch (Exception e) {
            log.error("Register process failed", e);
        }
    }
}
