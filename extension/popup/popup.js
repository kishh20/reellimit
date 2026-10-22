/**
 * ReelLimit Popup Script
 */

document.addEventListener('DOMContentLoaded', async () => {
  const totalCountEl = document.getElementById('totalCount');
  const captionLimitEl = document.getElementById('captionLimit');
  const progressRingBar = document.getElementById('progressRingBar');
  const statusBadge = document.getElementById('statusBadge');

  const valYoutube = document.getElementById('valYoutube');
  const valInstagram = document.getElementById('valInstagram');
  const valSnapchat = document.getElementById('valSnapchat');

  const dailyLimitInput = document.getElementById('dailyLimitInput');
  const dailyLimitVal = document.getElementById('dailyLimitVal');
  const reminderIntervalInput = document.getElementById('reminderIntervalInput');
  const limitActionSelect = document.getElementById('limitActionSelect');

  const checkYoutube = document.getElementById('checkYoutube');
  const checkInstagram = document.getElementById('checkInstagram');
  const checkSnapchat = document.getElementById('checkSnapchat');

  const saveBtn = document.getElementById('saveBtn');
  const resetTodayBtn = document.getElementById('resetTodayBtn');
  const saveToast = document.getElementById('saveToast');

  const RING_CIRCUMFERENCE = 2 * Math.PI * 50; // r=50 => ~314.159
  progressRingBar.style.strokeDasharray = `${RING_CIRCUMFERENCE} ${RING_CIRCUMFERENCE}`;

  function updateProgressRing(count, limit) {
    const fraction = limit > 0 ? Math.min(1, count / limit) : 0;
    const offset = RING_CIRCUMFERENCE - (fraction * RING_CIRCUMFERENCE);
    progressRingBar.style.strokeDashoffset = offset;

    if (count >= limit) {
      progressRingBar.style.stroke = '#ef4444';
      statusBadge.textContent = 'Limit Reached';
      statusBadge.classList.add('limit-hit');
    } else if (count >= limit * 0.75) {
      progressRingBar.style.stroke = '#f59e0b';
      statusBadge.textContent = 'Near Limit';
      statusBadge.classList.remove('limit-hit');
    } else {
      progressRingBar.style.stroke = '#6366f1';
      statusBadge.textContent = 'Active';
      statusBadge.classList.remove('limit-hit');
    }
  }

  function showToastNotice(msg = 'Settings saved successfully!') {
    saveToast.textContent = msg;
    saveToast.classList.add('show');
    setTimeout(() => {
      saveToast.classList.remove('show');
    }, 2000);
  }

  // Load status from background
  async function loadStatus() {
    try {
      const response = await chrome.runtime.sendMessage({ type: 'GET_STATUS' });
      if (!response || !response.success) return;

      const { settings, today } = response;

      totalCountEl.textContent = today.total || 0;
      captionLimitEl.textContent = settings.dailyLimit || 20;
      dailyLimitInput.value = settings.dailyLimit || 20;
      dailyLimitVal.textContent = settings.dailyLimit || 20;

      valYoutube.textContent = today.youtube || 0;
      valInstagram.textContent = today.instagram || 0;
      valSnapchat.textContent = today.snapchat || 0;

      reminderIntervalInput.value = settings.reminderInterval || 10;
      limitActionSelect.value = settings.limitAction || 'block_page';

      checkYoutube.checked = Boolean(settings.platforms?.youtube);
      checkInstagram.checked = Boolean(settings.platforms?.instagram);
      checkSnapchat.checked = Boolean(settings.platforms?.snapchat);

      updateProgressRing(today.total || 0, settings.dailyLimit || 20);
    } catch (err) {
      console.error('Error fetching status:', err);
    }
  }

  dailyLimitInput.addEventListener('input', (e) => {
    dailyLimitVal.textContent = e.target.value;
    captionLimitEl.textContent = e.target.value;
    const currentCount = parseInt(totalCountEl.textContent, 10) || 0;
    updateProgressRing(currentCount, parseInt(e.target.value, 10));
  });

  saveBtn.addEventListener('click', async () => {
    const updatedSettings = {
      dailyLimit: parseInt(dailyLimitInput.value, 10) || 20,
      reminderInterval: parseInt(reminderIntervalInput.value, 10) || 10,
      limitAction: limitActionSelect.value,
      platforms: {
        youtube: checkYoutube.checked,
        instagram: checkInstagram.checked,
        snapchat: checkSnapchat.checked
      }
    };

    try {
      const res = await chrome.runtime.sendMessage({
        type: 'UPDATE_SETTINGS',
        settings: updatedSettings
      });
      if (res && res.success) {
        showToastNotice('Settings saved!');
      }
    } catch (err) {
      console.error('Save error:', err);
    }
  });

  resetTodayBtn.addEventListener('click', async () => {
    if (!confirm("Are you sure you want to reset today's watch count to 0?")) return;

    try {
      const res = await chrome.runtime.sendMessage({ type: 'RESET_TODAY' });
      if (res && res.success) {
        totalCountEl.textContent = '0';
        valYoutube.textContent = '0';
        valInstagram.textContent = '0';
        valSnapchat.textContent = '0';
        const limit = parseInt(dailyLimitInput.value, 10) || 20;
        updateProgressRing(0, limit);
        showToastNotice("Today's count reset to 0");
      }
    } catch (err) {
      console.error('Reset error:', err);
    }
  });

  await loadStatus();
});
