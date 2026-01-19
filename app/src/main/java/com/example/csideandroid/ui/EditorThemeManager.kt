package com.example.csideandroid.ui

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.core.content.edit

enum class EditorTheme {
    CLASSIC,
    DARK,
    SOLARIZED,
    NORD,
    MONOKAI,
    DRACULA,
    STAR_WARS_EMPIRE,
    DUNE_ARRAKIS,
    CYBERPUNK_NIGHTCITY,
    JEDI_ORDER,
    FALLOUT_PIPBOY,
    MASS_EFFECT_N7,
    HP_GRYFFINDOR,
    HP_HUFFLEPUFF,
    HP_RAVENCLAW,
    HP_SLYTHERIN,
    DESERT_DUNES,
    NO_MANS_SKY
}

data class EditorThemeColors(
    val background: Int,
    val text: Int,
    val lineNumber: Int,
    val optionColor: Int,
    val inlineVarColor: Int,
    val defaultCommandColor: Int,
    val commandColors: Map<String, Int>
)

object EditorThemeManager {

    private const val PREFS_NAME = "editor_prefs"

    private const val KEY_THEME = "editor_theme"

    // User overrides for theme colors
    private const val KEY_OVR_PREFIX = "theme_override_"

    private fun ovrKey(theme: EditorTheme, field: String): String {
        return KEY_OVR_PREFIX + theme.name + "_" + field
    }

    fun getColorsForSora(context: Context, theme: EditorTheme): EditorThemeColors {
        val base = colorsFor(theme)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun getInt(field: String, fallback: Int): Int {
            return if (prefs.contains(ovrKey(theme, field))) prefs.getInt(ovrKey(theme, field), fallback) else fallback
        }

        return base.copy(
            background = getInt("background", base.background),
            text = getInt("text", base.text),
            lineNumber = getInt("lineNumber", base.lineNumber),
            optionColor = getInt("optionColor", base.optionColor),
            inlineVarColor = getInt("inlineVarColor", base.inlineVarColor),
            defaultCommandColor = getInt("defaultCommandColor", base.defaultCommandColor)
        )
    }

