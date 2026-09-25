# Gradle Daemon Resource Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Configure reusable Gradle daemons with bounded CPU and memory usage for `ford-ai-service` and the workstation-wide Gradle user home.

**Architecture:** Keep the policy in Gradle’s native `gradle.properties` mechanism. Add a project-level file for reproducible project behavior and merge the same five properties into the existing user-level file without changing unrelated entries or build scripts.

**Tech Stack:** Gradle 9.4.1 Wrapper, `gradle.properties`, macOS Gradle User Home.

## Global Constraints

- `org.gradle.daemon=true`
- `org.gradle.workers.max=2`
- `org.gradle.parallel=false`
- `org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8`
- `org.gradle.caching=true`
- Preserve unrelated working-tree changes and existing global properties, including sensitive values.
- Do not modify `build.gradle.kts`, application code, tests, or CI configuration.

---

### Task 1: Add the project and global Gradle resource policy

**Files:**
- Create: `ford-ai-service/gradle.properties`
- Modify: `/Users/miguelangeldelgadillozarate/.gradle/gradle.properties` by appending the five settings if each key is absent, or replacing only an existing line for the same key.

**Interfaces:**
- Consumes: Existing project and global Gradle property files, if present.
- Produces: Both property files expose the same five `org.gradle.*` settings.

- [x] **Step 1: Verify the target files and existing keys before editing**

Run from the repository root:

```bash
test ! -e ford-ai-service/gradle.properties || sed -n '1,160p' ford-ai-service/gradle.properties
test -f /Users/miguelangeldelgadillozarate/.gradle/gradle.properties
for key in org.gradle.daemon org.gradle.workers.max org.gradle.parallel org.gradle.jvmargs org.gradle.caching; do
  count=$(grep -Ec "^[[:space:]]*${key}[[:space:]]*=" /Users/miguelangeldelgadillozarate/.gradle/gradle.properties || true)
  test "$count" -le 1
done
```

Expected: The project file is absent or readable; the global file exists; each target key appears zero or one time globally.

- [x] **Step 2: Create the project-level property file**

Create `ford-ai-service/gradle.properties` with exactly:

```properties
org.gradle.daemon=true
org.gradle.workers.max=2
org.gradle.parallel=false
org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8
org.gradle.caching=true
```

- [x] **Step 3: Merge the same settings into the global property file**

Preserve every existing global line and update only these keys to the exact values below:

```properties
org.gradle.daemon=true
org.gradle.workers.max=2
org.gradle.parallel=false
org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8
org.gradle.caching=true
```

If a key is absent, append it once. Do not print the global file because it contains sensitive unrelated properties.

- [x] **Step 4: Verify both files without exposing global secrets**

Run:

```bash
expected='org.gradle.daemon=true
org.gradle.workers.max=2
org.gradle.parallel=false
org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8
org.gradle.caching=true'
test "$(sed -n '1,5p' ford-ai-service/gradle.properties)" = "$expected"
for key in org.gradle.daemon org.gradle.workers.max org.gradle.parallel org.gradle.jvmargs org.gradle.caching; do
  grep -Eq "^[[:space:]]*${key}[[:space:]]*=" /Users/miguelangeldelgadillozarate/.gradle/gradle.properties
done
```

Expected: The project file matches exactly and all five global keys exist.

- [x] **Step 5: Verify Gradle’s effective configuration**

Run from `ford-ai-service`:

```bash
./gradlew --version
./gradlew help --no-scan
./gradlew --status
```

Expected: Gradle starts successfully, `help` completes, and daemon status is reported. If the wrapper distribution is blocked by the sandbox, rerun the command with the required filesystem approval and record that limitation.

- [x] **Step 6: Review the final diff and status**

Run:

```bash
git diff --check
git diff -- ford-ai-service/gradle.properties
git status --short
```

Expected: Only the new project property file and intended design/plan documentation are attributable to this task; pre-existing unrelated modifications remain untouched.
