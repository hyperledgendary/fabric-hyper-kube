/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v0;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Helper utilities for working with k8s deployments.
 */
@Slf4j
public class DeploymentUtil
{
    private static final YAMLMapper yamlMapper = new YAMLMapper();

    /**
     * Wait a little while for all the pods in a deployment to reach ready status.
     */
    public static void waitForDeployment(final KubernetesClient client,
                                         final Deployment deployment,
                                         final long timeout,
                                         final TimeUnit units)
        throws Exception
    {
        log.info("Waiting {} {} for deployment {} to be available.",
                 timeout,
                 units,
                 deployment.getMetadata().getName());

        final CountDownLatch latch = new CountDownLatch(1);

        try (Watch watch = client.apps()
                                 .deployments()
                                 .withName(deployment.getMetadata().getName())
                                 .watch(new Watcher<>()
                                 {
                                     @Override public void eventReceived(Action action, Deployment resource)
                                     {
                                         try
                                         {
                                             log.info("action {} {}", action, yamlMapper.writeValueAsString(resource));

                                             final DeploymentStatus status = resource.getStatus();
                                             if (status == null)
                                             {
                                                 log.warn("Deployment has no status?");
                                                 return;
                                             }

                                             //
                                             // release the latch if the deployment is up or deleted.
                                             //
                                             if (Action.DELETED.equals(action))
                                             {
                                                 log.info("deployment was deleted.  abort!");
                                                 latch.countDown();
                                             }
                                             else if (status.getUnavailableReplicas() == null)
                                             {
                                                 log.info("All replicas are ready.  Let's go!");
                                                 latch.countDown();
                                             }
                                         }
                                         catch (Exception ex)
                                         {
                                             log.error("Could not process callback event", ex);
                                             // todo: this should log the error but trap the exception below, deleting the deployment/service if it was created.
                                             // todo: this assertion failure is NOT caught by the test runner!
                                         }
                                     }

                                     @Override public void onClose(WatcherException cause)
                                     {
                                         if (cause != null)
                                         {
                                             log.error("Watch forcibly closed", cause);
                                         }

                                         latch.countDown();
                                     }
                                 }))
        {
            log.info("Awaiting a maximum of {} {} for deployment.", timeout, units);

            boolean completed = latch.await(timeout, units);
            if (! completed)
            {
                //
                // The deployment / services can be removed here, but this will scrub any debugging info from the event history.
                // TODO: we can detect that the deployment has stalled, but the root cause will be in the POD status conditions...  trap the error here and present to the user.
                //
                throw new Exception("Deployment was not ready after " +
                                            timeout + " " + units +
                                            ".  Most likely this is an error pulling the Docker image.");
            }
        }
    }
}
