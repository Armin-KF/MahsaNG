package com.v2ray.ang

import com.v2ray.ang.ui.tv.TvFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TvFormatTest {
    @Test
    fun masksEveryHostShape() {
        assertEquals("", TvFormat.maskHost(null))
        assertEquals("203.•.24", TvFormat.maskHost("203.0.113.24"))
        assertEquals("2606:•", TvFormat.maskHost("2606:4700::1"))
        assertEquals("exa•.com", TvFormat.maskHost("example.com"))
        assertEquals("a•.io", TvFormat.maskHost("a.io"))
        assertEquals("loc•", TvFormat.maskHost("localhost"))
        assertFalse(TvFormat.maskHost("secret-node.example.net").contains("secret-node"))
    }

    @Test
    fun endpointIsIsolatedAndEmptyWithoutHost() {
        assertEquals("\u2066exa•.com : 443\u2069", TvFormat.endpoint("example.com", 443))
        assertEquals("", TvFormat.endpoint(null, 443))
    }

    @Test
    fun urlHostDropsTokens() {
        assertEquals("sub.example.com", TvFormat.urlHost("https://sub.example.com/api?token=abc"))
        assertEquals("", TvFormat.urlHost("not a url"))
        assertEquals("", TvFormat.urlHost(null))
    }
}
