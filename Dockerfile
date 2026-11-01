FROM maven:3.9-eclipse-temurin-21 AS build
ARG ICE4J_VERSION=3.2-17-geea6cd3
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B -Dice4j.version=${ICE4J_VERSION} dependency:go-offline
COPY src src
RUN mvn -q -B -Dice4j.version=${ICE4J_VERSION} package

FROM eclipse-temurin:21-jre
COPY --from=build /build/target/repro.jar /repro.jar
ENTRYPOINT ["java", "-jar", "/repro.jar"]
