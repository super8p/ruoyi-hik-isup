package com.oldwei.isup.mapper;

import com.oldwei.isup.model.GbDeviceAlarm;
import java.util.List;

public interface GbDeviceAlarmMapper {
    int insert(GbDeviceAlarm alarm);

    List<GbDeviceAlarm> selectList(GbDeviceAlarm alarm);
}
