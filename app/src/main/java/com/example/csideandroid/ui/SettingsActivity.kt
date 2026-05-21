package com.example.csideandroid.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.csideandroid.R
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.core.content.edit

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "editor_prefs"
        const val KEY_VISIBLE_INDENTS = "visible_indents"
        const val KEY_SPELL_CHECK = "spell_check_enabled"
        const val KEY_RESET_ON_LAUNCH = "runner_reset_on_launch"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Apply right navigation bar inset for landscape mode
        val root = findViewById<LinearLayout>(R.id.settingsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, navInsets.right + v.paddingLeft, v.paddingBottom)
            insets
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        findViewById<Button>(R.id.btnSettingsBack).setOnClickListener { finish() }

        val switchVisibleIndents = findViewById<SwitchMaterial>(R.id.switchVisibleIndents)
        switchVisibleIndents.isChecked = prefs.getBoolean(KEY_VISIBLE_INDENTS, true)
        switchVisibleIndents.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(KEY_VISIBLE_INDENTS, isChecked) }
        }

        val switchSpellCheck = findViewById<SwitchMaterial>(R.id.switchSpellCheck)
        switchSpellCheck.isChecked = prefs.getBoolean(KEY_SPELL_CHECK, true)
        switchSpellCheck.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(KEY_SPELL_CHECK, isChecked) }
        }

        val switchResetOnLaunch = findViewById<SwitchMaterial>(R.id.switchResetOnLaunch)
        switchResetOnLaunch.isChecked = prefs.getBoolean(KEY_RESET_ON_LAUNCH, true)
        switchResetOnLaunch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(KEY_RESET_ON_LAUNCH, isChecked) }
        }
    }
}
