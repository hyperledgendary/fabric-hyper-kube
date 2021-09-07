/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.hyperledger.fabric.fabctl.v0.DeploymentUtil;
import org.hyperledger.fabric.fabctl.v0.JobUtil;
import org.hyperledger.fabric.fabctl.v0.command.PeerCommand;
import org.hyperledger.fabric.fabctl.v1.chaincode.ChaincodeConnection;
import org.hyperledger.fabric.fabctl.v1.chaincode.ChaincodeDescriptor;
import org.hyperledger.fabric.fabctl.v1.chaincode.ChaincodeMetadata;
import org.hyperledger.fabric.fabctl.v1.msp.MSPDescriptor;
import org.hyperledger.fabric.fabctl.v1.network.Environment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Build up to a scenario of a chaincode author building, installing, approving, and executing chaincode on a
 * remote Fabric network.
 * <p>
 * In k8s, no Docker daemon is available and all chaincode routines will be deployed to the network using
 * the Chaincode as a Service (CCaaS) pattern.  With CCaaS, the smart contract runs in a standalone Kube Deployment,
 * exposing a gRPC service port at an address within the cluster.
 * <p>
 * A few things must happen before a chaincode endpoint can be linked into a Fabric Network:
 * <ul>
 * <li> The developer constructs a Docker image containing both the chaincode logic as well as the gRPC Service Router.
 * <li> The developer pushes the Docker image to a repository where it may be consumed by the k8s nodes (e.g. network route, image pull secrets, etc.)
 * <li> The developer assembles a chaincode package:
 * <pre>
 *   chaincode-descriptor.tgz
 *     |
 *     +- metadata.json:
 *     |  {
 *     |    "type": "external",
 *     |    "label": "my_chaincode_1.0"             << chaincode label
 *     |  }
 *     |
 *     +- code.tar.gz
 *       |
 *       +- connection.json:
 *          {
 *            "address": "my_chaincode:9999",      << Chaincode service URL - k8s service name OR 'host.docker.local' to connect to localhost
 *            "dial_timeout": "10s",
 *            "tls_required": false
 *          }
 * </pre>
 * <li> The chaincode package archive is transferred into the cluster.
 * <li> The chaincode package is `installed` on a peer, generating a `CHAINCODE_ID`
 * <li> The chaincode Docker container is launched as a k8s Deployment, specifying CHAINCODE_ID in the env.
 * <li> The chaincode is committed and approved, passing CHAINCODE_ID as a peer CLI argument.
 *
 *
 * This progression is onerous, but can be assisted by `fabctl` to help with the CCaaS lifecycle.
 *
 * This class pulls on the threads above, working through options to assist with the mechanics of automation.
 * Rough edges are:
 *
 *  - How will the user describe a chaincode?   (chaincode.yaml descriptor? - metadata (name, type, label, etc.) and connection?) - think k8s CRD
 *  - How will fabctl transmit the chaincode package (generated from cc.yaml) into the cluster?
 *      - configmaps need to be mounted in the peer at peer launch time.  Not OK.
 *      - cc package can be built on fabctl client, loaded as a configmap, and then a Job run to copy configmap into a shared volume.  (meh.)
 *      - cc.yaml injected as configmap, and something in cluster runs to prepare cc.tar.gz on a volume share.   (similar to above, but closer to CRD)
 *      - serice endpoint with CURDL REST / HTTP API (meh)
 *  - How will the CHAINCODE_ID be assigned / accessed?  (Currently we scrape the peer stdout - is there a better technique?)
 *
 *
 * Let's pull on a thread in this test case:
 *
 * - user writes some chaincode, builds an image (my-chaincode:1.0), and pushes to a docker repo at localhost:5000
 * - user crafts a chaincode descriptor - chaincode.yaml:
 *     ---
 *     metadata:
 *       type: external
 *       label: my_chaincode_1.0
 *       ...
 *     connection:
 *       address: my_chaincode:9999
 *       dial_timeout: 10s
 *       tls_required: false
 *     endpoint:
 *       image: localhost:5000/chaincode/my-chaincode:1.0
 *
 * - fabctl loads chaincode.yaml into k8s as a configmap       (--> eventual CRD for controller)
 * - WRONG: fabctl runs a Job in k8s to generate chaincode.tar.gz on a volume share
 * - fabctl generates chaincode.tgz from the descriptor and loads this as a configmap.
 * - fabctl runs a Job in k8s to install chaincode.tar.gz --> CHAINCODE_ID.  ${cc.id} is assigned to the configmap as a label.
 *
 *
 * How is CC_ID pushed into approve/commit/Deployment cycles?    This is not ideal but we can store the CCI_ID as a
 * parameter in the chaincode.yaml descriptor.  fabctl can scrape out the ID from the install command stdout (ouch)
 * and then append it to the descriptor.  (Or present to the user, who then edits the file.)  This approach seems
 * rough but is better aligned with a Chaincode CRD and controller.  (In the Operator case, the user installs the
 * chaincode CRD and the controller updates the CRD with a generated attribute / label.)
 *
 * % fabctl chaincode up -f chaincode.yaml          -- install, approve, commit, start
 * % fabctl chaincode install -f chaincode.yaml     -- generates ${cc.id}
 * % fabctl chaincode start -f chaincode.yaml       -- starts image as k8s deployment w/ ${cc.id}
 * % fabctl chaincode package -f chaincode.yaml     -- generate cc.tar.gz locally or in k8s
 * % ...
 *
 * ?
 *
 * This is just a sandbox to try out some of the options and ideas above.
 */
