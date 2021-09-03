/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.IOUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * With the CryptoXYZZYConfigMapTest, we tried an approach whereby the MSP assets from an enrollment
 * were stored as a k8s configmap, with each cert in the MSP mapped to an individual key within the CM.
 *
 * When mounting a volume within a pod with the XYZZY approach, each of the cm keys needs to be mapped
 * back out into the correct location in the original MSP folder structure.  This is possible, but it's
 * a real nightmare.   Let's do something different, and ...
 *
 * - use cryptogen to pre-construct a folder hierarchy of MSP assets (note: this approach also works with CA)
 *
 * - Write some code to mangle an MSP asset structure into a SINGLE YAML file.  This is the "msp descriptor"
 *   or MSP context.
 *
 * - When running fabric things, fabctl will specify the MSP context by mounting the msp descriptors into
 *   the pods running fabric binaries.  When the binary starts, each of the relevant MSP descriptors will
 *   be mounted as a configmap / secret into the pods.
 *
 * - Each fabric pod running in k8s will include an init container, with instructions to unfurl the MSP
 *   descriptor into the appropriate place on the local file system.   Despite some fiddling at the CLI
 *   env and args, this will allow us to carefully constrain the scope in which the fabric binaries run.
 *
 *
 * In this pass we'll write a companion routine (docker image) that knows how to unfurl the MSP descriptors
 * into the pod at launch time.   A more robust solution is to simply teach core fabric how to read an
 * MSP from an input YAML file, rather than relying on disk-based structures referenced by the single
 * "MSPDir" attribute currently in fabric config.
 *
 * Let's roll up some MSP contexts, and see how this goes!
 *
 *
 *
 *
 * TEST OUTCOMES:
 *
 * - hyperledgendary/fabric-hyper-kube/fabctl-msp-unfurler is a VERY rough cut at a program which will
 *   read msp descriptors mounted in CM/secrets, unfurling into an MSP folder structure in the scope
 *   of the network nodes.  This is suitable for specifying the MSPDir attribute in the fabric config.
 *
 * - While this test is incomplete, the overall approach feels right.  At a minimum it is a HUGE
 *   improvement over the flattened structures outlined in CryptoXYZZYConfigMapTest.
 *
 * - I checked the behavior of mounting the unfurled MSP context using static YAML / manual kubectl,
 *   rather than dynamically generating the remote volumes from a test driver and making assertions
 *   in this test routine.  We could do so, but ... the approach seems sound enough to skip forward
 *   and go through the config of the test network using the cryptogen local assets.
 *
 *
 *
 * NEXT STEPS:
 *
 * - use cryptogen to specify the network topology as MSP assets on the local drive.
 *
 * - Describe a NetworkConfig matching the topology of the test network.
 *
 * - For each of the network nodes, expand on "Context" by running deployments in ENV + { msp-context } scope.
 *
 * - Construct the org/orderer/peer network, specifying initContainers and unfurling msp-descriptors before "running things"
 *
 * - Install chaincode, run queries.
 *
 * - Use the LOCAL MSP files (or possibly the k8s msp descriptor maps??) to generate a gateway connection profile.
 *
 * AND
 *
 * - run gateway client app locally (via port-forward to gateway peer node)
 *
 * - run gateway client app in cluster
 *
 *
 * This is an effective plateau for the "V1" approach, using cryptogen + MSP descriptor context.
 *
 * For a "V2" approach, replace the cryptogen with a network topology describing a set of CAs.  This is harder than
 * it seems, as the CA clients need to run somewhere with access to both the peer binaries and an HTTP(s) route into
 * the CAs.
 */
@Slf4j
public class MSPContextTest extends TestBase
{
    //
    // KISS : don't get fancy, just model the MSP context structure exactly as it would appear on
    // a directory volume.
    //
    @Data
    private static class MSP
    {
        final String name;
        final String id;
        final JsonNode msp;

        private MSP(final String name, final File basedir)
        {
            this.name = name;
            this.id = basedir.getName();
            this.msp = helper(new File(basedir, "msp"));
        }

        private JsonNode helper(final File f)
        {
            if (f.isDirectory())
            {
                final ObjectNode node = objectMapper.createObjectNode();

                for (File child : f.listFiles())
                {
                    node.set(child.getName(), helper(child));
                }

                return node;
            }
            else
            {
                // copy file contents directly as a string.  maybe need some b64 encoding here?
                try (final ByteArrayOutputStream baos = new ByteArrayOutputStream())
                {
                    IOUtils.copy(f, baos);
                    return objectMapper.getNodeFactory().textNode(baos.toString(Charset.defaultCharset()));
                }
                catch (IOException ex)
                {
                    fail("Could not read msp asset", ex);
                    return null;
                }
            }
        }
    }

