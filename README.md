# Screen Time Archive

A private, non-root Android app that reads the usage history Android exposes,
estimates historical foreground time, exports CSV, and preserves daily totals
from the day the app is installed.

## What it can recover

Android stores usage in several aggregate buckets. Current AOSP defaults retain
daily files for about 10 days, weekly files for 4 weeks, monthly files for 6
months, and yearly files for 2 years. Phone manufacturers may change this.

For a phone bought about one year ago, the app may recover a yearly foreground-
time estimate covering that period, but it cannot recreate a day-by-day history
that Android has already deleted. It cannot read Digital Wellbeing's private
database on a normal, non-rooted phone.

The result is an **estimate of app foreground time**, not a certified measurement
of screen-on time. Removed apps can be absent and simultaneous split-screen apps
can overlap.

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

## First run

1. Tap **Grant usage access** and enable Screen Time Archive.
2. Return to the app.
3. Choose the date the phone was bought or first used.
4. Tap **Scan retained history**.

Keep usage access enabled so the scheduled job can backfill the most recent ten
days into the private archive. Android may delay background work under aggressive
battery optimization, so opening the app occasionally is useful.

## Technical notes

- Minimum Android version: Android 8.0 (API 26)
- Target Android version: Android 15 (API 35)
- Uses only Android framework APIs; no third-party runtime libraries
- Usage source: `UsageStatsManager`
- Local archive: `SQLiteOpenHelper`
- Background collection: `JobScheduler`
