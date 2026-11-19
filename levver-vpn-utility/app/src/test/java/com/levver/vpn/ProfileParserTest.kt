package com.levver.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileParserTest {
    @Test
    fun parsesVlessAndTrojanLinks() {
        val profiles = ProfileParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@example.net:443#Primary\n" +
            "trojan://password@example.org:443#Backup"
        )
        assertEquals(2, profiles.size)
        assertEquals("VLESS", profiles[0].protocol)
        assertEquals("example.net", profiles[0].server)
        assertEquals("Trojan", profiles[1].protocol)
    }

    @Test
    fun ignoresUnsupportedAndMalformedLines() {
        val profiles = ProfileParser.parse(
            "https://example.com\nnot-a-profile\nvless://"
        )
        assertTrue(profiles.isEmpty())
    }

    @Test
    fun removesDuplicateLinks() {
        val link = "vless://11111111-1111-1111-1111-111111111111@example.net:443#Primary"
        assertEquals(1, ProfileParser.parse("$link\n$link").size)
    }
}
