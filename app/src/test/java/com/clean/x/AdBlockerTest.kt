package com.clean.x

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerTest {

    @Test
    fun testIsAdUrl_blocksKnownAdDomains() {
        assertTrue(AdBlocker.isAdUrl("https://doubleclick.net/ad"))
        assertTrue(AdBlocker.isAdUrl("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(AdBlocker.isAdUrl("https://adnxs.com/seg?add=1"))
        assertTrue(AdBlocker.isAdUrl("https://criteo.com/delivery/ajs.php"))
    }

    @Test
    fun testIsAdUrl_allowsValidTwitterDomains() {
        assertFalse(AdBlocker.isAdUrl("https://x.com/home"))
        assertFalse(AdBlocker.isAdUrl("https://twitter.com/login"))
        assertFalse(AdBlocker.isAdUrl("https://abs.twimg.com/responsive-web/client-web/main.js"))
        assertFalse(AdBlocker.isAdUrl("https://pbs.twimg.com/media/sample.jpg"))
        assertFalse(AdBlocker.isAdUrl("https://analytics.twitter.com/1/jot"))
        assertFalse(AdBlocker.isAdUrl("https://ads-api.twitter.com/graphql"))
        assertFalse(AdBlocker.isAdUrl("https://p.twitter.com/t.gif"))
        assertFalse(AdBlocker.isAdUrl("https://jf.x.com/api/sso"))
    }

    @Test
    fun testIsAdUrl_allowsAuthEndpoints() {
        assertFalse(AdBlocker.isAdUrl("https://accounts.google.com/gsi/client"))
        assertFalse(AdBlocker.isAdUrl("https://accounts.google.co.in/signin/v2/identifier"))
        assertFalse(AdBlocker.isAdUrl("https://smartlock.google.com/auth"))
        assertFalse(AdBlocker.isAdUrl("https://content.googleapis.com/oauth2/v2/auth"))
        assertFalse(AdBlocker.isAdUrl("https://ssl.gstatic.com/accounts/ui/avatar_2x.png"))
        assertFalse(AdBlocker.isAdUrl("https://x.com/i/flow/login"))
        assertFalse(AdBlocker.isAdUrl("https://appleid.apple.com/auth/authorize"))
    }

    @Test
    fun testIsInternalOrAuthHost_allowsAuthAndInternalDomains() {
        assertTrue(MainActivity.isInternalOrAuthHost("x.com"))
        assertTrue(MainActivity.isInternalOrAuthHost("api.x.com"))
        assertTrue(MainActivity.isInternalOrAuthHost("twitter.com"))
        assertTrue(MainActivity.isInternalOrAuthHost("t.co"))
        assertTrue(MainActivity.isInternalOrAuthHost("pbs.twimg.com"))
        assertTrue(MainActivity.isInternalOrAuthHost("accounts.google.com"))
        assertTrue(MainActivity.isInternalOrAuthHost("accounts.google.co.in"))
        assertTrue(MainActivity.isInternalOrAuthHost("accounts.google.co.uk"))
        assertTrue(MainActivity.isInternalOrAuthHost("google.com"))
        assertTrue(MainActivity.isInternalOrAuthHost("google.co.in"))
        assertTrue(MainActivity.isInternalOrAuthHost("smartlock.google.com"))
        assertTrue(MainActivity.isInternalOrAuthHost("apis.google.com"))
        assertTrue(MainActivity.isInternalOrAuthHost("oauth2.googleapis.com"))
        assertTrue(MainActivity.isInternalOrAuthHost("ssl.gstatic.com"))
        assertTrue(MainActivity.isInternalOrAuthHost("accounts.youtube.com"))
        assertTrue(MainActivity.isInternalOrAuthHost("lh3.googleusercontent.com"))
        assertTrue(MainActivity.isInternalOrAuthHost("appleid.apple.com"))
        assertTrue(MainActivity.isInternalOrAuthHost("apple.com"))
    }

    @Test
    fun testIsInternalOrAuthHost_rejectsExternalDomains() {
        assertFalse(MainActivity.isInternalOrAuthHost("github.com"))
        assertFalse(MainActivity.isInternalOrAuthHost("facebook.com"))
        assertFalse(MainActivity.isInternalOrAuthHost("nytimes.com"))
    }
}
