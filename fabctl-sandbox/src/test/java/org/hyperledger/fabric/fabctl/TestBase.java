package org.hyperledger.fabric.fabctl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.BufferedReader;
import java.io.Reader;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;

@Slf4j
public class TestBase
{
    protected static final ObjectMapper objectMapper = new ObjectMapper();

    protected static final YAMLMapper yamlMapper = new YAMLMapper();

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
}
