/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1.msp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import lombok.Data;
import org.apache.commons.compress.utils.IOUtils;

/**
 * An MSP descriptor is a json / yaml rendering of an MSP folder structure
 *
 * Still stirring the pot.  Nothing final in here...
 */
@Data
public class MSPDescriptor
{
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public final String name;
    public final String id;
    public final JsonNode msp;
    public final JsonNode tls;

    public MSPDescriptor(final String name, final File basedir) throws IOException
    {
        this.name = name;
        this.id = basedir.getName();
        this.msp = helper(new File(basedir, "msp"));
        this.tls = helper(new File(basedir, "tls"));
    }

    private JsonNode helper(final File f) throws IOException
    {
        if (! f.exists())
        {
            // e.g.. no 'tls' folder under root folder.
            return null;
        }
        else if (f.isDirectory())
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
        }
    }
}
