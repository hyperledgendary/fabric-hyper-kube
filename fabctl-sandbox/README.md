# fabctl sandbox 

This is a quickstart sandbox for emulating the functionality of `network.sh`, kube-native style.

This code base is intentionally rough.  It is a sandbox for rapidly iterating through various mechanics 
for cluster config, job control, and design of _peer connection profiles_ to achieve fabric administration 
objectives on a remote / cloud-based cluster.

The usage scenarios are intended to be run interactively through an IDE (+debugger) or in a scripted sequence
through gradle unit test invocations.  Remember, this project is just using gradle + java + JUnit as a 
mechanism to quickly thrash and iterate through various approaches.  After things start to settle down the 
routines may be refactored into CLIs, service APIs, or eventually into a fully-fledged k8s Controller. 


## Quickstart 

### Kube setup

```shell
kind create cluster
kind load docker-image hyperledgendary/fabric-ccs-builder
kind load docker-image hyperledger/chaincode/asset-transfer-basic

kubectl apply -f src/test/resources/kube/ns-test-network.yaml
kubectl apply -f src/test/resources/kube/pv-fabric.yaml
kubectl apply -f src/test/resources/kube/pvc-fabric.yaml
```

### Test Network 

- TODO: don't run cryptogen in the cluster.  Set up a CA 
- TODO: introduce an _MSP context_ and load into k8s secrets/configmaps 

```shell 
kubectl -n test-network create configmap fabric-config --from-file=config/
kubectl -n test-network create -f src/test/resources/kube/job-crypto-config.yaml
kubectl -n test-network wait --for=condition=complete --timeout=120s job/job-crypto-config
```

Create a three-orderer, two-org, quad-peer network: 
```shell
./gradlew test --tests InitFabricNetworkTest
```


### Channel, Chaincode, Application 

Create `mychannel` and join to all peers: 
```shell
./gradlew test --tests CreateAndJoinChannelTest 
```

Deploy `asset-transfer-basic` running in CCaaS mode: 
```shell
./gradlew test --tests ChaincodeSandboxTest
```

## Teardown 

```shell
kind delete cluster
```