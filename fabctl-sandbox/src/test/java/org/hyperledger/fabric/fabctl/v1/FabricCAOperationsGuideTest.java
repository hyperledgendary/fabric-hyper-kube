/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import java.io.*;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.IOUtils;
import org.hyperledger.fabric.fabctl.v0.DeploymentUtil;
import org.hyperledger.fabric.fabctl.v1.network.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Let's try to mirror the approach used by the Fabric CA Operations Guide by describing a
 * three-org fabric network including CAs.
 *
 * For a nice figure outlining the components of this test network, see the figure at
 * https://hyperledger-fabric-ca.readthedocs.io/en/latest/operations_guide.html
 *
 * Looking at this it seems like fabctl should work with four types of CRDs / config stanzas:
 *
 * - networks (structure of orgs)
 * - organizations (structure of CAs, orderers, peers, and Users/Identities/Enrollments/xyzzys)
 * - channels
 * - chaincode
 *
 * The MSP context will still be a bit odd, as identities need to be bootstrapped by the CAs.  This suggests
 * that there is a different type of CRD / config :  MSP.
 *
 * Working with MSPs as CRD types could really simplify the notion of context when running admin commands,
 * bootstrapping node components, etc.  a "profile" = { env, msp-context } where msp-context is a set of MSP CRDs.
 *
 * Here's an idea:
 *
 * - CAs run in cluster, exposed as port forward or via REST / HTTP ingress.
 * - fabric-ca-client (running locally) hits the CA and generates MSP assets locally in a file tree.
 * - fabctl knows how to mangle local MSP folders, and condense an MSP into a single YAML.
 *
 * - MSP YAML are mooshed up to k8s (CRD upload or configmap or secret)
 * - MSP YAML are mounted into pods
 * - MSP YAML are unfurled by an init container, writing a file structure onto the empty/local volume.
 *
 * - deployments (CAs, peers, orderers, CLI actions, etc.) read local MSP context as if it was just "available."
 *
 * Let's roll!
 *
 *
 *
 * TODO: why is hyperledger/fabric-ca:1.5 the latest rev on docker hub?  What about 2.3.2 ?
 * TODO: set up a readiness probe on the CA port 7054 - this will block the deployment until after the CA cert has been generated.
 * TODO: track down the ACTIVE pod running a deployment.  Some can be left in TERMINATING status.
 *
 *
 * OUTCOMES FROM THIS TEST:
 *
 * - There IS a path to set up CAs (both TLS and ECert) for multi-org networks.  Using a network descriptor
 *   to describe the topology (orgs, nodes, scope, MSP context, etc.) seems like a valuable approach to carry
 *   forward.
 *
 * - Transferring the CA signing certificate out of the container is done via "kubectl cp ..." (tar -).  This
 *   approach is a good practice for moving files into/out of containers at runtime for cases when configmaps
 *   aren't suitable.
 *
 * - Setting up the CA deployments (and services) is straightforward, but there is still a big open question
 *   about how to actually reach the CA endpoints for enrollment and registration of identities.  This test
 *   suite fizzled out when requiring fabric-ca-client to run locally (e.g. in or next to fabctl), connecting
 *   to the CA REST service running somewhere in the kube.  Effectively this needs both an ingress into the
 *   cluster AND the fabric binary to run locally.   For this test case I ended up setting up a local port
 *   forward (which is possible to initialize dynamically using the Fabric8 / kube API client) and running
 *   fabric binary commands on my machine.  This path "works" but is really messy... back to the drawing
 *   board.
 *
 * - Mounting an MSP context / directory structure into pods is onerous when each of the individual files
 *   is being managed as a key in a configmap / secret.  A better approach is to craft a SINGLE descriptor
 *   for the MSP context, store it in a cm/secret, and unfurl it in the fabric nodes using an init container
 *   (or better:  we can just teach core fabric to read the MSP descriptor.)
 *
 *
 * In general, this test case was "two steps forward, one backwards..."   Let's fall back to using cryptogen
 * to inflate MSP assets on the local drive, mangle them into local MSP YAML descriptors, and moosh into k8s
 * using cm/secrets and an init container to unfurl the context at runtime.   For "v2" we will attack the
 * CA configuration and remove the dependency on cryptogen.
 */
