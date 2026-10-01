package com.clean.x

import java.net.URLEncoder
import java.util.UUID

object GoogleAuthHelper {

    private val JWT_REGEX = Regex("""(eyJ[a-zA-Z0-9_-]{10,}\.eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]+)""")
    private val BOOTSTRAP_REGEX = Regex("""bootstrap\(['"]([A-Za-z0-9+/=_-]{20,})['"]\)""")

    fun base64UrlEncode(bytes: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1

            sb.append(table[b0 ushr 2])
            if (b1 != -1) {
                sb.append(table[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                if (b2 != -1) {
                    sb.append(table[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
                    sb.append(table[b2 and 0x3F])
                } else {
                    sb.append(table[(b1 and 0x0F) shl 2])
                }
            } else {
                sb.append(table[(b0 and 0x03) shl 4])
            }
            i += 3
        }
        return sb.toString()
    }

    fun base64Decode(str: String): ByteArray {
        val clean = str.trim().replace("\n", "").replace("\r", "")
        val table = IntArray(256) { -1 }
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in chars.indices) {
            table[chars[i].code] = i
        }
        table['-'.code] = 62
        table['_'.code] = 63

        val out = ArrayList<Byte>()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val v = if (ch.code < 256) table[ch.code] else -1
            if (v >= 0) {
                buffer = (buffer shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.add(((buffer ushr bits) and 0xFF).toByte())
                }
            }
        }
        return out.toByteArray()
    }

    fun buildSsoUrl(idToken: String, state: String = UUID.randomUUID().toString()): String {
        val jsonPayload = """{"provider":"google","id_token":"$idToken","state":"$state"}"""
        val encodedPayload = base64UrlEncode(jsonPayload.toByteArray(Charsets.UTF_8))
        val urlParam = URLEncoder.encode(encodedPayload, "UTF-8")
        return "https://x.com/i/jf/onboarding/web?mode=sso&input_flow_data=$urlParam"
    }

    fun extractJwtFromText(text: String): String? {
        val match = JWT_REGEX.find(text)
        return match?.groupValues?.get(1)
    }

    fun extractJwtFromBootstrap(rawHtml: String): String? {
        val bMatch = BOOTSTRAP_REGEX.find(rawHtml) ?: return null
        val b64 = bMatch.groupValues[1]
        try {
            val decodedBytes = base64Decode(b64)
            val decodedString = String(decodedBytes, Charsets.ISO_8859_1)
            val jwtMatch = JWT_REGEX.find(decodedString)
            if (jwtMatch != null) {
                return jwtMatch.groupValues[1]
            }
        } catch (_: Exception) {}
        return null
    }

    val MAIN_GSI_HOOK_SCRIPT: String = """
        (function() {
            if (window.__cleanx_gsi_installed) return;
            window.__cleanx_gsi_installed = true;

            // 1. Transparently rewrite broken /onboarding/web/sso to working /onboarding/web in all client fetch/XHR
            try {
                const origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function(input, init) {
                        try {
                            if (typeof input === 'string' && input.includes('/onboarding/web/sso')) {
                                input = input.replace('/onboarding/web/sso', '/onboarding/web');
                            } else if (input && typeof input.url === 'string' && input.url.includes('/onboarding/web/sso')) {
                                input = new Request(input.url.replace('/onboarding/web/sso', '/onboarding/web'), input);
                            }
                        } catch(e) {}
                        return origFetch.call(this, input, init);
                    };
                }

                const origXhrOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    try {
                        if (typeof url === 'string' && url.includes('/onboarding/web/sso')) {
                            arguments[1] = url.replace('/onboarding/web/sso', '/onboarding/web');
                        }
                    } catch(e) {}
                    return origXhrOpen.apply(this, arguments);
                };

                const origPushState = history.pushState;
                if (origPushState) {
                    history.pushState = function(state, title, url) {
                        try {
                            if (typeof url === 'string' && url.includes('/i/jf/onboarding/web/sso')) {
                                url = url.replace('/i/jf/onboarding/web/sso', '/i/jf/onboarding/web');
                            }
                        } catch(e) {}
                        return origPushState.call(this, state, title, url);
                    };
                }

                const origReplaceState = history.replaceState;
                if (origReplaceState) {
                    history.replaceState = function(state, title, url) {
                        try {
                            if (typeof url === 'string' && url.includes('/i/jf/onboarding/web/sso')) {
                                url = url.replace('/i/jf/onboarding/web/sso', '/i/jf/onboarding/web');
                            }
                        } catch(e) {}
                        return origReplaceState.call(this, state, title, url);
                    };
                }
            } catch(e) {}

            // 2. Comprehensive error logging to capture exact failure reasons
            try {
                window.addEventListener('error', function(e) {
                    console.log('[CleanX-Error] ' + (e.message || e.error) + (e.error && e.error.stack ? '\n' + e.error.stack : ''));
                });
                window.addEventListener('unhandledrejection', function(e) {
                    console.log('[CleanX-UnhandledRejection] ' + (e.reason ? (e.reason.message || e.reason) + (e.reason.stack ? '\n' + e.reason.stack : '') : ''));
                });
            } catch(e) {}

            // 3. Deep hook into Google GSI initialization to capture callback and client instance
            function hookIdObject(idObj) {
                if (!idObj || idObj.__cleanx_hooked) return;
                idObj.__cleanx_hooked = true;

                let originalInit = idObj.initialize;
                function wrapInit(fn) {
                    return function(config) {
                        console.log('[CleanX] Captured google.accounts.id.initialize!');
                        if (config && typeof config.callback === 'function') {
                            window.__x_gsi_callback = config.callback;
                            console.log('[CleanX] Successfully hooked GSI callback!');
                        }
                        if (fn) {
                            return fn.apply(this, arguments);
                        }
                    };
                }

                try {
                    Object.defineProperty(idObj, 'initialize', {
                        configurable: true,
                        enumerable: true,
                        get: function() { return originalInit; },
                        set: function(newFn) {
                            originalInit = wrapInit(newFn);
                        }
                    });
                    if (originalInit) {
                        idObj.initialize = originalInit;
                    }
                } catch(e) {
                    if (originalInit) {
                        idObj.initialize = wrapInit(originalInit);
                    }
                }
            }

            try {
                window.google = window.google || {};
                let _accounts = window.google.accounts || {};
                let _id = _accounts.id || {};

                hookIdObject(_id);

                Object.defineProperty(_accounts, 'id', {
                    configurable: true,
                    enumerable: true,
                    get: function() { return _id; },
                    set: function(val) {
                        _id = val;
                        hookIdObject(_id);
                    }
                });

                Object.defineProperty(window.google, 'accounts', {
                    configurable: true,
                    enumerable: true,
                    get: function() { return _accounts; },
                    set: function(val) {
                        _accounts = val;
                        if (_accounts) {
                            if (_accounts.id) {
                                _id = _accounts.id;
                                hookIdObject(_id);
                            }
                            try {
                                Object.defineProperty(_accounts, 'id', {
                                    configurable: true,
                                    enumerable: true,
                                    get: function() { return _id; },
                                    set: function(idVal) {
                                        _id = idVal;
                                        hookIdObject(_id);
                                    }
                                });
                            } catch(e) {}
                        }
                    }
                });
            } catch(e) {}
        })();
    """.trimIndent()

    val POPUP_BRIDGE_SCRIPT: String = """
        (function() {
            function report(token) {
                if (!token || typeof token !== 'string') return;
                let clean = token.trim();
                if (clean.startsWith('eyJ') && clean.split('.').length >= 3) {
                    console.log('[PopupBridge] Found Google JWT: ' + clean.substring(0, 20) + '...');
                    if (window.AndroidGoogleBridge) {
                        window.AndroidGoogleBridge.onGoogleCredential(clean);
                    }
                }
            }

            // 1. Install mock opener to satisfy GSI postMessage channel
            if (!window.opener || typeof window.opener.postMessage !== 'function') {
                window.opener = {
                    postMessage: function(data, targetOrigin, transfer) {
                        console.log('[PopupBridge] Opener postMessage called:', JSON.stringify(data), targetOrigin);
                        if (data && data.type === 'readyForConnect') {
                            let channel = new MessageChannel();
                            channel.port1.onmessage = function(e) {
                                console.log('[PopupBridge] Channel response received:', JSON.stringify(e.data));
                                if (e.data && e.data.type === 'response') {
                                    let resp = e.data.response;
                                    if (resp && resp.credential) {
                                        report(resp.credential);
                                    }
                                }
                            };
                            try {
                                window.postMessage({
                                    type: 'channelConnect',
                                    nonce: data.channelId || ''
                                }, '*', [channel.port2]);
                            } catch(err) {
                                console.error('[PopupBridge] channelConnect error:', err);
                            }
                        }
                    },
                    closed: false
                };
            }

            // 2. Scan DOM and scripts for JWT
            function scan() {
                let html = document.documentElement ? document.documentElement.innerHTML : '';
                let match = html.match(/(eyJ[a-zA-Z0-9_-]{10,}\.eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]+)/);
                if (match) {
                    report(match[1]);
                    return;
                }

                let bMatch = html.match(/bootstrap\(['"]([A-Za-z0-9+/=_-]{20,})['"]\)/);
                if (bMatch) {
                    try {
                        let raw = atob(bMatch[1]);
                        let rawMatch = raw.match(/(eyJ[a-zA-Z0-9_-]{10,}\.eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]+)/);
                        if (rawMatch) {
                            report(rawMatch[1]);
                            return;
                        }
                    } catch(e) {}
                }

                let inputs = document.querySelectorAll('input, textarea');
                for (let i = 0; i < inputs.length; i++) {
                    let val = inputs[i].value;
                    if (val && val.startsWith('eyJ') && val.split('.').length >= 3) {
                        report(val);
                        return;
                    }
                }
            }

            scan();
            setTimeout(scan, 200);
            setTimeout(scan, 600);
            setTimeout(scan, 1200);
        })();
    """.trimIndent()

    fun buildDeliverCredentialJs(credential: String): String {
        return """
            (function() {
                let cred = '$credential';
                let called = false;

                // Strategy 1: Captured initialize callback
                if (typeof window.__x_gsi_callback === 'function') {
                    try {
                        console.log("[CleanX] Invoking window.__x_gsi_callback with Google credential");
                        window.__x_gsi_callback({ credential: cred, select_by: 'btn' });
                        called = true;
                    } catch(e) {
                        console.error("[CleanX] Error in __x_gsi_callback:", e);
                    }
                }

                // Strategy 2: Google GSI Client instance callback
                if (!called && window.__G_ID_CLIENT__ && typeof window.__G_ID_CLIENT__.callback === 'function') {
                    try {
                        console.log("[CleanX] Invoking window.__G_ID_CLIENT__.callback with Google credential");
                        window.__G_ID_CLIENT__.callback.call(window.__G_ID_CLIENT__, { credential: cred, select_by: 'btn' });
                        called = true;
                    } catch(e) {
                        console.error("[CleanX] Error in __G_ID_CLIENT__.callback:", e);
                    }
                }

                function buildWorkingSsoUrl(idToken) {
                    let payload = JSON.stringify({
                        provider: 'google',
                        id_token: idToken,
                        state: (crypto && crypto.randomUUID) ? crypto.randomUUID() : ('x-' + Date.now())
                    });
                    let encoded;
                    try {
                        let bytes = new TextEncoder().encode(payload);
                        let binary = '';
                        for (let b of bytes) binary += String.fromCharCode(b);
                        encoded = btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
                    } catch(e) {
                        encoded = btoa(payload).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
                    }
                    return 'https://x.com/i/jf/onboarding/web?mode=sso&input_flow_data=' + encodeURIComponent(encoded);
                }

                if (!called) {
                    let ssoUrl = buildWorkingSsoUrl(cred);
                    console.log("[CleanX] Direct navigation to working SSO URL:", ssoUrl);
                    window.location.assign(ssoUrl);
                } else {
                    setTimeout(function() {
                        let path = window.location.pathname;
                        if (path.includes('login') || path === '/' || path.includes('/sso')) {
                            let ssoUrl = buildWorkingSsoUrl(cred);
                            console.log("[CleanX] Callback check after delay, navigating to working SSO URL:", ssoUrl);
                            window.location.assign(ssoUrl);
                        }
                    }, 1200);
                }
            })();
        """.trimIndent()
    }
}
