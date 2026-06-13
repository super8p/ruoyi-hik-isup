package com.oldwei.isup.sdk.gb28181;

import com.oldwei.isup.config.SipConfig;
import com.oldwei.isup.model.GbDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.SipProvider;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.*;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import java.util.ArrayList;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SipSender {
    private final SipConfig sipConfig;
    private final GB28181SipListener sipListener;

    public void sendCatalogRequest(GbDevice device) {
        String sn = String.valueOf((int) ((Math.random() * 9 + 1) * 100000));
        String xml = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\n" +
                "<Query>\n" +
                "  <CmdType>Catalog</CmdType>\n" +
                "  <SN>" + sn + "</SN>\n" +
                "  <DeviceID>" + device.getDeviceId() + "</DeviceID>\n" +
                "</Query>\n";
        sendXmlMessage(device, xml);
        log.info("Sent Catalog query to device: {}", device.getDeviceId());
    }

    public void sendDeviceInfoRequest(GbDevice device) {
        String sn = String.valueOf((int) ((Math.random() * 9 + 1) * 100000));
        String xml = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\n" +
                "<Query>\n" +
                "  <CmdType>DeviceInfo</CmdType>\n" +
                "  <SN>" + sn + "</SN>\n" +
                "  <DeviceID>" + device.getDeviceId() + "</DeviceID>\n" +
                "</Query>\n";
        sendXmlMessage(device, xml);
        log.info("Sent DeviceInfo query to device: {}", device.getDeviceId());
    }

    public void sendBroadcastNotify(GbDevice device, String channelId) {
        String sn = String.valueOf((int) ((Math.random() * 9 + 1) * 100000));
        String xml = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\n" +
                "<Notify>\n" +
                "  <CmdType>Broadcast</CmdType>\n" +
                "  <SN>" + sn + "</SN>\n" +
                "  <SourceID>" + sipConfig.getId() + "</SourceID>\n" +
                "  <TargetID>" + channelId + "</TargetID>\n" +
                "</Notify>\n";
        sendXmlMessage(device, xml);
        log.info("Sent Broadcast Notify to device: {}, channel: {}", device.getDeviceId(), channelId);
    }

    private void sendXmlMessage(GbDevice device, String xml) {
        try {
            SipProvider provider = "tcp".equalsIgnoreCase(device.getTransport()) ? 
                    sipListener.getTcpProvider() : sipListener.getUdpProvider();
            AddressFactory addressFactory = GB28181SipListener.getAddressFactory();
            HeaderFactory headerFactory = GB28181SipListener.getHeaderFactory();
            MessageFactory messageFactory = GB28181SipListener.getMessageFactory();

            SipURI requestUri = addressFactory.createSipURI(device.getDeviceId(), device.getIp() + ":" + device.getPort());
            CallIdHeader callIdHeader = provider.getNewCallId();
            CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(1L, Request.MESSAGE);

            SipURI fromUri = addressFactory.createSipURI(sipConfig.getId(), sipConfig.getIp() + ":" + sipConfig.getPort());
            Address fromAddress = addressFactory.createAddress(fromUri);
            FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, UUID.randomUUID().toString().replace("-", ""));

            SipURI toUri = addressFactory.createSipURI(device.getDeviceId(), device.getIp() + ":" + device.getPort());
            Address toAddress = addressFactory.createAddress(toUri);
            ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

            MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);

            ArrayList<ViaHeader> viaHeaders = new ArrayList<>();
            ViaHeader viaHeader = headerFactory.createViaHeader(sipConfig.getIp(), sipConfig.getPort(), device.getTransport(), null);
            viaHeaders.add(viaHeader);

            Request request = messageFactory.createRequest(requestUri, Request.MESSAGE, callIdHeader, cSeqHeader, 
                    fromHeader, toHeader, viaHeaders, maxForwards);

            ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("Application", "MANSCDP+xml");
            request.setContent(xml.getBytes("GB2312"), contentTypeHeader);

            provider.sendRequest(request);

        } catch (Exception e) {
            log.error("Failed to send SIP MESSAGE request to device: " + device.getDeviceId(), e);
        }
    }
}
