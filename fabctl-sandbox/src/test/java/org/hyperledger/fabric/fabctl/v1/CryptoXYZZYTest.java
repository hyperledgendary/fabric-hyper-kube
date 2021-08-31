/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;

import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.TreeMap;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.compress.utils.IOUtils;
import org.hyperledger.fabric.fabctl.v1.network.Environment;
import org.hyperledger.fabric.fabctl.v1.network.NetworkConfig;
import org.hyperledger.fabric.fabctl.v1.network.OrdererConfig;
import org.hyperledger.fabric.fabctl.v1.network.PeerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
 *
 *
 * OUTCOMES FROM THIS TEST:
 *
 * - Managing MSP and TLS folders is burdensome, especially across the boundary of files residing locally
 *   and the use of configmaps / secrets to dynamically load contents into the k8s cluster.
 *
 * - A lot of the MSP "config" issues stem from the reliance on hard-coded paths to the MSP assets within
 *   the fabric binaries.  E.g. configtx.yaml specifies a single parameter "MSPDir" for the base pointer,
 *   and all files are read from this folder via convention.  This would be easier IF the config files
 *   could accept overrides for the individual file paths within the MSP folder, rather than relying on
 *   folder naming conventions.
 *
 * - What would really make ALL OF THIS EASY is if the peer and admin commands could accept a tar / zip
 *   archive of the msp and TLS assets as a single bundle, rather than a bunch of files.  In that case
 *   we could just zip up a folder structure with the file contents, mount a cm/secret in k8s, and
 *   instruct the core/configtx.yaml to unfurl the archive at runtime.
 */
