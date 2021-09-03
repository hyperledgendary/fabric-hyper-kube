# MSP Unfurler 

This experimental routine will read YAML descriptor files from an input folder,
unfurling the contents into a folder structure matching the `MSPDir` fabric 
configuration attribute.

This will be used in conjunction with an init container to extract an MSP Context from 
a series of configmaps / secrets stored in Kubernetes.

## Build 

```shell
./gradlew build 

docker build -t hyperledgendary/fabric-hyper-kube/fabctl-msp-unfurler .

kind load docker-image hyperledgendary/fabric-hyper-kube/fabctl-msp-unfurler .
```

## Run  

```shell
docker run \
  --rm \
  -e INPUT_FOLDER=/var/hyperledger/msp/in \
  -e OUTPUT_FOLDER=/var/hyperledger/msp/out \
  -v /tmp/msp:/var/hyperledger/msp \
  hyperledgendary/fabric-hyper-kube/fabctl-msp-unfurler
```