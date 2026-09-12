package com.lightphone.spotify.ui

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The collectors in `AppViewModel`'s `init` may only touch state declared above them.
 *
 * This is a source-order test because the failure it guards has no other shape. `viewModelScope` is
 * `Dispatchers.Main.immediate`, which does not dispatch when it is already on the main thread, so
 * `launch` runs its body inline inside the constructor; every flow those collectors read is a
 * `StateFlow`, which hands a new collector its current value with no suspension. The first emission
 * was therefore delivered while the constructor was still running, and a `val` further down the
 * class was still null — with a non-null type, no warning, and no way for any ordinary unit test to
 * notice. What it produced was a `NullPointerException` out of `Constructor.newInstance` on every
 * launch, for anyone whose restored playback took the one branch that read one of these.
 *
 * `collectorsStart` makes the collectors dispatch, which fixes it properly. This pins the second
 * half: the two flows that were caught by it stay above `init`, so re-introducing the bug by
 * declaring them back down with the rest of the podcast state fails here rather than on a phone.
 */
class InitOrderTest {

    private val source = File("src/main/java/com/lightphone/spotify/ui/AppViewModel.kt")

    @Test
    fun `podcast state the init collectors touch is declared before init`() {
        // The test runs from the module directory under Gradle; skip rather than fail if some
        // other runner has a different working directory. A guard that lies is worse than absent.
        assumeTrue("AppViewModel.kt not found from ${File(".").absolutePath}", source.exists())
        val text = source.readText()

        val firstInit = text.indexOf("\n    init {")
        assertTrue("no init block found in AppViewModel", firstInit > 0)

        for (property in listOf("private val _playedEpisodes", "private val _unheardShows")) {
            val at = text.indexOf(property)
            assertTrue("$property is missing", at > 0)
            assertTrue(
                "$property is declared after `init`, so the collectors in init read it as null " +
                    "during construction — move it above init",
                at < firstInit,
            )
        }
    }

    @Test
    fun `the init collectors do not start on the immediate dispatcher`() {
        assumeTrue(source.exists())
        val text = source.readText()
        val init = text.indexOf("\n    init {")
        val body = text.substring(init, text.indexOf("\n    /** Offline downloads", init))
        assertTrue(
            "a collector in init uses a bare viewModelScope.launch, which runs inline inside the " +
                "constructor — start it with `collectorsStart`",
            !body.contains("\n        viewModelScope.launch {"),
        )
    }
}
