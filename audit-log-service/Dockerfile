FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring -u 65532
USER 65532
VOLUME /tmp
ARG JAR_FILE=target/*.jar
COPY --chown=65532:spring ${JAR_FILE} app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
