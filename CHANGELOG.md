# Changelog

What changed for users, per version. When a GitHub release is created with an empty description, the Build workflow fills it in from the section of that version.

## 0.11.2

### Changed

- **Weekly plans with marked appointments:** the check list shows only what is marked on the plan. A button below it, e.g. "Show 12 more dates", brings the others to tick as well.

### Fixed

- **Weekly plans with AI:** the AI often ticked every appointment, also when only some were marked with a highlighter. It is now asked about each entry on its own, and when it still ticks everything, the phone looks at the colours itself and ticks only what is marked. Reading such a plan takes a little longer for that.

## 0.11.1

### Fixed

- **Weekly plans with AI:** a plan with many appointments made the AI's answer longer than it was allowed to be. The answer was lost, and the phone read the plan itself instead. The answer may now be twice as long and is written more briefly; if it is cut off all the same, the appointments that are complete are kept.
- **Weekly plans without AI:** when the phone could not read some of the weekday headings, their columns were missing, so that for example only Thursday and Friday appeared. Two headings are now enough for the whole week, and the plan is read twice, so that a heading or a time lost in one reading comes from the other.
- A sentence under the plan ("Every first Thursday ...") no longer turns into an appointment.
- An appointment of which only the end time could be read is left out instead of being entered at the wrong time.

## 0.11.0

### What's new

- **Appointments from a picture, a file or a message:** in WhatsApp, a mail or the gallery tap "Share" and pick Merk's. A picture is read like a photo, a PDF with its first three pages together, a message is searched for dates; a short one like "tomorrow at 10 dentist" is understood like something said.
- **"From a picture or file"** on the main screen opens a picture, a PDF or a calendar file from the phone.
- **Calendar files (.ics)** from booking pages and invitations are taken over exactly: title, place, start and end, a series as a series. Merk's can also open them from a mail.
- **Weekly plans:** a plan of courses, groups or treatments with a column for each weekday becomes all its appointments, each with its own title and with the room or a remark in its note. What is marked on the plan with a highlighter is ticked; the rest can be ticked as well.

### Changed

- **Without AI,** the phone now reads a weekly plan column by column, so each time goes with its own title and day; misread titles are evened out with the way the plan writes them elsewhere.
- **With AI,** a plan comes with all its appointments (up to 40, before only 12), and none of them ends up in the note any more.

### Good to know

- Merk's reads a copy and deletes it afterwards; the original stays where it is. Entries say that they were taken from a file or message.
- With an AI key, pictures and messages go to the AI as photos do. Calendar files are read on the phone only.

## 0.10.0

### What's new

- **"All in one" widget:** what comes next and the buttons to speak and to photograph, on one card. Two rows high it shows the date tile, the title and when, the entry after it, and the long buttons "Speak" and "Photo"; one row high, round buttons sit beside it. "Put on the home screen" offers it first.
- **All widgets in a new look:** a calm card in the phone's light or dark mode, big round buttons, and in "Up next" the date tile from the list: teal for appointments, amber for reminders.
- **Clear print:** widgets cannot use the app's own font, so on most phones their text came out thin and plain. They now use the phone's font, bold where it matters.

### Changed

- Every widget fits its size and larger print without cutting anything off: a narrow "Up next" puts the day on top and lists the entries after it with their day above the title; a very low one puts everything in one line, the time first.
- "Up next" starts three cells wide, with room for the date tile.

### Good to know

- Widgets already on the home screen take on the new look by themselves after the update.

## 0.9.1

### What's new

- **Merk's as the calendar:** a new switch in the settings, for anyone who uses Merk's instead of the phone's calendar. Appointments then stay in Merk's only, and Merk's reminds you of them. The appointments already in the phone's calendar can be taken out of it right away, so the calendar app does not remind you as well; switched off again, they can go back in.
- **Choose when appointments remind you:** a week, a day or two hours ahead, the evening before at 6 pm, an hour, 30 or 15 minutes ahead, when it starts, or several of these (Settings → Remind me of appointments). Out of the box it stays the evening before and an hour ahead; all-day appointments remind at 8 in the morning.

### Changed

- The reminder for an appointment has "OK" instead of "Done": it puts away only this notification, so a tap a day ahead no longer cancels the reminder an hour ahead, and the appointment stays under "Up next" until it is over.
- Saving, alarms and widget updates are quicker and steadier: all parts of the app now share one connection to the entries, instead of opening a new one each time that stayed open until Android cleaned up.

### Good to know

- 0.9.0 was published without the app file: its build stopped at a test that gave the build server too little time. Everything from it is in 0.9.1.

## 0.9.0

Published without the app file; its changes came with 0.9.1.

## 0.8.0

### What's new

- **Appointments from a photo:** tap "Snap an appointment" in the app, or the new "Photo" widget, and photograph a letter or an appointment card. Merk's reads the date, the time and what it is about, and shows it to check and change before it goes into the calendar, with a note such as what to bring. A card with several treatments gives several appointments to tick; if no date was found, you can pick it yourself. The letter's own date, birthdays and dates that are over are left out.
- **With and without AI:** without an AI key, the phone reads the photo itself; that works well for printed text. With an AI key, the AI reads it, handwriting included. Without internet, the phone takes over.
- **The photo is not kept:** it is deleted as soon as it has been read. Only the line the date came from stays with the entry ("Read from a photo: …").
- "Put on the home screen" now offers the "Photo" button as well.