    @Test
    public void testConstructMSP() throws Exception
    {
        final MSP msp =
                new MSP("msp-com.example.orderer1",
                        new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com"));

        log.info("msp:\n{}", yamlMapper.writeValueAsString(msp));

        assertEquals("orderer1.example.com", msp.name);
        assertNotNull(msp.msp);

        assertTrue(msp.msp.has("admincerts"));
        assertTrue(msp.msp.has("cacerts"));
        assertTrue(msp.msp.has("keystore"));
        assertTrue(msp.msp.has("signcerts"));
        assertTrue(msp.msp.has("tlscacerts"));
        assertTrue(msp.msp.has("config.yaml"));

        assertTrue(msp.msp.get("cacerts").has("ca.example.com-cert.pem"));
        assertTrue(msp.msp.get("keystore").has("priv_sk"));
        assertTrue(msp.msp.get("signcerts").has("orderer1.example.com-cert.pem"));
        assertTrue(msp.msp.get("tlscacerts").has("tlsca.example.com-cert.pem"));

        assertTrue(msp.msp.get("cacerts").get("ca.example.com-cert.pem").isTextual());
        assertTrue(msp.msp.get("keystore").get("priv_sk").isTextual());
        assertTrue(msp.msp.get("signcerts").get("orderer1.example.com-cert.pem").isTextual());
        assertTrue(msp.msp.get("tlscacerts").get("tlsca.example.com-cert.pem").isTextual());
        assertTrue(msp.msp.get("config.yaml").isTextual());
    }

    private static final MSP EXAMPLE_ORG_ORDERER1 = new MSP("msp-com.example.orderer1", new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com"));
    private static final MSP EXAMPLE_ORG_ORDERER2 = new MSP("msp-com.example.orderer2", new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer2.example.com"));
    private static final MSP EXAMPLE_ORG_ORDERER3 = new MSP("msp-com.example.orderer3", new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer3.example.com"));
    private static final MSP EXAMPLE_ORG_ORDERER4 = new MSP("msp-com.example.orderer4", new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer4.example.com"));
    private static final MSP EXAMPLE_ORG_ORDERER5 = new MSP("msp-com.example.orderer5", new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer5.example.com"));
    
    
    @Test 
    public void testCreateMSPConfigMap() throws Exception
    {
        ConfigMap cm = null;
        try
        {
            cm = createMSPConfigMap(EXAMPLE_ORG_ORDERER1);

            log.info("Created MSP config:\n{}", yamlMapper.writeValueAsString(cm));
        }
        finally
        {
            client.configMaps().delete(cm);
        }
    }

    @Test
    public void testMakeAFew() throws Exception
    {
        createMSPConfigMap(EXAMPLE_ORG_ORDERER1);
        createMSPConfigMap(EXAMPLE_ORG_ORDERER2);
        createMSPConfigMap(EXAMPLE_ORG_ORDERER3);
        createMSPConfigMap(EXAMPLE_ORG_ORDERER4);
        createMSPConfigMap(EXAMPLE_ORG_ORDERER5);
    }


    private ConfigMap createMSPConfigMap(final MSP msp) throws Exception
    {
        return client.configMaps()
                     .create(new ConfigMapBuilder()
                                     .withNewMetadata()
                                     .withName(msp.name)
                                     // todo: add some metadata labels.
                                     .endMetadata()
                                     .withImmutable(true)
                                     .withData(Map.of(msp.name + ".yaml", yamlMapper.writeValueAsString(msp)))
                                     .build());
    }




    //
    // This is an example peer yaml that includes an init container running the msp-unfurler.
    //
    // At runtime, the contents of the MSP folder will be unfurled into /var/hyperledger/fabric/xyzzy/orderer2.example.com/msp
    //

    /*

    ---
apiVersion: apps/v1
kind: Deployment
metadata:
  namespace: test-network
  name: org1-peer1
spec:
  replicas: 1
  selector:
    matchLabels:
      app: org1-peer1
  template:
    metadata:
      labels:
        app: org1-peer1
    spec:
      containers:
      - name: main
        image: hyperledger/fabric-peer:2.3.2
        imagePullPolicy: Always
        envFrom:
          - configMapRef:
              name: org1-peer1-config
        ports:
          - containerPort: 7051
          - containerPort: 7052
          - containerPort: 9443
        volumeMounts:
          - name: fabric-volume
            mountPath: /var/hyperledger/fabric
          - name: fabric-config
            mountPath: /var/hyperledger/fabric/config

          - name: msp-volume
            mountPath: /var/hyperledger/fabric/xyzzy

      initContainers:
      - name: msp-unfurl
        image: hyperledgendary/fabric-hyper-kube/fabctl-msp-unfurler
        imagePullPolicy: IfNotPresent
        env:
          - name: INPUT_FOLDER
            value: /var/hyperledger/fabric/msp-descriptors
          - name: OUTPUT_FOLDER
            value: /var/hyperledger/fabric/xyzzy
        volumeMounts:
          - name: msp-config
            mountPath: /var/hyperledger/fabric/msp-descriptors
          - name: msp-volume
            mountPath: /var/hyperledger/fabric/xyzzy

      volumes:
        - name: fabric-volume
          persistentVolumeClaim:
            claimName: fabric
        - name: fabric-config
          configMap:
            name: fabric-config
        - name: msp-config
          configMap:
            name: msp-com.example.orderer2
        - name: msp-volume
          emptyDir: {}

     */
}
