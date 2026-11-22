# The runtime image of the backend, for linux/amd64.
#
# It only copies the jar that deploy/scripts/build-images.sh has already built, so building it on
# any machine (an Apple Silicon laptop included) runs no x86 program. The same image runs both
# backend instances, and, with another entry point, the one-shot database migration.
# Build from the repository root:  docker build -f backend/Dockerfile -t geoalert/backend:VERSION .
FROM --platform=linux/amd64 eclipse-temurin:21.0.13.1_2-jre-alpine-3.24

WORKDIR /app
COPY --chown=10001:10001 backend/target/geofence-alerts-backend-*.jar /app/app.jar

# not root; a read-only file system works because the JVM only needs /tmp
USER 10001:10001
EXPOSE 8080

# the heap follows the memory limit of the container; an out-of-memory error ends the process so
# the container is restarted instead of limping
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