@Slf4j
public class ChaincodeSandboxTest extends TestBase
{
    private static final String CHANNEL_ID = "mychannel";

    /**
     * Imagine that the user saves a chaincode.yaml file available to fabctl (it can be stored anywhere / any way)
     */
    private ChaincodeDescriptor describeAssetTransferBasic()
    {
        final ChaincodeMetadata metadata = new ChaincodeMetadata();
        metadata.name = "asset-transfer-basic";
        metadata.label = "basic_1.0";
        metadata.image = "hyperledger/asset-transfer-basic"; // "localhost:5000/chaincode/asset-transfer-basic";  // why is this broken on KIND?
        metadata.description = "Basic Asset Transfer Example";
        metadata.author = "Allen Smithee";
        // metadata.project_url = new URL("https://github.com/hyperledger/fabric-samples/tree/main/asset-transfer-basic/chaincode-external");

        final ChaincodeConnection connection = new ChaincodeConnection();
        connection.address = metadata.name + ":9999";   // will be k8s service port address - could be host.docker.internal:9999 for local debug
        connection.dial_timeout = "10s";
        connection.tls_required = false;

        return new ChaincodeDescriptor(metadata, connection);
    }

    private static final Environment ORG1_PEER1_ENVIRONMENT = new Environment()
    {{
        put("FABRIC_LOGGING_SPEC",            "INFO");
        put("CORE_PEER_TLS_ENABLED",          "true");
        put("CORE_PEER_TLS_ROOTCERT_FILE",    "/var/hyperledger/fabric/xyzzy/org1-peer1.org1.example.com/tls/ca.crt");
        put("CORE_PEER_ADDRESS",              "org1-peer1:7051");
        put("CORE_PEER_LOCALMSPID",           "Org1MSP");
        put("CORE_PEER_MSPCONFIGPATH",        "/var/hyperledger/fabric/xyzzy/Admin@org1.example.com/msp");
    }};

