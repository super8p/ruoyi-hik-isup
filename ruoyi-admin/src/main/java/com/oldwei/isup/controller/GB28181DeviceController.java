package com.oldwei.isup.controller;

import com.oldwei.isup.mapper.GbDeviceChannelMapper;
import com.oldwei.isup.mapper.GbDeviceMapper;
import com.oldwei.isup.model.GbDevice;
import com.oldwei.isup.model.GbDeviceChannel;
import com.oldwei.isup.model.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * GB28181 设备与通道查询控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/gb28181/devices")
@RequiredArgsConstructor
public class GB28181DeviceController {

    private final GbDeviceMapper gbDeviceMapper;
    private final GbDeviceChannelMapper gbDeviceChannelMapper;

    /**
     * 获取国标设备列表
     */
    @GetMapping
    public R<List<GbDevice>> getDevices(GbDevice device) {
        log.info("Requesting GB28181 device list: {}", device);
        List<GbDevice> devices = gbDeviceMapper.selectList(device);
        return R.ok(devices);
    }

    /**
     * 获取国标设备通道列表
     */
    @GetMapping("/{deviceId}/channels")
    public R<List<GbDeviceChannel>> getChannels(@PathVariable("deviceId") String deviceId) {
        log.info("Requesting GB28181 channels for device: {}", deviceId);
        List<GbDeviceChannel> channels = gbDeviceChannelMapper.selectListByDeviceId(deviceId);
        return R.ok(channels);
    }
}
