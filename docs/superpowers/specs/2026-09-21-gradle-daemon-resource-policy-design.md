# Gradle Daemon Resource Policy

## Goal

Make Gradle reuse compatible daemons across repeated builds while limiting
background CPU and memory consumption for this project and for other Gradle
projects on the workstation.

## Scope

- Add project-level defaults in `ford-ai-service/gradle.properties`.
- Add the same defaults to the user-level
  `/Users/miguelangeldelgadillozarate/.gradle/gradle.properties`.
- Preserve existing project files, unrelated working-tree changes, and all
  existing user-level properties.

## Configuration

Both property files will contain the following Gradle settings:

```properties
org.gradle.daemon=true
org.gradle.workers.max=2
org.gradle.parallel=false
org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8
org.gradle.caching=true
```

The daemon setting enables reuse. Matching JVM arguments and Java/Gradle
versions allow Gradle to connect to an existing compatible daemon instead of
starting another one. The worker cap and disabled project parallelism reduce
concurrent CPU and memory pressure. The build cache reduces repeated work.

## Verification

Verify that:

1. The project property file contains the intended values.
2. The global property file retains its existing entries and contains the
   intended values exactly once.
3. Gradle can report the effective configuration and run a lightweight task,
   subject to the wrapper distribution being available in the sandbox.

No build script or application behavior changes are required.
