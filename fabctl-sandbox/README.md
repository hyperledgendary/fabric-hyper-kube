# fabctl sandbox 

This is a quickstart sandbox for emulating the functionality of `network.sh`, kube-native style.

This code base is intentionally rough.  It is a sandbox for rapidly iterating through various mechanics 
for cluster config, job control, and design of _peer connection profiles_ to achieve fabric administration 
objectives on a remote / cloud-based cluster.

## Quickstart 

### Kube setup

```shell
kind create cluster

kubectl apply -f src/test/resources/kube/ns-test-network.yaml
kubectl apply -f src/test/resources/kube/pv-fabric.yaml
kubectl apply -f src/test/resources/kube/pvc-fabric.yaml
```

### Test Network 

- TODO: introduce _fabric network descriptor_ as a local config resource, and inflate the cluster dynamically.
- TODO: don't run cryptogen in the cluster.  Set up a CA 
- TODO: inflate the cluster from a test case, not copy/paste README

```shell 
kubectl -n test-network create configmap fabric-config --from-file=config/
```

```shell
kubectl create -f src/test/resources/kube/job-crypto-config.yaml
kubectl create -f src/test/resources/kube/job-orderer-genesis.yaml
kubectl create -f src/test/resources/kube/job-update-org1-anchor-peers.yaml
kubectl create -f src/test/resources/kube/job-update-org2-anchor-peers.yaml
```

```shell
kubectl apply -f src/test/resources/kube/orderer1.yaml
kubectl apply -f src/test/resources/kube/orderer2.yaml
kubectl apply -f src/test/resources/kube/orderer3.yaml
```

```shell
kubectl apply -f src/test/resources/kube/org1-peer1.yaml
kubectl apply -f src/test/resources/kube/org1-peer2.yaml
kubectl apply -f src/test/resources/kube/org2-peer1.yaml
kubectl apply -f src/test/resources/kube/org2-peer2.yaml
```


### Channel, Chaincode, Application 

Run the test case routines interactively in an IDE / etc. 

## Teardown 

```shell
kind delete cluster
```