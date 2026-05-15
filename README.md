# LocalAgent

Native Android shell around **[Hermes Agent](https://hermes-agent.nousresearch.com/)**: onboarding, Compose UI with rich assistant markdown (tables + offline KaTeX), encrypted credentials and provider presets, encrypted PTY-backed terminal (**ConnectBot termlib**), optional **Unix TCP bridge** for structured GUI updates, and **on-device GGUF** inference via **llama.cpp** JNI with an OpenAI-compatible HTTP surface for Hermes in Termux.

**Repository:** [github.com/lukifer23/LocalAgent](https://github.com/lukifer23/LocalAgent)  
**Deep dive:** [`TECHNICAL_OVERVIEW.md`](TECHNICAL_OVERVIEW.md) (bridge protocol, env merge routing, JNI, persistence).  
**Doc index:** [`docs/README.md`](docs/README.md) (contributing, active dev workflow, pointers).

---

## What you get

| Area | Summary |
|------|---------|
| **Onboarding** | Multi-step welcome: Termux expectation, POST notifications + `RUN_COMMAND`, optional cloud keys — stored in DataStore (`AppContainer`). |
| **Hermes tab** | Commit-pinned **`install.sh`** (`bash -s -- --skip-setup --skip-browser`, absolute Termux `$HOME` workdir); **RUN_COMMAND** + queued install / `.env` push; **Stop / disconnect** (close bridge TCP clients, `killall hermes` in Termux); **activity log** (Termux stdout/stderr + bridge diag); `hermes model` shortcut for provider sign-in. |
| **Keys tab** | Encrypted vault; **Sign in with Google (Gemini / OpenAI-compatible base)** when `GOOGLE_OAUTH_CLIENT_ID` is set in `gradle.properties`; OpenAI API keys in browser; custom OIDC; internal OAuth/OIDC state never written to Hermes `.env`. |
| **Settings** | **Wi‑Fi-only bridge / LLM listener** policy (binds bridge only on Wi‑Fi; local HTTP follows same guard); rolling **bridge diagnostics** log (TCP, AUTH, JSON) with copy. |
| **Local model tab** | One-click Qwen path, **warmup benchmark** (latency / ~tok/s); bundled GGUF + parallel download merge **size verify**. |
| Chat | Persists **`BridgeEvent`** stream; **`/help`**, **`/model`**, **`/skills`** handled in-process; **`/model`** routing modes; assistant markdown with **animated** code blocks. |
| **Assistant rendering** | **CommonMark** + GFM tables, fenced code (copy), **KaTeX** offline; code blocks use enter animation. |
| **Local LLM** | **True streaming inference** via llama.cpp JNI; hardware-accelerated on modern ARM (dotprod). |
| **Agent Skills** | **Autonomous capabilities** in Termux: Shell access, File System management, and custom skill bootstrapping. |
| **Downloads** | Unpinned GGUF: resume **`*.part`** with **`Range`**. Cold start ≥ **8 MiB** and range-capable CDN: parallel **2–8** segment fetch, merge-on-disk, fallback to resumable single connection on failure. Pinned SHA: single-stream download + verify (no parallel). |

---

## Build

### On-Device (Termux) - Recommended

LocalAgent is optimized for building directly on your Android device using Termux. This path handles native NDK dependencies and specialized toolchain requirements.

1.  **Setup Environment**: Run the provided setup script to configure the hybrid build environment (requires `proot-distro` for library compatibility).
    ```bash
    curl -sL https://raw.githubusercontent.com/lukifer23/LocalAgent/main/setup_termux_build.sh | bash
    ```
2.  **Build**:
    ```bash
    cd ~/LocalAgent
    ./build.sh
    ```

### Desktop (Android Studio)

Requires **JDK 17** (AGP 9 expects a supported toolchain). Example:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:assembleDebug
```

- **Gradle** 9.4.1, **AGP** 9.2.x, **Kotlin** 2.3.x, **KSP** 2.3.8.  
- Composite **`includeBuild`** of vendored ConnectBot **termlib** (`third_party/termlib`).  
- AppAuth: `manifestPlaceholders["appAuthRedirectScheme"]` → `com.localagent`. Optional: `GOOGLE_OAUTH_CLIENT_ID` in **`gradle.properties`** for Keys-tab Google sign-in (Android OAuth client from Google Cloud).  
- Native inference: **`arm64-v8a`** and **`x86_64`** only (32-bit ARM omitted — upstream NEON expectations).  
- `gradle.properties`: `android.disallowKotlinSourceSets=false` so **Room/KSP** registers under AGP 9’s Kotlin DSL (prefer tightening this when AGP catches up).

First CMake configure fetches **llama.cpp** (git shallow).

**Checks:**

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

### Install on a device (USB debugging)

```bash
./gradlew :app:installDebug
```

Requires a connected device or emulator with **USB debugging** enabled (`adb devices`). Debug signing only — not for production release.

---

## Hermes Agent on Android

Upstream Hermes is not a standalone APK; the supported phone path is **[Termux](https://hermes-agent.nousresearch.com/docs/getting-started/termux)** (Python venv + pip `.[termux]`).

### Hermes tab (in app)

1. Download **`scripts/install.sh`** using the **commit-pinned** URL and SHA in [`app/src/main/assets/hermes-manifest.json`](app/src/main/assets/hermes-manifest.json) (bump **both** when upgrading Hermes).  
2. Run it in Termux via **`RunCommandService`** (`bash -s -- --skip-setup --skip-browser` + script on stdin). Use **`hermes model`** in Termux for interactive provider setup (same idea as desktop).  
3. Use **Push merged .env to Termux** so **`~/.hermes/.env`** matches the sandbox merge (vault + bridge vars). After **Local → one-click** success, this push can start automatically when Termux + permission allow (see Hermes/onboarding docs in UI strings).

Bridge and local LLM HTTP bind **`0.0.0.0`** so Hermes (different UID) reaches the phone’s **LAN IPv4**; **`127.0.0.1` is not shared** across apps.

**Local LLM HTTP** (`LOCALAGENT_LLM_HTTP_PORT`, default **17853**): **`Authorization: Bearer <LOCAL_LLM_HTTP_BEARER>`** — same value merged into `.env`. Supports **`GET /v1/models`**, **`POST /v1/chat/completions`** (optional **`stream: true`** SSE-style chunks).

**Bridge TCP** (`LOCALAGENT_BRIDGE_PORT`, default **17852**): first non-empty line **`AUTH <LOCALAGENT_BRIDGE_TOKEN>`** (hex), then newline-delimited JSON **`BridgeEvent`**. Repeated bad AUTH attempts per IP are rate-limited.

Traffic is **plain TCP on the LAN** — avoid untrusted networks (see in-app Hermes warnings).

---

## Chat slash commands

Composer forwards plain text to Termux `hermes chat` except:

- **`/help`**, **`/model`**, **`/skills`** — handled in-app (meta rows when a session exists).  
- **`/model`** — routing sheet (**DEFAULT / LOCAL_FIRST / VAULT_FIRST**); updates **`HermesEnvWriter`** merge immediately; **`/model status`** — loaded GGUF label, endpoint, routing mode.  
- **`/skills`** — list sandbox **`~/.hermes/skills`**; **`/skills push`** — mirror into Termux; **`/skills disable|enable <file>`** — toggles **`*.disabled`** in sandbox.

---

## Repository layout (high level)

| Path | Role |
|------|------|
| `app/src/main/java/com/localagent/` | Compose UI (`ui/`), **`ChatRepository`**, bridge **`BridgeSocketServer`**, **`LocalLlmService`**, **`ModelDownloadManager`**, **`TermuxRunCommand`**, auth/env. |
| `app/src/main/java/com/localagent/jni/` | llama JNI + CMake. |
| `app/src/main/assets/hermes-manifest.json` | Pinned Hermes installer script metadata. |
| `app/src/main/assets/katex/` | Offline KaTeX JS/CSS/fonts for **`AssistantMarkdown`**. |
| `third_party/termlib/` | Vendored ConnectBot **termlib** (`includeBuild`); shipped as plain files (no nested `.git`) so clones are self-contained. |
| `files/hermes/home/.hermes/` (sandbox) | Synthetic Hermes home; `.env` merge target; skills mirror source. |

---

## Contributing & roadmap

See [`docs/README.md`](docs/README.md) for documentation map, prioritized **gaps**, and **`docs/CONTRIBUTING.md`** for build/test conventions.
