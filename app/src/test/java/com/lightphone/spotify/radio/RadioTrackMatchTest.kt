package com.lightphone.spotify.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioTrackMatchTest {

    @Test
    fun `plain hyphen splits artist and title`() {
        val parsed = RadioTrackMatch.parse("Nils Frahm - Says")
        assertEquals("Nils Frahm", parsed?.artist)
        assertEquals("Says", parsed?.title)
    }

    @Test
    fun `en dash splits too`() {
        val parsed = RadioTrackMatch.parse("Hania Rani – Rilla")
        assertEquals("Hania Rani", parsed?.artist)
        assertEquals("Rilla", parsed?.title)
    }

    @Test
    fun `hyphens inside a title survive`() {
        // The first separator wins, so a hyphenated title stays whole.
        val parsed = RadioTrackMatch.parse("Godspeed You! Black Emperor - Sleep - Part I")
        assertEquals("Godspeed You! Black Emperor", parsed?.artist)
        assertEquals("Sleep - Part I", parsed?.title)
    }

    @Test
    fun `trailing noise is stripped`() {
        val parsed = RadioTrackMatch.parse("Aphex Twin - Xtal (Official Audio)")
        assertEquals("Xtal", parsed?.title)
    }

    @Test
    fun `a show name is not a track`() {
        // NTS live sends these. Searching one would put an arbitrary cover on screen.
        assertNull(RadioTrackMatch.parse("Charlie Bones"))
        assertNull(RadioTrackMatch.parse("Breakfast Show"))
    }

    @Test
    fun `blank sides are rejected`() {
        assertNull(RadioTrackMatch.parse(" - "))
        assertNull(RadioTrackMatch.parse("Artist - "))
        assertNull(RadioTrackMatch.parse(" - Title"))
    }

    @Test
    fun `null and blank are no match`() {
        assertNull(RadioTrackMatch.parse(null))
        assertNull(RadioTrackMatch.parse("   "))
    }

    @Test
    fun `an overlong field is a station slogan, not a track`() {
        assertNull(
            RadioTrackMatch.parse(
                "The very best in continuous smooth listening all day every day - " +
                    "and now the news",
            ),
        )
    }

    @Test
    fun `query puts artist before title`() {
        assertEquals("Nils Frahm Says", RadioTrackMatch.parse("Nils Frahm - Says")?.query)
    }
}
