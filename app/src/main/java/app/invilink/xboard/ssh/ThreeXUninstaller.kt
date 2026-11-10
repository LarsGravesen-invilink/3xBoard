package app.invilink.xboard.ssh

import org.json.JSONObject

internal class UninstallException(code: String, stage: String, panelId: String) : IllegalStateException(
    when (code) {
        "ROOT" -> "Нужен вход по SSH под root."
        "OWNERSHIP" -> "Нет подтверждённой установки 3xBoard для этой панели; чужая 3x-UI не удаляется."
        "TARGET" -> "Порт, путь или база не совпадают с выбранной панелью."
        "PARENT" -> "Родительский каталог перенаправлен или доступен для чужой записи; проверьте ${uninstallParentPath(stage)}."
        "ENVIRONMENT" -> "В /etc/default/x-ui или /etc/sysconfig/x-ui есть неизвестные настройки. Оставьте только XUI_DB_TYPE=sqlite либо удалите файл вручную после проверки."
        "ARCHIVE" -> "Архив /usr/local/x-ui-linux-{amd64,arm64}.tar.gz не совпадает с закреплённым релизом 3.11.0; проверьте и удалите его вручную."
        "CERTIFICATE" -> "В удаляемом каталоге обнаружен сертификат или ключ SSL. Перенесите пару в /root/cert и обновите пути панели перед удалением."
        "JOB_PRIVATE" -> "Проверьте владельца root и права 700/600 каталога задания и его файлов."
        "PANEL_LAYOUT" -> "Корневой путь панели перенаправлен или изменён; проверьте /usr/local/x-ui и /etc/x-ui."
        "DB_LAYOUT" -> "База x-ui.db или её служебный файл перенаправлен; проверьте /etc/x-ui."
        "MOUNT" -> "В удаляемом каталоге обнаружена точка монтирования; сначала отключите её вручную."
        "SERVICE" -> "Не удалось подтвердить службу x-ui выбранной панели или выполнить команду systemd."
        "FOREIGN" -> "Каталоги 3x-UI используются другой службой; нужна ручная проверка."
        "PROCESS" -> "Не подтверждено отсутствие посторонних или оставшихся процессов 3x-UI/xRay."
        "BUSY" -> "Установка или обслуживание ещё выполняется; повторите после завершения."
        "RECEIPT" -> "Запись удаления повреждена или относится к другой панели; нужна ручная проверка задания."
        "REAPPEARED" -> "После подтверждённого удаления файлы панели появились снова; повторное удаление запрещено."
        "CHANGED" -> "Пути или служба изменились во время удаления; проверьте VPS перед повтором."
        else -> "Удаление не подтверждено; проверьте VPS и повторите для продолжения."
    } + " [$code/$stage]. Локальная запись сохранена. Задание: /root/.xboard-install/$panelId (uninstall-started, uninstall-complete).",
)

private fun uninstallParentPath(stage: String): String = when (stage) {
    "PARENT_FILESYSTEM_ROOT" -> "/"
    "PARENT_ROOT" -> "/root"
    "PARENT_USR" -> "/usr"
    "PARENT_USR_LOCAL" -> "/usr/local"
    "PARENT_USR_BIN" -> "/usr/bin"
    "PARENT_ETC" -> "/etc"
    "PARENT_ETC_SYSTEMD" -> "/etc/systemd"
    "PARENT_ETC_SYSTEMD_SYSTEM" -> "/etc/systemd/system"
    "PARENT_ETC_DEFAULT" -> "/etc/default"
    "PARENT_ETC_SYSCONFIG" -> "/etc/sysconfig"
    "PARENT_VAR" -> "/var"
    "PARENT_VAR_LOG" -> "/var/log"
    else -> "родительский каталог (этап $stage)"
}

internal class ThreeXUninstaller(private val connection: SshConnection) {
    suspend fun uninstall(server: InstalledServer): Unit {
        require(Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(server.panelId))
        require(server.panelPort in 1..65535)
        val expected = JSONObject()
            .put("panelId", server.panelId)
            .put("port", server.panelPort.toString())
            .put("path", server.secretPath)
            .put("url", server.panelUrl)
        check(!server.imported) { "Удаление 3x-UI с сервера доступно только если 3x-UI было установлено через 3xBoard." }
        val script = ("export XBOARD_EXPECTED=${shellQuote(expected.toString())}\n" +
            "exec /usr/bin/python3 -I - <<'XBOARD_NATIVE_REMOVE'\n" + SIMPLE_REMOVE + "\nXBOARD_NATIVE_REMOVE\n")
            .toByteArray(Charsets.UTF_8)
        val response = try {
            connection.executeInput(
                "env -i HOME=/root LC_ALL=C PATH=/usr/sbin:/usr/bin:/sbin:/bin /bin/bash --noprofile --norc -s",
                script, timeoutMillis = 300_000, maxBytes = 4096,
            )
        } finally {
            script.fill(0)
        }
        if (response.exitCode == 0 && response.stderr.isBlank() && response.stdout == "REMOVED\n") return
        val refusal = Regex("REFUSED\\t([A-Z_]+)\\t([A-Z_]+)\\n").matchEntire(response.stdout)
        throw UninstallException(
            refusal?.groupValues?.get(1) ?: "UNCONFIRMED",
            refusal?.groupValues?.get(2) ?: "TRANSPORT",
            server.panelId,
        )
    }

