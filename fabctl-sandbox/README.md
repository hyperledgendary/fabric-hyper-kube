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

### Kubernetes

```shell
kind create cluster
kind load docker-image hyperledgendary/fabric-ccs-builder
kind load docker-image hyperledger/chaincode/asset-transfer-basic
```

```shell
kubectl create -f src/test/resources/kube/pv-fabric.yaml
kubectl create -f src/test/resources/kube/ns-test-network.yaml
kubectl -n test-network create -f src/test/resources/kube/pvc-fabric.yaml
```

### Test Network

#### v0 

crypto-spec is stored "in cluster" 

```shell
kubectl -n test-network create configmap fabric-config --from-file=config/
kubectl -n test-network create -f src/test/resources/kube/job-crypto-config.yaml
kubectl -n test-network wait --for=condition=complete --timeout=120s job/job-crypto-config
```

```shell
echo -n | ./gradlew test --tests org.hyperledger.fabric.fabctl.v0.InitFabricNetworkTest      # network.sh up 
echo -n | ./gradlew test --tests org.hyperledger.fabric.fabctl.v0.CreateAndJoinChannelTest   # network.sh createChannel
echo -n | ./gradlew test --tests org.hyperledger.fabric.fabctl.v0.ChaincodeSandboxTest       # network.sh deployCC 
```

#### v1

crypto-spec is stored locally and reflected as configmaps / secrets 

```shell
docker run \
  --rm \
  -v ${PWD}/config:/config \
  hyperledger/fabric-tools \
    cryptogen generate \
    --config=/config/crypto-config.yaml \
    --output=/config/crypto-config 

kubectl -n test-network create configmap fabric-config --from-file=config/
```

```shell
???

echo -n | ./gradlew test --tests InitFabricNetworkTest      # network.sh up 
echo -n | ./gradlew test --tests CreateAndJoinChannelTest   # network.sh createChannel
echo -n | ./gradlew test --tests ChaincodeSandboxTest       # network.sh deployCC 
```

- TODO: don't run cryptogen in the cluster.  Set up a CA
- TODO: introduce an _MSP context_ and load into k8s secrets/configmaps
- Note: the above `echo -n |` is set up to give gradle a dedicated stdin.  (Without it, the commands can 
  not be pasted into a terminal window as a sequential block.)


### Chaincode Query 

todo: deploy the fabric-rest-sample and a connection profile for access to the ledgers via REST entrypoints. Until then ... shell into a peer and: 

[Query Chaincode](https://github.com/jkneubuh/fabric-samples/tree/feature/kind-test-network/test-network-kind#query)


## Teardown 

```shell
kubectl -n test-network delete deployment --all 
kubectl -n test-network create -f src/test/resources/kube/job-scrub-test-network.yaml
kubectl -n test-network wait --for=condition=complete --timeout=60s job/job-scrub-fabric-volume
kubectl delete namespace test-network 
kubectl delete pv fabric
```


or ... 
```shell
kind delete cluster
```

- TODO: add script and notes to scrub the network without destroying the KIND cluster. 