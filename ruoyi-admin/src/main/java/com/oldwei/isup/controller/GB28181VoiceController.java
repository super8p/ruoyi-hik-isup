package com.oldwei.isup.controller;

import com.oldwei.isup.model.R;
import com.oldwei.isup.service.IGB28181StreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/gb28181/{deviceId}/voice")
@RequiredArgsConstructor
public class GB28181VoiceController {

    private final IGB28181StreamService gbStreamService;

    @PostMapping("/talk")
    public R<String> startTalk(@PathVariable("deviceId") String deviceId, @RequestParam("channelId") String channelId) {
        log.info("API request to start GB28181 audio intercom: device={}, channel={}", deviceId, channelId);
        gbStreamService.startTalk(deviceId, channelId);
        return R.ok("GB28181 voice intercom signaling initiated.");
    }

    @PostMapping("/stop")
    public R<String> stopTalk(@PathVariable("deviceId") String deviceId, @RequestParam("channelId") String channelId) {
        log.info("API request to stop GB28181 audio intercom: device={}, channel={}", deviceId, channelId);
        gbStreamService.stopTalk(deviceId, channelId);
        return R.ok("GB28181 voice intercom signaling stopped.");
    }
}
