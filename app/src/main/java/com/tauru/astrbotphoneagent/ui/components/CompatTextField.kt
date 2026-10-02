package com.tauru.astrbotphoneagent.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Plan-B input field for the Honor/MagicOS keyboard bug (2026-09-27).
 *
 * Symptom on RMO-AN00 with the stock Honor keyboard: text stays in the IME
 * composition buffer; the accessibility tree and onValueChange both see an
 * empty string, so the send button submits nothing.
 *
 * Differences from OutlinedTextField that improve OEM compatibility:
 *  - BasicTextField is used directly, skipping the Material decoration
 *    pipeline's extra focus/semantics indirection.
 *  - textStyle is passed through unchanged so the IME sees plain styles.
 *  - The decoration box is a plain Box; nothing wraps the input layer.
 *
 * Not wired into ChatScreen yet — switch to it only if the 1.7.6 +
 * TextFieldValue fix still shows empty onValueChange logs.
 */
@Composable
fun CompatTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else 4,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    ) { innerTextField ->
        Box {
            if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
            innerTextField()
        }
    }
}
