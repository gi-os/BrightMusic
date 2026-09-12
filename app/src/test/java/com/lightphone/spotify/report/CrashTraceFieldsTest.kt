package com.lightphone.spotify.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An automatic report has to say where the crash happened, and the only record of that is the
 * header the dying process wrote. Reading the live [ReportContext] instead would name whatever
 * screen the *new* process has reached, which is never where it died.
 */
class CrashTraceFieldsTest {

    private val trace = """
        at: 2026-09-11 15:43:06
        pid: 23284
        thread: main
        screen: playing

        java.lang.IllegalArgumentException: px must be > 0.
        ${'\t'}at coil.size.Dimension${'$'}Pixels.<init>(Dimension.kt:12)
    """.trimIndent()

    @Test
    fun `the screen comes out of the trace`() {
        assertEquals("playing", CrashLog.screenOf(trace))
    }

    @Test
    fun `a trace from an older build has no screen line`() {
        assertNull(CrashLog.screenOf("at: 2026-09-11 15:43:06\npid: 1\n\njava.lang.Error"))
    }

    @Test
    fun `an empty screen line is not a screen name`() {
        assertNull(CrashLog.screenOf("screen: \n\njava.lang.Error"))
    }
}
