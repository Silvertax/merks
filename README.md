# Merk's

Tap once, say it, done. Merk's is an Android app for people who do not want to open an app, type, or think about where something belongs.

A home screen widget starts listening right away. What you say becomes an appointment, a reminder or a note. Appointments go into your calendar, and the app reminds you in time. Merk's speaks English and German.

<p>
  <img src="docs/screenshots/en-speaking.png" width="200" alt="Home screen with the Up next widget, speaking via the widget">
  <img src="docs/screenshots/en-main-dark.png" width="200" alt="List of entries">
  <img src="docs/screenshots/en-changed.png" width="200" alt="Date changed by voice">
  <img src="docs/screenshots/en-settings-dark.png" width="200" alt="Settings">
</p>

## Install

Download `merks.apk` from the [latest release](https://github.com/Silvertax/merks/releases/latest) and open it. Updates install over older versions; your entries are kept. To look for a new version, tap “Check for updates now” in the settings.

## How it works

1. Tap the widget or the "Speak" button in the app.
2. Say what you want to remember, for example "Tomorrow at 10 dentist" or "Remind me in 20 minutes to take the laundry out".
3. A notification shows what was saved. "Undo" removes it again.

Forgot the date, or said the wrong time? Tap "Change" on the confirmation, or tap the entry in the app and then "Change date or time", and just say the new one, for example "Friday at 10". The title stays; the calendar entry and the reminders move along. A note that gets a date becomes an appointment.

Repeating things work too: "Every Monday take out the trash", "Every day at 8 am take pills", "Every year on December 3rd grandma's birthday". They become repeating calendar events.

Reminders come on time, appointments one hour ahead and, if you like, also the evening before. Each reminder has "Done", "In 15 min" and "In 1 hr" buttons.

The widget can be resized from 2x2 down to 1x1. A second widget, "Up next", shows on your home screen what comes next; made taller, it also lists the two after it. If you like, a quiet notification shows it on the lock screen too (Settings → Always show what's next).

The theme is light, dark or follows your phone, and an OLED mode makes the dark theme pure black.

## Language

German phones get German, all others English. You can also choose the language in the settings (on Android 13 and newer also in the system's app language settings). Speech recognition follows the app's language.

## Understanding what you say

- **Built in:** common date, time and repeat phrases in English and German are understood offline, without any account.
- **With AI (optional):** with your own Anthropic API key, Merk's also understands free-form speech. You can choose the model; the settings show whether key and model work. Without internet, the built-in rules take over.

## Privacy

No ads, no tracking, no account. Your entries stay on your phone and in your calendar.

- Speech recognition is done by the recognizer installed on your phone.
- Only when you tap "Check for updates now" in the settings, the app asks GitHub for the latest version. No personal data is sent.
- Only if you enter an AI key, the spoken text is sent to Anthropic to be sorted.

## Status

Merk's is young and still growing. Ideas and problems are welcome as an [issue](https://github.com/Silvertax/merks/issues).

## License

MIT, see [LICENSE](LICENSE). The Nunito font is licensed under the SIL Open Font License, see [fonts/Nunito-OFL.txt](fonts/Nunito-OFL.txt).
