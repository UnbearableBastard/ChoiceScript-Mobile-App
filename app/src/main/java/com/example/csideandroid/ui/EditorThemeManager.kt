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
    DRACULA
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

    fun getCurrentTheme(context: Context): EditorTheme {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // DEFAULT = CLASSIC (white + rainbow)
        val name = prefs.getString(KEY_THEME, EditorTheme.CLASSIC.name) ?: EditorTheme.CLASSIC.name
        return runCatching { EditorTheme.valueOf(name) }.getOrElse { EditorTheme.CLASSIC }
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