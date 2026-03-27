<div align="center">

# ⬛ ORKA 

<img src="branding/source/logo.svg" alt="ORKA Logo" width="120" />

**Execution, not intention.** <br>
*An AI-assisted personal execution system for Android, designed to close the gap between knowing what to do and actually doing it.*

[![Android Min SDK](https://img.shields.io/badge/Min%20SDK-31-blue.svg)](#)
[![Compile SDK](https://img.shields.io/badge/Compile%20SDK-35-blueviolet.svg)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-orange.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Proprietary-red.svg)]()

---

</div>

## 📌 What is ORKA?

ORKA is not a passive to-do list. It is an active cognitive agent built entirely for Android, operating on behalf of your future self. Instead of gentle notifications that get lost in the noise, ORKA uses **full-screen exact alarms** to arrest your attention and enforce action at the moment it counts. 

Built first for offline privacy and extreme speed, ORKA locally processes task entry via natural language capabilities to infer deadlines and auto-schedule reminders, driving your execution follow-through intelligently over time.

## ✨ Key Features

- **⚡ Frictionless NLP Capture**: Enter tasks rapidly via natural language. E.g., *"Submit ML assignment by Friday 11:59pm"*.
- **🚨 Full-Screen Exact Alarms**: Doesn't ask politely. Intervenes with unignorable, full-screen AlarmManager-driven execution reminders.
- **🧠 Local-First On-Device AI**: Powered by a locally inferred LLM (Gemma 2B INT4). No cloud connection required for core functional parsing. 
- **⚖️ Dynamic Action Set**: Alarms present context-aware actions based on task urgency, time parameters, and interaction history. No easy "dismiss".
- **🎨 Dark-First Premium UI**: Beautiful, depth-layered UI leveraging Satoshi (language) and JetBrains Mono (data points) to maximize text hierarchy and legibility.

---

## 📸 Product Gallery

<div align="center">
  <table width="100%">
    <tr>
      <td align="center"><em>[Placeholder: Capture Screen]</em><br><img src="docs/assets/placeholder-capture.png" width="200"/></td>
      <td align="center"><em>[Placeholder: Task List]</em><br><img src="docs/assets/placeholder-list.png" width="200"/></td>
      <td align="center"><em>[Placeholder: Alarm Screen]</em><br><img src="docs/assets/placeholder-alarm.png" width="200"/></td>
      <td align="center"><em>[Placeholder: Diagnostics]</em><br><img src="docs/assets/placeholder-diagnostics.png" width="200"/></td>
    </tr>
  </table>
</div>

---

## 🗺️ High-Level System Overview

```mermaid
flowchart LR
    User([👤 User]) -->|Natural Language| Capture[Capture Interface]
    Capture --> AI[🧠 Local AI Parser<br>(Gemma 2B)]
    Capture --> RegEx[Fallback Parser]
    AI -->|Structured Draft| Confirm[Confirmation Sheet]
    RegEx --> Confirm
    Confirm -->|Validated| Database[(Room Database)]
    Database --> Scheduler[⏱️ Smart Scheduler]
    Scheduler --> AlarmManager[🔔 Android AlarmManager]
    AlarmManager --> AlarmUI[🚨 Full-Screen Alarm Interface]
    AlarmUI -->|User Interaction| Logging[📊 Behavior Diagnostics]
    Logging --> Scheduler
```

---

## 🚀 Setup & Installation

ORKA is built as a greenfield Kotlin/Jetpack Compose app targeting internal/sideload-first drops. 

### 1. Requirements
*   **Android Studio**: Ladybug (or latest stable)
*   **JDK**: 17+
*   **Android Target Device or Emulator**: API 31+ (Android 12+) 

### 2. Building the Project
Clone the repository and build via Gradle:
```bash
# Debug Compile & Assembly
./gradlew :app:assembleDebug

# Run directly on attached Android device
./gradlew :app:installDebug
```

### 3. Offline Model Kit Setup (Gemma 2B) 🧠
Because the unquantized/quantized LLM is massive (1GB+), it is **not** bundled directly in the APK. You must side-load the companion asset kit on a fresh install:
1. Obtain the required `gemma-2b-it-gpu-int4.bin` artifact.
2. Follow the onboarding instructions in the app to selectively import the model file from your device's `Downloads` or `Documents` folder. 
3. *Note:* If skipped, ORKA gracefully degrades to an internal fallback heuristic/regex parser automatically with zero crashes.

---

## 🧪 Development & Testing

ORKA utilizes a highly robust, CI-ready testing pipeline based heavily on Fakes, JUnit4, Truth, and Turbine.

```bash
# Run unit tests across all component layers
./gradlew test

# Execute UI instrumentation & detailed end-to-end device flows
./gradlew connectedDebugAndroidTest

# Generate layout baseline profiles for peak app-start performance
./gradlew :benchmark:generateBaselineProfile

# Run system frame-rate benchmarking smoke tests
./gradlew :benchmark:connectedCheck
```

---

## 📖 Deep Technical Reference 

For heavily detailed documentation spanning our software modularity, exact alarm intent architectures, data persistence lifecycle, and scheduling pipeline design, please refer to the dedicated architecture guide:

👉 [**ARCHITECTURE.md**](./ARCHITECTURE.md)

<div align="center">

---

*“Execution, not intention”*

</div>