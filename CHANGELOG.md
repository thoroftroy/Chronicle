# Chronicle changelog

## v0.5.1

- Fixed the countdown widget saying “Nothing coming up”: it now reads just the event you picked (or looks ahead a day, a week, a month, a year), works on big synced calendars, and remembers your event even if the calendar is briefly unavailable
- Widgets update in the background, so saving events and tasks no longer makes the app stutter
- Much faster and smoother: the app is now built optimised (the way Play Store apps are), so screens open quicker, scrolling is fluid and the APK is about a quarter of the size
- Screens and lists no longer decode pictures on the main thread, and skip redrawing parts that didn't change

## v0.5.0

- Month view redesigned: only the weeks the month needs, so each day is much taller; events are clear coloured chips that fit more per day; selected day is outlined
- New option: let the calendar fill the screen and tap a day to see its events in a sheet. Start times can show in the month when days are wide enough
- In landscape the month and the day's list sit side by side
- Countdown widget now counts down to the event you choose (a wedding, a trip) or whatever's next, with your own label, colour and style (days, weeks, days + hours). After the event it counts the days since
- New “Count down to this on your home screen” button on every event
- Up next widget settings: how far ahead to look, whether to include tasks, and the background
- Fixed a crash in Agenda with events that span several days
- Works properly in landscape: tabs move to a rail at the side so the content keeps its height, and screens that ran out of room now scroll
- If the app ever crashes it says so next time it opens and offers to send a report (only the error, app version and phone model, with file names and links removed). Turn on automatic sending in Control Room › Wonderdesk
- Lists can no longer crash when two items share a name

## v0.4.0

- Tapping a date no longer jumps back to today
- Month view shows event titles in each day; tap the selected day again to open it, or use the Open day and Add buttons
- New Countdown widget for your next event, ticking live in the last 24 hours
- If the app ever crashes or freezes, a report goes to Control Room so it can be shared and fixed
- App icon art is larger and fills its tile without being cut off

## v0.3.1

- The current-time line updates once a minute and only while Chronicle is on screen
- App icon redrawn so nothing is cut off: round icons fill the space, the rest sit on an Aqua tile
- Smoother and lighter on the battery: the window background no longer animates, tap effects sleep until you tap, and nothing ticks while the app is in the background
- Opening the app no longer waits on Control Room to read the suite look

## v0.3.0

- Reads and writes your phone's calendars, so Google Calendar sync just works
- Month, day and agenda views, jump to any date, countdowns
- Tasks with due dates, reminders and a home screen widget
- .ics import and export