### Good to know

- The app is bigger now, about 14 MB instead of 5 MB: the reading on the phone needs its recognition data for German and English.

## 0.7.1

### What's new

- **Only what is current:** appointments and reminders whose time is over now leave the list by themselves, done or not, so what is still to come is always at the top. An appointment counts as over when it ends (an hour after it starts if no end was said), an all-day one at midnight, a reminder an hour after it rang. Appointments stay in the phone's calendar; notes and series stay in the app.

### Fixed

- The buttons of a reminder notification whose entry is gone now close the notification instead of doing nothing.

## 0.7.0

### What's new

- **Notes that go with an appointment:** say what belongs to it, for example what to bring or to buy for it. Tap "Add note" on the confirmation right after saving, or tap the entry and choose "Add a note by voice". The note shows below the appointment in the list, in the calendar entry, in the reminder and when sharing. A note spoken on its own can join an appointment afterwards with "Add to an appointment", and "Edit by typing" has a field for it.
- **The weekday at a glance:** the date in front of each entry now shows the weekday too, such as "Sat 14 Nov".
- **Choose how long to think:** under Settings → Pauses while speaking, the wait after a pause is short (1.5 s), normal (3 s) or long (6 s).

### Fixed

- **Speaking stopped too soon on some phones.** When the phone's speech recognition refused to start listening again right after a pause, or gave up early, Merk's saved at once. It now starts a fresh recognizer each time, tries again until the pause is really over, and ignores late messages of the one before.
- **Words could get lost:** when speech recognition ended without a final result, the words already shown on screen were thrown away. They now count. Tapping "Done speaking" saves what was understood even if the recognizer does not answer any more.
- **"Error 5":** this hiccup of the speech recognition is now tried again once by itself, and otherwise explained in plain words instead of a number.

## 0.6.3

### What's new

- **Time to think while speaking:** a short pause no longer ends the recording. Many phones stop listening after half a second of silence; Merk's now keeps listening, shows "Still listening …" with what you said so far, and puts it all together. Only after about three seconds without a word is it saved. Tap "Done speaking" to save right away.

## 0.6.2

### What's new

- **The date at a glance:** in the list, appointments and reminders now show the day and month, such as "18 Nov", in place of the calendar or bell symbol. You find a date without reading the lines.
- **"Up next" as a second big button:** the "Up next" widget now fits two cells wide, next to the "Speak" button, with the day on top and the time below the title. A tap opens the list with all entries, so it is a large, easy-to-hit way into the app. New ones start at that size; wider or taller shows more.
- **Edit by typing:** tap an entry and choose "Edit by typing" to correct its text, for example a name the speech recognition got wrong, or to pick another day and time. The calendar entry and the reminders follow. Changing by voice works as before.
- **Share:** the new share button at the top of the list passes on everything that is coming up as plain text, for example by WhatsApp or mail. To pass on a single entry, tap it and choose "Share".
- **Update in the app:** when "Check for updates now" finds a new version, "Update now" downloads it right in the app and opens the phone's installer. No more looking for the file on a web page. The first time, the phone asks you to allow Merk's to install its update; the app explains that step. If the download fails, the button offers the browser instead.
- **Both widgets from the app:** "Put on the home screen" now offers the "Speak" button and "Up next". In the phone's widget list, the button is now called "Speak".

### Fixed

- With large print set on the phone, the "Up next" widget could cut off its last line. It now shows only as much as fits.

## 0.6.1

### Better understanding without AI

Most people use Merk's without an AI key, so the built-in rules now understand a lot more, in English and German:

- **Time spans:** "Tomorrow from 10 to 12 handyman", "Friday from 2 to 4 pm", "between 2 and 4". The calendar event gets the right end, and the app shows it too.
- **Several days:** "Monday to Friday vacation" or "December 20 to 23 grandma visits" become one calendar event over all those days.
- **In the evening:** "today at 8" said in the evening now means 8 pm instead of a time that is already over. A bare "at 8" or "at 9" once the morning is past also means tonight; "at 10" or "at 11" still means tomorrow morning.
- **Short times are reminders:** "in 20 minutes", "in 2 hours", "in half an hour" now remind you on time instead of making a calendar appointment.
- **More series:** "every other Wednesday", "every second Wednesday", and "every weekday" or "on weekdays", a new series from Monday to Friday.
- **More dates:** "on the 15th", "Friday the 20th", "the 1st of next month", "a week from Friday", "Wednesday next week", "this weekend", "the first Monday of the month", "at midnight", "this afternoon", and "12/3" (month first in the US, day first elsewhere).
- **Cleaner titles:** "next week", "of every month", "until" and the like no longer end up in the title.
- "At 3:15" without am or pm is now in the afternoon.

In German, for example: "morgen von 10 bis 12 Uhr Handwerker", "vom 20. bis 23. Dezember Urlaub", "heute um 8", "in 2 Stunden", "jeden zweiten Mittwoch", "werktags um 7", "am 15.", "nächste Woche Mittwoch", "bis Freitag".

### Fixed

- Entries the AI recognised as lasting several days went into the calendar as a single day.

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
