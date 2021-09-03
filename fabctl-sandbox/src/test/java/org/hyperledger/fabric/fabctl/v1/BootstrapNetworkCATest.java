/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;

import org.hyperledger.fabric.fabctl.v1.network.NetworkConfig;
import org.hyperledger.fabric.fabctl.v1.network.OrganizationConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Goodbye, cryptogen.  Hello, CAs.
 *
 * This imparts a little complexity as fabctl now needs to interact with the CA in order to enroll
 * the MSPs in the network.  We can certainly set up an ingress into the CAs such that the systems
 * can be reached via http / https via an external URL, but that somewhat defeats the purpose of
 * trying to interact ENTIRELY through the kube API controller.
 *
 * On the other hand, if the enrollments occur within the cluster (e.g. running as a Job), then
 * network access is easy but we won't have visibility to the MSP assets on the local host.
 *
 * Here are a few ideas to resolve the above conflict:
 *
 * - leave the MSP assets in the cluster and run enrollments as k8s Jobs
 *
 * - expose an HTTP ingress into the CAs, running the fabric-ca-client (or HTTP / REST) calls locally.
 *
 * - Allow fabctl to provision a short-lived port-forward directly to the CA services.
 *
 */
public class BootstrapNetworkCATest extends TestBase
{
    private final NetworkConfig testNetwork = new TestNetwork();

    @Test
    public void testAllOrgsHaveCA()
    {
        int count = 0;

        for (OrganizationConfig org : testNetwork.organizations)
        {
//            assertNotNull(org.ecertCA, "for org " + org.getName());
            count++;
        }

        assertEquals(3, count);
    }
}
