/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1.chaincode;

import lombok.Data;

/**
 * overly simplified data structure to describe an external chaincode endpoint.
 */
@Data
public class ChaincodeDescriptor
{
    // private final String apiVersion = "v1";              -- think of this like a CRD, but currently saved in a config map.
    // private final String kind = "ChaincodeDescriptor";
    public final ChaincodeMetadata metadata;
    public final ChaincodeConnection connection;
}
