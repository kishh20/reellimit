package com.reellimit.app

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("reellimit_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_DAILY_LIMIT = "daily_limit"
        private const val KEY_REMINDER_INTERVAL = "reminder_interval"
        private const val KEY_ENABLE_YOUTUBE = "enable_youtube"
        private const val KEY_ENABLE_INSTAGRAM = "enable_instagram"
        private const val KEY_ENABLE_SNAPCHAT = "enable_snapchat"

        private const val KEY_SAVED_DATE = "saved_date"
        private const val KEY_COUNT_TOTAL = "count_total"
        private const val KEY_COUNT_YOUTUBE = "count_youtube"
        private const val KEY_COUNT_INSTAGRAM = "count_instagram"
        private const val KEY_COUNT_SNAPCHAT = "count_snapchat"

        const val DEFAULT_DAILY_LIMIT = 20
        const val DEFAULT_REMINDER_INTERVAL = 10
    }

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun checkAndResetIfNewDay() {
        val today = getTodayDateString()
        val savedDate = prefs.getString(KEY_SAVED_DATE, "")
        if (savedDate != today) {
            prefs.edit()
                .putString(KEY_SAVED_DATE, today)
                .putInt(KEY_COUNT_TOTAL, 0)
                .putInt(KEY_COUNT_YOUTUBE, 0)
                .putInt(KEY_COUNT_INSTAGRAM, 0)
                .putInt(KEY_COUNT_SNAPCHAT, 0)
                .apply()
        }
    }

    var dailyLimit: Int
        get() = prefs.getInt(KEY_DAILY_LIMIT, DEFAULT_DAILY_LIMIT)
        set(value) = prefs.edit().putInt(KEY_DAILY_LIMIT, value).apply()

    var reminderInterval: Int
        get() = prefs.getInt(KEY_REMINDER_INTERVAL, DEFAULT_REMINDER_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_REMINDER_INTERVAL, value).apply()

    var isYoutubeEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_YOUTUBE, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_YOUTUBE, value).apply()

    var isInstagramEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_INSTAGRAM, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_INSTAGRAM, value).apply()

    var isSnapchatEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_SNAPCHAT, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_SNAPCHAT, value).apply()

    fun getTotalWatched(): Int {
        checkAndResetIfNewDay()
        return prefs.getInt(KEY_COUNT_TOTAL, 0)
    }

    fun getYoutubeWatched(): Int {
        checkAndResetIfNewDay()
        return prefs.getInt(KEY_COUNT_YOUTUBE, 0)
    }

    fun getInstagramWatched(): Int {
        checkAndResetIfNewDay()
        return prefs.getInt(KEY_COUNT_INSTAGRAM, 0)
    }

    fun getSnapchatWatched(): Int {
        checkAndResetIfNewDay()
        return prefs.getInt(KEY_COUNT_SNAPCHAT, 0)
    }

    fun isLimitReached(): Boolean {
        return getTotalWatched() >= dailyLimit
    }

    @Synchronized
    fun incrementCount(platform: String): Int {
        checkAndResetIfNewDay()
        val total = prefs.getInt(KEY_COUNT_TOTAL, 0) + 1
        val editor = prefs.edit()
        editor.putInt(KEY_COUNT_TOTAL, total)

        when (platform) {
            "youtube" -> editor.putInt(KEY_COUNT_YOUTUBE, prefs.getInt(KEY_COUNT_YOUTUBE, 0) + 1)
            "instagram" -> editor.putInt(KEY_COUNT_INSTAGRAM, prefs.getInt(KEY_COUNT_INSTAGRAM, 0) + 1)
            "snapchat" -> editor.putInt(KEY_COUNT_SNAPCHAT, prefs.getInt(KEY_COUNT_SNAPCHAT, 0) + 1)
        }
        editor.apply()
        return total
    }

    fun resetToday() {
        val today = getTodayDateString()
        prefs.edit()
            .putString(KEY_SAVED_DATE, today)
            .putInt(KEY_COUNT_TOTAL, 0)
            .putInt(KEY_COUNT_YOUTUBE, 0)
            .putInt(KEY_COUNT_INSTAGRAM, 0)
            .putInt(KEY_COUNT_SNAPCHAT, 0)
            .apply()
    }
}
