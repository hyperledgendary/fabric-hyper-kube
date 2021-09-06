/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.fabric.fabctl.v0.DeploymentUtil;
import org.hyperledger.fabric.fabctl.v1.network.NetworkConfig;
import org.hyperledger.fabric.fabctl.v1.network.OrganizationConfig;
import org.hyperledger.fabric.fabctl.v1.network.PeerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * With the v0 approach the crypto / MSP assets were all installed IN the cluster on a volume share using cryptogen.
 * This made it straightforward for all of the node routines to inherit a consistent MSP context, but it was nigh
 * impossible to construct the connection profile documents necessary to connect to the fabric gateway.
 *
 * With v1, all of the MSP context is local on the file system, so we can just read the required certs / keys / etc.
 * and generate the connection profile directly.  Easy-peasy-lemon-squeezy...
 *
 * To launch the rest sample we will need to:
 *
 * - gather the required certs, keys, and crypto spec from config/cyrpto-config/*
 *
 * - construct a kube Deployment descriptor with ENV settings, certs, and CONNECTION PROFILES.
 *
 * - construct a kube Service
 *
 * - construct a local port forward
 *
 * - consume the REST app.
 *
 * Let's roll!
 *
 *
 * TEST OUTCOMES:
 *
 * - The network descriptor attributes need to be in keyed maps, not array lists.  (E.g. net.orgs.get("Org1") not net.orgs.get(1))
 *
 * - The network descriptor ORG needs a list / map of CAs.  (Pull on this thread in v2)
 *
 * - TODO: replace the local port forward with a kube ingress + kustomization overlays for OCP, ICP, IKS, and KIND
 *
 */
@Slf4j
public class FabricRESTSampleTest extends TestBase
{
    /**
     * Set up the fabric-rest-sample with a dynamic gateway connection profile.
     */
    @Test
    public void testLaunchRESTSample() throws Exception
    {
        final NetworkConfig network = new TestNetwork();

        final ConfigMap cm = createServiceConfigMap("fabric-rest-sample-config", network);

        log.info("Created service config map: \n{}", yamlMapper.writeValueAsString(cm));

        final Deployment deployment = createDeployment("fabric-rest-sample");
        final Service service = createService("fabric-rest-sample");

        DeploymentUtil.waitForDeployment(client, deployment, 1, TimeUnit.MINUTES);
        log.info("Service is READY");
        
        // todo: add a port forward for the scope of the test? ?? 
        // todo: add some HTTP URL connections to the sample app? 
    }

    private Deployment createDeployment(final String deploymentName) throws IOException 
    {
        final Deployment template = 
                new DeploymentBuilder()
                        .withApiVersion("apps/v1")
                        .withNewMetadata()
                        .withName(deploymentName)
                        .endMetadata()
                        .withNewSpec()
                        .withReplicas(1)
                        .withNewSelector()
                        .withMatchLabels(Map.of("app", deploymentName))
                        .endSelector()
                        .withNewTemplate()
                        .withNewMetadata()
                        .withLabels(Map.of("app", deploymentName))
                        // todo: other labels here.
                        .endMetadata()
                        .withNewSpec()

                        // rest sample app
                        .addNewContainer()
                        .withName("main")
                        .withImage("hyperledgendary/fabric-rest-sample")
                        .withImagePullPolicy("IfNotPresent")
                        .withEnvFrom(new EnvFromSourceBuilder()
                                             .withConfigMapRef(new ConfigMapEnvSourceBuilder()
                                                                       .withName("fabric-rest-sample-config")
                                                                       .build())
                                             .build())
                        .withPorts(new ContainerPortBuilder()
                                           .withContainerPort(3000)
                                           .build())
                        .endContainer()

                        // redis
                        .addNewContainer()
                        .withName("redis")
                        .withImage("redis")
                        .withPorts(new ContainerPortBuilder()
                                           .withContainerPort(6379)
                                           .build())
                        .endContainer()

                        .endSpec()
                        .endTemplate()
                        .endSpec()
                        .build();

        final Deployment deployment = 
                client.apps()
                      .deployments()
                      .createOrReplace(template);
        
        log.info("Created deployment:\n{}", yamlMapper.writeValueAsString(deployment));

        return deployment;
    }
    
