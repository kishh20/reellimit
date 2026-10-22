# ReelLimit - Android Native App

ReelLimit is an Android application designed to track short-form video consumption (YouTube Shorts, Instagram Reels, Snapchat Spotlight), deliver reminders every 10 videos, and enforce daily limits by automatically closing the app and locking reopening.

---

## Technical Architecture

```
┌────────────────────────────────────────────────────────┐
│               Android System Events                    │
│   (Instagram, YouTube Shorts, Snapchat Spotlight)      │
└───────────────────────────┬────────────────────────────┘
                            │
                            ▼
              ┌───────────────────────────┐
              │ ReelAccessibilityService  │
              │ - Intercepts scroll/view  │
              │ - Debounces 1.8s per reel │
              └─────────────┬─────────────┘
                            │
         ┌──────────────────┴──────────────────┐
         ▼                                     ▼
 ┌───────────────┐                     ┌───────────────┐
 │ Count % 10 == 0│                    │ Count >= Limit│
 └───────┬───────┘                     └───────┬───────┘
         │                                     │
         ▼                                     ▼
 ┌───────────────────────────┐  ┌───────────────────────────────────┐
 │ Milestone Reminder Alert   │  │ 1. performGlobalAction(HOME)      │
 │ (Heads-up Notification &  │  │ 2. Launch LockOverlayActivity     │
 │ 300ms Haptic Vibration)   │  │ 3. Block attempts to reopen       │
 └───────────────────────────┘  └───────────────────────────────────┘
```

---

## Key Components

1. **`ReelAccessibilityService` (`accessibility_service_config.xml`):**
   - Monitors `com.google.android.youtube`, `com.instagram.android`, and `com.snapchat.android`.
   - Inspects UI hierarchies for short-form video player containers (`shorts_player_pager`, `clips_viewer`, `spotlight_carousel`).
   - Debounces scroll gestures (1.8s) to distinguish between rapid skimming and actual viewing.
   - Executes `performGlobalAction(GLOBAL_ACTION_HOME)` to instantly exit the restricted app when the user's limit is hit.
   - Continuously guards against reopening until the daily reset.

2. **`LockOverlayActivity`:**
   - Full-screen focus shield that appears when the limit is reached.
   - Traps the back button and provides a single action: "Return to Home Screen".

3. **`PreferencesManager`:**
   - Persists daily limit, reminder intervals, and per-platform counts in `SharedPreferences`.
   - Automatically detects date rollover and resets counts for the new day.

4. **`MainActivity`:**
   - Dashboard displaying live watch metrics and breakdown.
   - Status indicators verifying whether the Accessibility Service has been granted.
   - Sliders and toggles to adjust limits and monitor specific platforms.
   - Test buttons to verify reminder notifications and vibration immediately.

---

## Building and Running

### Option A: Using Android Studio (Recommended)
1. Launch **Android Studio**.
2. Select **Open** and choose the `cool-bell/android` directory.
3. Allow Gradle to sync dependencies (`androidx.core`, `androidx.appcompat`, `material3`).
4. Connect your Android device via USB (with USB Debugging enabled) or start an Android Virtual Device (AVD).
5. Click **Run 'app'** (`Shift + F10`).

### Option B: Command Line (Gradle)
```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Setup on Device
1. Open **ReelLimit**.
2. Tap **Enable in Settings** on the Accessibility Permission card.
3. Locate **ReelLimit Reel & Shorts Monitor** under Installed Services / Accessibility and toggle it **ON**.
4. (Android 13+) Grant the Notification permission when prompted.
5. Set your desired Daily Limit (e.g. 20 reels).
6. Open YouTube Shorts, Instagram Reels, or Snapchat Spotlight — ReelLimit will track your scrolls, alert you every 10 reels, and close the app once your limit is reached!
