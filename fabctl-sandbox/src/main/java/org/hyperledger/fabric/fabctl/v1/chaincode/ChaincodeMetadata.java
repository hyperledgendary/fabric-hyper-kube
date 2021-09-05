/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1.chaincode;

import lombok.Data;

@Data
public class ChaincodeMetadata
{
    public final String type = "external";
    public String name;
    public String label;
    public String image;

    public String description;
    public String author;
    // .. other stuff...
    // project_URL;
    // org
    // version (not label - semantic revision)
    // tags / labels
    // ...
}
