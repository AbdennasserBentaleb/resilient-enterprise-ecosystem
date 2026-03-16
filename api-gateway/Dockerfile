# Stage 1: Build
FROM maven:3.9.6-eclipse-temurin-17-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Run
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring -u 65532
USER 65532
VOLUME /tmp
COPY --from=build --chown=65532:spring /app/target/*.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
