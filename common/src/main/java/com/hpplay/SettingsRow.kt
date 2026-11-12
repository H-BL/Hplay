package com.hpplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.hpplay.host.R

/** Runs [block] for every edit, mirroring the one method the panel actually needs. */
internal fun EditText.onTextEdited(block: (String) -> Unit) {
    addTextChangedListener(
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                block(s?.toString().orEmpty())
            }
        },
    )
}

/**
 * The single set of row components every settings category is built from.
 *
 * Before this file the panel assembled itself from a dozen bespoke builders (a switch row, two
 * different radio-row helpers, an input row, a slider section, ...), each with its own type scale
 * and padding, which is why rows of the same kind looked different across categories. Here each
 * kind of control has exactly one implementation, so a category is a short list of calls and every
 * category shares the same metrics:
 *

 *  - [segment]                   - 2-4 mutually exclusive options in one row (or a grid)
 *  - [choiceList]                - 5+ options as wrapping chips
 *  - [slider]                    - a stepped value with a live label
 *  - [input]                     - free text
 *  - [document]                  - a picked file plus a "choose" button
 *  - [action]                    - one or more buttons for an immediate command
 *  - [danger]                    - a destructive command, fenced off from the rest
 *
 * Colours come from [SettingsPalette] and nothing here reads layout XML, so light/dark and the
 * whole palette can change without touching these builders.
 */
