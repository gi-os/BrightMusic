package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.gios.light.common.hw.WheelScroll
import com.lightphone.spotify.data.isEpisodeUri
import com.lightphone.spotify.playback.SleepChoice
import com.lightphone.spotify.playback.SleepClock
import com.lightphone.spotify.playback.SleepTimer
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay

/**
 * Pick how long to keep playing.
 *
 * The list is durations rather than a numeric picker: at bedtime nobody wants to dial in 37
 * minutes, and five choices reach the whole useful range with one tap each. "End of episode" is the
 * one people actually mean on a podcast, so it is at the bottom of the list where the thumb is,
 * and it names whatever is playing rather than always saying "track".
 */
@Composable
fun SleepTimerScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val playback by vm.playback.collectAsState()
    val radio by vm.radio.collectAsState()
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    val remaining = rememberSleepRemainingMs()
    val armed = SleepTimer.state.armed

    // A live stream has no end to stop at, and a track whose length has not arrived yet cannot be
    // measured either — in both cases the row is left out rather than shown doing nothing.
    val endOfItemDelay = if (radio.isActive) null else vm.endOfItemDelayMs()
    val isEpisode = playback.currentUri.isEpisodeUri()

    PhonoScreenShell(
        title = "Sleep timer",
        hideBackButton = false,
        onBack = onBack,
        rightIconVisible = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        LightScrollView(modifier = Modifier.weight(1f), scrollState = scroll) {
            Column(Modifier.fillMaxWidth()) {
                if (armed) {
                    LightText(
                        text = if (SleepTimer.state.endOfItem && remaining <= 0L) {
                            "Until the end"
                        } else {
                            SleepClock.formatRemaining(remaining)
                        },
                        variant = LightTextVariant.Heading,
                        align = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = legacyNToGridDp(10), bottom = legacyNToGridDp(4)),
                    )
                    LightText(
                        text = if (SleepTimer.state.fading) {
                            "Fading out"
                        } else if (SleepTimer.state.endOfItem) {
                            if (isEpisode) "At the end of this episode" else "At the end of this track"
                        } else {
                            "Fades out over the last 20 seconds"
                        },
                        variant = LightTextVariant.Detail,
                        color = PhonoSemanticColors.Placeholder,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = legacyNToGridDp(12)),
                    )
                    SleepRow("Add 15 minutes") { vm.extendSleepTimer(15) }
                    SleepRow("Cancel timer") { vm.cancelSleepTimer() }
                    Spacer(Modifier.height(legacyNToGridDp(12)))
                    SectionLabelPublic("Or set a new one")
                }

                SleepChoice.MINUTES.forEach { minutes ->
                    SleepRow(
                        text = "$minutes minutes",
                        selected = armed && !SleepTimer.state.endOfItem &&
                            SleepTimer.state.minutes == minutes,
                    ) {
                        vm.startSleepTimer(SleepChoice.Minutes(minutes))
                        onBack()
                    }
                }
                if (endOfItemDelay != null) {
                    SleepRow(
                        text = if (isEpisode) "End of episode" else "End of track",
                        selected = armed && SleepTimer.state.endOfItem,
                    ) {
                        vm.startSleepTimer(SleepChoice.EndOfItem)
                        onBack()
                    }
                }
                Spacer(Modifier.height(legacyNToGridDp(40)))
            }
        }
    }
}

@Composable
private fun SleepRow(text: String, selected: Boolean = false, onClick: () -> Unit) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        underline = selected,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = legacyNToGridDp(8)),
    )
}

@Composable
private fun SectionLabelPublic(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        color = PhonoSemanticColors.Placeholder,
        modifier = Modifier.padding(bottom = legacyNToGridDp(8)),
    )
}

/**
 * Milliseconds left on the timer, recomposing about twice a second while one is running.
 *
 * The clock is polled rather than pushed: the deadline is a single number and nothing observes it,
 * so a ticker here is cheaper than a state flow updated every second by the playback service — and
 * it stops entirely when no timer is set, which is nearly always.
 */
@Composable
fun rememberSleepRemainingMs(): Long {
    val armed = SleepTimer.state.armed
    var remaining by remember { mutableLongStateOf(SleepTimer.remainingMs()) }
    LaunchedEffect(armed) {
        remaining = SleepTimer.remainingMs()
        while (armed) {
            delay(500)
            remaining = SleepTimer.remainingMs()
        }
    }
    return remaining
}