    private Service createService(final String appName) throws IOException  
    {
        final List<ServicePort> servicePorts =
                Arrays.asList(new ServicePortBuilder()
                                      .withName("http")
                                      .withProtocol("TCP")
                                      .withPort(3000)
                                      .build());

        final Service service =
                client.services()
                      .createOrReplace(new ServiceBuilder()
                                      .withNewMetadata()
                                      .withName(appName)
                                      .endMetadata()
                                      .withNewSpec()
                                      .withSelector(Map.of("app", appName))
                                      .withPorts(servicePorts)
                                      .endSpec()
                                      .build());

        log.info("Created service:\n{}", yamlMapper.writeValueAsString(service));

        return service;
    }
    
    
    private ConfigMap createServiceConfigMap(final String name, final NetworkConfig network) throws Exception
    {
        final Map<String,String> data = new TreeMap<>();

        data.put("LOG_LEVEL", "info");
        data.put("PORT", "3000");
        data.put("RETRY_DELAY", "3000");
        data.put("MAX_RETRY_COUNT", "5");

        data.put("HLF_CONNECTION_PROFILE_ORG1",
                 objectMapper.writeValueAsString(buildConnectionProfile("test-network-org1",
                                                                        network.organizations.get(1))));
        //
        // todo: read certificates from MSP not from local config file.  This is the admin cert, which is not really in the right place in the network descriptor.
        //
        data.put("HLF_CERTIFICATE_ORG1", load(new File("config/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/signcerts/Admin@org1.example.com-cert.pem")));
        data.put("HLF_PRIVATE_KEY_ORG1", load(new File("config/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/keystore/priv_sk")));

        data.put("HLF_CONNECTION_PROFILE_ORG2",
                 objectMapper.writeValueAsString(buildConnectionProfile("test-network-org2",
                                                                        network.organizations.get(2))));
        data.put("HLF_CERTIFICATE_ORG2", load(new File("config/crypto-config/peerOrganizations/org2.example.com/users/Admin@org2.example.com/msp/signcerts/Admin@org2.example.com-cert.pem")));
        data.put("HLF_PRIVATE_KEY_ORG2", load(new File("config/crypto-config/peerOrganizations/org2.example.com/users/Admin@org2.example.com/msp/keystore/priv_sk")));

        data.put("HLF_COMMIT_TIMEOUT",  "3000");
        data.put("HLF_ENDORSE_TIMEOUT",  "30");
        data.put("REDIS_HOST",  "localhost");
        data.put("REDIS_PORT",  "6379");
        data.put("ORG1_APIKEY",  "97834158-3224-4CE7-95F9-A148C886653E");
        data.put("ORG2_APIKEY",  "BC42E734-062D-4AEE-A591-5973CB763430");
        data.put("AS_LOCAL_HOST",  "false");

        // data.put("REDIS_USERNAME", "todo");
        // data.put("REDIS_PASSWORD", "todo");
        
        return client.configMaps()
                .createOrReplace(new ConfigMapBuilder()
                                         .withNewMetadata()
                                         .withName(name)
                                         .endMetadata()
                                         .withData(data)
                                         .build());
    }

    /**
     * We have a nice descripton of the test network as a structure, including MSP contexts.  Let's try to
     * use it and construct a JSON connection profile document.
     */
    // @Test
    public void testConstructConnectionProfile() throws Exception
    {
        final NetworkConfig network = new TestNetwork();
        final OrganizationConfig org1 = network.organizations.get(1);

        assertEquals("Org1", org1.getName());

        final JsonNode profile =
                buildConnectionProfile("test-network-org1",
                                       org1);

        log.info("connection descriptor:\n{}",
                 objectMapper.writerWithDefaultPrettyPrinter()
                             .writeValueAsString(profile));

        assertNotNull(profile);
        assertEquals("test-network-org1", profile.get("name").asText());
        assertEquals("1.0.0", profile.get("version").asText());

        assertTrue(profile.has("client"));
        assertEquals("Org1", profile.get("client").get("organization").asText());
        // ...


        assertTrue(profile.has("organizations"));
        assertTrue(profile.get("organizations").has("Org1"));
        assertEquals("Org1MSP", profile.get("organizations").get("Org1").get("mspid").asText());
        // ...


        assertTrue(profile.has("peers"));

        // ...



        assertTrue(profile.has("certificateAuthorities"));

        // ...
    }

