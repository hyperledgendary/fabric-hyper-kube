/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v0;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.fabric.fabctl.v0.command.ConfigTXGenCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test is an experimental "what if?" sandbox for manipulating fabric networks in a cloud-native fashion.
 *
 * In the resource bundle src/test/resources/kube, a number of kube resource descriptors are available
 * which will inflate a test network from a static configuration.   This test case advances a scenario whereby
 * the network administrator maintains a "network descriptor" configuration, which is then interpreted as
 * a set of deployments, pods, services, routes, configmaps, etc... to affect a remote fabric network running
 * in Kubernetes.  This is a baby step towards a K8s "operator" CRD, whereby a custom resource specifies the
 * network configuration, and a Fabric Controller is responsible for applying the relevant updates to the kube
 * via the API control plane.
 *
 * At this point, KISS:  just read a simple topology of peers, orderers, and contexts, and use this to
 * inflate a remote network and achieve parity with the `network.sh` running in Docker Compose.
 */
@Slf4j
public class InitFabricNetworkTest extends TestBase
{
    private static final String FABRIC_VERSION = "2.3.2";

    @Data
    public static class NetworkConfig
    {
        public final Metadata meta;
        public final List<PeerConfig> peers = new ArrayList<>();
        public List<OrdererConfig> orderers = new ArrayList<>();

        // todo: This doesn't actually reflect the core config IN the cluster, which is currently set up with a config map.

        public NetworkConfig(final String name)
        {
            this.meta = new Metadata(name);
        }
    }

    @Data
    public static class Metadata
    {
        public final String name;
    }

    @Data
    public static class PeerConfig
    {
        public final String name;
        public final Context context;
        // todo: id / msp scope
    }

    @Data
    public static class OrdererConfig
    {
        public final String name;
        public final Context context;
    }

    public static class Context extends TreeMap<String,String>
    {
    }

    // this is almost too annoying.  Read these from a local resource bundle. (yaml, properties, json, etc...)
    private static final Context ORDERER1_CONTEXT = new Context(){{
        put("FABRIC_CFG_PATH", "/var/hyperledger/fabric/config");
        put("FABRIC_LOGGING_SPEC", "debug:cauthdsl,policies,msp,common.configtx,common.channelconfig=info");
        put("ORDERER_GENERAL_LISTENADDRESS", "0.0.0.0");
        put("ORDERER_GENERAL_LISTENPORT", "6050");
        put("ORDERER_GENERAL_LOCALMSPID", "OrdererMSP");
        put("ORDERER_GENERAL_LOCALMSPDIR", "/var/hyperledger/fabric/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com/msp");
        put("ORDERER_GENERAL_TLS_ENABLED", "true");
        put("ORDERER_GENERAL_TLS_PRIVATEKEY", "/var/hyperledger/fabric/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com/tls/server.key");
        put("ORDERER_GENERAL_TLS_CERTIFICATE", "/var/hyperledger/fabric/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com/tls/server.crt");
        put("ORDERER_GENERAL_TLS_ROOTCAS", "/var/hyperledger/fabric/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com/tls/ca.crt");
        put("ORDERER_GENERAL_BOOTSTRAPMETHOD", "file");
        put("ORDERER_GENERAL_BOOTSTRAPFILE", "/var/hyperledger/fabric/channel-artifacts/genesis.block");
        put("ORDERER_FILELEDGER_LOCATION", "/var/hyperledger/fabric/data/orderer");
        put("ORDERER_CONSENSUS_WALDIR", "/var/hyperledger/fabric/data/orderer/etcdraft/wal");
        put("ORDERER_CONSENSUS_SNAPDIR", "/var/hyperledger/fabric/data/orderer/etcdraft/wal");
        put("ORDERER_OPERATIONS_LISTENADDRESS", "0.0.0.0:8443");
        put("ORDERER_ADMIN_LISTENADDRESS", "0.0.0.0:9443");
    }}; 

