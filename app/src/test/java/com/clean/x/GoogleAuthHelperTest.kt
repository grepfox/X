package com.clean.x

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class GoogleAuthHelperTest {

    @Test
    fun testBase64UrlEncode_encodesCorrectlyWithoutPadding() {
        val sample = "hello world"
        val encoded = GoogleAuthHelper.base64UrlEncode(sample.toByteArray(Charsets.UTF_8))
        assertEquals("aGVsbG8gd29ybGQ", encoded)
    }

    @Test
    fun testBase64Decode_decodesCorrectly() {
        val original = "CleanX-Test-Payload-12345"
        val b64 = "Q2xlYW5YLVRlc3QtUGF5bG9hZC0xMjM0NQ=="
        val decoded = String(GoogleAuthHelper.base64Decode(b64), Charsets.UTF_8)
        assertEquals(original, decoded)
    }

    @Test
    fun testBuildSsoUrl_producesValidTwitterSsoUrl() {
        val testJwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NSIsImVtYWlsIjoidGVzdEBnbWFpbC5jb20ifQ.sample_signature"
        val testState = "fixed-uuid-state-1234"
        val ssoUrl = GoogleAuthHelper.buildSsoUrl(testJwt, testState)

        assertTrue(ssoUrl.startsWith("https://x.com/i/jf/onboarding/web?mode=sso&input_flow_data="))

        val param = ssoUrl.substringAfter("input_flow_data=")
        val decodedParam = URLDecoder.decode(param, "UTF-8")
        val decodedBytes = GoogleAuthHelper.base64Decode(decodedParam)
        val decodedJson = String(decodedBytes, Charsets.UTF_8)

        assertTrue(decodedJson.contains("\"provider\":\"google\""))
        assertTrue(decodedJson.contains("\"id_token\":\"$testJwt\""))
        assertTrue(decodedJson.contains("\"state\":\"$testState\""))
    }

    @Test
    fun testExtractJwtFromText_findsJwtInHtml() {
        val jwt = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20iLCJzdWIiOiIxMjM0NSJ9.abcdef_12345-UVWXYZ"
        val html = """
            <html>
                <body>
                    <input type="hidden" name="credential" value="$jwt" />
                </body>
            </html>
        """.trimIndent()

        val extracted = GoogleAuthHelper.extractJwtFromText(html)
        assertEquals(jwt, extracted)
    }

    @Test
    fun testExtractJwtFromBootstrap_decodesAndFindsJwt() {
        val jwt = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20iLCJzdWIiOiIxMjM0NSJ9.abcdef_12345-UVWXYZ"
        // Simulate protobuf bytes where tag 0x12 contains the JWT
        val protoBytes = byteArrayOf(0x12, jwt.length.toByte()) + jwt.toByteArray(Charsets.UTF_8)
        val b64Proto = java.util.Base64.getEncoder().encodeToString(protoBytes)

        val html = """
            <!DOCTYPE html>
            <html>
                <body>
                    <script nonce="xyz">gis.provider.transform.bootstrap('$b64Proto');</script>
                </body>
            </html>
        """.trimIndent()

        val extracted = GoogleAuthHelper.extractJwtFromBootstrap(html)
        assertEquals(jwt, extracted)
    }

    @Test
    fun testExtractJwtFromText_returnsNullWhenNoJwt() {
        val html = "<html><body><h1>No token here</h1></body></html>"
        assertNull(GoogleAuthHelper.extractJwtFromText(html))
        assertNull(GoogleAuthHelper.extractJwtFromBootstrap(html))
    }
}
