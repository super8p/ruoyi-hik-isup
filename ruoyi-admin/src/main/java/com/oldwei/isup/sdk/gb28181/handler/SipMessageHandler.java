package com.oldwei.isup.sdk.gb28181.handler;

import com.oldwei.isup.mapper.GbDeviceAlarmMapper;
import com.oldwei.isup.mapper.GbDeviceChannelMapper;
import com.oldwei.isup.mapper.GbDeviceMapper;
import com.oldwei.isup.model.GbDevice;
import com.oldwei.isup.model.GbDeviceAlarm;
import com.oldwei.isup.model.GbDeviceChannel;
import com.oldwei.isup.sdk.gb28181.DeviceStatusManager;
import com.oldwei.isup.sdk.gb28181.GB28181SipListener;
import com.oldwei.isup.service.GbAlarmNotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sip.RequestEvent;
import javax.sip.SipProvider;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SipMessageHandler {
    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final GbDeviceAlarmMapper alarmMapper;
    private final GbAlarmNotifyService alarmNotifyService;
    private final DeviceStatusManager deviceStatusManager;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private com.oldwei.isup.sdk.service.impl.FRegisterCallBack fRegisterCallBack;

    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private com.oldwei.isup.service.impl.GB28181StreamServiceImpl streamService;

    /**
     * 心跳批量入库队列（对标 wvp KeepaliveNotifyMessageHandler.taskQueue）
     * 收到心跳后先放队列，每10秒批量写库，避免高频写数据库
     */
    private final BlockingQueue<GbDevice> keepaliveQueue = new LinkedBlockingQueue<>();

    private static class CatalogCache {
        long lastTime = System.currentTimeMillis();
        int sumNum;
        List<GbDeviceChannel> channels = new ArrayList<>();
    }

    private final ConcurrentHashMap<String, CatalogCache> catalogCacheMap = new ConcurrentHashMap<>();

    public void handle(RequestEvent requestEvent) {
        try {
            Request request = requestEvent.getRequest();
            SipProvider provider = (SipProvider) requestEvent.getSource();
            MessageFactory messageFactory = GB28181SipListener.getMessageFactory();

            // 获取 XML 消息体内容
            byte[] rawContent = request.getRawContent();
            if (rawContent == null || rawContent.length == 0) {
                Response response = messageFactory.createResponse(Response.OK, request);
                provider.sendResponse(response);
                return;
            }

            // GB28181 规范默认采用 GB2312 编码
            String xml = new String(rawContent, "GB2312");
            if (xml.contains("encoding=\"utf-8\"") || xml.contains("encoding=\"UTF-8\"")) {
                xml = new String(rawContent, "UTF-8");
            }

            Document document = DocumentHelper.parseText(xml);
            Element root = document.getRootElement();
            String cmdType = root.elementTextTrim("CmdType");
            String deviceId = root.elementTextTrim("DeviceID");

            // 从 SIP From 头中解析真正的 DeviceID / ChannelID
            javax.sip.header.FromHeader fromHeader = (javax.sip.header.FromHeader) request.getHeader(javax.sip.header.FromHeader.NAME);
            String sipFromUserId = ((javax.sip.address.SipURI) fromHeader.getAddress().getURI()).getUser();

            // 健壮的设备查询逻辑
            GbDevice device = deviceMapper.selectByDeviceId(sipFromUserId);
            if (device == null) {
                GbDeviceChannel channel = channelMapper.selectByChannelId(sipFromUserId);
                if (channel != null) {
                    device = deviceMapper.selectByDeviceId(channel.getDeviceId());
                }
            }
            if (device == null && deviceId != null) {
                device = deviceMapper.selectByDeviceId(deviceId);
                if (device == null) {
                    GbDeviceChannel channel = channelMapper.selectByChannelId(deviceId);
                    if (channel != null) {
                        device = deviceMapper.selectByDeviceId(channel.getDeviceId());
                    }
                }
            }

            if (device == null) {
                log.warn("[SIP消息] 收到未知设备 (FromHeader={}, XML={}) 的 {} 消息，回复 403 Forbidden 促使其重新注册", sipFromUserId, deviceId, cmdType);
                Response response = messageFactory.createResponse(Response.FORBIDDEN, request);
                provider.sendResponse(response);
                return;
            }

            // 设备存在，返回 200 OK
            Response response = messageFactory.createResponse(Response.OK, request);
            provider.sendResponse(response);

            String resolvedDeviceId = device.getDeviceId();

            if ("KeepAlive".equalsIgnoreCase(cmdType)) {
                // 心跳单独打印详细信息（来源 IP:Port + 原始 XML）
                ViaHeader via = (ViaHeader) request.getHeader(ViaHeader.NAME);
                String srcIp   = (via != null && via.getReceived() != null) ? via.getReceived() : (via != null ? via.getHost() : "unknown");
                int    srcPort = (via != null && via.getRPort() > 0) ? via.getRPort() : (via != null ? via.getPort() : 0);
                log.info("[心跳收包] 来源={}:{}, DeviceID={}, 原始XML:\n{}", srcIp, srcPort, resolvedDeviceId, xml);
                handleKeepAlive(resolvedDeviceId, root, requestEvent);
            } else if ("Catalog".equalsIgnoreCase(cmdType)) {
                log.debug("[Catalog] DeviceID={}, 原始XML:\n{}", resolvedDeviceId, xml);
                handleCatalog(resolvedDeviceId, root);
            } else if ("DeviceInfo".equalsIgnoreCase(cmdType)) {
                log.debug("[DeviceInfo] DeviceID={}, 原始XML:\n{}", resolvedDeviceId, xml);
                handleDeviceInfo(resolvedDeviceId, root);
            } else if ("Alarm".equalsIgnoreCase(cmdType)) {
                log.info("[Alarm] DeviceID={}, 原始XML:\n{}", resolvedDeviceId, xml);
                handleAlarm(resolvedDeviceId, root);
            } else if ("Broadcast".equalsIgnoreCase(cmdType)) {
                log.info("[Broadcast] DeviceID={}, 原始XML:\n{}", resolvedDeviceId, xml);
                handleBroadcast(resolvedDeviceId, root);
            } else {
                log.warn("[未知CmdType={}] DeviceID={}, 原始XML:\n{}", cmdType, resolvedDeviceId, xml);
            }

        } catch (Exception e) {
            log.error("Failed to parse/process SIP XML message", e);
        }
    }

    /**
     * 心跳处理（对标 wvp KeepaliveNotifyMessageHandler.handForDevice）
     * <p>
     * 1. 查找设备，若不存在则忽略（未注册的设备无法识别心跳）
     * 2. 检测 IP/Port 是否变化，若变化则立即更新（对标 wvp 地址变化逻辑）
     * 3. 刷新 keepaliveTimeStamp 并放入批量队列（每10秒写库，避免高频 UPDATE）
     * 4. 通知 DeviceStatusManager 刷新超时时间（实现离线检测）
     * 5. 若设备处于离线状态，立即将其置为在线
     *
     * @param deviceId     心跳 XML 中的 DeviceID
     * @param root         心跳 XML 根元素（用于提取 SN/Status）
     * @param requestEvent 原始 SIP 请求事件（用于获取来源 IP/Port）
     */
    private void handleKeepAlive(String deviceId, Element root, RequestEvent requestEvent) {
        // ★ 打印心跳关键字段
        String sn     = root.elementTextTrim("SN");
        String status = root.elementTextTrim("Status");
        log.info("[心跳详情] DeviceID={}, SN={}, Status={}", deviceId, sn, status);

        GbDevice device = deviceMapper.selectByDeviceId(deviceId);
        if (device == null) {
            log.warn("[心跳] 收到未知设备的心跳（DB中不存在），忽略: {}", deviceId);
            return;
        }

        // ① 检测 IP/Port 是否发生变化（对标 wvp 地址变化检测）
        Request request = requestEvent.getRequest();
        ViaHeader viaHeader = (ViaHeader) request.getHeader(ViaHeader.NAME);
        if (viaHeader != null) {
            String remoteIp = viaHeader.getReceived();
            if (remoteIp == null) remoteIp = viaHeader.getHost();
            int remotePort = viaHeader.getRPort();
            if (remotePort <= 0) remotePort = viaHeader.getPort();

            boolean ipChanged   = remoteIp != null && !remoteIp.equalsIgnoreCase(device.getIp());
            boolean portChanged = remotePort > 0 && remotePort != (device.getPort() != null ? device.getPort() : 0);
            if (ipChanged || portChanged) {
                log.info("[心跳] 设备 {} 地址变化: {}:{} -> {}:{}",
                        deviceId, device.getIp(), device.getPort(), remoteIp, remotePort);
                device.setIp(remoteIp);
                device.setPort(remotePort);
                device.setHostAddress(remoteIp + ":" + remotePort);
            }
        }

        // ② 若设备当前离线，立即标记上线（对标 wvp online 分支）
        if (Boolean.FALSE.equals(device.getOnLine())) {
            log.info("[心跳] 离线设备 {} 收到心跳，恢复上线", deviceId);
            device.setOnLine(true);
            deviceMapper.updateOnlineStatus(deviceId, true);
            try {
                fRegisterCallBack.notifyXiaoanOnlineStatus(deviceId, "online", 1);
            } catch (Exception e) {
                log.error("Failed to notify Xiaoan about GB28181 device online status", e);
            }
        }

        // ③ 更新心跳时间并放入批量队列（延迟10秒写库）
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        device.setKeepaliveTime(sdf.format(new Date()));
        device.setUpdateTime(device.getKeepaliveTime());
        keepaliveQueue.offer(device);

        // ④ 刷新 DeviceStatusManager 超时时间（对标 wvp deviceStatusManager.add）
        int heartBeatInterval = device.getHeartBeatInterval() != null ? device.getHeartBeatInterval() : 60;
        int heartBeatCount    = device.getHeartBeatCount()    != null ? device.getHeartBeatCount()    : 3;
        int expires           = device.getExpires()           != null ? device.getExpires()           : 3600;
        long expiresMs = Math.min(expires, (long) heartBeatInterval * heartBeatCount) * 1000L;
        deviceStatusManager.add(deviceId, expiresMs + System.currentTimeMillis());

        log.info("[心跳] DeviceID={}, SN={}, Status={}, onLine={}, keepaliveTime={}, 下次超时 +{}s",
                deviceId, sn, status, device.getOnLine(), device.getKeepaliveTime(), expiresMs / 1000);
    }

    /**
     * 批量将心跳队列中的设备信息写入数据库（对标 wvp executeUpdateDeviceList）
     * fixedDelay=10s，避免每条心跳都触发一次 UPDATE
     */
    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.SECONDS)
    public void executeUpdateKeepaliveList() {
        if (keepaliveQueue.isEmpty()) {
            return;
        }
        List<GbDevice> batch = new ArrayList<>();
        keepaliveQueue.drainTo(batch);
        // 同一设备可能有多条，只取最新一条（deviceId 去重，保留最后一个）
        Map<String, GbDevice> dedup = new java.util.LinkedHashMap<>();
        for (GbDevice d : batch) {
            dedup.put(d.getDeviceId(), d);
        }
        log.debug("[心跳批量入库] 本批更新 {} 台设备", dedup.size());
        for (GbDevice d : dedup.values()) {
            try {
                deviceMapper.updateKeepalive(d.getDeviceId(), d.getKeepaliveTime(), d.getIp(), d.getPort(), d.getHostAddress());
            } catch (Exception e) {
                log.error("[心跳批量入库] 更新设备 {} 失败", d.getDeviceId(), e);
            }
        }
    }

    private void handleDeviceInfo(String deviceId, Element root) {
        GbDevice device = deviceMapper.selectByDeviceId(deviceId);
        if (device != null) {
            // ★ Fix: 读取设备名称（GB28181 标准字段 DeviceName，部分厂商用 Name）
            String deviceName = root.elementTextTrim("DeviceName");
            if (deviceName == null || deviceName.isEmpty()) {
                deviceName = root.elementTextTrim("Name");
            }
            if (deviceName != null && !deviceName.isEmpty()) {
                device.setName(deviceName);
            }
            String manufacturer = root.elementTextTrim("Manufacturer");
            String model = root.elementTextTrim("Model");
            String firmware = root.elementTextTrim("Firmware");
            // charset 有些设备会在 DeviceInfo 里告知
            String charset = root.elementTextTrim("Charset");
            if (manufacturer != null && !manufacturer.isEmpty()) device.setManufacturer(manufacturer);
            if (model != null && !model.isEmpty()) device.setModel(model);
            if (firmware != null && !firmware.isEmpty()) device.setFirmware(firmware);
            if (charset != null && !charset.isEmpty()) device.setCharset(charset);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            device.setUpdateTime(sdf.format(new Date()));
            deviceMapper.update(device);
            log.info("[DeviceInfo] DeviceID={}, DeviceName={}, Manufacturer={}, Model={}, Firmware={}",
                    deviceId, device.getName(), manufacturer, model, firmware);
        }
    }

    private void handleCatalog(String deviceId, Element root) {
        // Clean up expired cache entries (older than 1 minute)
        long now = System.currentTimeMillis();
        catalogCacheMap.entrySet().removeIf(entry -> now - entry.getValue().lastTime > 60000);

        String sn = root.elementTextTrim("SN");
        String sumNumStr = root.elementTextTrim("SumNum");
        int sumNum = 0;
        if (sumNumStr != null && !sumNumStr.isEmpty()) {
            try {
                sumNum = Integer.parseInt(sumNumStr);
            } catch (NumberFormatException ignored) {}
        }

        if (sumNum == 0) {
            channelMapper.deleteByDeviceId(deviceId);
            log.info("Catalog cleared for GB28181 device: {}", deviceId);
            return;
        }

        String cacheKey = deviceId + ":" + (sn != null ? sn : "default");
        final int finalSumNum = sumNum;
        CatalogCache cache = catalogCacheMap.computeIfAbsent(cacheKey, k -> {
            CatalogCache c = new CatalogCache();
            c.sumNum = finalSumNum;
            return c;
        });
        cache.lastTime = System.currentTimeMillis();

        GbDevice device = deviceMapper.selectByDeviceId(deviceId);
        int dataDeviceId = (device != null) ? device.getId() : 0;

        Element deviceListEl = root.element("DeviceList");
        if (deviceListEl != null) {
            Iterator<Element> it = deviceListEl.elementIterator();
            while (it.hasNext()) {
                Element item = it.next();
                String channelId = item.elementTextTrim("DeviceID");
                if (channelId == null || channelId.isEmpty()) {
                    continue;
                }
                String name = item.elementTextTrim("Name");
                String manufacturer = item.elementTextTrim("Manufacturer");
                String model = item.elementTextTrim("Model");
                String owner = item.elementTextTrim("Owner");
                String civilCode = item.elementTextTrim("CivilCode");
                String address = item.elementTextTrim("Address");
                String parentalStr = item.elementTextTrim("Parental");
                String parentId = item.elementTextTrim("ParentID");
                String safetyWayStr = item.elementTextTrim("SafetyWay");
                String registerWayStr = item.elementTextTrim("RegisterWay");
                String secrecyStr = item.elementTextTrim("Secrecy");
                String status = item.elementTextTrim("Status");
                String longitudeStr = item.elementTextTrim("Longitude");
                String latitudeStr = item.elementTextTrim("Latitude");

                GbDeviceChannel channel = new GbDeviceChannel();
                channel.setGbDeviceId(channelId);
                channel.setDeviceId(deviceId);
                channel.setName(name != null ? name : channelId);
                channel.setManufacturer(manufacturer);
                channel.setModel(model);
                channel.setOwner(owner);
                channel.setCivilCode(civilCode);
                channel.setAddress(address);

                int parentalVal = 0;
                if (parentalStr != null && !parentalStr.isEmpty()) {
                    try {
                        parentalVal = Integer.parseInt(parentalStr);
                    } catch (NumberFormatException ignored) {}
                }
                channel.setParental(parentalVal);
                channel.setParentId(parentId);

                int safetyWayVal = 0;
                if (safetyWayStr != null && !safetyWayStr.isEmpty()) {
                    try {
                        safetyWayVal = Integer.parseInt(safetyWayStr);
                    } catch (NumberFormatException ignored) {}
                }
                channel.setSafetyWay(safetyWayVal);

                int registerWayVal = 1;
                if (registerWayStr != null && !registerWayStr.isEmpty()) {
                    try {
                        registerWayVal = Integer.parseInt(registerWayStr);
                    } catch (NumberFormatException ignored) {}
                }
                channel.setRegisterWay(registerWayVal);

                int secrecyVal = 0;
                if (secrecyStr != null && !secrecyStr.isEmpty()) {
                    try {
                        secrecyVal = Integer.parseInt(secrecyStr);
                    } catch (NumberFormatException ignored) {}
                }
                channel.setSecrecy(secrecyVal);

                String statusStr = "ON";
                if (status != null) {
                    if (status.equalsIgnoreCase("ON") || status.equalsIgnoreCase("ONLINE") || status.equalsIgnoreCase("OK")) {
                        statusStr = "ON";
                    } else if (status.equalsIgnoreCase("OFF") || status.equalsIgnoreCase("OFFLINE")) {
                        statusStr = "OFF";
                    }
                }
                channel.setStatus(statusStr);

                if (longitudeStr != null && !longitudeStr.isEmpty()) {
                    try {
                        channel.setLongitude(Double.parseDouble(longitudeStr));
                    } catch (NumberFormatException ignored) {}
                }
                if (latitudeStr != null && !latitudeStr.isEmpty()) {
                    try {
                        channel.setLatitude(Double.parseDouble(latitudeStr));
                    } catch (NumberFormatException ignored) {}
                }

                // Parse Info sub-element
                Element infoEl = item.element("Info");
                String ptzTypeStr = null;
                String positionTypeStr = null;
                String roomTypeStr = null;
                String useTypeStr = null;
                String supplyLightTypeStr = null;
                String directionTypeStr = null;
                String resolution = null;
                String businessGroupId = item.elementTextTrim("BusinessGroupID");
                if (infoEl != null) {
                    ptzTypeStr = infoEl.elementTextTrim("PTZType");
                    positionTypeStr = infoEl.elementTextTrim("PositionType");
                    roomTypeStr = infoEl.elementTextTrim("RoomType");
                    useTypeStr = infoEl.elementTextTrim("UseType");
                    supplyLightTypeStr = infoEl.elementTextTrim("SupplyLightType");
                    directionTypeStr = infoEl.elementTextTrim("DirectionType");
                    resolution = infoEl.elementTextTrim("Resolution");
                    if (businessGroupId == null || businessGroupId.isEmpty()) {
                        businessGroupId = infoEl.elementTextTrim("BusinessGroupID");
                    }
                }
                if (ptzTypeStr == null || ptzTypeStr.isEmpty()) {
                    ptzTypeStr = item.elementTextTrim("PTZType");
                }

                if (ptzTypeStr != null && !ptzTypeStr.isEmpty()) {
                    try {
                        channel.setPtzType(Integer.parseInt(ptzTypeStr));
                    } catch (NumberFormatException ignored) {}
                }
                if (positionTypeStr != null && !positionTypeStr.isEmpty()) {
                    try {
                        channel.setPositionType(Integer.parseInt(positionTypeStr));
                    } catch (NumberFormatException ignored) {}
                }
                if (roomTypeStr != null && !roomTypeStr.isEmpty()) {
                    try {
                        channel.setRoomType(Integer.parseInt(roomTypeStr));
                    } catch (NumberFormatException ignored) {}
                }
                if (useTypeStr != null && !useTypeStr.isEmpty()) {
                    try {
                        channel.setUseType(Integer.parseInt(useTypeStr));
                    } catch (NumberFormatException ignored) {}
                }
                if (supplyLightTypeStr != null && !supplyLightTypeStr.isEmpty()) {
                    try {
                        channel.setSupplyLightType(Integer.parseInt(supplyLightTypeStr));
                    } catch (NumberFormatException ignored) {}
                }
                if (directionTypeStr != null && !directionTypeStr.isEmpty()) {
                    try {
                        channel.setDirectionType(Integer.parseInt(directionTypeStr));
                    } catch (NumberFormatException ignored) {}
                }
                channel.setResolution(resolution);
                channel.setBusinessGroupId(businessGroupId);

                int channelType = 0;
                if (channelId.length() <= 8) {
                    channelType = 1;
                } else if (channelId.length() == 20) {
                    try {
                        int typeCode = Integer.parseInt(channelId.substring(10, 13));
                        if (typeCode == 215 || typeCode == 216) {
                            channelType = 2;
                            channel.setParental(1);
                        }
                    } catch (Exception ignored) {}
                }
                channel.setChannelType(channelType);

                boolean hasAudio = false;
                if (channelId.length() == 20) {
                    try {
                        int typeCode = Integer.parseInt(channelId.substring(10, 13));
                        if (typeCode == 131 || typeCode == 132 || typeCode == 136 || typeCode == 137 || typeCode == 138) {
                            hasAudio = true;
                        }
                    } catch (Exception ignored) {}
                }
                channel.setHasAudio(hasAudio);

                channel.setDataType(1); // GB28181 Channel type
                channel.setDataDeviceId(dataDeviceId);

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String nowStr = sdf.format(new Date());
                channel.setCreateTime(nowStr);
                channel.setUpdateTime(nowStr);

                cache.channels.add(channel);
            }
        }

        log.info("[Catalog Sync] Device: {}, SN: {}, received count: {}, sumNum: {}", deviceId, sn, cache.channels.size(), cache.sumNum);
        if (cache.channels.size() >= cache.sumNum) {
            catalogCacheMap.remove(cacheKey);
            saveChannels(deviceId, cache.channels);
        }
    }

    private void saveChannels(String deviceId, List<GbDeviceChannel> channelList) {
        org.springframework.transaction.support.TransactionTemplate transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            if (channelList == null || channelList.isEmpty()) {
                return;
            }

            // Query all existing channels for this device from the database
            List<GbDeviceChannel> dbChannels = channelMapper.selectListByDeviceId(deviceId);
            Map<String, GbDeviceChannel> dbChannelMap = new HashMap<>();
            for (GbDeviceChannel ch : dbChannels) {
                dbChannelMap.put(ch.getGbDeviceId(), ch);
            }

            // Prepare lists for batch inserts/updates
            List<GbDeviceChannel> toInsert = new ArrayList<>();
            List<GbDeviceChannel> toUpdate = new ArrayList<>();
            Set<String> newGbDeviceIds = new HashSet<>();

            for (GbDeviceChannel channel : channelList) {
                // Avoid duplicates in the received list itself
                if (!newGbDeviceIds.add(channel.getGbDeviceId())) {
                    continue;
                }

                GbDeviceChannel dbChannel = dbChannelMap.get(channel.getGbDeviceId());
                if (dbChannel != null) {
                    channel.setId(dbChannel.getId());
                    channel.setStreamId(dbChannel.getStreamId());
                    channel.setUpdateTime(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                    toUpdate.add(channel);
                    dbChannelMap.remove(channel.getGbDeviceId());
                } else {
                    channel.setCreateTime(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                    channel.setUpdateTime(channel.getCreateTime());
                    toInsert.add(channel);
                }
            }

            // Remaining elements in dbChannelMap are stale/deleted channels. We delete them!
            for (GbDeviceChannel staleChannel : dbChannelMap.values()) {
                channelMapper.deleteByChannelId(staleChannel.getGbDeviceId());
                log.info("Deleted stale GB28181 channel: {} of device: {}", staleChannel.getGbDeviceId(), deviceId);
                try {
                    fRegisterCallBack.notifyXiaoanOnlineStatus(deviceId, staleChannel.getGbDeviceId(), "offline", 1);
                } catch (Exception ex) {
                    log.error("Failed to notify Xiaoan about stale channel offline status: " + staleChannel.getGbDeviceId(), ex);
                }
            }

            // Insert new ones
            for (GbDeviceChannel channel : toInsert) {
                try {
                    channelMapper.insert(channel);
                    try {
                        fRegisterCallBack.notifyXiaoanOnlineStatus(deviceId, channel.getGbDeviceId(), "online", 1);
                    } catch (Exception ex) {
                        log.error("Failed to notify Xiaoan about new channel online status: " + channel.getGbDeviceId(), ex);
                    }
                } catch (Exception e) {
                    log.error("Failed to insert channel: " + channel.getGbDeviceId(), e);
                }
            }

            // Update existing ones
            for (GbDeviceChannel channel : toUpdate) {
                try {
                    channelMapper.update(channel);
                    try {
                        fRegisterCallBack.notifyXiaoanOnlineStatus(deviceId, channel.getGbDeviceId(), "online", 1);
                    } catch (Exception ex) {
                        log.error("Failed to notify Xiaoan about updated channel online status: " + channel.getGbDeviceId(), ex);
                    }
                } catch (Exception e) {
                    log.error("Failed to update channel: " + channel.getGbDeviceId(), e);
                }
            }

            log.info("Catalog sync completed for GB28181 device: {}. Inserted: {}, Updated: {}, Deleted: {}",
                    deviceId, toInsert.size(), toUpdate.size(), dbChannelMap.size());
        });
    }

    private void handleAlarm(String deviceId, Element root) {
        String channelId = root.elementTextTrim("DeviceID");
        String alarmPriority = root.elementTextTrim("AlarmPriority");
        String alarmMethod = root.elementTextTrim("AlarmMethod");
        String alarmTime = root.elementTextTrim("AlarmTime");
        String alarmDescription = root.elementTextTrim("AlarmDescription");
        String longitudeStr = root.elementTextTrim("Longitude");
        String latitudeStr = root.elementTextTrim("Latitude");
        
        Element infoEl = root.element("Info");
        String alarmType = null;
        if (infoEl != null) {
            alarmType = infoEl.elementTextTrim("AlarmType");
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String nowStr = sdf.format(new Date());

        GbDeviceAlarm alarm = new GbDeviceAlarm();
        alarm.setDeviceId(deviceId);
        alarm.setChannelId(channelId != null ? channelId : deviceId);
        alarm.setAlarmPriority(alarmPriority);
        alarm.setAlarmMethod(alarmMethod);
        alarm.setAlarmTime(alarmTime != null ? alarmTime : nowStr);
        alarm.setAlarmDescription(alarmDescription);
        if (longitudeStr != null && !longitudeStr.isEmpty()) {
            alarm.setLongitude(Double.parseDouble(longitudeStr));
        }
        if (latitudeStr != null && !latitudeStr.isEmpty()) {
            alarm.setLatitude(Double.parseDouble(latitudeStr));
        }
        alarm.setAlarmType(alarmType);
        alarm.setCreateTime(nowStr);

        alarmMapper.insert(alarm);
        log.info("Saved GB28181 Alarm to MySQL: device={}, channel={}, desc={}", deviceId, channelId, alarmDescription);

        // 异步推送给小安警情平台
        try {
            alarmNotifyService.pushAlarm(alarm);
        } catch (Exception e) {
            log.error("Failed to push GB28181 alarm to Xiaoan platform", e);
        }
    }

    private void handleBroadcast(String deviceId, Element root) {
        String channelId = root.elementTextTrim("DeviceID");
        String result = root.elementTextTrim("Result");
        Element infoEl = root.element("Info");
        String reason = infoEl != null ? infoEl.elementTextTrim("Reason") : null;
        log.info("[语音广播回复] deviceId={}, channelId={}, result={}, reason={}", deviceId, channelId, result, reason);
        if (!"OK".equalsIgnoreCase(result)) {
            try {
                streamService.stopTalk(deviceId, channelId);
            } catch (Exception e) {
                log.error("Failed to stop talk for device {} on broadcast failure", deviceId, e);
            }
        }
    }
}
