# Chrome Web Store Listing - ReelLimit

**Last Updated:** 2026-10-21  
**Version:** 1.0.0  
**Category:** Productivity / Wellbeing  

---

## Listing Metadata

### Item Title
ReelLimit - Shorts, Reels & Spotlight Focus Guard

### Short Description (Max 132 chars)
Monitors watched Reels, Shorts, and Spotlight count, provides reminders every 10 videos, and enforces daily focus limits.

### Detailed Description
Take control of endless algorithmic scrolling. ReelLimit helps you stay mindful and productive by monitoring how many short-form videos (YouTube Shorts, Instagram Reels, and Snapchat Spotlight) you watch.

**Key Features:**
- **Accurate Video Counter:** Seamlessly tracks unique Reels, Shorts, and Spotlight videos as you scroll.
- **Mindful Reminders:** Delivers a gentle on-screen milestone alert and browser notification every 10 reels so you never lose track of time.
- **Customizable Daily Limits:** Set your ideal daily target (e.g., 10, 20, 30 reels).
- **Automatic Feed Enforcer:** Once your limit is reached, ReelLimit locks the short-video feed or closes the tab to help you reclaim your time.
- **Dopamine Reset Screen:** Features a calming 4-7-8 breathing exercise to reset your attention span.
- **100% Private & Local:** All counts and settings are stored locally on your device. Zero telemetry, zero analytics, zero external servers.

---

## Permissions Justification

| Permission | Justification |
| :--- | :--- |
| `storage` | Stores your daily watched video counts, breakdown statistics, and custom limit preferences locally in your browser. |
| `notifications` | Displays a reminder every 10 reels watched and notifies you when your daily limit has been reached. |
| `tabs` | Required to redirect or automatically close the video feed tab once your daily limit is hit. |
| `alarms` | Ensures daily watch counters automatically reset at midnight. |

### Host Permissions Justification

| Host Pattern | Justification |
| :--- | :--- |
| `*://*.youtube.com/*` | Injects a lightweight content script on YouTube Shorts to count distinct video views and display the in-feed reminder badge. |
| `*://*.instagram.com/*` | Injects a lightweight content script on Instagram Reels to monitor view counts and display milestone reminders. |
| `*://*.snapchat.com/*` | Injects a lightweight content script on Snapchat Spotlight to monitor view counts and enforce limits. |

---

## Privacy & Data Use Disclosure
- **Personal Data Collected:** None.
- **Remote Communications:** None. No data leaves the user's browser.
- **Single Purpose Compliance:** The extension exclusively exists to monitor short-video consumption and assist the user with digital wellbeing.
