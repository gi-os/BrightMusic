package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightThemeTokens

@Composable
fun SearchScreen(
    vm: AppViewModel,
    onOpenEditor: (String) -> Unit,
) {
    val search by vm.search.collectAsState()

    PhonoScreenShell(
        title = "Search",
        hideBackButton = true,
        rightIconVisible = false,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        LightTextField(
            label = "Search:",
            value = search.query,
            placeholder = "Search for something!",
            onClick = { onOpenEditor(search.query) },
            underlineWidthFraction = 1f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Full-screen search entry backed by the system IME. The keyboard's Search action key
 * submits, so a query can be run without reaching for the bottom bar.
 */
@Composable
fun SearchInputScreen(
    initialQuery: String,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
) {
    val textState = rememberTextFieldState(initialQuery)

    LightTextInputEditor(
        title = "Search",
        state = textState,
        onSubmit = { text ->
            val query = text.toString().trim()
            if (query.isNotBlank()) onSubmit(query)
        },
        onBack = onBack,
        submitIcon = LightIcons.SEARCH,
        submitLabel = "SEARCH",
        imeAction = ImeAction.Search,
        // Artist and track names are rarely sentence-cased; let the user type freely.
        capitalization = KeyboardCapitalization.None,
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    )
}
