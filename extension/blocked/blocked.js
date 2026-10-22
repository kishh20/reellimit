/**
 * ReelLimit Blocked Screen Logic
 */

document.addEventListener('DOMContentLoaded', async () => {
  const limitTargetEl = document.getElementById('limitTarget');
  const statTotalEl = document.getElementById('statTotal');
  const statYoutubeEl = document.getElementById('statYoutube');
  const statInstagramEl = document.getElementById('statInstagram');
  const statSnapchatEl = document.getElementById('statSnapchat');
  const closeTabBtn = document.getElementById('closeTabBtn');

  const breathCircle = document.getElementById('breathCircle');
  const breathText = document.getElementById('breathText');

  // Load stats
  try {
    const response = await chrome.runtime.sendMessage({ type: 'GET_STATUS' });
    if (response && response.success) {
      const { settings, today } = response;
      limitTargetEl.textContent = settings.dailyLimit || 20;
      statTotalEl.textContent = today.total || 0;
      statYoutubeEl.textContent = today.youtube || 0;
      statInstagramEl.textContent = today.instagram || 0;
      statSnapchatEl.textContent = today.snapchat || 0;
    }
  } catch (err) {
    console.error('Error loading stats:', err);
  }

  closeTabBtn.addEventListener('click', async () => {
    try {
      const currentTab = await chrome.tabs.getCurrent();
      if (currentTab && currentTab.id) {
        await chrome.tabs.remove(currentTab.id);
      } else {
        window.close();
      }
    } catch (e) {
      window.close();
    }
  });

  // 4-7-8 Breathing Cycle
  let phase = 0; // 0: Inhale (4s), 1: Hold (4s), 2: Exhale (6s)
  function runBreathingStep() {
    if (phase === 0) {
      breathCircle.className = 'breathing-circle inhale';
      breathText.textContent = 'Breathe In';
      phase = 1;
      setTimeout(runBreathingStep, 4000);
    } else if (phase === 1) {
      breathCircle.className = 'breathing-circle hold';
      breathText.textContent = 'Hold';
      phase = 2;
      setTimeout(runBreathingStep, 4000);
    } else {
      breathCircle.className = 'breathing-circle exhale';
      breathText.textContent = 'Exhale';
      phase = 0;
      setTimeout(runBreathingStep, 5000);
    }
  }

  runBreathingStep();
});
