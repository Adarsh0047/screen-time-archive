# Screen Time Archive

A private, non-root Android dashboard that visualizes the usage history Android
exposes and automatically preserves event-derived daily totals locally.

## What it can recover

Android stores usage in several aggregate buckets. Current AOSP defaults retain
daily files for about 10 days, weekly files for 4 weeks, monthly files for 6
months, and yearly files for 2 years. Phone manufacturers may change this.

For a phone bought about one year ago, the app may recover a yearly foreground-
time estimate covering that period, but it cannot recreate a day-by-day history
that Android has already deleted. It cannot read Digital Wellbeing's private
database on a normal, non-rooted phone.

Recent daily values are calculated from the union of launchable-app lifecycle
events, preventing split-screen or duplicate lifecycle events from making a day
longer than 24 hours. Older results remain filtered aggregate estimates rather
than certified measurements of screen-on time. Removed apps can be absent.

## Privacy

- No internet permission.
- No analytics or account.
- The local daily archive is stored in the app's private SQLite database.
- CSV export happens only when the user taps Export and chooses a destination.

## Build

### Android Studio

1. Open this folder in Android Studio.
2. Allow Gradle sync to finish (JDK 17, Android SDK 35).
3. Select `app` and press Run, or use **Build > Build APK(s)**.

### GitHub Actions (can be triggered from a phone browser)

1. Put this project in a GitHub repository.
2. Open **Actions > Build Android APK > Run workflow**.
3. Download the `screen-time-archive-debug` artifact when the build finishes.
4. Extract it and install `app-debug.apk` on the phone.

Android will warn that the APK is from outside the Play Store. Only install an
APK you built from source you control.

## Features

- Overview with today, seven-day average, comparison, and bar chart
- Thirty-day history chart and recent daily values
- Weekday, peak-day, trend, and under-six-hour streak insights
- Automatic daily collection with ten-day catch-up and boot persistence
- Duplicate-safe CSV import plus CSV export
- Four-tab Material-style interface; no network permission or analytics

## First run

1. Tap **Grant usage access** and enable Screen Time Archive.
2. Return to the app.
3. The first-use date defaults to 1 January 2024 and can be changed under Data.
4. History loads and archives automatically; there is no manual scan step.

Keep usage access enabled. Android may defer exact background timing under battery
optimization, so every app launch also performs a safe automatic catch-up.

## Technical notes

- Minimum Android version: Android 8.0 (API 26)
- Target Android version: Android 15 (API 35)
- Uses only Android framework APIs; no third-party runtime libraries
- Usage source: `UsageStatsManager`
- Local archive: `SQLiteOpenHelper`
- Background collection: `JobScheduler`
