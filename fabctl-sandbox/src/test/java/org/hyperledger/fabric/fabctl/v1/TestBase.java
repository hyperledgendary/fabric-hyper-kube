/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TestBase
{
    protected static final ObjectMapper objectMapper = new ObjectMapper();

    protected static final YAMLMapper yamlMapper = new YAMLMapper();

    protected static final String TEST_NAMESPACE = "test-network";
}
