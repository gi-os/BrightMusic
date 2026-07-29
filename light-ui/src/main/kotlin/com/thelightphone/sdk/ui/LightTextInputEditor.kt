package com.thelightphone.sdk.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview

/**
 * Full-screen text entry, modelled on LightOS `DisplayWithKeyboardPortrait` but driven
 * by the **system IME** instead of the bundled LP3 Compose keyboard.
 *
 * - Top bar with back button + title
 * - Underlined heading-style input, autofocused so the IME opens on entry
 * - [LightBottomBar] submit action, lifted above the keyboard via `imePadding()`
 *
 * The IME's own action key (Done / Search / Go) submits too, so the bottom bar is a
 * convenience rather than the only way out.
 *
 * Selection, long-press, and cut/copy/paste come from `BasicTextField`, which is why
 * this is shorter than the LP3-keyboard version it replaced: the pointer-drag cursor
 * placement and hand-rolled cursor Box are no longer needed.
 *
 * LOCAL PATCH — diverges from the upstream Light SDK on purpose. Re-apply after
 * `scripts/sync-light-ui.sh`. See VENDOR_VERSION.
 */
@Composable
fun LightTextInputEditor(
    title: String,
    state: TextFieldState,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    submitLabel: String = "SUBMIT",
    submitIcon: LightIconConfiguration? = null,
    showBackButton: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    singleLine: Boolean = true,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
) {
    val colors = LightThemeTokens.colors
    val inputStyle = lightInputTextStyle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Land in the field with the cursor after any prefilled text, keyboard already up.
    LaunchedEffect(state) {
        state.edit { placeCursorAtEnd() }
        focusRequester.requestFocus()
        keyboard?.show()
    }

    val submit: () -> Unit = { onSubmit(state.text) }

    Surface {
        Column(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            LightTopBar(
                leftButton = if (showBackButton) {
                    LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = onBack,
                    )
                } else {
                    null
                },
                center = LightTopBarCenter.Text(title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                contentAlignment = Alignment.TopStart,
            ) {
                BasicTextField(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = inputStyle,
                    lineLimits = if (singleLine) {
                        TextFieldLineLimits.SingleLine
                    } else {
                        TextFieldLineLimits.Default
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = capitalization,
                        autoCorrectEnabled = false,
                        imeAction = imeAction,
                    ),
                    onKeyboardAction = { submit() },
                    cursorBrush = SolidColor(colors.content),
                )
            }

            LightBottomBar(
                items = listOf(
                    when (val icon = submitIcon) {
                        null -> LightBarButton.Text(
                            text = submitLabel,
                            onClick = submit,
                        )
                        else -> LightBarButton.LightIcon(
                            icon = icon,
                            onClick = submit,
                            contentDescription = submitLabel,
                        )
                    },
                ),
            )
        }
    }
}

@Composable
private fun lightInputTextStyle(): TextStyle {
    val colors = LightThemeTokens.colors
    val t = LightThemeTokens.typography
    return t.heading
        .copy(
            color = colors.content,
            textDecoration = TextDecoration.Underline,
        )
        .scaledForScreenHeight()
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightTextInputEditorDark() {
    val state = rememberTextFieldState("hi")
    LightTheme(colors = LightThemeColors.Dark) {
        LightTextInputEditor(
            title = "Name",
            state = state,
            onSubmit = {},
            onBack = {},
        )
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightTextInputEditorLight() {
    val state = rememberTextFieldState("")
    LightTheme(colors = LightThemeColors.Light) {
        LightTextInputEditor(
            title = "Search",
            state = state,
            onSubmit = {},
            onBack = {},
            submitLabel = "SEARCH",
        )
    }
}
