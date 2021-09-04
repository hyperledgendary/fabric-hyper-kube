/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.fabric.fabctl.v0.DeploymentUtil;
import org.hyperledger.fabric.fabctl.v1.msp.MSPDescriptor;
import org.hyperledger.fabric.fabctl.v1.network.NetworkConfig;
import org.hyperledger.fabric.fabctl.v1.network.OrdererConfig;
import org.hyperledger.fabric.fabctl.v1.network.OrganizationConfig;
import org.hyperledger.fabric.fabctl.v1.network.PeerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test extends the v0 approach by maintaining a set of MSP structures on the
 * local system, using an "MSP Descriptor" document to specify the identity context
 * when running network nodes in the cluster.
 *
 * The MSP Descriptor is a YAML rendering of the crypto-spec for an MSP folder
 * structure.  MSP descriptors are loaded into kubernetes as configmaps / secrets,
 * and an init container is used to unfurl the YAML file into the original file
 * structure prior to launching the fabric containers.   Effectively this allows
 * fabctl to carefully shape the runtime context of peers, orderers, and CAs to
 * include both an environment scope frame and a set of MSP identities at runtime.
 *
 * todo: convert the README kubectl command to a dynamic cm constructor (fabric-config)
 * todo: create fabric-config CM from /config/* (move to test config resource bundle)
 * todo: add some metadata labels to the configmap (e.g. id, org, name, type, etc .etc. )
 * todo: organize the configmaps by "msp-<reverse-dns-name(msp.name)>"
 * todo: no xyzzy in the volume mount path
 * todo: support multiple MSP config mounts in a node volume mount
 *
 * TEST OUTCOMES:
 *
 * - ???
 *
 */
@Slf4j
public class InitFabricNetworkTest extends TestBase
{
    private static final String FABRIC_VERSION = "2.3.2";

    @Test
    public void testInitFabricNetwork() throws Exception
    {
        final NetworkConfig network = new TestNetwork();
        log.info("Launching network\n{}", yamlMapper.writeValueAsString(network));


        //
        // Create the fabric-config ConfigMap from local /conf/* resources.
        //
        // TODO: convert the README kubectl command to a dynamic configmap constructor.
        //
//        createFabricConfigConfigMap();


        //
        // Create the genesis block for the test network.
        //
//        createGenesisBlock(network);


        //
        // Create an MSP configuration map for each node in the network.
        //
        createMSPDescriptorConfigMaps(network);


        //
        // Launch the orderers in the correct context (env + MSP), blocking until all deployments are ready.
        //
        launchOrderers(network);


        //
        // Launch the peers in the correct context (env + MSP), blocking until all deployments are ready.
        //
//        launchPeers(network);
    }

    private void createGenesisBlock(final NetworkConfig network) throws Exception
    {
        log.info("Creating genesis block");

        fail("not implemented");
    }

    /**
     * Create a config map for each MSP context in the network.
     */
    private List<ConfigMap> createMSPDescriptorConfigMaps(final NetworkConfig network) throws Exception
    {
        log.info("Creating MSP config maps");

        final List<ConfigMap> configMaps = new ArrayList<>();

        for (OrganizationConfig org : network.organizations)
        {
            for (PeerConfig peer : org.peers)
            {
                for (MSPDescriptor msp : peer.msps)
                {
                    configMaps.add(createMSPConfigMap(msp));
                }
            }

            for (OrdererConfig orderer : org.orderers)
            {
                for (MSPDescriptor msp : orderer.msps)
                {
                    configMaps.add(createMSPConfigMap(msp));
                }
            }
        }

        for (ConfigMap cm : configMaps)
        {
            log.info("Created MSP config map: {}", cm.getMetadata().getName());
        }

        return configMaps;
    }

    private void launchOrderers(final NetworkConfig network) throws Exception
    {
        log.info("Launching orderers");

        final List<Deployment> ordererDeployments = new ArrayList<>();

        //
        // Launch all of the network orderers
        //
        for (OrganizationConfig org : network.organizations)
        {
            log.info("Launching org {} orderers:", org.name);

            for (OrdererConfig orderer : org.orderers)
            {
                ordererDeployments.add(launchOrderer(orderer));
            }
        }

        //
        // Wait for all deployments to reach a ready status.
        //
        for (Deployment deployment : ordererDeployments)
        {
            DeploymentUtil.waitForDeployment(client, deployment, 1, TimeUnit.MINUTES);
        }

        log.info("Orderers are up.");
    }

    private void launchPeers(final NetworkConfig network) throws Exception
    {
        log.info("Launching peers");

        final List<Deployment> peerDeployments = new ArrayList<>();

        for (final OrganizationConfig org : network.organizations)
        {
            log.info("Launching org {} peers", org.name);

            for (final PeerConfig peer : org.peers)
            {
                peerDeployments.add(launchPeer(peer));
            }
        }

        //
        // Wait for all of the deployments to reach a ready state.
        //
        for (final Deployment deployment : peerDeployments)
        {
            DeploymentUtil.waitForDeployment(client, deployment, 1, TimeUnit.MINUTES);
        }

        log.info("All peers are up.");
    }

    private Deployment launchPeer(final PeerConfig peerConfig) throws Exception
    {
        log.info("Launching peer {}", peerConfig.name);

        fail("not implemented");

        return null;
    }

