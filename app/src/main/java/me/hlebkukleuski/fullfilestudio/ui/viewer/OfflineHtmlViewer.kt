/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://gnu.org>.
 */

package me.hlebkukleuski.fullfilestudio.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.math.roundToInt
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppStrings

@Composable
internal fun OfflineHtmlViewer(
    htmlSource: String,
    htmlBrowserZoomPercent: Int,
    searchQuery: String,
    isSearchVisible: Boolean,
    activeWebViewRef: WebView?,
    onActiveWebViewChanged: (WebView) -> Unit,
    blockedExternalUrlPrompt: String?,
    onBlockedUrlChanged: (String?) -> Unit,
    clipManager: ClipboardManager?,
    modifier: Modifier = Modifier
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current

    val bgHex = remember(palette.canvasBackground) {
        String.format(
            Locale.US,
            "#%02X%02X%02X",
            (palette.canvasBackground.red * 255).roundToInt().coerceIn(0, 255),
            (palette.canvasBackground.green * 255).roundToInt().coerceIn(0, 255),
            (palette.canvasBackground.blue * 255).roundToInt().coerceIn(0, 255)
        )
    }
    val fgHex = remember(palette.primaryText) {
        String.format(
            Locale.US,
            "#%02X%02X%02X",
            (palette.primaryText.red * 255).roundToInt().coerceIn(0, 255),
            (palette.primaryText.green * 255).roundToInt().coerceIn(0, 255),
            (palette.primaryText.blue * 255).roundToInt().coerceIn(0, 255)
        )
    }
    val accentHex = remember(palette.accent) {
        String.format(
            Locale.US,
            "#%02X%02X%02X",
            (palette.accent.red * 255).roundToInt().coerceIn(0, 255),
            (palette.accent.green * 255).roundToInt().coerceIn(0, 255),
            (palette.accent.blue * 255).roundToInt().coerceIn(0, 255)
        )
    }

    val sandboxedHtml = remember(htmlSource, bgHex, fgHex, accentHex, palette.isDark) {
        val cspAndThemeHeader = """
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline' data:; img-src data: blob: file: about:; font-src data:; media-src data: blob:; connect-src 'none'; form-action 'none';">
            <style id="ffs-theme-defaults">
                :root { color-scheme: ${if (palette.isDark) "dark" else "light"}; }
                html, body {
                    background-color: $bgHex !important;
                    color: $fgHex !important;
                    font-family: sans-serif;
                    line-height: 1.5;
                    margin: 12px;
                    word-wrap: break-word;
                }
                a { color: $accentHex !important; }
                img, svg, video, canvas, table { max-width: 100%; height: auto; }
                pre, code { overflow-x: auto; max-width: 100%; }
            </style>
        """.trimIndent()
        if (htmlSource.contains("<head", ignoreCase = true)) {
            htmlSource.replaceFirst(Regex("(?i)<head[^>]*>"), "$0\n$cspAndThemeHeader")
        } else {
            "<!DOCTYPE html><html><head>$cspAndThemeHeader</head><body>$htmlSource</body></html>"
        }
    }

    LaunchedEffect(searchQuery, isSearchVisible, activeWebViewRef) {
        val wv = activeWebViewRef ?: return@LaunchedEffect
        if (isSearchVisible && searchQuery.trim().isNotEmpty()) {
            wv.findAllAsync(searchQuery.trim())
        } else {
            wv.clearMatches()
        }
    }

    if (blockedExternalUrlPrompt != null) {
        AlertDialog(
            onDismissRequest = { onBlockedUrlChanged(null) },
            confirmButton = {
                Button(
                    onClick = {
                        clipManager?.setPrimaryClip(ClipData.newPlainText("URL", blockedExternalUrlPrompt))
                        onBlockedUrlChanged(null)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                ) {
                    Text(
                        text = strings.copyActionButton,
                        color = if (palette.isDark) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { onBlockedUrlChanged(null) }) {
                    Text(strings.closeBtn, color = palette.secondaryText)
                }
            },
            title = {
                Text(
                    text = strings.securityBadge,
                    color = palette.warningBadge,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = blockedExternalUrlPrompt,
                    color = palette.primaryText,
                    fontSize = 14.sp
                )
            },
            containerColor = palette.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(
                    AndroidColor.rgb(
                        (palette.canvasBackground.red * 255).roundToInt(),
                        (palette.canvasBackground.green * 255).roundToInt(),
                        (palette.canvasBackground.blue * 255).roundToInt()
                    )
                )
                settings.apply {
                    javaScriptEnabled = false
                    blockNetworkLoads = true
                    blockNetworkImage = true
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                    domStorageEnabled = false
                    @Suppress("DEPRECATION")
                    databaseEnabled = false
                    setGeolocationEnabled(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    builtInZoomControls = true
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    defaultTextEncodingName = "UTF-8"
                    textZoom = htmlBrowserZoomPercent
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val scheme = request?.url?.scheme?.lowercase(Locale.ROOT) ?: ""
                        if (scheme in setOf("about", "data")) {
                            return super.shouldInterceptRequest(view, request)
                        }
                        return WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            403,
                            "Blocked by FullFileStudio Offline Sandbox",
                            emptyMap(),
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val targetUrl = request?.url?.toString() ?: return true
                        if (targetUrl.startsWith("about:blank#")) {
                            return false
                        }
                        onBlockedUrlChanged(targetUrl)
                        return true
                    }
                }
                onActiveWebViewChanged(this)
            }
        },
        update = { webView ->
            onActiveWebViewChanged(webView)
            webView.setBackgroundColor(
                AndroidColor.rgb(
                    (palette.canvasBackground.red * 255).roundToInt(),
                    (palette.canvasBackground.green * 255).roundToInt(),
                    (palette.canvasBackground.blue * 255).roundToInt()
                )
            )
            if (webView.settings.textZoom != htmlBrowserZoomPercent) {
                webView.settings.textZoom = htmlBrowserZoomPercent
            }
            val contentHash = sandboxedHtml.hashCode()
            if (webView.tag != contentHash) {
                webView.tag = contentHash
                webView.loadDataWithBaseURL(
                    "about:blank",
                    sandboxedHtml,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
