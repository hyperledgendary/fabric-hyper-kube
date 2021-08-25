package org.hyperledger.fabric.fabctl;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Slf4j
public class NoOpTest
{
    @Data
    public static class SimpleStruct
    {
        int x;
        double y;
        String z;
    }

    @Test
    public void testNothing()
    {
        final SimpleStruct s = new SimpleStruct();
        s.setX(10);
        s.setY(20.0);
        s.setZ("a string");

        log.info("this is a log message: {}", s);
        assertTrue(true);
    }


}
