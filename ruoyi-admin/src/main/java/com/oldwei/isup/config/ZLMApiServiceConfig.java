package com.oldwei.isup.config;

//import com.aizuda.zlm4j.core.ZLMApi;

import com.aizuda.zlm4j.callback.IMKWebRtcGetAnwerSdpCallBack;
import com.aizuda.zlm4j.core.ZLMApi;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ZLMApiServiceConfig {

    private final HikIsupProperties hikIsupProperties;

    @Bean
    public ZLMApi zlmApi() {
        ZLMApi zlmApi = Native.load("mk_api", ZLMApi.class);
        //初始化zmk服务器
        zlmApi.mk_env_init2(1, 4, 1, null, 0, 0, null, 0, null, "hik12345");
        //创建http服务器 0:失败,非0:端口号
        short httpPort = zlmApi.mk_http_server_start((short) 7788, 0);
        //创建rtsp服务器 0:失败,非0:端口号
        short rtspPort = zlmApi.mk_rtsp_server_start((short) 7554, 0);
        //创建rtmp服务器 0:失败,非0:端口号
        short rtmpPort = zlmApi.mk_rtmp_server_start((short) 7935, 0);
        //创建rtc服务器 0:失败,非0:端口号
        short rtcPort = zlmApi.mk_rtc_server_start((short) 8000);
        if (httpPort > 0 && rtspPort > 0 && rtmpPort > 0 && rtcPort > 0) {
            log.info("ZLM服务启动成功 - HTTP端口:{}, RTSP端口:{}, RTMP端口:{}, RTC端口:{}",
                    httpPort, rtspPort, rtmpPort, rtcPort);
        } else {
            throw new RuntimeException("ZLM服务端口启动失败 - HTTP端口:" + httpPort + ", RTSP端口:" + rtspPort + ", RTMP端口:" + rtmpPort + ", RTC端口:" + rtcPort);
        }
        log.info("ZLM API initialized.");

        // 预热 ZLM WebRTC 回调线程：延时 3 秒后发一个哑请求，激活 JNA 回调线程。
        // 否则服务重启后第一次 WebRTC SDP 请求会因回调线程未就绪而超时（需要 ISUP 先跑一次才行）。
        Thread warmupThread = new Thread(() -> {
            try {
                Thread.sleep(3000);
                log.info("[ZLM预热] 开始发送哑 WebRTC 请求以激活 JNA 回调线程...");
                String dummyRtcUrl = "rtc://__defaultVhost__/live/__warmup__?app=live&stream=__warmup__&type=play";
                String dummySdp = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n";
                // 用数组持有引用防止 GC 在回调触发前回收对象
                final Object[] cbHolder = new Object[1];
                IMKWebRtcGetAnwerSdpCallBack dummyCb = new IMKWebRtcGetAnwerSdpCallBack() {
                    @Override
                    public void invoke(Pointer user_data, String answer_sdp, String err) {
                        // 哑请求结果不重要，只是为了激活内部线程
                        log.info("[ZLM预热] WebRTC JNA 回调线程已激活, err={}", err);
                    }
                };
                cbHolder[0] = dummyCb;
                zlmApi.mk_webrtc_get_answer_sdp(null, dummyCb, "play", dummySdp, dummyRtcUrl);
                Thread.sleep(2000); // 等待回调触发后再释放引用
                cbHolder[0] = null;
                log.info("[ZLM预热] 完成。");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[ZLM预热] 预热请求异常（可忽略）: {}", e.getMessage());
            }
        });
        warmupThread.setName("zlm-webrtc-warmup");
        warmupThread.setDaemon(true);
        warmupThread.start();

        return zlmApi;
    }
}
