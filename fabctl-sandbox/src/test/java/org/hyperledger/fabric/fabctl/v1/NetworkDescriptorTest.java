package org.hyperledger.fabric.fabctl.v1;

import lombok.extern.slf4j.Slf4j;
import org.hyperledger.fabric.fabctl.v1.network.*;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@Slf4j
public class NetworkDescriptorTest extends TestBase
{
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


    private NetworkConfig describeSampleNetwork()
    {
        final OrganizationConfig ordererOrg = new OrganizationConfig("OrdererOrg");
        ordererOrg.mspID = "OrdererMSP";
        ordererOrg.orderers.add(new OrdererConfig("orderer1", ORDERER1_ENVIRONMENT));
        ordererOrg.orderers.add(new OrdererConfig("orderer2", ORDERER2_ENVIRONMENT));
        ordererOrg.orderers.add(new OrdererConfig("orderer3", ORDERER3_ENVIRONMENT));

        final OrganizationConfig org1 = new OrganizationConfig("Org1");
        org1.mspID = "Org1MSP";
        org1.peers.add(new PeerConfig("org1-peer1", ORG1_PEER1_ENVIRONMENT));
        org1.peers.add(new PeerConfig("org1-peer2", ORG1_PEER2_ENVIRONMENT));

        final OrganizationConfig org2 = new OrganizationConfig("Org2");
        org2.mspID = "Org2MSP";
        org2.peers.add(new PeerConfig("org2-peer1", ORG2_PEER1_ENVIRONMENT));
        org2.peers.add(new PeerConfig("org2-peer2", ORG2_PEER2_ENVIRONMENT));

        final NetworkConfig network = new NetworkConfig("test-network");
        network.organizations.add(ordererOrg);
        network.organizations.add(org1);
        network.organizations.add(org2);

        return network;
    }

    @Test
    public void testPrettyPrintNetwork() throws Exception
    {
        final NetworkConfig network = describeSampleNetwork();
        log.info("Network Configuration:\n{}", yamlMapper.writeValueAsString(network));
    }
}
