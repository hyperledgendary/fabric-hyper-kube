/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1.network;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class OrganizationConfig
{
    public final String name;
    public String mspID;

    public final List<CAConfig> cas = new ArrayList<>();
    public final List<PeerConfig> peers = new ArrayList<>();
    public final List<OrdererConfig> orderers = new ArrayList<>();

    public OrganizationConfig(final String name)
    {
        this.name = name;
    }
}
