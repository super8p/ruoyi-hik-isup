package com.oldwei.isup.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sip.*;
import java.util.Properties;

@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "sip")
public class SipConfig {
    private String ip;
    private int port = 5060;
    private String id;
    private String domain;
    private String password;
    private int timeout = 30;

    @Bean(destroyMethod = "stop")
    public SipStack sipStack() throws PeerUnavailableException {
        SipFactory sipFactory = SipFactory.getInstance();
        sipFactory.setPathName("gov.nist");
        Properties properties = new Properties();
        properties.setProperty("javax.sip.STACK_NAME", "GB28181_SIP_STACK");
        // 明确绑定到配置的 IP，防止多网卡环境下绑定到错误接口
        properties.setProperty("javax.sip.IP_ADDRESS", ip);
        // ★ 开启自动 Dialog 支持：INVITE 事务需要此项才能正确管理 Dialog 并发送 ACK
        properties.setProperty("javax.sip.AUTOMATIC_DIALOG_SUPPORT", "on");
        properties.setProperty("gov.nist.javax.sip.LOG_MESSAGE_CONTENT", "true");
        // 临时开启SIP信令日志，确认注册流程后设置为0关闭
        properties.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "16");
        properties.setProperty("gov.nist.javax.sip.THREAD_POOL_SIZE", "16");
        properties.setProperty("gov.nist.javax.sip.REENTRANT_LISTENER", "true");
        log.info("GB28181 SipStack initializing, binding to IP: {}", ip);
        return sipFactory.createSipStack(properties);
    }
}
