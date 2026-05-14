# Contributing to LocalAgent

## Prerequisites

- **JDK 17** (set `JAVA_HOME` before invoking Gradle — Apple Silicon example: `/usr/libexec/java_home -v 17`).
- **Android SDK** matching the **`compileSdk`** in `app/build.gradle.kts` (install via Android Studio or `sdkmanager`).
- Optional: device or emulator with Termux (**GitHub build**, not Play) for **`RUN_COMMAND`** integration tests (`termux.RUN_COMMAND` permission flow is device-dependent).

Native builds fetch **llama.cpp** via CMake `FetchContent` on first configure; ensure network available for initial clone.

Optional **Google sign-in (Gemini)**: set **`GOOGLE_OAUTH_CLIENT_ID`** in **`gradle.properties`** (Android OAuth client ID from Google Cloud Console). Rebuild so `BuildConfig` picks it up.

---

## Common Gradle tasks

```bash
./gradlew :app:assembleDebug             # Debug APK (outputs under app/build/outputs/apk/debug/)
./gradlew :app:installDebug              # Build and install on connected adb device/emulator
./gradlew :app:compileDebugKotlin        # Fast compile-only check
./gradlew :app:testDebugUnitTest         # JVM unit tests
./gradlew :app:connectedDebugAndroidTest # Requires device/emulator
```

On-device investigation: **`adb logcat -s LocalAgentTermux:I`** captures Termux `RUN_COMMAND` result lines (stdout/stderr excerpts) emitted by `TermuxRunResultReceiver`.

Run **`compileDebugKotlin`** before opening a PR; add or extend unit tests under `app/src/test/` when touching pure Kotlin (parsers, merge logic).

**Hermes `install.sh` pin:** never point `hermes-manifest.json` at a floating branch without updating the SHA in the same commit. Prefer a **commit-scoped** `raw.githubusercontent.com/.../<sha>/scripts/install.sh` URL so CI and devices agree on bytes.

---

## Code organization

| Package / area | Guideline |
|----------------|-----------|
| `com.localagent.ui` | Compose routes; prefer thin composables delegating to view models where state grows heavy. |
| `com.localagent.bridge` | Wire protocol parsing and socket lifecycle — avoid Android UI imports in core parsers. |
| `com.localagent.llm` | JNI-free Kotlin for HTTP server + download orchestration (`ModelDownloadManager`); native calls only through JNI façade. |
| `com.localagent.termux` | Isolate **`RUN_COMMAND`** intent builders and PendingIntent bookkeeping. |

Hermes-facing strings belong in **`res/values/strings.xml`** — no hard-coded user-visible English in Compose except debug-only.

---

## Native (JNI)

- CMake entry: `app/src/main/cpp/CMakeLists.txt`. Edit JNI **paired** Kotlin through `com.localagent.llm.LlamaNative` when renaming native symbols or changing load/unload/signatures.  
- After changing **`llama.cpp` revision** or compile flags, run a full `:app:assembleDebug` locally; ABI splits are **`arm64-v8a`** and **`x86_64`**.

---

## Documentation expectations

Functional changes touch:

1. **[`README.md`](../README.md)** — capabilities table / operator notes.  
2. **[`TECHNICAL_OVERVIEW.md`](../TECHNICAL_OVERVIEW.md)** — protocol, merge rules, subsystem detail.  
3. **[`docs/README.md`](README.md)** — only if roadmap or checklist items change materially.

Secrets, bearer tokens, and bridge AUTH values must never be committed; manifests use placeholders.

---

## PR hygiene

- Keep diffs scoped; avoid unrelated reformatting.  
- If adding new permissions or exported components, mirror justification in **`README`** security section and ensure manifest review.  
- For Termux-visible behavior changes, smoke-test **`pushHermesDotEnvStdin`** and one **`RUN_COMMAND`** path on hardware when possible.
