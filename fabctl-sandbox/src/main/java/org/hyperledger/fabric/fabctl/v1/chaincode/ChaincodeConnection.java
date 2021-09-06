/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1.chaincode;

import lombok.Data;

@Data
public class ChaincodeConnection
{
    public String address;
    public String dial_timeout;
    public boolean tls_required;
}
