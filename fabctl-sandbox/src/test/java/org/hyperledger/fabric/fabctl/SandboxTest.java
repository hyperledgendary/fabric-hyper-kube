package org.hyperledger.fabric.fabctl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This is just a sample collection of scratch routines, what-if, and spaghetti code.
 */
@Slf4j
public class SandboxTest
{
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TEST_NAMESPACE = "test-network";

    private static KubernetesClient client;

    @BeforeAll
    public static void beforeAll() throws Exception
    {
        final Config kubeConfig =
                new ConfigBuilder()
                        .withTrustCerts(true)
                        .withNamespace(TEST_NAMESPACE)
                        .build();

        log.info("Connecting to k8s with config:\n" +
                         objectMapper.writerWithDefaultPrettyPrinter()
                                     .writeValueAsString(kubeConfig));

        client = new DefaultKubernetesClient(kubeConfig);
        assertNotNull(client);
    }

    // Just run a find command with the volume mounts for a peer context.
    @Test
    public void testLaunchBatchJob() throws Exception
    {
        assertNotNull(client);

        final Job template = buildTestJob("find", "/var/hyperledger/fabric");

        final Job job =
                client.batch()
                      .v1()
                      .jobs()
                      .inNamespace(TEST_NAMESPACE)
                      .create(template);

        log.info("Launched job:\n{}",
                 objectMapper.writerWithDefaultPrettyPrinter()
                             .writeValueAsString(job));

        assertNotNull(job);
    }

    /**
     * Run a job and wait for completion
     */
    @Test
    public void testLaunchAndWaitForJob() throws Exception
    {
        //final String[] command = { "find", "/var/hyperledger/fabric" };
        final String[] command = { "find", "/" };
        final int deadlineSeconds = 60;

        final Job job =
                client.batch()
                      .v1()
                .jobs()
                .inNamespace(TEST_NAMESPACE)
                .create(buildTestJob(command));

        assertNotNull(job);

        waitForJob(job, 60, TimeUnit.SECONDS);

        //
        // Job has completed.  What happened?
        //
        final String jobName = job.getMetadata().getName();
        final Pod mainPod = findMainPod(jobName);
        assertNotNull(mainPod);

        final PodStatus mainPodStatus = mainPod.getStatus();
        assertNotNull(mainPodStatus);

        final int exitCode = getContainerStatusCode(mainPodStatus, "main");
        assertEquals(0, exitCode);

        // todo: stdout / stderr
    }

    @Test
    public void testLaunchAnotherJob() throws Exception
    {
        final Job job =
                JobUtil.runJob(client,
                               buildTestJob("find", "/"),
                               60,
                               TimeUnit.SECONDS);

        assertNotNull(job);

    }



    private void waitForJob(final Job job, final long timeout, final TimeUnit units)
            throws InterruptedException
    {
        //
        // Subscribe to events for the newly created job.
        //
        final String jobName = job.getMetadata().getName();
        log.info("Opening watch for job {}", jobName);

        //
        // Block the current thread until receipt of a success/failed event, or a timeout has been reached.
        //
        final CountDownLatch latch = new CountDownLatch(1);

        try (final Watch watch = client.batch()
                                       .v1()
                                       .jobs()
                                       .inNamespace(TEST_NAMESPACE)
                                       .withName(jobName)
                                       .watch(new Watcher<Job>()
                {
                   @Override
                   public void eventReceived(Action action, Job resource)
                   {
                       try
                       {
                           log.info("Received Job action " + action + ":\n" +
                                            objectMapper.writerWithDefaultPrettyPrinter()
                                                        .writeValueAsString(resource));

                           final JobStatus jobStatus = resource.getStatus();
                           if (jobStatus == null)
                           {
                               log.info("Job has no status?");
                               return;
                           }

                           //
                           // Release the latch if the job succeeded or failed.
                           //
                           if (Integer.valueOf(1).equals(jobStatus.getActive()))
                           {
                               log.info("Job started at " + jobStatus.getStartTime());
                           }
                           else if (Integer.valueOf(1).equals(jobStatus.getFailed()))
                           {
                               log.info("Job failed with conditions " +
                                                objectMapper.writeValueAsString(jobStatus.getConditions()));
                               latch.countDown();
                           }
                           else if (Integer.valueOf(1).equals(jobStatus.getSucceeded()))
                           {
                               log.info("Job succeeded at " + jobStatus.getCompletionTime());
                               latch.countDown();
                           }
                       }
                       catch (Exception ex)
                       {
                           fail("Could not process action event", ex);
                       }
                   }

                   @Override
                   public void onClose(WatcherException cause)
                   {
                       if (cause != null)
                       {
                           fail("Watch was closed with exception", cause);
                       }

                       latch.countDown();
                   }
                }))
        {
            log.info("Awaiting a maximum of {} {} for job completion.", timeout, units);

            boolean completed = latch.await(timeout, units);
            if (! completed)
            {
                fail("Job did not complete after " + timeout + " " + units);
            }
        }
    }

    /**
     * Find the Job's pod with a container named [main]
     * @param jobName
     * @return
     */
    private Pod findMainPod(String jobName)
    {
        for (Pod pod : client.pods()
                             .withLabel("job-name", jobName)
                             .list()
                             .getItems())
        {
            for (Container container : pod.getSpec().getContainers())
            {
                if ("main".equalsIgnoreCase(container.getName()))
                {
                    return pod;
                }
            }
        }

        return null;
    }

    /**
     * Dig the [main] container's exit code out of the PodStatus
     *
     * @param podStatus
     * @param containerName
     * @return
     * @throws Exception
     */
    private int getContainerStatusCode(PodStatus podStatus, String containerName)
            throws Exception
    {
        for (ContainerStatus containerStatus : podStatus.getContainerStatuses())
        {
            if (containerName.equalsIgnoreCase(containerStatus.getName()))
            {
                final ContainerState containerState = containerStatus.getState();
                log.info("Final container state: \n" +
                                 objectMapper.writerWithDefaultPrettyPrinter()
                                             .writeValueAsString(containerState));

                //
                // Carry the exit code from the [main] driver into the job runner exit.
                //
                if (containerState.getRunning() != null)
                {
                    log.error("Timeout expired: container is still running.");
                    return -1;
                }
                else if (containerState.getWaiting() != null)
                {
                    log.error("Timeout expired: container is still waiting");
                    return -1;
                }
                else if (containerState.getTerminated() != null)
                {
                    return containerState.getTerminated().getExitCode();
                }
            }
        }

        throw new IllegalStateException("No exit status found for terminal state: \n" +
                                                objectMapper.writerWithDefaultPrettyPrinter()
                                                            .writeValueAsString(podStatus));
    }


    private Job buildTestJob(final String... command)
    {
        // @formatter:off
        return new JobBuilder()
            .withApiVersion("batch/v1")
            .withNewMetadata()
                //.withName("foo-job")
                .withGenerateName("foo-job-")
            .endMetadata()
            .withNewSpec()
                .withBackoffLimit(0)
                .withCompletions(1)
                .withNewTemplate()
                    .withNewSpec()
                        .withRestartPolicy("Never")
                        .addNewContainer()
                            .withName("main")
                            .withImage("alpine")
                            .withCommand(command)
                        .endContainer()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build();
        // @formatter:on
    }
}
