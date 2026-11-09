package com.github.raphaelluethy.codesteps.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.awt.Color

/** How the text of one hunk changes on screen. */
enum class InsertMode { FADE, TYPE, NONE }

/**
 * Transition styles. Hunk styles treat each block of changed lines as one unit;
 * word styles animate only the changed words inside a hunk.
 */
enum class TransitionStyle(
    private val displayName: String,
    val wholeHunks: Boolean,
    val insertMode: InsertMode,
    val fadesOut: Boolean,
) {
    HUNKS("Hunks: fade out old block, fade in new block", true, InsertMode.FADE, true),
    HUNKS_TYPED("Hunks: fade out old block, type new block", true, InsertMode.TYPE, true),
    HIGHLIGHT("Highlight: no motion, mark changed lines", true, InsertMode.NONE, false),
    FADE("Words: fade out and fade in changed words", false, InsertMode.FADE, true),
    TYPEWRITER("Words: fade out changed words, type new words", false, InsertMode.TYPE, true),
    INSTANT("Instant: no motion, no highlight", true, InsertMode.NONE, false);

    override fun toString() = displayName
}

/** Personal presentation preferences. Presentations themselves live in the project, see `PresentationStorage`. */
@Service(Service.Level.APP)
@State(name = "CodeStepsSettings", storages = [Storage("codeSteps.xml")])
class CodeStepsSettings : SimplePersistentStateComponent<CodeStepsSettings.State>(State()) {
    class State : BaseState() {
        var transition by enum(TransitionStyle.HUNKS)
        var highlightChanges by property(true)
        /** Marker color as `RRGGBB`. */
        var highlightColor by string(DEFAULT_HIGHLIGHT_COLOR)
        var fadeDurationMs by property(900)
        var typingCharsPerSecond by property(35)
        var maxTypingDurationMs by property(6000)
        var hunkPauseMs by property(800)
        /** Playback speed in percent; scales every duration and pause. */
        var speedPercent by property(100)
        var playAcrossSteps by property(false)
        var restoreOnStop by property(true)
        var projectorMode by property(false)
        var projectorFontSize by property(28)
        var projectorHideToolWindows by property(true)
    }

    companion object {
        const val DEFAULT_HIGHLIGHT_COLOR = "FFE600"
        val SPEEDS = listOf(50, 75, 100, 150, 200, 300)

        fun getInstance(): CodeStepsSettings = service()

        fun parseColor(hex: String?): Color =
            hex?.removePrefix("#")?.toIntOrNull(16)?.let { Color(it) } ?: Color(DEFAULT_HIGHLIGHT_COLOR.toInt(16))

        fun formatColor(color: Color): String = "%06X".format(color.rgb and 0xFFFFFF)

        fun formatSpeed(percent: Int): String =
            (if (percent % 100 == 0) "${percent / 100}" else "%.2f".format(percent / 100.0).trimEnd('0')) + "×"
    }
}
