/**
 * ReelLimit - Background Service Worker (Manifest V3)
 * Handles storage, badge updates, notifications, and tab enforcement.
 */

const DEFAULT_SETTINGS = {
  dailyLimit: 20,
  reminderInterval: 10,
  limitAction: 'block_page', // 'block_page' or 'close_tab'
  platforms: {
    youtube: true,
    instagram: true,
    snapchat: true
  }
};

function getTodayKey() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

async function getStoredState() {
  const result = await chrome.storage.local.get(['reellimit_settings', 'reellimit_today', 'reellimit_history']);
  const settings = { ...DEFAULT_SETTINGS, ...(result.reellimit_settings || {}) };
  const todayKey = getTodayKey();
  let today = result.reellimit_today;

  if (!today || today.date !== todayKey) {
    // New day or first run: archive yesterday if exists
    const history = result.reellimit_history || {};
    if (today && today.date && today.total > 0) {
      history[today.date] = {
        total: today.total,
        youtube: today.youtube || 0,
        instagram: today.instagram || 0,
        snapchat: today.snapchat || 0
      };
    }
    today = {
      date: todayKey,
      total: 0,
      youtube: 0,
      instagram: 0,
      snapchat: 0
    };
    await chrome.storage.local.set({ reellimit_today: today, reellimit_history: history });
  }

  return { settings, today };
}

async function updateBadge(total, dailyLimit) {
  try {
    const text = total > 0 ? String(total) : '';
    await chrome.action.setBadgeText({ text });
    const color = total >= dailyLimit ? '#ef4444' : total >= dailyLimit * 0.75 ? '#f59e0b' : '#6366f1';
    await chrome.action.setBadgeBackgroundColor({ color });
  } catch (err) {
    console.error('Badge update error:', err);
  }
}

chrome.runtime.onInstalled.addListener(async () => {
  const { settings, today } = await getStoredState();
  await updateBadge(today.total, settings.dailyLimit);
});

chrome.runtime.onStartup.addListener(async () => {
  const { settings, today } = await getStoredState();
  await updateBadge(today.total, settings.dailyLimit);
});

// Intercept tabs navigating to shorts/reels when limit is already reached
chrome.tabs.onUpdated.addListener(async (tabId, changeInfo, tab) => {
  if (!changeInfo.url) return;
  const url = changeInfo.url.toLowerCase();

  const isShorts = url.includes('youtube.com/shorts');
  const isReels = url.includes('instagram.com/reels') || url.includes('instagram.com/reel');
  const isSpotlight = url.includes('snapchat.com/spotlight');

  if (!isShorts && !isReels && !isSpotlight) return;

  const { settings, today } = await getStoredState();

  // Check if platform is active
  if (isShorts && !settings.platforms.youtube) return;
  if (isReels && !settings.platforms.instagram) return;
  if (isSpotlight && !settings.platforms.snapchat) return;

  if (today.total >= settings.dailyLimit) {
    if (settings.limitAction === 'close_tab') {
      try {
        await chrome.tabs.remove(tabId);
      } catch (e) {
        // Tab might already be closed
      }
    } else {
      const blockedUrl = chrome.runtime.getURL('blocked/blocked.html');
      if (tab.url !== blockedUrl) {
        try {
          await chrome.tabs.update(tabId, { url: blockedUrl });
        } catch (e) {
          console.error('Error redirecting to blocked page:', e);
        }
      }
    }
  }
});

