# Changelog

What changed for users, per version. When a GitHub release is created with an empty description, the Build workflow fills it in from the section of that version.

## 0.5.2

### Changed

- Prepared for F-Droid: the F-Droid version gets its updates from F-Droid and leaves out the update check on GitHub. The version from GitHub keeps telling you about new releases.
- The APK no longer contains an encrypted block of dependency data meant for Google.

## 0.5.1

### What's new

- **Theme:** choose light, dark or like the phone in the settings.
- **OLED mode** now makes the dark theme pure black; it no longer switches the app to dark by itself. If you had it on, the app stays dark.

## 0.5.0

### What's new

- **English:** the app now speaks English as well. It follows the phone (German phones get German, all others English), and the language can be chosen in the settings; on Android 13 and newer also in the system's app language settings.
- **Offline rules in English:** "Tomorrow at 10 dentist", "Remind me in 20 minutes to take the laundry out", "Every Monday take out the trash" and more work without AI.
- **Speech recognition** listens in the app's language; dates and times follow it too ("Fri, Nov 13, 10:00 AM").
- AI error messages appear in the app's language.

## 0.4.1

### What's new

- **OLED mode:** a switch in the settings for a pure black background, which saves power on OLED screens. The app then stays dark.
- **Widget:** the microphone now stands on its own, without the light circle, in all sizes.

## 0.4.0

A new look.

### What's new

- **New icon:** a speech bubble with an "m" and an amber dot, instead of a microphone like any dictation app.
- **Fresh design:** calm teal and warm amber on cream, the friendly Nunito font, rounded buttons and cards with a symbol for each kind of entry.
- **Dark mode:** the app follows the phone's light or dark setting.
- **Listening you can see:** the ring around the microphone grows with your voice.
- **Clearer settings:** a title bar with a back arrow, settings grouped, and each row can be tapped as a whole.
- **Widget and notifications** in the new style.

### Fixed

- Settings: tapping next to the key or model field ends the input and starts the check more reliably.

## 0.3.1

Includes 0.3.0, which was not released on its own.

### What's new

- **Series:** "jeden Montag", "jeden Tag um 8 Uhr", "alle zwei Wochen freitags", "jeden Monat am 1." or "jedes Jahr am 3. Dezember" become repeating calendar events.
- **Reminder the evening before:** appointments also notify at 18:00 on the day before. Can be switched off in the settings.
- **Buttons in reminders:** "Erledigt", "In 15 Min." and "In 1 Std." right in the notification. "Erledigt" on a series only settles that date.
- **Choose the AI model:** any Anthropic model can be entered; Haiku 5.5 is the default. Spellings like "Sonnet 5.5" are turned into the model ID.
- **Checks as you go:** key and model are checked as soon as you leave the field. An unknown model goes back to Haiku 5.5 with a short note, and the settings show whether the last AI request worked.

### Fixed

- The AI test failed with "No value for text" for models that think before they answer, such as Sonnet 5.5 and Opus 5.5.
- A model entered in the settings was lost when leaving without pressing "Speichern". Everything is now saved automatically.
- AI errors are shown as short German texts instead of raw HTTP codes.

## 0.2.1

Includes 0.2.0, which was not released on its own.

### What's new

- **Update notice:** the app checks GitHub once a day and tells you when a newer version is available. Can be switched off in the settings.
- **Resizable widget:** 2x2 by default, down to 1x1. Small sizes show only the microphone, flat wide sizes show icon and label side by side.
- **Compact recording:** only a small info appears at the bottom of the screen while listening. Afterwards a notification shows what was saved, with an undo action. Can be switched to full screen in the settings.

### Fixed

- The widget label was missing at 2x2.

## 0.1.0

First test version.

- **Widget:** one tap on the home screen, then speak.
- **Sorting:** what you say becomes an appointment, a reminder or a note. Appointments go into the phone calendar.
- **Reminders:** on time for reminders, one hour ahead for appointments, at 8:00 for all-day entries.
- **Offline first:** German date and time phrases are understood without internet. AI sorting with an Anthropic API key is optional.