@Slf4j
public class CryptoXYZZYTest extends TestBase
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
     * Try this scheme out by constructing config maps for each of the identities
     * above.  We'll use crypto assets from /config/crypto-config to get started.
     * (These should come from src/test/resources/fixtures/crypto-config or some other
     * stable location from run to run.)
     *
     * TODO: What this REALLY MEANS is that configtx.yaml should be considered part of the NetworkConfiguration, and generated dynamically!
     */

    /**
     * Just create one.
     * 
     * Should this be binaryData? 
     * Should this be a k8s secret? 
     * Which labels should be applied?
     * How and where to encode the CamelCaseUser@Name.ValueS_inK8s?
     * This is not great.
     */
    @Test
    public void testConstructXYZZYConfigMap() throws Exception
    {
        ConfigMap xyzzy = null;
        try
        {
            //
            // Note that the config map structure needs to be flattened.  We'll need to re-arrange it when mounting 
            // the cm as a volume in the Jobs and deployments.  Each entry in the cm should be the contents of the 
            // crypto spec retrieved from the CA.
            //
            // oof.  This is going to be a pain to pack/unpack.
            //
            final Map<String,String> data = new TreeMap<>();
            data.put("tls-ca.crt",
                     "-----BEGIN CERTIFICATE-----\n" +
                    "MIICRDCCAeqgAwIBAgIRAOG6gafJUPW92WYvZQwCL6kwCgYIKoZIzj0EAwIwbDEL\n" +
                    "MAkGA1UEBhMCVVMxEzARBgNVBAgTCkNhbGlmb3JuaWExFjAUBgNVBAcTDVNhbiBG\n" +
                    "cmFuY2lzY28xFDASBgNVBAoTC2V4YW1wbGUuY29tMRowGAYDVQQDExF0bHNjYS5l\n" +
                    "eGFtcGxlLmNvbTAeFw0yMTA4MjcxNTE2MDBaFw0zMTA4MjUxNTE2MDBaMGwxCzAJ\n" +
                    "BgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1TYW4gRnJh\n" +
                    "bmNpc2NvMRQwEgYDVQQKEwtleGFtcGxlLmNvbTEaMBgGA1UEAxMRdGxzY2EuZXhh\n" +
                    "bXBsZS5jb20wWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATbC+j0nmDcykM9iGfO\n" +
                    "OR2yNM+3YQ2EaUPQKF7Lu55kof6OwtwQuuf4d7cQ+S+393vtV7g5s/HDtmlE145q\n" +
                    "f19jo20wazAOBgNVHQ8BAf8EBAMCAaYwHQYDVR0lBBYwFAYIKwYBBQUHAwIGCCsG\n" +
                    "AQUFBwMBMA8GA1UdEwEB/wQFMAMBAf8wKQYDVR0OBCIEIA1dwQnb3EmesgrVqrQp\n" +
                    "0qqVZZcbqe2Jv7FpsRXBFIZ3MAoGCCqGSM49BAMCA0gAMEUCIQDHfw5xbKR5Xlj8\n" +
                    "l0CcOC+Qg6YbEwE6laTU6THoehWcMgIgPcOx4GhSMjqnU0Hj3wx+iqLPAyXrHalm\n" +
                    "A1gQrIobVwQ=\n" +
                    "-----END CERTIFICATE-----");

            data.put("tls-client.crt",
                     "-----BEGIN PRIVATE KEY-----\n" +
                     "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgiLt0yU/g3S+JEDPT\n" +
                     "s7Tu7qhRYr8+dZnKQDVwskDGCtmhRANCAAQ3EftUZSqq/CbbXjpPvY7BIYidUL23\n" +
                     "wqNEOtQuNzWR+qcOlnpuY/X44nOWdLfEX8l+JcG+xJEnIOvUM7c36emE\n" +
                     "-----END PRIVATE KEY-----\n");

            data.put("tls-client.key",       "BEGIN PRIVATE KEY ...");
            data.put("msp-oof.pem",          "BEGIN CERT ...");
            data.put("msp-cacerts-ca.pem",   "foo");
            data.put("msp-keystore-priv_sk", "bar");
            data.put("msp-signcerts",        "baz");
            
            // ... 
            
            // @formatter:off
            xyzzy = client.configMaps()
                          .createOrReplace(new ConfigMapBuilder()
                              .withNewMetadata()
                                    .withName("xyzzy-com.example.orderer1")
                                    .withLabels(Map.of(
                                      "type", "xyzzy",                  // eventual CRD? 
//                                      "user", "Admin@example.com",      // can't encode everything in cm.name
                                      "id", "orderer1.example.com"      // where mounted in /xyzzy/
                                    ))
                              .endMetadata()
                              .withImmutable(true)
                              .withData(data)
                              .build());
            // @formatter:on

            log.info("Created configmap:\n{}", yamlMapper.writeValueAsString(xyzzy));

            assertNotNull(xyzzy);
            assertNotNull(xyzzy.getMetadata());
            // ...
        }
        finally
        {
            client.configMaps().delete(xyzzy);
        }
    }

    /**
     * Inflate an xyzzy config map from a folder created by cryptogen.
     *
     * This is a little easier, but still can / should be improved:
     *  - traverse the folder structure and add everything.
     *  - convert file path -> data.key (and inverse when re-mounting on disk?)
     *  - Where can we store the metadata attributes, e.g. "Admin@org1.example.com" ?
     *  - we should probably use secrets not config maps
     *  - unfurling the folder structure is going to be a real pain.  Write a routine to do so (see above...)
     *
     * config.yaml will be a problem.  We are removing the "name" bits from the files.
     *
     *  NodeOUs:
     *   Enable: true
     *   ClientOUIdentifier:
     *     Certificate: cacerts/ca.example.com-cert.pem
     *     OrganizationalUnitIdentifier: client
     *   PeerOUIdentifier:
     *     Certificate: cacerts/ca.example.com-cert.pem
     *     OrganizationalUnitIdentifier: peer
     *   AdminOUIdentifier:
     *     Certificate: cacerts/ca.example.com-cert.pem
     *     OrganizationalUnitIdentifier: admin
     *   OrdererOUIdentifier:
     *     Certificate: cacerts/ca.example.com-cert.pem
     *     OrganizationalUnitIdentifier: orderer
     *
     */
    @Test
    public void testInflateXYZZYFromCryptogenOutput() throws Exception
    {
        // todo: from resource bundle / test fixtures.
        final File cryptoDir = new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com");
        final File mspDir = new File(cryptoDir, "msp");
        final File tlsDir = new File(cryptoDir, "tls");

        log.info("loading xyzzy from {}", cryptoDir);

        assertTrue(mspDir.exists());
        assertTrue(tlsDir.exists());

        //
        // load file contents into the configmap .data.  This should probably traverse the
        // file structure and add all contents, rather than hardwiring known keys to paths.
        //
        final Map<String, String> data = new TreeMap<>();
        data.put("msp-cacerts-cert.pem",    load(new File(mspDir, "cacerts/ca.example.com-cert.pem")));
        data.put("msp-keystore-priv_sk",    load(new File(mspDir, "keystore/priv_sk")));
        data.put("msp-signcerts-cert.pem",  load(new File(mspDir, "signcerts/orderer1.example.com-cert.pem")));
        data.put("msp-tlscerts-cert.pem",   load(new File(mspDir, "tlscacerts/tlsca.example.com-cert.pem")));
        data.put("msp-config.yaml",         load(new File(mspDir, "config.yaml")));
        data.put("tls-ca.crt",              load(new File(tlsDir, "ca.crt")));
        data.put("tls-server.crt",          load(new File(tlsDir, "server.crt")));
        data.put("tls-server.key",          load(new File(tlsDir, "server.key")));

        final Map<String, String> labels = new TreeMap<>();
        labels.put("foo", "bar");

        ConfigMap xyzzy = null;
        try
        {
            // @formatter:off
            xyzzy = client.configMaps()
                          .createOrReplace(new ConfigMapBuilder()
                                                   .withNewMetadata()
                                                        .withName("xyzzy-com.example.orderer1")
                                                   .withLabels(labels)
                                                   .endMetadata()
                                                   .withImmutable(true)
                                                   //.withBinaryData(data)
                                                   .withData(data)
                                                   .build());
            // @formatter:on

            log.info("Created configmap:\n{}", yamlMapper.writeValueAsString(xyzzy));
        }
        finally
        {
            client.configMaps().delete(xyzzy);
        }
    }

    /**
     * Here's another approach:  let's create TWO cm/secrets for each xyzzy:   (This is like: xyzzy-tls and xyzzy-msp)
     * This is a little different than the above approach where one config map had a more complicated path structure.
     *
     * - xyzzy-tls-com.example.org1
     * - xyzzy-msp-com.example.org1
     */
    @Test
    public void testManageMultipleCMsPerXYZZY() throws Exception
    {
        final File cryptoDir = new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com");
        final File mspDir = new File(cryptoDir, "msp");
        final File tlsDir = new File(cryptoDir, "tls");

        log.info("loading xyzzy from {}", cryptoDir);

        assertTrue(mspDir.exists());
        assertTrue(tlsDir.exists());


        //
        // In the approach above, the config map keys also encoded the directory structure of the xyzzy
        // using '-' as path separators.  Since the convention for mounting this cm into the pods is known,
        // we don't actually need the folder structure - just the pems and key files at known locations.
        //

        //
        // Load the file contents into TWO config maps, one for TLS and one for MSP assets.
        //
        final String mspName = "xyzzy-msp-com.example.orderer1";
        final Map<String, String> mspData = new TreeMap<>();
        mspData.put("cacert.pem",   load(new File(mspDir, "cacerts/ca.example.com-cert.pem")));
        mspData.put("keystore.key", load(new File(mspDir, "keystore/priv_sk")));
        mspData.put("signcert.pem", load(new File(mspDir, "signcerts/orderer1.example.com-cert.pem")));
        mspData.put("tlscert.pem",  load(new File(mspDir, "tlscacerts/tlsca.example.com-cert.pem")));
        mspData.put("config.yaml",  load(new File(mspDir, "config.yaml")));

        final String tlsName = "xyzzy-tls-com.example.orderer1";
        final Map<String, String> tlsData = new TreeMap<>();
        tlsData.put("ca.crt",       load(new File(tlsDir, "ca.crt")));
        tlsData.put("server.crt",   load(new File(tlsDir, "server.crt")));
        tlsData.put("server.key",   load(new File(tlsDir, "server.key")));

        final Map<String, String> labels = new TreeMap<>();
        labels.put("foo", "bar");


        ConfigMap tls = null;
        ConfigMap msp = null;
        try
        {
            // @formatter:off
            tls = client.configMaps()
                        .create(new ConfigMapBuilder()
                                        .withNewMetadata()
                                            .withName(tlsName)
                                        .withLabels(labels)
                                        .endMetadata()
                                        .withImmutable(true)
                                        .withData(tlsData)
                                        .build());
            // @formatter:on

            log.info("Created TLS configmap:\n{}", yamlMapper.writeValueAsString(tls));

            assertNotNull(tls);
            // additional checks ...

            // @formatter:off
            msp = client.configMaps()
                        .create(new ConfigMapBuilder()
                                        .withNewMetadata()
                                            .withName(mspName)
                                        .withLabels(labels)
                                        .endMetadata()
                                        .withImmutable(true)
                                        .withData(mspData)
                                        .build());
            // @formatter:on

            log.info("Created configmap:\n{}", yamlMapper.writeValueAsString(msp));
        }
        finally
        {
            client.configMaps().withName(mspName).delete();
            client.configMaps().withName(tlsName).delete();
        }
    }

    /**
     * In addition to juggling tls and msp configmaps, we need to retain a config.yaml with pointers to the
     * local keys and certificates.
     *
     * Try this as a data structure.  With this approach the fields will never change and this could just as
     * well have been hardcoded as a String constant.
     */
    @Data
    @JsonNaming(PropertyNamingStrategy.UpperCamelCaseStrategy.class)
    private static class MSPConfig
    {
        public final NodeOUs NodeOUs = new NodeOUs();
    }

    @Data
    @JsonNaming(PropertyNamingStrategy.UpperCamelCaseStrategy.class)
    private static final class NodeOUs
    {
        public final boolean Enable = true;

        public final OUIdentifier ClientOUIdentifier    = new OUIdentifier("client");
        public final OUIdentifier PeerOUIdentifier      = new OUIdentifier("peer");
        public final OUIdentifier AdminOUIdentifier     = new OUIdentifier("admin");
        public final OUIdentifier OrdererOUIdentifier   = new OUIdentifier("orderer");
    }

    @Data
    @JsonNaming(PropertyNamingStrategy.UpperCamelCaseStrategy.class)
    private static final class OUIdentifier
    {
        public final String Certificate = "cacerts/cert.pem";
        public final String OrganizationalUnitIdentifier;
    }

    @Test
    public void testRenderMSPConfig() throws Exception
    {
        final MSPConfig config = new MSPConfig();

        log.info("pretty config:\n{}", yamlMapper.writeValueAsString(config));
    }


    /**
     * Two configmaps per xyzzy, with a dynamically generated msp config.yaml
     */
    @Test
    public void testManageMultipleCMsPerXYZZYAndDynamicMSPConfig() throws Exception
    {
        final File cryptoDir = new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com");
        final File mspDir = new File(cryptoDir, "msp");
        final File tlsDir = new File(cryptoDir, "tls");

        log.info("loading xyzzy from {}", cryptoDir);

        assertTrue(mspDir.exists());
        assertTrue(tlsDir.exists());

        //
        // Load the file contents into TWO config maps, one for TLS and one for MSP assets.
        //
        final String mspName = "xyzzy-msp-com.example.orderer1";
        final Map<String, String> mspData = new TreeMap<>();
        mspData.put("cacert.pem",   load(new File(mspDir, "cacerts/ca.example.com-cert.pem")));
        mspData.put("keystore.key", load(new File(mspDir, "keystore/priv_sk")));
        mspData.put("signcert.pem", load(new File(mspDir, "signcerts/orderer1.example.com-cert.pem")));
        mspData.put("tlscert.pem",  load(new File(mspDir, "tlscacerts/tlsca.example.com-cert.pem")));


        //
        // Dynamically generate the msp config file in the configmap.
        //
        final MSPConfig mspConfig = new MSPConfig();
        log.info("Using msp config:\n{}", mspConfig);
        mspData.put("config.yaml",  yamlMapper.writeValueAsString(mspConfig));


        final String tlsName = "xyzzy-tls-com.example.orderer1";
        final Map<String, String> tlsData = new TreeMap<>();
        tlsData.put("ca.crt",       load(new File(tlsDir, "ca.crt")));
        tlsData.put("server.crt",   load(new File(tlsDir, "server.crt")));
        tlsData.put("server.key",   load(new File(tlsDir, "server.key")));

        final Map<String, String> labels = new TreeMap<>();
        labels.put("foo", "bar");


        ConfigMap tls = null;
        ConfigMap msp = null;
        try
        {
            // @formatter:off
            tls = client.configMaps()
                        .create(new ConfigMapBuilder()
                                        .withNewMetadata()
                                        .withName(tlsName)
                                        .withLabels(labels)
                                        .endMetadata()
                                        .withImmutable(true)
                                        .withData(tlsData)
                                        .build());
            // @formatter:on

            log.info("Created TLS configmap:\n{}", yamlMapper.writeValueAsString(tls));

            assertNotNull(tls);
            // additional checks ...

            // @formatter:off
            msp = client.configMaps()
                        .create(new ConfigMapBuilder()
                                        .withNewMetadata()
                                        .withName(mspName)
                                        .withLabels(labels)
                                        .endMetadata()
                                        .withImmutable(true)
                                        .withData(mspData)
                                        .build());
            // @formatter:on

            log.info("Created configmap:\n{}", yamlMapper.writeValueAsString(msp));
        }
        finally
        {
            client.configMaps().withName(mspName).delete();
            client.configMaps().withName(tlsName).delete();
        }
    }



    /**
     * Create a few xyzzy config maps / secrets - enough for configtxgen.
     *
     * Oof!  this is real pain.  Each of the crypto folders has a different file structure.  Let's maintain a
     * mapping from cm key name to crypto asset file.
     */
    private static final Map<String, String> ORDERER1_MSP_MAPPING = new TreeMap<>()
    {{
        put("cacert.pem",   "cacerts/ca.example.com-cert.pem");
        put("keystore.key", "keystore/priv_sk");
        put("signcert.pem", "signcerts/orderer1.example.com-cert.pem");
        put("tlscert.pem",  "tlscacerts/tlsca.example.com-cert.pem");
        put("config.yaml",  "config.yaml");
    }};

    private static final Map<String,String> TLS_MAPPING = new TreeMap<>()
    {{
        put("ca.crt",       "ca.crt");
        put("server.crt",   "server.crt");
        put("server.key",   "server.key");
    }};


    @Test
    public void testConstructXYZZYWithMappedFiles() throws Exception
    {
        try
        {
            createXYZZY("com.example.orderer1",
                        new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com"),
                        ORDERER1_MSP_MAPPING);

            final ConfigMap msp =
                    client.configMaps()
                          .withName("xyzzy-msp-com.example.orderer1")
                          .get();
            
            assertNotNull(msp);
            assertNotNull(msp.getMetadata());
            assertEquals("xyzzy-msp-com.example.orderer1", msp.getMetadata().getName());
            assertNotNull(msp.getData());
            assertTrue(msp.getData().containsKey("cacert.pem"));
            assertTrue(msp.getData().containsKey("keystore.key"));
            assertTrue(msp.getData().containsKey("signcert.pem"));
            assertTrue(msp.getData().containsKey("tlscert.pem"));
            assertTrue(msp.getData().containsKey("config.yaml"));

            
            final ConfigMap tls = 
                    client.configMaps()
                          .withName("xyzzy-tls-com.example.orderer1")
                          .get();

            assertNotNull(tls);
            assertNotNull(tls.getMetadata());
            assertEquals("xyzzy-tls-com.example.orderer1", tls.getMetadata().getName());
            assertNotNull(tls.getData());
            assertTrue(tls.getData().containsKey("ca.crt"));
            assertTrue(tls.getData().containsKey("server.crt"));
            assertTrue(tls.getData().containsKey("server.key"));
        }
        finally
        {
            client.configMaps().withName("xyzzy-tls-com.example.orderer1").delete();
            client.configMaps().withName("xyzzy-msp-com.example.orderer1").delete();
        }
    }

    private void createXYZZY(final String name, final File cryptoDir, final Map<String,String> mspFileMapping) throws Exception
    {
        log.info("loading xyzzy from {}", cryptoDir);

        //
        // Create a config map for the TLS crypto assets.
        //
        final ConfigMap tls =
                createMappedConfigMap("xyzzy-tls-" + name,
                                      new File(cryptoDir, "tls"),
                                      TLS_MAPPING);

        log.info("Created TLS configmap: \n{}", yamlMapper.writeValueAsString(tls));


        //
        // Config map for MSP assets, with mapped file paths.
        //
        final ConfigMap msp =
                createMappedConfigMap("xyzzy-msp-" + name,
                                      new File(cryptoDir, "msp"),
                                      mspFileMapping);

        log.info("Created MSP configmap: \n{}", yamlMapper.writeValueAsString(msp));
    }

    private ConfigMap createMappedConfigMap(final String name, final File basedir, final Map<String, String> keymap)
            throws IOException
    {
        log.info("Creating mapped config map {} in folder {} with keymap {}", name, basedir, keymap);

        //
        // Load all mapped files into the config map
        //
        final Map<String,String> data = new TreeMap<>();
        for (Entry<String,String> e : keymap.entrySet())
        {
            //
            // todo: refactor this code and approach to handle config.yaml
            //
            // This is an awful hack but we'll override whatever the user had locally for the msp config yaml.
            //
            if (e.getKey().equalsIgnoreCase("config.yaml"))
            {
                data.put("config.yaml", yamlMapper.writeValueAsString(new MSPConfig()));
                continue;
            }

            data.put(e.getKey(), load(new File(basedir, e.getValue())));
        }

        // @formatter:off
        final ConfigMap cm =
                client.configMaps()
                      .create(new ConfigMapBuilder()
                              .withNewMetadata()
                                .withName(name)
                              .endMetadata()
                              .withImmutable(true)
                              .withData(data)
                              .build());
        // @formatter:on

        return cm;
    }

    /**
     * Set up xyzzy residue for all the nodes in a network.
     */

    private static final Map<String, String> ORDERER2_MSP_MAPPING = new TreeMap<>()
    {{
        put("cacert.pem",   "cacerts/ca.example.com-cert.pem");
        put("keystore.key", "keystore/priv_sk");
        put("signcert.pem", "signcerts/orderer2.example.com-cert.pem");
        put("tlscert.pem",  "tlscacerts/tlsca.example.com-cert.pem");
        put("config.yaml",  "config.yaml");
    }};

    private static final Map<String, String> ORDERER3_MSP_MAPPING = new TreeMap<>()
    {{
        put("cacert.pem",   "cacerts/ca.example.com-cert.pem");
        put("keystore.key", "keystore/priv_sk");
        put("signcert.pem", "signcerts/orderer3.example.com-cert.pem");
        put("tlscert.pem",  "tlscacerts/tlsca.example.com-cert.pem");
        put("config.yaml",  "config.yaml");
    }};

    @Test
    public void testCreateXYZZYForAllNetworkNodes() throws Exception
    {
        try
        {
            createXYZZY("com.example.orderer1",
                        new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com"),
                        ORDERER1_MSP_MAPPING);

            assertNotNull(client.configMaps().withName("xyzzy-msp-com.example.orderer1").get());
            assertNotNull(client.configMaps().withName("xyzzy-tls-com.example.orderer1").get());

            createXYZZY("com.example.orderer2",
                        new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer2.example.com"),
                        ORDERER2_MSP_MAPPING);

            assertNotNull(client.configMaps().withName("xyzzy-msp-com.example.orderer2").get());
            assertNotNull(client.configMaps().withName("xyzzy-tls-com.example.orderer2").get());

            createXYZZY("com.example.orderer3",
                        new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer3.example.com"),
                        ORDERER3_MSP_MAPPING);

            assertNotNull(client.configMaps().withName("xyzzy-msp-com.example.orderer3").get());
            assertNotNull(client.configMaps().withName("xyzzy-tls-com.example.orderer3").get());

        }
        finally
        {
            client.configMaps().withName("xyzzy-msp-com.example.orderer1").delete();
            client.configMaps().withName("xyzzy-tls-com.example.orderer1").delete();
            client.configMaps().withName("xyzzy-msp-com.example.orderer2").delete();
            client.configMaps().withName("xyzzy-tls-com.example.orderer2").delete();
            client.configMaps().withName("xyzzy-msp-com.example.orderer3").delete();
            client.configMaps().withName("xyzzy-tls-com.example.orderer3").delete();
        }
    }

    /**
     * This can be improved, but it's not super relevant HOW the context is initialized.  Just experimenting here...
     */
    private static Environment loadEnvironment(final String path)
    {
        final Properties props = new Properties();

        try
        {
            props.load(CryptoXYZZYTest.class.getResourceAsStream(path));
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

    private String load(File path) throws IOException
    {
        try (final ByteArrayOutputStream baos = new ByteArrayOutputStream();
             final FileInputStream fis = new FileInputStream(path))
        {
            IOUtils.copy(fis, baos);

            return baos.toString(Charset.defaultCharset());
        }
    }

    private String loadBinary(File path) throws IOException
    {
        try (final ByteArrayOutputStream baos = new ByteArrayOutputStream();
             final FileInputStream fis = new FileInputStream(path))
        {
            IOUtils.copy(fis, baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }
}
