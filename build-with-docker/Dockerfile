# STIMBA VNC Server URCap — reproducible build container
#
# Build:
#   docker build -t stimba/urcap-builder .
#
# Use (from the vnc-urcap/ project root):
#   docker run --rm -v "$PWD":/src -v "$HOME/.m2":/root/.m2 stimba/urcap-builder
#
# The output ends up at ./target/vnc-server-3.2.0.urcap on the host.

FROM maven:3.9.6-eclipse-temurin-8

LABEL maintainer="Andrej Bielik <bielik@stimba.sk>"
LABEL description="STIMBA URCap build image — Java 8 + Maven 3.9 + pre-installed UR URCap API 1.18.0"

# --- 1) Bring in the URCap API JAR ---
# The URCap API is not on Maven Central. Ship it alongside the Dockerfile in
# local-urcap-api/ as:
#     local-urcap-api/api-1.18.0.jar
#     local-urcap-api/api-1.18.0.pom
#     local-urcap-api/api-1.18.0-sources.jar   (optional; for IDE source lookup)
# These are extracted from URCap SDK 1.18 (artifacts/api/1.18.0/…). Kept in-tree
# so CI and fresh clones build without SDK scavenging. See local-urcap-api/README.md
# for provenance + rollback path. The folder may also contain legacy api-1.16.0.*
# and api-1.3.0.* artefakty retained for quick bisect — they are NOT installed
# into ~/.m2 by default (only the version referenced below is installed).
#
# Bump rationale 2026-04-20: PS 5.25.1 rejected 3.1.0 (built against 1.16.0)
# with "Tento URCap vyžaduje novšiu verziu API URCap". Moving to 1.18.0 (highest
# API shipped in SDK 1.18, released 2025-09-12) aligns with PS 5.22-5.25 loader.
COPY local-urcap-api/ /tmp/local-urcap-api/

RUN set -eux; \
    cd /tmp/local-urcap-api; \
    if [ -f api-1.18.0.pom ]; then \
        mvn -B install:install-file \
            -Dfile=api-1.18.0.jar \
            -DpomFile=api-1.18.0.pom; \
    else \
        mvn -B install:install-file \
            -Dfile=api-1.18.0.jar \
            -DgroupId=com.ur.urcap \
            -DartifactId=api \
            -Dversion=1.18.0 \
            -Dpackaging=jar \
            -DgeneratePom=true; \
    fi

WORKDIR /src

# Default command = package the URCap
CMD ["mvn", "-B", "clean", "package"]
