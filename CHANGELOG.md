# Changelog

What changed for users, per version. When a GitHub release is created with an empty description, the Build workflow fills it in from the section of that version.

## 0.6.0

### What's new

- **Change by voice:** forgot the date or said the wrong time? Tap "Change" on the confirmation, or tap the entry in the list and then "Change date or time", and say only the new date or time, e.g. "Friday at 10". The title stays, the calendar entry and the reminders move along, and "Undo" brings the old time back. A note that gets a date becomes an appointment.
- **Up next at a glance:** a new widget "Up next" shows the next appointment or reminder on the home screen; made taller, it also lists the two after it.
- **On the lock screen:** under Settings → "Always show what's next", a quiet notification shows what comes next, also on the lock screen.

### Changed

- **Updates:** the app no longer looks for updates by itself. Under Settings there is now a button "Check for updates now"; it says whether a newer version is out and offers the download. The switch and the notice on the main screen are gone.

## 0.5.3

### Fixed

- With OLED mode on and a theme different from the phone's, the recording restarted itself over and over and never listened.
- When no sound reaches the app at all, it now says "I didn't hear anything" and why, as far as the phone tells: a running call, a muted microphone or another app using it. Before, it said "I didn't catch that", as if you had only mumbled.
- If the speech recognition is still busy, the app tries once more by itself.
- "Put button on home screen": some phones, Xiaomi above all, take the request and then do nothing. The app now notices that and explains how to add the widget, with a shortcut to the app settings on Xiaomi.
- The speech recognition now gets a plain language code and, if it refuses the language, tries again in its own default.

## 0.5.2

### Changed

- Releases are now checked to carry the real signing key, so every update installs over the app you have.
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
