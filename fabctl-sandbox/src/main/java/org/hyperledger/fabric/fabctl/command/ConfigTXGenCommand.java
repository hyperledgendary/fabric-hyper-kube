package org.hyperledger.fabric.fabctl.command;

import lombok.Data;

public class ConfigTXGenCommand extends FabricCommand
{
    public ConfigTXGenCommand(final String... command)
    {
        super("hyperledger/fabric-tools", command);
    }
}
