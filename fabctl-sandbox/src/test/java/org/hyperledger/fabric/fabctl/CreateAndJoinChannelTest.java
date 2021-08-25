package org.hyperledger.fabric.fabctl;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import java.util.*;
import java.util.Map.Entry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Build up a channel and join peers in a single flow.
 */
@Slf4j
public class CreateAndJoinChannelTest extends TestBase
{
    private static final String CHANNEL = "mychannel";

    // still stirring the pot. .. don't refactor this yet.
    @Data
    private static class FabricCommand
    {
        public final String image;
        public final String label = "2.3.2";
        public final String[] command;

        public FabricCommand(final String image, final String[] command)
        {
            this.image = image;
            this.command = command;
        }

        public FabricCommand()
        {
            this.image = null;
            this.command = null;
        }
    }

    private static class PeerCommand extends FabricCommand
    {
        public PeerCommand(final String... command)
        {
            super("hyperledger/fabric-peer", command);
        }
    }

    private static class ConfigTXGenCommand extends FabricCommand
    {
        public ConfigTXGenCommand(final String... command)
        {
            super("hyperledger/fabric-tools", command);
        }
    }

    /**
     * In a future pass, pull on a thread to load the peer env context from a properties file / yaml / json / resource bundle / etc. into a real struct, not a simple map.
     */
    private static final Map<String,String> ORG1_PEER1_CONTEXT =
            Map.ofEntries(
                    entry("FABRIC_LOGGING_SPEC",            "INFO"),
                    entry("CORE_PEER_TLS_ENABLED",          "true"),
                    entry("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/peers/org1-peer1.org1.example.com/tls/ca.crt"),
                    entry("CORE_PEER_ADDRESS",              "org1-peer1:7051"),
                    entry("CORE_PEER_LOCALMSPID",           "Org1MSP"),
                    entry("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp")
            );

    private static final Map<String,String> ORG1_PEER2_CONTEXT =
            Map.ofEntries(
                    entry("FABRIC_LOGGING_SPEC",            "INFO"),
                    entry("CORE_PEER_TLS_ENABLED",          "true"),
                    entry("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/peers/org1-peer2.org1.example.com/tls/ca.crt"),
                    entry("CORE_PEER_ADDRESS",              "org1-peer2:7051"),
                    entry("CORE_PEER_LOCALMSPID",           "Org1MSP"),
                    entry("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp")
            );

    private static final Map<String,String> ORG2_PEER1_CONTEXT =
            Map.ofEntries(
                    entry("FABRIC_LOGGING_SPEC",            "INFO"),
                    entry("CORE_PEER_TLS_ENABLED",          "true"),
                    entry("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/crypto-config/peerOrganizations/org2.example.com/peers/org2-peer1.org2.example.com/tls/ca.crt"),
                    entry("CORE_PEER_ADDRESS",              "org2-peer1:7051"),
                    entry("CORE_PEER_LOCALMSPID",           "Org2MSP"),
                    entry("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/crypto-config/peerOrganizations/org2.example.com/users/Admin@org2.example.com/msp")
            );

    private static final Map<String,String> ORG2_PEER2_CONTEXT =
            Map.ofEntries(
                    entry("FABRIC_LOGGING_SPEC",            "INFO"),
                    entry("CORE_PEER_TLS_ENABLED",          "true"),
                    entry("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/crypto-config/peerOrganizations/org2.example.com/peers/org2-peer2.org2.example.com/tls/ca.crt"),
                    entry("CORE_PEER_ADDRESS",              "org2-peer2:7051"),
                    entry("CORE_PEER_LOCALMSPID",           "Org2MSP"),
                    entry("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/crypto-config/peerOrganizations/org2.example.com/users/Admin@org2.example.com/msp")
            );

    private static final List<Map<String,String>> PEER_CONTEXTS =
            Arrays.asList(
                    ORG1_PEER1_CONTEXT,
                    ORG1_PEER2_CONTEXT,
                    ORG2_PEER1_CONTEXT,
                    ORG2_PEER2_CONTEXT
            );

