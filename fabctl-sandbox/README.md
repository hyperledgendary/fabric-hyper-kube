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

kind load docker-image hyperledger/chaincode/asset-transfer-basic
kind load docker-image hyperledgendary/fabric-ccs-builder
kind load docker-image hyperledgendary/fabric-hyper-kube/fabctl-msp-unfurler
kind load docker-image hyperledgendary/fabric-rest-sample
```

```shell
kubectl create -f src/test/resources/kube/pv-fabric.yaml
kubectl create -f src/test/resources/kube/ns-test-network.yaml
kubectl -n test-network create -f src/test/resources/kube/pvc-fabric.yaml
```

- TODO: add doc pointers on where/how to build local Docker images 
- TODO: add an nginx ingress controller to KIND cluster 
- TODO: add a kustomization base and overlays for installation to KIND, IKS, and OCP (pvc + ingress) 


### Test Network

```shell
rm -rf config/crypto-config/
docker run \
  --rm \
  -v ${PWD}/config:/config \
  hyperledger/fabric-tools \
    cryptogen generate \
    --config=/config/crypto-config.yaml \
    --output=/config/crypto-config 
```

```shell
echo -n | ./gradlew test --tests org.hyperledger.fabric.fabctl.v1.InitFabricNetworkTest      # network.sh up 
echo -n | ./gradlew test --tests org.hyperledger.fabric.fabctl.v1.CreateAndJoinChannelTest   # network.sh createChannel
echo -n | ./gradlew test --tests org.hyperledger.fabric.fabctl.v1.ChaincodeSandboxTest       # network.sh deployCC 
```

### Chaincode Query 

```shell
kubectl -n test-network exec deploy/org1-peer1 -i -t -- /bin/sh
# export CORE_PEER_MSPCONFIGPATH=/var/hyperledger/fabric/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp  # v0
export CORE_PEER_MSPCONFIGPATH=/var/hyperledger/fabric/xyzzy/Admin@org1.example.com/msp  #v1 
export FABRIC_LOGGING_SPEC=INFO

peer chaincode \
  invoke \
  -o orderer1:6050 \
  -C mychannel \
  -n basic \
  -c '{"Args":["CreateAsset","1","blue","35","tom","1000"]}' \
  --tls \
  --cafile /var/hyperledger/fabric/xyzzy/orderer1.example.com/tls/ca.crt \

sleep 5

peer chaincode query -C mychannel -n basic -c '{"Args":["ReadAsset","1"]}'

exit
```

### REST Easy 

```shell
echo -n | ./gradlew test --tests org.hyperledger.fabric.fabctl.v1.FabricRESTSampleTest
```

Open a port forward to the REST sample service in a new shell: 
```shell
kubectl -n test-network port-forward svc/fabric-rest-sample 3000:3000
```

Follow the HTTP examples from [fabric-rest-sample](https://github.com/hyperledgendary/fabric-rest-sample)
```shell
export SAMPLE_APIKEY=97834158-3224-4CE7-95F9-A148C886653E

curl --header "X-Api-Key: ${SAMPLE_APIKEY}" http://localhost:3000/api/assets | jq
```



- TODO: Run the rest endpoint locally (docker/main()/...) and connect via ingress or port-forward


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


## Iterations (Legacy)

### v0

In the first approach for this project, the MSP assets were generated IN the cluster and written to a persistent 
volume by calling `cryptogen` from a batch Job.  Mechanically this works OK, but makes it nigh-impossible to 
dynamically construct a Gateway Connection Profile for client-side application development. 

```shell
kubectl -n test-network create configmap fabric-config --from-file=config/v0/
kubectl -n test-network create -f src/test/resources/kube/job-crypto-config.yaml
kubectl -n test-network wait --for=condition=complete --timeout=120s job/job-crypto-config

echo -n | ./gradlew test --tests org.hyperledger.fabric.fabctl.v0.InitFabricNetworkTest      # network.sh up 
echo -n | ./gradlew test --tests org.hyperledger.fabric.fabctl.v0.CreateAndJoinChannelTest   # network.sh createChannel
echo -n | ./gradlew test --tests org.hyperledger.fabric.fabctl.v0.ChaincodeSandboxTest       # network.sh deployCC 
```

### v1

In v1 (the current stake), crypto assets are generated by running `cryptogen` from a Docker container, storing the 
MSP assets on a LOCAL DRIVE.  Local directories are read and transformed into an MSP YAML DESCRIPTOR file, mounted 
in the network pods as config maps, and unfurled back into folder structures by an init container. 


### v2

In v2 we'll carry forward the approach of the MSP and Network descriptors, introducing a dynamic mechanism to 
bootstrap the fabric CAs, Enrollments, Registrations, MSP Context, and mutual TLS auth. 

