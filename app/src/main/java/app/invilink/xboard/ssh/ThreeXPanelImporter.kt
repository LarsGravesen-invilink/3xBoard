package app.invilink.xboard.ssh

import app.invilink.xboard.panels.PanelApi
import app.invilink.xboard.panels.PanelValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.bouncycastle.crypto.generators.OpenBSDBCrypt
import java.net.URI
import java.util.UUID

internal class ThreeXPanelImporter(private val connection: SshConnection) {
    suspend fun discover(credentials: SshCredentials, identity: SshHostIdentity, knownServers: List<InstalledServer> = emptyList(),
        suppliedPanelPassword: String = ""): InstalledServer {
        val id = UUID.randomUUID().toString()
        val knownTokens = org.json.JSONArray(knownServers.filter {
            it.sshHost == credentials.host && it.sshPort == credentials.port.toInt() &&
                it.hostKeyAlgorithm == identity.algorithm && it.hostKeyBase64 == identity.keyBase64
        }.map { it.token }.distinct().take(8)).toString()
        val script = "import_host = ${pythonString(credentials.host)}\ntoken_name = ${pythonString("xboard-$id")}\nknown_tokens_json = ${pythonString(knownTokens)}\nmanual_password_present = ${if (suppliedPanelPassword.isNotEmpty()) "True" else "False"}\n" + DISCOVER
        val input = script.toByteArray(Charsets.UTF_8)
        val result = try {
            connection.executeInput("python3 -I -", input, timeoutMillis = 60_000, maxBytes = 32_768)
        } finally {
            input.fill(0)
        }
        if (result.exitCode != 0) {
            val reason = result.stderr.lineSequence().firstOrNull { it.startsWith("XBOARD_IMPORT:") }
                ?.removePrefix("XBOARD_IMPORT:")?.take(300)
            throw IllegalStateException(reason ?: "Не удалось обнаружить штатную работающую панель 3x-UI. Нужны root, python3 и SQLite; данные панели не изменялись.")
        }
        val data = JSONObject(result.stdout)
        val candidate = suppliedPanelPassword.ifEmpty { data.optString("panelPassword") }
        val hash = data.optString("passwordHash")
        val chars = candidate.toCharArray()
        val passwordMatches = try {
            candidate.isNotEmpty() && candidate.toByteArray(Charsets.UTF_8).size <= 72 &&
                withContext(Dispatchers.Default) { runCatching { OpenBSDBCrypt.checkPassword(hash, chars) }.getOrDefault(false) }
        } finally { chars.fill('\u0000') }
        require(suppliedPanelPassword.isEmpty() || passwordMatches) {
            "Введённый пароль не совпадает с паролем первого администратора 3x-UI. Пароль на сервере не изменён."
        }
        val panelPassword = if (passwordMatches) candidate else ""
        val url = PanelValidation.normalizeBaseUrl(data.getString("url"))
        val uri = URI(url)
        val target = uri.host.removeSurrounding("[", "]")
        val server = InstalledServer(
            panelId = id, sshHost = credentials.host, sshPort = credentials.port.toInt(), sshUsername = credentials.username,
            hostKeyAlgorithm = identity.algorithm, hostKeyBase64 = identity.keyBase64,
            panelUsername = data.optString("username"), panelPassword = panelPassword,
            panelPort = uri.port, panelUrl = url, token = data.getString("token"), secretPath = uri.path.trim('/'),
            sslMode = if (target.contains(':') || Regex("[0-9.]+").matches(target)) SslMode.IP else SslMode.DOMAIN,
            certificateTarget = target, credentials = credentials, imported = true,
        )
        val metrics = withContext(Dispatchers.IO) { PanelApi().fetch(server.panelConfig()) }
        check(metrics.availability == app.invilink.xboard.panels.PanelAvailability.ONLINE) {
            "Панель обнаружена по SSH, но недоступна с устройства. Проверьте DNS, SSL и открытый порт панели. Пароль и данные панели не изменены."
        }
        return server
    }

    private fun pythonString(value: String): String = JSONObject.quote(value)

