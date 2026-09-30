package com.example.x

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URI

object AdBlocker {

    private val BLOCKED_DOMAINS = hashSetOf(
        "analytics.twitter.com",
        "ads-api.twitter.com",
        "ads-twitter.com",
        "p.twitter.com",
        "doubleclick.net",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "app.adjust.com",
        "adnxs.com",
        "criteo.com",
        "scorecardresearch.com",
        "branch.io",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "stats.g.doubleclick.net"
    )

    fun isAdUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains("/flow/login") ||
            lowerUrl.contains("/onboarding/") ||
            lowerUrl.contains("/i/flow/") ||
            lowerUrl.contains("accounts.google.") ||
            lowerUrl.contains("smartlock.google.") ||
            lowerUrl.contains("apis.google.com") ||
            lowerUrl.contains("oauth") ||
            lowerUrl.contains("/gsi/") ||
            lowerUrl.contains("gstatic.com") ||
            lowerUrl.contains("googleapis.com") ||
            lowerUrl.contains("googleusercontent.com") ||
            lowerUrl.contains("appleid.apple.com") ||
            lowerUrl.contains("apple.com")
        ) {
            return false
        }

        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: return false
            BLOCKED_DOMAINS.any { blocked -> host == blocked || host.endsWith(".$blocked") } ||
                    url.contains("/i/ads/") ||
                    url.contains("/telemetry/") ||
                    url.contains("placementTracking")
        } catch (_: Exception) {
            false
        }
    }

    fun createEmptyResource(): WebResourceResponse {
        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    const val AD_BLOCK_SCRIPT: String = """
        (function() {
            if (document.getElementById('__x_adblock_style')) return;

            const style = document.createElement('style');
            style.id = '__x_adblock_style';
            style.textContent = `
                div[data-testid="placementTracking"],
                div[data-testid="cellInnerDiv"]:has(div[data-testid="placementTracking"]),
                div[data-testid="cellInnerDiv"]:has([aria-label*="Promoted"]),
                div[data-testid="cellInnerDiv"]:has([aria-label*="Ad"]),
                div[data-testid="trend"]:has(span:has-text("Promoted")),
                div[data-testid="sheetDialog"]:has(span:has-text("Get the app")),
                div[data-testid="app-install-banner"],
                a[href*="play.google.com/store/apps/details?id=com.twitter.android"] {
                    display: none !important;
                    height: 0px !important;
                    min-height: 0px !important;
                    max-height: 0px !important;
                    margin: 0px !important;
                    padding: 0px !important;
                    overflow: hidden !important;
                    visibility: hidden !important;
                    pointer-events: none !important;
                }
            `;
            (document.head || document.documentElement).appendChild(style);
        })();
    """
}
