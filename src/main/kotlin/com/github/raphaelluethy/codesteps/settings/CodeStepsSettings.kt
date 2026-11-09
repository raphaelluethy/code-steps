package com.github.raphaelluethy.codesteps.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.awt.Color

/** Personal presentation preferences. Presentations themselves live in the project, see `PresentationStorage`. */
@Service(Service.Level.APP)
@State(name = "CodeStepsSettings", storages = [Storage("codeSteps.xml")])
class CodeStepsSettings : SimplePersistentStateComponent<CodeStepsSettings.State>(State()) {
    class State : BaseState() {
        /** Off: each change appears at once. */
        var animate by property(true)
        var fadeDurationMs by property(900)
        var highlightChanges by property(true)
        /** Tint color as `RRGGBB`. */
        var highlightColor by string(DEFAULT_HIGHLIGHT_COLOR)
        /** Mark changes in the diff colors of the editor color scheme instead of [highlightColor]. */
        var useDiffColors by property(false)
        var hunkPauseMs by property(800)
        var playAcrossSteps by property(false)
        var projectorMode by property(false)
        var projectorFontSize by property(28)
    }

    companion object {
        const val DEFAULT_HIGHLIGHT_COLOR = "FFE600"

        fun getInstance(): CodeStepsSettings = service()

        fun parseColor(hex: String?): Color =
            hex?.removePrefix("#")?.toIntOrNull(16)?.let { Color(it) } ?: Color(DEFAULT_HIGHLIGHT_COLOR.toInt(16))

        fun formatColor(color: Color): String = "%06X".format(color.rgb and 0xFFFFFF)
    }
}
