<!-- markdownlint-disable MD033 -->
<div align="center">

# 🚀 ORKA

<img src="branding/source/logo.svg" alt="ORKA logo" width="160" />

**Execution, not intention.**

*An Android execution engine that turns intent into action using exact alarms, context-aware responses, and local-first intelligence.*

![Min SDK](https://img.shields.io/badge/Min%20SDK-31-blue.svg)
![Compile SDK](https://img.shields.io/badge/Compile%20SDK-35-blueviolet.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-orange.svg)
![License](https://img.shields.io/badge/License-Proprietary-red.svg)

</div>
<!-- markdownlint-enable MD033 -->

---

## 🎯 Why ORKA

Most task apps remind you.
ORKA intervenes.

It combines:

- **Natural-language capture** for low-friction input.
- **Deterministic scheduling** with adaptive/RL pathways.
- **Full-screen exact alarms** for execution pressure at the right moment.
- **On-device model workflow** for private, local-first operation.

---

## ✨ Product highlights

### 🚨 Execution-first reminders

ORKA uses `AlarmManager`-driven exact alarms and an action surface designed for momentum:

- `START_TASK`
- `MARK_DONE`
- context-sensitive options (reschedule/snooze/split)
- acknowledgement trail for behavior profiling

### 🧠 Bundled local model (ADB workflow)

For personal installs, the model is packaged from your local `model-kit/` during build.

- No manual model-path typing during onboarding.
- Automatic first-run provisioning into app storage.
- Fallback parsing remains available if model provisioning fails.

### 📱 OEM-aware reliability posture

ORKA includes setup guidance for aggressive OEM power policies (including OnePlus/OxygenOS) to reduce missed reminders in real-world conditions.

---

## 🗺️ System overview

```mermaid
flowchart LR
    User([User]) --> Capture[Capture]
    Capture --> Parser[Task Parser]
    Parser --> Confirm[Draft Confirmation]
    Confirm --> DB[(Room)]
    DB --> Scheduler[Scheduler Orchestrator]
    Scheduler --> Alarm[AlarmManager]
    Alarm --> AlarmUI[Full-screen Alarm UI]
    AlarmUI --> Feedback[Interaction Events]
    Feedback --> Scheduler
```

---

## 🚀 Quick start (ADB-first)

### ✅ Prerequisites

- Android Studio (latest stable)
- JDK 17+
- Android device/emulator on API 31+
- `adb` available in your shell

### 📦 1) Place the model file

Copy your model to:

- `model-kit/gemma-2b-int4.gguf`

> Packaging/install tasks fail fast if this file is missing.

### 🛠️ 2) Build and install

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

### 🎬 3) First launch behavior

- ORKA auto-provisions the bundled model to app-managed storage.
- Onboarding shows model status (no manual path input required).

---

## 🧪 Testing & verification

```bash
# Full JVM test suite
./gradlew test

# App instrumentation tests (requires connected device/emulator)
./gradlew :app:connectedDebugAndroidTest

# Macrobenchmark & baseline profile modules
./gradlew :benchmark:assemble :baselineprofile:assemble
```

---

## 🔋 Device reliability checklist (OnePlus-focused)

For best reminder delivery on OnePlus/OxygenOS:

1. Allow exact alarms.
2. Set battery usage for ORKA to **Unrestricted**.
3. Enable background/auto-launch permissions in OEM security settings.
4. Keep notifications enabled.

---

## 📚 Documentation map

- Product and usage (this file): `README.md`
- Engineering blueprint: [`ARCHITECTURE.md`](./ARCHITECTURE.md)
- Local model setup details: `model-kit/README.md`

---

## 🧾 Status notes

- Distribution target: **personal ADB installs**
- Play Store delivery: **out of scope** for this workflow
- Model source of truth: local gitignored `model-kit/`

---

<!-- markdownlint-disable MD033 -->
<div align="center">

---

**⚡ ORKA: execution over intention.**

</div>
<!-- markdownlint-enable MD033 -->