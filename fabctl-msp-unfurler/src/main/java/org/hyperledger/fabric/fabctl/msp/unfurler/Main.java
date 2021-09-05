/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.msp.unfurler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Iterator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;

/**
 * This is an incredibly rough prototype routine that will unfurl
 * fabric MSP descriptors into MSP Folder structures on disk.  The intent
 * is that this entrypoint be set up as an init container on pods running
 * fabric binaries.
 */
@Slf4j
public class Main
{
    private static final YAMLMapper yamlMapper = new YAMLMapper();

    /**
     * Really, really, really simple.  Just enough to see if this scheme will work.
     */
    public static void main(final String[] args)
    {
        log.info("hello, world");

        final String inputFolder = System.getenv("INPUT_FOLDER");
        final String outputFolder = System.getenv("OUTPUT_FOLDER");

        log.info("Scanning {} for msp descriptors", inputFolder);
        log.info("Writing output MSP structures to {}", outputFolder);

        final File inputDir = new File(inputFolder);
        final File outputDir = new File(outputFolder);

        if (! inputDir.exists())
        {
            log.error("input folder {} does not exist.", inputDir);
            System.exit(1);
        }

        // not realistic - just process any file ending in .yaml
        final FileFilter fileFilter = new WildcardFileFilter("msp-*.yaml");

        for (File descriptor : inputDir.listFiles(fileFilter))
        {
            try
            {
                unfurl(outputDir, descriptor);
            }
            catch (Exception ex)
            {
                log.error("Could not unfurl " + descriptor, ex);
                System.exit(1);
            }
        }

        System.exit(0);
    }

    private static void unfurl(final File outputDir, final File descriptor)
            throws IOException
    {
        final JsonNode node = yamlMapper.readTree(descriptor);
        log.info(yamlMapper.writeValueAsString(node));

        final File mspDir = new File(outputDir, node.get("id").asText());
        log.info("Unfurling MSP descriptor {} --> {}", descriptor, mspDir);

        if (mspDir.exists())
        {
            throw new RuntimeException("msp folder " + mspDir + " exists and will not be overwritten.");
        }

        unfurl(new File(mspDir, "msp"), node.get("msp"));
        unfurl(new File(mspDir, "tls"), node.get("tls"));
    }

    private static void unfurl(final File f, final JsonNode node) throws IOException
    {
        // no node?  no unfurling
        if (node == null)
        {
            return;
        }

        if (node.isTextual())
        {
            log.info("writing {}", f);

            try (final FileOutputStream fos = new FileOutputStream(f))
            {
                IOUtils.write(node.textValue(), fos, Charset.defaultCharset());
            }
        }
        else if (node instanceof ObjectNode)
        {
            f.mkdirs();

            final ObjectNode on = (ObjectNode) node;
            final Iterator<String> i = on.fieldNames();
            while (i.hasNext())
            {
                final String field = i.next();
                final JsonNode child = on.get(field);

                unfurl(new File(f, field), child);
            }
        }
        else
        {
            throw new RuntimeException("can not unfurl node " + node);
        }
    }
}
