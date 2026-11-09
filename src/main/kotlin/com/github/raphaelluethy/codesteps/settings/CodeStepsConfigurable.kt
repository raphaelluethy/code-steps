package com.github.raphaelluethy.codesteps.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected

class CodeStepsConfigurable : BoundConfigurable("Code Steps") {
    override fun createPanel(): DialogPanel {
        val state = CodeStepsSettings.getInstance().state
        lateinit var tint: Cell<JBCheckBox>
        return panel {
            row {
                checkBox("Animate changes:").bindSelected(state::animate)
                spinner(0..10000, 100).bindIntValue(state::fadeDurationMs)
                label("ms to fade old code out, and again to fade new code in")
            }
            row {
                tint = checkBox("Tint changed lines:").bindSelected(state::highlightChanges)
                val colorPanel = ColorPanel()
                cell(colorPanel)
                    .onReset { colorPanel.selectedColor = CodeStepsSettings.parseColor(state.highlightColor) }
                    .onIsModified { colorPanel.selectedColor != CodeStepsSettings.parseColor(state.highlightColor) }
                    .onApply { colorPanel.selectedColor?.let { state.highlightColor = CodeStepsSettings.formatColor(it) } }
            }
            indent {
                row {
                    checkBox("Use the diff colors of the editor color scheme")
                        .bindSelected(state::useDiffColors)
                        .comment("Old code fades out in the deleted or modified color, new code keeps the inserted or modified color.")
                }
            }.enabledIf(tint.selected)
            row("Play pauses between hunks for:") {
                spinner(0..10000, 100).bindIntValue(state::hunkPauseMs)
                label("ms")
            }
            row {
                checkBox("Play continues into the next step").bindSelected(state::playAcrossSteps)
            }
            row("Projector mode font size:") {
                spinner(8..96, 1).bindIntValue(state::projectorFontSize)
                label("pt, only for the presented file")
            }
        }
    }
}