@Slf4j
public class FabricCAOperationsGuideTest extends TestBase
{
    public static final String NETWORK_NAME = "ca-ops-guide";

    public NetworkConfig describeNetwork()
    {
        final OrganizationConfig org0 = new OrganizationConfig("Org0", "Org0MSP");
        org0.cas.add(new CAConfig("rca-org0", loadEnvironment("Org0-CA.properties")));
        org0.orderers.add(new OrdererConfig("Orderer", loadEnvironment("Org0-Orderer.properties")));

        final OrganizationConfig org1 = new OrganizationConfig("Org1", "Org1MSP");
        org1.cas.add(new CAConfig("rca-org1", loadEnvironment("Org1-CA.properties")));
        org1.peers.add(new PeerConfig("Peer1", loadEnvironment("Org1-Peer1.properties")));
        org1.peers.add(new PeerConfig("Peer2", loadEnvironment("Org1-Peer2.properties")));

        final OrganizationConfig org2 = new OrganizationConfig("Org2", "Org2MSP");
        org2.cas.add(new CAConfig("rca-org2", loadEnvironment("Org2-CA.properties")));
        org2.peers.add(new PeerConfig("Peer1", loadEnvironment("Org2-Peer1.properties")));
        org2.peers.add(new PeerConfig("Peer2", loadEnvironment("Org2-Peer2.properties")));

        final NetworkConfig network = new NetworkConfig(NETWORK_NAME);
        network.organizations.add(org0);
        network.organizations.add(org1);
        network.organizations.add(org2);

        return network;
    }

    @Test
    public void testPrettyPrintNetwork() throws Exception
    {
        final NetworkConfig network = describeNetwork();

        log.info("Test network config:\n{}", yamlMapper.writeValueAsString(network));
    }

    /**
     * The bootstrap CA environment is specified here as it's not necessarily part of the network topology.
     *
     * This CA will be started, used to generate TLS CAs, and then RESTARTED using a certificate override.
     * By default the TLS certificates will expire after 1 year, leaving a time-bomb within the cluster when
     * the root certificate expires and can not be renewed without destroying the peer network.
     */
    private static final Environment BOOTSTRAP_TLS_CA_ENV = new Environment()
    {{
        put("FABRIC_CA_SERVER_HOME",        "/var/hyperledger/fabric-ca/crypto");
        put("FABRIC_CA_SERVER_TLS_ENABLED", "true");
        put("FABRIC_CA_SERVER_CSR_CN",      "ca-tls");
        put("FABRIC_CA_SERVER_CSR_HOSTS",   "0.0.0.0");
        put("FABRIC_CA_SERVER_DEBUG",       "true");
    }};
    
    /**
     * Baby step 1: https://hyperledger-fabric-ca.readthedocs.io/en/latest/operations_guide.html#setup-tls-ca
     *
     * Set up an out-of-network TLS CA
     */
    //@Test
    public void testSetupTLSCA() throws Exception
    {
        final CAConfig tlsCA = new CAConfig("ca-tls", BOOTSTRAP_TLS_CA_ENV);
        final Deployment deployment = launchCA(tlsCA);

        // todo: port to v1
        // todo: return and inspect latest revision of Deployment
        DeploymentUtil.waitForDeployment(client, deployment, 1, TimeUnit.MINUTES);

        log.info("CA is up.");
    }