    /**
     * This is a somewhat reasonable approach / stake for manipulating external chaincode :
     *
     * - create a cc descriptor document
     * - create a cc package archive and load as a k8s config map
     * - run `peer chaincode install ... chaincode.tar.gz` to generate {CHAINCODE_ID}
     * - run remote peer commands with {CHAINCODE_ID} to approve and commit the chaincode.
     */
    @Test
    public void testDeployChaincode() throws Exception
    {
        final ChaincodeDescriptor descriptor = describeAssetTransferBasic();

        ConfigMap cm = null;
        try
        {
            //
            // Create a config map with the chaincode archive.
            //
            cm = createChaincodeArchiveConfigMap(descriptor);


            //
            // When running peer admin commands we need to load the MSP context for the admin user and the peer.
            // Normally these would come from the network descriptor, but let's just read them from the conf folder
            // to keep the overall flow similar to the v0 routine.
            //
            // Assume that these msp descriptors have already been loaded into configmaps by a previous test.
            //
            final MSPDescriptor[] mspContext = new MSPDescriptor[] {
                    new MSPDescriptor("msp-com.example.org1.org1-peer1",  new File("config/crypto-config/peerOrganizations/org1.example.com/peers/org1-peer1.org1.example.com")),
                    new MSPDescriptor("msp-com.example.org1.users.admin", new File("config/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com")),
                    new MSPDescriptor("msp-com.example.orderer1",         new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com")),
            };


            //
            // peer lifecycle chaincode install ...
            //
            final Job installJob =
                    buildRemoteJob(new PeerCommand("peer",
                                                   "lifecycle",
                                                   "chaincode", "install",
                                                   "/var/hyperledger/fabric/chaincode/chaincode.tar.gz"),  // << populated by cm volume
                                   ORG1_PEER1_ENVIRONMENT,
                                   mspContext);

            //
            // + a tweak to the job descriptor to load a new volume and volume mount for the chaincode configmap.
            //
            installJob.getSpec()
                      .getTemplate()
                      .getSpec()
                      .getVolumes()
                      .add(new VolumeBuilder()
                                   .withName("chaincode-config")
                                   .withConfigMap(new ConfigMapVolumeSourceBuilder()
                                                          .withName(cm.getMetadata().getName()) // new configmap
                                                          .build())
                                   .build());


            //
            // Volume mount is in the [main] container.
            //
            final Container main =
                    installJob.getSpec()
                              .getTemplate()
                              .getSpec()
                              .getContainers()
                              .get(0);

            assertEquals("main", main.getName());

            main.getVolumeMounts()
                .add(new VolumeMountBuilder()
                             .withName("chaincode-config")
                             .withMountPath("/var/hyperledger/fabric/chaincode")
                             .build());


            //
            // Install.
            //
            final Job job = JobUtil.runJob(client, installJob, JOB_TIMEOUT, JOB_TIMEOUT_UNITS);
            final Pod mainPod = JobUtil.findMainPod(client, job.getMetadata().getName());
            final PodStatus status = mainPod.getStatus();
            final int exitCode = JobUtil.getContainerStatusCode(status, "main");

            log.info("Command output:");


            //
            // Print the [main] container / pod logs, scraping the logs to determine CHAINCODE_ID
            //
            String chaincodeID = null;
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

                    final String token = "Chaincode code package identifier: ";
                    if (line.contains(token))
                    {
                        chaincodeID = line.substring(line.indexOf(token) + token.length());
                    }
                }
            }

            log.info("Command exit: {}", exitCode);
            log.info("Chaincode ID: {}", chaincodeID);

            assertEquals(0, exitCode);
            assertNotNull(chaincodeID);
            assertTrue(chaincodeID.startsWith(descriptor.metadata.label + ":"));  // can't exact match but at least it looks about right.


            //
            // Start the chaincode deployment
            //
            deployChaincode(descriptor, chaincodeID);


            //
            // approve chaincode for org1
            //
            assertEquals(0,
                         execute(new PeerCommand("peer", "lifecycle",
                                                 "chaincode", "approveformyorg",
                                                 "-o", "orderer1:6050",
                                                 "--channelID", CHANNEL_ID,
                                                 "--name", "basic",  // todo: get the name from the cc descriptor
                                                 "--version", "1",
                                                 "--package-id", chaincodeID,
                                                 "--sequence", "1",
                                                 "--tls",
                                                 "--cafile", "/var/hyperledger/fabric/xyzzy/orderer1.example.com/msp/tlscacerts/tlsca.example.com-cert.pem"),
                                 ORG1_PEER1_ENVIRONMENT,
                                 mspContext));

            
            //
            // Commit chaincode
            //
            assertEquals(0,
                         execute(new PeerCommand("peer", "lifecycle",
                                                 "chaincode", "commit",
                                                 "-o", "orderer1:6050",
                                                 "--channelID", CHANNEL_ID,
                                                 "--name", "basic",  // todo: from descriptor
                                                 "--version", "1",
                                                 "--sequence", "1",
                                                 "--tls",
                                                 "--cafile", "/var/hyperledger/fabric/xyzzy/orderer1.example.com/msp/tlscacerts/tlsca.example.com-cert.pem"),
                                 ORG1_PEER1_ENVIRONMENT,
                                 mspContext));


            //
            // Chaincode is ready!
            //
            log.info("w00f!");
        }
        finally
        {
            if (cm != null)
            {
                client.configMaps().delete(cm);
            }
        }
    }

    /**
     * Deploy a CCaaS deployment + service for an external chaincode endpoint.
     */
    private Deployment deployChaincode(final ChaincodeDescriptor descriptor, final String chaincodeID)
            throws Exception
    {
        log.info("Deploying chaincode\n{}", yamlMapper.writeValueAsString(descriptor));

        // @formatter:off
        final Deployment deployment =
                client.apps()
                      .deployments()
                      .createOrReplace(new DeploymentBuilder()
                                      .withApiVersion("apps/v1")
                                      .withNewMetadata()
                                        .withName(descriptor.metadata.getName())
                                      .endMetadata()
                                      .withNewSpec()
                                        .withProgressDeadlineSeconds(30)
                                        .withReplicas(1)
                                        .withNewSelector()
                                            .withMatchLabels(Map.of("app", descriptor.getMetadata().getName()))
                                        .endSelector()

                                        .withNewTemplate()
                                            .withNewMetadata()
                                                .withLabels(Map.of("app", descriptor.getMetadata().getName()))
                                            .endMetadata()
                                            .withNewSpec()
                                                .addNewContainer()
                                                    .withName("main")
                                                    .withImage(descriptor.metadata.getImage())
                                                    .withImagePullPolicy("IfNotPresent")
                                                    .withEnv(Arrays.asList(new EnvVarBuilder()
                                                                                   .withName("CHAINCODE_SERVER_ADDRESS")
                                                                                   .withValue("0.0.0.0:9999")
                                                                                   .build(),
                                                                           new EnvVarBuilder()
                                                                                   .withName("CHAINCODE_ID")
                                                                                   .withValue(chaincodeID)
                                                                                   .build()))
                                                    .withPorts(Arrays.asList(new ContainerPortBuilder()
                                                                                     .withContainerPort(9999)
                                                                                     .build()))
                                                .endContainer()
                                            .endSpec()
                                        .endTemplate()
                                      .endSpec()
                                      .build());
        // @formatter:on

        log.info("Created deployment:\n{}", yamlMapper.writeValueAsString(deployment));


        //
        // Create a service for the deployment
        //
        // @formatter:on
        final Service service =
                client.services()
                      .createOrReplace(new ServiceBuilder()
                                      .withNewMetadata()
                                        .withName(descriptor.metadata.getName())
                                      .endMetadata()
                                      .withNewSpec()
                                        .withSelector(Map.of("app", descriptor.metadata.getName()))
                                        .withPorts(new ServicePortBuilder()
                                                           .withName("chaincode")
                                                           .withProtocol("TCP")
                                                           .withPort(9999)
                                                           .build())
                                      .endSpec()
                                      .build());
        // @formatter:off

        log.info("Created service:\n{}", service);

        //
        // Wait for the deployment to come up before continuing.
        //
        try
        {
            DeploymentUtil.waitForDeployment(client, deployment, 1, TimeUnit.MINUTES); 
        }
        catch (Exception ex)
        {
            log.error("An error occurred while starting the deployment", ex);

            if (deployment != null)
            {
                log.error("Deleting failed deployment {}", deployment.getMetadata().getName());
                client.apps()
                      .deployments()
                      .delete(deployment);
            }

            if (service != null)
            {
                log.error("Deleting service for failed chaincode deployment {}", service.getMetadata().getName());
                client.services().delete(service);
            }

            fail("Chaincode deployment failed", ex);
        }

        return deployment;
    }


    /**
     * Create a configmap with an embedded chaincode package archive.
     */
    private ConfigMap createChaincodeArchiveConfigMap(final ChaincodeDescriptor descriptor) throws Exception
    {
        final ConfigMap cm =
                client.configMaps()
                      .create(buildChaincodeArchiveConfigMap(descriptor));

        log.info("created config map:\n{}", yamlMapper.writeValueAsString(cm));
        return cm;
    }

    /**
     * Build a configmap with an embedded chaincode package archive.
     */
    private ConfigMap buildChaincodeArchiveConfigMap(final ChaincodeDescriptor descriptor) throws IOException
    {
        final byte[] ccPackageBytes = createChaincodeArchive(descriptor);
        final String encoded = Base64.getEncoder().encodeToString(ccPackageBytes);

        final Map<String, String> binaryData = new TreeMap<>();
        binaryData.put("chaincode.tar.gz", encoded);

        final ConfigMap configMap =
                new ConfigMapBuilder()
                        .withNewMetadata()
                        .withGenerateName(descriptor.metadata.name + "-")
                        .addToLabels("chaincode-type", descriptor.metadata.type)
                        .addToLabels("chaincode-name", descriptor.metadata.name)
                        .addToLabels("chaincode-label", descriptor.metadata.label)
                        // would really like to add chaincode-id here!
                        .endMetadata()
                        .withBinaryData(binaryData)
                        .build();

        return configMap;
    }

    /**
     * Create a byte array for a tar.gz containing metadata.json and code.tar.gz (connection.json)
     */
    private byte[] createChaincodeArchive(final ChaincodeDescriptor descriptor) throws IOException
    {
        //
        // metadata.json
        //
        final ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("type", descriptor.metadata.type);
        metadata.put("label", descriptor.metadata.label);

        final byte[] metaBytes =
                objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsBytes(metadata);

        final byte[] codeArchiveBytes = createCodeArchive(descriptor);

        //
        // cc.tgz:
        //   metadata.json
        //   code.tar.gz
        //
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GzipCompressorOutputStream gzos = new GzipCompressorOutputStream(baos);
             TarArchiveOutputStream tos = new TarArchiveOutputStream(gzos))
        {
            // metadata.json
            final TarArchiveEntry metaEntry = new TarArchiveEntry("metadata.json");
            metaEntry.setSize(metaBytes.length);
            tos.putArchiveEntry(metaEntry);
            IOUtils.copy(new ByteArrayInputStream(metaBytes), tos);
            tos.closeArchiveEntry();

            final TarArchiveEntry codeEntry = new TarArchiveEntry("code.tar.gz");
            codeEntry.setSize(codeArchiveBytes.length);
            tos.putArchiveEntry(codeEntry);
            IOUtils.copy(new ByteArrayInputStream(codeArchiveBytes), tos);
            tos.closeArchiveEntry();

            tos.finish();
            tos.close();

            return baos.toByteArray();
        }
    }

    /**
     * Create a code.tar.gz archive containing connection.json
     */
    private byte[] createCodeArchive(final ChaincodeDescriptor descriptor) throws IOException
    {
        //
        // connection.json
        // {
        //   "address": "host.docker.internal:9999",
        //   "dial_timeout": "10s",
        //   "tls_required": false
        // }
        //
        // todo: would it be better just to serialize descriptor.connection as json?
        //
        final ObjectNode connection = objectMapper.createObjectNode();
        connection.put("address", descriptor.connection.address);
        connection.put("dial_timeout", descriptor.connection.dial_timeout);
        connection.put("tls_required", descriptor.connection.tls_required);

        final byte[] connectionBytes =
                objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsBytes(connection);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GzipCompressorOutputStream gzos = new GzipCompressorOutputStream(baos);
             TarArchiveOutputStream tos = new TarArchiveOutputStream(gzos))
        {
            // metadata.json
            final TarArchiveEntry metaEntry = new TarArchiveEntry("connection.json");
            metaEntry.setSize(connectionBytes.length);
            tos.putArchiveEntry(metaEntry);
            IOUtils.copy(new ByteArrayInputStream(connectionBytes), tos);
            tos.closeArchiveEntry();

            tos.finish();
            tos.close();

            return baos.toByteArray();
        }
    }
}
