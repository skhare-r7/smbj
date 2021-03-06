/*
 * Copyright (C)2016 - SMBJ Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hierynomus.smbj.connection;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb.SMB1MessageConverter;
import com.hierynomus.mssmb.SMB1NotSupportedException;
import com.hierynomus.mssmb.SMB1Packet;
import com.hierynomus.mssmb.messages.SMB1ComNegotiateRequest;
import com.hierynomus.mssmb2.*;
import com.hierynomus.mssmb2.messages.*;
import com.hierynomus.protocol.commons.Factory;
import com.hierynomus.protocol.commons.buffer.Buffer;
import com.hierynomus.protocol.commons.concurrent.CancellableFuture;
import com.hierynomus.protocol.commons.concurrent.Futures;
import com.hierynomus.protocol.transport.*;
import com.hierynomus.smb.SMBPacket;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.auth.Authenticator;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.hierynomus.smbj.event.ConnectionClosed;
import com.hierynomus.smbj.event.SMBEventBus;
import com.hierynomus.smbj.event.SessionLoggedOff;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.spnego.NegTokenInit;
import com.hierynomus.spnego.SpnegoException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.engio.mbassy.listener.Handler;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.hierynomus.mssmb2.SMB2Packet.SINGLE_CREDIT_PAYLOAD_SIZE;
import static com.hierynomus.mssmb2.messages.SMB2SessionSetup.SMB2SecurityMode.SMB2_NEGOTIATE_SIGNING_ENABLED;
import static java.lang.String.format;

/**
 * A connection to a server.
 */
public class Connection implements AutoCloseable, PacketReceiver<SMBPacket<?>> {
    private static final Logger logger = LoggerFactory.getLogger(Connection.class);
    private static final DelegatingSMBMessageConverter converter = new DelegatingSMBMessageConverter(new SMB2MessageConverter(), new SMB1MessageConverter());

    private ConnectionInfo connectionInfo;
    private String remoteName;

    private SMBClient client;

    public SMBClient getClient() {
        return client;
    }

    private SmbConfig config;
    private TransportLayer<SMBPacket<?>> transport;
    private final SMBEventBus bus;
    private final ReentrantLock lock = new ReentrantLock();
    private int remotePort;

    public Connection(SmbConfig config, SMBClient client, SMBEventBus bus) {
        this.config = config;
        this.client = client;
        this.transport = config.getTransportLayerFactory().createTransportLayer(new PacketHandlers<>(new SMBPacketSerializer(), this, converter), config);
        this.bus = bus;
        bus.subscribe(this);
    }

    public Connection(Connection connection) {
        this.client = connection.client;
        this.config = connection.config;
        this.transport = connection.transport;
        this.bus = connection.bus;
        bus.subscribe(this);
    }

    public void connect(String hostname, int port) throws IOException {
        if (isConnected()) {
            throw new IllegalStateException(format("This connection is already connected to %s", getRemoteHostname()));
        }
        this.remoteName = hostname;
        this.remotePort = port;
        transport.connect(new InetSocketAddress(hostname, port));
        this.connectionInfo = new ConnectionInfo(config.getClientGuid(), hostname);
        negotiateDialect();
        logger.info("Successfully connected to: {}", getRemoteHostname());
    }

    @Override
    public void close() throws Exception {
        close(false);
    }

    /**
     * Close the Connection. If {@code force} is set to true, it forgoes the {@link Session#close()} operation on the open sessions, and it just
     * calls the {@link TransportLayer#disconnect()}.
     *
     * @param force if set, does not nicely terminate the open sessions.
     * @throws Exception If any error occurred during close-ing.
     */
    public void close(boolean force) throws Exception {
        try {
            if (!force) {
                for (Session session : connectionInfo.getSessionTable().activeSessions()) {
                    try {
                        session.close();
                    } catch (IOException e) {
                        logger.warn("Exception while closing session {}", session.getSessionId(), e);
                    }
                }
            }
        } finally {
            transport.disconnect();
            logger.info("Closed connection to {}", getRemoteHostname());
            bus.publish(new ConnectionClosed(remoteName, remotePort));
        }
    }

