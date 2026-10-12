# eidas-validator container image
#
# Build:  docker build -t eidas-validator .
# Run:    docker run --rm -p 127.0.0.1:8765:8765 -v eidas-tl-cache:/data/tl-cache eidas-validator
# CLI:    docker run --rm -v "$PWD:/work:ro" eidas-validator /work/signed.pdf --no-trusted-lists
#
# The web interface listens on 0.0.0.0 inside the container and is reached through the port
# mapping; publish it on 127.0.0.1 (as above) unless you put a reverse proxy with
# authentication in front of it. It has no authentication of its own.

# ---- build stage ------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
# Dependencies first, so source edits do not invalidate the download layer.
COPY pom.xml .
RUN mvn -B --no-transfer-progress -q dependency:go-offline
COPY src ./src
# Tests run in CI before an image is built; here we only package.
RUN mvn -B --no-transfer-progress -q -DskipTests package

# ---- runtime stage ----------------------------------------------------------------------
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="eidas-validator" \
      org.opencontainers.image.description="Plain-language validator for qualified electronic signatures (eIDAS) in PDF files, built on EU DSS." \
      org.opencontainers.image.source="https://github.com/juraj-jumic/eidas-validator" \
      org.opencontainers.image.licenses="MIT"

RUN useradd --system --uid 10001 --create-home --home-dir /home/validator validator \
    && mkdir -p /app /data/tl-cache \
    && chown -R validator:validator /data

COPY --from=build /src/target/eidas-validator.jar /app/eidas-validator.jar
COPY --from=build /src/target/lib /app/lib
COPY LICENSE NOTICE /app/

USER validator
WORKDIR /home/validator
VOLUME ["/data/tl-cache"]
EXPOSE 8765

# Loopback inside the container is not reachable from outside; bind to all container
# interfaces and let the port mapping decide who can connect.
ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp -XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/eidas-validator.jar"]
CMD ["serve", "--bind", "0.0.0.0", "--port", "8765", "--cache-dir", "/data/tl-cache"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8765 && printf "GET /healthz HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && head -1 <&3 | grep -q " 200 "'