    @Test
    public void testCreateAndJoinChannel() throws Exception
    {
        //
        // channel config
        //
        final ConfigTXGenCommand configChannel =
            new ConfigTXGenCommand(
                    "configtxgen",
                    "-channelID",             "mychannel",
                    "-profile",               "TwoOrgsChannel",
                    "-outputCreateChannelTx", "/var/hyperledger/fabric/channel-artifacts/mychannel.tx");

        // todo: crypto-config is not aligned with fabric/config paths between peer and configtxgen commands.
        // Fix this by generating the crypto spec locally, mounting an msp / identity into the context.
        final Map<String,String> txgenContext = Map.of("FABRIC_CFG_PATH", "/var/hyperledger/fabric");

        assertEquals(0, executeCommand(configChannel, txgenContext));

        //
        // create the channel
        //
        final PeerCommand createChannel =
                new PeerCommand("peer",
                                "channel", "create",
                                "-c", CHANNEL,
                                "-o", "orderer1:6050",  // net config?
                                "-f", "/var/hyperledger/fabric/channel-artifacts/mychannel.tx",     // files on PVC ?
                                "--outputBlock", "/var/hyperledger/fabric/channel-artifacts/mychannel.block",
                                "--tls",
                                "--cafile", "/var/hyperledger/fabric/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com/tls/ca.crt");

        assertEquals(0, executeCommand(createChannel, ORG1_PEER1_CONTEXT));


        //
        // Update the channel to anchors
        //
        final PeerCommand updateChannel =
                new PeerCommand("peer",
                                "channel", "update",
                                "-c", CHANNEL,
                                "-o", "orderer1:6050",  // net config?
                                "-f", "/var/hyperledger/fabric/channel-artifacts/Org1MSPanchors.tx",
                                "--tls",
                                "--cafile", "/var/hyperledger/fabric/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com/tls/ca.crt");

        assertEquals(0, executeCommand(updateChannel, ORG1_PEER1_CONTEXT));


        //
        // Joins peers to channel
        //
        final PeerCommand joinChannel =
                new PeerCommand("peer",
                                "channel", "join",
                                "-b", "/var/hyperledger/fabric/channel-artifacts/mychannel.block");

        for (Map<String, String> context : PEER_CONTEXTS)
        {
            assertEquals(0, executeCommand(joinChannel, context));
        }
    }

    private int executeCommand(final FabricCommand command, final Map<String,String> context) throws Exception
    {
        log.info("Launching command:\n{}", yamlMapper.writeValueAsString(command));
        log.info("With context:\n{}", yamlMapper.writeValueAsString(context));

        final Job template = buildRemoteJob(command, context);

        return runJob(template);
    }

    /**
     * Still not ideal but keep this in the local test...  what's the mechanism for passing in the config yaml
     * and block residue on the PVC / volume mount?
     * <p>
     * More importantly:  how will we set the peer context via a config map to specify the proper crypto config
     * and MSP assets?
     * <p>
     * "context" here is just a k/v env map.  But this is incorrect... the "context" for a remote peer command
     * also needs to specify the _identity_ (msp) of the activity.  This includes an env scope as well as a set
     * of crypto assets, to be mounted or referenced dynamically as a set of kube secrets and/or config maps.
     */
    private Job buildRemoteJob(final FabricCommand command, final Map<String,String> context)
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

        // oof: this is rough.  configtxgen and peer commands need the crypto-spec and fabric config in slightly different folders.
        if (command instanceof PeerCommand)
        {
            volumeMounts.add(new VolumeMountBuilder()
                                     .withName("fabric-config")
                                     .withMountPath("/var/hyperledger/fabric/config")
                                     .build());
        }
        else if (command instanceof ConfigTXGenCommand)
        {
            volumeMounts.add(new VolumeMountBuilder()
                                     .withName("fabric-config")
                                     .withMountPath("/var/hyperledger/fabric/configtx.yaml")
                                     .withSubPath("configtx.yaml")
                                     .build());
        }
        else
        {
            fail("Unknown command type: " + command);
        }


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
