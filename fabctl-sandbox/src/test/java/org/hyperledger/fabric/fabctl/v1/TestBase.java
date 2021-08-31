/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;

@Slf4j
public class TestBase
{
    protected static final ObjectMapper objectMapper = new ObjectMapper();

    protected static final YAMLMapper yamlMapper = new YAMLMapper();

    protected static final String TEST_NAMESPACE = "test-network";


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
}
