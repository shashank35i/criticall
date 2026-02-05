#  (criticall) — Android App

Criticall is a multi‑role healthcare app with patient, doctor, pharmacist, and admin experiences. The app supports multilingual UI, booking and payments, AI assistance, and role‑specific dashboards and workflows.

This README summarizes what the app does and how to run it locally.

## Roles And Core Features

### Patient
1. Onboarding, login, language selection.
2. Home dashboard with quick actions and upcoming appointments.
3. Doctor discovery by specialty, doctor list, and doctor details.
4. Appointment booking: select specialty → doctor → slot → payment → confirmation.
5. Appointment details and consultation start.
6. Medical records: prescriptions, vitals, and history.
7. Notifications and profile management.
8. AI Assistant:
   - Voice input and short responses.
   - Language‑aware UI and responses (English, Hindi, Tamil, Telugu, Kannada, Malayalam).
9. Emergency call shortcut.

### Doctor
1. Doctor home with upcoming appointments and quick start.
2. Consultations list (all/upcoming/completed).
3. Patient records and prescription creation.
4. Notifications and profile management.
5. Consultation start flow:
   - External call launch (video/audio) and automatic handoff to next workflow (prescription screen).

### Pharmacist
1. Pharmacy home dashboard.
2. Stock overview, low‑stock alerts, and requests.
3. Add medicine, update stock, and search.
4. Notifications and profile management.**
5. Call/dial actions for requests.

### Admin
1. Admin dashboard.
2. User verification workflows and approvals.
3. Admin profile and support.

## AI Assistant**
The AI Assistant is available to patients and provides:
1. Short medical guidance (non‑diagnostic).
2. Multilingual responses aligned to the selected app language.
3. A mini assistant bar that can be expanded into the full assistant sheet.

The assistant uses the existing `LabClient` integration and translation utilities in the app.

## Calls And Consultations
1. Video/audio consults open external call links.
2. If a meet link is not provided by the API, the app falls back to a Jitsi URL built from `JITSI_BASE_URL` and a room derived from the appointment public code.
3. After the call returns, the app shows the next workflow screen (patient summary or doctor prescription).

## Payments
Payment flow uses Razorpay in `PaymentActivity`.

## Localization
The UI supports:
1. English
2. Hindi
3. Tamil
4. Telugu
5. Kannada
6. Malayalam

Language selection is persisted and applied across screens.

## Backend / API
The app expects a PHP backend. The base URL is configured in:
1. `app/src/main/java/com/simats/criticall/ApiConfig.kt`

Database schema / procedures are in:
1. `sehatsethu.sql`

## Build And Run
1. Open the project in Android Studio.
2. Ensure `local.properties` points to your Android SDK.
3. Update `ApiConfig.BASE_URL` to match your backend host.
4. Build and run:

```bash
./gradlew assembleDebug
```

## Project Structure (High Level)
1. `app/src/main/java/com/simats/criticall`
   - Shared app logic, preferences, API utilities, assistant, translations.
2. `app/src/main/java/com/simats/criticall/roles`
   - Role‑specific flows: `patient`, `doctor`, `pharmacist`, `admin`.
3. `app/src/main/res`
   - Layouts, drawables, and strings (multi‑language).

## Notes
1. The project name is `criticall` in package naming.
2. Cleartext traffic is enabled for local dev (see `AndroidManifest.xml`).
3. For production, move sensitive keys to secure storage and use HTTPS endpoints.

