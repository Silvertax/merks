# Merk's

Tap once, say it, done. Merk's is an Android app for people who do not want to open an app, type, or think about where something belongs.

A home screen widget starts listening right away. What you say is sorted into an appointment, a reminder or a note. Appointments go into the phone calendar, and the app notifies you in time.

## How it works

1. Tap the widget (or the "Sprechen" button in the app).
2. Speak, for example "Morgen um 10 Zahnarzt" or "Erinnere mich in 20 Minuten an die Wäsche".
3. The app shows what it understood. "Rückgängig" removes it again.

Notifications: reminders fire at their time, appointments one hour ahead, all-day entries at 8:00.

## Sorting

- **Offline rules** (default): recognise common German date and time phrases. No account, no internet for the sorting step.
- **AI** (optional): enter an Anthropic API key under "Einstellungen". The spoken text is then sent to the Anthropic API, which also copes with free-form speech. If the request fails, the offline rules take over.

Speech-to-text uses the recogniser installed on the phone (`SpeechRecognizer`). Whether that works offline depends on the phone.

## Status

Version 0.1.0 is an early test version. The interface and the offline rules are German only.

## Build

```
./gradlew testDebugUnitTest assembleRelease
```

Requires JDK 17 or newer and the Android SDK (platform 35). Release signing reads `MERKS_KEYSTORE`, `MERKS_KEYSTORE_PASSWORD`, `MERKS_KEY_ALIAS` and `MERKS_KEY_PASSWORD` from the environment; without them the debug key is used.