    public SmbConfig getConfig() {
        return config;
    }

    /**
     * Authenticate the user on this connection in order to start a (new) session.
     *
     * @return a (new) Session that is authenticated for the user.
     */

    public Session authenticate(AuthenticationContext authContext) {
        return authenticate(authContext, null);
    }

    /**
     * Authenticate the user on this connection in order to start a (new) session.
     *
     * @param metadata an optional ConnectionMetadata that is set based on
     *                 the NegotiateProtocolResponse and SMBSessionSetupResponse
     * @return a (new) Session that is authenticated for the user.
     */

    public Session authenticate(AuthenticationContext authContext, ConnectionMetadata metadata) {
        try {
            Authenticator authenticator = getAuthenticator(authContext);
            authenticator.init(config.getSecurityProvider(), config.getRandomProvider());
            Session session = getSession(authContext);
            SMB2SessionSetup receive = authenticationRound(authenticator, authContext, connectionInfo.getGssNegotiateToken(), session);
            if (metadata != null) {
                grabOSInfo(receive.getSecurityBuffer(), metadata);
                grabConnectionInfo(metadata);
            }
            long sessionId = receive.getHeader().getSessionId();
            session.init(receive);
            connectionInfo.getPreauthSessionTable().registerSession(sessionId, session);
            try {
                while (receive.getHeader().getStatus() == NtStatus.STATUS_MORE_PROCESSING_REQUIRED) {
                    logger.debug("More processing required for authentication of {} using {}", authContext.getUsername(), authenticator);
                    receive = authenticationRound(authenticator, authContext, receive.getSecurityBuffer(), session);
                }

                if (receive.getHeader().getStatus() != NtStatus.STATUS_SUCCESS) {
                    throw new SMBApiException(receive.getHeader(), format("Authentication failed for '%s' using %s", authContext.getUsername(), authenticator));
                }

                if (receive.getSecurityBuffer() != null) {
                    // process the last received buffer
                    authenticator.authenticate(authContext, receive.getSecurityBuffer(), session);
                }
                logger.info("Successfully authenticated {} on {}, session is {}", authContext.getUsername(), remoteName, session.getSessionId());
                connectionInfo.getSessionTable().registerSession(session.getSessionId(), session);
                return session;
            } finally {
                connectionInfo.getPreauthSessionTable().sessionClosed(sessionId);
            }
        } catch (SpnegoException | IOException e) {
            throw new SMBRuntimeException(e);
        }
    }

    private Session getSession(AuthenticationContext authContext) {
        return new Session(this, authContext, bus, connectionInfo.isServerRequiresSigning(), config.isDfsEnabled(), config.getSecurityProvider());
    }

    private SMB2SessionSetup authenticationRound(Authenticator authenticator, AuthenticationContext authContext, byte[] inputToken, Session session) throws IOException {
        byte[] securityContext = authenticator.authenticate(authContext, inputToken, session);

        SMB2SessionSetup req = new SMB2SessionSetup(connectionInfo.getNegotiatedProtocol().getDialect(), EnumSet.of(SMB2_NEGOTIATE_SIGNING_ENABLED),
            connectionInfo.getClientCapabilities());
        req.setSecurityBuffer(securityContext);
        req.getHeader().setSessionId(session.getSessionId());
        return Futures.get(this.<SMB2SessionSetup>send(req), getConfig().getTransactTimeout(), TimeUnit.MILLISECONDS, TransportException.Wrapper);
    }

