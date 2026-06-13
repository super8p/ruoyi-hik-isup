package com.oldwei.isup.sdk.gb28181;

import com.oldwei.isup.config.SipConfig;
import com.oldwei.isup.sdk.gb28181.handler.SipRegisterHandler;
import com.oldwei.isup.sdk.gb28181.handler.SipMessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sip.*;
import javax.sip.address.AddressFactory;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
public class GB28181SipListener implements SipListener, InitializingBean, DisposableBean {
    private final SipConfig sipConfig;
    private final SipStack sipStack;
    
    private final SipRegisterHandler registerHandler;
    private final SipMessageHandler messageHandler;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.oldwei.isup.service.impl.GB28181StreamServiceImpl streamService;

    private static AddressFactory addressFactory;
    private static HeaderFactory headerFactory;
    private static MessageFactory messageFactory;

    private SipProvider udpProvider;
    private SipProvider tcpProvider;
    private ListeningPoint udpListeningPoint;
    private ListeningPoint tcpListeningPoint;

    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    @Override
    public void afterPropertiesSet() throws Exception {
        SipFactory sipFactory = SipFactory.getInstance();
        addressFactory = sipFactory.createAddressFactory();
        headerFactory = sipFactory.createHeaderFactory();
        messageFactory = sipFactory.createMessageFactory();

        // 尝试从 SipStack 中获取可能已存在的监听点以防止重启冲突
        java.util.Iterator<?> lpIt = sipStack.getListeningPoints();
        while (lpIt.hasNext()) {
            ListeningPoint lp = (ListeningPoint) lpIt.next();
            if (lp.getPort() == sipConfig.getPort() && lp.getTransport().equalsIgnoreCase("udp")) {
                udpListeningPoint = lp;
            }
            if (lp.getPort() == sipConfig.getPort() && lp.getTransport().equalsIgnoreCase("tcp")) {
                tcpListeningPoint = lp;
            }
        }

        if (udpListeningPoint == null) {
            udpListeningPoint = sipStack.createListeningPoint(sipConfig.getIp(), sipConfig.getPort(), "udp");
        }

        if (tcpListeningPoint == null) {
            tcpListeningPoint = sipStack.createListeningPoint(sipConfig.getIp(), sipConfig.getPort(), "tcp");
        }

        try {
            udpProvider = sipStack.createSipProvider(udpListeningPoint);
        } catch (ObjectInUseException e) {
            log.warn("UDP Provider already attached to listening point, attempting to reuse/retrieve.");
            java.util.Iterator<?> it = sipStack.getSipProviders();
            while (it.hasNext()) {
                SipProvider prov = (SipProvider) it.next();
                if (prov.getListeningPoint("udp") != null && 
                    prov.getListeningPoint("udp").getPort() == sipConfig.getPort()) {
                    udpProvider = prov;
                    break;
                }
            }
            if (udpProvider == null) {
                throw e;
            }
        }
        udpProvider.addSipListener(this);

        try {
            tcpProvider = sipStack.createSipProvider(tcpListeningPoint);
        } catch (ObjectInUseException e) {
            log.warn("TCP Provider already attached to listening point, attempting to reuse/retrieve.");
            java.util.Iterator<?> it = sipStack.getSipProviders();
            while (it.hasNext()) {
                SipProvider prov = (SipProvider) it.next();
                if (prov.getListeningPoint("tcp") != null && 
                    prov.getListeningPoint("tcp").getPort() == sipConfig.getPort()) {
                    tcpProvider = prov;
                    break;
                }
            }
            if (tcpProvider == null) {
                throw e;
            }
        }
        tcpProvider.addSipListener(this);

        log.info("GB28181 SIP Server listening on {}:{} (UDP/TCP)", sipConfig.getIp(), sipConfig.getPort());
    }

    @Override
    public void destroy() throws Exception {
        log.info("Destroying GB28181 SIP Listener and releasing ports/listeners...");
        if (executor != null) {
            executor.shutdownNow();
        }

        if (udpProvider != null) {
            try {
                udpProvider.removeSipListener(this);
            } catch (Exception e) {
                log.debug("Error removing UDP sip listener: {}", e.getMessage());
            }
            try {
                sipStack.deleteSipProvider(udpProvider);
            } catch (Exception e) {
                log.debug("Error deleting UDP sip provider: {}", e.getMessage());
            }
        }

        if (tcpProvider != null) {
            try {
                tcpProvider.removeSipListener(this);
            } catch (Exception e) {
                log.debug("Error removing TCP sip listener: {}", e.getMessage());
            }
            try {
                sipStack.deleteSipProvider(tcpProvider);
            } catch (Exception e) {
                log.debug("Error deleting TCP sip provider: {}", e.getMessage());
            }
        }

        if (udpListeningPoint != null) {
            try {
                sipStack.deleteListeningPoint(udpListeningPoint);
            } catch (Exception e) {
                log.debug("Error deleting UDP listening point: {}", e.getMessage());
            }
        }

        if (tcpListeningPoint != null) {
            try {
                sipStack.deleteListeningPoint(tcpListeningPoint);
            } catch (Exception e) {
                log.debug("Error deleting TCP listening point: {}", e.getMessage());
            }
        }
    }

    public static AddressFactory getAddressFactory() {
        return addressFactory;
    }

    public static HeaderFactory getHeaderFactory() {
        return headerFactory;
    }

    public static MessageFactory getMessageFactory() {
        return messageFactory;
    }

    public SipProvider getUdpProvider() {
        return udpProvider;
    }

    public SipProvider getTcpProvider() {
        return tcpProvider;
    }

    @Override
    public void processRequest(RequestEvent requestEvent) {
        executor.execute(() -> {
            try {
                String method = requestEvent.getRequest().getMethod();
                log.info("Received SIP Request: {}", method);
                if (Request.REGISTER.equals(method)) {
                    registerHandler.handle(requestEvent);
                } else if (Request.MESSAGE.equals(method)) {
                    messageHandler.handle(requestEvent);
                } else {
                    log.warn("Unsupported request method: {}", method);
                }
            } catch (Exception e) {
                log.error("Error processing SIP request", e);
            }
        });
    }

    @Override
    public void processResponse(ResponseEvent responseEvent) {
        log.info("Received SIP Response: {}", responseEvent.getResponse().getStatusCode());
        try {
            streamService.handleResponse(responseEvent);
        } catch (Exception e) {
            log.error("Failed to forward SIP response to stream service", e);
        }
    }

    @Override
    public void processTimeout(TimeoutEvent timeoutEvent) {
        log.warn("SIP Transaction Timeout: {}", timeoutEvent.getTimeout().toString());
    }

    @Override
    public void processIOException(IOExceptionEvent exceptionEvent) {
        log.error("SIP IOException: host={}, port={}", exceptionEvent.getHost(), exceptionEvent.getPort());
    }

    @Override
    public void processTransactionTerminated(TransactionTerminatedEvent transactionTerminatedEvent) {
        // 心跳/非事务逻辑无需处理
    }

    @Override
    public void processDialogTerminated(DialogTerminatedEvent dialogTerminatedEvent) {
        // 会话结束逻辑
    }
}
