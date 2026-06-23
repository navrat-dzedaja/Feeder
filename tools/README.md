# Build scripts

PowerShell helpers to build a sideloadable Feeder APK for a phone (e.g. Pixel 10).

| Flavor | Summarization providers |
|--------|-------------------------|
| **fdroid** | OpenAI-compatible API + on-device **llama.cpp** (fully FOSS, no Google services) |
| **play** | OpenAI-compatible API + on-device **llama.cpp** + **ML Kit GenAI** (Gemini Nano) |

The two builds use different application ids, so both can be installed side by side.

## Usage

From the repo root, in PowerShell:

```powershell
# Build only (prints the APK path)
.\tools\build-fdroid.ps1
.\tools\build-play.ps1

# Build and install onto the connected device (USB or wireless debugging on)
.\tools\build-play.ps1 -Install
```

Both build the **debug** variant, which is auto-signed with the debug keystore and installs
without any extra setup. The first run compiles llama.cpp from source and is slow (~5 min);
later runs are incremental.

## Requirements

- Android SDK with **NDK 27.2.12479018** and **CMake 3.22.1** (used to build the native llama.cpp lib).
- The `llama.cpp` git submodule. The scripts run `git submodule update --init` automatically if it's missing.
- For `-Install`: `adb` on PATH (or under `%LOCALAPPDATA%\Android\Sdk\platform-tools`) and the phone reachable.

## Using the on-device llama.cpp model

After installing: Settings → AI summary provider → **On-device · llama.cpp** → *Select model file*,
then pick a `.gguf` model. Small quantized instruct models work best on a phone
(e.g. Llama-3.2-1B/3B-Instruct Q4, Qwen2.5-1.5B-Instruct Q4, Gemma-2-2B-it Q4).
