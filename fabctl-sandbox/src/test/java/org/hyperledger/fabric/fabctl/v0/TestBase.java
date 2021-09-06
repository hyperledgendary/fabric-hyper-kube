/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v0;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.BufferedReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.fabric.fabctl.v0.command.ConfigTXGenCommand;
import org.hyperledger.fabric.fabctl.v0.command.FabricCommand;
import org.hyperledger.fabric.fabctl.v0.command.PeerCommand;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.fail;

@Slf4j
public class TestBase
{
    protected static final ObjectMapper objectMapper = new ObjectMapper();

    protected static final YAMLMapper yamlMapper = new YAMLMapper();

    protected static final String TEST_NAMESPACE = "test-network";

    protected static final String CCS_BUILDER_IMAGE = "hyperledgendary/fabric-ccs-builder";

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

        log.info("Connecting to k8s with config:\n{}", yamlMapper.writeValueAsString(kubeConfig));

        client = new DefaultKubernetesClient(kubeConfig);
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

    protected int execute(final FabricCommand command, final Map<String,String> context) throws Exception
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
    protected Job buildRemoteJob(final FabricCommand command, final Map<String,String> context)
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
