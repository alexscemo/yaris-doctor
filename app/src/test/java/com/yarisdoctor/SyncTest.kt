package com.yarisdoctor

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncTest {
    @Test fun hubUrlFillsInSchemeAndDefaultPort() {
        assertEquals("http://192.168.1.50:8765", hubUrl("192.168.1.50"))
        assertEquals("http://192.168.1.50:9000", hubUrl(" 192.168.1.50:9000/ "))
        assertEquals("https://hub.lan:8765", hubUrl("https://hub.lan"))
        assertEquals("http://hub.lan:80/x", hubUrl("http://hub.lan:80/x"))
        assertEquals("", hubUrl("  "))
    }
}
