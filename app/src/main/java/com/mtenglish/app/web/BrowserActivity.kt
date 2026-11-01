package com.mtenglish.app.web

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ScriptHandler
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mtenglish.app.BuildConfig
import com.mtenglish.app.MtEnglishApp
import com.mtenglish.app.Prefs
import com.mtenglish.app.R
import com.mtenglish.app.databinding.ActivityBrowserBinding
import com.mtenglish.app.translate.ChineseText
import com.mtenglish.app.ui.SettingsActivity

/**
 * Built-in English browser: a WebView on Meituan's own mobile sites with translator.js injected into
 * every page and frame. Text, attributes, forms and (optionally) images are translated in place; the
 * toolbar can switch back to the original Chinese page at any time.
 */
class BrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        /** Meituan Waimai (food delivery) mobile home, used when no valid URL is passed. */
        const val DEFAULT_URL = "https://h5.waimai.meituan.com/waimai/mindex/home"

        private const val TAG = "BrowserActivity"
        private const val STATE_WEB = "browser_web_state"
        private const val STATE_TRANSLATE = "browser_translate"

        /** External app links are only followed this long after the user touched the page. */
        private const val GESTURE_WINDOW_MS = 5_000L
    }

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var webView: WebView
    private lateinit var prefs: Prefs

    private val imageServer = WebImageServer { MtEnglishApp.instance.imageTranslator }
    private var scriptHandler: ScriptHandler? = null
    private var documentStartSupported = false
    private var webViewDestroyed = false

    private var translationEnabled = true
    private var imagesEnabled = true
    private var longPressEnabled = true
    private var extraTitle: String? = null
    private var lastPageTitle: String? = null
    private var lastTouchAt = 0L

    private var pendingGeolocation: Pair<String, GeolocationPermissions.Callback>? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.any { it }
            pendingGeolocation?.let { (origin, callback) -> callback.invoke(origin, granted, false) }
            pendingGeolocation = null
            if (!granted) toast(R.string.browser_location_denied)
        }

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingFileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
            pendingFileCallback = null
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        prefs = Prefs(this)
        imagesEnabled = prefs.translateImages
        longPressEnabled = prefs.showOriginalOnLongPress
        translationEnabled = savedInstanceState?.getBoolean(STATE_TRANSLATE, true) ?: true
        extraTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }

        webView = binding.browserWebview
        configureWebView()
        WebBridge(
            webView = webView,
            scope = lifecycleScope,
            engine = { MtEnglishApp.instance.engine },
            images = imageServer,
            imagesEnabled = { translationEnabled && imagesEnabled },
            userAgent = webView.settings.userAgentString,
        ).install()
        registerScript()
        setupToolbar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!webViewDestroyed && webView.canGoBack()) webView.goBack() else finish()
            }
        })

        val webState = savedInstanceState?.getBundle(STATE_WEB)
        val restored = webState != null && webView.restoreState(webState) != null
        if (!restored) webView.loadUrl(startUrl())
    }

    override fun onResume() {
        super.onResume()
        if (webViewDestroyed) return
        webView.onResume()
        refreshFromPrefs()
    }

    override fun onPause() {
        if (!webViewDestroyed) webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TRANSLATE, translationEnabled)
        if (!webViewDestroyed) {
            val web = Bundle()
            webView.saveState(web)
            outState.putBundle(STATE_WEB, web)
        }
    }

    override fun onDestroy() {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        pendingGeolocation = null
        destroyWebView()
        super.onDestroy()
    }

    private fun destroyWebView() {
        if (webViewDestroyed) return
        webViewDestroyed = true
        scriptHandler = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun startUrl(): String {
        val raw = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        val scheme = Uri.parse(raw).scheme?.lowercase()
        return if (scheme == "http" || scheme == "https") raw else DEFAULT_URL
    }

    // ------------------------------------------------------------------ WebView setup

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            setGeolocationEnabled(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // target=_blank and window.open load in this same view.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // User agent and Accept-Language stay at the WebView defaults (a regular mobile browser,
            // Chinese content as the site expects).
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchAt = SystemClock.uptimeMillis()
            }
            false
        }
        webView.setDownloadListener { url, _, _, _, _ ->
            toast(R.string.browser_download_unsupported)
            openInExternalBrowser(url)
        }
        webView.webViewClient = Client()
        webView.webChromeClient = ChromeClient()
        installServiceWorkerClient()
    }

    /**
     * A page's service worker can answer image fetches itself (respondWith(fetch(req))); those requests
     * bypass WebViewClient, so the fake translated-image host must also be served to service workers.
     */
    private fun installServiceWorkerClient() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
        ) {
            return
        }
        try {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    imageServer.intercept(request)
            })
        } catch (e: RuntimeException) {
            Log.w(TAG, "Service worker interception unavailable", e)
        }
    }

    /** (Re)registers translator.js as a document-start script with the current settings. */
    private fun registerScript() {
        scriptHandler?.remove()
        scriptHandler = null
        documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStartSupported && translationEnabled) {
            scriptHandler = try {
                WebViewCompat.addDocumentStartJavaScript(webView, script(), TrustedOrigins.ORIGIN_RULES)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Document-start injection unavailable", e)
                documentStartSupported = false
                null
            }
        }
    }

    private fun script(): String = TranslatorScript.build(this, translationEnabled, imagesEnabled, longPressEnabled)

    /** Fallback injection for WebViews without document-start scripts; the script guards against repeats. */
    private fun injectFallback() {
        if (documentStartSupported || !translationEnabled || webViewDestroyed) return
        if (!TrustedOrigins.isTrustedUrl(webView.url)) return
        webView.evaluateJavascript(script(), null)
    }

    private fun runInPage(js: String) {
        if (!webViewDestroyed) webView.evaluateJavascript("try{$js}catch(e){}", null)
    }

    private fun refreshFromPrefs() {
        val images = prefs.translateImages
        val longPress = prefs.showOriginalOnLongPress
        if (images == imagesEnabled && longPress == longPressEnabled) return
        imagesEnabled = images
        longPressEnabled = longPress
        registerScript()
        runInPage("window.__mte&&(window.__mte.setImages($images),window.__mte.setLongPress($longPress))")
        refreshToolbar()
    }

    // ------------------------------------------------------------------ toolbar

    private fun setupToolbar() {
        val toolbar = binding.browserToolbar
        toolbar.title = extraTitle ?: getString(R.string.browser_loading)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.browser_menu)
        toolbar.setOnMenuItemClickListener(::onToolbarItem)
        refreshToolbar()
    }

    private fun onToolbarItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.browser_action_back -> if (webView.canGoBack()) webView.goBack()
            R.id.browser_action_forward -> if (webView.canGoForward()) webView.goForward()
            R.id.browser_action_reload -> webView.reload()
            R.id.browser_action_show_original -> toggleOriginal()
            R.id.browser_action_translate_images -> toggleImages()
            R.id.browser_action_open_external -> webView.url?.let { openInExternalBrowser(it) }
            R.id.browser_action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return false
        }
        return true
    }

    private fun toggleOriginal() {
        translationEnabled = !translationEnabled
        registerScript()
        if (!translationEnabled) runInPage("window.__mte&&window.__mte.setEnabled(false)")
        toast(if (translationEnabled) R.string.browser_translation_on else R.string.browser_showing_original)
        refreshToolbar()
        updateTitle(lastPageTitle)
        webView.reload()
    }

    private fun toggleImages() {
        imagesEnabled = !imagesEnabled
        prefs.translateImages = imagesEnabled
        registerScript()
        runInPage("window.__mte&&window.__mte.setImages($imagesEnabled)")
        toast(if (imagesEnabled) R.string.browser_images_on else R.string.browser_images_off)
        refreshToolbar()
    }

    private fun refreshToolbar() {
        if (!::binding.isInitialized || webViewDestroyed) return
        val menu = binding.browserToolbar.menu
        menu.findItem(R.id.browser_action_back)?.let { setEnabledLook(it, webView.canGoBack()) }
        menu.findItem(R.id.browser_action_forward)?.let { setEnabledLook(it, webView.canGoForward()) }
        menu.findItem(R.id.browser_action_show_original)?.isChecked = !translationEnabled
        menu.findItem(R.id.browser_action_translate_images)?.apply {
            isChecked = imagesEnabled
            isEnabled = translationEnabled
        }
    }

    private fun setEnabledLook(item: MenuItem, enabled: Boolean) {
        item.isEnabled = enabled
        item.icon?.mutate()?.alpha = if (enabled) 255 else 90
    }

    private fun updateTitle(pageTitle: String?) {
        lastPageTitle = pageTitle
        val t = pageTitle?.trim().orEmpty()
        val looksLikeUrl = t.isEmpty() || t.startsWith("http://") || t.startsWith("https://") ||
            t == webView.url || t.startsWith("about:")
        binding.browserToolbar.title = when {
            looksLikeUrl -> extraTitle ?: getString(R.string.browser_loading)
            translationEnabled && ChineseText.containsChinese(t) ->
                MtEnglishApp.instance.engine.peekCached(t) ?: extraTitle ?: t
            else -> t
        }
    }

    private fun updateSubtitle(url: String?) {
        binding.browserToolbar.subtitle = url?.let { Uri.parse(it).host }.orEmpty()
    }

    // ------------------------------------------------------------------ external links

    private fun handleExternal(uri: Uri) {
        when (val r = ExternalLinks.open(this, uri)) {
            ExternalLinks.Result.Launched -> Unit
            is ExternalLinks.Result.Fallback -> webView.loadUrl(r.url)
            ExternalLinks.Result.Unavailable -> toast(R.string.browser_needs_app)
        }
    }

    private fun openInExternalBrowser(url: String) {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.browser_no_external_browser)
        } catch (e: SecurityException) {
            toast(R.string.browser_no_external_browser)
        }
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ clients

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (ExternalLinks.isWebScheme(uri)) return false
            // Pages often try to auto-open the app (imeituan://...) on load; only follow app links the
            // user actually tapped, and never from background frames.
            val recentGesture = request.hasGesture() || SystemClock.uptimeMillis() - lastTouchAt < GESTURE_WINDOW_MS
            if (request.isForMainFrame && recentGesture) handleExternal(uri)
            else Log.d(TAG, "Ignored automatic app link: ${uri.scheme}")
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            imageServer.intercept(request)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            binding.browserProgress.visibility = View.VISIBLE
            updateSubtitle(url)
            injectFallback()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            binding.browserProgress.visibility = View.GONE
            injectFallback()
            refreshToolbar()
            updateTitle(view.title)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            updateSubtitle(url)
            refreshToolbar()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "Renderer gone (crash=${detail.didCrash()})")
            if (view !== webView) return true
            destroyWebView()
            if (!isFinishing && !isDestroyed) {
                toast(R.string.browser_crashed)
                recreate()
            }
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            val bar = binding.browserProgress
            bar.setProgressCompat(newProgress, true)
            bar.visibility = if (newProgress in 0..99) View.VISIBLE else View.GONE
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            updateTitle(title)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (hasLocationPermission()) {
                callback.invoke(origin, true, false)
                return
            }
            pendingGeolocation?.let { (o, cb) -> cb.invoke(o, false, false) }
            pendingGeolocation = origin to callback
            try {
                locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } catch (e: IllegalStateException) {
                pendingGeolocation = null
                callback.invoke(origin, false, false)
            }
        }

        override fun onGeolocationPermissionsHidePrompt() {
            pendingGeolocation = null
        }

        override fun onShowFileChooser(
            view: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = filePathCallback
            try {
                fileChooser.launch(fileChooserParams.createIntent())
            } catch (e: ActivityNotFoundException) {
                pendingFileCallback = null
                filePathCallback.onReceiveValue(null)
                toast(R.string.browser_no_file_picker)
            }
            return true
        }
    }
}
