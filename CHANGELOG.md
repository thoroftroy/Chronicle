# Chronicle changelog

## v0.5.8

- Swiping between months no longer flashes: months you've seen show their events instantly and update in place
- Countdown widget: show the time left as hours only, minutes only, months and days, or sleeps, as well as the earlier styles
- Changing the look in Control Room now restyles this app's widgets straight away, with no refresh or restart

## v0.5.7

- Widgets: choose the title colour in each widget's settings (automatic, white, dark, Aqua blue, Super pink or gold), on top of the background looks

## v0.5.6

- Reminders no longer come back after you swipe them away: Android re-sends calendar reminders after a restart or time change until they're marked delivered, and Chronicle now marks them (and never shows the same one twice)
- Reminders for events that ended while the phone was off are skipped instead of arriving late
- If Google Calendar is still on (so every reminder shows twice), Chronicle says so and takes you to switch it off
- Widgets update by themselves as soon as a calendar changes, the time zone changes or the app updates

## v0.5.5

- Widgets can now match your look: new Bliss blue and Super neon backgrounds, and “Match my Wonderdesk look” follows whatever Control Room is set to

## v0.5.4

- Up next widget: choose which events it shows. Its settings list what's coming in the next month, most frequent first, so you can switch off things like work every day; or hide all repeating events, or whole calendars. They still show in Chronicle itself
- Fixes from a full round of stress testing on every screen

## v0.5.3

- Swipe a task right to complete it, or left to move it to tomorrow
- Tap the tab you're already on to glide back to the top of its list
- Nothing required stays hidden: if Android is blocking something the app needs, a slim banner says what and takes you straight to the switch, and Settings › Permissions and access lists everything with its state

## v0.5.2

- The day view now swipes smoothly between days too, keeping the timeline at the same hour
- Tasks glide into place when you add, finish or reorder them
- Smoother everywhere: new screens, tabs and Settings ease in instead of snapping, and buttons, tabs, chips and switches give a light tap you can feel (following your phone's touch-feedback setting)
- Android's predictive back: the back gesture shows a live preview of where you're going
- Swiping between months is smooth: the next month slides in under your finger and snaps into place, and the arrows animate the same way
- Countdown widget fits any size, down to a 1×1 square with a big number and the event name
- Up next widget shrinks cleanly to small sizes
- Super look: tap sparkles now match Bliss Player: soft glowing sparks with trails, drifting rainbow colours and a faint aurora, instead of big solid circles

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
