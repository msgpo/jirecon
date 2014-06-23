/*
 * Jirecon, the Jitsi recorder container.
 * 
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.jitsi.jirecon.task.session;

import java.io.IOException;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.SourcePacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.ContentPacketExtension.*;
import net.java.sip.communicator.service.protocol.OperationFailedException;
import net.java.sip.communicator.util.Logger;

import org.jitsi.jirecon.dtlscontrol.SrtpControlManager;
import org.jitsi.jirecon.extension.MediaExtension;
import org.jitsi.jirecon.task.JireconTaskSharingInfo;
import org.jitsi.jirecon.transport.JireconTransportManager;
import org.jitsi.jirecon.utils.JinglePacketParser;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.format.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.packet.MUCUser;

/**
 * This class is responsible for managing a Jingle session and extract some
 * information which could be used by others.
 * 
 * @author lishunyang
 * 
 */
public class JireconSessionImpl
    implements JireconSession
{
    private XMPPConnection connection;

    private MultiUserChat conference;

    private JireconTaskSharingInfo sharingInfo;

    private static final Logger logger = Logger
        .getLogger(JireconSessionImpl.class);

    private final static String NICK_KEY = "JIRECON_NICKNAME";

    private String NICK = "default";

    private List<JireconSessionPacketListener> packetListeners =
        new ArrayList<JireconSessionPacketListener>();

    private final long MAX_WAIT_TIME = 20000;

    public JireconSessionImpl(XMPPConnection connection, String conferenceJid,
        JireconTaskSharingInfo sharingInfo)
    {
        logger.setLevelDebug();
        this.sharingInfo = sharingInfo;
        ConfigurationService configuration = LibJitsi.getConfigurationService();
        this.NICK = configuration.getString(NICK_KEY);
        this.connection = connection;
        this.sharingInfo.setMucJid(conferenceJid);

        addPacketSendingListener();
        addPacketReceivingListener();

        JireconSessionPacketListener packetListener =
            new JireconSessionPacketListener()
            {
                @Override
                public void handlePacket(Packet packet)
                {
                    if (Presence.class == packet.getClass())
                        handlePresencePacket((Presence) packet);
                }
            };

        addPacketListener(packetListener);
    }

    @Override
    public JingleIQ connect(JireconTransportManager transportManager,
        SrtpControlManager srtpControlManager)
        throws XMPPException,
        OperationFailedException,
        IOException
    {
        joinMUC();
        JingleIQ initIq = waitForInitPacket();
        recordSessionInfo(initIq);
        sendAck(initIq);
        sendAccpetPacket(initIq, transportManager, srtpControlManager);
        waitForAckPacket();

        return initIq;
    }

    @Override
    public void disconnect(Reason reason, String reasonText)
    {
        sendByePacket(reason, reasonText);
        leaveMUC();
    }

    private void joinMUC() throws XMPPException
    {
        logger.info("joinMUC");

        conference = new MultiUserChat(connection, sharingInfo.getMucJid());
        conference.join(NICK);
    }

    private void leaveMUC()
    {
        logger.info("leaveMUC");

        if (null != conference)
        {
            conference.leave();
        }
    }

    private void sendAccpetPacket(JingleIQ initIq,
        JireconTransportManager transportManager,
        SrtpControlManager srtpControlManager)
    {
        logger.info("sendAcceptPacket");

        JingleIQ acceptPacket =
            createAcceptPacket(initIq, transportManager, srtpControlManager);
        connection.sendPacket(acceptPacket);
    }

    private void sendAck(JingleIQ jiq)
    {
        logger.info("sendAck");
        connection.sendPacket(IQ.createResultIQ(jiq));
    }

    private void sendByePacket(Reason reason, String reasonText)
    {
        logger.info("sendByePacket");

        connection.sendPacket(JinglePacketFactory.createSessionTerminate(
            sharingInfo.getLocalJid(), sharingInfo.getRemoteJid(),
            sharingInfo.getSid(), reason, reasonText));
    }

    private void recordSessionInfo(JingleIQ jiq)
    {
        sharingInfo.setLocalJid(jiq.getTo());
        sharingInfo.setRemoteJid(jiq.getFrom());
        sharingInfo.setSid(jiq.getSID());
        sharingInfo.setFormatAndPayloadTypes(JinglePacketParser
            .getFormatAndDynamicPTs(jiq));
    }

    private JingleIQ waitForInitPacket() throws OperationFailedException
    {
        logger.info("waitForInitPacket");

        final List<JingleIQ> resultList = new ArrayList<JingleIQ>();
        final Object waitForInitPacketSyncRoot = new Object();
        JireconSessionPacketListener packetListener =
            new JireconSessionPacketListener()
            {
                @Override
                public void handlePacket(Packet packet)
                {
                    if (packet instanceof JingleIQ)
                    {
                        final JingleIQ jiq = (JingleIQ) packet;
                        if (JingleAction.SESSION_INITIATE.equals(jiq
                            .getAction()))
                        {
                            resultList.add(jiq);
                            JireconSessionImpl.this.removePacketListener(this);

                            synchronized (waitForInitPacketSyncRoot)
                            {
                                waitForInitPacketSyncRoot.notify();
                            }
                        }
                    }
                }
            };

        addPacketListener(packetListener);
        boolean interrupted = false;

        synchronized (waitForInitPacketSyncRoot)
        {
            while (resultList.isEmpty())
            {
                try
                {
                    waitForInitPacketSyncRoot.wait(MAX_WAIT_TIME);
                }
                catch (InterruptedException ie)
                {
                    interrupted = true;
                }
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();

        removePacketListener(packetListener);
        if (resultList.isEmpty())
        {
            throw new OperationFailedException(
                "Could not get session-init packet",
                OperationFailedException.GENERAL_ERROR);
        }

        return resultList.get(0);
    }

    private void waitForAckPacket() throws OperationFailedException
    {
        logger.info("waitForAckPacket");

        final List<Packet> resultList = new ArrayList<Packet>();
        final Object waitForAckPacketSyncRoot = new Object();
        JireconSessionPacketListener packetListener =
            new JireconSessionPacketListener()
            {
                @Override
                public void handlePacket(Packet packet)
                {
                    if (packet.toXML().indexOf("type=\"result\"") >= 0)
                    {
                        resultList.add(packet);
                        synchronized (waitForAckPacketSyncRoot)
                        {
                            waitForAckPacketSyncRoot.notify();
                        }
                    }
                }
            };

        addPacketListener(packetListener);
        boolean interrupted = false;

        synchronized (waitForAckPacketSyncRoot)
        {
            while (resultList.isEmpty())
            {
                try
                {
                    waitForAckPacketSyncRoot.wait(MAX_WAIT_TIME);
                }
                catch (InterruptedException ie)
                {
                    interrupted = true;
                }
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();

        removePacketListener(packetListener);
        if (resultList.isEmpty())
        {
            throw new OperationFailedException("Could not get ack packet",
                OperationFailedException.GENERAL_ERROR);
        }
    }

    private void handlePresencePacket(Presence p)
    {
        PacketExtension packetExt = p.getExtension(MediaExtension.NAMESPACE);
        MUCUser userExt =
            (MUCUser) p
                .getExtension("x", "http://jabber.org/protocol/muc#user");
        String participantJid = userExt.getItem().getJid();
        if (null != participantJid && null != packetExt)
        {
            MediaExtension mediaExt = (MediaExtension) packetExt;
            Map<MediaType, String> participantSsrcs =
                new HashMap<MediaType, String>();
            for (MediaType mediaType : MediaType.values())
            {
                // Make sure that we only handle audio or video type.
                if (MediaType.AUDIO != mediaType
                    && MediaType.VIDEO != mediaType)
                {
                    continue;
                }

                MediaDirection direction =
                    MediaDirection.parseString(mediaExt.getDirection(mediaType
                        .toString()));
                String ssrc = mediaExt.getSsrc(mediaType.toString());
                if (direction == MediaDirection.SENDONLY
                    || direction == MediaDirection.SENDRECV)
                {
                    participantSsrcs.put(mediaType, ssrc);
                }
            }
            sharingInfo.setParticipantSsrcs(participantJid, participantSsrcs);
        }
    }

    private JingleIQ createAcceptPacket(JingleIQ initIq,
        JireconTransportManager transportManager,
        SrtpControlManager srtpControlManager)
    {
        logger.info("createSessionAcceptPacket");
        final List<ContentPacketExtension> contents =
            new ArrayList<ContentPacketExtension>();
        for (MediaType mediaType : MediaType.values())
        {
            // Make sure that we only handle audio or video type.
            if (MediaType.AUDIO != mediaType && MediaType.VIDEO != mediaType)
            {
                continue;
            }

            ContentPacketExtension initIqContent =
                JinglePacketParser.getContentPacketExt(initIq, mediaType);
            contents.add(createContentPacketExtension(mediaType, initIqContent,
                transportManager, srtpControlManager));
        }

        JingleIQ acceptJiq =
            JinglePacketFactory.createSessionAccept(sharingInfo.getLocalJid(),
                sharingInfo.getRemoteJid(), sharingInfo.getSid(), contents);

        return acceptJiq;
    }

    private ContentPacketExtension createContentPacketExtension(
        MediaType mediaType, ContentPacketExtension initIqContent,
        JireconTransportManager transportManager,
        SrtpControlManager srtpControlManager)
    {
        logger.debug(this.getClass() + " createContentPacketExtension");
        IceUdpTransportPacketExtension transportPE =
            transportManager.getTransportPacketExt();

        // DTLS stuff, fingerprint packet extension
        String fingerprint = srtpControlManager.getLocalFingerprint(mediaType);
        String hash =
            srtpControlManager.getLocalFingerprintHashFunction(mediaType);
        DtlsFingerprintPacketExtension fingerprintPE =
            transportPE
                .getFirstChildOfType(DtlsFingerprintPacketExtension.class);
        if (fingerprintPE == null)
        {
            fingerprintPE = new DtlsFingerprintPacketExtension();
            transportPE.addChildExtension(fingerprintPE);
        }
        fingerprintPE.setFingerprint(fingerprint);
        fingerprintPE.setHash(hash);

        List<PayloadTypePacketExtension> payloadTypes =
            new ArrayList<PayloadTypePacketExtension>();
        for (Map.Entry<MediaFormat, Byte> e : sharingInfo
            .getFormatAndPayloadTypes(mediaType).entrySet())
        {
            PayloadTypePacketExtension payloadType =
                new PayloadTypePacketExtension();
            payloadType.setId(e.getValue());
            payloadType.setName(e.getKey().getEncoding());
            if (e.getKey() instanceof AudioMediaFormat)
            {
                payloadType.setChannels(((AudioMediaFormat) e.getKey())
                    .getChannels());
            }
            payloadType.setClockrate((int) e.getKey().getClockRate());
            for (Map.Entry<String, String> en : e.getKey()
                .getFormatParameters().entrySet())
            {
                ParameterPacketExtension parameter =
                    new ParameterPacketExtension();
                parameter.setName(en.getKey());
                parameter.setValue(en.getValue());
                payloadType.addParameter(parameter);
            }
            payloadTypes.add(payloadType);
        }

        RtpDescriptionPacketExtension description =
            new RtpDescriptionPacketExtension();
        description.setMedia(mediaType.toString());
        for (PayloadTypePacketExtension p : payloadTypes)
        {
            description.addPayloadType(p);
        }
        SourcePacketExtension sourcePacketExtension =
            new SourcePacketExtension();
        description.setSsrc(sharingInfo.getLocalSsrc(mediaType).toString());

        sourcePacketExtension.setSSRC(sharingInfo.getLocalSsrc(mediaType));
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension(
            "cname", LibJitsi.getMediaService().getRtpCname()));
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension(
            "msid", sharingInfo.getMsid(mediaType)));
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension(
            "mslabel", sharingInfo.getMsLabel()));
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension(
            "label", sharingInfo.getLabel(mediaType)));
        description.addChildExtension(sourcePacketExtension);

        ContentPacketExtension content = new ContentPacketExtension();
        content.setCreator(CreatorEnum.responder);
        content.setName(initIqContent.getName());
        content.setSenders(SendersEnum.initiator);
        content.addChildExtension(description);
        content.addChildExtension(transportPE);

        return content;
    }

    private void handlePacket(Packet packet)
    {
        for (JireconSessionPacketListener l : packetListeners)
        {
            l.handlePacket(packet);
        }
    }

    private void addPacketSendingListener()
    {
        connection.addPacketSendingListener(new PacketListener()
        {
            @Override
            public void processPacket(Packet packet)
            {
                // logger.debug("--->: " + packet.toXML());
                System.out.println("--->: " + packet.toXML());
            }
        }, new PacketFilter()
        {
            @Override
            public boolean accept(Packet packet)
            {
                return true;
            }
        });
    }

    private void addPacketReceivingListener()
    {
        connection.addPacketListener(new PacketListener()
        {
            @Override
            public void processPacket(Packet packet)
            {
                // logger.debug(packet.getClass() + "<---: " + packet.toXML());
                System.out.println(packet.getClass() + "<---: "
                    + packet.toXML());
                handlePacket(packet);
            }
        }, new PacketFilter()
        {
            @Override
            public boolean accept(Packet packet)
            {
                if (null != sharingInfo.getLocalJid()
                    && !packet.getTo().equals(sharingInfo.getLocalJid()))
                {
                    logger.fatal("packet failed: to " + packet.getTo()
                        + ", but we are " + sharingInfo.getLocalJid());
                    return false;
                }
                return true;
            }
        });
    }

    private interface JireconSessionPacketListener
    {
        public void handlePacket(Packet packet);
    }

    private void addPacketListener(JireconSessionPacketListener listener)
    {
        packetListeners.add(listener);
    }

    private void removePacketListener(JireconSessionPacketListener listener)
    {
        packetListeners.remove(listener);
    }
}
