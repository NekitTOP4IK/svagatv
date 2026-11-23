package com.github.andreyasadchy.xtra.svagaplus

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * In-app WebView sign-in sheet for Svaga+ (`/viewer-connect`).
 * Supports both direct OAuth code interception on `/viewer-connect?code=...` and seamless
 * `viewer_token` extraction from `localStorage` once the user finishes Twitch + Telegram linking.
 */
@AndroidEntryPoint
class SvagaAuthDialog : DialogFragment() {

    @Inject
    lateinit var authManager: SvagaAuthManager

    private var webView: WebView? = null
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.ThemeOverlay_Material3_Dialog)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(8f), dp(8f))
        }
        val titleView = TextView(ctx).apply {
            text = getString(R.string.svaga_account_connect)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = ImageButton(ctx).apply {
            setImageResource(R.drawable.baseline_close_black_36)
            background = null
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setOnClickListener { dismissAllowingStateLoss() }
        }
        topBar.addView(titleView)
        topBar.addView(closeBtn)
        root.addView(topBar)

        val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3f))
        }
        root.addView(progress)

        val webContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(520f),
            )
        }

        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val scheme = uri.scheme?.lowercase().orEmpty()
                    if (scheme != "http" && scheme != "https") {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (_: Exception) {
                        }
                        return true
                    }
                    if (uri.host?.equals("t.me", ignoreCase = true) == true ||
                        uri.host?.endsWith(".t.me", ignoreCase = true) == true
                    ) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (_: Exception) {
                        }
                        return true
                    }
                    if (maybeCaptureOAuthCode(uri.toString())) {
                        return false
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    progress.isVisible = false
                    probeViewerTokenFromStorage(view, url)
                }
            }
        }
        webView = wv
        webContainer.addView(wv)
        root.addView(webContainer)

        wv.loadUrl(authManager.getViewerConnectUrl())
        return root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun maybeCaptureOAuthCode(url: String): Boolean {
        if (completed) return false
        val redirectBase = authManager.getRedirectUri()
        if (!url.startsWith(redirectBase, ignoreCase = true)) return false
        val uri = url.toUri()
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: return false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = authManager.completeOAuthCode(code, redirectBase)
            result.onSuccess { account ->
                if (account.telegramLinked) {
                    finishSuccess(account.twitchLogin)
                }
            }
        }
        return true
    }

    private fun probeViewerTokenFromStorage(view: WebView, url: String) {
        if (completed) return
        val backendHost = authManager.getViewerConnectUrl().toUri().host ?: return
        if (url.toUri().host?.equals(backendHost, ignoreCase = true) != true) return

        view.evaluateJavascript("(function(){ try { return localStorage.getItem('viewer_token') || ''; } catch(e) { return ''; } })();") { rawResult ->
            if (completed || !isAdded) return@evaluateJavascript
            val token = rawResult?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "null" } ?: return@evaluateJavascript
            viewLifecycleOwner.lifecycleScope.launch {
                val res = authManager.saveAndHydrateToken(token)
                res.onSuccess { account ->
                    if (account.telegramLinked || url.contains("/viewer/settings")) {
                        finishSuccess(account.twitchLogin)
                    }
                }
            }
        }
    }

    private fun finishSuccess(login: String) {
        if (completed) return
        completed = true
        context?.let {
            Toast.makeText(it, "Свага+: подключён @$login", Toast.LENGTH_SHORT).show()
        }
        dismissAllowingStateLoss()
    }

    override fun onDestroyView() {
        webView?.destroy()
        webView = null
        super.onDestroyView()
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    companion object {
        const val TAG = "SvagaAuthDialog"
    }
}
