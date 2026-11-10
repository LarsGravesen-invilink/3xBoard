package app.invilink.xboard.ui

import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.invilink.xboard.ssh.InstalledServer
import org.json.JSONObject
import java.net.URI
import java.util.UUID

@Composable
internal fun NativePanelDialog(server: InstalledServer, onSaveCredentials: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val promptMarker = remember { "xboard-login-" + UUID.randomUUID().toString() }
    var credentialsToSave by remember { mutableStateOf<Pair<String, String>?>(null) }
    val palette = LocalXBoardPalette.current
    val base = remember(server.panelId) { runCatching { URI(server.panelUrl) }.getOrNull() }
    val secure = base?.scheme.equals("https", ignoreCase = true) && !base?.host.isNullOrBlank()
    fun allowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return base != null && uri.scheme.equals("https", true) && uri.host.equals(base.host, true) &&
            (if (uri.port == -1) 443 else uri.port) == (if (base.port == -1) 443 else base.port) && uri.userInfo == null
    }
    fun loginPage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = base?.path.orEmpty().trimEnd('/') + "/"
        return allowed(url) && uri.path in setOf(path, path.trimEnd('/'), path + "login")
    }
    var browser by remember { mutableStateOf<WebView?>(null) }
    BackHandler {
        val view = browser
        if (view != null && view.canGoBack()) view.goBack() else onDismiss()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn,
    )) {
        SessionDialogGuard()
        Surface(Modifier.fillMaxSize(), color = palette.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BoardIconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).semantics { contentDescription = "Закрыть панель" }) {
                        BoardIcon(BoardSymbol.Back, palette.foreground, 22.dp)
                    }
                    Text("3x-UI", style = MaterialTheme.typography.titleLarge, color = palette.foreground)
                }
                val message = when {
                    !secure -> "Нативный вход доступен только через HTTPS с действительным сертификатом."
                    server.panelUsername.isBlank() || server.panelPassword.isBlank() -> "Логин или пароль не сохранены. Введите данные панели вручную."
                    else -> null
                }
                if (message != null) Text(message, Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = palette.secondary)
                if (secure && base != null) {
                    AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = {
                        WebView(context).apply {
                            browser = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(false)
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            @Suppress("DEPRECATION")
                            settings.saveFormData = false
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webChromeClient = object : WebChromeClient() {
                                override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean {
                                    if (message != promptMarker || !loginPage(url) || !loginPage(view.url.orEmpty())) return false
                                    val credentials = runCatching {
                                        val data = JSONObject(defaultValue)
                                        val username = data.getString("username")
                                        val password = data.getString("password")
                                        if (username.isNotBlank() && username.length <= 128 && password.isNotEmpty() && password.length <= 128 &&
                                            username.none { it.isISOControl() } && password.none { it.isISOControl() }) username to password else null
                                    }.getOrNull()
                                    if (credentials != null && (credentials.first != server.panelUsername || credentials.second != server.panelPassword)) {
                                        credentialsToSave = credentials
                                    }
                                    result.confirm("")
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                private var attempted = false
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    request.isForMainFrame && !allowed(request.url.toString())
                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !allowed(url)
                                override fun onPageFinished(view: WebView, url: String) {
                                    super.onPageFinished(view, url)
                                    if (!loginPage(url)) return
                                    val autoLogin = !attempted && server.panelUsername.isNotBlank() && server.panelPassword.isNotBlank()
                                    if (autoLogin) attempted = true
                                    fun js(value: String) = JSONObject.quote(value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
                                    val script = """
                                        (async function() {
                                          const base = ${js(server.panelUrl.trimEnd('/') + "/")};
                                          const loginUrl = new URL(base + 'login', location.href).href;
                                          const rawFetch = window.fetch.bind(window);
                                          if (!window.__xboardLoginMonitor) {
                                            window.__xboardLoginMonitor = true;
                                            window.fetch = async function(input, init) {
                                              const response = await rawFetch(input, init);
                                              try {
                                                const target = new URL(typeof input === 'string' ? input : input.url, location.href);
                                                if (target.href === loginUrl && init && String(init.method).toUpperCase() === 'POST' &&
                                                    typeof init.body === 'string' && response.ok && !response.redirected && response.url === loginUrl) {
                                                  const form = new URLSearchParams(init.body);
                                                  const username = form.get('username'), password = form.get('password');
                                                  const result = await response.clone().json();
                                                  if (result.success === true && username && password) {
                                                    window.prompt(${js(promptMarker)}, JSON.stringify({username, password}));
                                                  }
                                                }
                                              } catch (_) { }
                                              return response;
                                            };
                                          }
                                          if (!${autoLogin}) return;
                                          try {
                                            const csrfResponse = await rawFetch(base + 'csrf-token', {credentials:'same-origin', redirect:'error'});
                                            const csrf = await csrfResponse.json();
                                            if (!csrfResponse.ok || !csrf.success || typeof csrf.obj !== 'string') return;
                                            const form = new URLSearchParams();
                                            form.set('username', ${js(server.panelUsername)});
                                            form.set('password', ${js(server.panelPassword)});
                                            form.set('twoFactorCode', '');
                                            const response = await rawFetch(base + 'login', {method:'POST', credentials:'same-origin', redirect:'error',
                                              headers:{'Content-Type':'application/x-www-form-urlencoded','X-CSRF-Token':csrf.obj}, body:form.toString()});
                                            const result = await response.json();
                                            if (response.ok && result.success === true) window.location.replace(base + 'panel/');
                                          } catch (_) { }
                                        })();
                                    """.trimIndent()
                                    view.evaluateJavascript(script, null)
                                }
                            }
                            loadUrl(server.panelUrl)
                        }
                    })
                }
            }
        }
    }
    credentialsToSave?.let { credentials ->
        AlertDialog(
            onDismissRequest = { credentialsToSave = null },
            title = { SessionDialogGuard(); Text("Сохранить данные входа?") },
            text = { Text("Сохранить логин и пароль для последующей автоматической авторизации? Пароль на сервере не изменится.") },
            confirmButton = {
                TextButton(onClick = {
                    credentialsToSave = null
                    onSaveCredentials(server.panelId, credentials.first, credentials.second)
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { credentialsToSave = null }) { Text("Не сохранять") } },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            browser?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
            browser = null
        }
    }
}