    private Authenticator getAuthenticator(AuthenticationContext context) throws IOException, SpnegoException {
        List<Factory.Named<Authenticator>> supportedAuthenticators = new ArrayList<>(config.getSupportedAuthenticators());
        List<ASN1ObjectIdentifier> mechTypes = new ArrayList<>();
        if (connectionInfo.getGssNegotiateToken().length > 0) {
            NegTokenInit negTokenInit = new NegTokenInit().read(connectionInfo.getGssNegotiateToken());
            mechTypes = negTokenInit.getSupportedMechTypes();
        }

        for (Factory.Named<Authenticator> factory : new ArrayList<>(supportedAuthenticators)) {
            if (mechTypes.isEmpty() || mechTypes.contains(new ASN1ObjectIdentifier(factory.getName()))) {
                Authenticator authenticator = factory.create();
                if (authenticator.supports(context)) {
                    return authenticator;
                }
            }
        }

        throw new SMBRuntimeException("Could not find a configured authenticator for mechtypes: " + mechTypes + " and authentication context: " + context);
    }

    /**
     * send a packet.
     *
     * @param packet SMBPacket to send
     * @return a Future to be used to retrieve the response packet
     * @throws TransportException When a transport level error occurred
     */
    public <T extends SMB2Packet> Future<T> send(SMB2Packet packet) throws TransportException {
        lock.lock();
        try {
            int availableCredits = connectionInfo.getSequenceWindow().available();
            int grantCredits = calculateGrantedCredits(packet, availableCredits);
            if (availableCredits == 0) {
                logger.warn("There are no credits left to send {}, will block until there are more credits available.", packet.getHeader().getMessage());
            }
            long[] messageIds = connectionInfo.getSequenceWindow().get(grantCredits);
            packet.getHeader().setMessageId(messageIds[0]);
            logger.debug("Granted {} (out of {}) credits to {}", grantCredits, availableCredits, packet);
            packet.getHeader().setCreditRequest(Math.max(SequenceWindow.PREFERRED_MINIMUM_CREDITS - availableCredits - grantCredits, grantCredits));

            Request request = new Request(packet.getHeader().getMessageId(), UUID.randomUUID());
            connectionInfo.getOutstandingRequests().registerOutstanding(request);
            transport.write(packet);
            return request.getFuture(new CancelRequest(request));
        } finally {
            lock.unlock();
        }
    }

    private <T extends SMB2Packet> T sendAndReceive(SMB2Packet packet) throws TransportException {
        return Futures.get(this.<T>send(packet), getConfig().getTransactTimeout(), TimeUnit.MILLISECONDS, TransportException.Wrapper);
    }

    private int calculateGrantedCredits(final SMB2Packet packet, final int availableCredits) {
        final int grantCredits;
        int maxPayloadSize = packet.getMaxPayloadSize();
        int creditsNeeded = creditsNeeded(maxPayloadSize);
        if (creditsNeeded > 1 && !connectionInfo.supports(SMB2GlobalCapability.SMB2_GLOBAL_CAP_LARGE_MTU)) {
            logger.trace("Connection to {} does not support multi-credit requests.", getRemoteHostname());
            grantCredits = 1;
        } else if (creditsNeeded < availableCredits) { // Scale the credits dynamically
            grantCredits = creditsNeeded;
        } else if (creditsNeeded > 1 && availableCredits > 1) { // creditsNeeded >= availableCredits
            grantCredits = availableCredits - 1; // Keep 1 credit left for a simple request
        } else {
            grantCredits = 1;
        }
        packet.setCreditsAssigned(grantCredits);
        return grantCredits;
    }

    private void negotiateDialect() throws TransportException {
        logger.debug("Negotiating dialects {} with server {}", config.getSupportedDialects(), getRemoteHostname());
        SMB2Packet resp;
        if (config.isUseMultiProtocolNegotiate()) {
            resp = multiProtocolNegotiate();
        } else {
            resp = smb2OnlyNegotiate();
        }
        if (!(resp instanceof SMB2NegotiateResponse)) {
            throw new IllegalStateException("Expected a SMB2 NEGOTIATE Response, but got: " + resp);
        }
        SMB2NegotiateResponse negotiateResponse = (SMB2NegotiateResponse) resp;
        if (!negotiateResponse.getHeader().getStatus().isSuccess()) {
            throw new SMBApiException(negotiateResponse.getHeader(), "Failure during dialect negotiation");
        }
        connectionInfo.negotiated(negotiateResponse);
        logger.debug("Negotiated the following connection settings: {}", connectionInfo);
    }

