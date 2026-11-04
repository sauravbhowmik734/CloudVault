package com.cloudvault.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var loader: FrameLayout
    private lateinit var loaderText: TextView
    private var chooserCb: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private lateinit var root: FrameLayout
    @Volatile private var loaded = false

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        chooserCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        chooserCb = null
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, ViewGroup.LayoutParams(-1, -1))

        loader = FrameLayout(this).apply { setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.vault_bg)) }
        loader.addView(ProgressBar(this), FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        loaderText = TextView(this).apply {
            setTextColor(0xFFB8C4D4.toInt()); textSize = 14f; gravity = android.view.Gravity.CENTER
            setPadding(48, 0, 48, 0); text = "Starting Cloud Vault..."
        }
        loader.addView(loaderText, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER).apply { topMargin = 220 })
        root.addView(loader, ViewGroup.LayoutParams(-1, -1))
        setContentView(root)

        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustom()
                    web.canGoBack() -> web.goBack()
                    else -> moveTaskToBack(true)       // server keeps running in the background
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        askBatteryOnce()

        ServerService.ensurePort(this)
        ContextCompat.startForegroundService(this, Intent(this, ServerService::class.java))
        waitForServerThenLoad()
    }

    private fun askBatteryOnce() {
        val prefs = getSharedPreferences("vault", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) { }
        }
    }

    private fun waitForServerThenLoad() {
        Thread {
            val url = "http://127.0.0.1:${ServerService.port}/"
            val t0 = System.currentTimeMillis()
            while (!loaded) {
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 1000; c.readTimeout = 3000
                    val ok = c.responseCode == 200
                    c.disconnect()
                    if (ok) { loaded = true; runOnUiThread { web.loadUrl(url); loader.visibility = View.GONE }; return@Thread }
                } catch (_: Exception) { }
                if (System.currentTimeMillis() - t0 > 60_000) {
                    val log = File(ServerService.dataDir(this), "android_crash.log")
                    val msg = if (log.exists()) log.readText().takeLast(1500) else "Server did not start. Close and open the app again."
                    runOnUiThread { loaderText.text = msg }
                    break
                }
                Thread.sleep(500)
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(false)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            builtInZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.setBackgroundColor(0xFF0F1722.toInt())
        web.addJavascriptInterface(Bridge(), "AndroidVault")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val h = r.url.host
                if (h == "127.0.0.1" || h == "localhost") return false
                openExternal(r.url); return true
            }
            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                runOnUiThread { recreate() }; return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                chooserCb?.onReceiveValue(null)
                chooserCb = cb
                return try { chooser.launch(p.createIntent()); true } catch (e: Exception) { chooserCb = null; false }
            }
            override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, msg: Message): Boolean {
                val tmp = WebView(this@MainActivity)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(x: WebView, r: WebResourceRequest): Boolean {
                        openExternal(r.url); x.post { x.destroy() }; return true
                    }
                }
                (msg.obj as WebView.WebViewTransport).webView = tmp
                msg.sendToTarget(); return true
            }
            override fun onShowCustomView(view: View, cb: CustomViewCallback) {          // fullscreen video
                if (customView != null) { cb.onCustomViewHidden(); return }
                customView = view; customCb = cb
                root.addView(view, ViewGroup.LayoutParams(-1, -1))
                web.visibility = View.GONE
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
            override fun onHideCustomView() = hideCustom()
        }

        web.setDownloadListener { url, _, cd, mime, _ ->
            val name = URLUtil.guessFileName(url, cd, mime)
            if (url.startsWith("blob:")) fetchBlob(url, name, mime) else downloadHttp(url, name, mime)
        }
    }

    private fun hideCustom() {
        customView?.let { root.removeView(it) }
        customView = null
        customCb?.onCustomViewHidden(); customCb = null
        web.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun openExternal(u: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    // ---- downloads -------------------------------------------------------------------------------

    private fun downloadHttp(url: String, name: String, mime: String?) {
        toast("Downloading $name ...")
        val cookie = CookieManager.getInstance().getCookie(url)
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                if (cookie != null) c.setRequestProperty("Cookie", cookie)
                c.connectTimeout = 10_000; c.readTimeout = 120_000
                c.inputStream.use { saveStream(name, c.contentType ?: mime ?: "application/octet-stream", it) }
            } catch (e: Exception) { toast("Download failed: ${e.message}") }
        }.start()
    }

    private fun fetchBlob(url: String, name: String, mime: String?) {
        val js = """(function(){var x=new XMLHttpRequest();x.open('GET','$url',true);x.responseType='blob';
            x.onload=function(){var r=new FileReader();r.onloadend=function(){
            AndroidVault.saveBlob(r.result.split(',')[1],${org.json.JSONObject.quote(name)},x.response.type||'application/octet-stream')};
            r.readAsDataURL(x.response)};x.send()})()"""
        web.evaluateJavascript(js, null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun saveBlob(b64: String, name: String, mime: String) {
            Thread {
                try { saveStream(name, mime, java.io.ByteArrayInputStream(Base64.decode(b64, Base64.DEFAULT))) }
                catch (e: Exception) { toast("Save failed: ${e.message}") }
            }.start()
        }
    }

    private fun saveStream(name: String, mime: String, input: InputStream) {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw Exception("cannot create file")
            contentResolver.openOutputStream(uri)!!.use { input.copyTo(it) }
            cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, cv, null, null)
            toast("Saved to Downloads: $name")
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
            File(dir, name).outputStream().use { input.copyTo(it) }
            toast("Saved: ${File(dir, name).absolutePath}")
        }
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        chooserCb?.onReceiveValue(null)
        super.onDestroy()
    }
}
