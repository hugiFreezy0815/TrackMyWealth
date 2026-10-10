# Multi-stage build so the runtime image ships only the built jar and a JRE, not Maven or the
# source tree. Base images are multi-arch (amd64/arm64), so this builds and runs unchanged on an
# Intel/AMD machine, an Apple Silicon Mac, or an arm64 NAS - see docker-compose.yml.

FROM maven:3-eclipse-temurin-27 AS build
WORKDIR /build

# Dependencies are resolved before the source is copied in, so editing application code doesn't
# invalidate this layer's cache on rebuild. Built through the Maven Wrapper (#194), so the image
# uses the same pinned Maven version as CI and every local build, not the base image's own.
# unzip: without it the wrapper silently fetches Maven's .tar.gz instead of the .zip that
# .mvn/wrapper/maven-wrapper.properties pins by distributionSha256Sum, and the checksum check fails.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
COPY mvnw pom.xml ./
COPY .mvn ./.mvn
RUN ./mvnw -B dependency:go-offline

COPY src ./src
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app

# #153: deterministic fallback for any library code that still consults Locale.getDefault().
# Request validation itself is request/user-localized and does not depend on this value. The JVM
# locale is pinned on the java command line (ENTRYPOINT) rather than via JAVA_TOOL_OPTIONS, which a
# deployment setting its own JAVA_TOOL_OPTIONS would silently replace.
ENV LANG=C.UTF-8

# #276: OCR for scanned PDF statements runs in this container, with local English and German
# models only - a statement is personal financial data and is never sent to an external service.
# The versions are those of the base image's Ubuntu release (26.04); a base image on another
# release fails here, so a new Tesseract is a deliberate change, never a silent one.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr=5.5.0-1build1 \
        tesseract-ocr-eng=1:4.1.0-2build1 \
        tesseract-ocr-deu=1:4.1.0-2build1 \
    && rm -rf /var/lib/apt/lists/* \
    && tesseract --list-langs | grep -qx deu \
    && tesseract --list-langs | grep -qx eng
# Tesseract otherwise starts an OpenMP thread per core for every page; LocalOcrService already
# runs two recognitions side by side, and more threads only contend for the same cores.
ENV OMP_THREAD_LIMIT=1

# Runs as a non-root user - unlike the Maven build stage, this is the image that actually faces
# the network.
RUN addgroup --system trackmywealth && adduser --system --ingroup trackmywealth trackmywealth
COPY --from=build /build/target/*.jar app.jar
USER trackmywealth

EXPOSE 8080
ENTRYPOINT ["java", "-Duser.language=en", "-Duser.country=US", "-jar", "app.jar"]
