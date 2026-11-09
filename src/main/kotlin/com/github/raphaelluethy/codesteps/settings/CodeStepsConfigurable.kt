package com.github.raphaelluethy.codesteps.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer

class CodeStepsConfigurable : BoundConfigurable("Code Steps") {
    override fun createPanel(): DialogPanel {
        val state = CodeStepsSettings.getInstance().state
        return panel {
            group("Transitions") {
                row("Style:") {
                    comboBox(TransitionStyle.entries, textListCellRenderer { it.toString() })
                        .bindItem({ state.transition }, { state.transition = it ?: TransitionStyle.HUNKS })
                }
                row("Fade duration:") {
                    spinner(0..10000, 100).bindIntValue(state::fadeDurationMs)
                    label("ms for fading out, and again for fading in")
                }
                row("Typing speed:") {
                    spinner(1..2000, 5).bindIntValue(state::typingCharsPerSecond)
                    label("characters per second")
                }
                row("Longest typing animation:") {
                    spinner(0..60000, 500).bindIntValue(state::maxTypingDurationMs)
                    label("ms per hunk; larger hunks type faster")
                }
                row {
                    checkBox("Highlight changes (the Highlight style always does)").bindSelected(state::highlightChanges)
                }
                row("Highlight color:") {
                    val colorPanel = ColorPanel()
                    cell(colorPanel)
                        .onReset { colorPanel.selectedColor = CodeStepsSettings.parseColor(state.highlightColor) }
                        .onIsModified { colorPanel.selectedColor != CodeStepsSettings.parseColor(state.highlightColor) }
                        .onApply { colorPanel.selectedColor?.let { state.highlightColor = CodeStepsSettings.formatColor(it) } }
                    label("text on it turns black or white, whichever reads better")
                }
            }
            group("Playback") {
                row("Pause between hunks:") {
                    spinner(0..10000, 100).bindIntValue(state::hunkPauseMs)
                    label("ms")
                }
                row("Speed:") {
                    comboBox(CodeStepsSettings.SPEEDS, textListCellRenderer { CodeStepsSettings.formatSpeed(it ?: 100) })
                        .bindItem({ state.speedPercent }, { state.speedPercent = it ?: 100 })
                }
                row {
                    checkBox("Play continues into the next step").bindSelected(state::playAcrossSteps)
                }
                row {
                    checkBox("Restore the original file content when the presentation stops")
                        .bindSelected(state::restoreOnStop)
                }
            }
            group("Projector Mode") {
                row("Editor font size:") {
                    spinner(8..96, 1).bindIntValue(state::projectorFontSize)
                    label("pt, only for the presented file")
                }
                row {
                    checkBox("Hide tool windows while presenting").bindSelected(state::projectorHideToolWindows)
                }
            }
        }
    }
}
