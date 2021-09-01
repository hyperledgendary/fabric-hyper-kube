/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1.network;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Nothing fancy, still just stirring the pot.  Think of this as a local YAML file describing the overall structure
 * of a fabric network.   The closest equivalent would be something from minifab's network descriptor.
 */
@Data
public class NetworkConfig
{
    public final Metadata metadata;
    public final List<OrganizationConfig> organizations = new ArrayList<>();

    public NetworkConfig(final String name)
    {
        this.metadata = new Metadata(name);
    }
}
