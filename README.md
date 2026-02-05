# CritiCall (Android)

Multi-role healthcare Android app with dedicated experiences for **Patient**, **Doctor**, **Pharmacist**, and **Admin**. Includes multilingual UI, appointment booking with payments, consultation handoff flows, role-specific dashboards, and an in-app assistant layer with guided multi-step workflows (with explicit confirmation for irreversible actions).

<div align="center">

![Platform](https://img.shields.io/badge/platform-Android-informational)
![Build](https://img.shields.io/badge/build-Gradle-informational)
![Language](https://img.shields.io/badge/language-Java%20%7C%20Kotlin-informational)
![Status](https://img.shields.io/badge/status-active-success)
[![License](https://img.shields.io/badge/license-SEE%20LICENSE-lightgrey)](LICENSE)

</div>

> **Assumption:** This repository is a Gradle-based Android Studio project rooted at `app/` and contains a `LICENSE` file at repo root. Update badges if your repo includes CI workflows (e.g., GitHub Actions) or an explicit license type.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Install](#install)
  - [Environment Variables](#environment-variables)
  - [Run / Build / Test](#run--build--test)
- [Configuration](#configuration)
- [Deployment](#deployment)
- [Monitoring & Logging](#monitoring--logging)
- [Security Notes](#security-notes)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

CritiCall is designed around four role-specific experiences:

- **Patient:** onboarding, multilingual UI, doctor discovery, appointment booking, payments, records, and notifications.
- **Doctor:** upcoming consults, patient record review, prescription creation, and post-consult workflows.
- **Pharmacist:** inventory workflows, stock updates, and request handling.
- **Admin:** approval/governance workflows and operational oversight.

The app includes an in-app **assistant UI** (assistant bar + expandable sheet) and an optional **guided workflow mode** that can help users complete multi-step flows (e.g., booking) while requiring explicit confirmation for irreversible actions (like initiating payment).

Success is measured via:
- booking completion rate + time-to-complete,
- crash-free sessions,
- API error rate + latency on critical screens,
- payment success rate and reconciliation correctness.

---

## Features

### Core (Role-based)
- Patient, Doctor, Pharmacist, Admin role flows with dedicated screens and navigation.
- Appointment lifecycle: discovery → slot selection → payment handoff → confirmation → consult launch.
- Role-aware dashboards (upcoming items, quick actions, notifications).

### Patient
- Onboarding + login + language selection.
- Profile setup for new accounts with optional medical record upload (PDF/images) or skip.
- Doctor discovery by specialty and doctor detail views.
- Appointments: booking, details, start consultation, follow-up navigation.
- Medical records: prescriptions, vitals, visit history.
- Notifications and profile management.
- Emergency call shortcut (if present in UI/workflow).

### Doctor
- Consultations list (upcoming/completed).
- Patient record review and summaries.
- Prescription creation and save to patient record.
- Consultation start flow and post-call workflow screen.

### Pharmacist
- Stock overview, low-stock awareness (if present), and updates.
- Add medicine/update stock/search inventory.
- Requests processing and contact/dial actions (if present).

### Admin
- Approval workflows (e.g., doctor/pharmacist verification if implemented).
- Governance and operational workflows tied to role onboarding.

### Integrations
- **Razorpay** payment handoff (via `PaymentActivity`).
- Video/audio consult via external meeting links; fallback to Jitsi using `JITSI_BASE_URL` (where configured).

### Assistant
- Assistant bar UI with expandable assistant sheet.
- Multilingual responses aligned to app-selected language.
- Voice input support (if enabled by permissions/config).

---

## Architecture

```mermaid
flowchart LR
  U[User\nPatient/Doctor/Pharmacist/Admin] --> APP[Android App\nCritiCall]

  subgraph ANDROID[Android App]
    NAV[Role Navigation\nRole modules + guarded routes]
    UI[Role Screens\nPatient/Doctor/Pharmacist/Admin]
    ASST[Assistant UI\nBar + Sheet]
    ORCH[Guided Workflow Mode\nIntent → Plan → Execute → Verify]
    NET[Network Layer\nApiConfig + clients]
    PAY[Payments\nRazorpay activity]
    CALLS[Calls\nExternal link + Jitsi fallback]
    I18N[Localization\nResources + preferences]
  end

  APP --> NAV
  NAV --> UI
  APP --> ASST
  ASST --> ORCH
  UI --> NET
  PAY --> NET
  CALLS --> NET

  NET --> API[Backend API\nPHP (configured)]
  API --> DB[(MySQL)]
  PAY --> RZP[Razorpay]
  CALLS --> VC[Video/Audio Provider\nMeet link or Jitsi]
  DB --- SQL[Schema file\nsehatsethu.sql / criticall.sql]
```

### Component Notes
- **Role modules:** isolate domain UI/workflows per role; reduces cross-role coupling.
- **Network layer:** central place for base URL, clients, and error handling.
- **Payments:** booking flows route through Razorpay checkout then update appointment state.
- **Calls:** open external consult links; build a Jitsi room URL when API does not provide a link.
- **Assistant + workflow mode:** UI + optional orchestrator that guides multi-step flows with guardrails.

---

## Tech Stack

- **Mobile:** Android (Java/Kotlin), Gradle, AndroidX / Material (as used in the project)
- **Backend (expected):** PHP API (base URL configured in app)
- **Database:** MySQL (schema included in repo)
- **Payments:** Razorpay
- **Calls:** external meet link + Jitsi fallback (`JITSI_BASE_URL`)
- **Localization:** Android resources (`res/values-*`) + persisted language preference

---

## Project Structure

```text
.
├─ app/
│  ├─ src/main/
│  │  ├─ AndroidManifest.xml
│  │  ├─ java/com/simats/criticall/
│  │  │  ├─ assistant/              # assistant bar + assistant sheet + workflow hooks (if present)
│  │  │  ├─ network/                # ApiConfig, clients, request/response utils
│  │  │  ├─ roles/
│  │  │  │  ├─ patient/             # onboarding, booking, records, patient dashboard
│  │  │  │  ├─ doctor/              # consultations, patient review, prescriptions
│  │  │  │  ├─ pharmacist/          # inventory, requests
│  │  │  │  └─ admin/               # approvals/governance
│  │  │  └─ utils/                  # translation, preferences, helpers
│  │  └─ res/
│  │     ├─ layout/
│  │     ├─ drawable/
│  │     └─ values*/                # localized strings
│  └─ build.gradle
├─ build.gradle
├─ settings.gradle
├─ gradle/
├─ sehatsethu.sql                   # DB schema (repo-provided)
└─ README.md
```

> **Assumption:** Package name is `com.simats.criticall` and role modules exist under `roles/`. Adjust paths to match your repo.

---

## Getting Started

### Prerequisites

- **Android Studio** (recent stable)
- **JDK 17** (recommended for modern Android Gradle Plugin)
- **Android SDK** with an emulator or physical device
- Optional: access to the backend API and Razorpay test configuration if you want end-to-end booking + payment

> If your repo pins Gradle/AGP versions, follow what’s in `gradle-wrapper.properties` and top-level `build.gradle`.

### Install

```bash
git clone <REPO_URL>
cd criticall
```

Open in Android Studio:
- **File → Open…** → select the repo root
- Let **Gradle Sync** finish

### Environment Variables

If the repo contains an `.env.example`, mirror it here. Otherwise, these keys are inferred from typical usage in this project.

| Variable | Required | Used by | Example | Notes |
|---|:---:|---|---|---|
| `API_BASE_URL` | ✅ | Network (`ApiConfig.kt`) | `https://api.example.com/` | Base URL for the PHP backend. |
| `JITSI_BASE_URL` | ⛔ | Calls fallback | `https://meet.jit.si/` | Used only if API doesn’t return a meeting link. |
| `RAZORPAY_KEY_ID` | ✅* | Payments | `rzp_test_...` | Publishable key ID for Razorpay checkout. |
| `RAZORPAY_KEY_SECRET` | ✅* (server) | Backend | *(server-side)* | **Do not** put secrets in the Android app. |

\* Required only if you run booking flows that trigger payments.

#### Recommended local setup (no secrets committed)

Use `local.properties` (not committed) and map to `BuildConfig`:

`local.properties`:
```properties
API_BASE_URL=https://api.example.com/
JITSI_BASE_URL=https://meet.jit.si/
RAZORPAY_KEY_ID=rzp_test_xxxxx
```

`app/build.gradle` (example):
```gradle
android {
  defaultConfig {
    def apiBaseUrl = project.properties["API_BASE_URL"] ?: ""
    def jitsiBaseUrl = project.properties["JITSI_BASE_URL"] ?: ""
    def razorpayKeyId = project.properties["RAZORPAY_KEY_ID"] ?: ""

    buildConfigField "String", "API_BASE_URL", "\"${apiBaseUrl}\""
    buildConfigField "String", "JITSI_BASE_URL", "\"${jitsiBaseUrl}\""
    buildConfigField "String", "RAZORPAY_KEY_ID", "\"${razorpayKeyId}\""
  }
}
```

Then reference `BuildConfig.API_BASE_URL` / `BuildConfig.JITSI_BASE_URL` / `BuildConfig.RAZORPAY_KEY_ID` from code.

---

### Run / Build / Test

#### Run (Android Studio)
- Select a device/emulator
- Run the **app** configuration

#### Build (CLI)

Debug APK:
```bash
./gradlew :app:assembleDebug
```

Install to a connected device:
```bash
./gradlew :app:installDebug
```

Release APK:
```bash
./gradlew :app:assembleRelease
```

Release AAB (recommended for Play Store):
```bash
./gradlew :app:bundleRelease
```

Tests (if present):
```bash
./gradlew test
```

Instrumentation tests (if configured):
```bash
./gradlew connectedAndroidTest
```

> Tip: run `./gradlew tasks` to see exactly what this repo exposes.

---

## Configuration

Common configuration touchpoints:

- `app/build.gradle`
  - dependencies, build types (debug/release), BuildConfig fields, signing.
- `gradle/wrapper/gradle-wrapper.properties`
  - Gradle version pinning.
- `app/src/main/AndroidManifest.xml`
  - permissions (internet, audio, etc.), activity declarations, deep links.
- `app/src/main/res/values*/`
  - localized strings and theme resources.

Backend integration:
- Base URL configured in `app/src/main/java/.../network/ApiConfig.kt` (or equivalent).
- Schema file at repo root: `sehatsethu.sql` (and/or `criticall.sql`).

---

## Deployment

### Android release artifacts

```bash
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

### Environment separation

- Use different API endpoints per environment (dev/staging/prod) via BuildConfig fields.
- Keep secrets server-side:
  - Razorpay secret key must be used only on the backend for order creation and signature verification.
- Never commit:
  - keystores, signing passwords, production keys, `.jks` files, `local.properties`.

---

## Monitoring & Logging

- Local debugging:
  - Use **Logcat** filtered by `com.simats.criticall`.
- Production recommendations (only if present / enabled in repo):
  - crash reporting and performance telemetry
  - structured logging with PII redaction
  - release build gating (telemetry enabled in release, optional in debug)

> If this repo includes telemetry SDK config, document the exact setup here (service name, env, where dashboards live).

---

## Security Notes

- **RBAC:** role enforcement must be server-side. Client-side checks are UX, not security.
- **Secrets:** do not store any secret keys in the Android app (especially payment secrets).
- **Transport:** use HTTPS for all API calls.
- **PII:** avoid logging patient identifiers, medical details, or tokens.
- **File uploads:** validate file type/size on backend; store with access controls.

---

## Troubleshooting

1) **Gradle sync fails (JDK mismatch)**
- In Android Studio: Settings → Build Tools → Gradle → set **Gradle JDK = 17**.
- Verify:
```bash
./gradlew -version
```

2) **Network calls failing / 404 / timeout**
- Confirm `API_BASE_URL` is set and reachable:
```bash
curl -I https://api.example.com/
```

3) **Cleartext HTTP blocked**
- Use HTTPS. For local dev only, configure a Network Security Config and keep it out of release.

4) **Payment flow errors**
- Ensure `RAZORPAY_KEY_ID` is configured.
- Ensure backend creates Razorpay orders and verifies signatures (server-side).
- Update WebView/Play Services on emulator/device.

5) **Consult link not opening**
- Validate the meet link returned by the API.
- If using fallback, ensure `JITSI_BASE_URL` is set and room names are URL-safe.

6) **Build succeeds, install fails**
```bash
adb devices
adb uninstall com.simats.criticall
./gradlew :app:installDebug
```

7) **Manifest merge conflicts**
```bash
./gradlew :app:processDebugManifest --stacktrace
```

8) **Missing resources / duplicate classes**
- Clean + rebuild:
```bash
./gradlew clean
./gradlew :app:assembleDebug
```

---

## Contributing

1) Fork the repo and create a branch:
```bash
git checkout -b feat/<short-name>
```

2) Keep PRs small and reviewable.
- Include screenshots for UI changes.
- Describe how to validate the change.

3) Run checks locally:
```bash
./gradlew test
./gradlew :app:assembleDebug
```

Code style guidelines:
- Keep role logic isolated under `roles/`.
- Centralize API calls in the network layer.
- Avoid hard-coded secrets and environment URLs.

---

## License

See [LICENSE](LICENSE).