    /**
     * Baby step 2:  launch the CA deployment and transfer the /var/hyperledger/fabric-ca/crypto/tls-cert.pem
     * certificate locally.
     *
     * https://hyperledger-fabric-ca.readthedocs.io/en/latest/operations_guide.html#enroll-tls-ca-s-admin
     */
    // @Test
    public void testStartTLSCAAndTransferTLSCert() throws Exception
    {
        Deployment deployment = null;
        try
        {
            //
            // Launch the deployment.
            //
            deployment = launchCA(new CAConfig("ca-tls", BOOTSTRAP_TLS_CA_ENV));
            DeploymentUtil.waitForDeployment(client, deployment, 1, TimeUnit.MINUTES);

            //
            // TODO: register /cainfo as a readiness probe
            //

            //
            // We need to find the main pod / container for the CA entrypoint.
            //
            Pod caPod = null;
            for (Pod pod : client.pods()
                                 .withLabel("app", "ca-tls")
                                 .list()
                                 .getItems())
            {
                log.info("Found pod:\n{}", yamlMapper.writeValueAsString(pod));

                // todo: track down only the ACTIVE pod, not the Terminating ones.

                caPod = pod;
            }

            assertNotNull(caPod);

            log.info("Found CA pod: \n{}", yamlMapper.writeValueAsString(caPod));


            //
            // Copy tls-cert.pem locally.
            //
            // kubectl cp test-network/ca-tls-86d56ccb6c-kq6x8:/var/hyperledger/fabric-ca/crypto/tls-cert.pem /tmp/tls-cert.pem
            //
            final String remotePath = "/var/hyperledger/fabric-ca/crypto/tls-cert.pem";
            final File localCopy = File.createTempFile("tls-cert", ".pem");

            transferFileFromPod(caPod, remotePath, localCopy);

            assertTrue(localCopy.exists());

            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            IOUtils.copy(localCopy, baos);

            //
            // trusted root certificate for the TLS CA
            //
            final String cert = baos.toString(Charset.defaultCharset()).trim();
            log.info("cert:\n{}", cert);

            assertNotNull(cert);
            assertTrue(cert.startsWith("-----BEGIN CERTIFICATE-----"));
            assertTrue(cert.endsWith("-----END CERTIFICATE-----"));

            log.info("zzz...");
            Thread.sleep(60 * 1000);
        }
        finally
        {
            // todo: this is not deleting the pod in the foreground correctly.
            client.apps()
                  .deployments()
                  .delete(deployment);
//                  .withName(deployment.getMetadata().getName())
//                  .withPropagationPolicy(DeletionPropagation.FOREGROUND)
//                  .withGracePeriod(60)
//                  .delete();
        }
    }

    /**
     * Baby step 3: TLS CA enrollment and register identities for all network participants.
     */
    // @Test
    public void testRegisterTLSCAIdentities()
    {
        /*
        export FABRIC_CA_CLIENT_TLS_CERTFILES=/tmp/hyperledger/tls-ca/crypto/tls-ca-cert.pem
        export FABRIC_CA_CLIENT_HOME=/tmp/hyperledger/tls-ca/admin
        fabric-ca-client enroll -d -u https://admin:adminpw@0.0.0.0:7054
        fabric-ca-client register -d --id.name peer1-org1 --id.secret peer1PW --id.type peer -u https://0.0.0.0:7054
        fabric-ca-client register -d --id.name peer2-org1 --id.secret peer2PW --id.type peer -u https://0.0.0.0:7054
        fabric-ca-client register -d --id.name peer1-org2 --id.secret peer1PW --id.type peer -u https://0.0.0.0:7054
        fabric-ca-client register -d --id.name peer2-org2 --id.secret peer2PW --id.type peer -u https://0.0.0.0:7054
        fabric-ca-client register -d --id.name orderer1-org0 --id.secret ordererPW --id.type orderer -u https://0.0.0.0:7054
        */

        fail("not implemented");
    }

    /**
     * Baby step 4: Set up Orderer Org CA
     * https://hyperledger-fabric-ca.readthedocs.io/en/latest/operations_guide.html#setup-orderer-org-ca
     */
//    @Test
    public void testSetupOrdererOrgCA() throws Exception
    {
        final NetworkConfig network = describeNetwork();

        log.info("Launching org0 CA from config:\n{}", yamlMapper.writeValueAsString(network));

        //
        // Pick the org0 CA config out of the network descriptor.
        //
        // todo: maps not lists for descriptor structure?
        //
        final OrganizationConfig org0 = network.organizations.get(0);
        assertEquals("Org0", org0.name);
        assertEquals(1, org0.cas.size());

        final CAConfig ca = org0.cas.get(0);
        assertEquals("rca-org0", ca.name);

        Deployment deployment = null;
        try
        {
            deployment = launchCA(ca);
            log.info("{} deployment is up", ca.name);

            assertNotNull(deployment);


            //
            // Copy the org0 CA's TLS certificate locally.
            //
            fail("copy the ca TLS certificate locally.");


            //
            // Ingress?   port forward?  run in kube as job ???
            //
            fail("How will we issue the registration and enrollment to the CA?");

        }
        finally
        {
            // client.apps().deployments().delete(deployment);
        }
    }

