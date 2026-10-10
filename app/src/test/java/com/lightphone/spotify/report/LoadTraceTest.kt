package com.lightphone.spotify.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stall report's trace is only worth anything if it puts the steps in the order they happened,
 * measures them from the tap, and never posts a token to a tracker. Those three are pinned here.
 */
class LoadTraceTest {

    private fun lines(rendered: String) = rendered.lines()

    // --- timing ------------------------------------------------------------------------------

    @Test
    fun `steps are measured from the play request`() {
        val trace = LoadTrace()
        trace.begin(10_000, "app: play tapped")
        trace.note(10_004, "app: playUris -> engine")
        trace.note(11_250, "app: event Playing at 0ms")

        assertEquals(
            listOf(
                "  +0.000s  app: play tapped",
                "  +0.004s  app: playUris -> engine",
                "  +1.250s  app: event Playing at 0ms",
            ),
            lines(trace.render(nowMs = 12_000)),
        )
    }

    @Test
    fun `a few steps from before the tap stay, with negative times, and older ones go`() {
        val trace = LoadTrace(keepBefore = 2)
        trace.note(1_000, "net: online -> false")
        trace.note(2_000, "net: online -> true")
        trace.note(9_500, "engine: told the network is back")
        trace.begin(10_000, "app: play tapped")

        assertEquals(
            listOf(
                "  -8.000s  net: online -> true",
                "  -0.500s  engine: told the network is back",
                "  +0.000s  app: play tapped",
            ),
            lines(trace.render(nowMs = 10_100)),
        )
    }

    @Test
    fun `the ring drops its oldest step when it is full`() {
        val trace = LoadTrace(capacity = 3)
        trace.begin(0, "one")
        trace.note(1, "two")
        trace.note(2, "three")
        trace.note(3, "four")

        val rendered = trace.render(nowMs = 10)
        assertFalse(rendered.contains("one"))
        assertTrue(rendered.endsWith("four"))
        assertEquals(3, lines(rendered).size)
    }

    // --- merging with the engine ---------------------------------------------------------------

    @Test
    fun `engine steps are placed by their age among the app's own`() {
        val trace = LoadTrace()
        trace.begin(10_000, "app: play tapped")
        trace.note(10_005, "app: playUris returned")

        val engine = listOf(
            EngineStep(ageMs = 8, text = "engine: play_uris"), // written at 10_002
            EngineStep(ageMs = 3, text = "player: load command taken"), // written at 10_007
        )

        assertEquals(
            listOf(
                "  +0.000s  app: play tapped",
                "  +0.002s  engine: play_uris",
                "  +0.005s  app: playUris returned",
                "  +0.007s  player: load command taken",
            ),
            lines(trace.render(nowMs = 10_010, engine = engine)),
        )
    }

    @Test
    fun `engine history from long before the tap is cut to the same few lines`() {
        val trace = LoadTrace(keepBefore = 1)
        trace.begin(100_000, "app: play tapped")
        val engine = (1..50).map { EngineStep(ageMs = 60_000L + it, text = "player: old $it") } +
            EngineStep(ageMs = 0, text = "engine now: online session, player thread alive")

        val shown = lines(trace.render(nowMs = 100_500, engine = engine))
        assertEquals(3, shown.size)
        assertTrue(shown[0].endsWith("player: old 1")) // the newest of the old ones
        assertTrue(shown[1].endsWith("app: play tapped"))
        assertTrue(shown[2].endsWith("engine now: online session, player thread alive"))
    }

    @Test
    fun `a trace too long for a report keeps its start and its end`() {
        val trace = LoadTrace(capacity = 500, maxLines = 9)
        trace.begin(0, "step 0")
        for (i in 1..99) trace.note(i.toLong(), "step $i")

        val shown = lines(trace.render(nowMs = 1_000))
        assertEquals(10, shown.size)
        assertTrue(shown.first().endsWith("step 0"))
        // Two thirds of the budget for the start, the rest for the end.
        assertTrue(shown[5].endsWith("step 5"))
        assertTrue(shown[6].contains("91 steps left out"))
        assertTrue(shown[7].endsWith("step 97"))
        assertTrue(shown.last().endsWith("step 99"))
    }

    @Test
    fun `an empty trace says so`() {
        assertEquals("(nothing was recorded)", LoadTrace().render(nowMs = 0))
    }

    @Test
    fun `offsets are signed seconds to the millisecond`() {
        assertEquals("  +0.000s", LoadTrace.offset(0))
        assertEquals("  +0.042s", LoadTrace.offset(42))
        assertEquals(" +12.345s", LoadTrace.offset(12_345))
        assertEquals("  -1.500s", LoadTrace.offset(-1_500))
    }

    // --- redaction ---------------------------------------------------------------------------------

    @Test
    fun `a CDN URL is cut down to its host`() {
        assertEquals(
            "file 0a1b2c: GET audio4-ak-spotify-com.akamaized.net failed",
            LoadTrace.redact(
                "file 0a1b2c: GET https://audio4-ak-spotify-com.akamaized.net/audio/0a1b2c3d" +
                    "?__token__=exp=1760000000~hmac=0123456789abcdef failed",
            ),
        )
    }

    @Test
    fun `credentials and ports in a URL go with the rest of it`() {
        assertEquals("proxy example.com refused", LoadTrace.redact("proxy http://user:secret@example.com:8080/x refused"))
        assertEquals("[::1]", LoadTrace.hostOf("https://[::1]:443/path"))
    }

    @Test
    fun `tokens outside a URL are blanked`() {
        assertEquals("Authorization: Bearer <redacted>", LoadTrace.redact("Authorization: Bearer BQD1x.y_z-9"))
        assertEquals(
            "refresh_token=<redacted>&grant_type=refresh_token",
            LoadTrace.redact("refresh_token=AQCabc123&grant_type=refresh_token"),
        )
        assertEquals("got <redacted> back", LoadTrace.redact("got eyJhbGciOi.eyJzdWIiOi.c2lnbmF0dXJl back"))
    }

    @Test
    fun `ordinary lines pass through untouched`() {
        val line = "loader: audio key in 212 ms, reading the file header"
        assertEquals(line, LoadTrace.redact(line))
    }

    @Test
    fun `a line cannot break out of the report's code fence or run on forever`() {
        assertEquals("a '''b''' c", LoadTrace.redact("a ```b``` c"))
        assertEquals("one two", LoadTrace.redact("one\ntwo"))
        val long = LoadTrace.redact("x".repeat(1_000))
        assertEquals(LoadTrace.MAX_LINE, long.length)
        assertTrue(long.endsWith("..."))
    }

    @Test
    fun `the rendered trace is redacted too, engine lines included`() {
        val trace = LoadTrace()
        trace.begin(0, "app: play tapped")
        val rendered = trace.render(
            nowMs = 100,
            engine = listOf(EngineStep(50, "file ab12cd: bytes 0+65536 from https://cdn.example/a?token=SECRET failed")),
        )
        assertFalse(rendered.contains("SECRET"))
        assertTrue(rendered.contains("from cdn.example failed"))
    }
}
