# Heracles for Android

Heracles is a native Android developer and device-automation agent built with **Kotlin** and **Jetpack Compose (Material 3)**.

## Architecture & Key Modules

- **UI & Theming (`ui/`)**: Jetpack Compose Material 3 with 4 themes (`BW`, `Mist`, `Aurora`, `Vitality`) across Light/Dark modes, bundled `Inter` and `JetBrains Mono` typography, adaptive tablet sidebar / phone drawer, Navigation Compose with type-safe routes, and bilingual (`zh` / `en`) localization.
- **Agent Core (`agent/`)**:
  - `HeraclesAgent`: Streaming Think-Act-Observe loop with tool approval gating (`Manual`, `WorkspaceAuto`, `FullAccess`), `Always Allow Tool`, interactive `ask_user`, live plan steps (`write_todos`), queue/interrupt steering, `ContinuePolicy`, and `AgentChatService` foreground keepalive.
  - `ToolRegistry`: File & SAF workspace operations (`list_files`, `read_file`, `write_file`, `edit`, `apply_patch`, `mkdir`, `find_files`, `grep_files`, `delete_path`, `move_path`, `copy_path`, `read_document`), shell routing (`SandboxManager` PRoot Linux rootfs or `DeviceShell` via Stellar/Shizuku), JGit tools, web fetch/search, battery/thermal health (`DeviceHealth`), local notifications, and scoped memory.
  - `AutomationRunner`: Consumes `AutomationScheduler` interval triggers cleanly without duplicate concurrent execution.
- **Data & Integrations (`data/`, `bridge/`)**:
  - Multi-protocol SSE streaming client (`OpenAI Chat`, `Anthropic Messages`, `OpenAI Responses`) and `/models` capability discovery (`LlmClient`).
  - Hardware-backed `KeystoreVault` (AES-256-GCM) for provider keys, GitHub OAuth tokens, and JEV API keys.
  - `SessionStore` JSONL session persistence with fast tail loading and process-killed checkpoint recovery.
  - `GitService` (Eclipse JGit) & `GitHubAuthService` (Device Flow OAuth + repo cloning).
  - `MemoryStore` & `ScopedMemoryService` (`global` with proposal approval + `workspace` + `session`, secret pattern guard).
  - `JevRepository` (cloud `NeoHorse-Jev-4B` calibrated probability decisions, sanitized diagnostics, device RAM/storage profiler, StartLux GGUF asset manager) and `VoiceModelRepository` (16kHz PCM/WAV capture, Android `SpeechRecognizer`, bundled Zipformer assets).

## Build

Requires JDK 17+, Android SDK 36 (`minSdk 31`), and Gradle 9.3.1+:

```bash
gradle :app:assembleDebug
```
