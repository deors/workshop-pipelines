FROM docker.io/eclipse-temurin:20.0.1_9-jdk
VOLUME /tmp
ADD target/dependency/jacocoagent.jar jacocoagent.jar
ADD target/workshop-pipelines.jar app.jar
ENTRYPOINT exec java $JAVA_OPTS -jar /app.jar
