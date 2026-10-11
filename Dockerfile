FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build --chown=1001:0 /build/target/quarkus-app/ ./
USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
