package dev.blamspot.jcode.ext.scm

import android.content.Context
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
 * A real View, not Compose's text field, for one reason that cannot be worked around from Compose:
 * in landscape the IME takes over the whole screen unless the editor asks it not to, and the flag
 * that asks — `IME_FLAG_NO_EXTRACT_UI` — is set on the `EditorInfo` handed out by
 * `onCreateInputConnection`. Compose builds that object itself and exposes no way to add a flag to
 * it. JCode's own editor and its browser both override the method for exactly this; so does this.
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
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.value)
            // The pane's rows are laid out at a fixed height; matching it here is what keeps the
            // gutter's numbers beside the lines they belong to.
            val target = lineHeight.value * view.resources.displayMetrics.scaledDensity
            val natural = view.paint.fontMetrics.let { it.bottom - it.top }
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

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        // The whole reason this is a View. Without these the IME covers the screen in landscape and
        // edits a copy of the text in its own window, which is not what editing a file looks like.
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
