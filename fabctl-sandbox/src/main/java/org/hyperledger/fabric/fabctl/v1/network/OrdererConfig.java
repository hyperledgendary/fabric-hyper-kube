/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1.network;

import lombok.Data;

@Data
public class OrdererConfig
{
    public final String name;
    public final Environment environment;

    public OrdererConfig(final String name, final Environment environment)
    {
        this.name = name;
        this.environment = environment;
    }
}