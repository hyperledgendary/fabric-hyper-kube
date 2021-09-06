/*-
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.fabctl.v1;


import java.io.File;
import java.io.IOException;
import java.util.Properties;
import org.hyperledger.fabric.fabctl.v1.msp.MSPDescriptor;
import org.hyperledger.fabric.fabctl.v1.network.*;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * test-network is a NetworkConfiguration matching the topology of fabric-samples test-network.
 *
 * This is just a convenience for inflating a sample network for the scope of unit tests.
 *
 * This code is ugly - don't stare too hard - it's just to illustrate a dynamic structure for the fabric sample network.
 *
 * This test network will read the node environment scopes from the /resources/config/v1/networks/test-network resource bundle.
 *
 * Nodes in the test network include an MSP Descriptor, translated from a local crypto-config folder output by cryptogen.
 *
 * TEST OUTCOMES:
 *
 * - The approach works OK but it needs to be streamlined.  This network descriptor is 1000% bloated in current form.
 *
 * - Where will users / enrollments / registrations occur and be stored?
 *
 * - It seems like MSP descriptors should be REFERENCED not embedded in this structure.  Something inspired from k8s
 *   such as kube references (envFrom, secretRef, configMapRef, or Argo workflowRef,... )
 *
 */
class TestNetwork extends NetworkConfig
{
    TestNetwork()
    {
        this("test-network");
    }

    private TestNetwork(final String name)
    {
        super(name);

        try
        {
            //
            // orderer org: three orderers. 
            //
            final OrganizationConfig ordererOrg = 
                    new OrganizationConfig("OrdererOrg",
                                           "OrdererMSP",
                                           new MSPDescriptor("msp-com.example",
                                                             new File("config/crypto-config/ordererOrganizations/example.com")));

            ordererOrg.getOrderers()
                      .add(new OrdererConfig("orderer1",
                                             loadEnvironment("orderer1.properties"),
                                             new MSPDescriptor("msp-com.example.orderer1",
                                                               new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com"))));

            ordererOrg.getOrderers()
                      .add(new OrdererConfig("orderer2",
                                             loadEnvironment("orderer2.properties"),
                                             new MSPDescriptor("msp-com.example.orderer2",
                                                               new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer2.example.com"))));

            ordererOrg.getOrderers()
                      .add(new OrdererConfig("orderer3",
                                             loadEnvironment("orderer3.properties"),
                                             new MSPDescriptor("msp-com.example.orderer3",
                                                               new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer3.example.com"))));


            
            //
            // org1 : two peers + admin context in peer1
            // 
            final OrganizationConfig org1 =
                    new OrganizationConfig("Org1",
                                           "Org1MSP",
                                           new MSPDescriptor("msp-com.example.org1",
                                                             new File("config/crypto-config/peerOrganizations/org1.example.com")));


            //
            // This is an awful hack but org1-peer1 needs an org Admin context MSP in order to run the CLI (cc query from shell)
            /// AND it needs the orderer1 tls/ca.crt...
            // todo: what's the correct way to specify users, enrollments, admin contexts?   Figure this out when switching to the CA.
            // todo: doesn't the admin MSP User context belong up on the org?
            // todo: we don't need the entire MSP for distributing the TLS certificates
            //
            org1.getPeers()
                .add(new PeerConfig("org1-peer1",
                                    loadEnvironment("org1-peer1.properties"),
                                    new MSPDescriptor("msp-com.example.org1.org1-peer1",
                                                      new File("config/crypto-config/peerOrganizations/org1.example.com/peers/org1-peer1.org1.example.com")),
                                    new MSPDescriptor("msp-com.example.org1.user.admin",
                                                      new File("config/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com")),
                                    new MSPDescriptor("msp-com.example.orderer1",
                                                      new File("config/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com"))));

            org1.getPeers()
                .add(new PeerConfig("org1-peer2",
                                    loadEnvironment("org1-peer2.properties"),
                                    new MSPDescriptor("msp-com.example.org1.org1-peer2",
                                                      new File("config/crypto-config/peerOrganizations/org1.example.com/peers/org1-peer2.org1.example.com"))));


            
            // 
            // org2 : two peers 
            // 
            final OrganizationConfig org2 =
                    new OrganizationConfig("Org2",
                                           "Org1MSP",
                                           new MSPDescriptor("msp-com.example.org2",
                                                             new File("config/crypto-config/peerOrganizations/org2.example.com")));


            org2.getPeers()
                .add(new PeerConfig("org2-peer1",
                                    loadEnvironment("org2-peer1.properties"),
                                    new MSPDescriptor("msp-com.example.org2.org2-peer1",
                                                      new File("config/crypto-config/peerOrganizations/org2.example.com/peers/org2-peer1.org2.example.com"))));
            org2.getPeers()
                .add(new PeerConfig("org2-peer2",
                                    loadEnvironment("org2-peer2.properties"),
                                    new MSPDescriptor("msp-com.example.org2.org2-peer2",
                                                      new File("config/crypto-config/peerOrganizations/org2.example.com/peers/org2-peer2.org2.example.com"))));


            organizations.add(ordererOrg);
            organizations.add(org1);
            organizations.add(org2);
        }
        catch (Exception ex)
        {
            fail("Could not load test network config", ex);
        }
    }

    /**
     * This can be improved, but it's not super relevant HOW the context is initialized.  Just experimenting here...
     */
    protected static Environment loadEnvironment(final String name)
    {
        final String path = "/config/v1/networks/test-network/" + name;
        final Properties props = new Properties();

        try
        {
            props.load(CryptoXYZZYConfigMapTest.class.getResourceAsStream(path));
        }
        catch (IOException ex)
        {
            fail("Could not load resource bundle " + path, ex);
        }

        final Environment environment = new Environment();
        for (Object o : props.keySet())
        {
            final String key = o.toString();
            environment.put(key, props.getProperty(key));
        }

        return environment;
    }

}
