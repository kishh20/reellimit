/**
 * ReelLimit Content Script
 * Tracks Shorts/Reels/Spotlight playback, manages in-page reminder toasts, and enforces limits.
 */

(function () {
  'use strict';

  // Prevent multiple executions in the same frame
  if (window.__reellimit_loaded) return;
  window.__reellimit_loaded = true;

  const hostname = window.location.hostname;
  let platform = null;

  if (hostname.includes('youtube.com')) platform = 'youtube';
  else if (hostname.includes('instagram.com')) platform = 'instagram';
  else if (hostname.includes('snapchat.com')) platform = 'snapchat';

  if (!platform) return;

  const watchedVideoIds = new Set();
  let currentVideoId = null;
  let watchTimer = null;
  let currentSettings = null;
  let currentToday = null;

  // DOM Elements
  let pillContainer = null;
  let pillText = null;
  let pillDot = null;
  let milestoneToast = null;
  let toastBody = null;
  let toastTitle = null;

  function extractVideoId() {
    const path = window.location.pathname;

    if (platform === 'youtube') {
      const match = path.match(/\/shorts\/([a-zA-Z0-9_-]+)/);
      return match ? match[1] : null;
    }

    if (platform === 'instagram') {
      const match = path.match(/\/(?:reels|reel)\/([a-zA-Z0-9_-]+)/);
      return match ? match[1] : null;
    }

    if (platform === 'snapchat') {
      const match = path.match(/\/spotlight\/([a-zA-Z0-9_-]+)/);
      if (match) return match[1];
      if (path.includes('/spotlight')) {
        const activeVideo = document.querySelector('video[src]');
        if (activeVideo && activeVideo.src) {
          return activeVideo.src.split('?')[0].slice(-30);
        }
      }
      return null;
    }

    return null;
  }

  function initUI() {
    if (pillContainer) return;

    // Mini floating status pill in bottom-right
    pillContainer = document.createElement('div');
    pillContainer.className = 'reellimit-pill-container';

    const pill = document.createElement('div');
    pill.className = 'reellimit-mini-pill';
    pill.title = 'ReelLimit - Click to check details';

    pillDot = document.createElement('span');
    pillDot.className = 'reellimit-pill-dot';

    pillText = document.createElement('span');
    pillText.textContent = 'Reels: ...';

    pill.appendChild(pillDot);
    pill.appendChild(pillText);
    pillContainer.appendChild(pill);

    // Milestone Toast Card (Every 10 reels)
    milestoneToast = document.createElement('div');
    milestoneToast.className = 'reellimit-milestone-toast';

    const toastHeader = document.createElement('div');
    toastHeader.className = 'reellimit-toast-header';

    toastTitle = document.createElement('div');
    toastTitle.className = 'reellimit-toast-title';
    toastTitle.innerHTML = '<span>🔔 Milestone Alert</span>';

    const toastBadge = document.createElement('span');
    toastBadge.className = 'reellimit-toast-badge';
    toastBadge.textContent = 'Take a Break';

    toastHeader.appendChild(toastTitle);
    toastHeader.appendChild(toastBadge);

    toastBody = document.createElement('div');
    toastBody.className = 'reellimit-toast-body';
    toastBody.textContent = 'You have watched 10 reels. Stretch your legs or drink some water.';

    const toastActions = document.createElement('div');
    toastActions.className = 'reellimit-toast-actions';

    const dismissBtn = document.createElement('button');
    dismissBtn.className = 'reellimit-toast-btn';
    dismissBtn.textContent = 'Got it';
    dismissBtn.addEventListener('click', () => {
      milestoneToast.classList.remove('show');
    });

    toastActions.appendChild(dismissBtn);

    milestoneToast.appendChild(toastHeader);
    milestoneToast.appendChild(toastBody);
    milestoneToast.appendChild(toastActions);

    document.body.appendChild(pillContainer);
    document.body.appendChild(milestoneToast);

    // Open extension popup/dashboard helper when clicking pill
    pill.addEventListener('click', () => {
      showTemporaryToast('Daily Focus', `Today's Count: ${currentToday ? currentToday.total : 0} / Limit: ${currentSettings ? currentSettings.dailyLimit : 20}`);
    });
  }

  function updatePillDisplay(total, limit) {
    if (!pillText || !pillDot) return;
    pillText.textContent = `Reels: ${total} / ${limit}`;

    pillDot.className = 'reellimit-pill-dot';
    if (total >= limit) {
      pillDot.classList.add('danger');
    } else if (total >= limit * 0.75) {
      pillDot.classList.add('warning');
    }
  }

  function showTemporaryToast(title, message, durationMs = 6000) {
    if (!milestoneToast) return;
    toastTitle.innerHTML = `<span>🔔 ${title}</span>`;
    toastBody.textContent = message;
    milestoneToast.classList.add('show');

    setTimeout(() => {
      if (milestoneToast) milestoneToast.classList.remove('show');
    }, durationMs);
  }

  function showExitOverlay(limit) {
    // Show full-screen blocker when limit is reached
    const overlay = document.createElement('div');
    overlay.className = 'reellimit-block-screen';

    const icon = document.createElement('div');
    icon.className = 'reellimit-block-icon';
    icon.textContent = '🛑';

    const title = document.createElement('div');
    title.className = 'reellimit-block-title';
    title.textContent = 'Daily Limit Reached';

    const sub = document.createElement('div');
    sub.className = 'reellimit-block-sub';
    sub.textContent = `You've watched your limit of ${limit} short videos today! Closing this tab to protect your time and focus.`;

    const pill = document.createElement('div');
    pill.className = 'reellimit-countdown-pill';
    pill.textContent = 'Closing now...';

    overlay.appendChild(icon);
    overlay.appendChild(title);
    overlay.appendChild(sub);
    overlay.appendChild(pill);

    document.body.appendChild(overlay);
  }

  async function checkInitialStatus() {
    try {
      const response = await chrome.runtime.sendMessage({ type: 'GET_STATUS' });
      if (response && response.success) {
        currentSettings = response.settings;
        currentToday = response.today;

        initUI();
        updatePillDisplay(response.today.total, response.settings.dailyLimit);

        if (response.isLimitReached && isWatchingFeed()) {
          showExitOverlay(response.settings.dailyLimit);
        }
      }
    } catch (err) {
      // Background worker might be sleeping; ignore
    }
  }

  function isWatchingFeed() {
    return Boolean(extractVideoId());
  }

  async function recordCurrentVideo(videoId) {
    if (!videoId || watchedVideoIds.has(videoId)) return;

    try {
      const response = await chrome.runtime.sendMessage({
        type: 'RECORD_WATCH',
        platform,
        videoId
      });

      if (!response || !response.success) return;

      watchedVideoIds.add(videoId);
      currentToday = { ...(currentToday || {}), total: response.total };
      const limit = response.settings ? response.settings.dailyLimit : 20;

      updatePillDisplay(response.total, limit);

      // Handle Milestone Reminder (e.g. at 10, 20, 30...)
      if (response.isReminder && !response.limitReached) {
        showTemporaryToast(
          `10 Reels Watched (${response.total} Total)!`,
          `Mindful reminder: You've scrolled through ${response.total} short videos. Give your eyes a break! 🌿`
        );
      }

      // Handle Limit Reached
      if (response.limitReached) {
        showExitOverlay(limit);
      }
    } catch (err) {
      console.warn('ReelLimit watch recording error:', err);
    }
  }

  function handleVideoChange() {
    const videoId = extractVideoId();

    if (watchTimer) {
      clearTimeout(watchTimer);
      watchTimer = null;
    }

    if (!videoId) {
      // User navigated away from shorts/reels feed
      if (pillContainer) pillContainer.style.display = 'none';
      return;
    }

    if (pillContainer) pillContainer.style.display = 'block';

    if (videoId === currentVideoId) return;
    currentVideoId = videoId;

    // Debounce timer: wait 1.8 seconds of continuous viewing before counting as a watch
    // This ensures fast accidental swiping is not counted
    watchTimer = setTimeout(() => {
      recordCurrentVideo(videoId);
    }, 1800);
  }

  // Intercept Single-Page Application (SPA) navigation
  const originalPushState = history.pushState;
  const originalReplaceState = history.replaceState;

  history.pushState = function (...args) {
    const result = originalPushState.apply(this, args);
    setTimeout(handleVideoChange, 100);
    return result;
  };

  history.replaceState = function (...args) {
    const result = originalReplaceState.apply(this, args);
    setTimeout(handleVideoChange, 100);
    return result;
  };

  window.addEventListener('popstate', () => {
    setTimeout(handleVideoChange, 100);
  });

  // YouTube SPA specific event
  window.addEventListener('yt-navigate-finish', () => {
    setTimeout(handleVideoChange, 150);
  });

  // Polling fallback to catch any layout/scroll changes in feeds
  setInterval(() => {
    const current = extractVideoId();
    if (current && current !== currentVideoId) {
      handleVideoChange();
    }
  }, 1000);

  // Initialize
  checkInitialStatus().then(() => {
    handleVideoChange();
  });
})();
