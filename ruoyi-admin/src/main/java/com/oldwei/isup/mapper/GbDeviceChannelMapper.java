package com.oldwei.isup.mapper;

import com.oldwei.isup.model.GbDeviceChannel;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface GbDeviceChannelMapper {
    GbDeviceChannel selectByChannelId(@Param("gbDeviceId") String gbDeviceId);

    List<GbDeviceChannel> selectListByDeviceId(@Param("deviceId") String deviceId);

    int insert(GbDeviceChannel channel);

    int update(GbDeviceChannel channel);

    int deleteByDeviceId(@Param("deviceId") String deviceId);

    int deleteByChannelId(@Param("gbDeviceId") String gbDeviceId);
}
