FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates rlwrap \
 && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL -o /tmp/install.sh https://github.com/clojure/brew-install/releases/latest/download/posix-install.sh \
 && chmod +x /tmp/install.sh \
 && /tmp/install.sh
COPY deps.edn build.clj ./
RUN clojure -P -T:build
COPY src ./src
COPY resources ./resources
RUN clojure -T:build uberjar

# Litestream streams the SQLite file to an S3-compatible bucket for
# point-in-time recovery; docker/start.sh only engages it when the
# LITESTREAM_REPLICA_URL secret is present.
FROM litestream/litestream:0.3.13 AS litestream

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/hosted-clay.jar /app/hosted-clay.jar
COPY --from=litestream /usr/local/bin/litestream /usr/local/bin/litestream
COPY env/prod/resources /app/conf
COPY docker/start.sh /app/start.sh
EXPOSE 8080
ENTRYPOINT ["/app/start.sh"]
