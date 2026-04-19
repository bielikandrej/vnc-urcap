# STIMBA VNC Server URCap — reproducible build container
#
# Build:
#   docker build -t stimba/urcap-builder .
#
# Use (from the vnc-urcap/ project root):
#   docker run --rm -v "$PWD":/src -v "$HOME/.m2":/root/.m2 stimba/urcap-builder
#
# The output ends up at ./target/vnc-server-2.0.0.urcap on the host.

FROM maven:3.9.6-eclipse-temurin-8

LABEL maintainer="Andrej Bielik <bielik@stimba.sk>"
LABEL description="STIMBA URCap build image — Java 8 + Maven 3.9 + pre-installed UR URCap API 1.3.0"

# --- 1) Bring in the URCap API JAR ---
# The URCap API is not on Maven Central. Ship it alongside the Dockerfile in
# local-urcap-api/ as:
#     local-urcap-api/api-1.3.0.jar
#     local-urcap-api/api-1.3.0.pom
# You can extract these from the official URCap SDK zip (URCap-x.y.z.zip →
# com/ur/urcap/api/). If you only have the .jar, a minimal .pom is generated
# below.
COPY local-urcap-api/ /tmp/local-urcap-api/

RUN set -eux; \
    cd /tmp/local-urcap-api; \
    if [ -f api-1.3.0.pom ]; then \
        mvn -B install:install-file \
            -Dfile=api-1.3.0.jar \
            -DpomFile=api-1.3.0.pom; \
    else \
        mvn -B install:install-file \
            -Dfile=api-1.3.0.jar \
            -DgroupId=com.ur.urcap \
            -DartifactId=api \
            -Dversion=1.3.0 \
            -Dpackaging=jar \
            -DgeneratePom=true; \
    fi

WORKDIR /src

# Default command = package the URCap
CMD ["mvn", "-B", "clean", "package"]
