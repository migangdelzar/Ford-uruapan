FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x gradlew \
    && sed -i 's/\r$//' gradlew \
    && JAVA_TOOL_OPTIONS="-Xmx1g -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8" \
        ./gradlew --version --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=""
COPY src src
RUN find src/main/resources/db/changelog -type f -exec sed -i 's/\r$//' {} +
RUN JAVA_TOOL_OPTIONS="-Xmx1g -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8" \
    ./gradlew bootJar --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=""

FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.source=https://github.com/migangdelzar/Ford-uruapan
WORKDIR /app
# The Temurin Ubuntu base includes Pebble as a generic container service manager.
# This image starts Java directly and does not use Pebble.
RUN rm -f /usr/bin/pebble
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