internal class SettingsRows(
    private val context: Context,
    private val palette: SettingsPalette,
) {
    /** Semantic tone for a status pill: green when ready, amber while working, grey when idle. */
    enum class Tone { OK, WARN, OFF }

    /** One option of a [segment] / [choiceList]. */
    data class Option(val label: String, val selected: Boolean)

    /** One button of an [action] row. */
    data class Action(
        val label: String,
        val primary: Boolean = false,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    )

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun tint(color: Int, alpha: Float): Int = Color.argb(
        (Color.alpha(color) * alpha).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun rounded(fill: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun label(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
    }

    /** Label + optional description stacked in the leading column of a row. */
    private fun leading(labelText: String, description: String?): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                label(labelText, 15f, palette.text, bold = true),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            if (!description.isNullOrBlank()) {
                addView(
                    label(description, 12.5f, palette.tertiary).apply {
                        setLineSpacing(dp(3).toFloat(), 1f)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(3) },
                )
            }
        }

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(0, dp(10), 0, dp(10))
    }

    private fun stack(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(10))
    }

    private fun leadingParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /** Zero-width, 1dp-tall filler that pushes a trailing control to the right. */
    private fun spacer(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** A grouped card holding [rows], with a hairline between neighbours. */
    fun card(rows: List<View>): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = palette.card(dp(16).toFloat())
        setPadding(dp(16), dp(2), dp(16), dp(2))
        rows.forEachIndexed { index, view ->
            addView(view, matchWrap())
            if (index != rows.lastIndex) {
                // Explicit 1dp height: a bare View has no intrinsic size, so with WRAP_CONTENT it
                // would otherwise stretch to absorb any leftover height in the column and squash
                // every row after it.
                addView(
                    divider(),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
                )
            }
        }
    }

    /** Uppercase micro-heading that names one card. */
    fun sectionTitle(text: String): TextView =
        label(text.uppercase(), 11.5f, palette.tertiary, bold = true).apply {
            letterSpacing = 0.12f
            setPadding(dp(4), 0, dp(4), dp(8))
        }

    /** A titled group: micro-heading + card. [title] may be null for a bare card. */
    fun section(title: String?, rows: List<View>): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (!title.isNullOrBlank()) addView(sectionTitle(title), matchWrap())
        addView(card(rows), matchWrap())
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(palette.divider)
    }

    // ---------------------------------------------------------------- boolean

    fun switch(
        labelText: String,
        description: String? = null,
        checked: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit,
    ): View = row().apply {
        addView(leading(labelText, description), leadingParams())
        addView(
            SwitchView(context, palette).apply {
                isChecked = checked
                isEnabled = enabled
                setOnChanged(onChange)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(16) },
        )
    }

    // ----------------------------------------------------------------- choice

    /**
     * Mutually exclusive options laid out in one row. [columns] > 1 renders a grid instead, which is
     * how a four-way choice stays a single row tall on a wide head unit.
     */
    fun segment(
        labelText: String,
        description: String? = null,
        options: List<Option>,
        columns: Int = 1,
        onSelect: (Int) -> Unit,
    ): View = stack().apply {
        addView(leading(labelText, description), matchWrap())
        addView(segmentTrack(options, columns, onSelect), matchWrap().apply { topMargin = dp(10) })
    }

    private fun segmentTrack(options: List<Option>, columns: Int, onSelect: (Int) -> Unit): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(tint(palette.trackOff, 0.55f), 12)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val lines = if (columns > 1) options.chunked(columns) else listOf(options)
        val chips = mutableListOf<TextView>()
        var index = 0
        lines.forEachIndexed { lineIndex, chunk ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { option ->
                val optionIndex = index
                val chip = segmentChip(option)
                chips += chip
                line.addView(
                    chip,
                    LinearLayout.LayoutParams(0, dp(40), 1f),
                )
                index++
            }
            if (columns > 1) {
                repeat(columns - chunk.size) {
                    line.addView(View(context), LinearLayout.LayoutParams(0, dp(40), 1f))
                }
            }
            container.addView(
                line,
                matchWrap().apply { if (lineIndex > 0) topMargin = dp(3) },
            )
        }
        chips.forEachIndexed { chipIndex, chip ->
            chip.setOnClickListener {
                onSelect(chipIndex)
                // Restyle from the tap alone: a caller that keeps its own state (and may not
                // rebuild the row) still gets immediate visual feedback.
                chips.forEachIndexed { otherIndex, other ->
                    styleSegmentChip(other, otherIndex == chipIndex)
                }
            }
        }
        return container
    }

    private fun segmentChip(option: Option): TextView = TextView(context).apply {
        text = option.label
        textSize = 13f
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setPadding(dp(6), 0, dp(6), 0)
        styleSegmentChip(this, option.selected)
        isClickable = true
        isFocusable = true
    }

    private fun styleSegmentChip(chip: TextView, selected: Boolean) {
        chip.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        if (selected) {
            chip.setTextColor(palette.accent)
            chip.background = rounded(palette.surfaceElevated, 9, palette.cardBorder)
        } else {
            chip.setTextColor(palette.secondary)
            chip.background = null
        }
    }

    /**
     * Wrapping chips for choices too wide for one [segment] line (five MFi targets, for example).
     * Two per line, same selection semantics as [segment].
     */
    fun choiceList(
        labelText: String,
        description: String? = null,
        options: List<Option>,
        onSelect: (Int) -> Unit,
    ): View = stack().apply {
        addView(leading(labelText, description), matchWrap())
        val chips = mutableListOf<TextView>()
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val lines = options.chunked(2)
                var index = 0
                lines.forEachIndexed { lineIndex, pair ->
                    val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    pair.forEachIndexed { position, option ->
                        val chip = listChip(option)
                        chips += chip
                        line.addView(
                            chip,
                            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                                if (position > 0) marginStart = dp(8)
                            },
                        )
                        index++
                    }
                    if (pair.size == 1) {
                        line.addView(
                            View(context),
                            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) },
                        )
                    }
                    addView(line, matchWrap().apply { if (lineIndex > 0) topMargin = dp(8) })
                }
            },
            matchWrap().apply { topMargin = dp(10) },
        )
        chips.forEachIndexed { chipIndex, chip ->
            chip.setOnClickListener {
                onSelect(chipIndex)
                // Restyle from the tap alone, so the selection is visible even when the caller
                // keeps its own state and rebuilds the dependent fields in place.
                chips.forEachIndexed { otherIndex, other ->
                    styleListChip(other, otherIndex == chipIndex)
                }
            }
        }
    }

    private fun listChip(option: Option): TextView = TextView(context).apply {
        text = option.label
        textSize = 13f
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setPadding(dp(8), dp(4), dp(8), dp(4))
        styleListChip(this, option.selected)
        isClickable = true
        isFocusable = true
    }

    private fun styleListChip(chip: TextView, selected: Boolean) {
        chip.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        chip.setTextColor(if (selected) palette.accent else palette.secondary)
        chip.background = if (selected) {
            rounded(palette.accentSoft, 11, tint(palette.accent, 0.30f))
        } else {
            rounded(tint(palette.trackOff, 0.55f), 11)
        }
    }

    // ----------------------------------------------------------------- slider

    /** Stepped slider over [values]; the label updates live and the value is reported on change. */
    fun slider(
        labelText: String,
        description: String? = null,
        values: List<Int>,
        selectedValue: Int,
        label: (Int) -> String,
        onValueChanged: (Int) -> Unit,
        onCreated: ((SeekBar, TextView) -> Unit)? = null,
    ): View = stack().apply {
        val selectedIndex = values.indexOf(selectedValue).takeIf { it >= 0 } ?: 0
        val valueView = label(label(values[selectedIndex]), 19f, palette.accent, bold = true)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(leading(labelText, description), leadingParams())
                addView(
                    valueView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
            matchWrap(),
        )
        val seekBar = SeekBar(context).apply {
            max = (values.size - 1).coerceAtLeast(0)
            progress = selectedIndex
            splitTrack = false
            progressTintList = ColorStateList.valueOf(palette.accent)
            thumbTintList = ColorStateList.valueOf(palette.accent)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = values.getOrNull(progress) ?: return
                        valueView.text = label(value)
                        if (fromUser) onValueChanged(value)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        addView(seekBar, matchWrap().apply { topMargin = dp(8) })
        onCreated?.invoke(seekBar, valueView)
    }

    // ------------------------------------------------------------------ input

    fun input(
        labelText: String,
        value: String,
        hint: String? = null,
        password: Boolean = false,
        numeric: Boolean = false,
        onInputCreated: ((EditText) -> Unit)? = null,
        onChanged: (String) -> Unit,
    ): View = stack().apply {
        addView(leading(labelText, null), matchWrap())
        val field = EditText(context).apply {
            setText(value)
            textSize = 15f
            setTextColor(palette.text)
            setHintTextColor(palette.tertiary)
            background = rounded(palette.surfaceElevated, 10, palette.divider)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = context.resources.getDimensionPixelSize(R.dimen.settings_input_min_height)
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (password) typeface = Typeface.MONOSPACE
            onTextEdited { onChanged(it) }
        }
        addView(field, matchWrap().apply { topMargin = dp(8) })
        if (!hint.isNullOrBlank()) {
            addView(
                label(hint, 12f, palette.tertiary).apply { setLineSpacing(dp(3).toFloat(), 1f) },
                matchWrap().apply { topMargin = dp(6) },
            )
        }
        onInputCreated?.invoke(field)
    }

    /** A picked file: current value plus a button that opens the picker. */
    fun document(
        labelText: String,
        value: String,
        buttonLabel: String,
        onDocumentViewCreated: ((TextView) -> Unit)? = null,
        onPick: () -> Unit,
    ): View = row().apply {
        val valueView = label(value, 13.5f, palette.secondary).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        onDocumentViewCreated?.invoke(valueView)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(labelText, 15f, palette.text, bold = true), matchWrap())
                addView(valueView, matchWrap().apply { topMargin = dp(3) })
            },
            leadingParams(),
        )
        addView(
            secondaryButton(buttonLabel, onPick),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(12) },
        )
    }

    // ----------------------------------------------------------------- status

    /** A status row around an existing pill, for callers that need to restyle it later. */
    fun statusRow(labelText: String?, description: String?, pill: View): View = row().apply {
        if (!labelText.isNullOrBlank()) {
            addView(leading(labelText, description), leadingParams())
        } else {
            addView(spacer())
        }
        addView(
            pill,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /** The semantic pill itself, used by [statusRow]. */
    fun pill(text: String, tone: Tone): TextView = label(text, 12.5f, pillColor(tone), bold = true).apply {
        tag = tone
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = rounded(tint(pillColor(tone), 0.12f), 999, tint(pillColor(tone), 0.30f))
    }

    private fun pillColor(tone: Tone): Int = when (tone) {
        Tone.OK -> palette.ok
        Tone.WARN -> palette.accent
        Tone.OFF -> palette.tertiary
    }

    /** A small rounded preview frame, used by the AirPlay icon picker. */
    fun iconPreview(): android.widget.ImageView = android.widget.ImageView(context).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        background = rounded(palette.trackOff, 12)
    }

    /** A plain value line whose text is meant to be replaced later. */
    fun valueNote(initial: String, sizeSp: Float = 12.5f): TextView =
        label(initial, sizeSp, palette.secondary).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }

    // ----------------------------------------------------------------- action

    fun action(
        labelText: String?,
        description: String?,
        actions: List<Action>,
    ): View = row().apply {
        if (!labelText.isNullOrBlank()) {
            addView(leading(labelText, description), leadingParams())
        } else {
            addView(spacer())
        }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                actions.forEachIndexed { index, action ->
                    val button = if (action.primary) {
                        primaryButton(action.label, action.onClick)
                    } else {
                        secondaryButton(action.label, action.onClick)
                    }
                    button.isEnabled = action.enabled
                    button.alpha = if (action.enabled) 1f else 0.5f
                    addView(
                        button,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { if (index > 0) marginStart = dp(8) },
                    )
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    fun danger(
        title: String,
        description: String,
        buttonLabel: String,
        onClick: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(tint(palette.danger, 0.07f), 14, tint(palette.danger, 0.30f))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(label(title, 14f, palette.danger, bold = true), matchWrap())
        addView(
            label(description, 12.5f, palette.secondary).apply { setLineSpacing(dp(3).toFloat(), 1f) },
            matchWrap().apply { topMargin = dp(3) },
        )
        addView(
            outlineButton(buttonLabel, palette.danger, onClick),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
    }

    // ---------------------------------------------------------------- buttons

    private fun primaryButton(text: String, onClick: () -> Unit): Button =
        baseButton(text, palette.onAccent, onClick).apply {
            backgroundTintList = ColorStateList.valueOf(palette.accent)
        }

    private fun secondaryButton(text: String, onClick: () -> Unit): Button =
        baseButton(text, palette.text, onClick).apply {
            backgroundTintList = ColorStateList.valueOf(palette.trackOff)
        }

    private fun outlineButton(text: String, color: Int, onClick: () -> Unit): Button =
        baseButton(text, color, onClick).apply {
            background = rounded(Color.TRANSPARENT, 10, tint(color, 0.45f))
        }

    private fun baseButton(text: String, textColor: Int, onClick: () -> Unit): Button =
        Button(context).apply {
            this.text = text
            isAllCaps = false
            textSize = 13.5f
            setTextColor(textColor)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener { onClick() }
        }


    /** A standalone button: [primary] is the accent fill, otherwise a ghost fill. */
    fun actionButton(
        text: String,
        primary: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): Button {
        val button = if (primary) primaryButton(text, onClick) else secondaryButton(text, onClick)
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.5f
        return button
    }

    /** A read-only block of text, used by the display preview. */
    fun note(text: String): TextView = valueNote(text)
}
