/*
 * Copyright 2012 Matt Corallo.
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

package com.google.bitcoin.core;

import com.google.bitcoin.store.BlockStoreException;
import com.google.bitcoin.store.FullPrunedBlockStore;
import com.google.bitcoin.store.H2FullPrunedBlockStore;
import com.google.bitcoin.store.MemoryFullPrunedBlockStore;
import com.google.bitcoin.utils.BlockFileLoader;
import com.google.bitcoin.utils.BriefLogFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * A tool for comparing the blocks which are accepted/rejected by bitcoind/bitcoinj
 * It is designed to run as a testnet-in-a-box network between a single bitcoind node and bitcoinj
 * It is not an automated unit-test because it requires a bit more set-up...read comments below
 */
public class BitcoindComparisonTool {
    private static final Logger log = LoggerFactory.getLogger(BitcoindComparisonTool.class);

    private static NetworkParameters params;
    private static FullPrunedBlockStore store;
    private static FullPrunedBlockChain chain;
    private static PeerGroup peers;
    private static Sha256Hash bitcoindChainHead;
    private static volatile Peer bitcoind;
    
    public static void main(String[] args) throws Exception {
        BriefLogFormatter.init();
        System.out.println("USAGE: bitcoinjBlockStoreLocation runLargeReorgs(1/0) [port=18444]");
        boolean runLargeReorgs = Integer.parseInt(args[1]) == 1;

        params = NetworkParameters.testNet2();
        /**
         * The following have been changed from the default and do not match bitcoind's default.
         * In order for this test to work, bitcoind should be recompiled with the same values you see here.
         * You can also opt to comment out these lines to use the default, however that will cause this tool to be
         * very significantly less efficient and useful (it will likely run forever trying to mine new blocks).
         * 
         * You could also simply use git apply to apply the test-patches included with bitcoind
         */
        
        // bnProofOfWorkLimit set in main.cpp
        params.proofOfWorkLimit = new BigInteger("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
        // Also set hashGenesisBlock to 0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206 one line up
        
        // constant (210000) in GetBlockValue (main.cpp)
        params.setSubsidyDecreaseBlockCount(150);
        
        // block.nNonce/block.nBits in LoadBlockIndex not the ones under "if (fTestNet)"
        params.genesisBlock.setNonce(2);
        params.genesisBlock.setDifficultyTarget(0x207fFFFFL);
        // Also set block.nTime    = 1296688602; in the same block
        
        File blockFile = File.createTempFile("testBlocks", ".dat");
        blockFile.deleteOnExit();
        
        FullBlockTestGenerator generator = new FullBlockTestGenerator(params);
        BlockAndValidityList blockList = generator.getBlocksToTest(true, runLargeReorgs, blockFile);
        Iterator<Block> blocks = new BlockFileLoader(params, Arrays.asList(blockFile));
        
        // Only needs to be set in bitcoinj
        params.allowEmptyPeerChains = true;
        
        try {
            store = new H2FullPrunedBlockStore(params, args[0], blockList.maximumReorgBlockCount);
            ((H2FullPrunedBlockStore)store).resetStore();
            //store = new MemoryFullPrunedBlockStore(params, blockList.maximumReorgBlockCount);
            chain = new FullPrunedBlockChain(params, store);
        } catch (BlockStoreException e) {
            e.printStackTrace();
            System.exit(1);
        }

        peers = new PeerGroup(params, chain);
        peers.setUserAgent("BlockAcceptanceComparisonTool", "1.0");
        
        // bitcoind MUST be on localhost or we will get banned as a DoSer
        peers.addAddress(new PeerAddress(InetAddress.getByName("localhost"), args.length > 2 ? Integer.parseInt(args[2]) : 18444));

        final Set<Sha256Hash> blocksRequested = Collections.synchronizedSet(new HashSet<Sha256Hash>());
        peers.addEventListener(new AbstractPeerEventListener() {
            @Override
            public void onPeerConnected(Peer peer, int peerCount) {
                super.onPeerConnected(peer, peerCount);
                log.info("bitcoind connected");
                bitcoind = peer;
            }

            @Override
            public void onPeerDisconnected(Peer peer, int peerCount) {
                super.onPeerDisconnected(peer, peerCount);
                log.error("bitcoind node disconnected!");
                System.exit(1);
            }
            
            @Override
            public Message onPreMessageReceived(Peer peer, Message m) {
                if (m instanceof HeadersMessage) {
                    for (Block block : ((HeadersMessage) m).getBlockHeaders())
                        bitcoindChainHead = block.getHash();
                    return null;
                } else if (m instanceof Block) {
                    log.error("bitcoind sent us a block it already had, make sure bitcoind has no blocks!");
                    System.exit(1);
                } else if (m instanceof GetDataMessage) {
                    for (InventoryItem item : ((GetDataMessage)m).items)
                        if (item.type == InventoryItem.Type.Block)
                            blocksRequested.add(item.hash);
                    return null;
                }
                return m;
            }
        });
        
        bitcoindChainHead = params.genesisBlock.getHash();
        
        // Connect to bitcoind and make sure it has no blocks
        peers.start();
        peers.setMaxConnections(1);
        peers.downloadBlockChain();
        
        while (bitcoind == null)
            Thread.sleep(50);
        
        ArrayList<Sha256Hash> locator = new ArrayList<Sha256Hash>(1);
        locator.add(params.genesisBlock.getHash());
        Sha256Hash hashTo = new Sha256Hash("0000000000000000000000000000000000000000000000000000000000000000");
                
        int differingBlocks = 0;
        int invalidBlocks = 0;
        for (BlockAndValidity block : blockList.list) {
            boolean threw = false;
            Block nextBlock = blocks.next();
            try {
                if (chain.add(nextBlock) != block.connects) {
                    log.error("Block didn't match connects flag on block \"" + block.blockName + "\"");
                    invalidBlocks++;
                }
            } catch (VerificationException e) {
                threw = true;
                if (!block.throwsException) {
                    log.error("Block didn't match throws flag on block \"" + block.blockName + "\"");
                    e.printStackTrace();
                    invalidBlocks++;
                } else if (block.connects) {
                    log.error("Block didn't match connects flag on block \"" + block.blockName + "\"");
                    e.printStackTrace();
                    invalidBlocks++;
                }
            }
            if (!threw && block.throwsException) {
                log.error("Block didn't match throws flag on block \"" + block.blockName + "\"");
                invalidBlocks++;
            } else if (!chain.getChainHead().getHeader().getHash().equals(block.hashChainTipAfterBlock)) {
                log.error("New block head didn't match the correct value after block \"" + block.blockName + "\"");
                invalidBlocks++;
            } else if (chain.getChainHead().getHeight() != block.heightAfterBlock) {
                log.error("New block head didn't match the correct height after block " + block.blockName);
                invalidBlocks++;
            }
            
            InventoryMessage message = new InventoryMessage(params);
            message.addBlock(nextBlock);
            bitcoind.sendMessage(message);
            // bitcoind doesn't request blocks inline so we can't rely on a ping for synchronization
            for (int i = 0; !blocksRequested.contains(nextBlock.getHash()); i++) {
                if (i % 20 == 19)
                    log.error("bitcoind still hasn't requested block " + block.blockName);
                Thread.sleep(50);
            }
            bitcoind.sendMessage(nextBlock);
            locator.clear();
            locator.add(bitcoindChainHead);
            bitcoind.sendMessage(new GetHeadersMessage(params, locator, hashTo));
            bitcoind.ping().get();
            if (!chain.getChainHead().getHeader().getHash().equals(bitcoindChainHead)) {
                differingBlocks++;
                log.error("bitcoind and bitcoinj acceptance differs on block \"" + block.blockName + "\"");
            }
            log.info("Block \"" + block.blockName + "\" completed processing");
        }
        
        log.info("Done testing.\n" +
        		"Blocks which were not handled the same between bitcoind/bitcoinj: " + differingBlocks + "\n" +
        		"Blocks which should/should not have been accepted but weren't/were: " + invalidBlocks);
        System.exit(differingBlocks > 0 || invalidBlocks > 0 ? 1 : 0);
    }
}