    private static final Context ORDERER2_CONTEXT = loadContext("/config/orderer2.properties");
    private static final Context ORDERER3_CONTEXT = loadContext("/config/orderer3.properties");
    private static final Context ORG1_PEER1_CONTEXT = loadContext("/config/org1-peer1.properties");
    private static final Context ORG1_PEER2_CONTEXT = loadContext("/config/org1-peer2.properties");
    private static final Context ORG2_PEER1_CONTEXT = loadContext("/config/org2-peer1.properties");
    private static final Context ORG2_PEER2_CONTEXT = loadContext("/config/org2-peer2.properties");

    private static final Context ADMIN_CONTEXT = new Context(){{
        put("FABRIC_CFG_PATH", "/var/hyperledger/fabric");
    }};

    private NetworkConfig buildSampleNetwork()
    {
        final NetworkConfig network = new NetworkConfig("test-network");

        network.orderers.add(new OrdererConfig("orderer1", ORDERER1_CONTEXT));
        network.orderers.add(new OrdererConfig("orderer2", ORDERER2_CONTEXT));
        network.orderers.add(new OrdererConfig("orderer3", ORDERER3_CONTEXT));

        network.peers.add(new PeerConfig("org1-peer1", ORG1_PEER1_CONTEXT));
        network.peers.add(new PeerConfig("org1-peer2", ORG1_PEER2_CONTEXT));
        network.peers.add(new PeerConfig("org2-peer1", ORG2_PEER1_CONTEXT));
        network.peers.add(new PeerConfig("org2-peer2", ORG2_PEER2_CONTEXT));

        return network;
    }

    @Test
    public void testConstructNetwork() throws Exception
    {
        final NetworkConfig network = buildSampleNetwork();
        log.info("Constructing network:\n{}", yamlMapper.writeValueAsString(network));

        //
        // Create the admin genesis block
        //         kubectl create -f src/test/resources/kube/job-orderer-genesis.yaml
        //         kubectl create -f src/test/resources/kube/job-update-org1-anchor-peers.yaml
        //         kubectl create -f src/test/resources/kube/job-update-org2-anchor-peers.yaml
        //
        createGenesisBlock(network);   // only run once per cluster.


        //
        // Launch the orderers:
        //        kubectl apply -f src/test/resources/kube/orderer1.yaml
        //        kubectl apply -f src/test/resources/kube/orderer2.yaml
        //        kubectl apply -f src/test/resources/kube/orderer3.yaml
        //
        final List<Deployment> orderers = new ArrayList<>();

        for (OrdererConfig config : network.getOrderers())
        {
            orderers.add(launchOrderer(config));
        }


        //
        // Orderers all start before peers.
        //
        for (Deployment deployment : orderers)
        {
            DeploymentUtil.waitForDeployment(client, deployment, 1, TimeUnit.MINUTES);
        }


        //
        // Launch the peers:
        //        kubectl apply -f src/test/resources/kube/org1-peer1.yaml
        //        kubectl apply -f src/test/resources/kube/org1-peer2.yaml
        //        kubectl apply -f src/test/resources/kube/org2-peer1.yaml
        //        kubectl apply -f src/test/resources/kube/org2-peer2.yaml
        //
        final List<Deployment> peers = new ArrayList<>();

        for (PeerConfig config : network.getPeers())
        {
            peers.add(launchPeer(config));
        }


        //
        // Peers all start before declaring victory.
        //
        for (Deployment deployment : peers)
        {
            DeploymentUtil.waitForDeployment(client, deployment, 1, TimeUnit.MINUTES);
        }
    }

