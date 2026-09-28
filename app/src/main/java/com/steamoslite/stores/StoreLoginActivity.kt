package com.steamoslite.stores

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContract
import com.steamoslite.stores.epic.EpicConstants
import com.steamoslite.stores.gog.GOGConstants

/**
 * The store's own sign-in page in a WebView. Returns the OAuth authorization code: GOG puts it in
 * the redirect URL, Epic in the redirect page's body (a JSON object), so both are checked. The
 * state parameter must come back unchanged, or the redirect is ignored.
 */
class StoreLoginActivity : Activity() {
    private lateinit var store: Store
    private var state: String = ""
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.entries.first { it.id == intent.getStringExtra(EXTRA_STORE) }
        val (url, st) = savedInstanceState?.let { it.getString(SAVED_URL)!! to it.getString(SAVED_STATE)!! }
            ?: if (store == Store.GOG) GOGConstants.LoginUrlWithState() else EpicConstants.LoginUrlWithState()
        state = st
        intent.putExtra(SAVED_URL, url)
        val redirect = Uri.parse(if (store == Store.GOG) GOGConstants.GOG_REDIRECT_URI else EpicConstants.EPIC_REDIRECT_URI)

        val web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = object : WebViewClient() {
            fun isRedirect(u: Uri) = u.scheme.equals(redirect.scheme, true) && u.host.equals(redirect.host, true) && u.path == redirect.path

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (isRedirect(u) && u.getQueryParameter("state") == state) {
                    u.getQueryParameter("code")?.let { finishWith(it); return true }
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                val u = Uri.parse(url)
                if (!isRedirect(u) || u.getQueryParameter("state") != state) return
                u.getQueryParameter("code")?.let { finishWith(it); return }
                view.evaluateJavascript(
                    "(function(){try{var j=JSON.parse(document.body&&document.body.innerText||'{}');return j.authorizationCode||null;}catch(e){return null;}})();",
                ) { result ->
                    val code = result?.trim()?.takeIf { it != "null" }?.removeSurrounding("\"")
                    if (!code.isNullOrBlank()) finishWith(code)
                }
            }
        }
        setContentView(web)
        web.loadUrl(url)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SAVED_URL, intent.getStringExtra(SAVED_URL))
        outState.putString(SAVED_STATE, state)
    }

    private fun finishWith(code: String) {
        if (done) return
        done = true
        setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
        finish()
    }

    /** Launches the sign-in for a store; the result is the authorization code, or null. */
    class Contract : ActivityResultContract<Store, String?>() {
        override fun createIntent(context: Context, input: Store) =
            Intent(context, StoreLoginActivity::class.java).putExtra(EXTRA_STORE, input.id)

        override fun parseResult(resultCode: Int, intent: Intent?): String? =
            if (resultCode == RESULT_OK) intent?.getStringExtra(EXTRA_CODE) else null
    }

    companion object {
        private const val EXTRA_STORE = "store"
        private const val EXTRA_CODE = "code"
        private const val SAVED_URL = "url"
        private const val SAVED_STATE = "state"
    }
}