    internal companion object {
        private val SIMPLE_REMOVE = """
            import json, os, re, shutil, sqlite3, stat, subprocess, sys
            from urllib.parse import quote
            stage = 'START'
            class Refused(Exception):
                def __init__(self, code): self.code = code
            def need(condition, code):
                if not condition: raise Refused(code)
            def systemctl(*args, required=True):
                result = subprocess.run(['/usr/bin/systemctl', *args], stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60, text=True)
                if required: need(result.returncode == 0, 'SERVICE')
                return result
            def property_value(name):
                result = systemctl('show', 'x-ui.service', '--property=' + name, '--value', required=False)
                need(result.returncode in (0, 1) and bool(result.stdout.strip()), 'SERVICE')
                return result.stdout.strip()
            def verify_paths():
                with open('/proc/self/mountinfo', encoding='utf-8') as source:
                    mounts = [re.sub(r'\\([0-7]{3})', lambda m: chr(int(m[1], 8)), line.split()[4]) for line in source]
                for root in ('/usr/local/x-ui', '/etc/x-ui', '/var/log/x-ui'):
                    if not os.path.lexists(root): continue
                    need(not os.path.islink(root) and os.path.isdir(root) and os.path.realpath(root) == root, 'PANEL_LAYOUT')
                    need(not any(m == root or m.startswith(root + '/') for m in mounts), 'MOUNT')
                    for parent, dirs, files in os.walk(root, followlinks=False):
                        for name in dirs + files:
                            path = os.path.join(parent, name)
                            if os.path.islink(path): continue
                            need(not name.lower().endswith(('.pem', '.key', '.crt', '.cer', '.p12', '.pfx')) and
                                name.lower() not in ('cert', 'certs', 'certificate', 'certificates', '.acme.sh'), 'CERTIFICATE')
            try:
                need(os.geteuid() == 0, 'ROOT')
                expected = json.loads(os.environ.pop('XBOARD_EXPECTED'))
                stage = 'PANEL'
                verify_paths()
                db = '/etc/x-ui/x-ui.db'
                load = property_value('LoadState')
                need(load in ('loaded', 'not-found'), 'SERVICE')
                if os.path.exists(db):
                    need(os.path.isfile(db) and not os.path.islink(db), 'DB_LAYOUT')
                    with sqlite3.connect('file:' + quote(db, safe='/') + '?mode=ro', uri=True, timeout=5) as con:
                        settings = dict(con.execute('SELECT key, value FROM settings WHERE key IN (?, ?, ?, ?, ?, ?)',
                            ('webPort', 'webBasePath', 'webCertFile', 'webKeyFile', 'subCertFile', 'subKeyFile')).fetchall())
                    norm = lambda value: '/' + str(value).strip('/') + '/'
                    need(str(settings.get('webPort')) == expected['port'] and
                        norm(settings.get('webBasePath', '')) == norm(expected['path']), 'TARGET')
                    for key in ('webCertFile', 'webKeyFile', 'subCertFile', 'subKeyFile'):
                        value = settings.get(key)
                        if value:
                            path = os.path.realpath(value)
                            need(os.path.isabs(value) and not any(path == root or path.startswith(root + '/') for root in
                                ('/usr/local/x-ui', '/etc/x-ui', '/var/log/x-ui')), 'CERTIFICATE')
                else:
                    need(load == 'not-found' and not os.path.lexists('/usr/local/x-ui') and
                        not os.path.lexists('/etc/x-ui'), 'TARGET')
                stage = 'SERVICE'
                if load == 'loaded':
                    need(property_value('FragmentPath') == '/etc/systemd/system/x-ui.service', 'SERVICE')
                    systemctl('stop', 'x-ui.service')
                    systemctl('disable', 'x-ui.service')
                stage = 'DELETE'
                verify_paths()
                for path in ('/etc/systemd/system/x-ui.service', '/etc/default/x-ui',
                             '/etc/fail2ban/jail.d/x-ui.conf', '/etc/fail2ban/filter.d/x-ui.conf'):
                    if os.path.lexists(path):
                        need(not os.path.isdir(path) and not os.path.islink(path), 'PANEL_LAYOUT')
                        os.unlink(path)
                for path in ('/usr/local/x-ui', '/etc/x-ui', '/var/log/x-ui'):
                    if os.path.isdir(path): shutil.rmtree(path)
                stage = 'VERIFY'
                systemctl('daemon-reload')
                systemctl('reset-failed')
                fail2ban = systemctl('is-active', '--quiet', 'fail2ban.service', required=False)
                if fail2ban.returncode == 0: systemctl('restart', 'fail2ban.service')
                need(property_value('LoadState') == 'not-found', 'SERVICE')
                need(not any(os.path.lexists(p) for p in ('/usr/local/x-ui', '/etc/x-ui',
                    '/etc/systemd/system/x-ui.service', '/etc/default/x-ui', '/var/log/x-ui')), 'UNCONFIRMED')
                print('REMOVED', flush=True)
            except Refused as failure:
                print('REFUSED\t' + failure.code + '\t' + stage, flush=True)
                sys.exit(1)
            except Exception:
                print('REFUSED\tUNCONFIRMED\t' + stage, flush=True)
                sys.exit(1)
        """.trimIndent()
        val PYTHON = """
            import base64, fcntl, grp, hashlib, json, os, re, signal, sqlite3, stat, subprocess, time
            from urllib.parse import quote
            from contextlib import closing, contextmanager
            LIVE = '/usr/local/x-ui'
            CONFIG = '/etc/x-ui'
            UNIT = '/etc/systemd/system/x-ui.service'
            DROP = UNIT + '.d'
            CLI = '/usr/bin/x-ui'
            DB = CONFIG + '/x-ui.db'
            STAGE = 'START'
            INSTALL_FD = None
            TREES = (LIVE, CONFIG)
            LOG = '/var/log/x-ui'
            ENVIRONMENTS = ('/etc/default/x-ui', '/etc/sysconfig/x-ui')
            ARCHIVES = {
                '/usr/local/x-ui-linux-amd64.tar.gz': '0ddfa4702f595442e2962acd81addc768fdacaa4ff2672c971a0463dae744916',
                '/usr/local/x-ui-linux-arm64.tar.gz': 'f50b4c30fd919d018f2f4c3d8db53a4ca027503d581328727770afb116a79d90',
            }
            VERIFIED_ARCHIVES = {}
            ADDITIONAL_PATHS = ENVIRONMENTS + (LOG,) + tuple(ARCHIVES)
            JOB_SECRETS = ('result', 'result.next', 'install-result.env', 'install.sh', 'verified.sh', 'cookies', 'runner')
            ENABLE_LINK = '/etc/systemd/system/multi-user.target.wants/x-ui.service'
            CORE_PATHS = TREES + (UNIT, CLI, DROP, DB, LIVE + '/x-ui', ENABLE_LINK)
            REMOVAL_PATHS = CORE_PATHS + ADDITIONAL_PATHS
            class Refused(Exception):
                def __init__(self, code):
                    self.code = code
            def need(value, code='PANEL_LAYOUT'):
                if not value:
                    raise Refused(code)
            def inspect(path, directory=False, private=False, code='JOB_PRIVATE'):
                s = os.lstat(path)
                if code == 'PARENT' and directory:
                    trusted = set()
                    if path == '/usr/local':
                        trusted.add('staff')
                    if path in ('/var', '/var/log'):
                        trusted.update(('adm', 'syslog'))
                    try:
                        group = grp.getgrgid(s.st_gid).gr_name
                    except KeyError:
                        group = ''
                    need(os.path.realpath(path) == path and stat.S_ISDIR(s.st_mode) and s.st_uid == 0 and
                         not s.st_mode & 0o002 and (not s.st_mode & 0o020 or group in trusted), code)
                    return s
                need(os.path.realpath(path) == path and s.st_uid == 0 and s.st_gid == 0 and not s.st_mode & 0o022, code)
                need(stat.S_ISDIR(s.st_mode) if directory else stat.S_ISREG(s.st_mode) and s.st_nlink == 1, code)
                if private:
                    need(stat.S_IMODE(s.st_mode) == (0o700 if directory else 0o600), code)
                return s
            def regular(path, code='PANEL_LAYOUT'):
                s = os.lstat(path)
                need(os.path.realpath(path) == path and stat.S_ISREG(s.st_mode), code)
                return s
            def read(path, limit=65536):
                regular(path)
                with open(path, 'rb') as f:
                    data = f.read(limit + 1)
                need(len(data) <= limit)
                return data
            def digest(path):
                regular(path)
                h = hashlib.sha256()
                with open(path, 'rb') as f:
                    for block in iter(lambda: f.read(1048576), b''):
                        h.update(block)
                return h.hexdigest()
            def under(path, root):
                return path == root or path.startswith(root + '/')
            def mounts():
                with open('/proc/self/mountinfo', encoding='utf-8') as f:
                    return [re.sub(r'\\([0-7]{3})', lambda m: chr(int(m[1], 8)), line.split()[4]) for line in f]
            def scan(root):
                top = os.lstat(root)
                need(os.path.realpath(root) == root and stat.S_ISDIR(top.st_mode), 'PANEL_LAYOUT')
                device = top.st_dev
                need(not any(under(m, root) for m in mounts()), 'MOUNT')
                for parent, dirs, files in os.walk(root, followlinks=False):
                    for name in dirs + files:
                        need(os.lstat(parent + '/' + name).st_dev == device, 'MOUNT')
            def run(args, timeout=20, absent=False):
                p = subprocess.run(['/usr/bin/systemctl'] + args, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout)
                need(len(p.stdout) <= 2097152, 'SERVICE')
                output = p.stdout.decode('utf-8').strip()
                if p.returncode != 0:
                    need(absent and p.returncode == 1 and args == ['show', '--all', 'x-ui.service',
                         '--property=LoadState,ActiveState,FragmentPath,DropInPaths'] and
                         len(output.splitlines()) == 4 and set(output.splitlines()) ==
                         {'LoadState=not-found', 'ActiveState=inactive', 'FragmentPath=', 'DropInPaths='}, 'SERVICE')
                return output
            def prop(key, unit='x-ui.service', timeout=20):
                return run(['show', unit, '--property=' + key, '--value'], timeout=timeout)
            def absence_state(timeout=20):
                output = run(['show', '--all', 'x-ui.service', '--property=LoadState,ActiveState,FragmentPath,DropInPaths'], timeout=timeout, absent=True)
                rows = output.splitlines()
                need(len(rows) == 4 and len({row.split('=', 1)[0] for row in rows}) == 4, 'SERVICE')
                values = dict(row.split('=', 1) for row in rows)
                need(set(values) == {'LoadState', 'ActiveState', 'FragmentPath', 'DropInPaths'}, 'SERVICE')
                return values == {'LoadState': 'not-found', 'ActiveState': 'inactive', 'FragmentPath': '', 'DropInPaths': ''}
            def wait_for(check, seconds, code):
                deadline = time.monotonic() + seconds
                while True:
                    try:
                        need(check(min(2, max(0.001, deadline - time.monotonic()))), code)
                        return
                    except Refused as e:
                        if e.code != code:
                            raise
                    except subprocess.TimeoutExpired:
                        pass
                    remaining = deadline - time.monotonic()
                    need(remaining > 0, code)
                    time.sleep(min(0.25, remaining))
            def directives(path):
                return [line.strip() for line in read(path).decode('utf-8').splitlines()
                        if line.strip() and not line.lstrip().startswith(('#', ';'))]
            def normalize_path(value):
                return '/' + value.strip('/') + '/'
            def database(path):
                regular(path, 'DB_LAYOUT')
                for suffix in ('-wal', '-shm', '-journal'):
                    if os.path.lexists(path + suffix):
                        regular(path + suffix, 'DB_LAYOUT')
                con = sqlite3.connect('file:' + quote(path, safe='/') + '?mode=ro', uri=True, timeout=5)
                con.execute('PRAGMA query_only=ON')
                return con
            def verify_db(path=DB):
                with closing(database(path)) as con:
                    rows = con.execute('SELECT key, value FROM settings WHERE key IN (?, ?)', ('webPort', 'webBasePath')).fetchall()
                    need(len(rows) == 2, 'TARGET')
                    settings = dict(rows)
                    need(str(settings.get('webPort')) == expected['port'], 'TARGET')
                    need(normalize_path(str(settings.get('webBasePath'))) == normalize_path(expected['path']), 'TARGET')
                    for key, value in con.execute('SELECT key, value FROM settings WHERE key IN (?, ?, ?, ?)',
                                                  ('webCertFile', 'webKeyFile', 'subCertFile', 'subKeyFile')):
                        if value:
                            need(os.path.isabs(value) and not any(under(os.path.realpath(value), root)
                                 for root in TREES + (LOG,)), 'CERTIFICATE')
            def ownership():
                inspect('/root/.xboard-install', True, True)
                inspect(job, True, True)
                need(not any(under(m, '/root/.xboard-install') for m in mounts()), 'MOUNT')
                need(not os.path.lexists(job + '/runner'), 'BUSY')
                for name in ('status', 'phase', 'result', 'started', 'install.sh', 'verified.sh'):
                    inspect(job + '/' + name, private=True)
                need(read(job + '/status') == b'SUCCEEDED\n' and read(job + '/phase') == b'COMPLETE\n', 'OWNERSHIP')
                need(os.lstat(job + '/result').st_mtime_ns > os.lstat(job + '/started').st_mtime_ns, 'OWNERSHIP')
                lines = read(job + '/result', 16384).decode('ascii').splitlines()
                need(len(lines) == 9 and lines[0] == 'XBOARD_RESULT_V1' and lines[-1] == 'END', 'OWNERSHIP')
                values = {}
                for line in lines[1:-1]:
                    parts = line.split('\t')
                    need(len(parts) == 2 and parts[0] not in values, 'OWNERSHIP')
                    data = base64.b64decode(parts[1], validate=True)
                    need(base64.b64encode(data).decode('ascii') == parts[1], 'OWNERSHIP')
                    values[parts[0]] = data.decode('utf-8')
                need(set(values) == {'XUI_USERNAME', 'XUI_PASSWORD', 'XUI_PANEL_PORT', 'XUI_WEB_BASE_PATH',
                                     'XUI_ACCESS_URL', 'XUI_API_TOKEN', 'XUI_DB_TYPE'}, 'OWNERSHIP')
                need(values['XUI_DB_TYPE'] == 'sqlite' and values['XUI_PANEL_PORT'] == expected['port'], 'OWNERSHIP')
                need(normalize_path(values['XUI_WEB_BASE_PATH']) == normalize_path(expected['path']), 'OWNERSHIP')
                need(values['XUI_ACCESS_URL'].rstrip('/') == expected['url'].rstrip('/'), 'OWNERSHIP')
            def service(resuming=False):
                global STAGE
                previous = STAGE
                STAGE = 'SERVICE_UNIT'
                need(not any(m in (UNIT, CLI) for m in mounts()), 'MOUNT')
                regular(UNIT, 'SERVICE')
                if os.path.lexists(CLI):
                    regular(CLI, 'SERVICE')
                if os.path.lexists(LIVE + '/x-ui'):
                    regular(LIVE + '/x-ui', 'SERVICE')
                if os.path.lexists(DROP):
                    scan(DROP)
                    safe_certificates(DROP)
                if prop('NeedDaemonReload') == 'yes':
                    run(['daemon-reload'])
                need(prop('FragmentPath') == UNIT, 'SERVICE')
                STAGE = 'SERVICE_EXECUTABLE'
                start = prop('ExecStart')
                need(re.search(r'(?:^|[ {;])path=' + re.escape(LIVE) + r'/x-ui\s*;', start), 'SERVICE')
                cg = prop('ControlGroup')
                need(cg in ('', '/system.slice/x-ui.service'), 'SERVICE')
                pid = prop('MainPID')
                if pid and pid != '0':
                    need(pid.isdigit(), 'PROCESS')
                    try:
                        executable = os.readlink('/proc/' + pid + '/exe')
                        if executable.endswith(' (deleted)'):
                            executable = executable[:-10]
                        need(executable == LIVE + '/x-ui', 'PROCESS')
                    except (FileNotFoundError, ProcessLookupError):
                        pass
                STAGE = previous
                return cg
            def processes(cgroup, stopped=False):
                for pid in os.listdir('/proc'):
                    if not pid.isdigit():
                        continue
                    try:
                        exe = os.readlink('/proc/' + pid + '/exe')
                        with open('/proc/' + pid + '/cgroup', encoding='utf-8') as f:
                            groups = [line.rstrip().split(':', 2)[2] for line in f]
                        target = exe[:-10] if exe.endswith(' (deleted)') else exe
                        owned = any(under(target, root) for root in TREES)
                        member = any(cgroup and under(group, cgroup) for group in groups)
                        if owned or member:
                            need(not stopped and owned and member, 'PROCESS')
                    except (FileNotFoundError, ProcessLookupError):
                        continue
                    except PermissionError:
                        raise Refused('PROCESS')
            def safe_certificates(root):
                for parent, dirs, files in os.walk(root, followlinks=False):
                    for name in dirs + files:
                        path = parent + '/' + name
                        if stat.S_ISLNK(os.lstat(path).st_mode):
                            continue
                        need(not name.lower().endswith(('.pem', '.key', '.crt', '.cer', '.p12', '.pfx')) and
                             name.lower() not in ('cert', 'certs', 'certificate', 'certificates', '.acme.sh'), 'CERTIFICATE')
                        if stat.S_ISREG(os.lstat(path).st_mode):
                            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
                            with os.fdopen(fd, 'rb') as f:
                                head = f.read(128)
                            need(not re.search(br'-----BEGIN (?:[A-Z ]*PRIVATE KEY|CERTIFICATE)-----', head), 'CERTIFICATE')
            def environment(path):
                if not os.path.lexists(path):
                    return
                inspect(os.path.dirname(path), True, code='PARENT')
                inspect(path, code='ENVIRONMENT')
                for line in directives(path):
                    need(re.fullmatch(r'XUI_DB_TYPE[ \t]*=[ \t]*(?:sqlite|"sqlite"|\x27sqlite\x27)', line), 'ENVIRONMENT')
            def archive_signature(path):
                s = regular(path, 'ARCHIVE')
                return (s.st_dev, s.st_ino, s.st_mode, s.st_uid, s.st_gid, s.st_nlink,
                        s.st_size, s.st_mtime_ns, s.st_ctime_ns)
            def verify_archive(path):
                signature = archive_signature(path)
                if VERIFIED_ARCHIVES.get(path) != signature:
                    need(digest(path) == ARCHIVES[path] and archive_signature(path) == signature, 'ARCHIVE')
                    VERIFIED_ARCHIVES[path] = signature
            def validate_additional():
                parents()
                for path in ADDITIONAL_PATHS:
                    if not os.path.lexists(path):
                        continue
                    need(not any(under(m, path) for m in mounts()), 'MOUNT')
                    s = os.lstat(path)
                    if path in ENVIRONMENTS:
                        environment(path)
                    elif stat.S_ISLNK(s.st_mode):
                        continue
                    elif path == LOG and stat.S_ISDIR(s.st_mode):
                        scan(path)
                        safe_certificates(path)
                    else:
                        need(stat.S_ISREG(s.st_mode), 'PANEL_LAYOUT')
                        if path in ARCHIVES:
                            verify_archive(path)
            def clean_additional():
                validate_additional()
                for path in ADDITIONAL_PATHS:
                    validate_additional()
                    if os.path.lexists(path):
                        if path == LOG and stat.S_ISDIR(os.lstat(path).st_mode):
                            remove_tree(path)
                        else:
                            os.unlink(path)
            def job_secrets():
                return set(JOB_SECRETS) | {name for name in os.listdir(job)
                    if re.fullmatch(r'verify-[A-Za-z0-9_-]+\.cookies', name)}
            def purge_job():
                for name in job_secrets():
                    path = job + '/' + name
                    if os.path.lexists(path):
                        inspect(path, private=True)
                        os.unlink(path)
                fsync_dir(job)
            def install_boundary():
                need(INSTALL_FD is not None, 'BUSY')
                s = inspect('/root/.xboard-install', True, True)
                locked = os.fstat(INSTALL_FD)
                need((locked.st_dev, locked.st_ino) == (s.st_dev, s.st_ino), 'CHANGED')
            def install_command(pid):
                try:
                    with open('/proc/' + pid + '/cmdline', 'rb') as f:
                        command = f.read(1048577)
                    need(len(command) <= 1048576 and (not command or command.endswith(b'\0')), 'BUSY')
                    root = re.escape('/root/.xboard-install/'.encode())
                    pattern = root + br'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}/(?:runner|install\.sh|verified\.sh|certificate-check\.py)'
                    need(not any(re.fullmatch(pattern, arg) for arg in command.split(b'\0')), 'BUSY')
                    return command
                except (FileNotFoundError, ProcessLookupError):
                    return None
                except PermissionError:
                    raise Refused('BUSY')
            def active_boundary():
                install_boundary()
                root = '/root/.xboard-install'
                active = root + '/active'
                for pid in os.listdir('/proc'):
                    if pid.isdigit():
                        install_command(pid)
                if not os.path.lexists(active):
                    return False
                inspect(active, True, True)
                names = set(os.listdir(active))
                need(names <= {'job', 'pid'}, 'BUSY')
                if 'job' in names:
                    inspect(active + '/job', private=True)
                    need(read(active + '/job').decode('ascii').strip() in (panel_id, job), 'BUSY')
                for name in os.listdir(root):
                    if name == 'active':
                        continue
                    need(re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', name), 'BUSY')
                    other = root + '/' + name
                    inspect(other, True, True)
                    need(not os.path.lexists(other + '/pid'), 'BUSY')
                    if os.path.lexists(other + '/runner'):
                        need(other == job and 'job' in names and
                             (os.path.lexists(job + '/uninstall-started') or os.path.lexists(job + '/uninstall-complete')), 'BUSY')
                        inspect(other + '/runner', private=True)
                    inspect(other + '/status', private=True)
                    inspect(other + '/phase', private=True)
                    need(read(other + '/status') in (b'SUCCEEDED\n', b'FAILED\n') and
                         (read(other + '/status') != b'SUCCEEDED\n' or read(other + '/phase') == b'COMPLETE\n'), 'BUSY')
                if 'pid' in names:
                    need('job' in names, 'BUSY')
                    inspect(active + '/pid', private=True)
                    pid = read(active + '/pid').decode('ascii').strip()
                    need(re.fullmatch(r'[1-9][0-9]{0,9}', pid), 'BUSY')
                    command = install_command(pid)
                    need(command is not None or not os.path.lexists('/proc/' + pid), 'BUSY')
                return True
            def clean_active():
                if active_boundary():
                    active = '/root/.xboard-install/active'
                    for name in ('pid', 'job'):
                        if os.path.lexists(active + '/' + name):
                            os.unlink(active + '/' + name)
                    os.rmdir(active)
                    fsync_dir('/root/.xboard-install')
            def verify_job_removed():
                need(not any(os.path.lexists(job + '/' + name) for name in job_secrets()), 'UNCONFIRMED')
                need(not os.path.lexists('/root/.xboard-install/active'), 'BUSY')
            def verify_removed(cgroup, paths):
                need(absence_state(), 'UNCONFIRMED')
                processes(cgroup, stopped=True)
                for path in paths:
                    need(not os.path.lexists(path), 'UNCONFIRMED')
            def fingerprint():
                payload = dict(expected, path=normalize_path(expected['path']), url=expected['url'].rstrip('/'))
                return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
            def job_boundary():
                install_boundary()
                inspect(job, True, True)
                need(not any(under(m, '/root/.xboard-install') for m in mounts()), 'MOUNT')
                present = active_boundary()
                if os.path.lexists(job + '/runner'):
                    need(present, 'BUSY')
            def receipt(path):
                inspect(path, private=True)
                try:
                    value = json.loads(read(path))
                    need(value['version'] == 'XBOARD_NATIVE_REMOVE_V2' and value['panelId'] == panel_id and
                         value['target'] == fingerprint(), 'RECEIPT')
                    if path == job + '/uninstall-complete':
                        need(set(value) == {'version', 'panelId', 'target'}, 'RECEIPT')
                        return value
                    need(set(value) == {'version', 'panelId', 'target', 'roots', 'controls'}, 'RECEIPT')
                    need(set(value['roots']) == set(TREES) or set(value['roots']) == set(TREES + (DROP,)), 'RECEIPT')
                    controls = set(value['controls'])
                    need({UNIT, CLI} <= controls and all(path in (UNIT, CLI) or
                         os.path.dirname(path) == DROP for path in controls), 'RECEIPT')
                    return value
                except Refused:
                    raise
                except Exception:
                    raise Refused('RECEIPT')
            def completed():
                marker = job + '/uninstall-complete'
                if not os.path.lexists(marker):
                    return False
                job_boundary()
                value = receipt(marker)
                started = receipt(job + '/uninstall-started')
                need({key: started[key] for key in value} == value, 'RECEIPT')
                need(not any(os.path.lexists(path) for path in CORE_PATHS), 'REAPPEARED')
                pending = any(os.path.lexists(path) for path in ADDITIONAL_PATHS) or any(
                    os.path.lexists(job + '/' + name) for name in job_secrets()) or os.path.lexists('/root/.xboard-install/active')
                verify_removed('/system.slice/x-ui.service', CORE_PATHS if pending else REMOVAL_PATHS)
                if pending:
                    return False
                verify_job_removed()
                return True
            def private_write(path, data):
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
                with os.fdopen(fd, 'wb') as f:
                    f.write(data)
                    f.flush()
                    os.fsync(f.fileno())
            def fsync_dir(path):
                fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                try:
                    os.fsync(fd)
                finally:
                    os.close(fd)
            def write_receipt(path, value):
                temporary = path + '.next'
                if os.path.lexists(temporary):
                    inspect(temporary, private=True)
                    os.unlink(temporary)
                private_write(temporary, json.dumps(value, sort_keys=True, separators=(',', ':')).encode() + b'\n')
                os.replace(temporary, path)
                fsync_dir(job)
            def validate_remaining(value):
                parents()
                job_boundary()
                for root in TREES + (DROP,):
                    if os.path.lexists(root):
                        need(root in value['roots'], 'CHANGED')
                        s = os.lstat(root)
                        need([s.st_dev, s.st_ino] == value['roots'][root], 'CHANGED')
                        scan(root)
                        safe_certificates(root)
                validate_additional()
                active_boundary()
                for path, checksum in value['controls'].items():
                    if os.path.lexists(path):
                        need(not any(m == path for m in mounts()), 'MOUNT')
                        need(digest(path) == checksum, 'CHANGED')
                need(not os.path.lexists(DROP) or DROP in value['roots'], 'CHANGED')
                if os.path.lexists(DROP):
                    for name in os.listdir(DROP):
                        path = DROP + '/' + name
                        if stat.S_ISREG(os.lstat(path).st_mode):
                            need(path in value['controls'], 'CHANGED')
                enable_link = ENABLE_LINK
                if os.path.lexists(enable_link):
                    inspect('/etc/systemd/system/multi-user.target.wants', True, code='PARENT')
                    need(not any(m == enable_link for m in mounts()), 'MOUNT')
                    need(stat.S_ISLNK(os.lstat(enable_link).st_mode) and os.path.realpath(enable_link) == UNIT, 'SERVICE')
            def parents():
                global STAGE
                previous = STAGE
                paths = ['/', '/root', '/usr', '/usr/local', '/usr/bin', '/etc', '/etc/systemd', '/etc/systemd/system']
                if os.path.lexists(LOG):
                    paths.extend(('/var', '/var/log'))
                for target in ENVIRONMENTS:
                    if os.path.lexists(target):
                        paths.append(os.path.dirname(target))
                for path in paths:
                    STAGE = 'PARENT_' + (re.sub(r'[^A-Z_]', '_', path.strip('/').upper()) or 'FILESYSTEM_ROOT')
                    inspect(path, True, code='PARENT')
                STAGE = previous
            @contextmanager
            def install_lock():
                global INSTALL_FD
                root = '/root/.xboard-install'
                s = inspect(root, True, True)
                fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                try:
                    try:
                        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError:
                        raise Refused('BUSY')
                    locked = os.fstat(fd)
                    need((locked.st_dev, locked.st_ino) == (s.st_dev, s.st_ino), 'CHANGED')
                    INSTALL_FD = fd
                    install_boundary()
                    yield
                finally:
                    INSTALL_FD = None
                    os.close(fd)
            def lock(path):
                fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
                try:
                    inspect(path, private=True)
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    return fd
                except BlockingIOError:
                    os.close(fd)
                    raise Refused('BUSY')
                except BaseException:
                    os.close(fd)
                    raise
            def remove_tree(root):
                device = os.lstat(root).st_dev
                scan(root)
                for parent, dirs, files, fd in os.fwalk(root, topdown=False, follow_symlinks=False):
                    need(os.fstat(fd).st_dev == device and not any(under(m, root) for m in mounts()), 'MOUNT')
                    for name in files:
                        os.unlink(name, dir_fd=fd)
                    for name in dirs:
                        if stat.S_ISLNK(os.stat(name, dir_fd=fd, follow_symlinks=False).st_mode):
                            os.unlink(name, dir_fd=fd)
                        else:
                            os.rmdir(name, dir_fd=fd)
                os.rmdir(root)
            def main():
                global expected, panel_id, job, STAGE
                need(os.geteuid() == 0 and os.getegid() == 0, 'ROOT')
                os.umask(0o077)
                signal.signal(signal.SIGALRM, lambda signum, frame: (_ for _ in ()).throw(Refused('UNCONFIRMED')))
                signal.alarm(270)
                expected = json.loads(os.environ.pop('XBOARD_EXPECTED'))
                panel_id = expected['panelId']
                need(re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', panel_id), 'OWNERSHIP')
                job = '/root/.xboard-install/' + panel_id
                STAGE = 'PARENTS'
                parents()
                STAGE = 'INSTALL_LOCK'
                with install_lock():
                    remove_panel()
            def remove_panel():
                global STAGE
                STAGE = 'COMPLETION'
                if completed():
                    print('REMOVED', flush=True)
                    return
                STAGE = 'JOB'
                job_boundary()
                STAGE = 'LOCK'
                maintenance = '/root/.xboard-maintenance'
                if not os.path.lexists(maintenance):
                    os.mkdir(maintenance, 0o700)
                inspect(maintenance, True, True)
                need(not any(under(m, maintenance) for m in mounts()), 'MOUNT')
                maintenance_fd = lock(maintenance + '/lock')
                job_fd = None
                try:
                    need(not os.path.lexists(maintenance + '/active'), 'BUSY')
                    job_fd = lock(job + '/uninstall-lock')
                    STAGE = 'COMPLETION'
                    if completed():
                        print('REMOVED', flush=True)
                        return
                    if os.path.lexists(job + '/uninstall-complete'):
                        value = receipt(job + '/uninstall-started')
                        validate_remaining(value)
                        clean_additional()
                        purge_job()
                        clean_active()
                        verify_removed('/system.slice/x-ui.service', REMOVAL_PATHS)
                        verify_job_removed()
                        print('REMOVED', flush=True)
                        return
                    STAGE = 'RECEIPT'
                    resuming = os.path.lexists(job + '/uninstall-started')
                    if resuming:
                        value = receipt(job + '/uninstall-started')
                    else:
                        STAGE = 'OWNERSHIP'
                        try:
                            ownership()
                        except Refused:
                            raise
                        except Exception:
                            raise Refused('OWNERSHIP')
                        for other in ('/etc/xui', '/etc/3x-ui', '/etc/3xui', '/usr/local/3x-ui', '/usr/local/xui'):
                            need(not os.path.lexists(other), 'TARGET')
                        STAGE = 'PANEL'
                        for root in TREES:
                            scan(root)
                            safe_certificates(root)
                        validate_additional()
                        active_boundary()
                        STAGE = 'DATABASE'
                        verify_db()
                        STAGE = 'SERVICE'
                        cgroup = service()
                        processes(cgroup)
                        roots = TREES + ((DROP,) if os.path.lexists(DROP) else ())
                        controls = (UNIT, CLI) + (tuple(DROP + '/' + name for name in sorted(os.listdir(DROP))
                            if stat.S_ISREG(os.lstat(DROP + '/' + name).st_mode)) if DROP in roots else ())
                        value = {'version': 'XBOARD_NATIVE_REMOVE_V2', 'panelId': panel_id, 'target': fingerprint(),
                                 'roots': {p: [os.lstat(p).st_dev, os.lstat(p).st_ino] for p in roots},
                                 'controls': {p: digest(p) for p in controls}}
                        STAGE = 'AUTHORIZE'
                        validate_remaining(value)
                        ownership()
                        verify_db()
                        service()
                        write_receipt(job + '/uninstall-started', value)
                    STAGE = 'REVALIDATE'
                    validate_remaining(value)
                    if resuming:
                        run(['daemon-reload'])
                    cgroup = '/system.slice/x-ui.service'
                    STAGE = 'STOP'
                    if os.path.lexists(UNIT):
                        current_cgroup = service(resuming=True)
                        processes(current_cgroup)
                        run(['stop', 'x-ui.service'], timeout=45)
                        if prop('ActiveState') == 'failed':
                            run(['reset-failed', 'x-ui.service'])
                    def stopped(timeout):
                        need(prop('ActiveState', timeout=timeout) == 'inactive' if os.path.lexists(UNIT)
                             else absence_state(timeout), 'PROCESS')
                        processes(cgroup, stopped=True)
                        return True
                    wait_for(stopped, 15, 'PROCESS')
                    STAGE = 'DISABLE'
                    validate_remaining(value)
                    if os.path.lexists(DB):
                        verify_db()
                    if os.path.lexists(UNIT):
                        service(resuming=True)
                        run(['disable', '--no-reload', 'x-ui.service'])
                    need(not os.path.lexists(ENABLE_LINK), 'SERVICE')
                    STAGE = 'DELETE'
                    for root in TREES + (DROP,):
                        validate_remaining(value)
                        processes(cgroup, stopped=True)
                        if os.path.lexists(root):
                            remove_tree(root)
                    for path in (CLI, UNIT):
                        validate_remaining(value)
                        if os.path.lexists(path):
                            os.unlink(path)
                    clean_additional()
                    STAGE = 'RELOAD'
                    run(['daemon-reload'])
                    STAGE = 'VERIFY'
                    wait_for(absence_state, 10, 'UNCONFIRMED')
                    verify_removed(cgroup, REMOVAL_PATHS)
                    STAGE = 'PURGE_JOB'
                    purge_job()
                    clean_active()
                    verify_job_removed()
                    STAGE = 'COMPLETE'
                    write_receipt(job + '/uninstall-complete', {key: value[key] for key in ('version', 'panelId', 'target')})
                    verify_removed(cgroup, REMOVAL_PATHS)
                    verify_job_removed()
                    print('REMOVED', flush=True)
                finally:
                    if job_fd is not None:
                        os.close(job_fd)
                    os.close(maintenance_fd)
            try:
                main()
            except Refused as e:
                print('REFUSED\t' + e.code + '\t' + STAGE, flush=True)
                raise SystemExit(1)
            except Exception:
                print('REFUSED\tUNCONFIRMED\t' + STAGE, flush=True)
                raise SystemExit(1)
        """.trimIndent()
    }
}
