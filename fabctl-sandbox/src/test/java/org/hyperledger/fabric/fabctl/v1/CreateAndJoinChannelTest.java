/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.fabric.fabctl.v0.command.ConfigTXGenCommand;
import org.hyperledger.fabric.fabctl.v0.command.PeerCommand;
import org.hyperledger.fabric.fabctl.v1.msp.MSPDescriptor;
import org.hyperledger.fabric.fabctl.v1.network.Environment;
import org.hyperledger.fabric.fabctl.v1.network.NetworkConfig;
import org.hyperledger.fabric.fabctl.v1.network.OrdererConfig;
import org.hyperledger.fabric.fabctl.v1.network.OrganizationConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Build up a channel and join peers in a single flow.
 * 
 * This code could be cleaned up with some refactoring and better organization, but it's kept intentionally 
 * "messy" just to keep all of the context clear when invoking fabric commands.
 *
 * TEST OUTCOMES:
 *
 * - It works!   Not very pretty, but at least it dynamically constructs the channel and joins peers.
 */
@Slf4j
public class CreateAndJoinChannelTest extends TestBase
{
    private static final String CHANNEL = "mychannel";

    @Test
    public void testCreateAndJoinChannel() throws Exception
    {
        final NetworkConfig network = new TestNetwork();

        // 
        // Channel config. 
        // 
        // When running configtxgen, we need to load the MSP descriptors for all orgs and orderers.
        //
        final List<MSPDescriptor> msps = new ArrayList<>();

        for (final OrganizationConfig org : network.organizations)
        {
            //
            // Add organization msp contexts
            //
            msps.addAll(org.msps);

            //
            // add orderer msp for raft TLS certificates
            //
            for (final OrdererConfig orderer : org.orderers)
            {
                msps.addAll(orderer.msps);
            }
        }
        
        assertEquals(0,
                     execute(new ConfigTXGenCommand("configtxgen",
                                                    "-channelID",             "mychannel",
                                                    "-profile",               "TwoOrgsChannel",
                                                    "-outputCreateChannelTx", "/var/hyperledger/fabric/channel-artifacts/mychannel.tx"),
                             new Environment()
                             {{
                                 put("FABRIC_CFG_PATH", "/var/hyperledger/fabric");
                             }},
                             msps));
        
        
        //
        // To create the channel we need to load MSP contexts for orderer1, org1-peer1, and Admin@org1.
        //
        // todo: this is ... so ... wrong.
        //
        final MSPDescriptor orderer1MSP = network.organizations.get(0).orderers.get(0).msps.get(0);
        final MSPDescriptor org1Peer1MSP = network.organizations.get(1).peers.get(0).msps.get(0);
        final MSPDescriptor org1Peer2MSP = network.organizations.get(1).peers.get(1).msps.get(0);
        final MSPDescriptor org2Peer1MSP = network.organizations.get(2).peers.get(0).msps.get(0);
        final MSPDescriptor org2Peer2MSP = network.organizations.get(2).peers.get(1).msps.get(0);

        //
        // Construct an MSP scope and configmap for the org1 and org2 admin user.
        //
        final MSPDescriptor org1AdminMSP =
                new MSPDescriptor("msp-com.example.org1.users.admin",
                                  new File("config/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com"));

        final MSPDescriptor org2AdminMSP =
                new MSPDescriptor("msp-com.example.org2.users.admin",
                                  new File("config/crypto-config/peerOrganizations/org2.example.com/users/Admin@org2.example.com"));

        createMSPConfigMap(org1AdminMSP);
        createMSPConfigMap(org2AdminMSP);


        //
        // Create the channel.
        //
        assertEquals(0,
                     execute(new PeerCommand("peer",
                                             "channel", "create",
                                             "-c", CHANNEL,
                                             "-o", "orderer1:6050",  // net config?
                                             "-f", "/var/hyperledger/fabric/channel-artifacts/mychannel.tx",     // files on PVC ?
                                             "--outputBlock", "/var/hyperledger/fabric/channel-artifacts/mychannel.block",
                                             "--tls",
                                             "--cafile", "/var/hyperledger/fabric/xyzzy/orderer1.example.com/tls/ca.crt"),

                             // k/v environment variables
                             new Environment()
                             {{
                                 put("FABRIC_LOGGING_SPEC",            "INFO");
                                 put("CORE_PEER_TLS_ENABLED",          "true");
                                 put("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/xyzzy/org1-peer1.org1.example.com/tls/ca.crt");
                                 put("CORE_PEER_ADDRESS",              "org1-peer1:7051");
                                 put("CORE_PEER_LOCALMSPID",           "Org1MSP");
                                 put("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/xyzzy/Admin@org1.example.com/msp");
                             }},

                             ///
                             // msp contexts unfurled by the pod's init container.
                             //
                             List.of(orderer1MSP,
                                     org1Peer1MSP,
                                     org1AdminMSP)
                     ));


        //
        // Update the channel anchor peers
        //
        assertEquals(0,
                     execute(new PeerCommand("peer",
                                             "channel", "update",
                                             "-c", CHANNEL,
                                             "-o", "orderer1:6050",  // net config?
                                             "-f", "/var/hyperledger/fabric/channel-artifacts/Org1MSPanchors.tx",
                                             "--tls",
                                             "--cafile", "/var/hyperledger/fabric/xyzzy/orderer1.example.com/tls/ca.crt"),

                             // k/v environment variables
                             new Environment()
                             {{
                                 put("FABRIC_LOGGING_SPEC",            "INFO");
                                 put("CORE_PEER_TLS_ENABLED",          "true");
                                 put("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/xyzzy/org1-peer1.org1.example.com/tls/ca.crt");
                                 put("CORE_PEER_ADDRESS",              "org1-peer1:7051");
                                 put("CORE_PEER_LOCALMSPID",           "Org1MSP");
                                 put("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/xyzzy/Admin@org1.example.com/msp");
                             }},

                             ///
                             // msp contexts unfurled by the pod's init container.
                             //
                             List.of(orderer1MSP,
                                     org1Peer1MSP,
                                     org1AdminMSP)
                     ));



        //
        // Joins peers to channel
        //
        // This code is intentionally "ugly" - just spelling out the approach here of what's being passed around 
        // in the execution scope. 
        //
        
        final PeerCommand joinCommand =
                new PeerCommand("peer",
                                "channel", "join",
                                "-b", "/var/hyperledger/fabric/channel-artifacts/mychannel.block");

        
        //
        // org1-peer1
        //
        assertEquals(0,
                     execute(joinCommand, 
                             new Environment()
                             {{
                                 put("FABRIC_LOGGING_SPEC",            "INFO");
                                 put("CORE_PEER_TLS_ENABLED",          "true");
                                 put("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/xyzzy/org1-peer1.org1.example.com/tls/ca.crt");
                                 put("CORE_PEER_ADDRESS",              "org1-peer1:7051");
                                 put("CORE_PEER_LOCALMSPID",           "Org1MSP");
                                 put("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/xyzzy/Admin@org1.example.com/msp");

                             }},
                             List.of(org1Peer1MSP,
                                     org1AdminMSP)));


        //
        // org1-peer2
        //
        assertEquals(0,
                     execute(joinCommand,
                             new Environment()
                             {{
                                 put("FABRIC_LOGGING_SPEC",            "INFO");
                                 put("CORE_PEER_TLS_ENABLED",          "true");
                                 put("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/xyzzy/org1-peer2.org1.example.com/tls/ca.crt");
                                 put("CORE_PEER_ADDRESS",              "org1-peer2:7051");
                                 put("CORE_PEER_LOCALMSPID",           "Org1MSP");
                                 put("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/xyzzy/Admin@org1.example.com/msp");

                             }},
                             List.of(org1Peer2MSP,
                                     org1AdminMSP)));



        //
        // org2-peer1
        //
        assertEquals(0,
                     execute(joinCommand,
                             new Environment()
                             {{
                                 put("FABRIC_LOGGING_SPEC",            "INFO");
                                 put("CORE_PEER_TLS_ENABLED",          "true");
                                 put("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/xyzzy/org2-peer1.org2.example.com/tls/ca.crt");
                                 put("CORE_PEER_ADDRESS",              "org2-peer1:7051");
                                 put("CORE_PEER_LOCALMSPID",           "Org2MSP");
                                 put("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/xyzzy/Admin@org2.example.com/msp");

                             }},
                             List.of(org2Peer1MSP,
                                     org2AdminMSP)));


        //
        // org2-peer2
        //
        assertEquals(0,
                     execute(joinCommand,
                             new Environment()
                             {{
                                 put("FABRIC_LOGGING_SPEC",            "INFO");
                                 put("CORE_PEER_TLS_ENABLED",          "true");
                                 put("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/xyzzy/org2-peer2.org2.example.com/tls/ca.crt");
                                 put("CORE_PEER_ADDRESS",              "org2-peer2:7051");
                                 put("CORE_PEER_LOCALMSPID",           "Org2MSP");
                                 put("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/xyzzy/Admin@org2.example.com/msp");

                             }},
                             List.of(org2Peer2MSP,
                                     org2AdminMSP)));
    }

    /**
     * Create an MSP config map from an MSP Descriptor
     *
     * todo: add some metadata labels to the configmap (e.g. id, org, name, type, etc .etc. )
     */
    private ConfigMap createMSPConfigMap(final MSPDescriptor msp) throws Exception
    {
        final String cmName = msp.name;
        final String mspKey = msp.name + ".yaml";
        final String mspVal = yamlMapper.writeValueAsString(msp);

        return client.configMaps()
                     .createOrReplace(new ConfigMapBuilder()
                                              .withNewMetadata()
                                              .withName(cmName)
                                              .endMetadata()
                                              .withImmutable(true)
                                              .withData(Map.of(mspKey, mspVal))
                                              .build());
    }

}