    private SMB2Packet smb2OnlyNegotiate() throws TransportException {
        SMB2Packet negotiatePacket = new SMB2NegotiateRequest(config.getSupportedDialects(), connectionInfo.getClientGuid(), config.isSigningRequired());
        Future<SMB2Packet> send = send(negotiatePacket);
        return Futures.get(send, getConfig().getTransactTimeout(), TimeUnit.MILLISECONDS, TransportException.Wrapper);
    }

    private SMB2Packet multiProtocolNegotiate() throws TransportException {
        SMB1Packet negotiatePacket = new SMB1ComNegotiateRequest(config.getSupportedDialects());
        long l = connectionInfo.getSequenceWindow().get();
        if (l != 0) {
            throw new IllegalStateException("The SMBv1 SMB_COM_NEGOTIATE packet needs to be the first packet sent.");
        }
        Request request = new Request(l, UUID.randomUUID());
        connectionInfo.getOutstandingRequests().registerOutstanding(request);
        transport.write(negotiatePacket);
        Future<SMB2Packet> future = request.getFuture(null);
        SMB2Packet packet = Futures.get(future, getConfig().getTransactTimeout(), TimeUnit.MILLISECONDS, TransportException.Wrapper);
        if (!(packet instanceof SMB2NegotiateResponse)) {
            throw new IllegalStateException("Expected a SMB2 NEGOTIATE Response to our SMB_COM_NEGOTIATE, but got: " + packet);
        }
        SMB2NegotiateResponse negotiateResponse = (SMB2NegotiateResponse) packet;

        if (negotiateResponse.getDialect() == SMB2Dialect.SMB_2XX) {
            return smb2OnlyNegotiate();
        }
        return negotiateResponse;
    }

    /**
     * [MS-SMB2].pdf 3.1.5.2 Calculating the CreditCharge
     */
    private int creditsNeeded(int payloadSize) {
        return Math.abs((payloadSize - 1) / SINGLE_CREDIT_PAYLOAD_SIZE) + 1;
    }

    /**
     * Returns the negotiated protocol details for this connection.
     *
     * @return The negotiated protocol details
     */
    public NegotiatedProtocol getNegotiatedProtocol() {
        return connectionInfo.getNegotiatedProtocol();
    }