    /**
     * Baby step 5: Enroll Orderer Org's CA Admin
     *
     * https://hyperledger-fabric-ca.readthedocs.io/en/latest/operations_guide.html#enroll-orderer-org-s-ca-admin
     *
     * How are we going to do this?   Enrollment needs to reach out to the CA from this process using the
     * fabric ca client.
     */
    @Test
    public void testEnrollOrdererOrgCAAdmin() throws Exception
    {

        /*
        export FABRIC_CA_CLIENT_TLS_CERTFILES=/tmp/hyperledger/org0/ca/crypto/ca-cert.pem
        export FABRIC_CA_CLIENT_HOME=/tmp/hyperledger/org0/ca/admin

        fabric-ca-client enroll -d -u https://admin:adminpw@0.0.0.0:7054
        fabric-ca-client register -d --id.name orderer1-org0 --id.secret ordererpw --id.type orderer -u https://0.0.0.0:7054
        fabric-ca-client register -d --id.name admin-org0 --id.secret org0adminpw --id.type admin --id.attrs "hf.Registrar.Roles=client,hf.Registrar.Attributes=*,hf.Revoker=true,hf.GenCRL=true,admin=true:ecert,abac.init=true:ecert" -u https://0.0.0.0:7054
        */

        fail("ingress - how?");
    }


    /**
     * Equivalent of kubectl cp namespace/pod:remotePath localFile
     */
    private void transferFileFromPod(final Pod pod, final String remotePath, final File localFile) throws IOException
    {
        log.info("kubectl cp {}/{}:{} {}",
                 pod.getMetadata().getNamespace(),
                 pod.getMetadata().getName(),
                 remotePath,
                 localFile);

        assertTrue(client.pods()
                         .withName(pod.getMetadata().getName())
                         .file(remotePath)
                         .copy(localFile.toPath()));
    }

    /**
     * Baby step 3: https://hyperledger-fabric-ca.readthedocs.io/en/latest/operations_guide.html#setup-cas
     */
    @Test
    public void testSetupCAs() throws Exception
    {
        fail("not implemented");
    }


    /**
     * todo: refactor.  Just illustrating here how the setup of a CA / k8s yaml is dynamic.
     */
    private Deployment launchCA(final CAConfig config) throws Exception
    {
        log.info("Launching CA:\n{}", yamlMapper.writeValueAsString(config));

        final Deployment template =
                new DeploymentBuilder()
                        .withApiVersion("apps/v1")
                        .withNewMetadata()
                        .withName(config.getName())
                        .endMetadata()
                        .withNewSpec()
                        .withReplicas(1)
                        .withNewSelector()
                        .withMatchLabels(Map.of("app", config.getName()))
                        .endSelector()
                        .withNewTemplate()
                        .withNewMetadata()
                        .withLabels(Map.of("app", config.getName()))
                        // todo: other labels here.
                        .endMetadata()
                        .withNewSpec()
                        .addNewContainer()
                        .withName("main")
                        .withImage("hyperledger/fabric-ca:latest")
                        .withEnv(config.environment.asEnvVarList())
                        .withPorts(new ContainerPortBuilder().withContainerPort(config.port).build())
                        .endContainer()
                        .endSpec()
                        .endTemplate()
                        .endSpec()
                        .build();

        final Deployment deployment =
                client.apps()
                      .deployments()
                      .create(template);

        log.info("Created deployment:\n{}", yamlMapper.writeValueAsString(deployment));


        final ServicePort servicePort =
                new ServicePortBuilder()
                        .withName("tls")
                        .withProtocol("TCP")
                        .withPort(config.port)
                        .build();

        final Service service =
                client.services()
                      .create(new ServiceBuilder()
                                      .withNewMetadata()
                                      .withName(config.getName())
                                      .endMetadata()
                                      .withNewSpec()
                                      .withSelector(Map.of("app", config.getName()))
                                      .withPorts(servicePort)
                                      .endSpec()
                                      .build());

        log.info("Created Service:\n{}", yamlMapper.writeValueAsString(service));

        // todo: port to v1
        // todo: return and inspect latest revision of Deployment
        DeploymentUtil.waitForDeployment(client, deployment, 1, TimeUnit.MINUTES);

        return deployment;
    }


