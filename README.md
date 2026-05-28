# 🦞 ClawMobile

A native Android client for [OpenClaw](https://github.com/openclaw/openclaw) and [Orchestrator](https://github.com/henrycode03/orchestrator) — monitor and control your AI agent sessions from your phone.

[![Release](https://img.shields.io/github/v/release/henrycode03/clawmobile?style=flat-square&label=release&color=555555)](https://github.com/henrycode03/clawmobile/releases)
[![Downloads](https://img.shields.io/github/downloads/henrycode03/clawmobile/total?style=flat-square&label=downloads&color=4c9be8)](https://github.com/henrycode03/clawmobile/releases)
[![License](https://img.shields.io/github/license/henrycode03/clawmobile?style=flat-square&color=blue)](https://github.com/henrycode03/clawmobile/blob/main/LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%207.0+-00A884?style=flat-square)](https://developer.android.com/about/versions/nougat)

---

## Features

### Chat
- **Real-time streaming** — token-by-token typewriter response via WebSocket
- **Ed25519 authentication** — full device pairing handshake with OpenClaw Gateway 2.13+
- **Agent selector** — switch between Main / Engineer / QA agents on the fly
- **Markdown rendering** — bold, italic, inline code, code blocks, headings, lists
- **Chat history** — local Room database, sortable, per-session
- **Voice input** — Android Speech-to-Text integration

### Orchestrator (session control plane)
- **Session management** — start, pause, resume, and stop Orchestrator sessions from your phone
- **Live log streaming** — WebSocket log tail with phase/error/checkpoint filters; auto-reconnect with exponential backoff
- **Task progress** — per-session task counts (pending / running / done / failed)
- **Checkpoint management** — view, load, and delete session checkpoints
- **Human-in-the-loop interventions** — approve, deny, or provide guidance when the agent pauses for operator input
- **Background intervention polling** — WorkManager job notifies you when any session is paused awaiting operator input
- **Permission approval** — review and approve/reject tool permission requests
- **Replan flow** — when a session fails, view the AI-generated failure summary, add operator feedback, and send it back to Project Architect to seed a new planning session
- **Project & task explorer** — browse projects, task lists, file trees, and task detail

### General
- **Offline banner** — detects network drops; falls back to REST polling silently
- **Dark theme** — consistent slate/navy UI across all screens

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | XML Views + ViewBinding |
| Architecture | MVVM (ViewModel + LiveData + Repository) |
| Local storage | Room Database |
| Networking | OkHttp (WebSocket + REST) |
| Auth | Ed25519 via BouncyCastle |
| Background jobs | WorkManager |
| Build | Gradle KTS + KSP |

## Project Structure

```
app/src/main/java/com/user/
├── data/
│   ├── OrchestratorModels.kt   # All Orchestrator API response models
│   ├── PermissionModels.kt
│   ├── PrefsManager.kt         # Server URL, API key, gateway token, prefs
│   └── ...                     # Room entities, DAOs, database
├── repository/
│   └── OrchestratorRepository.kt
├── service/
│   ├── OrchestratorApiClient.kt    # All Orchestrator REST calls
│   ├── WebSocketManager.kt         # Live log streaming with reconnect
│   ├── InterventionPollService.kt  # WorkManager background poller
│   ├── PermissionPollService.kt
│   ├── GatewayClient.kt
│   ├── Ed25519Manager.kt
│   └── OpenClawService.kt
├── ui/
│   ├── activities/
│   │   ├── MainActivity.kt
│   │   ├── SessionsActivity.kt
│   │   ├── SettingsActivity.kt
│   │   └── OnboardingActivity.kt
│   ├── tasks/
│   │   ├── SessionDetailActivity.kt   # Live logs, interventions, replan
│   │   ├── ProjectDetailActivity.kt
│   │   ├── TaskDetailActivity.kt
│   │   ├── TaskListActivity.kt
│   │   └── CheckpointsBottomSheet.kt
│   ├── permissions/
│   │   └── PermissionsActivity.kt
│   ├── components/
│   │   ├── OfflineBannerView.kt
│   │   └── StatusBadgeView.kt
│   └── FailureSummary.kt, MarkdownRenderer.kt, OutputHighlighter.kt, ...
└── viewmodel/
    ├── SessionViewModel.kt
    ├── TaskViewModel.kt
    └── ...
```

## Architecture

```text
ClawMobile
  │
  ├─── Chat/voice ──────────────────────> OpenClaw Gateway (:18789)
  │                                           └── OpenClaw agent runtime
  │
  └─── Session control / monitoring ──> Orchestrator API (:8080)
           ├── Projects, tasks, sessions
           ├── Live WebSocket log stream
           ├── Human-in-the-loop interventions
           ├── Permission approvals
           └── Replan flow
```

Both connections run through Tailscale or LAN. They are independent — the Gateway token is for chat; the Orchestrator URL + API key are for session control.

## Quick Start

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17+
- Android SDK API 24+ (Android 7.0+)
- OpenClaw running locally or on a server
- Orchestrator running (for session management features)

### Network Setup

**Recommended — Tailscale**
```text
Orchestrator URL: http://<tailscale-ip>:8080
Gateway URL:      http://<tailscale-ip>:18789
```

**SSH tunnel (emulator or different network)**
```bash
ssh -N -L 8080:localhost:8080 -L 18789:localhost:18789 \
    -o ServerAliveInterval=30 USER@YOUR_SERVER_IP
```

**Same WiFi**
```text
Orchestrator URL: http://192.xxx.x.xx:8080
Gateway URL:      http://192.xxx.x.xx:18789
```

### Get Credentials

**Gateway token:**
```bash
cat ~/.openclaw/openclaw.json
# Copy gateway.auth.token
```

**Orchestrator API key:**  
Settings → API Keys in the Orchestrator dashboard.

### Build & Run

```bash
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

Or **Run → Run 'app'** in Android Studio.

### First Launch

1. App opens Settings if no token is set
2. Enter Gateway URL + Token → Save (for chat)
3. Enter Orchestrator URL + API Key → Save (for session control)
4. First Gateway connection shows **Device Pairing Required**:
```bash
openclaw gateway call device.pair.list --json
openclaw gateway call device.pair.approve --params '{"requestId":"<id>"}' --json
```
5. Restart → `● Connected`

## Troubleshooting

| Issue | Fix |
|---|---|
| `● Handshaking…` stuck | Check tunnel; verify gateway token |
| Pairing required on every launch | Use real device (emulator clears data on restart) |
| `Software caused connection abort` | Add `-o ServerAliveInterval=30` to SSH tunnel |
| Orchestrator sessions not loading | Verify Orchestrator URL and API key in Settings |
| Intervention notifications not firing | Check that POST_NOTIFICATIONS permission is granted |
| KSP build errors | Ensure KSP version prefix matches Kotlin version in `libs.versions.toml` |
| `Cannot resolve symbol` | File → Invalidate Caches / Restart |

## Related Projects

| Project | Description |
|---|---|
| [OpenClaw](https://github.com/openclaw/openclaw) | AI Agent framework |
| [orchestrator](https://github.com/henrycode03/orchestrator) | Automates software projects with OpenClaw agents |

## Privacy

- All messages stored locally on device via Room
- No analytics, no cloud sync, no third-party SDKs
- Only outbound connections are to your own OpenClaw and Orchestrator servers

## Star History

Consider giving it a star — it helps others discover the project and keeps us motivated!

<p align="center">
  <a href="https://github.com/henrycode03/clawmobile/stargazers">
    <img src="https://img.shields.io/github/stars/henrycode03/clawmobile?style=for-the-badge&logo=github&color=yellow" alt="GitHub Stars" />
  </a>
</p>

<p align="center">
  <a href="https://star-history.com/#henrycode03/clawmobile&Date">
    <img src="https://api.star-history.com/svg?repos=henrycode03/clawmobile&type=Date" width="600" alt="Star History Chart" />
  </a>
</p>

## License

MIT
