package com.oldwei.isup.model;

import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

@Data
public class GbDevice implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Integer id;
    private String deviceId;
    private String name;
    private String manufacturer;
    private String model;
    private String firmware;
    private String transport;
    private String streamMode;
    private Boolean onLine;
    private String registerTime;
    private String keepaliveTime;
    private String ip;
    private String createTime;
    private String updateTime;
    private Integer port;
    private Integer expires;
    private Integer subscribeCycleForCatalog;
    private Integer subscribeCycleForMobilePosition;
    private Integer mobilePositionSubmissionInterval;
    private Integer subscribeCycleForAlarm;
    private String hostAddress;
    private String charset;
    private Boolean ssrcCheck;
    private String geoCoordSys;
    private String mediaServerId;
    private String customName;
    private String sdpIp;
    private String localIp;
    private String password;
    private Boolean asMessageChannel;
    private Integer heartBeatInterval;
    private Integer heartBeatCount;
    private Integer positionCapability;
    private Boolean broadcastPushAfterAck;
    private String serverId;
}
