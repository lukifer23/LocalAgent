# Hermes Agent upstream

Source of truth: **https://github.com/NousResearch/hermes-agent**

Android install documentation: **https://hermes-agent.nousresearch.com/docs/getting-started/termux**

LocalAgent does **not** vendor this tree by default. The Android app pins `scripts/install.sh` (URL + SHA-256) in `app/src/main/assets/hermes-manifest.json` and drives installation through **Termux `RUN_COMMAND`** from the in-app **Hermes** tab.

To vendor sources locally for reading or CI:

```bash
git clone --depth 1 https://github.com/NousResearch/hermes-agent.git third_party/hermes-agent
```