    private void createGenesisBlock(final NetworkConfig network) throws Exception
    {
        log.info("Creating genesis block");
        assertEquals(0,
                     execute(new ConfigTXGenCommand("configtxgen",
                                                    "-profile", "TwoOrgsOrdererGenesis",
                                                    "-channelID", "test-system-channel-name",
                                                    "-outputBlock", "/var/hyperledger/fabric/channel-artifacts/genesis.block"),
                             ADMIN_CONTEXT));

        // todo: is this part of the network init, or the channel construction?
        log.info("Setting anchor peers");
        assertEquals(0,
                     execute(new ConfigTXGenCommand("configtxgen",
                                                    "-profile", "TwoOrgsChannel",
                                                    "-outputAnchorPeersUpdate", "/var/hyperledger/fabric/channel-artifacts/Org1MSPanchors.tx",
                                                    "-channelID", "mychannel",
                                                    "-asOrg", "Org1MSP"),
                             ADMIN_CONTEXT));

        // todo: is this part of the network init, or the channel construction?
        assertEquals(0,
                     execute(new ConfigTXGenCommand("configtxgen",
                                                    "-profile",                 "TwoOrgsChannel",
                                                    "-outputAnchorPeersUpdate", "/var/hyperledger/fabric/channel-artifacts/Org2MSPanchors.tx",
                                                    "-channelID",               "mychannel",
                                                    "-asOrg",                   "Org2MSP"),
                             ADMIN_CONTEXT));
    }


    /**
     * This can be improved.  The point here is that it's being generated dynamically from a local fabric config.
     */
    private Deployment launchOrderer(final OrdererConfig config) throws IOException
    {
        log.info("launching orderer {}", config.getName());

        //
        // environment variables
        //
        final List<EnvVar> env = new ArrayList<>();
        for (Entry<String,String> e : config.context.entrySet())
        {
            env.add(new EnvVarBuilder()
                            .withName(e.getKey())
                            .withValue(e.getValue())
                            .build());
        }


        //
        // Volume mounts:
        // todo: set the crypto / identity context for the MSP via reference to "connection profile"
        //
        final List<VolumeMount> volumeMounts =
                Arrays.asList(new VolumeMountBuilder()
                                 .withName("fabric-volume")
                                 .withMountPath("/var/hyperledger/fabric")
                                 .build(),
                              new VolumeMountBuilder()
                                 .withName("fabric-config")
                                 .withMountPath("/var/hyperledger/fabric/config")
                                 .build());

        //
        // Ports
        //
        final List<ContainerPort> containerPorts =
                Arrays.asList(
                        new ContainerPortBuilder()
//                                .withName("gossip")
//                                .withProtocol("TCP")
                                .withContainerPort(6050)
                                .build(),
                        new ContainerPortBuilder()
//                                .withName("operations")
//                                .withProtocol("TCP")
                                .withContainerPort(8443)
                                .build(),
                        new ContainerPortBuilder()
//                                .withName("admin")
//                                .withProtocol("TCP")
                                .withContainerPort(9443)
                                .build()
                );

        // @formatter:off
        final Deployment template =
                new DeploymentBuilder()
                        .withApiVersion("apps/v1")
                        .withNewMetadata()
                            .withName(config.getName())
                        .endMetadata()
                        .withNewSpec()
                            .withReplicas(1)
                            .withNewSelector()
                                .withMatchLabels(Map.of("app", config.getName()))
                            .endSelector()
                            .withNewTemplate()
                                .withNewMetadata()
                                    .withLabels(Map.of("app", config.getName()))
                                    // todo: other labels here.
                                .endMetadata()
                                .withNewSpec()
                                    .addNewContainer()
                                        .withName("main")
                                        .withImage("hyperledger/fabric-orderer:" + FABRIC_VERSION)
                                        .withEnv(env)
                                        .withVolumeMounts(volumeMounts)
                                        .withPorts(containerPorts)
                                    .endContainer()
                                    .addNewVolume()
                                        .withName("fabric-volume")
                                        .withPersistentVolumeClaim(new PersistentVolumeClaimVolumeSourceBuilder()
                                                                           .withClaimName("fabric")
                                                                           .build())
                                    .endVolume()
                                    .addNewVolume()
                                        .withName("fabric-config")
                                        .withConfigMap(new ConfigMapVolumeSourceBuilder()
                                                               .withName("fabric-config")
                                                               .build())
                                    .endVolume()
                                .endSpec()
                            .endTemplate()
                        .endSpec()
                        .build();
        // @formatter:on

        final Deployment deployment =
                client.apps()
                      .deployments()
                      .create(template);

        log.info("Created deployment:\n{}", yamlMapper.writeValueAsString(deployment));


        //
        // Create a service for the orderer so that it may be reached by adjacent pods.
        //
        final List<ServicePort> servicePorts =
                Arrays.asList(
                        new ServicePortBuilder()
                                .withName("general")
                                .withProtocol("TCP")
                                .withPort(6050)
                                .build(),
                        new ServicePortBuilder()
                                .withName("operations")
                                .withProtocol("TCP")
                                .withPort(8443)
                                .build(),
                        new ServicePortBuilder()
                                .withName("admin")
                                .withProtocol("TCP")
                                .withPort(9443)
                                .build()
                );

        // @formatter:off
        final Service service =
            client.services()
                .create(new ServiceBuilder()
                                .withNewMetadata()
                                    .withName(config.getName())
                                .endMetadata()
                                .withNewSpec()
                                    .withSelector(Map.of("app", config.getName()))
                                    .withPorts(servicePorts)
                                .endSpec()
                                .build());
        // @formatter:on

        log.info("Created Service:\n{}", yamlMapper.writeValueAsString(service));

        return deployment;
    }

