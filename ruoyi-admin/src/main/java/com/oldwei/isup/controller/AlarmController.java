package com.oldwei.isup.controller;

import com.ruoyi.common.annotation.Anonymous;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private static final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @Anonymous
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(3600000L); // 1 hour
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        
        // 发送一条连接成功的欢迎消息
        try {
            emitter.send(SseEmitter.event().name("connect").data("connected"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        
        return emitter;
    }

    public static void sendAlarm(String type, String message) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("alarm")
                        .data(new AlarmEvent(type, message)));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    public static class AlarmEvent {
        private String type;
        private String message;

        public AlarmEvent(String type, String message) {
            this.type = type;
            this.message = message;
        }

        public String getType() { return type; }
        public String getMessage() { return message; }
    }
}