    @Override
    public void handle(SMBPacket uncheckedPacket) throws TransportException {
        if (!(uncheckedPacket instanceof SMB2Packet)) {
            throw new SMB1NotSupportedException();
        }

        SMB2Packet packet = (SMB2Packet) uncheckedPacket;
        long messageId = packet.getSequenceNumber();

        if (!connectionInfo.getOutstandingRequests().isOutstanding(messageId)) {
            throw new TransportException("Received response with unknown sequence number <<" + messageId + ">>");
        }

        // [MS-SMB2].pdf 3.2.5.1.4 Granting Message Credits
        connectionInfo.getSequenceWindow().creditsGranted(packet.getHeader().getCreditResponse());
        logger.debug("Server granted us {} credits for {}, now available: {} credits", packet.getHeader().getCreditResponse(), packet, connectionInfo.getSequenceWindow().available());

        Request request = connectionInfo.getOutstandingRequests().getRequestByMessageId(messageId);
        logger.trace("Send/Recv of packet {} took << {} ms >>", packet, System.currentTimeMillis() - request.getTimestamp().getTime());

        // [MS-SMB2].pdf 3.2.5.1.5 Handling Asynchronous Responses
        if (packet.isIntermediateAsyncResponse()) {
            logger.debug("Received ASYNC packet {} with AsyncId << {} >>", packet, packet.getHeader().getAsyncId());
            request.setAsyncId(packet.getHeader().getAsyncId());
            // TODO Expiration timer
            return;
        }

        // [MS-SMB2].pdf 3.2.5.1.6 Handling Session Expiration
        if (packet.getHeader().getStatus() == NtStatus.STATUS_NETWORK_SESSION_EXPIRED) {
            // TODO reauthenticate session!
            return;
        }

        if (packet.getHeader().getSessionId() != 0 && (packet.getHeader().getMessage() != SMB2MessageCommandCode.SMB2_SESSION_SETUP)) {
            Session session = connectionInfo.getSessionTable().find(packet.getHeader().getSessionId());
            if (session == null) {
                // check for a not-yet-authenticated session
                session = connectionInfo.getPreauthSessionTable().find(packet.getHeader().getSessionId());
                if (session == null) {
                    logger.warn("Illegal request, no session matching the sessionId: {}", packet.getHeader().getSessionId());
                    //TODO maybe tear down the connection?
                    return;
                }
            }

            // check packet signature. Drop the packet if it is not correct.
            verifyPacketSignature(packet, session);
        }

        // [MS-SMB2].pdf 3.2.5.1.8 Processing the Response
        connectionInfo.getOutstandingRequests().receivedResponseFor(messageId).getPromise().deliver(packet);
    }

    private void verifyPacketSignature(SMB2Packet packet, Session session) throws TransportException {
        if (packet.getHeader().isFlagSet(SMB2MessageFlag.SMB2_FLAGS_SIGNED)) {
            if (!session.getPacketSignatory().verify(packet)) {
                logger.warn("Invalid packet signature for packet {}", packet);
                if (config.isSigningRequired()) {
                    throw new TransportException("Packet signature for packet " + packet + " was not correct");
                }
            }
        } else if (config.isSigningRequired()) {
            logger.warn("Illegal request, client requires message signing, but packet {} is not signed.", packet);
            throw new TransportException("Client requires signing, but packet " + packet + " was not signed");
        }
    }

    @Override
    public void handleError(Throwable t) {
        connectionInfo.getOutstandingRequests().handleError(t);
        try {
            this.close();
        } catch (Exception e) {
            String exceptionClass = e.getClass().getSimpleName();
            logger.debug("{} while closing connection on error, ignoring: {}", exceptionClass, e.getMessage());
        }
    }

    public String getRemoteHostname() {
        return remoteName;
    }

    public boolean isConnected() {
        return transport.isConnected();
    }

    @Handler
    @SuppressWarnings("unused")
    private void sessionLogoff(SessionLoggedOff loggedOff) {
        connectionInfo.getSessionTable().sessionClosed(loggedOff.getSessionId());
        logger.debug("Session << {} >> logged off", loggedOff.getSessionId());
    }

    private static class DelegatingSMBMessageConverter implements PacketFactory<SMBPacket<?>> {
        private PacketFactory<?>[] packetFactories;

        public DelegatingSMBMessageConverter(PacketFactory<?>... packetFactories) {
            this.packetFactories = packetFactories;
        }

        @Override
        public SMBPacket<?> read(byte[] data) throws Buffer.BufferException, IOException {
            for (PacketFactory<?> packetFactory : packetFactories) {
                if (packetFactory.canHandle(data)) {
                    return (SMBPacket<?>) packetFactory.read(data);
                }
            }
            throw new IOException("Unknown packet format received.");
        }

        @Override
        public boolean canHandle(byte[] data) {
            for (PacketFactory<?> packetFactory : packetFactories) {
                if (packetFactory.canHandle(data)) {
                    return true;
                }
            }
            return false;
        }
    }

    private class CancelRequest implements CancellableFuture.CancelCallback {
        private Request request;

        public CancelRequest(Request request) {
            this.request = request;
        }

