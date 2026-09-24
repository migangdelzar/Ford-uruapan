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
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.liquibase)
    implementation(libs.liquibase.core)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.modulith.starter.jdbc)
    runtimeOnly(libs.postgresql)

    implementation(libs.spring.ai.rag)
    implementation(libs.spring.ai.vector.store)
    implementation(libs.spring.ai.vector.store.advisor)
    implementation(libs.spring.ai.starter.model.ollama)
    implementation(libs.spring.ai.starter.model.openai)
    implementation(libs.spring.ai.starter.vector.store.pgvector)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.boot.webmvc.test)
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
