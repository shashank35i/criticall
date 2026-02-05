package com.simats.criticall

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {
    private var lastVolDownMs: Long = 0L

    // Apply selected language to every Activity automatically
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupRootBackBehavior()
    }

    override fun onResume() {
        super.onResume()
        try {
            AssistantBarController.attach(this)
        } catch (_: Throwable) {
        }
    }

    override fun onPause() {
        try {
            AssistantBarController.detach(this)
        } catch (_: Throwable) {
        }
        super.onPause()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Secret shortcut: double-press Volume Down to show Assistant bar
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            val now = System.currentTimeMillis()
            if (now - lastVolDownMs <= 450L) {
                try {
                    AssistantBarController.forceShow(this)
                } catch (_: Throwable) {
                }
                lastVolDownMs = 0L
                return true // consume second press
            }
            lastVolDownMs = now
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Root-back behavior:
     * - If this Activity is task root AND user is logged in -> close app (move to background)
     * - If task root AND not logged in -> go to RoleSelectActivity
     * - Otherwise -> normal back
     */
    private fun setupRootBackBehavior() {
        onBackPressedDispatcher.addCallback(this) {
            // Not root => normal back
            if (!isTaskRoot) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                return@addCallback
            }

            // Root screen
            val token = (AppPrefs.getToken(this@BaseActivity) ?: "").trim()

            if (token.isNotEmpty()) {
                // Logged in root (dashboard): close/minimize app instead of navigating to RoleSelect
                moveTaskToBack(true)
            } else {
                // Not logged in root: go RoleSelect (your strict rule)
                startActivity(Intent(this@BaseActivity, RoleSelectActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    /** Forward navigation (Next) */
    protected fun goNext(intent: Intent, finishThis: Boolean = false) {
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        if (finishThis) finish()
    }

    /** Back navigation */
    protected fun goBack(intent: Intent? = null) {
        if (intent != null) {
            startActivity(intent)
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            finish()
        } else {
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
    }

    override fun finish() {
        super.finish()
        // If caller didn't use goBack(), still keep it smooth:
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