        /**
         * [MS-SMB2] 3.2.4.24 Application Requests Canceling an Operation
         */
        @Override
        public void cancel() {
            SMB2CancelRequest cancel = new SMB2CancelRequest(connectionInfo.getNegotiatedProtocol().getDialect(),
                request.getMessageId(),
                request.getAsyncId());
            try {
                transport.write(cancel);
            } catch (TransportException e) {
                logger.error("Failed to send {}", cancel);
            }
        }
    }

    /**
     * This function is based on the NTLM type 2 protocol described in
     *  http://davenport.sourceforge.net/ntlm.html
     * and Spnego handshake described at
     * https://msdn.microsoft.com/en-us/library/ms995330.aspx
     *
     * @param gssBlob The security blob returned as a part of the SMB2SessionSetup Response
     * @param md Connection Metadata object to be populated
     */
    private void grabOSInfo(byte[] gssBlob, ConnectionMetadata md) {
        final int SPNEGO_RESPONSE_TOKEN_SEQ = 2;
        final int NTLM_MESSAGE_TYPE_START = 8;
        final int NTLM_MESSAGE_TYPE_END = 12;
        final int NTLM2_TARGET_NAME_OFFSET_START = 16;
        final int NTLM2_TARGET_NAME_OFFSET_END = 20;
        final int NTLM2_OS_VERSION_STRUCT_START = 48;
        final int NTLM2_OS_VERSION_STRUCT_END = 56;

        final byte [] NTLM_VERSION_2 = {0x02, 0x00, 0x00, 0x00};



        ASN1InputStream bin = new ASN1InputStream(gssBlob);
        try {
            DERTaggedObject spnegoBlob = (DERTaggedObject) bin.readObject();
            DERSequence negTokenTarg = (DERSequence) spnegoBlob.getObject();
            DERTaggedObject responseToken = (DERTaggedObject) negTokenTarg.getObjectAt(SPNEGO_RESPONSE_TOKEN_SEQ);
            DEROctetString NTLM = (DEROctetString) responseToken.getObject();
            byte[] NTLMOctects = NTLM.getOctets();
            // Verify we're dealing with a full sized NTLM V2 message
            if (NTLMOctects.length >= NTLM2_OS_VERSION_STRUCT_END) {
                byte[] NTLMMessageType = Arrays.copyOfRange(NTLMOctects, NTLM_MESSAGE_TYPE_START, NTLM_MESSAGE_TYPE_END);
                byte [] targetNameOffsetBytes = Arrays.copyOfRange(NTLMOctects,
                   NTLM2_TARGET_NAME_OFFSET_START,
                   NTLM2_TARGET_NAME_OFFSET_END);
                int targetNameOffset = ByteBuffer.wrap(targetNameOffsetBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
                // Verify NTLM V2 header and OS Version Information is not skipped
                if (Arrays.equals(NTLMMessageType, NTLM_VERSION_2) && targetNameOffset >= NTLM2_OS_VERSION_STRUCT_END) {
                    byte[] osVersion = Arrays.copyOfRange(NTLMOctects,
                       NTLM2_OS_VERSION_STRUCT_START,
                       NTLM2_OS_VERSION_STRUCT_END);
                    md.setOSmajorVersion(osVersion[0]);
                    md.setOSminorVersion(osVersion[1]);
                    md.setOSbuildCode(osVersion[3] << 8 | osVersion[2] & 0xFF);
                }
            }
        } catch (IOException e){
            //Fingerprinting failed, invalid blob?
        }
    }

    private void grabConnectionInfo(ConnectionMetadata md){
        md.setSigningRequired(connectionInfo.isServerRequiresSigning());
        md.setSigningEnabled(connectionInfo.isServerSigningEnabled());
        md.setEncryptionSupported(connectionInfo.supports(SMB2GlobalCapability.SMB2_GLOBAL_CAP_ENCRYPTION));
        md.setDialectVersion(connectionInfo.getNegotiatedProtocol().getDialect());
        md.setServerGUID(connectionInfo.getServerGuid());
    }
}
