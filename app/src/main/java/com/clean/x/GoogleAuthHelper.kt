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
        return "https://x.com/i/jf/onboarding/web/sso?mode=sso&input_flow_data=$urlParam"
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

            // 1. Transparently rewrite broken /onboarding/web/sso API requests to working /onboarding/web
            try {
                const origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function(input, init) {
                        try {
                            let urlStr = (typeof input === 'string') ? input :
                                         (input instanceof URL) ? input.href :
                                         (input && input.url) ? input.url : '';
                            if (urlStr.includes('/onboarding/web/sso')) {
                                let fixedUrl = urlStr.replace('/onboarding/web/sso', '/onboarding/web');
                                console.log('[CleanX] Rewrote JetFuel fetch from:', urlStr, 'to:', fixedUrl);
                                if (typeof input === 'string') {
                                    input = fixedUrl;
                                } else if (input instanceof URL) {
                                    input = new URL(fixedUrl);
                                } else if (input && input.url) {
                                    input = new Request(fixedUrl, input);
                                }
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
        })();
    """.trimIndent()

    // Captures the REAL X SSO/onboarding network handshake (method, url, request body, status,
    // response body) and pipes it to the native AndroidNetLog bridge. shouldInterceptRequest
    // cannot see POST bodies, so this JS wrapper is the only way to observe the flow_token +
    // credential submission that establishes (or rejects) the session.
    val NET_LOG_SCRIPT: String = """
        (function() {
            if (window.__cleanx_netlog_installed) return;
            window.__cleanx_netlog_installed = true;

            var MAX = 6000;
            function interesting(u) {
                if (!u) return false;
                u = ('' + u).toLowerCase();
                return u.indexOf('onboarding') >= 0 ||
                       u.indexOf('task.json') >= 0 ||
                       u.indexOf('jf.x.com') >= 0 ||
                       u.indexOf('/sso') >= 0 ||
                       u.indexOf('js_instrumentation') >= 0 ||
                       u.indexOf('guest') >= 0 ||
                       u.indexOf('oauth2') >= 0 ||
                       u.indexOf('accounts.google.com/gsi') >= 0;
            }
            function trunc(s) {
                if (s == null) return '';
                s = '' + s;
                return s.length > MAX ? s.substring(0, MAX) + '...<' + s.length + 'b>' : s;
            }
            function emit(obj) {
                try {
                    var line = JSON.stringify(obj);
                    if (window.AndroidNetLog && window.AndroidNetLog.log) {
                        window.AndroidNetLog.log(line);
                    } else {
                        console.log('[NetLog] ' + line);
                    }
                } catch (e) {}
            }
            function bodyToStr(body) {
                try {
                    if (body == null) return '';
                    if (typeof body === 'string') return body;
                    if (body instanceof URLSearchParams) return body.toString();
                    if (typeof FormData !== 'undefined' && body instanceof FormData) {
                        var parts = [];
                        body.forEach(function(v, k) { parts.push(k + '=' + (typeof v === 'string' ? v : '[file]')); });
                        return parts.join('&');
                    }
                    return '[' + (body.constructor ? body.constructor.name : typeof body) + ']';
                } catch (e) { return '[unserializable]'; }
            }

            // ---- fetch ----
            try {
                var origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function(input, init) {
                        var url = (typeof input === 'string') ? input :
                                  (input instanceof URL) ? input.href :
                                  (input && input.url) ? input.url : '';
                        var method = (init && init.method) || (input && input.method) || 'GET';
                        var reqBody = init && init.body ? bodyToStr(init.body) : '';
                        var track = interesting(url);
                        if (track) {
                            emit({ k: 'fetch-req', method: method, url: url, body: trunc(reqBody) });
                        }
                        var p = origFetch.call(this, input, init);
                        if (track) {
                            p.then(function(resp) {
                                try {
                                    var c = resp.clone();
                                    c.text().then(function(t) {
                                        emit({ k: 'fetch-res', url: url, status: resp.status, body: trunc(t) });
                                    }).catch(function() {
                                        emit({ k: 'fetch-res', url: url, status: resp.status, body: '[unreadable]' });
                                    });
                                } catch (e) {
                                    emit({ k: 'fetch-res', url: url, status: resp.status, body: '[clone-failed]' });
                                }
                            }).catch(function(err) {
                                emit({ k: 'fetch-err', url: url, error: '' + err });
                            });
                        }
                        return p;
                    };
                }
            } catch (e) {}

            // ---- XMLHttpRequest ----
            try {
                var origOpen = XMLHttpRequest.prototype.open;
                var origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__cleanx_method = method;
                    this.__cleanx_url = url;
                    this.__cleanx_track = interesting(url);
                    return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function(body) {
                    var self = this;
                    if (self.__cleanx_track) {
                        emit({ k: 'xhr-req', method: self.__cleanx_method, url: self.__cleanx_url, body: trunc(bodyToStr(body)) });
                        self.addEventListener('loadend', function() {
                            var rt = '';
                            try { rt = (self.responseType === '' || self.responseType === 'text') ? self.responseText : '[' + self.responseType + ']'; } catch (e) { rt = '[unreadable]'; }
                            emit({ k: 'xhr-res', url: self.__cleanx_url, status: self.status, body: trunc(rt) });
                        });
                    }
                    return origSend.apply(this, arguments);
                };
            } catch (e) {}
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
                    return 'https://x.com/i/jf/onboarding/web/sso?mode=sso&input_flow_data=' + encodeURIComponent(encoded);
                }

                let ssoUrl = buildWorkingSsoUrl(cred);
                console.log("[CleanX] Direct navigation to working SSO URL:", ssoUrl);
                window.location.assign(ssoUrl);
            })();
        """.trimIndent()
    }
}
