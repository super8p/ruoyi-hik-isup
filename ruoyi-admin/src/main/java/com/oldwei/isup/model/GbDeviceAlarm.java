package com.oldwei.isup.model;

import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

@Data
public class GbDeviceAlarm implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Integer id;
    private String deviceId;
    private String channelId;
    private String alarmPriority;
    private String alarmMethod;
    private String alarmTime;
    private String alarmDescription;
    private Double longitude;
    private Double latitude;
    private String alarmType;
    private String createTime;
}
