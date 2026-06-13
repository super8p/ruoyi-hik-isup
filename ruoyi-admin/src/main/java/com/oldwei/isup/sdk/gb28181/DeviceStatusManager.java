package com.oldwei.isup.sdk.gb28181;

import com.oldwei.isup.mapper.GbDeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 设备心跳超时检测管理器（对标 wvp-GB28181-pro 的 DeviceStatusManager）
 * <p>
 * wvp 使用 Redis ZSet（score=过期时间戳）+ 每秒定时检查；
 * 本项目无 Redis 依赖，改为 ConcurrentHashMap<deviceId, expireTimestamp> + 定时任务实现等价逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceStatusManager {

    private final GbDeviceMapper deviceMapper;

    /**
     * key: deviceId, value: 预计过期时间戳(ms)
     * 每次收到心跳时调用 {@link #add} 刷新；超时后定时任务将设备标为离线
     */
    private final ConcurrentHashMap<String, Long> deviceExpireMap = new ConcurrentHashMap<>();

    /**
     * 添加/刷新设备的心跳过期时间。
     * expireTime = System.currentTimeMillis() + min(expires, heartBeatInterval * heartBeatCount) * 1000L
     */
    public void add(String deviceId, long expireTimeMs) {
        deviceExpireMap.put(deviceId, expireTimeMs);
    }

    public void remove(String deviceId) {
        deviceExpireMap.remove(deviceId);
    }

    public boolean contains(String deviceId) {
        return deviceExpireMap.containsKey(deviceId);
    }

    public void clear() {
        deviceExpireMap.clear();
    }

    public Set<String> getAll() {
        return Collections.unmodifiableSet(deviceExpireMap.keySet());
    }

    /**
     * 每秒检查一次，找出心跳已超时的设备，将其标为离线。
     * 对标 wvp DeviceStatusManager.expirationCheck()（fixedDelay=1s, initialDelay=10s）
     */
    @Scheduled(fixedDelay = 1, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    public void expirationCheck() {
        long now = System.currentTimeMillis();
        List<String> expiredIds = new ArrayList<>();
        for (Map.Entry<String, Long> entry : deviceExpireMap.entrySet()) {
            if (entry.getValue() <= now) {
                expiredIds.add(entry.getKey());
            }
        }

        if (expiredIds.isEmpty()) {
            return;
        }

        log.info("[心跳超时] 检测到 {} 台设备超时，准备离线: {}", expiredIds.size(), expiredIds);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String nowStr = sdf.format(new Date());

        for (String deviceId : expiredIds) {
            deviceExpireMap.remove(deviceId);
            try {
                deviceMapper.updateOnlineStatus(deviceId, false);
                log.info("[心跳超时] 设备 {} 已标记为离线 (keepalive timeout at {})", deviceId, nowStr);
            } catch (Exception e) {
                log.error("[心跳超时] 更新设备 {} 离线状态失败", deviceId, e);
            }
        }
    }
}