// Handle messages from content scripts and popup UI
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  (async () => {
    try {
      if (message.type === 'GET_STATUS') {
        const { settings, today } = await getStoredState();
        sendResponse({
          success: true,
          settings,
          today,
          isLimitReached: today.total >= settings.dailyLimit
        });
        return;
      }

      if (message.type === 'RECORD_WATCH') {
        const { settings, today } = await getStoredState();
        const platform = message.platform; // 'youtube', 'instagram', 'snapchat'

        if (!settings.platforms[platform]) {
          sendResponse({ success: false, reason: 'platform_disabled', today });
          return;
        }

        // Check if already at or over limit
        if (today.total >= settings.dailyLimit) {
          sendResponse({
            success: true,
            limitReached: true,
            total: today.total,
            settings
          });

          // Trigger enforcement
          if (sender.tab && sender.tab.id) {
            if (settings.limitAction === 'close_tab') {
              await chrome.tabs.remove(sender.tab.id);
            } else {
              await chrome.tabs.update(sender.tab.id, {
                url: chrome.runtime.getURL('blocked/blocked.html')
              });
            }
          }
          return;
        }

        // Increment count
        today.total += 1;
        today[platform] = (today[platform] || 0) + 1;
        await chrome.storage.local.set({ reellimit_today: today });
        await updateBadge(today.total, settings.dailyLimit);

        const isReminder = today.total > 0 && today.total % settings.reminderInterval === 0;
        const isLimitHit = today.total >= settings.dailyLimit;

        // Deliver reminder notification if milestone reached
        if (isReminder && !isLimitHit) {
          try {
            await chrome.notifications.create(`reminder-${Date.now()}`, {
              type: 'basic',
              iconUrl: chrome.runtime.getURL('icons/icon-128.png'),
              title: `🔔 Watch Milestone: ${today.total} Short Videos!`,
              message: `You've watched ${today.total} reels today. Take a quick 1-minute pause to reset your eyes and mind.`,
              priority: 2
            });
          } catch (notifErr) {
            console.error('Notification error:', notifErr);
          }
        }

        // Deliver limit hit notification & enforce close
        if (isLimitHit) {
          try {
            await chrome.notifications.create(`limit-${Date.now()}`, {
              type: 'basic',
              iconUrl: chrome.runtime.getURL('icons/icon-128.png'),
              title: `🛑 Daily Reel Limit Reached (${settings.dailyLimit})!`,
              message: `You reached your daily limit of ${settings.dailyLimit} videos. Feed closed to safeguard your time.`,
              priority: 2
            });
          } catch (notifErr) {
            console.error('Notification error:', notifErr);
          }

          // Tab enforcement
          if (sender.tab && sender.tab.id) {
            if (settings.limitAction === 'close_tab') {
              // Grace delay so the content script can display an exit toast if needed
              setTimeout(async () => {
                try {
                  await chrome.tabs.remove(sender.tab.id);
                } catch (e) {}
              }, 1200);
            } else {
              setTimeout(async () => {
                try {
                  await chrome.tabs.update(sender.tab.id, {
                    url: chrome.runtime.getURL('blocked/blocked.html')
                  });
                } catch (e) {}
              }, 1200);
            }
          }
        }

        sendResponse({
          success: true,
          total: today.total,
          limitReached: isLimitHit,
          isReminder,
          settings
        });
        return;
      }

      if (message.type === 'UPDATE_SETTINGS') {
        const { settings: currentSettings } = await getStoredState();
        const updated = { ...currentSettings, ...message.settings };
        await chrome.storage.local.set({ reellimit_settings: updated });
        const { today } = await getStoredState();
        await updateBadge(today.total, updated.dailyLimit);
        sendResponse({ success: true, settings: updated });
        return;
      }

      if (message.type === 'RESET_TODAY') {
        const todayKey = getTodayKey();
        const resetToday = {
          date: todayKey,
          total: 0,
          youtube: 0,
          instagram: 0,
          snapchat: 0
        };
        await chrome.storage.local.set({ reellimit_today: resetToday });
        const { settings } = await getStoredState();
        await updateBadge(0, settings.dailyLimit);
        sendResponse({ success: true, today: resetToday });
        return;
      }

      sendResponse({ success: false, error: 'Unknown message type' });
    } catch (err) {
      console.error('Service worker error:', err);
      sendResponse({ success: false, error: err.message });
    }
  })();

  return true; // Keep message channel open for async response
});
