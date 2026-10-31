# syntax=docker/dockerfile:1
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JAVA_IMAGE=eclipse-temurin:21-jre-jammy
FROM ${MAVEN_IMAGE} AS build
ARG MAVEN_CENTRAL_URL=https://repo.maven.apache.org/maven2
ENV MAVEN_CENTRAL_URL=$MAVEN_CENTRAL_URL
WORKDIR /build
COPY backend/pom.xml ./
COPY docker/maven-settings.xml ./settings.xml
COPY backend/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -s settings.xml -Dmaven.test.skip=true package
FROM ${JAVA_IMAGE}
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build /build/target/repaircalc-backend-0.1.0-SNAPSHOT.jar /app/backend.jar
COPY data /app/data
COPY docker/init-data.sh /app/init-data.sh
ENV CLASSIFICATION_TAXONOMY=/app/data/classification/taxonomy.json INSPIRATIONS_DIR=/app/data/inspirations INSPIRATIONS_MANIFEST=/app/data/inspirations/manifest.json
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
