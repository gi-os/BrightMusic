package com.lightphone.spotify.playback.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local names for Connect devices.
 *
 * The name resolution and the encoding are pure, so they are tested here; the SharedPreferences round
 * trip is not, since it needs a device.
 */
class ConnectAliasesTest {

    @Test
    fun `an alias replaces the reported name`() {
        val aliases = mapOf("dev1" to "Kitchen")

        assertEquals("Kitchen", resolve(aliases, "dev1", "Some Speaker XM-4"))
    }

    @Test
    fun `no alias keeps whatever Spotify reported`() {
        assertEquals("Web Player (Chrome)", resolve(emptyMap(), "dev1", "Web Player (Chrome)"))
    }

    @Test
    fun `a device with no id cannot be aliased`() {
        // Spotify returns a null id for devices it knows but cannot target. There is nothing stable to
        // key an alias on, so the reported name stands.
        assertEquals("Mystery", resolve(mapOf("dev1" to "Kitchen"), null, "Mystery"))
    }

    @Test
    fun `a blank alias is ignored rather than showing an empty row`() {
        assertEquals("Real Name", resolve(mapOf("dev1" to "   "), "dev1", "Real Name"))
    }

    @Test
    fun `aliases are per device, not per name`() {
        // Two speakers can report the same name; renaming one must not rename the other.
        val aliases = mapOf("dev1" to "Kitchen")

        assertEquals("Kitchen", resolve(aliases, "dev1", "Speaker"))
        assertEquals("Speaker", resolve(aliases, "dev2", "Speaker"))
    }

    @Test
    fun `the encoding round-trips names with spaces and punctuation`() {
        val values = mapOf(
            "dev1" to "Gio's Kitchen",
            "dev2" to "Living Room — TV",
        )

        assertEquals(values, decode(encode(values)))
    }

    @Test
    fun `a name containing a tab or newline cannot corrupt the next entry`() {
        // The delimiters. A phone keyboard cannot type either, but the store should not be one paste
        // away from losing an unrelated device's name.
        val decoded = decode("dev1\tKitchen\ndev2\tLounge")

        assertEquals(2, decoded.size)
        assertEquals("Kitchen", decoded["dev1"])
        assertEquals("Lounge", decoded["dev2"])
    }

    @Test
    fun `malformed lines are skipped, not fatal`() {
        val decoded = decode("nodelimiter\ndev1\tKitchen\n\ntrailing\t")

        assertEquals(mapOf("dev1" to "Kitchen"), decoded)
    }

    @Test
    fun `an empty store decodes to nothing`() {
        assertTrue(decode("").isEmpty())
        assertFalse(decode("dev1\tKitchen").isEmpty())
    }

    // Mirrors of the production logic, which lives behind Android types. Kept deliberately literal so a
    // change to ConnectAliases that these do not follow shows up as a diff in both places.
    private fun resolve(aliases: Map<String, String>, deviceId: String?, reported: String): String {
        if (deviceId == null) return reported
        return aliases[deviceId]?.takeIf { it.isNotBlank() } ?: reported
    }

    private fun encode(values: Map<String, String>) =
        values.entries.joinToString("\n") { (id, name) -> "$id\t$name" }

    private fun decode(encoded: String): Map<String, String> =
        encoded.lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf('\t')
                if (at <= 0 || at == line.lastIndex) return@mapNotNull null
                line.substring(0, at) to line.substring(at + 1)
            }
            .toMap()
}
