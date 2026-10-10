package com.lightphone.spotify.report

/**
 * What playback did between a tap on play and sound, step by step, for the stall report.
 *
 * Eleven reports in a row said only that the spinner had been up for ten seconds: online, session
 * connected, play call returned, no player event. Every fact in them was true, and none of them
 * said where the load stopped. This keeps the steps themselves: a short ring of timestamped lines
 * that the app writes to here and the native engine writes to on its side, merged when a report
 * is filed, so the next one shows the last thing that happened before nothing did.
 *
 * Pure logic with no Android in it, so the ring, the rendering and the redaction are tested on the
 * JVM. Callers pass the clock in; [PlaybackTrace] is the process-wide instance and owns the clock.
 *
 * Cheap on purpose. A note is a lock and an append. Redaction and formatting happen only when a
 * report is rendered, which is at most once a minute.
 */
class LoadTrace(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val keepBefore: Int = DEFAULT_KEEP_BEFORE,
    private val maxLines: Int = DEFAULT_MAX_LINES,
) {
    private class Step(val atMs: Long, val text: String)

    private val steps = ArrayDeque<Step>()
    private var startedAtMs: Long? = null

    /**
     * A new play request. Its time is the zero every line is measured from.
     *
     * Only the last [keepBefore] older steps survive, as context: what the network or the session
     * was doing just before the tap is often the answer, but the previous song's whole story is not.
     */
    @Synchronized
    fun begin(nowMs: Long, what: String) {
        while (steps.size > keepBefore) steps.removeFirst()
        startedAtMs = nowMs
        add(nowMs, what)
    }

    /** One step. The oldest one goes when the ring is full. */
    @Synchronized
    fun note(nowMs: Long, text: String) {
        add(nowMs, text)
    }

    private fun add(nowMs: Long, text: String) {
        while (steps.size >= capacity) steps.removeFirst()
        steps.addLast(Step(nowMs, text))
    }

    /**
     * The trace as a report shows it: the app's steps and the [engine]'s merged in time order,
     * each line marked with seconds from the play request, secrets taken out.
     *
     * Engine steps arrive as "this many milliseconds before now" and are placed against [nowMs].
     * Everything from the play request on is kept, plus the last [keepBefore] lines before it. A
     * trace longer than [maxLines] keeps its start, where the load was set up, and its end, where
     * it stopped.
     */
    fun render(nowMs: Long, engine: List<EngineStep> = emptyList()): String {
        val own: List<Step>
        val started: Long?
        synchronized(this) {
            own = steps.toList()
            started = startedAtMs
        }
        val lines = ArrayList<Step>(own.size + engine.size)
        lines.addAll(own)
        engine.forEach { lines.add(Step(nowMs - it.ageMs.coerceAtLeast(0L), it.text)) }
        // Stable, so a step the app and the engine wrote in the same millisecond keeps the order
        // the two lists had.
        lines.sortBy { it.atMs }
        if (lines.isEmpty()) return "(nothing was recorded)"

        val origin = started ?: lines.first().atMs
        val firstFromOrigin = lines.indexOfFirst { it.atMs >= origin }.let { if (it < 0) lines.size else it }
        val shown = lines.subList((firstFromOrigin - keepBefore).coerceAtLeast(0), lines.size)

        val out = ArrayList<String>(shown.size + 1)
        if (shown.size > maxLines) {
            val head = maxLines * 2 / 3
            val tail = maxLines - head
            shown.subList(0, head).forEach { out.add(line(it, origin)) }
            out.add("          ... ${shown.size - head - tail} steps left out ...")
            shown.subList(shown.size - tail, shown.size).forEach { out.add(line(it, origin)) }
        } else {
            shown.forEach { out.add(line(it, origin)) }
        }
        return out.joinToString("\n")
    }

    private fun line(step: Step, origin: Long): String = offset(step.atMs - origin) + "  " + redact(step.text)

    companion object {
        const val DEFAULT_CAPACITY = 120
        const val DEFAULT_KEEP_BEFORE = 8
        const val DEFAULT_MAX_LINES = 150

        /** Longest line a report gets. An exception message can be any length. */
        const val MAX_LINE = 200

        /** Seconds from the play request, signed, to the millisecond: `   +1.250s`. */
        fun offset(ms: Long): String {
            val sign = if (ms < 0) "-" else "+"
            val abs = if (ms < 0) -ms else ms
            return "$sign${abs / 1000}.${(abs % 1000).toString().padStart(3, '0')}s".padStart(9)
        }

        private val URL = Regex("""(?i)\b[a-z][a-z0-9+.\-]*://[^\s"'<>()]+""")
        private val BEARER = Regex("""(?i)\bbearer\s+[^\s"',;]+""")
        private val SECRET_PARAM = Regex(
            """(?i)\b(access_token|refresh_token|id_token|token|__token__|hmac|signature|sig|""" +
                """client_secret|password|code|key|policy|key-pair-id)=([^\s&;,"']+)""",
        )
        private val JWT = Regex("""\beyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+""")

        /**
         * A line with nothing in it that should not be posted to a tracker.
         *
         * A URL is cut down to its host: a CDN URL carries a signed token in its query, and the host
         * is the only part a diagnosis needs. Bearer tokens, `token=`-style parameters and JWTs are
         * blanked wherever else they turn up, which in practice means inside an exception message.
         * Backtick fences are broken up because the trace is posted inside one.
         */
        fun redact(text: String): String {
            var out = URL.replace(text) { hostOf(it.value) }
            out = BEARER.replace(out, "Bearer <redacted>")
            out = SECRET_PARAM.replace(out) { "${it.groupValues[1]}=<redacted>" }
            out = JWT.replace(out, "<redacted>")
            out = out.replace("```", "'''").replace('\n', ' ').replace('\r', ' ')
            return if (out.length > MAX_LINE) out.take(MAX_LINE - 3) + "..." else out
        }

        /** The host of [url], without scheme, credentials, port, path, query or fragment. */
        fun hostOf(url: String): String {
            val rest = url.substringAfter("://", url)
            val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
            val hostPort = authority.substringAfterLast('@')
            return if (hostPort.startsWith("[")) {
                hostPort.substringBefore(']') + "]"
            } else {
                hostPort.substringBefore(':')
            }
        }
    }
}

/** One step from the native engine's own trace: written [ageMs] milliseconds before it was read. */
data class EngineStep(val ageMs: Long, val text: String)

/**
 * The trace playback, the audio sink and the stall report share.
 *
 * The clock is [System.nanoTime], which on Android is CLOCK_MONOTONIC: the same clock Rust's
 * `Instant` reads, so an engine step written "120 ms ago" lands where it happened among the app's
 * own. Nothing here throws. Some callers are JNI threads, where an exception would turn into a
 * failed audio write.
 */
object PlaybackTrace {
    private val trace = LoadTrace()

    /** Monotonic milliseconds, for measuring how long a step took. */
    fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** A new play request: the zero of every time in the next report. */
    fun begin(what: String) {
        try {
            trace.begin(nowMs(), what)
        } catch (ignored: Throwable) {
        }
    }

    fun note(text: String) {
        try {
            trace.note(nowMs(), text)
        } catch (ignored: Throwable) {
        }
    }

    /** The trace with the engine's steps merged in, ready for the report body. */
    fun render(engine: List<EngineStep>): String =
        try {
            trace.render(nowMs(), engine)
        } catch (e: Throwable) {
            "(the trace could not be rendered: ${e::class.java.simpleName})"
        }
}
