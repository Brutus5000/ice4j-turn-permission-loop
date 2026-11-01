FROM maven:3.9-eclipse-temurin-21 AS build
ARG ICE4J_VERSION=3.2-22-gcb4d152
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B -Dice4j.version=${ICE4J_VERSION} dependency:go-offline
COPY src src
RUN mvn -q -B -Dice4j.version=${ICE4J_VERSION} package

FROM eclipse-temurin:21-jre
COPY --from=build /build/target/repro.jar /repro.jar
ENTRYPOINT ["java", "-cp", "/repro.jar"]
CMD ["repro.Repro"]
