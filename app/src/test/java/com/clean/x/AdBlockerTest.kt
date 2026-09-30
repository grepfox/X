package com.clean.x

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerTest {

    @Test
    fun testIsAdUrl_blocksKnownAdDomains() {
        assertTrue(AdBlocker.isAdUrl("https://analytics.twitter.com/1/jot"))
        assertTrue(AdBlocker.isAdUrl("https://ads-api.twitter.com/graphql"))
        assertTrue(AdBlocker.isAdUrl("https://p.twitter.com/t.gif"))
        assertTrue(AdBlocker.isAdUrl("https://stats.g.doubleclick.net/r/collect"))
        assertTrue(AdBlocker.isAdUrl("https://x.com/i/ads/attribution"))
    }

    @Test
    fun testIsAdUrl_allowsValidTwitterDomains() {
        assertFalse(AdBlocker.isAdUrl("https://x.com/home"))
        assertFalse(AdBlocker.isAdUrl("https://twitter.com/login"))
        assertFalse(AdBlocker.isAdUrl("https://abs.twimg.com/responsive-web/client-web/main.js"))
        assertFalse(AdBlocker.isAdUrl("https://pbs.twimg.com/media/sample.jpg"))
    }
}
