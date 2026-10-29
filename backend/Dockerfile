# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*     && groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build /build/target/repaircalc-backend-0.1.0-SNAPSHOT.jar /app/backend.jar
COPY data /app/data
COPY docker/init-data.sh /app/init-data.sh
USER 10001:10001
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
