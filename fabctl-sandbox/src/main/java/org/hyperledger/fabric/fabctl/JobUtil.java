package org.hyperledger.fabric.fabctl;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JobUtil
{
    private static final YAMLMapper yamlMapper = new YAMLMapper();

    public static Job submitJob(final KubernetesClient client, final Job template) throws IOException
    {
        final Job job =
                client.batch()
                      .v1()
                      .jobs()
                      .create(template);

        log.info("Created job {}:\n{}",
                 job.getMetadata().getName(),
                 yamlMapper.writeValueAsString(job));

        return job;
    }

    public static Job runJob(final KubernetesClient client,
                             final Job template,
                             final long timeout,
                             final TimeUnit units)
            throws InterruptedException, IOException
    {
        final Job created = submitJob(client, template);
        waitForJob(client, created, timeout, units);

        return created;  // todo: better to return the final Job status / state
    }

    public static void waitForJob(final KubernetesClient client,
                                  final Job job,
                                  final long timeout,
                                  final TimeUnit units)
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
                                       .withName(jobName)
                                       .watch(new Watcher<Job>()
                        {
                           @Override
                           public void eventReceived(Action action, Job resource)
                           {
                               try
                               {
                                   log.info("Received Job action {}\n{}",
                                            action,
                                            yamlMapper.writeValueAsString(action));

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
                                                        yamlMapper.writeValueAsString(jobStatus.getConditions()));
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
                                   log.error("Could not process action event", ex);
                               }
                           }

                           @Override
                           public void onClose(WatcherException cause)
                           {
                               if (cause != null)
                               {
                                   log.error("Watch was closed with exception", cause);
                               }

                               latch.countDown();
                           }
                        }))
        {
            log.info("Awaiting a maximum of {} {} for job completion.", timeout, units);

            boolean completed = latch.await(timeout, units);
            if (! completed)
            {
                log.error("Job is still running.  Terminate it here?");
            }
        }
    }


    /**
     * Find the Job's pod with a container named [main]
     * @param jobName
     * @return
     */
    public static Pod findMainPod(final KubernetesClient client, final String jobName)
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
    public static int getContainerStatusCode(PodStatus podStatus, String containerName)
            throws Exception
    {
        for (ContainerStatus containerStatus : podStatus.getContainerStatuses())
        {
            if (containerName.equalsIgnoreCase(containerStatus.getName()))
            {
                final ContainerState containerState = containerStatus.getState();
                log.info("Final container state:\n{}", yamlMapper.writeValueAsString(containerState));

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

        throw new IllegalStateException("No exit status found for terminal state: \n" + podStatus);
    }
}
