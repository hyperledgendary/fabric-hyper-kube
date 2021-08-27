/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;

import java.io.IOException;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;

import org.hyperledger.fabric.fabctl.v1.network.Environment;
import org.hyperledger.fabric.fabctl.v1.network.NetworkConfig;
import org.hyperledger.fabric.fabctl.v1.network.OrdererConfig;
import org.hyperledger.fabric.fabctl.v1.network.PeerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * This carries forward the v0.InitFabricNetworkTest with improvements for storing crypto assets in configmaps
 * and secrets, rather than as pre-constructed files in a folder structure on disk.
 * 
 * In v0 when an admin command ran in the cluster, the CONTEXT for the job only described a set of k/v
 * environment variables.  Included in the env are pointers to local files on disk, which in turn were
 * substituted into the peer / orderer / etc. config files at runtime.  The files (pems, certs, keys, etc.)
 * were stored on disk, having been pre-generated on a local volume share by a call to `cryptogen`.
 * 
 * With this pass, the crypto assets will be uploaded to k8s and persisted as config maps / secrets.  For
 * instance, in the test network we will generate the following cm/secrets:
 * 
 * <pre>
 *   $ kubectl get cm 
 *   NAME                                DATA   AGE
 *   xyzzy-com.example.org1              1      43m
 *   xyzzy-com.example.org1.admin        1      45m
 *   xyzzy-com.example.org1.org1-peer1   1      47m
 *   xyzzy-com.example.org1.org1-peer2   1      47m
 *   xyzzy-com.example.org1.user1        1      45m
 *   xyzzy-com.example.org2              1      43m
 *   xyzzy-com.example.org2.admin        1      45m
 *   xyzzy-com.example.org2.org2-peer1   1      44m
 *   xyzzy-com.example.org2.org2-peer2   1      44m
 *   xyzzy-com.example.org2.user1        1      45m     
 * </pre>
 *
 * Each of these cm/secrets will contain a flattened map of crypto assets (pems, certs, keys, etc.) and 
 * metadata attributes (e.g. id=User1@org2.example.com) to help organize the instances in the namespace.
 * In general this is well aligned with the CAs, and reduces or eliminates the need to run cryptogen in 
 * "dev" mode for a wide swath of user / network initialization.
 * 
 * In v1 when "things happen" in the cluster, the execution CONTEXT describes both the ENVIRONMENT, as well 
 * as a notion of IDENTITY (principal / role / enrollment / user / msp context / ...).  It's not clear 
 * yet what exactly this scope should be named, so we'll call it XYZZY until a better, more accurate name 
 * can be coined.  Effectively: 
 * 
 *    invoke(function, env) --> invoke(function, context={ env, msp-context or XYZZY })
 *
 * How will xyzzy(msp?) profiles be constructed?  Used?  managed?   Not sure... use this test to try out 
 * a few approaches and pick one that works.  What is the proper NAME for an msp-context : XYZZY?
 *
 * NOTE: check up on k8s immutable configmaps to minimize traffic on the api controller, watches, etc.
 */
@Slf4j
public class NetworkUpTest extends TestBase
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
        final NetworkConfig network = new NetworkConfig("test-network");

        network.orderers.add(new OrdererConfig("orderer1", ORDERER1_ENVIRONMENT));
        network.orderers.add(new OrdererConfig("orderer2", ORDERER2_ENVIRONMENT));
        network.orderers.add(new OrdererConfig("orderer3", ORDERER3_ENVIRONMENT));

        network.peers.add(new PeerConfig("org1-peer1", ORG1_PEER1_ENVIRONMENT));
        network.peers.add(new PeerConfig("org1-peer2", ORG1_PEER2_ENVIRONMENT));
        network.peers.add(new PeerConfig("org2-peer1", ORG2_PEER1_ENVIRONMENT));
        network.peers.add(new PeerConfig("org2-peer2", ORG2_PEER2_ENVIRONMENT));

        return network;
    }

    @Test
    public void testPrettyPrintNetwork() throws Exception
    {
        final NetworkConfig network = describeSampleNetwork();
        log.info("Network Configuration:\n{}", yamlMapper.writeValueAsString(network));
    }

    /**
     * Constructing the network genesis block needs a few bits of crypto spec loaded
     * at runtime.  Effectively we need TLS and certs for all of the participants in
     * the network.
     *
     * configtx.yaml:
     *
     *  - organizations
     *      - example.com               msp dir
     *      - org1.example.com          msp dir
     *      - org2.example.com          msp dir
     *
     * - etcdraft:
     *      - orderer1.example.com      tls certs
     *      - orderer2.example.com      tls certs
     *      - orderer3.example.com      tls certs
     *
     *
     */
    @Test
    public void testConfigGenesisBlock() throws Exception
    {

    }











    /**
     * This can be improved, but it's not super relevant HOW the context is initialized.  Just experimenting here...
     */
    private static Environment loadEnvironment(final String path)
    {
        final Properties props = new Properties();

        try
        {
            props.load(NetworkUpTest.class.getResourceAsStream(path));
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
