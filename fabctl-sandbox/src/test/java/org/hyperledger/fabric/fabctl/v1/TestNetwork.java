/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;


import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Properties;
import org.hyperledger.fabric.fabctl.v1.network.*;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * test-network is a NetworkConfiguration matching the topology of fabric-samples test-network.
 *
 * This is just a convenience for inflating a sample network for the scope of unit tests.
 */
class TestNetwork extends NetworkConfig
{
    private TestNetwork(final String name)
    {
        super(name);
        init();
    }

    TestNetwork()
    {
        this("test-network");
    }

    private void init()
    {
        try
        {
            final OrganizationConfig ordererOrg = new OrganizationConfig("OrdererOrg");
            ordererOrg.mspID = "OrdererMSP";
//            ordererOrg.ecertCA = new URL("http://ordererca:5555");     // ???
            ordererOrg.orderers.add(new OrdererConfig("orderer1", ORDERER1_ENVIRONMENT));
            ordererOrg.orderers.add(new OrdererConfig("orderer2", ORDERER2_ENVIRONMENT));
            ordererOrg.orderers.add(new OrdererConfig("orderer3", ORDERER3_ENVIRONMENT));

            final OrganizationConfig org1 = new OrganizationConfig("Org1");
            org1.mspID = "Org1MSP";
//            org1.ecertCA = new URL("http://org1ca:5050");       // ???
            org1.peers.add(new PeerConfig("org1-peer1", ORG1_PEER1_ENVIRONMENT));
            org1.peers.add(new PeerConfig("org1-peer2", ORG1_PEER2_ENVIRONMENT));

            final OrganizationConfig org2 = new OrganizationConfig("Org2");
            org2.mspID = "Org2MSP";
//            org2.ecertCA = new URL("https://org2ca:5050");
            org2.peers.add(new PeerConfig("org2-peer1", ORG2_PEER1_ENVIRONMENT));
            org2.peers.add(new PeerConfig("org2-peer2", ORG2_PEER2_ENVIRONMENT));

            organizations.add(ordererOrg);
            organizations.add(org1);
            organizations.add(org2);
        }
        catch (Exception ex)
        {
            fail("Could not load test network config", ex);
        }
    }

    //
    // These can come from any k/v source (e.g. resource bundle, as below)
    // Note that the env scope has now has a "generic" path to the crypto assets, which will be filled in
    // by the location of the mounted config map when run in the cluster.
    //
    // (Just showing one env here for illustration.)
    // For clarity, some of the common overrides have been propagated into core.yaml and orderer.yaml
    // todo: migrate common peer config params to core.yaml
    //
    private static final Environment ORDERER1_ENVIRONMENT = new Environment()
    {{
        // These are the same for all orderers, but could be useful to override from a dynamic context.
        put("FABRIC_CFG_PATH",                  "/var/hyperledger/fabric/config");
        put("FABRIC_LOGGING_SPEC",              "debug:cauthdsl,policies,msp,common.configtx,common.channelconfig=info");
        put("ORDERER_GENERAL_BOOTSTRAPFILE",    "/var/hyperledger/fabric/channel-artifacts/genesis.block");

        // these vary across orderers
        put("ORDERER_FILELEDGER_LOCATION",      "/var/hyperledger/fabric/data/orderer1");
        put("ORDERER_CONSENSUS_WALDIR",         "/var/hyperledger/fabric/data/orderer1/etcdraft/wal");
        put("ORDERER_CONSENSUS_SNAPDIR",        "/var/hyperledger/fabric/data/orderer1/etcdraft/wal");

        // xyzzy-context attributes.   Fulfilled via dynamic configmap / secret.
        put("ORDERER_GENERAL_LOCALMSPID",       "OrdererMSP");
        put("ORDERER_GENERAL_LOCALMSPDIR",      "/var/hyperledger/fabric/xyzzy/orderer1.example.com/msp");
        put("ORDERER_GENERAL_TLS_PRIVATEKEY",   "/var/hyperledger/fabric/xyzzy/orderer1.example.com/tls/server.key");
        put("ORDERER_GENERAL_TLS_CERTIFICATE",  "/var/hyperledger/fabric/xyzzy/orderer1.example.com/tls/server.crt");
        put("ORDERER_GENERAL_TLS_ROOTCAS",      "/var/hyperledger/fabric/xyzzy/orderer1.example.com/tls/ca.crt");
        put("ORDERER_GENERAL_TLS_ENABLED",      "true");
    }};

    private static final Environment ORDERER2_ENVIRONMENT   = loadEnvironment("/config/v1/orderer2.properties");
    private static final Environment ORDERER3_ENVIRONMENT   = loadEnvironment("/config/v1/orderer3.properties");
    private static final Environment ORG1_PEER1_ENVIRONMENT = loadEnvironment("/config/v1/org1-peer1.properties");
    private static final Environment ORG1_PEER2_ENVIRONMENT = loadEnvironment("/config/v1/org1-peer2.properties");
    private static final Environment ORG2_PEER1_ENVIRONMENT = loadEnvironment("/config/v1/org2-peer1.properties");
    private static final Environment ORG2_PEER2_ENVIRONMENT = loadEnvironment("/config/v1/org2-peer2.properties");

    /**
     * This can be improved, but it's not super relevant HOW the context is initialized.  Just experimenting here...
     */
    protected static Environment loadEnvironment(final String path)
    {
        final Properties props = new Properties();

        try
        {
            props.load(CryptoXYZZYConfigMapTest.class.getResourceAsStream(path));
        }
        catch (IOException ex)
        {
            fail("Could not load resource bundle " + path, ex);
        }

        final Environment environment = new Environment();
        for (Object o : props.keySet())
        {
            final String key = o.toString();
            environment.put(key, props.getProperty(key));
        }

        return environment;
    }

}
