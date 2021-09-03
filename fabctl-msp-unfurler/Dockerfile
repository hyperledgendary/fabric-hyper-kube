FROM openjdk

ARG JAR_FILE=build/libs/fabctl-msp-unfurler.jar
ADD ${JAR_FILE} fabctl-msp-unfurler.jar
ENTRYPOINT ["java", "-jar", "/fabctl-msp-unfurler.jar"]