/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.File;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

/**
 * configtx.yaml defines, in broad strokes, the topology of a Fabric network sufficient to generate the
 * genesis blocks on a system channel or orderer admin.
 *
 * In network.sh there is a need to bring up a network, which has been hacked in these tests as a quick
 * data structure specifying a hierarchy of orderers, peers, and their relevant context (k/v env) for
 * launching within a pod/deployment in k8s.
 *
 * What would be ideal is if the fabctl system had thet ability to specify multi-organization fabric
 * networks in a holistic fashion.  I.e.. rather than have a single "network descriptor" document, perhaps
 * we should be focusing on the mechanics of automating the workflow for a single organization, then
 * expand outward to apply the single-org mechanics across a set of org descriptors.   Effectively the
 * "network descriptor" becomes:
 *
 * ---
 * network:
 *   organizations:
 *   - orgRef:
 *       id: org1.example.com
 *   - orgRef:
 *       id: org2.example.com
 *   - orgRef:
 *       id: orderer.example.com
 *
 * ---
 * organization:
 *   metadata:
 *     id: org1.example.com
 *     name: org1
 *     ...
 *   peers:
 *   - name: org1-peer1
 *     id: org1-peer1.org1.example.com
 *     ...
 *   - name: org1-peer2
 *     id: org1-peer2.org1.example.com
 *
 * ---
 * organization:
 *   metadata:
 *     id: org2.example.com
 *     name: org2
 *     ...
 *   peers:
 *   - name: org2-peer1
 *     url: peer1:7070
 *     ...
 *
 * ---
 * organization:
 *   metadata:
 *     id: orderer.example.com
 *     name: org3
 *   orderers:
 *   - name: orderer1
 *     url: orderer1.orderer.example.com:6050
 *   - name: orderer2
 *     url: orderer2.orderer.example.com:6050
 *   - name: orderer3
 *     url: orderer3.orderer.example.com:6050
 */
@Slf4j
public class ConfigTXReaderTest
{
    private static final YAMLMapper yamlMapper = new YAMLMapper();

    private static final File FIXTURES_DIR = new File("src/test/resources/fixtures");

    /**
     * Just a warm-up.  Can we even read the configtx.yaml?
     */
    @Test
    public void testReadConfigTXYAML() throws Exception
    {
        final File configtxFile = new File(FIXTURES_DIR, "configtx.yaml");
        final JsonNode config = yamlMapper.readTree(configtxFile);

        log.info("read config:\n{}", yamlMapper.writeValueAsString(config));

    }
}
