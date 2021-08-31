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

### Kube

```shell
kind create cluster
kind load docker-image hyperledgendary/fabric-ccs-builder
kind load docker-image hyperledger/chaincode/asset-transfer-basic
```

```shell
kubectl apply -f src/test/resources/kube/ns-test-network.yaml
kubectl apply -f src/test/resources/kube/pv-fabric.yaml
kubectl apply -f src/test/resources/kube/pvc-fabric.yaml
```

### Test Network

```shell
kubectl -n test-network create configmap fabric-config --from-file=config/
kubectl -n test-network create -f src/test/resources/kube/job-crypto-config.yaml
kubectl -n test-network wait --for=condition=complete --timeout=120s job/job-crypto-config

echo -n | ./gradlew test --tests InitFabricNetworkTest      # network.sh up 
echo -n | ./gradlew test --tests CreateAndJoinChannelTest   # network.sh createChannel
echo -n | ./gradlew test --tests ChaincodeSandboxTest       # network.sh deployCC 
```

- TODO: don't run cryptogen in the cluster.  Set up a CA
- TODO: introduce an _MSP context_ and load into k8s secrets/configmaps
- Note: the above `echo -n |` is set up to give gradle a dedicated stdin.  (Without it, the commands can 
  not be pasted into a terminal window as a sequential block.)



### Chaincode Query

open a shell to org1-peer1 and:
```shell
kubectl -n test-network exec deploy/org1-peer1 -i -t -- /bin/sh
export CORE_PEER_MSPCONFIGPATH=/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp
export FABRIC_LOGGING_SPEC=INFO

peer chaincode \
  invoke \
  -o orderer1:6050 \
  -C mychannel \
  -n basic \
  -c '{"Args":["CreateAsset","1","blue","35","tom","1000"]}' \
  --tls \
  --cafile /var/hyperledger/fabric/crypto-config/ordererOrganizations/example.com/orderers/orderer1.example.com/tls/ca.crt \

sleep 5

peer chaincode query -C mychannel -n basic -c '{"Args":["ReadAsset","1"]}'

# exit
```

- TODO: deploy the fabric-rest-sample and a connection profile for access to the ledgers via REST entrypoints.


## Teardown

```shell
kubectl -n test-network delete deployment --all 
kubectl -n test-network delete pod --all
kubectl -n test-network delete service --all
kubectl -n test-network delete configmap --all 
kubectl -n test-network delete secret --all 
kubectl -n test-network create -f src/test/resources/kube/job-scrub-test-network.yaml
kubectl -n test-network wait --for=condition=complete --timeout=60s job/job-scrub-fabric-volume
kubectl -n test-network delete job --all
```
[GOTO Network](#test-network)

or ...
```shell
kind delete cluster
```
[GOTO Kube](#kube)