    /**
     * After the CA comes up we can access the /cainfo endpoint to retrieve the CA certificate chain.
     *
     * https://hyperledger-fabric-ca.readthedocs.io/en/latest/operations_guide.html#enroll-tls-ca-s-admin
     *
     * Chris used `openssl` to retrieve the signing certificate for the CA :
     *
     * openssl s_client -servername ${ca_name}.${domain} -connect ${ca_name}.${domain}:443 2>/dev/null </dev/null |  sed -ne '/-BEGIN CERTIFICATE-/,/-END CERTIFICATE-/p' > "${PWD}/../enrollments/${ca_name}/tls-cert.pem"
     *
     * Can we do the same, but without using openssl?  Maybe an HTTPClient or HttpURLConnection to the CA?
     *
     * Let's pick apart the response JSON from the /cainfo.
     *
     * No, it's not in the JSON response from the CA's /cainfo.  The cert is in the SSL handshake!
     *
     */
    @Test
    public void testParseCAInfoResponse() throws Exception
    {
        final String cainfoResponse = "{\n" +
                "  \"result\": {\n" +
                "    \"CAName\": \"\",\n" +
                "    \"CAChain\": \"LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUNGRENDQWJxZ0F3SUJBZ0lVZW1kNUdCSU94RlpNVDN5NU1PNTN1eWNiZ2Q4d0NnWUlLb1pJemowRUF3SXcKWGpFTE1Ba0dBMVVFQmhNQ1ZWTXhGekFWQmdOVkJBZ1REazV2Y25Sb0lFTmhjbTlzYVc1aE1SUXdFZ1lEVlFRSwpFd3RJZVhCbGNteGxaR2RsY2pFUE1BMEdBMVVFQ3hNR1JtRmljbWxqTVE4d0RRWURWUVFERXdaallTMTBiSE13CkhoY05NakV3T1RBeU1UWTFNekF3V2hjTk16WXdPREk1TVRZMU16QXdXakJlTVFzd0NRWURWUVFHRXdKVlV6RVgKTUJVR0ExVUVDQk1PVG05eWRHZ2dRMkZ5YjJ4cGJtRXhGREFTQmdOVkJBb1RDMGg1Y0dWeWJHVmtaMlZ5TVE4dwpEUVlEVlFRTEV3WkdZV0p5YVdNeER6QU5CZ05WQkFNVEJtTmhMWFJzY3pCWk1CTUdCeXFHU000OUFnRUdDQ3FHClNNNDlBd0VIQTBJQUJCV1UyNVRGVDNDK0o1VVl6Rnl3MTlvVkl1ZHFpYjRCR0pBQVh4K0RtU09kdzh2Qy9KVlIKdTUyZHNrdkxSNjgyekJpQjBXQUs1WHBDVjBjN1d4anpJdmFqVmpCVU1BNEdBMVVkRHdFQi93UUVBd0lCQmpBUwpCZ05WSFJNQkFmOEVDREFHQVFIL0FnRUJNQjBHQTFVZERnUVdCQlJNcWwreityZjAwMldGb3l6OGhlVFJpV2hhClVqQVBCZ05WSFJFRUNEQUdod1FBQUFBQU1Bb0dDQ3FHU000OUJBTUNBMGdBTUVVQ0lRRG1lSHVEcno2eEZvOSsKT1EzV0pDTkYwcTBGUHNZYXdKU096VGJnZ1R6Q253SWdEbEZJdXdtTGFmaW56VC9JWHJJVTl6N0NNYkMrYjdsRQpZQ3RXWFBFSnBSZz0KLS0tLS1FTkQgQ0VSVElGSUNBVEUtLS0tLQo=\",\n" +
                "    \"IssuerPublicKey\": \"CgJPVQoEUm9sZQoMRW5yb2xsbWVudElEChBSZXZvY2F0aW9uSGFuZGxlEkQKIMdHlxMalJ65o89TKAu/wJAXqGQysClSxiFNOG+WLQRvEiAjBfYkEPgfD38uGvG9RWgtxoubGpgpUFYBUNXp7gy/VhpECiD8RWJnYa3LKa2z0bgaJHtMr+9OtryN/hZsPMZ4PvUXHRIg2/P97L1ZuqE3vbSd1jJKdbIyOFKzZIWsuKxFtbgwVjgiRAogvNcxOJG4VzQvs7WvGEVKxyqlqqwpZ3sUZ7iiHG73ojISIJrfxe8UX60z/WWF3lk9Xaz4STLEyYnVC9RomFo1W6BkIkQKIOArkCG/P81Sxzwo1sqZ7v9TTE/3M0s9MnEB2CA4fKmqEiBMpO67vzJ2T83XG3qHYrCpa9cfGh50RKqpT7NNBH9AJyJECiCNnw0ZJEFlkBNJEHFezbqlDp267QSDXcC7RgRrxiOpHhIgr4wTL78f5cXK1EjpqYjqvS6z9NFGKr7JOW1SOwM6YSwiRAoghQQTLctNZ2bCmC4+wFDY3dvQlGiTE3WNiRinWapEyiwSIPX9DbUPHr9wrJeezF05Elk1j/JUGTe/iCOw4vu+gcZKKogBCiCvEy0ubYVfbT3irLjaOmrrCE5tR15C/Uq5AupS7+kyuxIgrSiC9tKb4pvkFn+J8P10S87WRW+QT84va3+kvBcFg5IaIFvHvA7y9RVMyuqYTSVx446C/fg7wlplRzsRTPnIkLOoIiCV4gzl1y3K5+1jwZYRHjU30U3G50vOmjvhUmtn3u0GdjJECiArs/60YxSn1YmT2bEmjj6cjw1RU7Mgy7NyLaEvQZ7JmhIgWv9AN9TWgGWtqFiOXvKEc+M5l2C+ZAOawFyftgZJsis6RAogrs4fnXJan9OCOoeE5v5UWJjCZQ77UelnkmWJuiv3Se8SIE5vYfeZ2QCWwPu8n0JtHws5VtwdMVjjATWZyYuXUvIbQiA5ALTejSyN9svU8r+AZSxRVGQFhyP+95gcTKQ9HEPQ/0ogDmLugCeySGFnfsRKPZF3LNFpGU6tSgxOrU/GxUyE1jlSIPti+TlsCgCERl62t4uk5M6CyLJkQlIgtLpHdiix7z5q\",\n" +
                "    \"IssuerRevocationPublicKey\": \"LS0tLS1CRUdJTiBQVUJMSUMgS0VZLS0tLS0KTUhZd0VBWUhLb1pJemowQ0FRWUZLNEVFQUNJRFlnQUVIdEk2bStsZGcwQmlsUjU0YmJHdTE3RVI1VjBjOFErVAp2ais4NWsxMXhUUnJWMHVDbzI1aDhyWnJLNHBBQ0JYc1VYeU5rR3FGUHl6c2VXZXdYMVIxT2JWWDBmT3RncFNmClFPcG1DRHBFclNYZjN0M1FFRG9TdFRjcFN2WEdWc0J4Ci0tLS0tRU5EIFBVQkxJQyBLRVktLS0tLQo=\",\n" +
                "    \"Version\": \"1.4.9\"\n" +
                "  },\n" +
                "  \"errors\": [],\n" +
                "  \"messages\": [],\n" +
                "  \"success\": true\n" +
                "}";

        // output from openssl
        final String caSigningCert = "-----BEGIN CERTIFICATE-----\n" +
                "MIICYTCCAgegAwIBAgIUBrk7LQMb9x9NBOHXxzMNnv04VGgwCgYIKoZIzj0EAwIw\n" +
                "XjELMAkGA1UEBhMCVVMxFzAVBgNVBAgTDk5vcnRoIENhcm9saW5hMRQwEgYDVQQK\n" +
                "EwtIeXBlcmxlZGdlcjEPMA0GA1UECxMGRmFicmljMQ8wDQYDVQQDEwZjYS10bHMw\n" +
                "HhcNMjEwOTAyMTY1MzAwWhcNMjIwOTAyMTY1MzAwWjBvMQswCQYDVQQGEwJVUzEX\n" +
                "MBUGA1UECBMOTm9ydGggQ2Fyb2xpbmExFDASBgNVBAoTC0h5cGVybGVkZ2VyMQ8w\n" +
                "DQYDVQQLEwZGYWJyaWMxIDAeBgNVBAMTF2NhLXRscy04NmQ1NmNjYjZjLW54bmR6\n" +
                "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEKr9F1XjgsGLe9hZIwKKVlVwMItxt\n" +
                "XOz8wsKm7TIVdHPoZIn7jBIrYK4tS6lTNY8b/nlhz3asiRAW1MhBdacuYaOBkTCB\n" +
                "jjAOBgNVHQ8BAf8EBAMCA6gwHQYDVR0lBBYwFAYIKwYBBQUHAwEGCCsGAQUFBwMC\n" +
                "MAwGA1UdEwEB/wQCMAAwHQYDVR0OBBYEFOHxIvlnaJrLVO0WXM33pdP13QesMB8G\n" +
                "A1UdIwQYMBaAFEyqX7P6t/TTZYWjLPyF5NGJaFpSMA8GA1UdEQQIMAaHBAAAAAAw\n" +
                "CgYIKoZIzj0EAwIDSAAwRQIhAMO1NJroghAX4AtKhaf96Yh9YVl0PW2dxsjTQDBy\n" +
                "GnjdAiAeWd9gRUGi1fXLtb6pWtJDnrQTGEB6JjsgloOWgh1bvQ==\n" +
                "-----END CERTIFICATE-----";

        final JsonNode cainfo = objectMapper.readTree(cainfoResponse);


        log.info("example CA/cainfo response:\n{}", yamlMapper.writeValueAsString(cainfo));
        log.info("expected signing cert:\n{}", caSigningCert);

        final Base64.Decoder b64 = Base64.getDecoder();


        final String cachain =
                new String(b64.decode(cainfo.get("result")
                                            .get("CAChain")
                                            .asText()));

        final String issuerPublicKey =
                new String(b64.decode(cainfo.get("result")
                                            .get("IssuerPublicKey")
                                            .asText()));

        final String issuerRevocationPublicKey =
                new String(b64.decode(cainfo.get("result")
                                      .get("IssuerRevocationPublicKey")
                                      .asText()));

        log.info("CAChain:\n{}", cachain);
        log.info("IssuerPublicKey:\n{}", issuerPublicKey);
        log.info("IssuerRevocationPublicKey:\n{}", issuerRevocationPublicKey);
    }

    /**
     * Let's see if we can use an SSLSocket to connect to the CA and inspect the certificate.
     *
     * We certainly could use this approach.  However, it's not ideal.  With this technique
     * in order to connect to the CA endpoint, we'll need to set up both a service AND
     * an ingress controller to route traffic into the endpoint (or port-forward.)
     *
     * Setting up an ingress just to get the TLS certificate is going too far.  Let's just
     * try the equivalent of kubectl cp (internally based on kube exec 'tar -') to transfer
     * the /var/hyperledger/fabric-ca/crypto/tls-cert.pem file out of the Deployment's pod
     * and container.
     */
    @Test
    public void testReadSSLServerCertificate() throws Exception
    {
        fail("let's try kubectl cp first...");
    }













    private Environment loadEnvironment(final String resourceName)
    {
        return loadEnv(String.format("/config/v1/networks/%s/%s",
                                     NETWORK_NAME, 
                                     resourceName));
    }






}

