# LocalAgent documentation

Central index for this repository: **[github.com/lukifer23/LocalAgent](https://github.com/lukifer23/LocalAgent)**.  
Implementations evolve quickly — when you ship a behavior change, update **both** [`README.md`](../README.md) (operators / users of the codebase) and the relevant subsection here or in [`TECHNICAL_OVERVIEW.md`](../TECHNICAL_OVERVIEW.md).

---

## Active development workflow

1. **Clone** this repo and open the project in Android Studio (or use Gradle from the CLI).  
2. **JDK 17** + **Android SDK** as in [`CONTRIBUTING.md`](CONTRIBUTING.md).  
3. **Fast loop:** `./gradlew :app:compileDebugKotlin` then `:app:testDebugUnitTest`; on-device: `./gradlew :app:installDebug`.  
4. **Hermes installer pin:** when [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent) changes `scripts/install.sh`, update **`install_script_url`** (prefer a **full commit** raw URL) and **`install_script_sha256`** together in `app/src/main/assets/hermes-manifest.json`.  
5. **Termux smoke:** after changing `TermuxRunCommand` or bridge code, verify **RUN_COMMAND** + one background command on hardware (permissions + `allow-external-apps` in Termux).  
6. **Docs:** follow the maintenance checklist below before merging user-visible behavior.

---

## Document map

| Document | Audience | Contents |
|----------|----------|-----------|
| [`README.md`](../README.md) | Contributors, reviewers, advanced users | Feature matrix (onboarding → bridge → local LLM), build commands, slash commands, repo layout pointers. |
| [`TECHNICAL_OVERVIEW.md`](../TECHNICAL_OVERVIEW.md) | Engineers integrating or extending LocalAgent | Route-level UI map, assistant markdown subsystem, runtime architecture diagram, bridge + HTTP specs, **`OpenAiRoutingStore`** merge table, JNI/tuning notes, **`ModelDownloadManager`** algorithm detail, backlog / risks. |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Contributors | Prerequisites, Gradle tasks, JNI/CMake hints, dependency boundaries. |

---

## Similar projects (positioning)

| Project | Focus | vs LocalAgent |
|---------|--------|----------------|
| **OpenClaw-on-Android** | Termux-first automation server | Overlaps on Termux + local models; LocalAgent emphasizes **Hermes bridge protocol**, encrypted vault, and **in-process** GGUF + OpenAI-compatible HTTP for `LOCAL_FIRST`. |
| **ToolNeuron** | Broad on-device assistant (TTS, RAG, SD) | LocalAgent stays **Hermes-shaped**: Termux CLI, bridge events, skills mirror, no competing “general assistant” scope. |
| **Ollama-in-Termux** | Pull models via Ollama in Termux | LocalAgent uses **pinned GGUF + llama.cpp JNI** inside the APK and merges routing into Hermes `.env`. |

---

## Shipped from roadmap

These were previously “candidate” items and are now implemented in code:

- **Bridge observability**: `BridgeSocketServer.diagnostics` → Settings **Bridge wire log** (small replay buffer + live events), copy-to-clipboard; Hermes tab **activity log** also tails the same flow for operators.
- **Hermes session controls**: disconnect all bridge TCP clients; best-effort **Stop Hermes** via Termux `killall`; full stdout/stderr passthrough in-app; `LocalAgentTermux` tag in **logcat** for deep debugging.
- **Local LLM benchmark**: Local tab **Benchmark warmup** (`LocalLlmService.benchWarmupOnce`).
- **Per-network policy**: DataStore **`bridge_wifi_only`** — bridge binds only on **Wi‑Fi**; `LocalLlmService` HTTP start obeys the same predicate via `lanListenAllowed`.

Remaining candidates: backup/restore of sandbox+vault, launcher widget for `RUN_COMMAND` (policy).

---

## Maintenance checklist (when merging behavior PRs)

1. Bump or confirm [`README.md`](../README.md) **What you get** if user-visible behavior changed.  
2. Update [`TECHNICAL_OVERVIEW.md`](../TECHNICAL_OVERVIEW.md) **Major UI surfaces** or protocol sections if persistence, IPC, or wire format changed.  
3. Extend **Known gaps** only when you consciously defer security/perf work (otherwise remove stale rows).  
4. If Gradle, KSP, or NDK prerequisites shift, mirror in [`CONTRIBUTING.md`](CONTRIBUTING.md).

---

## Relationship to Hermes upstream

Hermes installs and runs inside **Termux** per **[official Termux docs](https://hermes-agent.nousresearch.com/docs/getting-started/termux)**. LocalAgent does not bundle Hermes Python; it coordinates install scripts, `.env`, and tool IPC. When Hermes introduces new **`BridgeEvent`** variants, extend parsing in **`ChatRepository`** and document new types in [`TECHNICAL_OVERVIEW.md`](../TECHNICAL_OVERVIEW.md) § Bridge Protocol.
