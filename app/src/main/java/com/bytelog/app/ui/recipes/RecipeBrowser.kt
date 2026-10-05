package com.bytelog.app.ui.recipes

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bytelog.app.data.repository.RecipeRepository
import com.bytelog.app.recipes.ImportedRecipe
import com.bytelog.app.recipes.WebPageCapture
import com.bytelog.app.ui.theme.ByteTextSecondary
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroProtein
import kotlinx.coroutines.delay

/**
 * In-app browser used when a site blocks the quick import (bot walls) or builds its recipe with
 * JavaScript. The page loads like in a normal browser, so people can pass "are you human" checks
 * and cookie walls. After each page load the rendered DOM is read; as soon as it holds a recipe
 * ([RecipeRepository.recipeFromHtml]) the recipe is handed to [onRecipe]. They can also browse to the
 * recipe page from a site's home page, or tap "Import this page".
 *
 * Nothing from the app is sent to the site: no Sparky credentials, no JavaScript bridge.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RecipeBrowser(url: String, onRecipe: (ImportedRecipe) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageTick by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Loading…") }
    var delivered by remember { mutableStateOf(false) }
    var manualAttempt by remember { mutableIntStateOf(0) }

    fun readPage(view: WebView, manual: Boolean) {
        view.evaluateJavascript(WebPageCapture.READ_DOM_JS) { raw ->
            if (delivered) return@evaluateJavascript
            val html = WebPageCapture.decodeJsResult(raw)
            val result = html?.let { RecipeRepository.recipeFromHtml(it, view.url ?: url) }
            val recipe = result?.getOrNull()
            if (recipe != null) {
                delivered = true
                onRecipe(recipe)
            } else if (manual) {
                status = "No recipe found on this page. Open a recipe, then tap Import this page."
            }
        }
    }

    // After every page load, look for a recipe now and again while the page's scripts finish rendering.
    LaunchedEffect(pageTick) {
        if (pageTick == 0) return@LaunchedEffect
        status = "Looking for a recipe on this page…"
        for (attempt in 0 until 8) {
            if (delivered) return@LaunchedEffect
            webView?.let { readPage(it, manual = false) }
            delay(1500)
        }
        if (!delivered) status = "No recipe found yet. Open a recipe page, or tap Import this page."
    }
    LaunchedEffect(manualAttempt) {
        if (manualAttempt > 0) webView?.let { readPage(it, manual = true) }
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            webView = null
        }
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(status, color = ByteTextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Import this page", ByteTomato, filled = true) { manualAttempt++ }
                if (canGoBack) Pill("Back", MacroProtein) { webView?.goBack() }
                Pill("Cancel", MacroCarbs) { onClose() }
            }
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, color = ByteTomato, modifier = Modifier.fillMaxWidth())
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.WHITE)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // No file or content-provider access, no mixed content, one window.
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        // Stay on web pages: block app links (market://, intent://) that some sites redirect to.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            !WebPageCapture.isLoadable(request.url.toString())

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            canGoBack = view.canGoBack()
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            canGoBack = view.canGoBack()
                            pageTick++
                        }
                    }
                    if (WebPageCapture.isLoadable(url)) loadUrl(url) else status = "That link can't be opened here."
                    webView = this
                }
            }
        )
    }
}
