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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.IOUtils;
import org.hyperledger.fabric.fabctl.v1.network.Environment;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.fail;

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

    /**
     * This can be improved, but it's not super relevant HOW the context is initialized.  Just experimenting here...
     */
    protected static Environment loadEnvironment(final String path)
    {
        final Properties props = new Properties();

        try
        {
            props.load(CryptoXYZZYConfigMapTest.class.getResourceAsStream(path));
        }
        catch (IOException ex)
        {
            fail("Could not load resource bundle " + path, ex);
        }

        final Environment environment = new Environment();
        for (Object o : props.keySet())
        {
            final String key = o.toString();
            environment.put(key, props.getProperty(key));
        }

        return environment;
    }

    protected String load(File path) throws IOException
    {
        try (final ByteArrayOutputStream baos = new ByteArrayOutputStream();
             final FileInputStream fis = new FileInputStream(path))
        {
            IOUtils.copy(fis, baos);

            return baos.toString(Charset.defaultCharset());
        }
    }

    protected String loadBinary(File path) throws IOException
    {
        try (final ByteArrayOutputStream baos = new ByteArrayOutputStream();
             final FileInputStream fis = new FileInputStream(path))
        {
            IOUtils.copy(fis, baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

}
