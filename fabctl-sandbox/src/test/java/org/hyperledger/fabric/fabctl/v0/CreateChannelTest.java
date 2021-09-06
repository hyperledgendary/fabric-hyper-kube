/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v0;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Build up to 'peer channel create':
 *
 * <pre>
 * kubectl -n test-network exec deploy/org1-peer1 -i -t -- /bin/sh
 *
 * export CORE_PEER_MSPCONFIGPATH=/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp
 *
 * peer channel \
 *   create \
 *   -c mychannel \
 *   -o orderer1:6050 \
 *   -f /var/hyperledger/fabric/channel-artifacts/mychannel.tx \
 *   --outputBlock /var/hyperledger/fabric/channel-artifacts/mychannel.block \
 *   --tls \
 *   --cafile /var/hyperledger/fabric/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com/tls/ca.crt
 *
 * peer channel \
 *   update \
 *   -o orderer1:6050 \
 *   -c mychannel \
 *   -f /var/hyperledger/fabric/channel-artifacts/Org1MSPanchors.tx \
 *   --tls \
 *   --cafile /var/hyperledger/fabric/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com/tls/ca.crt
 *
 * exit
 * </pre>
 *
 *
 * Here the "connection profile" (env) is minimal - only the MSP config path and a minimal set of env overrides are
 * necessary to run the command.  The complexity in this case stems from:
 *  - assumptions about the network structure (orderer host name) - needs a local "network config," e.g. minifab
 *  - multiple commands in sequence (Possible with k8s Batch Jobs but improved greatly by Tkn Tasks)
 *  - residue of previous commands available locally or on a PVC
 *  - command inputs funneled into command arguments
 *
 */
@Slf4j
public class CreateChannelTest extends TestBase
{
    // pass 2 - still stirring the pot.
    @Data
    private static class PeerCommand // extends fabric command
    {
        public final String image = "hyperledger/fabric-peer";
        public final String label = "2.3.2";

        public final String[] command;
        //public final Map<String,String> env = new TreeMap<>();  //  better to pass as arg to execute()

        public PeerCommand(final String... command)
        {
            this.command = command;
        }
    }

    /**
     * rough hack - this is a placeholder for "configuration files" residing on the client's local system.
     * The network configuration specifies the topology of peers, orderers, core config, etc.
     */
    @Data
    private static class NetworkConfig
    {
        public final List<String> orderers = new ArrayList<>();
        public final List<String> peers = new ArrayList();

        // Do the the orderer / peer TLS certs belong in this structure?
        // --cafile /var/hyperledger/fabric/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com/tls/ca.crt
    }

    @Test
    public void testCreateChannel() throws Exception
    {
        final String channelName = "mychannel";

        // this is not great but trying it on for size...
        final NetworkConfig network = new NetworkConfig();
        network.orderers.add("orderer1:6050");
        network.orderers.add("orderer2:6050");
        network.orderers.add("orderer3:6050");
        network.peers.add("org1-peer1");
        network.peers.add("org1-peer2");
        network.peers.add("org2-peer1");
        network.peers.add("org2-peer2");

        // This environment will come from a "connection profile"
        final Map<String,String> org1Peer1AdminScope = new TreeMap<>();
        org1Peer1AdminScope.put("FABRIC_LOGGING_SPEC",          "INFO");
        org1Peer1AdminScope.put("CORE_PEER_TLS_ENABLED",        "true");
        org1Peer1AdminScope.put("CORE_PEER_TLS_ROOTCERT_FILE",  "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/peers/org1-peer1.org1.example.com/tls/ca.crt");
        org1Peer1AdminScope.put("CORE_PEER_ADDRESS",            "org1-peer1:7051");
        org1Peer1AdminScope.put("CORE_PEER_LOCALMSPID",         "Org1MSP");
        org1Peer1AdminScope.put("CORE_PEER_MSPCONFIGPATH",      "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp");

        final PeerCommand createChannel =
                new PeerCommand("peer",
                                "channel", "create",
                                "-c", channelName,
                                "-o", network.orderers.get(0),  // meh.
                                "-f", "/var/hyperledger/fabric/channel-artifacts/mychannel.tx",     // files on PVC ?
                                "--outputBlock", "/var/hyperledger/fabric/channel-artifacts/mychannel.block",
                                "--tls",
                                "--cafile", "/var/hyperledger/fabric/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com/tls/ca.crt");

        assertEquals(0, executeCommand(createChannel, org1Peer1AdminScope));


        final PeerCommand updateChannel =
                new PeerCommand("peer",
                                "channel", "update",
                                "-c", channelName,
                                "-o", network.orderers.get(0),  // meh.
                                "-f", "/var/hyperledger/fabric/channel-artifacts/Org1MSPanchors.tx",
                                "--tls",
                                "--cafile", "/var/hyperledger/fabric/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com/tls/ca.crt");

        assertEquals(0, executeCommand(updateChannel, org1Peer1AdminScope));
    }

    private int executeCommand(final PeerCommand command, final Map<String,String> context) throws Exception
    {
        log.info("Launching command:\n{}", yamlMapper.writeValueAsString(command));
        log.info("With context:\n{}", yamlMapper.writeValueAsString(context));

        final Job template = buildRemoteJob(command, context);

        return runJob(template);
    }

    /**
     * Still not ideal but keep this in the local test...  what's the mechanism for passing in the config yaml
     * and block residue on the PVC / volume mount?
     *
     * More importantly:  how will we set the peer context via a config map to specify the proper crypto config
     * and MSP assets?
     *
     * "context" here is just a k/v env map.  But this is incorrect... the "context" for a remote peer command
     * also needs to specify the _identity_ (msp) of the activity.  This includes an env scope as well as a set
     * of crypto assets, to be mounted or referenced dynamically as a set of kube secrets and/or config maps.
     */
    private Job buildRemoteJob(final PeerCommand command, final Map<String,String> context)
    {
        final List<EnvVar> env = new ArrayList<>();
        for (Entry<String, String> e : context.entrySet())
        {
            env.add(new EnvVarBuilder()
                            .withName(e.getKey())
                            .withValue(e.getValue())
                            .build());
        }

        // todo: set the crypto / identity context for the MSP via reference to "connection profile"
        final List<VolumeMount> volumeMounts = new ArrayList<>();
        volumeMounts.add(new VolumeMountBuilder()
                                 .withName("fabric-volume")
                                 .withMountPath("/var/hyperledger/fabric")
                                 .build());
        volumeMounts.add(new VolumeMountBuilder()
                                 .withName("fabric-config")
                                 .withMountPath("/var/hyperledger/fabric/config")
                                 .build());

        // @formatter:off
        return new JobBuilder()
                .withApiVersion("batch/v1")
                .withNewMetadata()
                    .withGenerateName("peer-job-")
                .endMetadata()
                .withNewSpec()
                    .withBackoffLimit(0)
                    .withCompletions(1)
                    .withNewTemplate()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .addNewContainer()
                                .withName("main")
                                .withImage(command.getImage() + ":" + command.getLabel())
                                .withCommand(command.command)
                                .withEnv(env)
                                .withVolumeMounts(volumeMounts)
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
    }
}
