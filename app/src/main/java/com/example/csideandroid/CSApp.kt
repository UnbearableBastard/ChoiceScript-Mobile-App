package com.example.csideandroid

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.appcompat.app.AppCompatDelegate

// Applies a consistent status bar background across the whole app and prevents UI from overlapping the system status icons.
class CSApp : Application() {

    companion object {
        const val PREFS_NAME = "app_prefs"
        const val KEY_NIGHT_MODE = "night_mode_override"
    }

    override fun onCreate() {
        super.onCreate()

        // Apply the saved light/dark override (if any) before any screen opens.
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedMode = prefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedMode)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Do not draw behind system bars
                WindowCompat.setDecorFitsSystemWindows(activity.window, true)

                // Set status bar background color
                activity.window.statusBarColor =
                    ContextCompat.getColor(activity, R.color.status_bar)

                // Keep light icons (white) for dark status bar
                val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                controller.isAppearanceLightStatusBars = false
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}