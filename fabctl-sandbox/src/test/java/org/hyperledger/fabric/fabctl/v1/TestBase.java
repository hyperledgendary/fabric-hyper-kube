/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.IOUtils;
import org.hyperledger.fabric.fabctl.v0.JobUtil;
import org.hyperledger.fabric.fabctl.v0.command.ConfigTXGenCommand;
import org.hyperledger.fabric.fabctl.v0.command.FabricCommand;
import org.hyperledger.fabric.fabctl.v0.command.PeerCommand;
import org.hyperledger.fabric.fabctl.v1.msp.MSPDescriptor;
import org.hyperledger.fabric.fabctl.v1.network.Environment;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.fail;

@Slf4j
public class TestBase
{
    protected static final ObjectMapper objectMapper = new ObjectMapper();

    protected static final YAMLMapper yamlMapper = new YAMLMapper();

    protected static final String FABRIC_VERSION = "2.3.2";

    protected static final String TEST_NAMESPACE = "test-network";

    protected static final long JOB_TIMEOUT = 120;

    protected static final TimeUnit JOB_TIMEOUT_UNITS = TimeUnit.SECONDS;

    protected static Config kubeConfig;

    protected static KubernetesClient client;

    @BeforeAll
    public static void beforeAll() throws Exception
    {
        kubeConfig = new ConfigBuilder()
                .withTrustCerts(true)
                .withNamespace(TEST_NAMESPACE)
                .build();

        log.info("Connecting to k8s with context:\n{}",
                 yamlMapper.writeValueAsString(kubeConfig.getCurrentContext()));

        client = new DefaultKubernetesClient(kubeConfig);
    }

    /**
     * This can be improved, but it's not super relevant HOW the context is initialized.  Just experimenting here...
     */
    protected static Environment loadEnv(final String resourcePath)
    {
        log.info("Loading environment from resource bundle: {}", resourcePath);

        final Properties props = new Properties();

        try
        {
            props.load(TestBase.class.getResourceAsStream(resourcePath));
        }
        catch (IOException ex)
        {
            fail("Could not load resource bundle " + resourcePath, ex);
        }

        final Environment environment = new Environment();
        for (Object o : props.keySet())
        {
            final String key = o.toString();
            environment.put(key, props.getProperty(key));
        }

        return environment;
    }

    protected String load(File path) throws IOException
    {
        try (final ByteArrayOutputStream baos = new ByteArrayOutputStream();
             final FileInputStream fis = new FileInputStream(path))
        {
            IOUtils.copy(fis, baos);

            return baos.toString(Charset.defaultCharset());
        }
    }

    protected String loadBinary(File path) throws IOException
    {
        try (final ByteArrayOutputStream baos = new ByteArrayOutputStream();
             final FileInputStream fis = new FileInputStream(path))
        {
            IOUtils.copy(fis, baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

    protected int execute(final FabricCommand command,
                          final Environment environment,
                          final MSPDescriptor[] msps)
        throws Exception
    {
        log.info("Launching command:\n{}", yamlMapper.writeValueAsString(command));
        log.info("With context:\n{}", yamlMapper.writeValueAsString(environment));
        log.info("With msp descriptors:\n{}", yamlMapper.writeValueAsString(msps));

        final Job template = buildRemoteJob(command, environment, msps);

        return runJob(template);
    }


    protected Job buildRemoteJob(final FabricCommand command,
                                 final Map<String,String> context,
                                 final MSPDescriptor[] msps)
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
        final List<VolumeMount> nodeVolumeMounts = new ArrayList<>();
        nodeVolumeMounts.add(new VolumeMountBuilder()
                                     .withName("fabric-volume")
                                     .withMountPath("/var/hyperledger/fabric")
                                     .build());

        // add a volume mount for the unfurled msp contexts
        nodeVolumeMounts.add(new VolumeMountBuilder()
                                     .withName("msp-volume")
                                     .withMountPath("/var/hyperledger/fabric/xyzzy")
                                     .build());

        // oof: this is rough.  configtxgen and peer commands need the crypto-spec and fabric config in slightly different folders.
        if (command instanceof PeerCommand)
        {
            nodeVolumeMounts.add(new VolumeMountBuilder()
                                     .withName("fabric-config")
                                     .withMountPath("/var/hyperledger/fabric/config")
                                     .build());
        }
        else if (command instanceof ConfigTXGenCommand)
        {
            nodeVolumeMounts.add(new VolumeMountBuilder()
                                     .withName("fabric-config")
                                     .withMountPath("/var/hyperledger/fabric/configtx.yaml")
                                     .withSubPath("configtx.yaml")
                                     .build());
        }
        else
        {
            fail("Unknown command type: " + command);
        }


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
        // Add a config map volume for each msp context in scope.
        //
        int i = 1;
        for (MSPDescriptor msp : msps)
        {
            log.info("Appending msp volume {}", msp.name);

            volumes.add(new VolumeBuilder()
                                // .withName(msp.name)  // volumes may not have '.'
                                .withName("msp-cm-vol-" + i++)
                                .withConfigMap(new ConfigMapVolumeSourceBuilder()
                                                       .withName(msp.name)
                                                       .build())
                                .build());
        }

        final List<VolumeMount> initContainerVolumeMounts = new ArrayList<>();

        //
        // Always mount the volume where msp descriptors will be unfurled.
        //
        initContainerVolumeMounts.add(new VolumeMountBuilder()
                                              .withName("msp-volume")
                                              .withMountPath("/var/hyperledger/fabric/xyzzy")
                                              .build());

        //
        // Each msp descriptor will be mounted into the init container as a single file.
        //
        i = 1;
        for (final MSPDescriptor msp : msps)
        {
            log.info("Appending init container volume mount {}", msp.name);

            initContainerVolumeMounts.add(new VolumeMountBuilder()
                                                  // .withName(msp.name) // volumes may not have '.' in name
                                                  .withName("msp-cm-vol-" + i++)
                                                  .withMountPath("/var/hyperledger/fabric/msp-descriptors/" + msp.name + ".yaml")
                                                  .withSubPath(msp.name + ".yaml")
                                                  .build());
        }


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
                .withVolumeMounts(nodeVolumeMounts)
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
                .withVolumeMounts(initContainerVolumeMounts)
                .endInitContainer()


                .withVolumes(volumes)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    protected int runJob(final Job template) throws Exception
    {
        final Job job = JobUtil.runJob(client, template, JOB_TIMEOUT, JOB_TIMEOUT_UNITS);
        final Pod mainPod = JobUtil.findMainPod(client, job.getMetadata().getName());
        final PodStatus status = mainPod.getStatus();
        final int exitCode = JobUtil.getContainerStatusCode(status, "main");

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

        return exitCode;
    }
}