    fun saveThemeOverride(context: Context, theme: EditorTheme, field: String, color: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putInt(ovrKey(theme, field), color) }
    }

    fun clearThemeOverrides(context: Context, theme: EditorTheme) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            remove(ovrKey(theme, "background"))
            remove(ovrKey(theme, "text"))
            remove(ovrKey(theme, "lineNumber"))
            remove(ovrKey(theme, "optionColor"))
            remove(ovrKey(theme, "inlineVarColor"))
            remove(ovrKey(theme, "defaultCommandColor"))
        }
    }

    fun getCurrentTheme(context: Context): EditorTheme {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_THEME, EditorTheme.CLASSIC.name) ?: EditorTheme.CLASSIC.name

        return runCatching {
            EditorTheme.valueOf(name)
        }.getOrElse {
            // Clean invalid legacy themes if any
            prefs.edit { putString(KEY_THEME, EditorTheme.CLASSIC.name) }
            EditorTheme.CLASSIC
        }
    }

    fun setCurrentTheme(context: Context, theme: EditorTheme) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_THEME, theme.name) }
    }

    fun colorsFor(theme: EditorTheme): EditorThemeColors {
        return when (theme) {

            // CLASSIC — white background & rainbow commands
            EditorTheme.CLASSIC -> EditorThemeColors(
                background = "#FFFFFF".toColorInt(),
                text = "#000000".toColorInt(),
                lineNumber = "#888888".toColorInt(),
                optionColor = "#9CDCFE".toColorInt(),
                inlineVarColor = "#FFD479".toColorInt(),
                defaultCommandColor = "#80E7F3".toColorInt(),
                commandColors = rainbowCommandColors()
            )


            // DARK + rainbow commands
            EditorTheme.DARK -> EditorThemeColors(
                background = "#1E1E1E".toColorInt(),
                text = "#D4D4D4".toColorInt(),
                lineNumber = "#858585".toColorInt(),
                optionColor = "#9CDCFE".toColorInt(),
                inlineVarColor = "#FFD479".toColorInt(),
                defaultCommandColor = "#80E7F3".toColorInt(),
                commandColors = rainbowCommandColors()
            )

            // SOLARIZED
            EditorTheme.SOLARIZED -> {
                val flow     = "#268BD2".toColorInt() // blue
                val logic    = "#B58900".toColorInt() // yellow
                val state    = "#2AA198".toColorInt() // cyan
                val input    = "#CB4B16".toColorInt() // orange
                val present  = "#6C71C4".toColorInt() // violet
                val meta     = "#859900".toColorInt() // green

                EditorThemeColors(
                    background = "#FDF6E3".toColorInt(),
                    text = "#657B83".toColorInt(),
                    lineNumber = "#93A1A1".toColorInt(),
                    optionColor = "#2AA198".toColorInt(),   // cyan
                    inlineVarColor = "#B58900".toColorInt(), // yellow
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }

            // NORD
            EditorTheme.NORD -> {
                val flow     = "#88C0D0".toColorInt() // nord8
                val logic    = "#EBCB8B".toColorInt() // nord13
                val state    = "#A3BE8C".toColorInt() // nord14
                val input    = "#D08770".toColorInt() // nord12
                val present  = "#81A1C1".toColorInt() // nord9
                val meta     = "#B48EAD".toColorInt() // nord15

                EditorThemeColors(
                    background = "#2E3440".toColorInt(), // nord0
                    text = "#D8DEE9".toColorInt(),       // nord4
                    lineNumber = "#4C566A".toColorInt(), // nord3
                    optionColor = "#81A1C1".toColorInt(),
                    inlineVarColor = "#EBCB8B".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }

            // MONOKAI
            EditorTheme.MONOKAI -> {
                val flow     = "#A6E22E".toColorInt() // green
                val logic    = "#E6DB74".toColorInt() // yellow
                val state    = "#66D9EF".toColorInt() // aqua
                val input    = "#FD971F".toColorInt() // orange
                val present  = "#AE81FF".toColorInt() // purple
                val meta     = "#F92672".toColorInt() // pink/red

                EditorThemeColors(
                    background = "#272822".toColorInt(),
                    text = "#F8F8F2".toColorInt(),
                    lineNumber = "#75715E".toColorInt(),
                    optionColor = "#66D9EF".toColorInt(),
                    inlineVarColor = "#FD971F".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }

            // DRACULA
            EditorTheme.DRACULA -> {
                val flow     = "#8BE9FD".toColorInt() // cyan
                val logic    = "#F1FA8C".toColorInt() // yellow
                val state    = "#50FA7B".toColorInt() // green
                val input    = "#FFB86C".toColorInt() // orange
                val present  = "#BD93F9".toColorInt() // purple
                val meta     = "#FF79C6".toColorInt() // pink

                EditorThemeColors(
                    background = "#282A36".toColorInt(),
                    text = "#F8F8F2".toColorInt(),
                    lineNumber = "#44475A".toColorInt(),
                    optionColor = "#50FA7B".toColorInt(),
                    inlineVarColor = "#FFB86C".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }

            // STAR WARS — Empire
            EditorTheme.STAR_WARS_EMPIRE -> {
                val flow     = "#D21404".toColorInt() // Sith Red
                // Keep logic commands distinct from normal text.
                // This theme previously used the same value as `text`, making *if/*else blend in.
                val logic    = "#BFC5CA".toColorInt() // Imperial grey (darker than text)
                val state    = "#9AA0A6".toColorInt() // Cold Steel
                val input    = "#A81D1D".toColorInt() // Dark Red
                val present  = "#5A6268".toColorInt() // Gunmetal
                val meta     = "#FF4545".toColorInt() // Aggressive red

                EditorThemeColors(
                    background = "#0A0A0A".toColorInt(),
                    text = "#E0E0E0".toColorInt(),
                    lineNumber = "#555555".toColorInt(),
                    optionColor = "#FF4D4D".toColorInt(),
                    inlineVarColor = "#B47B7B".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }

            // DUNE — ARRAKIS
            EditorTheme.DUNE_ARRAKIS -> {
                val flow     = "#FF8C2B".toColorInt() // spice orange
                // Keep logic commands distinct from normal text.
                val logic    = "#405B8F".toColorInt() // arrakis blue
                val state    = "#D5B887".toColorInt() // dune brown
                val input    = "#C6711D".toColorInt() // deep spice
                val present  = "#405B8F".toColorInt() // arrakis blue
                val meta     = "#E67E22".toColorInt() // orange-gold

                EditorThemeColors(
                    background = "#2A1F0F".toColorInt(),
                    text = "#EED7A1".toColorInt(),
                    lineNumber = "#6A5740".toColorInt(),
                    optionColor = "#FF8C2B".toColorInt(),
                    inlineVarColor = "#C6711D".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }

            // CYBERPUNK 2077 — NIGHT CITY
            EditorTheme.CYBERPUNK_NIGHTCITY -> {
                val flow     = "#00F0FF".toColorInt() // neon cyan
                // Keep logic commands distinct from normal text.
                val logic    = "#00F0FF".toColorInt() // neon cyan
                val state    = "#FF00C8".toColorInt() // neon magenta
                val input    = "#00C0A3".toColorInt() // neon teal
                val present  = "#A700FF".toColorInt() // neon purple
                val meta     = "#FF007A".toColorInt() // neon pink

                EditorThemeColors(
                    background = "#0F0F12".toColorInt(),
                    text = "#FDF700".toColorInt(),
                    lineNumber = "#3A3A45".toColorInt(),
                    optionColor = "#00F0FF".toColorInt(),
                    inlineVarColor = "#FF00C8".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }


            // JEDI ORDER
            EditorTheme.JEDI_ORDER -> {
                val flow     = "#4DA6FF".toColorInt() // Jedi blue
                val logic    = "#D9B566".toColorInt() // soft gold
                val state    = "#A0DFFC".toColorInt() // pale holocron blue
                val input    = "#7FD8E8".toColorInt() // gentle cyan
                val present  = "#A6B5FF".toColorInt() // light holocron purple
                val meta     = "#73C9FF".toColorInt() // saber-glow blue

                EditorThemeColors(
                    background = "#1C2635".toColorInt(), // Jedi Archives
                    text = "#E7ECF4".toColorInt(),
                    lineNumber = "#4B566A".toColorInt(),
                    optionColor = "#3C9DFF".toColorInt(),
                    inlineVarColor = "#BBD1FF".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }

            // FALLOUT — PIP-BOY 3000 — Probably not useable but it's fun
            EditorTheme.FALLOUT_PIPBOY -> {
                val flow     = "#14FF3A".toColorInt() // core green
                val logic    = "#8AFFA2".toColorInt() // softer green
                val state    = "#37FF6A".toColorInt()
                val input    = "#52FF8C".toColorInt()
                val present  = "#9CFFC2".toColorInt()
                val meta     = "#C3FFD7".toColorInt()

                EditorThemeColors(
                    background = "#001B00".toColorInt(),
                    text = "#14FF3A".toColorInt(),
                    lineNumber = "#0C3B10".toColorInt(),
                    optionColor = "#37FF6A".toColorInt(),
                    inlineVarColor = "#8AFFA2".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }

            // MASS EFFECT — N7 TACTICAL
            EditorTheme.MASS_EFFECT_N7 -> {
                val flow     = "#D12A2A".toColorInt() // N7 red
                // Keep logic commands distinct from normal text.
                val logic    = "#7FA8FF".toColorInt() // blue HUD
                val state    = "#7FA8FF".toColorInt() // blue HUD
                val input    = "#FF6B3A".toColorInt() // warning orange
                val present  = "#999999".toColorInt() // gunmetal
                val meta     = "#FFEC80".toColorInt() // highlight

                EditorThemeColors(
                    background = "#0A0A0C".toColorInt(),
                    text = "#E6E6E6".toColorInt(),
                    lineNumber = "#44444C".toColorInt(),
                    optionColor = "#D12A2A".toColorInt(),
                    inlineVarColor = "#7FA8FF".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }

            // HARRY POTTER — GRYFFINDOR
            EditorTheme.HP_GRYFFINDOR -> {
                val flow     = "#A00000".toColorInt() // scarlet
                val logic    = "#F2C94C".toColorInt() // gold
                val state    = "#E5A07A".toColorInt()
                val input    = "#FF7B5C".toColorInt()
                val present  = "#D98C4C".toColorInt()
                val meta     = "#FFE39A".toColorInt()

                EditorThemeColors(
                    background = "#1A0C0C".toColorInt(),
                    text = "#F8F0E8".toColorInt(),
                    lineNumber = "#5C3232".toColorInt(),
                    optionColor = "#F2C94C".toColorInt(),
                    inlineVarColor = "#FFD27F".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }

            // HARRY POTTER — HUFFLEPUFF
            EditorTheme.HP_HUFFLEPUFF -> {
                val flow     = "#F7D633".toColorInt() // yellow
                val logic    = "#201F1E".toColorInt() // black
                val state    = "#FFEAA0".toColorInt()
                val input    = "#E0C15C".toColorInt()
                val present  = "#807666".toColorInt()
                val meta     = "#FFF5C4".toColorInt()

                EditorThemeColors(
                    background = "#18120A".toColorInt(),
                    text = "#FDF7E3".toColorInt(),
                    lineNumber = "#4C3C22".toColorInt(),
                    optionColor = "#F7D633".toColorInt(),
                    inlineVarColor = "#FFEAA0".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }

            // HARRY POTTER — RAVENCLAW
            EditorTheme.HP_RAVENCLAW -> {
                val flow     = "#1A3D7C".toColorInt() // blue
                val logic    = "#B87333".toColorInt() // bronze
                val state    = "#9CB6E5".toColorInt()
                val input    = "#4C739F".toColorInt()
                val present  = "#C48A4A".toColorInt()
                val meta     = "#E8C39E".toColorInt()

                EditorThemeColors(
                    background = "#0C1220".toColorInt(),
                    text = "#E4E9F7".toColorInt(),
                    lineNumber = "#35405C".toColorInt(),
                    optionColor = "#1A3D7C".toColorInt(),
                    inlineVarColor = "#B87333".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }

            // HARRY POTTER — SLYTHERIN
            EditorTheme.HP_SLYTHERIN -> {
                val flow     = "#1E6F43".toColorInt() // green
                // Keep logic commands distinct from normal text.
                val logic    = "#9FB4B3".toColorInt() // silver (darker)
                val state    = "#88C9A1".toColorInt()
                val input    = "#2F8F5A".toColorInt()
                val present  = "#9FB4B3".toColorInt()
                val meta     = "#E5F2F1".toColorInt()

                EditorThemeColors(
                    background = "#07130B".toColorInt(),
                    text = "#E6F3EC".toColorInt(),
                    lineNumber = "#30463A".toColorInt(),
                    optionColor = "#1E6F43".toColorInt(),
                    inlineVarColor = "#C9D6D5".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }


            // JOURNEY — DESERT DUNES
            EditorTheme.DESERT_DUNES -> {
                val flow     = "#C85E1F".toColorInt() // cloth-red
                val logic    = "#E5B45C".toColorInt() // sun-gold
                val state    = "#B38750".toColorInt() // desert tan
                val input    = "#FCE2A6".toColorInt() // pastel light
                val present  = "#B46A33".toColorInt() // sunset orange
                val meta     = "#6F4C2B".toColorInt() // deep dune brown

                EditorThemeColors(
                    background = "#F2E3B5".toColorInt(), // pale sand
                    text = "#4A3E2B".toColorInt(),       // warm brown ink
                    lineNumber = "#A58F6A".toColorInt(),
                    optionColor = "#C85E1F".toColorInt(),
                    inlineVarColor = "#E5B45C".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }

            // NO MAN'S SKY — ATLAS
            EditorTheme.NO_MANS_SKY -> {
                val flow     = "#FF5E79".toColorInt() // coral neon
                val logic    = "#6DDAF2".toColorInt() // pastel aqua
                val state    = "#FFCB6B".toColorInt() // starlight gold
                val input    = "#9AEFFF".toColorInt() // nebula blue
                val present  = "#E387FF".toColorInt() // lavender nova
                val meta     = "#F4F4F4".toColorInt() // starlight white

                EditorThemeColors(
                    background = "#1B0C15".toColorInt(), // deep cosmic wine
                    text = "#FFFFFF".toColorInt(),
                    lineNumber = "#52354A".toColorInt(),
                    optionColor = "#6DDAF2".toColorInt(),
                    inlineVarColor = "#FFCB6B".toColorInt(),
                    defaultCommandColor = flow,
                    commandColors = buildCategorizedCommandMap(
                        flowColor = flow,
                        logicColor = logic,
                        stateColor = state,
                        inputColor = input,
                        presentationColor = present,
                        metaColor = meta
                    )
                )
            }
        }
    }

    // Rainbow map for DARK & CLASSIC themes
    private fun rainbowCommandColors(): Map<String, Int> = mapOf(
        "achieve" to "#E55B5B".toColorInt(),
        "achievement" to "#E56A5B".toColorInt(),
        "check_achievements" to "#E5785B".toColorInt(),
        "choice" to "#E5875B".toColorInt(),
        "fake_choice" to "#E5955B".toColorInt(),
        "disable_reuse" to "#E5A45B".toColorInt(),
        "hide_reuse" to "#E5B25B".toColorInt(),
        "allow_reuse" to "#E5C15B".toColorInt(),
        "selectable_if" to "#E5CF5B".toColorInt(),
        "create" to "#E5DE5B".toColorInt(),
        "create_array" to "#DEE55B".toColorInt(),
        "temp" to "#CFE55B".toColorInt(),
        "temp_array" to "#C1E55B".toColorInt(),
        "set" to "#FF0000".toColorInt(),
        "setref" to "#A4E55B".toColorInt(),
        "delete" to "#95E55B".toColorInt(),
        "input_number" to "#87E55B".toColorInt(),
        "input_text" to "#78E55B".toColorInt(),
        "print" to "#6AE55B".toColorInt(),
        "rand" to "#5BE55B".toColorInt(),
        "if" to "#5BE56A".toColorInt(),
        "elseif" to "#5BE578".toColorInt(),
        "else" to "#5BE587".toColorInt(),
        "elsif" to "#5BE595".toColorInt(),
        "return" to "#5BE5A4".toColorInt(),
        "params" to "#5BE5B2".toColorInt(),
        "label" to "#5BE5C1".toColorInt(),
        "goto" to "#5BE5CF".toColorInt(),
        "goto_scene" to "#5BE5DE".toColorInt(),
        "goto_random_scene" to "#5BDDE5".toColorInt(),
        "gosub" to "#5BCFE5".toColorInt(),
        "gosub_scene" to "#5BC1E5".toColorInt(),
        "finish" to "#5BB2E5".toColorInt(),
        "ending" to "#5BA4E5".toColorInt(),
        "redirect_scene" to "#5B95E5".toColorInt(),
        "image" to "#5B87E5".toColorInt(),
        "line_break" to "#5B78E5".toColorInt(),
        "page_break" to "#5B6AE5".toColorInt(),
        "link" to "#5B5BE5".toColorInt(),
        "stat_chart" to "#6A5BE5".toColorInt(),
        "bold" to "#785BE5".toColorInt(),
        "italic" to "#875BE5".toColorInt(),
        "sound" to "#955BE5".toColorInt(),
        "script" to "#A45BE5".toColorInt(),
        "scene_list" to "#B25BE5".toColorInt(),
        "pause" to "#C15BE5".toColorInt(),
        "title" to "#CF5BE5".toColorInt(),
        "author" to "#DE5BE5".toColorInt(),
        "ifid" to "#E55BDD".toColorInt(),
        "save_checkpoint" to "#E55BCF".toColorInt(),
        "restore_checkpoint" to "#E55BC1".toColorInt(),
        "comment" to "#E55BB2".toColorInt(),
        "bug" to "#E55BA4".toColorInt(),
        "looplimit" to "#E55B95".toColorInt(),
        "more_games" to "#E55B87".toColorInt(),
        "share_this_game" to "#E55B78".toColorInt(),
        "show_password" to "#E55B6A".toColorInt()
    )

    // Category-based maps for the other themes
    private fun buildCategorizedCommandMap(
        flowColor: Int,
        logicColor: Int,
        stateColor: Int,
        inputColor: Int,
        presentationColor: Int,
        metaColor: Int
    ): Map<String, Int> {
        val map = mutableMapOf<String, Int>()

        // 1. Flow & Navigation
        listOf(
            "choice",
            "fake_choice",
            "label",
            "goto",
            "goto_scene",
            "goto_random_scene",
            "gosub",
            "gosub_scene",
            "finish",
            "ending",
            "redirect_scene",
            "link"
        ).forEach { map[it] = flowColor }

        // 2. Logic & Conditions
        listOf(
            "if",
            "elseif",
            "elsif",
            "else",
            "selectable_if",
            "disable_reuse",
            "hide_reuse",
            "allow_reuse"
        ).forEach { map[it] = logicColor }

        // 3. Variables & State
        listOf(
            "create",
            "create_array",
            "temp",
            "temp_array",
            "set",
            "setref",
            "delete",
            "rand",
            "params",
            "return"
        ).forEach { map[it] = stateColor }

        // 4. Input & Interaction
        listOf(
            "input_text",
            "input_number",
            "pause"
        ).forEach { map[it] = inputColor }

        // 5. Presentation & Output
        listOf(
            "print",
            "image",
            "line_break",
            "page_break",
            "bold",
            "italic",
            "sound",
            "script",
            "stat_chart",
            "scene_list",
            "title",
            "author",
            "more_games",
            "share_this_game",
            "show_password"
        ).forEach { map[it] = presentationColor }

        // 6. Meta / Technical / Debug
        listOf(
            "achievement",
            "achieve",
            "check_achievements",
            "ifid",
            "bug",
            "comment",
            "looplimit",
            "save_checkpoint",
            "restore_checkpoint"
        ).forEach { map[it] = metaColor }

        return map
    }
}