    companion object {
        internal val DISCOVER = """
            import os, sys, stat, sqlite3, subprocess, json, re, ssl, urllib.request, urllib.error, shlex, ipaddress, time, socket
            def need(value, message):
                if not value:
                    sys.stderr.write('XBOARD_IMPORT:' + message + '\n')
                    raise SystemExit(1)
            need(os.geteuid() == 0, 'Для обнаружения панели нужен root-доступ.')
            pid_text = subprocess.check_output(['systemctl','show','x-ui.service','--property=MainPID','--value'], text=True).strip()
            need(pid_text.isdigit() and int(pid_text) > 1, 'Служба x-ui не запущена. Запустите панель и повторите подключение.')
            exe = os.readlink('/proc/' + pid_text + '/exe')
            need(exe == '/usr/local/x-ui/x-ui', 'Нестандартная установка 3x-UI. Автоматическое изменение неизвестной установки запрещено.')
            env = dict(item.split(b'=',1) for item in open('/proc/' + pid_text + '/environ','rb').read().split(b'\0') if b'=' in item)
            need(env.get(b'XUI_DB_TYPE', b'sqlite') in (b'', b'sqlite') and not env.get(b'XUI_DB_DSN'), 'Автоимпорт поддерживает SQLite. Настройки другой базы не изменены.')
            folder = os.fsdecode(env.get(b'XUI_DB_FOLDER', b'/etc/x-ui'))
            need(folder.rstrip('/') == '/etc/x-ui', 'Нестандартный путь базы. Импорт остановлен без изменения панели.')
            db_path = '/etc/x-ui/x-ui.db'
            s = os.lstat(db_path)
            need(stat.S_ISREG(s.st_mode) and s.st_uid == 0 and not s.st_mode & 0o022, 'База панели не прошла проверку.')
            opened = []
            for _ in range(20):
                opened = []
                for descriptor in os.listdir('/proc/' + pid_text + '/fd'):
                    try: opened.append(os.readlink('/proc/' + pid_text + '/fd/' + descriptor))
                    except FileNotFoundError: pass
                if db_path in opened: break
                time.sleep(0.25)
            need(db_path in opened, 'Работающая служба использует другую базу. Импорт остановлен без изменения данных.')
            with sqlite3.connect('file:' + db_path + '?mode=ro', uri=True) as db:
                settings = dict(db.execute('SELECT key,value FROM settings WHERE key IN (?,?,?,?,?,?)', ('webPort','webBasePath','webDomain','webCertFile','webKeyFile','webListen')).fetchall())
                users = db.execute('SELECT username,password FROM users ORDER BY id LIMIT 1').fetchall()
            port = int(settings.get('webPort') or '2053')
            need(1 <= port <= 65535, 'Некорректный порт панели.')
            path = '/' + (settings.get('webBasePath') or '/').strip('/')
            if not path.endswith('/'): path += '/'
            need(len(path) <= 2048 and re.fullmatch(r'/[A-Za-z0-9_./~-]*/?', path) and '..' not in path, 'Некорректный путь панели.')
            tls = bool(settings.get('webCertFile') and settings.get('webKeyFile'))
            host = settings.get('webDomain') or import_host
            if tls and not settings.get('webDomain'):
                cert = subprocess.run(['openssl','x509','-in',settings['webCertFile'],'-noout','-ext','subjectAltName'], capture_output=True, text=True, timeout=10)
                dns = re.findall(r'DNS:([A-Za-z0-9.-]+)', cert.stdout)
                ips = re.findall(r'IP Address:([0-9a-fA-F:.]+)', cert.stdout)
                if import_host not in dns + ips:
                    if dns: host = dns[0]
                    elif ips: host = str(ipaddress.ip_address(ips[0]))
            need(len(host) <= 253 and re.fullmatch(r'[A-Za-z0-9.:-]+',host), 'Некорректный адрес панели.')
            scheme = 'https' if tls else 'http'
            authority = '[' + host + ']' if ':' in host else host
            url = scheme + '://' + authority + ':' + str(port) + path
            listen = settings.get('webListen') or '127.0.0.1'
            if listen in ('0.0.0.0','::',''): listen = '127.0.0.1'
            listen = str(ipaddress.ip_address(listen))
            local_host = '[' + listen + ']' if ':' in listen else listen
            ready = False
            for _ in range(20):
                try:
                    with socket.create_connection((listen,port), timeout=0.5): ready = True
                    break
                except OSError: time.sleep(0.25)
            need(ready, 'Служба панели ещё не открыла порт. Повторите подключение через несколько секунд.')
            local_url = scheme + '://' + local_host + ':' + str(port) + path + 'panel/api/server/status'
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self, req, fp, code, msg, headers, newurl): return None
            # Only the verified native service's local listener is contacted here, over pinned SSH.
            handlers = [urllib.request.ProxyHandler({}), NoRedirect()]
            if tls: handlers.append(urllib.request.HTTPSHandler(context=ssl._create_unverified_context()))
            opener = urllib.request.build_opener(*handlers)
            def accepts(token):
                if not re.fullmatch(r'[A-Za-z0-9_-]{20,512}',token): return False
                req = urllib.request.Request(local_url, headers={'Authorization':'Bearer ' + token, 'Host':authority})
                try:
                    with opener.open(req, timeout=8) as response:
                        body = response.read(1048577)
                        return len(body) <= 1048576 and json.loads(body).get('success') is True
                except Exception: return False
            token = next((candidate for candidate in json.loads(known_tokens_json) if accepts(candidate)), '')
            install_values = {}
            result_file = '/etc/x-ui/install-result.env'
            if os.path.isfile(result_file) and not os.path.islink(result_file):
                rs = os.stat(result_file)
                if rs.st_uid == 0 and rs.st_size <= 16384 and rs.st_mode & 0o077 == 0:
                    for line in open(result_file, encoding='utf-8'):
                        key, sep, raw = line.strip().partition('=')
                        if sep and key in ('XUI_API_TOKEN', 'XUI_USERNAME', 'XUI_PASSWORD') and key not in install_values:
                            try:
                                values = shlex.split(raw)
                                if len(values) == 1: install_values[key] = values[0]
                            except ValueError: pass
                    if install_values.get('XUI_API_TOKEN') and accepts(install_values['XUI_API_TOKEN']):
                        token = install_values['XUI_API_TOKEN']
            if not token:
                help_result = subprocess.run([exe,'setting','-h'], capture_output=True, text=True, timeout=10)
                help_text = help_result.stdout + help_result.stderr
                need('-tokenName' in help_text and '-tokenScope' in help_text, 'Эта версия 3x-UI не поддерживает отдельный именованный API-токен. Существующие токены не изменены; используйте Token API.')
                cli_env = os.environ.copy()
                for key,value in env.items():
                    if key.startswith(b'XUI_'): cli_env[os.fsdecode(key)] = os.fsdecode(value)
                cli_env['XUI_DB_TYPE'] = 'sqlite'
                cli_env['XUI_DB_FOLDER'] = '/etc/x-ui'
                created = subprocess.run([exe,'setting','-tokenName',token_name,'-tokenScope','admin','-getApiToken'], env=cli_env, capture_output=True, text=True, timeout=20)
                matches = re.findall(r'^apiToken: ([A-Za-z0-9_-]{20,512})$',created.stdout,re.M)
                need(created.returncode == 0 and len(matches) == 1, 'Не удалось создать отдельный токен 3xBoard. Пароль и настройки панели не изменялись.')
                token = matches[0]
                need(accepts(token), 'Панель не приняла созданный API-токен. Повторная установка не запускалась.')
            username = users[0][0] if users else ''
            candidate = install_values.get('XUI_PASSWORD', '') if install_values.get('XUI_USERNAME') == username else ''
            password_hash = users[0][1] if users and (candidate or manual_password_present) else ''
            print(json.dumps({'url':url,'token':token,'username':username,'panelPassword':candidate,'passwordHash':password_hash}, ensure_ascii=True))
        """.trimIndent() + "\n"
    }
}
