package org.hyperledger.fabric.fabctl.v1;

import lombok.extern.slf4j.Slf4j;
import org.hyperledger.fabric.fabctl.v1.network.*;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@Slf4j
public class NetworkDescriptorTest extends TestBase
{
    @Test
    public void testPrettyPrintNetwork() throws Exception
    {
        final NetworkConfig network = new TestNetwork();
        log.info("Network Configuration:\n{}", yamlMapper.writeValueAsString(network));
    }
}
