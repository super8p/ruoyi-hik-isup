package com.oldwei.isup.service;

import com.oldwei.isup.config.HikPlatformProperties;
import com.oldwei.isup.model.GbDeviceAlarm;
import com.oldwei.isup.util.WebFluxHttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GbAlarmNotifyService {
    private final HikPlatformProperties platformProperties;

    public void pushAlarm(GbDeviceAlarm alarm) {
        String xiaoanUrl = platformProperties.getXiaoanNotifyUrl();
        if (StringUtils.isBlank(xiaoanUrl) || alarm == null || StringUtils.isBlank(alarm.getDeviceId())) {
            return;
        }

        String alarmDesc = alarm.getAlarmDescription();
        String alarmType = alarm.getAlarmType();
        boolean isRecovery = false;
        
        // 判定是否为恢复/消警事件
        if (alarmDesc != null && (alarmDesc.contains("恢复") || alarmDesc.contains("消警") || alarmDesc.contains("正常") || alarmDesc.contains("恢复正常"))) {
            isRecovery = true;
        }
        if (alarmType != null && (alarmType.contains("Recovery") || alarmType.contains("reset") || alarmType.contains("Restore"))) {
            isRecovery = true;
        }

        // 映射为平台通用的告警事件类型
        String mappedType = "motionDetection"; // 默认移动侦测
        if (alarmDesc != null) {
            if (alarmDesc.contains("烟雾") || alarmDesc.contains("Smoke")) {
                mappedType = isRecovery ? "SmokeAlarmRecovery" : "smokeAlarm";
            } else if (alarmDesc.contains("离岗") || alarmDesc.contains("offDuty")) {
                mappedType = isRecovery ? "backDuty" : "offDuty";
            } else if (alarmDesc.contains("火") || alarmDesc.contains("Fire")) {
                mappedType = isRecovery ? "FirePointAlarmRecovery" : "fireSmartFireDetect";
            }
        } else if (alarmType != null) {
            if (alarmType.equalsIgnoreCase("Smoke")) {
                mappedType = isRecovery ? "SmokeAlarmRecovery" : "smokeAlarm";
            } else if (alarmType.equalsIgnoreCase("Duty")) {
                mappedType = isRecovery ? "backDuty" : "offDuty";
            } else if (alarmType.equalsIgnoreCase("Fire")) {
                mappedType = isRecovery ? "FirePointAlarmRecovery" : "fireSmartFireDetect";
            }
        }

        if (!isRecovery) {
            String targetUrl = xiaoanUrl + "/warning";
            Map<String, Object> payload = new HashMap<>();
            payload.put("DeviceId", alarm.getDeviceId());
            payload.put("AlarmType", mappedType);
            payload.put("AlarmDescription", alarmDesc != null ? alarmDesc : "GB28181 Alarm");
            payload.put("AlarmTime", alarm.getAlarmTime());

            WebFluxHttpUtil.postAsync(targetUrl, payload, String.class).subscribe(
                    resp -> log.info("Xiaoan warning (GB28181) notification success: {}", resp),
                    err -> log.error("Xiaoan warning (GB28181) notification error: {}", err.getMessage())
            );
        } else {
            String targetUrl = xiaoanUrl + "/warningComplete";
            Map<String, Object> payload = new HashMap<>();
            payload.put("DeviceId", alarm.getDeviceId());
            payload.put("AlarmType", mappedType);
            payload.put("AlarmDescription", alarmDesc != null ? alarmDesc : "GB28181 Alarm Restored");
            payload.put("AlarmTime", alarm.getAlarmTime());
            
            Map<String, Object> taskInfo = new HashMap<>();
            taskInfo.put("State", "finished");
            payload.put("TaskInfo", taskInfo);

            WebFluxHttpUtil.postAsync(targetUrl, payload, String.class).subscribe(
                    resp -> log.info("Xiaoan warningComplete (GB28181) notification success: {}", resp),
                    err -> log.error("Xiaoan warningComplete (GB28181) notification error: {}", err.getMessage())
            );
        }
    }
}
