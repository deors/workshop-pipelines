FROM eclipse-temurin:17.0.3_7-jdk
VOLUME /tmp
ADD target/workshop-pipelines.jar app.jar
ENTRYPOINT exec java $JAVA_OPTS -jar /app.jar
