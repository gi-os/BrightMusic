package com.lightphone.spotify.ui.navigation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PhonoShellViewModel : ViewModel() {
    // Opens on whichever bar tab the user picked in Settings; Playlists until they do.
    // ViewSettings is loaded in App.onCreate, well before any ViewModel exists.
    private val _currentTab = MutableStateFlow(
        tabForRoute(com.lightphone.spotify.ui.light.ViewSettings.defaultTabRoute),
    )
    val currentTab: StateFlow<PhonoTab> = _currentTab.asStateFlow()

    fun selectTab(tab: PhonoTab) {
        _currentTab.value = tab
    }
}
