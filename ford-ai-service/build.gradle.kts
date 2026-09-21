plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    java
}

group = "com.ford"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.ai.bom))
    implementation(platform(libs.spring.modulith.bom))

    implementation(libs.spring.boot.starter.web)
    developmentOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.liquibase)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.modulith.starter.jdbc)
    runtimeOnly(libs.spring.modulith.starter.insight)
    runtimeOnly(libs.postgresql)

    implementation(libs.spring.ai.rag)
    implementation(libs.spring.ai.vector.store)
    implementation(libs.spring.ai.vector.store.advisor)
    implementation(libs.spring.ai.starter.model.ollama)
    implementation(libs.spring.ai.starter.model.openai)
    implementation(libs.spring.ai.starter.vector.store.pgvector)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.h2)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
        runtimeClasspath += output + compileClasspath
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

tasks.test {
    useJUnitPlatform()
}

val java25NativeAccessArgs = listOf("--enable-native-access=ALL-UNNAMED")

tasks.withType<org.gradle.api.tasks.JavaExec>().configureEach {
    jvmArgs(java25NativeAccessArgs)
}

tasks.withType<Test>().configureEach {
    jvmArgs(java25NativeAccessArgs)
}

val testcontainersSocketOverride = providers.environmentVariable("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE")

tasks.withType<Test>().configureEach {
    testcontainersSocketOverride.orNull?.let { socketOverride ->
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", socketOverride)
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    shouldRunAfter(tasks.test)
    useJUnitPlatform()
}

tasks.check {
    dependsOn(integrationTest)
}
