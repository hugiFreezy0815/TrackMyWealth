# Multi-stage build so the runtime image ships only the built jar and a JRE, not Maven or the
# source tree. Base images are multi-arch (amd64/arm64), so this builds and runs unchanged on an
# Intel/AMD machine, an Apple Silicon Mac, or an arm64 NAS - see docker-compose.yml.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Dependencies are resolved before the source is copied in, so editing application code doesn't
# invalidate this layer's cache on rebuild.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app

# Runs as a non-root user - unlike the Maven build stage, this is the image that actually faces
# the network.
RUN addgroup --system trackmywealth && adduser --system --ingroup trackmywealth trackmywealth
COPY --from=build /build/target/*.jar app.jar
USER trackmywealth

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
