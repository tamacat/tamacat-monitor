# syntax=docker/dockerfile:1
#
# tamacat-monitor image = tamacat-httpd 1.7 image + monitor.war + configuration.
#
# Base: the published tamacat-httpd 1.7 image (distroless, jlink-reduced Java 25, UID 65532),
# or one built from https://github.com/tamacat/tamacat-httpd/tree/v1.7 (docker/tamacat).
# See docker/README.md.
#
# Build context: repository root (needs tamacat-monitor-admin/target/monitor.war; run `mvn package` first).
ARG HTTPD_IMAGE=docker.io/tamacat/tamacat-httpd:1.7
FROM ${HTTPD_IMAGE}

COPY --chown=65532:65532 docker/monitor/conf/server.properties /app/conf/server.properties
COPY --chown=65532:65532 docker/monitor/conf/url-config.xml /app/conf/url-config.xml
COPY --chown=65532:65532 docker/monitor/conf/tomcat/components.xml /app/conf/tomcat/components.xml
COPY --chown=65532:65532 docker/monitor/conf/tamacat-logging.properties /app/conf/tamacat-logging.properties
COPY --chown=65532:65532 tamacat-monitor-admin/target/monitor.war /app/webapps/monitor.war

# Publish as <host>:8080:8080 (see server.properties)
EXPOSE 8080
