package com.reellimit.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LockOverlayActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_overlay)

        val prefs = PreferencesManager(this)
        val limit = intent.getIntExtra("limit", prefs.dailyLimit)

        val tvLockMessage = findViewById<TextView>(R.id.tvLockMessage)
        tvLockMessage.text = "You have watched your daily limit of $limit short videos.\n\nFeed access has been closed to safeguard your attention and productivity."

        findViewById<Button>(R.id.btnBackToHome).setOnClickListener {
            goToHomeScreen()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Prevent backing into the blocked app: send user to Home Screen
        goToHomeScreen()
    }

    private fun goToHomeScreen() {
        val startMain = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(startMain)
        finish()
    }
}
