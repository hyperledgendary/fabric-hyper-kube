/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v0.command;

import lombok.Data;

// still stirring the pot. .. don't refactor this yet.
@Data
public class FabricCommand
{
    public final String image;
    public final String label = "2.3.2";
    public final String[] command;

    public FabricCommand(final String image, final String[] command)
    {
        this.image = image;
        this.command = command;
    }

    public FabricCommand()
    {
        this.image = null;
        this.command = null;
    }
}
