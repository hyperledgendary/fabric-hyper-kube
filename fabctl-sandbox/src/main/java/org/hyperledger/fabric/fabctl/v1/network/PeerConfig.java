/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1.network;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.hyperledger.fabric.fabctl.v1.msp.MSPDescriptor;

@Data
public class PeerConfig
{
    public final String name;
    public final Environment environment;
    public final List<MSPDescriptor> msps = new ArrayList<>();

    public PeerConfig(final String name, final Environment environment, final MSPDescriptor... msps)
    {
        this.name = name;
        this.environment = environment;

        for (MSPDescriptor msp : msps)
        {
            this.msps.add(msp);
        }
    }
}
