# CritiCall (Android)

CritiCall is a multi-role healthcare Android application with dedicated experiences for Patient, Doctor, Pharmacist, and Admin (with optional Lab Technician workflows where enabled). It supports multilingual UI, appointment booking with payments, consultations, role-specific dashboards, realtime operations, and an in-app AI assistant layer including agentic workflows that can guide and execute multi-step flows inside the app (with explicit user confirmation for irreversible actions).

---

## Table of Contents

- Overview
- Roles and Core Features
- Agentic AI (In-App Orchestrator)
- AI Assistant
- Calls and Consultations
- Payments
- Localization
- Backend / API
- Project Structure
- Build and Run
- Configuration
- Data and ML Engineering Notes
- Security Notes
- License

---

## Overview

CritiCall is designed around three product goals:

1. Workflow automation (agentic UX)
   - Reduce steps and time-to-complete for common tasks (booking, follow-ups, record navigation)
2. Prediction and triage support (non-diagnostic)
   - Provide safe, low-latency guidance and structured next steps without inventing facts
3. Monitoring and reliability
   - Maintain stability and observability for critical user journeys

Success is measured via completion rate, drop-off rate, time-to-complete, assistant satisfaction, latency, crash-free sessions, and API error rates.

---

## Roles and Core Features

### Patient
1. Onboarding, login, language selection
2. Profile setup for new accounts
   - enter personal details
   - optionally upload previous records (PDF/images) or skip
3. Home dashboard with quick actions and upcoming appointments
4. Doctor discovery by specialty, doctor list, and doctor details
5. Appointment booking: select specialty → doctor → slot → payment → confirmation
6. Appointment details and consultation start
7. Medical records: prescriptions, vitals, visit history
8. Notifications and profile management
9. AI Assistant access (assistant bar + expandable sheet)
10. Emergency call shortcut

### Doctor
1. Doctor home with upcoming appointments and quick start
2. Consultations list (all/upcoming/completed)
3. Patient records review
4. Prescription creation and save to patient record
5. Notifications and profile management
6. Consultation start flow
   - external call launch (video/audio)
   - return to app triggers handoff to next workflow screen (patient summary or doctor prescription)

### Pharmacist
1. Pharmacy home dashboard
2. Stock overview, low-stock alerts, and requests
3. Add medicine, update stock, and search
4. Notifications and profile management
5. Call/dial actions for requests

### Admin
1. Admin dashboard
2. User verification workflows and approvals
3. Governance workflows (role onboarding, approvals)
4. Admin profile and support

### Lab Technician (optional / module-based)
1. Lab order inbox and tracking (Firebase-enabled flows where configured)
2. Structured lab parameter entry
3. Critical value detection and critical notifications pipeline

---

## Agentic AI (In-App Orchestrator)

CritiCall includes an in-app “agentic” assistant mode that can plan and execute multi-step app workflows through app-owned interfaces. This is not background automation; it operates within the app UI with explicit user confirmations for irreversible actions (e.g., payment initiation).

### What It Can Do
- Appointment booking workflow
  - specialty selection → doctor selection → slot selection → payment handoff → booking confirmation
- Follow-up workflow navigation
  - open appointment details → show next action → guide completion
- Records navigation
  - open medical records → filter prescriptions/vitals/history → summarize or surface next steps
- Role-aware actions
  - constrained to user role permissions (patient/doctor/pharmacist/admin)

### Guardrails
- Always ask for confirmation before committing:
  - payment initiation
  - appointment submission
  - any irreversible write action
- Never invent medical facts or lab values
- Provide safe fallback to guided steps when automation cannot proceed

### Recommended Architecture (Implementation Guidance)
- Intent layer: map user requests into canonical intents (book_appointment, find_doctor, open_records, etc.)
- Planner: create minimal deterministic action plan (navigation + form fills + API calls)
- Executor: executes actions through controlled interfaces (navigation controller, screen contracts, validators)
- Verifier: checks post-conditions (appointment created, payment status)
- Fallback: guided steps if execution fails or context is missing

---

## AI Assistant

The assistant is available to patients and provides:
- short non-diagnostic guidance
- multilingual responses aligned to selected app language
- assistant bar UI that expands into a full assistant sheet
- voice input support
- offline fallback responder for precautionary guidance

### Model and Inference
This project supports on-device and server-assisted inference patterns depending on build configuration:

- On-device inference: TensorFlow Lite (recommended for offline + low latency)
- Server-assisted inference: optional via backend if configured

Design constraints:
- low latency
- concise outputs
- safety constraints enforced by deterministic checks
- strict separation between training data and runtime user data to avoid leakage

---

## Calls and Consultations

1. Video/audio consults open external call links
2. If a meet link is not provided by the API, the app falls back to a Jitsi URL built from `JITSI_BASE_URL` and a room derived from the appointment public code
3. After the call returns, the app shows the next workflow screen (patient summary or doctor prescription)

---

## Payments

Payment flow uses Razorpay in `PaymentActivity`.
- booking flows route through Razorpay checkout
- confirmation updates appointment state and downstream workflows

---

## Localization

The UI supports:
- English
- Hindi
- Tamil
- Telugu
- Kannada
- Malayalam

Language selection is persisted and applied across screens and assistant responses.

---

## Backend / API

The app expects a PHP backend. The base URL is configured in:
- `app/src/main/java/com/simats/criticall/ApiConfig.kt`

Database schema / procedures are in:
- `sehatsethu.sql`

---

## Project Structure

```text
criticall/
├─ app/
│  ├─ src/
│  │  ├─ main/
│  │  │  ├─ AndroidManifest.xml
│  │  │  ├─ java/
│  │  │  │  └─ com/simats/criticall/
│  │  │  │     ├─ assistant/                 # assistant bar, bottom sheet, orchestration hooks
│  │  │  │     ├─ network/                   # API config, clients, request/response utils
│  │  │  │     ├─ utils/                     # translation, preferences, helpers
│  │  │  │     ├─ roles/
│  │  │  │     │  ├─ patient/                # onboarding, booking, records, patient dashboard
│  │  │  │     │  ├─ doctor/                 # consultations, patient records, prescriptions
│  │  │  │     │  ├─ pharmacist/             # inventory, requests, alerts
│  │  │  │     │  └─ admin/                  # approvals, governance
│  │  │  │     └─ (optional) labtech/        # lab inbox, parameter entry, critical alerts
│  │  │  └─ res/
│  │  │     ├─ layout/
│  │  │     ├─ drawable/
│  │  │     └─ values/                       # localized strings (multi-language)
│  └─ build.gradle
├─ criticall.sql
├─ gradle/
├─ build.gradle
├─ settings.gradle
└─ README.md
