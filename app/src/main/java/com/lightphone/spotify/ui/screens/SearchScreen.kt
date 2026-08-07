package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import com.lightphone.spotify.data.SearchResultItem
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.PhonoFallbackImage
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

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
    /** Live results for what is currently typed; empty hides the list. */
    suggestions: List<SearchResultItem> = emptyList(),
    onTyping: (String) -> Unit = {},
    onSuggestion: (SearchResultItem) -> Unit = {},
) {
    val textState = rememberTextFieldState(initialQuery)

    // Feed every keystroke to the (debounced) suggester. snapshotFlow already conflates,
    // so fast typing costs one emission per frame at worst.
    LaunchedEffect(textState) {
        snapshotFlow { textState.text.toString() }
            .collect { onTyping(it) }
    }

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
        belowField = if (suggestions.isEmpty()) {
            null
        } else {
            { SearchSuggestionList(suggestions, onSuggestion) }
        },
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    )
}

/**
 * The rows under the search field while you type. Kept deliberately small — the IME owns
 * the bottom half of a 472dp screen, so four rows is roughly what is visible; the list
 * scrolls for the rest.
 */
@Composable
private fun SearchSuggestionList(
    suggestions: List<SearchResultItem>,
    onSuggestion: (SearchResultItem) -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth()) {
        items(suggestions, key = { "${it::class.simpleName}-${it.id}" }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { onSuggestion(item) }
                    .padding(vertical = legacyNToGridDp(6)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhonoFallbackImage(
                    imageUrl = item.imageUrl,
                    placeholderIcon = if (item is SearchResultItem.Artist) {
                        Icons.Default.Person
                    } else {
                        Icons.Default.MusicNote
                    },
                    placeholderIconSize = legacyNToGridDp(16),
                    crossfade = false,
                    decodeSize = legacyNToGridDp(34),
                    modifier = Modifier.size(legacyNToGridDp(34)),
                )
                Spacer(Modifier.size(legacyNToGridDp(10)))
                Column(Modifier.weight(1f)) {
                    LightText(
                        text = item.title,
                        variant = LightTextVariant.Copy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LightText(
                        text = item.subtitle,
                        variant = LightTextVariant.Detail,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