    /**
     * This is a bit tedious but let's construct the JSON node by hand.
     */
    private JsonNode buildConnectionProfile(final String profileName, final OrganizationConfig org) throws Exception
    {
        // log.debug("Building connection profile from org descriptor:\n{}", yamlMapper.writeValueAsString(org));

        final PeerConfig peerConfig = org.peers.get(0); // todo index by name not ordinal

        //
        // client stanza
        //
        final ObjectNode client = objectMapper.createObjectNode();
        final ObjectNode connection = objectMapper.createObjectNode();
        final ObjectNode timeout = objectMapper.createObjectNode();
        final ObjectNode peer = objectMapper.createObjectNode();

        peer.put("endorser", "300");
        timeout.set("peer", peer);
        connection.set("timeout", timeout);
        client.set("connection", connection);
        client.put("organization", org.name);


        //
        // organizations stanza
        //
        final ObjectNode organizations = objectMapper.createObjectNode();
        final ObjectNode orgNode = objectMapper.createObjectNode();
        final ArrayNode orgPeers = objectMapper.createArrayNode();
        final ArrayNode orgCAs = objectMapper.createArrayNode();


        orgPeers.add(new TextNode(peerConfig.name));     // todo: add all peers or just the first of N?
        orgCAs.add("ca." + org.name);      // todo: this is not correct - pass over when we add CAs

        orgNode.put("mspid", org.mspID);
        orgNode.set("peers", orgPeers);
        orgNode.set("certificateAuthorities", orgCAs);
        organizations.set(org.name, orgNode);


        //
        // Peers stanza
        //
        final ObjectNode peers = objectMapper.createObjectNode();
        final ObjectNode peerNode = objectMapper.createObjectNode();
        final ObjectNode peerTLSCACerts = objectMapper.createObjectNode();
        final ObjectNode grpcOptions = objectMapper.createObjectNode();

        peerTLSCACerts.put("pem",
                           peerConfig.getMsps()
                                     .get(0)
                                     .getTls()
                                     .get("ca.crt")
                                     .textValue());   // todo: oof!  Is this the correct cert???

        grpcOptions.put("ssl-target-name-override", peerConfig.name);
        grpcOptions.put("hostnameOverride", peerConfig.name);

        peerNode.put("url", "grpcs://" + peerConfig.name + ":7051");
        peerNode.set("tlsCACerts", peerTLSCACerts);
        peerNode.set("grpcOptions", grpcOptions);
        peers.set(peerConfig.name, peerNode);


        //
        // certificateAuthorities stanza
        //
        // TODO: set up CAs in fabric org descriptor.  I think this is not read by the fabric-rest-client.
        //
        final ObjectNode certificateAuthorities = objectMapper.createObjectNode();
        final ObjectNode ca = objectMapper.createObjectNode();
        final ObjectNode caTLSCaCerts = objectMapper.createObjectNode();
        final ObjectNode httpOptions = objectMapper.createObjectNode();

        caTLSCaCerts.put("pem", "TODO");

        httpOptions.put("verify", "false");

        ca.put("url", "https://localhost:1234");
        ca.put("caName", "ca-foo-org");

        ca.set("tlsCACerts", caTLSCaCerts);
        ca.set("httpOptions", httpOptions);

        certificateAuthorities.set("ca." + org.name, ca);


        //
        // Profile document
        //
        final ObjectNode profile = objectMapper.createObjectNode();

        profile.put("name", profileName);
        profile.put("version", "1.0.0");
        profile.set("client", client);
        profile.set("organizations", organizations);
        profile.set("peers", peers);
        profile.set("certificateAuthorities", certificateAuthorities);

        return profile;
    }
}
