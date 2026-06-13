package com.oldwei.isup.mapper;

import com.oldwei.isup.model.GbDevice;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface GbDeviceMapper {
    GbDevice selectByDeviceId(@Param("deviceId") String deviceId);

    List<GbDevice> selectList(GbDevice device);

    int insert(GbDevice device);

    int update(GbDevice device);

    int deleteByDeviceId(@Param("deviceId") String deviceId);

    int updateOnlineStatus(@Param("deviceId") String deviceId, @Param("onLine") Boolean onLine);

    int updateKeepalive(@Param("deviceId") String deviceId,
                         @Param("keepaliveTime") String keepaliveTime,
                         @Param("ip") String ip,
                         @Param("port") Integer port,
                         @Param("hostAddress") String hostAddress);
}
