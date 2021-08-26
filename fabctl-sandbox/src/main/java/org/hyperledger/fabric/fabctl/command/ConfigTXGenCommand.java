/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.command;

import lombok.Data;

public class ConfigTXGenCommand extends FabricCommand
{
    public ConfigTXGenCommand(final String... command)
    {
        super("hyperledger/fabric-tools", command);
    }
}
