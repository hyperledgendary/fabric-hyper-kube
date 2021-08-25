package org.hyperledger.fabric.fabctl;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import java.io.BufferedReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Build up to a peer join channel running as a k8s Job
 */
@Slf4j
public class PeerJoinScratchTest extends TestBase
{
    // total hack - just stirring the pot here.
    @Data
    private static class PeerCommand // extends fabric command
    {
        public final String image = "hyperledger/fabric-peer";
        public final String label = "2.3.2";

        public final String[] command;
        public final Map<String,String> env = new TreeMap<>();

        public PeerCommand(String... command)
        {
            this.command = command;
        }
    }

    @Test
    public void testPeerChannelJoinCommand() throws Exception
    {
        final PeerCommand command =
                new PeerCommand("peer",
                                "channel", "join",
                                "-b", "/var/hyperledger/fabric/channel-artifacts/mychannel.block");

        //
        // This is part of the "connection profile" - a set of k/v properties to be set in the `peer` environment.
        //
        // Connection Profiles will be saved on the client and used to swap back and forth between various
        // identities when running remote admin commands.
        //
        // The peer admin command needs to be run with a connection profile specifying the current user's context.
        // For joining channels, this needs to point to the msp folder for an Admin user on the correct org.
        //

        // org1-peer1 -->
//        command.env.put("FABRIC_LOGGING_SPEC",          "INFO");
//        command.env.put("CORE_PEER_TLS_ENABLED",        "true");
//        command.env.put("CORE_PEER_TLS_ROOTCERT_FILE",  "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/peers/org1-peer1.org1.example.com/tls/ca.crt");
//        command.env.put("CORE_PEER_ADDRESS",            "org1-peer1:7051");
//        command.env.put("CORE_PEER_LOCALMSPID",         "Org1MSP");
//        command.env.put("CORE_PEER_MSPCONFIGPATH",      "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp");

        // org1-peer2
//        command.env.put("FABRIC_LOGGING_SPEC",          "INFO");
//        command.env.put("CORE_PEER_TLS_ENABLED",        "true");
//        command.env.put("CORE_PEER_TLS_ROOTCERT_FILE",  "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/peers/org1-peer2.org1.example.com/tls/ca.crt");
//        command.env.put("CORE_PEER_ADDRESS",            "org1-peer2:7051");
//        command.env.put("CORE_PEER_LOCALMSPID",         "Org1MSP");
//        command.env.put("CORE_PEER_MSPCONFIGPATH",      "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp");

        // org2-peer1
//        command.env.put("FABRIC_LOGGING_SPEC",          "INFO");
//        command.env.put("CORE_PEER_TLS_ENABLED",        "true");
//        command.env.put("CORE_PEER_TLS_ROOTCERT_FILE",  "/var/hyperledger/fabric/crypto-config/peerOrganizations/org2.example.com/peers/org2-peer1.org2.example.com/tls/ca.crt");
//        command.env.put("CORE_PEER_ADDRESS",            "org2-peer1:7051");
//        command.env.put("CORE_PEER_LOCALMSPID",         "Org2MSP");
//        command.env.put("CORE_PEER_MSPCONFIGPATH",      "/var/hyperledger/fabric/crypto-config/peerOrganizations/org2.example.com/users/Admin@org2.example.com/msp");

        // org2-peer2
        command.env.put("FABRIC_LOGGING_SPEC",          "INFO");
        command.env.put("CORE_PEER_TLS_ENABLED",        "true");
        command.env.put("CORE_PEER_TLS_ROOTCERT_FILE",  "/var/hyperledger/fabric/crypto-config/peerOrganizations/org2.example.com/peers/org2-peer2.org2.example.com/tls/ca.crt");
        command.env.put("CORE_PEER_ADDRESS",            "org2-peer2:7051");
        command.env.put("CORE_PEER_LOCALMSPID",         "Org2MSP");
        command.env.put("CORE_PEER_MSPCONFIGPATH",      "/var/hyperledger/fabric/crypto-config/peerOrganizations/org2.example.com/users/Admin@org2.example.com/msp");



        final Job template = buildRemoteJob(command);
        final Job job = JobUtil.runJob(client, template, 120, TimeUnit.SECONDS);

        final Pod mainPod = JobUtil.findMainPod(client, job.getMetadata().getName());
        assertNotNull(mainPod);

        final PodStatus status = mainPod.getStatus();
        assertNotNull(status);

        final int exitCode = JobUtil.getContainerStatusCode(status, "main");

        log.info("Executed command:\n{}", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(command));
        log.info("Command output:");

        //
        // Print the [main] container / pod logs
        //
        try (final Reader logReader =
                client.pods()
                      .withName(mainPod.getMetadata().getName())
                      .inContainer("main")
                      .getLogReader();
             final BufferedReader reader = new BufferedReader(logReader))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                log.info(line);
            }
        }

        log.info("Command exit: {}", exitCode);

        assertEquals(0, exitCode);
    }

    private Job buildRemoteJob(final PeerCommand command)
    {
        final List<EnvVar> env = new ArrayList<>();
        for (Entry<String, String> e : command.env.entrySet())
        {
            env.add(new EnvVarBuilder()
                    .withName(e.getKey())
                    .withValue(e.getValue())
                    .build());
        }

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




// This is the full set of k/v env values from launching the peer node.  Here for reference.
//        command.env.put("FABRIC_CFG_PATH",                      "/var/hyperledger/fabric/config");
//        command.env.put("FABRIC_LOGGING_SPEC",                  "debug:cauthdsl,policies,msp,grpc,peer.gossip.mcs,gossip,leveldbhelper=info");
//        command.env.put("CORE_PEER_TLS_ENABLED",                "true");
//        command.env.put("CORE_PEER_TLS_CERT_FILE",              "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/peers/org1-peer1.org1.example.com/tls/server.crt");
//        command.env.put("CORE_PEER_TLS_KEY_FILE",               "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/peers/org1-peer1.org1.example.com/tls/server.key");
//        command.env.put("CORE_PEER_TLS_ROOTCERT_FILE",          "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/peers/org1-peer1.org1.example.com/tls/ca.crt");
//        command.env.put("CORE_PEER_ID",                         "org1-peer1.org1.example.com");
//        command.env.put("CORE_PEER_ADDRESS",                    "org1-peer1:7051");
//        command.env.put("CORE_PEER_LISTENADDRESS",              "0.0.0.0:7051");
//        command.env.put("CORE_PEER_CHAINCODEADDRESS",           "org1-peer1:7052");
//        command.env.put("CORE_PEER_CHAINCODELISTENADDRESS",     "0.0.0.0:7052");
//        command.env.put("CORE_PEER_GOSSIP_BOOTSTRAP",           "org1-peer2:7051");
//        command.env.put("CORE_PEER_GOSSIP_EXTERNALENDPOINT",    "org1-peer1:7051");
//        command.env.put("CORE_PEER_LOCALMSPID",                 "Org1MSP");
//        command.env.put("CORE_OPERATIONS_LISTENADDRESS",        "0.0.0.0:9443");
//        command.env.put("CORE_PEER_FILESYSTEMPATH",             "/var/hyperledger/fabric/data/org1-peer1.org1.example.com");
//        command.env.put("CORE_LEDGER_SNAPSHOTS_ROOTDIR",        "/var/hyperledger/fabric/data/org1-peer1.org1.example.com/snapshots");
