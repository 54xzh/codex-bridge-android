package com.xzh54.relayouter.ui.screens.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingQrParserTest {
    @Test
    fun parsePairingQrText_validPayload_returnsParsed() {
        val input = "codex-relayouter://pair?baseUrl=192.168.1.10:12345&pairingCode=123456"
        val parsed = parsePairingQrText(input)
        requireNotNull(parsed)
        assertEquals("192.168.1.10:12345", parsed.baseUrl)
        assertEquals("123456", parsed.pairingCode)
    }

    @Test
    fun parsePairingQrText_trimsWhitespace() {
        val input = "  codex-relayouter://pair?baseUrl=http%3A%2F%2F192.168.1.10%3A12345%2F&pairingCode=654321  "
        val parsed = parsePairingQrText(input)
        requireNotNull(parsed)
        assertEquals("http://192.168.1.10:12345/", parsed.baseUrl)
        assertEquals("654321", parsed.pairingCode)
    }

    @Test
    fun parsePairingQrText_wrongScheme_returnsNull() {
        val input = "http://example.com?baseUrl=192.168.1.10:12345&pairingCode=123456"
        assertNull(parsePairingQrText(input))
    }

    @Test
    fun parsePairingQrText_missingParams_returnsNull() {
        val input = "codex-relayouter://pair?baseUrl=192.168.1.10:12345"
        assertNull(parsePairingQrText(input))
    }
}
