# Termux Shell

This skill allows you to execute arbitrary bash commands within the Termux environment.

## Instructions
- You can run system utilities, manage packages with `pkg`, or start/stop background services.
- Always be careful with destructive commands like `rm -rf`.
- Prefer absolute paths when possible.

## Available Resources
- `bash -c "[command]"`: Run any bash command.
- `pkg list-installed`: See what's installed.
- `top -n 1`: Check system usage.
- `uptime`: See how long the device has been running.
