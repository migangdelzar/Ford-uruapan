plugins {
    alias(libs.plugins.spring.boot)
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
    // Keep these patched platforms until the Spring Boot BOM manages the same versions.
    implementation(enforcedPlatform(libs.spring.framework.bom))
    implementation(enforcedPlatform(libs.jackson2.bom))
    implementation(enforcedPlatform(libs.jackson3.bom))
    implementation(enforcedPlatform(libs.netty.bom))
    implementation(platform(libs.spring.ai.bom))
    implementation(platform(libs.spring.modulith.bom))
    implementation(platform(libs.oci.sdk.bom))
    developmentOnly(platform(libs.spring.boot.bom))

    constraints {
        implementation(libs.tomcat.embed.core) {
            version { strictly(libs.versions.tomcat.get()) }
            because("Spring Boot 4.1.1 manages a Tomcat version with unresolved critical CVEs")
        }
        implementation(libs.tomcat.embed.el) {
            version { strictly(libs.versions.tomcat.get()) }
        }
        implementation(libs.tomcat.embed.websocket) {
            version { strictly(libs.versions.tomcat.get()) }
        }
    }

    implementation(libs.spring.boot.starter.web)
    developmentOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.liquibase)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.oauth2.authorization.server)
    implementation(libs.spring.boot.starter.session.data.redis)
    implementation(libs.spring.security.webauthn)
    implementation(libs.spring.modulith.starter.jdbc)
    runtimeOnly(libs.spring.modulith.starter.insight)
    runtimeOnly(libs.postgresql)

    implementation(libs.spring.ai.rag)
    implementation(libs.spring.ai.vector.store)
    implementation(libs.spring.ai.vector.store.advisor)
    implementation(libs.spring.ai.starter.model.ollama)
    implementation(libs.spring.ai.starter.model.openai)
    implementation(libs.spring.ai.starter.vector.store.pgvector)
    implementation(libs.spring.ai.starter.typesafe)
    implementation(libs.typesafe.spring.ai)
    implementation(libs.langgraph4j.core)
    implementation(libs.langgraph4j.postgres.saver)
    implementation(libs.oci.sdk.secrets)
    implementation(libs.oci.sdk.objectstorage)
    implementation(libs.oci.sdk.httpclient.jersey3)

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
    create("providerPreflight") {
        compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
        runtimeClasspath += output + compileClasspath
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())
configurations["providerPreflightImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["providerPreflightRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

tasks.test {
    useJUnitPlatform()
}

val java25NativeAccessArgs = listOf("--enable-native-access=ALL-UNNAMED")

tasks.withType<org.gradle.api.tasks.JavaExec>().configureEach {
    jvmArgs(java25NativeAccessArgs)
}

tasks.withType<Test>().configureEach {
    jvmArgs(java25NativeAccessArgs)
    environment("FORD_OPERATOR_USERNAME", "")
    environment("FORD_OPERATOR_PASSWORD", "")
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

tasks.register<JavaExec>("importLocalCatalogSnapshot") {
    description = "Imports the approved filtered snapshot into the dedicated local catalog database."
    group = "application"
    dependsOn(tasks.named("integrationTestClasses"))
    classpath = sourceSets["integrationTest"].runtimeClasspath
    mainClass.set("com.ford.service.conversations.LocalCatalogSnapshotImportCli")
}

tasks.register<Test>("providerPreflight") {
    description = "Calls enabled real AI providers with synthetic tool and structured-output checks."
    group = "verification"
    testClassesDirs = sourceSets["providerPreflight"].output.classesDirs
    classpath = sourceSets["providerPreflight"].runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}
