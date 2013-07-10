/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.bitcoin.protocols.channels;

import com.google.bitcoin.core.*;
import com.google.bitcoin.utils.Threading;
import com.google.bitcoin.wallet.WalletFiles;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import org.bitcoin.paymentchannel.Protos;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.annotation.Nullable;
import java.io.File;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.bitcoin.protocols.channels.PaymentChannelCloseException.CloseReason;
import static org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage.MessageType;
import static org.junit.Assert.*;

public class ChannelConnectionTest extends TestWithWallet {
    private Wallet serverWallet;
    private AtomicBoolean fail;
    private BlockingQueue<Transaction> broadcasts;
    private TransactionBroadcaster mockBroadcaster;
    private Semaphore broadcastTxPause;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        sendMoneyToWallet(Utils.COIN, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        sendMoneyToWallet(Utils.COIN, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        wallet.addExtension(new StoredPaymentChannelClientStates(new TransactionBroadcaster() {
            @Override
            public ListenableFuture<Transaction> broadcastTransaction(Transaction tx) {
                fail();
                return null;
            }
        }, wallet));
        chain = new BlockChain(params, wallet, blockStore); // Recreate chain as sendMoneyToWallet will confuse it
        serverWallet = new Wallet(params);
        serverWallet.addKey(new ECKey());
        chain.addWallet(serverWallet);
        // Use an atomic boolean to indicate failure because fail()/assert*() dont work in network threads
        fail = new AtomicBoolean(false);

        // Set up a way to monitor broadcast transactions. When you expect a broadcast, you must release a permit
        // to the broadcastTxPause semaphore so state can be queried in between.
        broadcasts = new LinkedBlockingQueue<Transaction>();
        broadcastTxPause = new Semaphore(0);
        mockBroadcaster = new TransactionBroadcaster() {
            @Override
            public ListenableFuture<Transaction> broadcastTransaction(Transaction tx) {
                broadcastTxPause.acquireUninterruptibly();
                SettableFuture<Transaction> future = SettableFuture.create();
                future.set(tx);
                broadcasts.add(tx);
                return future;
            }
        };

        // Because there are no separate threads in the tests here (we call back into client/server in server/client
        // handlers), we have lots of lock cycles. A normal user shouldn't have this issue as they are probably not both
        // client+server running in the same thread.
        Threading.warnOnLockCycles();
    }

    @After
    public void checkFail() {
        assertFalse(fail.get());
        Threading.throwOnLockCycles();
    }

    @Test
    public void testSimpleChannel() throws Exception {
        // Test with network code and without any issues. We'll broadcast two txns: multisig contract and close transaction.
        final SettableFuture<ListenableFuture<PaymentChannelServerState>> serverCloseFuture = SettableFuture.create();
        final SettableFuture<Sha256Hash> channelOpenFuture = SettableFuture.create();
        final BlockingQueue<BigInteger> q = new LinkedBlockingQueue<BigInteger>();
        final PaymentChannelServerListener server = new PaymentChannelServerListener(mockBroadcaster, serverWallet, 1, Utils.COIN,
                new PaymentChannelServerListener.HandlerFactory() {
                    @Nullable
                    @Override
                    public ServerConnectionEventHandler onNewConnection(SocketAddress clientAddress) {
                        return new ServerConnectionEventHandler() {
                            @Override
                            public void channelOpen(Sha256Hash channelId) {
                                channelOpenFuture.set(channelId);
                            }

                            @Override
                            public void paymentIncrease(BigInteger by, BigInteger to) {
                                q.add(to);
                            }

                            @Override
                            public void channelClosed(CloseReason reason) {
                                serverCloseFuture.set(null);
                            }
                        };
                    }
                });
        server.bindAndStart(4243);

        PaymentChannelClientConnection client = new PaymentChannelClientConnection(
                new InetSocketAddress("localhost", 4243), 1, wallet, myKey, Utils.COIN, "");

        // Wait for the multi-sig tx to be transmitted.
        broadcastTxPause.release();
        Transaction broadcastMultiSig = broadcasts.take();
        // Wait for the channel to finish opening.
        client.getChannelOpenFuture().get();
        assertEquals(broadcastMultiSig.getHash(), channelOpenFuture.get());

        // Set up an autosave listener to make sure the server is saving the wallet after each payment increase.
        final AtomicInteger autoSaveCount = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(3);  // Expect 3 calls.
        File tempFile = File.createTempFile("channel_connection_test", ".wallet");
        tempFile.deleteOnExit();
        serverWallet.autosaveToFile(tempFile, 0, TimeUnit.SECONDS, new WalletFiles.Listener() {
            @Override
            public void onBeforeAutoSave(File tempFile) {
                latch.countDown();
            }

            @Override
            public void onAfterAutoSave(File newlySavedFile) {
            }
        });

        Thread.sleep(1250); // No timeouts once the channel is open
        client.incrementPayment(Utils.CENT);
        assertEquals(Utils.CENT, q.take());
        client.incrementPayment(Utils.CENT);
        assertEquals(Utils.CENT.multiply(BigInteger.valueOf(2)), q.take());
        client.incrementPayment(Utils.CENT);
        assertEquals(Utils.CENT.multiply(BigInteger.valueOf(3)), q.take());
        latch.await();

        StoredPaymentChannelServerStates channels = (StoredPaymentChannelServerStates)serverWallet.getExtensions().get(StoredPaymentChannelServerStates.EXTENSION_ID);
        StoredServerChannel storedServerChannel = channels.getChannel(broadcastMultiSig.getHash());
        PaymentChannelServerState serverState = storedServerChannel.getState(serverWallet, mockBroadcaster);

        // Check that you can call close multiple times with no exceptions.
        client.close();
        client.close();

        broadcastTxPause.release();
        broadcasts.take();
        assertEquals(PaymentChannelServerState.State.CLOSED, serverState.getState());

        if (!serverState.getBestValueToMe().equals(Utils.CENT.multiply(BigInteger.valueOf(3))) || !serverState.getFeePaid().equals(BigInteger.ZERO))
            fail();

        assertTrue(channels.mapChannels.isEmpty());

        server.close();
        server.close();
    }

    @Test
    public void testServerErrorHandling() throws Exception {
        // Gives the server crap and checks proper error responses are sent.
        ChannelTestUtils.RecordingPair pair = ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        PaymentChannelClient client = new PaymentChannelClient(wallet, myKey, Utils.COIN, Sha256Hash.ZERO_HASH, pair.clientRecorder);
        PaymentChannelServer server = pair.server;
        server.connectionOpen();
        client.connectionOpen();

        // Make sure we get back a BAD_TRANSACTION if we send a bogus refund transaction.
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.CLIENT_VERSION));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.SERVER_VERSION));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.INITIATE));
        Protos.TwoWayChannelMessage msg = pair.clientRecorder.checkNextMsg(MessageType.PROVIDE_REFUND);
        server.receiveMessage(Protos.TwoWayChannelMessage.newBuilder()
                .setType(MessageType.PROVIDE_REFUND)
                .setProvideRefund(
                        Protos.ProvideRefund.newBuilder(msg.getProvideRefund())
                                .setMultisigKey(ByteString.EMPTY)
                                .setTx(ByteString.EMPTY)
                ).build());
        final Protos.TwoWayChannelMessage errorMsg = pair.serverRecorder.checkNextMsg(MessageType.ERROR);
        assertEquals(Protos.Error.ErrorCode.BAD_TRANSACTION, errorMsg.getError().getCode());

        // Make sure the server closes the socket on CLOSE
        pair = ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        client = new PaymentChannelClient(wallet, myKey, Utils.COIN, Sha256Hash.ZERO_HASH, pair.clientRecorder);
        server = pair.server;
        server.connectionOpen();
        client.connectionOpen();
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.CLIENT_VERSION));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.SERVER_VERSION));
        client.close();
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.INITIATE));
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.CLOSE));
        assertEquals(CloseReason.CLIENT_REQUESTED_CLOSE, pair.serverRecorder.q.take());


        // Make sure the server closes the socket on ERROR
        pair = ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        client = new PaymentChannelClient(wallet, myKey, Utils.COIN, Sha256Hash.ZERO_HASH, pair.clientRecorder);
        server = pair.server;
        server.connectionOpen();
        client.connectionOpen();
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.CLIENT_VERSION));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.SERVER_VERSION));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.INITIATE));
        server.receiveMessage(Protos.TwoWayChannelMessage.newBuilder()
                .setType(MessageType.ERROR)
                .setError(Protos.Error.newBuilder().setCode(Protos.Error.ErrorCode.TIMEOUT))
                .build());
        assertEquals(CloseReason.REMOTE_SENT_ERROR, pair.serverRecorder.q.take());
    }

    @Test
    public void testChannelResume() throws Exception {
        // Tests various aspects of channel resuming.
        Utils.rollMockClock(0);

        final Sha256Hash someServerId = Sha256Hash.create(new byte[]{});

        // Open up a normal channel.
        ChannelTestUtils.RecordingPair pair = ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        pair.server.connectionOpen();
        PaymentChannelClient client = new PaymentChannelClient(wallet, myKey, Utils.COIN, someServerId, pair.clientRecorder);
        PaymentChannelServer server = pair.server;
        client.connectionOpen();
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.CLIENT_VERSION));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.SERVER_VERSION));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.INITIATE));
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.PROVIDE_REFUND));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.RETURN_REFUND));
        broadcastTxPause.release();
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.PROVIDE_CONTRACT));
        broadcasts.take();
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.CHANNEL_OPEN));
        Sha256Hash contractHash = (Sha256Hash) pair.serverRecorder.q.take();
        pair.clientRecorder.checkOpened();
        assertNull(pair.serverRecorder.q.poll());
        assertNull(pair.clientRecorder.q.poll());
        // Send a bitcent.
        client.incrementPayment(Utils.CENT);
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.UPDATE_PAYMENT));
        assertEquals(Utils.CENT, pair.serverRecorder.q.take());
        server.close();
        server.connectionClosed();
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.CLOSE));
        client.connectionClosed();
        assertFalse(client.connectionOpen);

        // There is now an open channel worth COIN-CENT with id Sha256.create(new byte[] {})
        StoredPaymentChannelClientStates clientStoredChannels =
                (StoredPaymentChannelClientStates) wallet.getExtensions().get(StoredPaymentChannelClientStates.EXTENSION_ID);
        assertEquals(1, clientStoredChannels.mapChannels.size());
        assertFalse(clientStoredChannels.mapChannels.values().iterator().next().active);

        // Check that server-side won't attempt to reopen a nonexistent channel (it will tell the client to re-initiate
        // instead).
        pair = ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        pair.server.connectionOpen();
        pair.server.receiveMessage(Protos.TwoWayChannelMessage.newBuilder()
                .setType(MessageType.CLIENT_VERSION)
                .setClientVersion(Protos.ClientVersion.newBuilder()
                        .setPreviousChannelContractHash(ByteString.copyFrom(Sha256Hash.create(new byte[]{0x03}).getBytes()))
                        .setMajor(0).setMinor(42))
                .build());
        pair.serverRecorder.checkNextMsg(MessageType.SERVER_VERSION);
        pair.serverRecorder.checkNextMsg(MessageType.INITIATE);

        // Now reopen/resume the channel.
        pair = ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        client = new PaymentChannelClient(wallet, myKey, Utils.COIN, someServerId, pair.clientRecorder);
        server = pair.server;
        client.connectionOpen();
        server.connectionOpen();
        // Check the contract hash is sent on the wire correctly.
        final Protos.TwoWayChannelMessage clientVersionMsg = pair.clientRecorder.checkNextMsg(MessageType.CLIENT_VERSION);
        assertEquals(contractHash, new Sha256Hash(clientVersionMsg.getClientVersion().getPreviousChannelContractHash().toByteArray()));
        server.receiveMessage(clientVersionMsg);
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.SERVER_VERSION));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.CHANNEL_OPEN));
        assertEquals(contractHash, pair.serverRecorder.q.take());
        pair.clientRecorder.checkOpened();
        assertNull(pair.serverRecorder.q.poll());
        assertNull(pair.clientRecorder.q.poll());
        // Send another bitcent and check 2 were received in total.
        client.incrementPayment(Utils.CENT);
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.UPDATE_PAYMENT));
        pair.serverRecorder.checkTotalPayment(Utils.CENT.multiply(BigInteger.valueOf(2)));

        PaymentChannelClient openClient = client;
        ChannelTestUtils.RecordingPair openPair = pair;

        // Now open up a new client with the same id and make sure it doesnt attempt to reopen the channel.
        // If a client connects to a server with the same channel ID as one that's currently in use, the
        // server responds by opening up a new channel instead of letting two client connections conflict.
        pair = ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        client = new PaymentChannelClient(wallet, myKey, Utils.COIN, someServerId, pair.clientRecorder);
        server = pair.server;
        client.connectionOpen();
        server.connectionOpen();
        // Check that no prev contract hash is sent on the wire the client notices it's already in use by another
        // client attached to the same wallet and refuses to resume.
        {
            Protos.TwoWayChannelMessage msg = pair.clientRecorder.checkNextMsg(MessageType.CLIENT_VERSION);
            assertFalse(msg.getClientVersion().hasPreviousChannelContractHash());
        }

        // Make sure the server won't allow two simultaneous opens either. It will try to reinitiate instead.
        pair = ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        client = new PaymentChannelClient(wallet, myKey, Utils.COIN, someServerId, pair.clientRecorder);
        server = pair.server;
        client.connectionOpen();
        server.connectionOpen();
        // Swap out the clients version message for a custom one that incorrectly tries to resume ...
        pair.clientRecorder.getNextMsg();
        server.receiveMessage(Protos.TwoWayChannelMessage.newBuilder()
                .setType(MessageType.CLIENT_VERSION)
                .setClientVersion(Protos.ClientVersion.newBuilder()
                        .setPreviousChannelContractHash(ByteString.copyFrom(contractHash.getBytes()))
                        .setMajor(0).setMinor(42))
                .build());
        // We get the usual setup sequence.
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.SERVER_VERSION));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.INITIATE));
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.PROVIDE_REFUND));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.RETURN_REFUND));
        broadcastTxPause.release();
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.PROVIDE_CONTRACT));
        broadcasts.take();
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.CHANNEL_OPEN));
        Sha256Hash secondContractHash = (Sha256Hash) pair.serverRecorder.q.take();
        pair.clientRecorder.checkOpened();
        assertNull(pair.serverRecorder.q.poll());
        assertNull(pair.clientRecorder.q.poll());
        client.close();
        client.connectionClosed();
        pair.server.close();
        pair.server.connectionClosed();

        // Now open again with the same id and make sure it reopens the second (because the 1st is still open).
        pair = ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        client = new PaymentChannelClient(wallet, myKey, Utils.COIN, someServerId, pair.clientRecorder);
        server = pair.server;
        client.connectionOpen();
        server.connectionOpen();
        {
            Protos.TwoWayChannelMessage msg = pair.clientRecorder.checkNextMsg(MessageType.CLIENT_VERSION);
            assertEquals(secondContractHash, new Sha256Hash(msg.getClientVersion().getPreviousChannelContractHash().toByteArray()));
            server.receiveMessage(msg);
        }
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.SERVER_VERSION));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.CHANNEL_OPEN));
        pair.clientRecorder.checkOpened();
        // Close it.
        assertEquals(2, clientStoredChannels.mapChannels.size());
        broadcastTxPause.release();
        client.close();
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.CLOSE));
        assertEquals(CloseReason.CLIENT_REQUESTED_CLOSE, pair.clientRecorder.q.take());
        server.connectionClosed();
        client.connectionClosed();

        assertFalse(clientStoredChannels.getChannel(Sha256Hash.create(new byte[]{}), secondContractHash).active);
        assertTrue(clientStoredChannels.getChannel(Sha256Hash.create(new byte[]{}), contractHash).active);

        // And finally close the first channel too.
        openClient.connectionClosed();
        assertFalse(clientStoredChannels.getChannel(Sha256Hash.create(new byte[]{}), contractHash).active);

        // Now roll the mock clock and recreate the client object so that it removes the channels and announces refunds.
        Utils.rollMockClock(60 * 60 * 24 + 60*5);   // Client announces refund 5 minutes after expire time
        StoredPaymentChannelClientStates newClientStates = new StoredPaymentChannelClientStates(mockBroadcaster, wallet);
        newClientStates.deserializeWalletExtension(wallet, clientStoredChannels.serializeWalletExtension());
        // Expect two pairs of contract/refund ...
        for (int i = 0; i < 2; i++) {
            broadcastTxPause.release();
            assertTrue(broadcasts.take().getOutput(0).getScriptPubKey().isSentToMultiSig());
            broadcastTxPause.release();
            assertEquals(TransactionConfidence.Source.SELF, broadcasts.take().getConfidence().getSource());
        }
        assertTrue(broadcasts.isEmpty());
        assertTrue(newClientStates.mapChannels.isEmpty());
        // Server also knows it's too late.
        StoredPaymentChannelServerStates serverStoredChannels = new StoredPaymentChannelServerStates(serverWallet, mockBroadcaster);
        Thread.sleep(2000);   // TODO: Fix this stupid hack.
        assertTrue(serverStoredChannels.mapChannels.isEmpty());
    }

    @Test
    public void testBadResumeHash() throws InterruptedException {
        // Check that server-side will reject incorrectly formatted hashes. If anything goes wrong with session resume,
        // then the server will start the opening of a new channel automatically, so we expect to see INITIATE here.
        ChannelTestUtils.RecordingPair srv =
                ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        srv.server.connectionOpen();
        srv.server.receiveMessage(Protos.TwoWayChannelMessage.newBuilder()
                .setType(MessageType.CLIENT_VERSION)
                .setClientVersion(Protos.ClientVersion.newBuilder()
                        .setPreviousChannelContractHash(ByteString.copyFrom(new byte[]{0x00, 0x01}))
                        .setMajor(0).setMinor(42))
                .build());

        srv.serverRecorder.checkNextMsg(MessageType.SERVER_VERSION);
        srv.serverRecorder.checkNextMsg(MessageType.INITIATE);
        assertTrue(srv.serverRecorder.q.isEmpty());
    }

    @Test
    public void testClientUnknownVersion() throws Exception {
        // Tests client rejects unknown version
        ChannelTestUtils.RecordingPair pair = ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        PaymentChannelClient client = new PaymentChannelClient(wallet, myKey, Utils.COIN, Sha256Hash.ZERO_HASH, pair.clientRecorder);
        client.connectionOpen();
        pair.clientRecorder.checkNextMsg(MessageType.CLIENT_VERSION);
        client.receiveMessage(Protos.TwoWayChannelMessage.newBuilder()
                .setServerVersion(Protos.ServerVersion.newBuilder().setMajor(2))
                .setType(MessageType.SERVER_VERSION).build());
        pair.clientRecorder.checkNextMsg(MessageType.ERROR);
        assertEquals(CloseReason.NO_ACCEPTABLE_VERSION, pair.clientRecorder.q.take());
        // Double-check that we cant do anything that requires an open channel
        try {
            client.incrementPayment(BigInteger.ONE);
            fail();
        } catch (IllegalStateException e) { }
    }

    @Test
    public void testClientTimeWindowTooLarge() throws Exception {
        // Tests that clients reject too large time windows
        ChannelTestUtils.RecordingPair pair = ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        PaymentChannelServer server = pair.server;
        PaymentChannelClient client = new PaymentChannelClient(wallet, myKey, Utils.COIN, Sha256Hash.ZERO_HASH, pair.clientRecorder);
        client.connectionOpen();
        server.connectionOpen();
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.CLIENT_VERSION));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.SERVER_VERSION));
        client.receiveMessage(Protos.TwoWayChannelMessage.newBuilder()
                .setInitiate(Protos.Initiate.newBuilder().setExpireTimeSecs(Utils.now().getTime() / 1000 + 60 * 60 * 48)
                        .setMinAcceptedChannelSize(100)
                        .setMultisigKey(ByteString.copyFrom(new ECKey().getPubKey())))
                .setType(MessageType.INITIATE).build());

        pair.clientRecorder.checkNextMsg(MessageType.ERROR);
        assertEquals(CloseReason.TIME_WINDOW_TOO_LARGE, pair.clientRecorder.q.take());
        // Double-check that we cant do anything that requires an open channel
        try {
            client.incrementPayment(BigInteger.ONE);
            fail();
        } catch (IllegalStateException e) { }
    }

    @Test
    public void testClientValueTooLarge() throws Exception {
        ChannelTestUtils.RecordingPair pair = ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        PaymentChannelServer server = pair.server;
        PaymentChannelClient client = new PaymentChannelClient(wallet, myKey, Utils.COIN, Sha256Hash.ZERO_HASH, pair.clientRecorder);
        client.connectionOpen();
        server.connectionOpen();
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.CLIENT_VERSION));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.SERVER_VERSION));
        client.receiveMessage(Protos.TwoWayChannelMessage.newBuilder()
                .setInitiate(Protos.Initiate.newBuilder().setExpireTimeSecs(Utils.now().getTime() / 1000)
                        .setMinAcceptedChannelSize(Utils.COIN.add(BigInteger.ONE).longValue())
                        .setMultisigKey(ByteString.copyFrom(new ECKey().getPubKey())))
                .setType(MessageType.INITIATE).build());
        pair.clientRecorder.checkNextMsg(MessageType.ERROR);
        assertEquals(CloseReason.SERVER_REQUESTED_TOO_MUCH_VALUE, pair.clientRecorder.q.take());
        // Double-check that we cant do anything that requires an open channel
        try {
            client.incrementPayment(BigInteger.ONE);
            fail();
        } catch (IllegalStateException e) { }
    }

    @Test
    public void testClientResumeNothing() throws Exception {
        ChannelTestUtils.RecordingPair pair = ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        PaymentChannelServer server = pair.server;
        PaymentChannelClient client = new PaymentChannelClient(wallet, myKey, Utils.COIN, Sha256Hash.ZERO_HASH, pair.clientRecorder);
        client.connectionOpen();
        server.connectionOpen();
        server.receiveMessage(pair.clientRecorder.checkNextMsg(MessageType.CLIENT_VERSION));
        client.receiveMessage(pair.serverRecorder.checkNextMsg(MessageType.SERVER_VERSION));
        client.receiveMessage(Protos.TwoWayChannelMessage.newBuilder()
                .setType(MessageType.CHANNEL_OPEN).build());
        pair.clientRecorder.checkNextMsg(MessageType.ERROR);
        assertEquals(CloseReason.REMOTE_SENT_INVALID_MESSAGE, pair.clientRecorder.q.take());
    }

    @Test
    public void testClientRandomMessage() throws Exception {
        ChannelTestUtils.RecordingPair pair = ChannelTestUtils.makeRecorders(serverWallet, mockBroadcaster);
        PaymentChannelClient client = new PaymentChannelClient(wallet, myKey, Utils.COIN, Sha256Hash.ZERO_HASH, pair.clientRecorder);

        client.connectionOpen();
        pair.clientRecorder.checkNextMsg(MessageType.CLIENT_VERSION);
        // Send a CLIENT_VERSION back to the client - ?!?!!
        client.receiveMessage(Protos.TwoWayChannelMessage.newBuilder()
                .setType(MessageType.CLIENT_VERSION).build());
        Protos.TwoWayChannelMessage error = pair.clientRecorder.checkNextMsg(MessageType.ERROR);
        assertEquals(Protos.Error.ErrorCode.SYNTAX_ERROR, error.getError().getCode());
        assertEquals(CloseReason.REMOTE_SENT_INVALID_MESSAGE, pair.clientRecorder.q.take());
   }
}
