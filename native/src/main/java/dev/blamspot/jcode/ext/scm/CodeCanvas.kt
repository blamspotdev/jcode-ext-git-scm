package dev.blamspot.jcode.ext.scm

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A text-editing surface that behaves like a code editor rather than a form control.
 *
 * A real View rather than Compose's text field: this is the pane a merge is hand-edited in, and it
 * wants an editor's line metrics rather than a form control's — pinned to the same line height the
 * rows beside it use, which is what keeps the gutter's numbers next to the lines they number.
 *
 * It asks the IME to stay out of extract mode itself. JCode asks for that app-wide, but through an
 * interceptor that reaches Compose's text fields only; a View is on its own, the same way JCode's
 * own editor and browser are.
 *
 * Everything else about it is chosen to disappear into the pane it sits in: monospace at the size
 * the rows use, no background, no underline, no padding of its own, and multi-line with no IME
 * action, because a newline in a file is a newline and not a button.
 */
@Composable
internal fun CodeCanvas(
    value: String,
    onValueChange: (String) -> Unit,
    textColor: Color,
    cursorColor: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
    onCaret: (IntRange?) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            CodeEditText(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                background = null
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
                gravity = Gravity.TOP or Gravity.START
                typeface = Typeface.MONOSPACE
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            if (!settingFromState) onValueChange(s?.toString().orEmpty())
                        }
                    },
                )
            }
        },
        update = { view ->
            view.onCaret = onCaret
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.value)
            // The pane's rows are laid out at a fixed height; matching it here is what keeps the
            // gutter's numbers beside the lines they belong to.
            val target = lineHeight.value * view.resources.displayMetrics.scaledDensity
            // ascent..descent, not top..bottom: the layout lays lines out on the former, and the
            // latter is the font's furthest reach. Measuring the wrong one leaves every line short
            // of the height asked for, by a constant that the gutter beside it does not share.
            val natural = view.paint.fontMetrics.let { it.descent - it.ascent }
            view.setLineSpacing((target - natural).coerceAtLeast(0f), 1f)
            view.setTextColor(textColor.toArgb())
            view.tintCursor(cursorColor)
            // Only when it actually differs: assigning the same text moves the cursor to the end,
            // which on every keystroke means typing in reverse.
            if (view.text.toString() != value) {
                view.settingFromState = true
                val at = view.selectionStart.coerceAtMost(value.length)
                view.setText(value)
                view.setSelection(at.coerceAtLeast(0))
                view.settingFromState = false
            }
        },
    )
}

/**
 * The editor View itself.
 *
 * [settingFromState] guards the write-back: pushing state into the field fires the watcher, and
 * without the guard that reports the value back as a fresh edit and the two chase each other.
 */
private class CodeEditText(context: Context) : EditText(context) {
    var settingFromState = false

    /** Told where the caret is, in this view's own pixels, whenever it moves. */
    var onCaret: ((IntRange?) -> Unit)? = null

    // Moving the caret makes a TextView ask every parent to scroll it into view, and that request
    // walks all the way out to the window — which slides the whole app up, taking the two versions
    // being merged off screen at the moment they are most needed. The page answers it instead,
    // from [onCaret], so only the page moves.
    override fun requestRectangleOnScreen(rectangle: Rect, immediate: Boolean): Boolean = false

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        reportCaret()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(focused, direction, previous)
        if (focused) reportCaret() else onCaret?.invoke(null)
    }

    // A selection can change before there is a layout to locate it in — on the way back from a
    // rotation, or as the field is first filled — so the caret is reported again once there is one.
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (hasFocus()) reportCaret()
    }

    private fun reportCaret() {
        val at = layout ?: return
        val line = at.getLineForOffset(selectionStart.coerceIn(0, text?.length ?: 0))
        onCaret?.invoke(at.getLineTop(line)..at.getLineBottom(line))
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        // Not covered by the app's Compose-side interceptor, so it asks for itself. Without these
        // the IME covers the screen in landscape and edits a copy of the text in a window of its
        // own, which is not what editing a file looks like.
        outAttrs.imeOptions = outAttrs.imeOptions or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return connection
    }

    fun tintCursor(color: Color) {
        // API 29 named the cursor drawable; before that it is a private field nobody should reach
        // for, and the default cursor is legible anyway.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable?.setTint(color.toArgb())
        }
        highlightColor = color.copy(alpha = 0.35f).toArgb()
    }
}
