package com.clean.x

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var splashView: FrameLayout
    private lateinit var btnRetry: Button

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserLauncher: ActivityResultLauncher<Intent>? = null
    private var storagePermissionLauncher: ActivityResultLauncher<String>? = null
    private var pendingDownloadAction: (() -> Unit)? = null

    private val targetUrl = "https://x.com"
    private var isFirstLoad = true
    private var popupDialog: Dialog? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "XApp"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        private val MEDIA_INSPECTION_SCRIPT = """
            (function() {
                if (window.__x_media_listener_installed) return;
                window.__x_media_listener_installed = true;

                let lastLongPressTarget = null;
                let pressTimer = null;

                function getMediaInfo(element) {
                    if (!element) return null;

                    // Direct video or inside video container
                    let video = element.closest('video') || element.querySelector('video');
                    if (!video) {
                        let videoContainer = element.closest('div[data-testid*="video"], div[aria-label*="video"], div[data-testid="tweetPhoto"]');
                        if (videoContainer) {
                            video = videoContainer.querySelector('video');
                        }
                    }
                    if (video) {
                        let src = video.currentSrc || video.src || '';
                        if (!src) {
                            let source = video.querySelector('source');
                            if (source) src = source.src || '';
                        }
                        if (src) {
                            return { type: 'video', url: src };
                        }
                    }

                    // Direct image or inside tweetPhoto / image container
                    let img = element.closest('img') || element.querySelector('img');
                    if (!img) {
                        let photoContainer = element.closest('div[data-testid="tweetPhoto"], div[data-testid*="image"]');
                        if (photoContainer) {
                            img = photoContainer.querySelector('img');
                        }
                    }
                    if (img && img.src && !img.src.includes('profile_images') && !img.src.includes('emoji')) {
                        return { type: 'image', url: img.src };
                    }

                    return null;
                }

                document.addEventListener('touchstart', function(e) {
                    lastLongPressTarget = e.target;
                }, { passive: true });

                document.addEventListener('contextmenu', function(e) {
                    let media = getMediaInfo(e.target || lastLongPressTarget);
                    if (media && window.AndroidMediaHandler) {
                        window.AndroidMediaHandler.onMediaLongClick(media.type, media.url);
                    }
                }, false);
            })();
        """.trimIndent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        initViews()
        setupWindowInsets()
        setupLaunchers()
        setupWebView()
        setupBackNavigation()

        val incomingUrl = extractValidUrl(intent)
        if (incomingUrl != null) {
            Log.d(TAG, "Opening external link on launch: $incomingUrl")
            webView.loadUrl(incomingUrl)
        } else if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(targetUrl)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val incomingUrl = extractValidUrl(intent)
        if (incomingUrl != null) {
            Log.d(TAG, "Opening external link on new intent: $incomingUrl")
            webView.loadUrl(incomingUrl)
        }
    }

    private fun extractValidUrl(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        val scheme = uri.scheme?.lowercase() ?: ""
        val host = uri.host?.lowercase() ?: ""

        if (scheme == "http" || scheme == "https") {
            val isTwitterDomain = host == "x.com" || host.endsWith(".x.com") ||
                    host == "twitter.com" || host.endsWith(".twitter.com") ||
                    host == "t.co" || host.endsWith(".t.co")
            if (isTwitterDomain) {
                return uri.toString()
            }
        }
        return null
    }

    private fun initViews() {
        rootContainer = findViewById(R.id.rootContainer)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        splashView = findViewById(R.id.splashView)
        btnRetry = findViewById(R.id.btnRetry)

        btnRetry.setOnClickListener {
            errorView.visibility = View.GONE
            webView.reload()
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            rootContainer.setPadding(0, statusBars.top, 0, 0)
            insets
        }
    }

    private fun setupLaunchers() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (filePathCallback != null) {
                val results: Array<Uri>? = when {
                    result.resultCode == RESULT_OK && result.data != null -> {
                        val clipData = result.data?.clipData
                        if (clipData != null && clipData.itemCount > 0) {
                            Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                        } else {
                            result.data?.data?.let { arrayOf(it) }
                        }
                    }
                    else -> null
                }
                filePathCallback?.onReceiveValue(results)
                filePathCallback = null
            }
        }

        storagePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                pendingDownloadAction?.invoke()
            } else {
                Toast.makeText(this, getString(R.string.storage_permission_required), Toast.LENGTH_SHORT).show()
            }
            pendingDownloadAction = null
        }
    }

    private fun checkStoragePermissionAndExecute(action: () -> Unit) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                action()
            } else {
                pendingDownloadAction = action
                storagePermissionLauncher?.launch(permission)
            }
        } else {
            action()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        configureWebSettings(webView.settings)

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onMediaLongClick(type: String, url: String) {
                mainHandler.post {
                    showMediaOptionsDialog(type, url)
                }
            }

            @JavascriptInterface
            fun saveBlobData(base64Data: String, mimeType: String, suggestedName: String) {
                mainHandler.post {
                    saveBase64ToDownloads(base64Data, mimeType, suggestedName)
                }
            }
        }, "AndroidMediaHandler")

        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val extra = result.extra
            when (result.type) {
                WebView.HitTestResult.IMAGE_TYPE -> {
                    if (!extra.isNullOrBlank()) {
                        showMediaOptionsDialog("image", extra)
                        true
                    } else {
                        false
                    }
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    if (!extra.isNullOrBlank()) {
                        showMediaOptionsDialog("image", extra)
                        true
                    } else {
                        false
                    }
                }
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    if (!extra.isNullOrBlank() && isDirectMediaUrl(extra)) {
                        val isVideo = extra.contains(".mp4", ignoreCase = true) || extra.contains(".m3u8", ignoreCase = true)
                        showMediaOptionsDialog(if (isVideo) "video" else "image", extra)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (AdBlocker.isAdUrl(url)) {
                    return AdBlocker.createEmptyResource()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                errorView.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE

                CookieManager.getInstance().flush()

                view?.evaluateJavascript(AdBlocker.AD_BLOCK_SCRIPT, null)
                view?.evaluateJavascript(MEDIA_INSPECTION_SCRIPT, null)

                if (isFirstLoad) {
                    isFirstLoad = false
                    splashView.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .withEndAction {
                            splashView.visibility = View.GONE
                            try {
                                rootContainer.removeView(splashView)
                            } catch (_: Exception) {}
                        }
                        .start()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    errorView.visibility = View.VISIBLE
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: ""
                val host = uri.host?.lowercase() ?: ""

                // Handle intent:// and other custom schemes
                if (scheme == "intent") {
                    return try {
                        val parsedIntent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        val fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url")
                        if (!fallbackUrl.isNullOrEmpty()) {
                            view?.loadUrl(fallbackUrl)
                            true
                        } else {
                            startActivity(parsedIntent)
                            true
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing intent scheme: ${e.message}")
                        false
                    }
                }

                val isInternalOrAuth = host == "x.com" || host.endsWith(".x.com") ||
                        host == "twitter.com" || host.endsWith(".twitter.com") ||
                        host == "t.co" || host.endsWith(".t.co") ||
                        host.endsWith("twimg.com") ||
                        host.contains("accounts.google.com") ||
                        host.contains("google.com") ||
                        host.contains("googleapis.com") ||
                        host.contains("gstatic.com") ||
                        host.contains("appleid.apple.com")

                if (isInternalOrAuth) {
                    return false
                }

                return try {
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val popupWebView = WebView(this@MainActivity)
                configureWebSettings(popupWebView.settings)
                popupWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                CookieManager.getInstance().setAcceptThirdPartyCookies(popupWebView, true)

                popupWebView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView?, url: String?) {
                        super.onPageFinished(v, url)
                        CookieManager.getInstance().flush()

                        // If popup completed login and redirected back to twitter/x home
                        if (url != null) {
                            val uri = Uri.parse(url)
                            val host = uri.host?.lowercase() ?: ""
                            val isTwitter = host == "x.com" || host.endsWith(".x.com") ||
                                    host == "twitter.com" || host.endsWith(".twitter.com")
                            if (isTwitter && (uri.path == "/home" || uri.path == "/" || uri.path?.contains("/login/success") == true)) {
                                popupDialog?.dismiss()
                                popupDialog = null
                                webView.reload()
                            }
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        req: WebResourceRequest?
                    ): Boolean {
                        val uri = req?.url ?: return false
                        val scheme = uri.scheme?.lowercase() ?: ""
                        val host = uri.host?.lowercase() ?: ""

                        if (scheme == "intent") {
                            return try {
                                val parsedIntent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                                val fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url")
                                if (!fallbackUrl.isNullOrEmpty()) {
                                    v?.loadUrl(fallbackUrl)
                                    true
                                } else {
                                    startActivity(parsedIntent)
                                    true
                                }
                            } catch (_: Exception) {
                                false
                            }
                        }

                        val isAuthOrInternal = host == "x.com" || host.endsWith(".x.com") ||
                                host == "twitter.com" || host.endsWith(".twitter.com") ||
                                host == "t.co" || host.endsWith(".t.co") ||
                                host.contains("accounts.google.com") ||
                                host.contains("google.com") ||
                                host.contains("googleapis.com") ||
                                host.contains("gstatic.com") ||
                                host.contains("appleid.apple.com")

                        if (isAuthOrInternal) {
                            return false
                        }

                        return try {
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            startActivity(intent)
                            true
                        } catch (e: Exception) {
                            false
                        }
                    }
                }

                popupWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        popupDialog?.dismiss()
                        popupDialog = null
                        webView.reload()
                    }
                }

                popupDialog?.dismiss()
                popupDialog = Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                    setContentView(popupWebView, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    setOnDismissListener {
                        CookieManager.getInstance().flush()
                        popupWebView.destroy()
                    }
                    show()
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popupWebView
                resultMsg?.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                popupDialog?.dismiss()
                popupDialog = null
            }

            override fun onShowFileChooser(
                mWebView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                try {
                    fileChooserLauncher?.launch(intent)
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    return false
                }
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            startFileDownload(url, userAgent, contentDisposition, mimetype)
        }
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
                lower.contains(".webp") || lower.contains(".gif") || lower.contains(".mp4") ||
                lower.contains("pbs.twimg.com/media") || lower.contains("video.twimg.com")
    }

    private fun showMediaOptionsDialog(type: String, url: String) {
        val isVideo = type.equals("video", ignoreCase = true)
        val title = if (isVideo) getString(R.string.save_video) else getString(R.string.save_image)
        val copyTitle = if (isVideo) getString(R.string.copy_video_link) else getString(R.string.copy_image_link)

        val options = arrayOf(title, copyTitle)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> { // Save
                        checkStoragePermissionAndExecute {
                            saveMediaUrl(type, url)
                        }
                    }
                    1 -> { // Copy link
                        copyToClipboard(url)
                    }
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Media URL", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
    }

    private fun saveMediaUrl(type: String, url: String) {
        var cleanUrl = url
        val isVideo = type.equals("video", ignoreCase = true)

        if (!isVideo && cleanUrl.contains("pbs.twimg.com/media/")) {
            // Upgrade image resolution from :thumb / :small to :orig if applicable
            if (cleanUrl.contains("format=")) {
                cleanUrl = cleanUrl.replace(Regex("&name=[a-zA-Z0-9]+"), "&name=orig")
                if (!cleanUrl.contains("&name=orig")) {
                    cleanUrl += "&name=orig"
                }
            } else if (cleanUrl.contains(":")) {
                cleanUrl = cleanUrl.substringBeforeLast(":") + ":orig"
            }
        }

        if (cleanUrl.startsWith("blob:")) {
            downloadBlobUrl(cleanUrl, if (isVideo) "video/mp4" else "image/jpeg")
            return
        }

        val mimeType = if (isVideo) "video/mp4" else "image/jpeg"
        val extension = if (isVideo) ".mp4" else ".jpg"
        val fileName = "x_${System.currentTimeMillis()}$extension"

        startFileDownload(cleanUrl, USER_AGENT, "attachment; filename=\"$fileName\"", mimeType)
    }

    private fun downloadBlobUrl(blobUrl: String, mimeType: String) {
        Toast.makeText(this, getString(R.string.saving_media), Toast.LENGTH_SHORT).show()
        val ext = if (mimeType.contains("video")) ".mp4" else ".jpg"
        val suggestedName = "x_${System.currentTimeMillis()}$ext"

        val js = """
            (function() {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', '$blobUrl', true);
                xhr.responseType = 'blob';
                xhr.onload = function(e) {
                    if (this.status == 200 || this.status == 0) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = reader.result.split(',')[1];
                            if (window.AndroidMediaHandler) {
                                window.AndroidMediaHandler.saveBlobData(base64, '$mimeType', '$suggestedName');
                            }
                        };
                        reader.readAsDataURL(this.response);
                    }
                };
                xhr.onerror = function() {
                    console.error('Failed to fetch blob');
                };
                xhr.send();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun saveBase64ToDownloads(base64Data: String, mimeType: String, suggestedName: String) {
        backgroundExecutor.execute {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, suggestedName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val collection = if (mimeType.contains("video")) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }
                    resolver.insert(collection, contentValues)
                } else {
                    val targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!targetDir.exists()) targetDir.mkdirs()
                    val targetFile = File(targetDir, suggestedName)
                    FileOutputStream(targetFile).use { it.write(bytes) }
                    null
                }

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                    }
                }

                mainHandler.post {
                    Toast.makeText(this@MainActivity, getString(R.string.saved_to_downloads), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving base64: ${e.message}", e)
                mainHandler.post {
                    Toast.makeText(this@MainActivity, "${getString(R.string.download_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startFileDownload(url: String, userAgent: String, contentDisposition: String?, mimetype: String?) {
        checkStoragePermissionAndExecute {
            try {
                val resolvedMime = mimetype ?: if (url.contains(".mp4", ignoreCase = true)) "video/mp4" else "image/jpeg"
                val guessedFileName = URLUtil.guessFileName(url, contentDisposition, resolvedMime)

                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(resolvedMime)
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (!cookie.isNullOrEmpty()) {
                        addRequestHeader("cookie", cookie)
                    }
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("Downloading media from X...")
                    setTitle(guessedFileName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        guessedFileName
                    )
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                val toastMsg = if (resolvedMime.contains("video")) getString(R.string.downloading_video) else getString(R.string.downloading_image)
                Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                Toast.makeText(this, "${getString(R.string.download_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.userAgentString = USER_AGENT
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (popupDialog?.isShowing == true) {
                    popupDialog?.dismiss()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        popupDialog?.dismiss()
        webView.destroy()
        backgroundExecutor.shutdown()
        super.onDestroy()
    }
}
