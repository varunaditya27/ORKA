# ORKA Architecture

Deep engineering blueprint for ORKA's modular Android stack, execution runtime, and reliability pipeline.

---

## Table of contents

1. [Module graph](#1-module-graph)
2. [Data and state foundations](#2-data-and-state-foundations)
3. [Bundled model pipeline (ADB-first)](#3-bundled-model-pipeline-adb-first)
4. [Parsing and draft generation](#4-parsing-and-draft-generation)
5. [Scheduling stack](#5-scheduling-stack)
6. [Alarm delivery and action surface](#6-alarm-delivery-and-action-surface)
7. [Diagnostics and OEM reliability](#7-diagnostics-and-oem-reliability)
8. [Testing strategy](#8-testing-strategy)
9. [Device matrix and operational posture](#9-device-matrix-and-operational-posture)

---

## 1. Module graph

ORKA is a strict multi-module Gradle project with downward dependency flow and UDF-oriented boundaries.

```mermaid
flowchart TB
  subgraph APP[App shell]
    A[app]
  end

  subgraph FEATURE[Feature modules]
    F1[onboarding]
    F2[capture]
    F3[tasks]
    F4[taskdetail]
    F5[archive]
    F6[settings]
    F7[diagnostics]
    F8[alarm]
  end

  subgraph DATA[Data engines]
    D1[parser]
    D2[scheduler]
    D3[behavior]
    D4[execution]
    D5[rl]
  end

  subgraph CORE[Core platform]
    C1[common]
    C2[model]
    C3[database]
    C4[designsystem]
    C5[testing]
  end

  A --> FEATURE
  FEATURE --> DATA
  DATA --> CORE
  FEATURE --> C4
```

### Architectural invariants

- UI speaks through `ViewModel` + `StateFlow`.
- Feature modules do not reach across peer feature internals.
- Domain contracts live in `core:model`.
- Infra details remain in data/core modules.

---

## 2. Data and state foundations

### Room persistence

- Task, reminder, and interaction records persist in `core:database`.
- Schedulers and alarms are built around stable task/reminder identity.

### DataStore preferences

- `UserSettings` persists onboarding completion, scheduler toggles, and theme state.
- Model metadata surfaces through `ModelInstallState` from `ModelInstaller`.

### Runtime state model

- Parsing state: `ParseMode` (`GEMMA`, `FALLBACK`, `ERROR`)
- Diagnostics state: capability + model + scheduler readiness
- Scheduling state: rule/adaptive/RL mode selection

---

## 3. Bundled model pipeline (ADB-first)

This repository targets **personal ADB installs**, not Play distribution.

### Build-time packaging

- Source of truth: local gitignored `model-kit/gemma-2b-int4.gguf`
- App build copies model into generated assets under `model/`
- Packaging/install tasks fail fast when model file is missing
- `.gguf` is marked uncompressed in packaging config

### First-run provisioning

- `CompanionKitModelInstaller` checks app assets for bundled model
- If found, it copies to app-managed storage: `files/model/gemma-2b-int4.gguf`
- `ModelInstallState` transitions through `IMPORTING` → `READY` (or `FAILED`)
- Onboarding/settings expose status + retry, without manual path entry

### Backup stance

- Runtime model copy (`files/model`) remains excluded from backup and transfer rules
- Prevents oversized backup payloads and restore inconsistencies

---

## 4. Parsing and draft generation

`DefaultTaskParser` provides deterministic draft extraction and confidence hints.

```mermaid
sequenceDiagram
  participant U as User Input
  participant P as TaskParser
  participant V as Draft Validator
  participant C as Capture UI

  U->>P: Raw natural-language task
  P->>P: Infer deadline/category/effort
  P-->>C: TaskDraft + ParseMode
  C->>V: Validate before confirm
  V-->>C: ValidationResult
```

### Parse-mode semantics

- `GEMMA`: model file is present and non-empty in app model storage
- `FALLBACK`: parser runs deterministic heuristics without model presence

> Current implementation focuses on deterministic reliability while keeping model-provisioning infrastructure production-ready.

---

## 5. Scheduling stack

ORKA orchestrates reminders through layered policies:

1. **Rule-based baseline**
   - deterministic schedule from deadline/effort context
2. **Adaptive mode**
   - behavior-profile adjustments when enough interaction signal exists
3. **RL mode**
   - enabled when trainer readiness reaches `Ready`

`SchedulerOrchestrator` chooses mode and persists resulting reminder timelines.

---

## 6. Alarm delivery and action surface

### Delivery path

- Exact alarms are registered via platform alarm services.
- Alarm UI is full-screen and action-driven (not passive notification-only).

### Action composition

Action surface is deterministic and bounded:

- start
- completion
- acknowledgement
- context action (snooze/reschedule/split)

This keeps runtime behavior predictable and testable under high urgency scenarios.

---

## 7. Diagnostics and OEM reliability

`DiagnosticsRepository` aggregates:

- exact alarm permission readiness
- notification availability
- battery optimization posture
- OEM action requirement
- model availability
- RL readiness and active scheduler mode

### OEM detection posture

OEM action-required handling includes:

- Xiaomi
- Realme
- OPPO
- OnePlus / OPlus

This powers onboarding and diagnostics guidance for aggressive process management environments.

---

## 8. Testing strategy

### Unit level

- JUnit4 + Truth + coroutines-test
- fake-first contracts via `core:testing`
- deterministic time/control with dispatcher rules

### Integration/instrumentation level

- Parser/installer behavior in android tests
- app flow coverage in compose instrumentation suites
- room DAO validation in `core:database` android tests

### Performance gates

- baseline profile module
- macrobenchmark module

---

## 9. Device matrix and operational posture

Given OEM power-management variance, ORKA validates behavior against mixed vendor policies.

| Vendor family | Risk profile | Operational expectation |
| --- | --- | --- |
| Pixel (AOSP-like) | Low | Standard exact-alarm + notification setup |
| Samsung (One UI) | Medium | Additional battery/deep-sleep allowlisting |
| OnePlus/OPlus (OxygenOS) | Medium-High | Background/auto-launch + unrestricted battery |
| Xiaomi/MIUI | High | OEM autostart/background allowances required |
| OPPO/Realme (ColorOS) | High | Aggressive kill mitigation via OEM settings |

---

Engineered for deterministic execution under real-world Android constraints.