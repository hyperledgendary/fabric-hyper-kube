/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v0;

import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.fabric.fabctl.v0.command.ConfigTXGenCommand;
import org.hyperledger.fabric.fabctl.v0.command.PeerCommand;
import org.junit.jupiter.api.Test;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Build up a channel and join peers in a single flow.
 */
@Slf4j
public class CreateAndJoinChannelTest extends TestBase
{
    private static final String CHANNEL = "mychannel";

    /**
     * In a future pass, pull on a thread to load the peer env context from a properties file / yaml / json / resource bundle / etc. into a real struct, not a simple map.
     */
    private static final Map<String,String> ORG1_PEER1_CONTEXT =
            Map.ofEntries(
                    entry("FABRIC_LOGGING_SPEC",            "INFO"),
                    entry("CORE_PEER_TLS_ENABLED",          "true"),
                    entry("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/peers/org1-peer1.org1.example.com/tls/ca.crt"),
                    entry("CORE_PEER_ADDRESS",              "org1-peer1:7051"),
                    entry("CORE_PEER_LOCALMSPID",           "Org1MSP"),
                    entry("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp")
            );

    private static final Map<String,String> ORG1_PEER2_CONTEXT =
            Map.ofEntries(
                    entry("FABRIC_LOGGING_SPEC",            "INFO"),
                    entry("CORE_PEER_TLS_ENABLED",          "true"),
                    entry("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/peers/org1-peer2.org1.example.com/tls/ca.crt"),
                    entry("CORE_PEER_ADDRESS",              "org1-peer2:7051"),
                    entry("CORE_PEER_LOCALMSPID",           "Org1MSP"),
                    entry("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp")
            );

    private static final Map<String,String> ORG2_PEER1_CONTEXT =
            Map.ofEntries(
                    entry("FABRIC_LOGGING_SPEC",            "INFO"),
                    entry("CORE_PEER_TLS_ENABLED",          "true"),
                    entry("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/crypto-config/peerOrganizations/org2.example.com/peers/org2-peer1.org2.example.com/tls/ca.crt"),
                    entry("CORE_PEER_ADDRESS",              "org2-peer1:7051"),
                    entry("CORE_PEER_LOCALMSPID",           "Org2MSP"),
                    entry("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/crypto-config/peerOrganizations/org2.example.com/users/Admin@org2.example.com/msp")
            );

    private static final Map<String,String> ORG2_PEER2_CONTEXT =
            Map.ofEntries(
                    entry("FABRIC_LOGGING_SPEC",            "INFO"),
                    entry("CORE_PEER_TLS_ENABLED",          "true"),
                    entry("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/crypto-config/peerOrganizations/org2.example.com/peers/org2-peer2.org2.example.com/tls/ca.crt"),
                    entry("CORE_PEER_ADDRESS",              "org2-peer2:7051"),
                    entry("CORE_PEER_LOCALMSPID",           "Org2MSP"),
                    entry("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/crypto-config/peerOrganizations/org2.example.com/users/Admin@org2.example.com/msp")
            );

    private static final List<Map<String,String>> PEER_CONTEXTS =
            Arrays.asList(
                    ORG1_PEER1_CONTEXT,
                    ORG1_PEER2_CONTEXT,
                    ORG2_PEER1_CONTEXT,
                    ORG2_PEER2_CONTEXT
            );

    @Test
    public void testCreateAndJoinChannel() throws Exception
    {
        //
        // channel config
        //
        final ConfigTXGenCommand configChannel =
            new ConfigTXGenCommand(
                    "configtxgen",
                    "-channelID",             "mychannel",
                    "-profile",               "TwoOrgsChannel",
                    "-outputCreateChannelTx", "/var/hyperledger/fabric/channel-artifacts/mychannel.tx");

        // todo: crypto-config is not aligned with fabric/config paths between peer and configtxgen commands.
        // Fix this by generating the crypto spec locally, mounting an msp / identity into the context.
        final Map<String,String> txgenContext = Map.of("FABRIC_CFG_PATH", "/var/hyperledger/fabric");

        assertEquals(0, execute(configChannel, txgenContext));

        //
        // create the channel
        //
        final PeerCommand createChannel =
                new PeerCommand("peer",
                                "channel", "create",
                                "-c", CHANNEL,
                                "-o", "orderer1:6050",  // net config?
                                "-f", "/var/hyperledger/fabric/channel-artifacts/mychannel.tx",     // files on PVC ?
                                "--outputBlock", "/var/hyperledger/fabric/channel-artifacts/mychannel.block",
                                "--tls",
                                "--cafile", "/var/hyperledger/fabric/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com/tls/ca.crt");

        assertEquals(0, execute(createChannel, ORG1_PEER1_CONTEXT));


        //
        // Update the channel to anchors
        //
        final PeerCommand updateChannel =
                new PeerCommand("peer",
                                "channel", "update",
                                "-c", CHANNEL,
                                "-o", "orderer1:6050",  // net config?
                                "-f", "/var/hyperledger/fabric/channel-artifacts/Org1MSPanchors.tx",
                                "--tls",
                                "--cafile", "/var/hyperledger/fabric/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com/tls/ca.crt");

        assertEquals(0, execute(updateChannel, ORG1_PEER1_CONTEXT));


        //
        // Joins peers to channel
        //
        final PeerCommand joinChannel =
                new PeerCommand("peer",
                                "channel", "join",
                                "-b", "/var/hyperledger/fabric/channel-artifacts/mychannel.block");

        for (Map<String, String> context : PEER_CONTEXTS)
        {
            assertEquals(0, execute(joinChannel, context));
        }
    }

}
