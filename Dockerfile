FROM openjdk:8-jre-alpine
COPY ./mmo/jvm/build/libs/mmo-server.jar /root/mmo-server.jar
COPY ./web /root/web
WORKDIR /root
CMD ["java", "-server", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCGroupMemoryLimitForHeap", "-XX:InitialRAMFraction=2", "-XX:MinRAMFraction=2", "-XX:MaxRAMFraction=2", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100", "-XX:+UseStringDeduplication", "-jar", "mmo-server.jar"]