    // @Test
    public void testConstructMSPConfigMap() throws Exception
    {
        final MSPDescriptor msp =
                new MSPDescriptor("msp-com.example.orderer1",
                                  new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com"));

        ConfigMap cm = null;
        try
        {
            cm = createMSPConfigMap(msp);

            log.info("created MSP configmap \n{}", yamlMapper.writeValueAsString(msp));

            assertNotNull(cm);
            assertEquals("msp-com.example.orderer1", cm.getMetadata().getName());
        }
        finally
        {
            client.configMaps().delete(cm);
        }
    }

    /**
     * Create an MSP config map from an MSP Descriptor
     *
     * todo: add some metadata labels to the configmap (e.g. id, org, name, type, etc .etc. )
     */
    private ConfigMap createMSPConfigMap(final MSPDescriptor msp) throws Exception
    {
        final String cmName = "msp-" + msp.name;
        final String mspKey = "msp-" + msp.name + ".yaml";
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

    /**
     * This is still a rough cut and will benefit from refactoring.  Both peers and orderers share 99% of an
     * identical deployment template.  Just get the functionality correct in this first pass.
     *
     * This can be improved.  The only point in this example is that the deployment is dynamically generated
     * from code and includes the variable context MSP descriptors and "unfurl" init container.
     */
    private Deployment launchOrderer(final OrdererConfig config) throws IOException
    {
        log.info("launching orderer {}", config.getName());

        //
        // environment variables
        //
        final List<EnvVar> env = new ArrayList<>();
        for (Entry<String,String> e : config.environment.entrySet())
        {
            env.add(new EnvVarBuilder()
                            .withName(e.getKey())
                            .withValue(e.getValue())
                            .build());
        }


        //
        // Volume mounts:
        //
        final List<VolumeMount> nodeVolumeMounts =
                Arrays.asList(new VolumeMountBuilder()
                                      .withName("fabric-volume")
                                      .withMountPath("/var/hyperledger/fabric")
                                      .build(),
                              new VolumeMountBuilder()
                                      .withName("fabric-config")
                                      .withMountPath("/var/hyperledger/fabric/config")
                                      .build(),
                              new VolumeMountBuilder()
                                      .withName("msp-volume")
                                      .withMountPath("/var/hyperledger/fabric/xyzzy")
                                      .build());


        //
        // Pod volumes.  Note that this mounts the empty volume and a config map for each MSP context in scope.
        //
        final List<Volume> volumes = new ArrayList<>();

        volumes.add(new VolumeBuilder()
                                .withName("fabric-volume")
                                .withPersistentVolumeClaim(new PersistentVolumeClaimVolumeSourceBuilder()
                                                                   .withClaimName("fabric")
                                                                   .build())
                                .build());
        volumes.add(new VolumeBuilder()
                            .withName("fabric-config")
                            .withConfigMap(new ConfigMapVolumeSourceBuilder()
                                                   .withName("fabric-config")
                                                   .build())
                            .build());

        volumes.add(new VolumeBuilder()
                            .withName("msp-volume")
                            .withNewEmptyDir()
                            .endEmptyDir()
                            .build());


        //
        // Add a config map for the MSP context.
        //
        // TODO: we will have multiple MSP contexts in a node runtime.  Currently this collides on name.
        //
        for (MSPDescriptor msp : config.msps)
        {
            log.info("Appending msp context {}", msp.id);

            volumes.add(new VolumeBuilder()
                                .withName("msp-config")   // todo : collides on name
                                .withConfigMap(new ConfigMapVolumeSourceBuilder()
                                                       .withName("msp-" + msp.id)
                                                       .build())
                                .build());
        }

        //
        // Ports
        //
        final List<ContainerPort> containerPorts =
                Arrays.asList(
                        new ContainerPortBuilder()
                                .withContainerPort(6050)
                                .build(),
                        new ContainerPortBuilder()
                                .withContainerPort(8443)
                                .build(),
                        new ContainerPortBuilder()
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
                        .withVolumeMounts(nodeVolumeMounts)
                        .withPorts(containerPorts)
                        .endContainer()


                        //
                        // Set up an init container to unfurl the MSP context into a directory on the node.
                        //
                        .addNewInitContainer()
                        .withName("msp-unfurl")
                        .withImage("hyperledgendary/fabric-hyper-kube/fabctl-msp-unfurler")
                        .withImagePullPolicy("IfNotPresent")
                        .addToEnv(new EnvVarBuilder()
                                          .withName("INPUT_FOLDER")
                                          .withValue("/var/hyperledger/fabric/msp-descriptors")
                                          .build())
                        .addToEnv(new EnvVarBuilder()
                                          .withName("OUTPUT_FOLDER")
                                          .withValue("/var/hyperledger/fabric/xyzzy")
                                          .build())
                        .withVolumeMounts(Arrays.asList(new VolumeMountBuilder()
                                                                .withName("msp-config")
                                                                .withMountPath("/var/hyperledger/fabric/msp-descriptors")
                                                                .build(),
                                                        new VolumeMountBuilder()
                                                                .withName("msp-volume")
                                                                .withMountPath("/var/hyperledger/fabric/xyzzy")
                                                                .build()))
                        .endInitContainer()


                        .withVolumes(volumes)
                        .endSpec()
                        .endTemplate()
                        .endSpec()
                        .build();
        // @formatter:on

        final Deployment deployment =
                client.apps()
                      .deployments()
                      .createOrReplace(template);

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
                      .createOrReplace(new ServiceBuilder()
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


}
