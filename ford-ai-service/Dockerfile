FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