    /**
     * TODO: refactor, please
     * TODO: refactor, please - just load from a template from the resource bundle and substitute peer name and scope env.
     * TODO: refactor, per orderer deployment above.  Both are identical other than docker image, port maps, and scope env.
     */
    private Deployment launchPeer(final PeerConfig config) throws Exception
    {
        log.info("Launching peer {}", config.getName());


        //
        // environment variables
        //
        final List<EnvVar> env = new ArrayList<>();
        for (Entry<String,String> e : config.context.entrySet())
        {
            env.add(new EnvVarBuilder()
                            .withName(e.getKey())
                            .withValue(e.getValue())
                            .build());
        }


        //
        // Volume mounts:
        // todo: set the crypto / identity context for the MSP via reference to "connection profile"
        //
        final List<VolumeMount> volumeMounts =
                Arrays.asList(new VolumeMountBuilder()
                                      .withName("fabric-volume")
                                      .withMountPath("/var/hyperledger/fabric")
                                      .build(),
                              new VolumeMountBuilder()
                                      .withName("fabric-config")
                                      .withMountPath("/var/hyperledger/fabric/config")
                                      .build(),
                              new VolumeMountBuilder()
                                      .withName("ccs-builder")
                                      .withMountPath("/var/hyperledger/fabric/ccs-builder/bin")
                                      .build());

        //
        // Ports
        //
        final List<ContainerPort> containerPorts =
                Arrays.asList(
                        new ContainerPortBuilder()
                                //                                .withName("gossip")
                                //                                .withProtocol("TCP")
                                .withContainerPort(7051)
                                .build(),
                        new ContainerPortBuilder()
                                //                                .withName("chaincode")
                                //                                .withProtocol("TCP")
                                .withContainerPort(7052)
                                .build(),
                        new ContainerPortBuilder()
                                //                                .withName("operations")
                                //                                .withProtocol("TCP")
                                .withContainerPort(9443)
                                .build()
                );

        //
        // Please stop using the builder to construct these with this pattern.  It's ugly, excessive, and can be improved.
        //
        // All this needs to do is load a yaml template from the resource bundle and substitute config.getName() + env.
        //
        // @formatter:off
        final Deployment template =
                new DeploymentBuilder()
                        .withApiVersion("apps/v1")
                        .withNewMetadata()
                            .withName(config.getName())
                        .endMetadata()
                        .withNewSpec()
                            .withReplicas(1)
                            .withNewSelector()
                            .withMatchLabels(Map.of("app", config.getName()))
                            .endSelector()
                            .withNewTemplate()
                                .withNewMetadata()
                                    .withLabels(Map.of("app", config.getName()))
                                // todo: other labels here.
                                .endMetadata()
                                .withNewSpec()

                                    .addNewContainer()
                                        .withName("main")
                                        .withImage("hyperledger/fabric-peer:" + FABRIC_VERSION)
                                        .withEnv(env)
                                        .withVolumeMounts(volumeMounts)
                                        .withPorts(containerPorts)
                                    .endContainer()

                                    // this copies the ccs-builder binaries into the peer image for external chaincode.
                                    .addNewInitContainer()
                                        .withName("fabric-ccs-builder")
                                        .withImage(CCS_BUILDER_IMAGE)
                                        .withImagePullPolicy("IfNotPresent")
                                        .withCommand("sh", "-c")
                                        .withArgs("cp /go/bin/* /var/hyperledger/fabric/ccs-builder/bin/")
                                        .withVolumeMounts(Arrays.asList(new VolumeMountBuilder()
                                                                                .withName("ccs-builder")
                                                                                .withMountPath("/var/hyperledger/fabric/ccs-builder/bin")
                                                                                .build()))
                                    .endInitContainer()

                                    .addNewVolume()
                                        .withName("fabric-volume")
                                        .withPersistentVolumeClaim(new PersistentVolumeClaimVolumeSourceBuilder()
                                                                           .withClaimName("fabric")
                                                                           .build())
                                    .endVolume()
                                    .addNewVolume()
                                        .withName("fabric-config")
                                        .withConfigMap(new ConfigMapVolumeSourceBuilder()
                                                               .withName("fabric-config")
                                                               .build())
                                    .endVolume()
                                    .addNewVolume()
                                        .withName("ccs-builder")
                                        .withEmptyDir(new EmptyDirVolumeSource())
                                    .endVolume()
                                .endSpec()
                            .endTemplate()
                        .endSpec()
                        .build();
        // @formatter:on

        final Deployment deployment =
                client.apps()
                      .deployments()
                      .create(template);

        log.info("Created deployment:\n{}", yamlMapper.writeValueAsString(deployment));


        //
        // Create a service for the orderer so that it may be reached by adjacent pods.
        //
        final List<ServicePort> servicePorts =
                Arrays.asList(
                        new ServicePortBuilder()
                                .withName("gossip")
                                .withProtocol("TCP")
                                .withPort(7051)
                                .build(),
                        new ServicePortBuilder()
                                .withName("chaincode")
                                .withProtocol("TCP")
                                .withPort(7052)
                                .build(),
                        new ServicePortBuilder()
                                .withName("operations")
                                .withProtocol("TCP")
                                .withPort(9443)
                                .build()
                );

        // @formatter:off
        final Service service =
                client.services()
                      .create(new ServiceBuilder()
                                      .withNewMetadata()
                                      .withName(config.getName())
                                      .endMetadata()
                                      .withNewSpec()
                                      .withSelector(Map.of("app", config.getName()))
                                      .withPorts(servicePorts)
                                      .endSpec()
                                      .build());

        log.info("Created service\n{}", yamlMapper.writeValueAsString(service));

        return deployment;
    }

    /**
     * This isn't really a test case, but hints at how the network config can easily be manipulated
     * (read, written, remote/local/git/http/..., etc.) as a static configuration file.
     */
    // @Test
    public void testPrettyPrintNetwork() throws Exception
    {
        final NetworkConfig network = buildSampleNetwork();

        log.info("pretty printed network: \n{}", yamlMapper.writeValueAsString(network));
    }

    /**
     * This can be improved, but it's not super relevant HOW the context is initialized.  Just experimenting here...
     */
    private static Context loadContext(final String path)
    {
        final Properties props = new Properties();

        try
        {
            props.load(InitFabricNetworkTest.class.getResourceAsStream(path));
        }
        catch (IOException ex)
        {
            fail("Could not load resource bundle " + path, ex);
        }

        final Context context = new Context();
        for (Object o : props.keySet())
        {
            final String key = o.toString();
            context.put(key, props.getProperty(key));
        }

        return context;
    }
}
