package app.invilink.xboard.ssh

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

class MaintenanceException(message: String) : IllegalStateException(message)

class ThreeXMaintenance(private val connection: SshConnection) {
    private val mutex = Mutex()
    private var identity: SshHostIdentity? = null
    private var selectedOperation: InstallOperation? = null

    @Volatile
    var pendingJobId: String? = null
        private set

    internal suspend fun run(
        credentials: SshCredentials,
        expected: SshHostIdentity,
        operation: InstallOperation,
        resume: Boolean,
        onProgress: suspend (InstallProgress) -> Unit,
        release: ThreeXRelease,
    ): MaintenanceResult = mutex.withLock {
        require(operation != InstallOperation.FRESH)
        val checked = credentials.validate()
        require(checked.host == expected.host && checked.port.toInt() == expected.port)
        if (resume) {
            check(pendingJobId != null && identity?.host == expected.host && identity?.port == expected.port && selectedOperation == operation)
        } else {
            check(pendingJobId == null)
        }
        var reportedPercent = 3
        onProgress(InstallProgress(reportedPercent, "Подключение SSH для обслуживания.", true))
        val actual = connection.connect(checked)
        if (actual.host != expected.host || actual.port != expected.port) {
            connection.close()
            throw MaintenanceException("Адрес SSH не совпадает с выбранным сервером. Обслуживание не продолжено.")
        }
        if (!resume) {
            reportedPercent = 7
            onProgress(InstallProgress(reportedPercent, "Проверка условий обслуживания.", true))
            val report = connection.preflight()
            if (!report.ready || !report.existingInstallation) {
                throw MaintenanceException("Обслуживание требует root, поддерживаемую ОС и обнаруженную существующую 3x-UI. Новая установка не запускалась.")
            }
            val prerequisites = connection.execute(
                "env -i HOME=/root LC_ALL=C PATH=/usr/sbin:/usr/bin:/sbin:/bin /bin/sh -c " + shellQuote(
                    "test -x /usr/bin/python3 && test -x /usr/bin/curl && /usr/bin/python3 -I -c 'import sqlite3, ssl, tarfile' && printf 'READY\\n'",
                ),
                timeoutMillis = 15_000,
                maxBytes = 1024,
            )
            if (prerequisites.exitCode != 0 || prerequisites.stderr.isNotBlank() || prerequisites.stdout != "READY\n") {
                throw MaintenanceException("Для обслуживания нужны системные python3 с SQLite/SSL и curl. Установите недостающие пакеты средствами вашей ОС и повторите проверку. Служба и файлы панели не изменялись; задача не запускалась.")
            }
            val id = UUID.randomUUID().toString()
            val python = "JOB = '$ROOT/$id'\nOPERATION = '${operation.name}'\n" + runnerFor(release)
            val payload = Base64.getEncoder().encodeToString(python.toByteArray(Charsets.UTF_8))
            val launch = "import base64\nJOB = '$ROOT/$id'\nOPERATION = '${operation.name}'\nPAYLOAD = base64.b64decode('$payload', validate=True).decode('utf-8')\n" + LAUNCH
            reportedPercent = 12
            onProgress(InstallProgress(reportedPercent, "Условия проверены; запуск загрузки закреплённых архивов.", true))
            identity = expected
            selectedOperation = operation
            pendingJobId = id
            val response = input(launch)
            val receipt = if (response.exitCode == 0 && response.stderr.isBlank()) {
                try { JSONObject(response.stdout) } catch (_: Exception) { null }
            } else null
            if (receipt?.optString("status") == "FAILED") {
                val reason = REASONS[receipt.optString("reason")] ?: "Задача не была отправлена."
                if (receipt.optBoolean("safeToRetry")) {
                    pendingJobId = null
                    identity = null
                    selectedOperation = null
                    throw MaintenanceException("$reason Рабочие файлы и служба не изменялись; автоматического повтора нет.")
                }
                throw MaintenanceException("$reason Проверьте состояние сервера через новое подключение; данные задачи: $ROOT/$id.")
            }
            if (receipt?.optString("status") != "STARTED" || receipt.optString("phase") != "QUEUED" || receipt.optString("operation") != operation.name) {
                throw MaintenanceException("Запуск обслуживания не подтверждён. Проверьте сервер через новое подключение. Возможна незавершённая задача в $ROOT/active.")
            }
        }
        val id = checkNotNull(pendingJobId)
        repeat(1800) {
            val response = input("JOB = '$ROOT/$id'\n" + POLL)
            if (response.exitCode != 0 || response.stderr.isNotBlank()) {
                throw MaintenanceException("Не удалось прочитать состояние обслуживания. Задача могла продолжиться. Повтор не отправлен; проверьте сервер через новое подключение. Состояние: $ROOT/$id.")
            }
            val data = try { JSONObject(response.stdout) } catch (_: Exception) {
                throw MaintenanceException("Некорректное состояние обслуживания. Автоматического повтора нет. Состояние: $ROOT/$id.")
            }
            if (data.optString("operation") != operation.name) {
                throw MaintenanceException("Операция в состоянии задачи не совпадает; автоматический повтор не выполнялся.")
            }
            val phase = data.optString("phase")
            val message = PHASES[phase] ?: throw MaintenanceException("Неизвестный этап обслуживания; автоматический повтор не выполнялся.")
            when (data.optString("status")) {
                "RUNNING" -> {
                    reportedPercent = maxOf(reportedPercent, progressForPhase(phase))
                    onProgress(InstallProgress(reportedPercent, message, true))
                }
                "FAILED" -> {
                    val reason = REASONS[data.optString("reason")] ?: "Обслуживание не подтверждено."
                    if (data.optBoolean("safeToRetry")) {
                        pendingJobId = null
                        identity = null
                        selectedOperation = null
                        throw MaintenanceException("$reason Задача завершилась до изменения программы и службы. Состояние: $ROOT/$id. Автоматический повтор не выполняется.")
                    }
                    throw MaintenanceException("$reason Состояние и резервные копии (если созданы): $ROOT/$id. Проверьте VPS перед новой операцией.")
                }
                "SUCCEEDED" -> {
                    check(phase == "COMPLETE" && data.getString("operation") == operation.name)
                    val source = data.getString("source")
                    check(source in setOf("3.10.0", "3.11.0", release.version))
                    val target = data.getString("target")
                    check(target == if (operation == InstallOperation.REINSTALL) source else release.version)
                    val result = MaintenanceResult(operation, id, source, targetVersion = target, backupPath = "$ROOT/$id/backup", previouslyActive = data.getBoolean("active"))
                    val runtimeVerified = data.getBoolean("runtimeVerified")
                    check(runtimeVerified == result.previouslyActive)
                    pendingJobId = null
                    identity = null
                    selectedOperation = null
                    onProgress(InstallProgress(100, if (runtimeVerified) {
                        "Программные файлы заменены; запуск ранее активной службы, все таблицы SQLite, настройки, сертификаты и ключевые файлы проверены. Внешний доступ и TLS-сеанс не проверялись."
                    } else {
                        "Программные файлы заменены; все таблицы SQLite, настройки, сертификаты и ключевые файлы сохранены. Ранее неактивная служба не запускалась; работоспособность новой версии, внешний доступ и TLS-сеанс не проверялись."
                    }))
                    return@withLock result
                }
                else -> throw MaintenanceException("Неизвестный статус обслуживания; автоматический повтор не выполнялся.")
            }
            delay(2_000)
        }
        throw MaintenanceException("Время ожидания результата истекло. Откройте новое подключение для проверки сервера. Данные задачи: $ROOT/$id.")
    }

    private suspend fun input(python: String): SshCommandResult {
        val bytes = ("set -eu\numask 077\nexec /usr/bin/python3 -I - <<'XBOARD_MAINTENANCE'\n" + python + "\nXBOARD_MAINTENANCE\n").toByteArray(Charsets.UTF_8)
        return try {
            connection.executeInput("env -i HOME=/root LC_ALL=C PATH=/usr/sbin:/usr/bin:/sbin:/bin /bin/bash --noprofile --norc -s", bytes, 30_000, 4096)
        } finally { bytes.fill(0) }
    }

    companion object {
        internal fun runnerFor(release: ThreeXRelease): String = RUNNER
            .replace("releases = {v:archive(v, arch) for v in ('3.10.0', '3.11.0')}",
                "HASHES['${release.version}'] = {'amd64':'${release.amd64Hash}', 'arm64':'${release.arm64Hash}'}\n    releases = {v:archive(v, arch) for v in dict.fromkeys(('3.10.0', '3.11.0', '${release.version}'))}")
            .replace("target_version = source_version if OPERATION == 'REINSTALL' else '3.11.0'",
                "target_version = source_version if OPERATION == 'REINSTALL' else '${release.version}'\n    need(tuple(map(int, target_version.split('.'))) >= tuple(map(int, source_version.split('.'))), 'UNSUPPORTED')")

        private const val ROOT = "/root/.xboard-maintenance"
        private val PHASES = linkedMapOf(
            "QUEUED" to "Задача принята; проверка защищённых каталогов.",
            "DOWNLOAD" to "Загрузка и проверка SHA-256 закреплённых архивов.",
            "LAYOUT" to "Проверка происхождения бинарника, службы, CLI и SQLite.",
            "BACKUP" to "Остановка только x-ui и создание согласованной резервной копии.",
            "STAGE" to "Подготовка программных файлов без сброса базы и сертификатов.",
            "SWAP" to "Замена дерева программы атомарными переименованиями.",
            "VERIFY" to "Проверка запуска, SQLite и сохранности данных.",
            "COMPLETE" to "Обслуживание завершено.",
        )
        internal fun progressForPhase(phase: String): Int = when (phase) {
            "QUEUED" -> 3
            "DOWNLOAD" -> 12
            "LAYOUT" -> 35
            "BACKUP" -> 48
            "STAGE" -> 62
            "SWAP" -> 78
            "VERIFY" -> 90
            "COMPLETE" -> 99
            else -> throw MaintenanceException("Неизвестный этап обслуживания; автоматический повтор не выполнялся.")
        }
        private val REASONS = mapOf(
            "RUNNER" to "Фоновый процесс обслуживания завершился или его личность не подтверждена. Требуется ручная проверка; автоматический повтор не выполнялся.",
            "STALLED" to "Фоновая задача не начала работу за отведённое время. Требуется ручная проверка; автоматический повтор не выполнялся.",
            "BUSY" to "На VPS выполняется другая задача обслуживания. Дождитесь её завершения и подключитесь заново.",
            "LAUNCH" to "Фоновый процесс обслуживания не удалось запустить или подтвердить.",
            "RESOURCES" to "Неизвестная ссылка на сертификат или ключ: разрешены только обычные root-файлы внутри /etc/x-ui либо единого дерева /root/cert, без symlink и внешних ссылок. Рабочая программа не заменялась.",
            "UNSUPPORTED" to "Установка не соответствует штатной native-службе 3x-UI с SQLite в /etc/x-ui. Неизвестные файлы, форки, backend или окружение не изменены; понижение версии не выполнялось.",
            "SOURCE" to "Программные файлы панели не совпали с проверенными официальными архивами. Неизвестная установка не изменена; новая установка поверх неё не запускалась.",
            "SERVICE" to "Неподдерживаемая служба или CLI: допускаются только штатные native x-ui.service и x-ui.sh соответствующего релиза. Пользовательские команды и служба не заменяются.",
            "BACKEND" to "Неподдерживаемое окружение/backend: обслуживание разрешено только для SQLite в /etc/x-ui без нестандартных XUI_* и других переменных службы.",
            "SCHEMA" to "Неподдерживаемая схема SQLite: обязательные stock-таблицы/поля отсутствуют либо обнаружены пользовательские SQL-триггеры, представления или virtual tables. Программа и база не заменялись.",
            "ARCHIVE" to "Архив не прошёл загрузку, проверку закреплённого SHA-256 или безопасной структуры. Рабочие файлы не заменялись.",
            "BACKUP" to "Согласованная резервная копия не подтверждена. Замена программы не начиналась; проверьте состояние ранее активной службы.",
            "CHANGED" to "Обнаружено параллельное изменение файлов или службы. Автоматическая перезапись/восстановление запрещены; проверьте VPS вручную.",
            "VERIFY" to "Запуск или сохранность данных не подтверждены. Новая версия остановлена, если это удалось подтвердить; резервная копия оставлена. Требуется ручная проверка и восстановление, не новая установка.",
            "RESTORED" to "Замена программы не завершилась. Исходное дерево возвращено, база проверена и не перезаписывалась; прежнее состояние активности службы восстановлено.",
            "RECOVERY" to "Восстановление не подтверждено. Резервные копии оставлены; требуется ручная проверка VPS. Не запускайте установку повторно.",
        )
        internal val COMMON = """
            import os, stat, json, re
            ROOT = '/root/.xboard-maintenance'
            def need(value, code='UNSUPPORTED'):
                if not value:
                    raise RuntimeError(code)
            def inspect(path, directory=False, private=False, program=False):
                s = os.lstat(path)
                owner = (s.st_uid, s.st_gid)
                need(os.path.realpath(path) == path and owner in (((0, 0), (1001, 1001)) if program else ((0, 0),)) and not s.st_mode & 0o7022)
                need(stat.S_ISDIR(s.st_mode) if directory else stat.S_ISREG(s.st_mode) and s.st_nlink == 1)
                if private:
                    need(not program and stat.S_IMODE(s.st_mode) == (0o700 if directory else 0o600))
                return s
            def metadata(path, directory=False, program=False):
                s = inspect(path, directory, program=program)
                return dict(mode=stat.S_IMODE(s.st_mode), uid=s.st_uid, gid=s.st_gid)
            def setup():
                need(os.geteuid() == 0 and os.getegid() == 0)
                need(re.fullmatch(re.escape(ROOT) + r'/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', JOB))
                inspect('/', True)
                inspect('/root', True)
            def private_write(path, data):
                temporary = path + '.next-' + str(os.getpid())
                fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
                inode = os.fstat(fd).st_ino
                try:
                    with os.fdopen(fd, 'w', encoding='utf-8') as f:
                        f.write(data)
                        f.flush()
                        os.fsync(f.fileno())
                    os.replace(temporary, path)
                    fd = os.open(os.path.dirname(path), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                    try:
                        os.fsync(fd)
                    finally:
                        os.close(fd)
                finally:
                    if os.path.lexists(temporary) and os.lstat(temporary).st_ino == inode:
                        os.unlink(temporary)
            def state(status, phase, **extra):
                inspect(ROOT, True, True)
                inspect(JOB, True, True)
                private_write(JOB + '/state', json.dumps(dict(status=status, phase=phase, operation=OPERATION, **extra)))
            def runner_identity(pid):
                with open('/proc/' + str(pid) + '/stat', encoding='ascii') as f:
                    fields = f.read(4096).rsplit(')', 1)[1].split()
                need(fields[0] not in ('Z', 'X'), 'RUNNER')
                with open('/proc/' + str(pid) + '/cmdline', 'rb') as f:
                    command = f.read(4096)
                need(command == b'/usr/bin/python3\0-I\0' + (JOB + '/runner.py').encode() + b'\0', 'RUNNER')
                return dict(pid=pid, start=fields[19])
            def release_active():
                try:
                    inspect(ROOT, True, True)
                    inspect(ROOT + '/active', True, True)
                    inspect(ROOT + '/active/job', private=True)
                    with open(ROOT + '/active/job', encoding='utf-8') as f:
                        need(f.read(1024) == JOB, 'CHANGED')
                    need(os.listdir(ROOT + '/active') == ['job'], 'CHANGED')
                    os.unlink(ROOT + '/active/job')
                    os.rmdir(ROOT + '/active')
                    return True
                except Exception:
                    return False
        """.trimIndent() + "\n"
        internal val LAUNCH = COMMON + """
            os.umask(0o077)
            claimed = attempted = dispatched = False
            ready = False
            try:
                setup()
                if not os.path.lexists(ROOT):
                    os.mkdir(ROOT, 0o700)
                inspect(ROOT, True, True)
                os.mkdir(JOB, 0o700)
                ready = True
                state('RUNNING', 'QUEUED')
                import subprocess
                os.mkdir(ROOT + '/active', 0o700)
                claimed = True
                private_write(ROOT + '/active/job', JOB)
                private_write(JOB + '/runner.py', PAYLOAD)
                compile(PAYLOAD, JOB + '/runner.py', 'exec')
                with open(os.devnull, 'rb') as stdin, open(os.devnull, 'wb') as output:
                    attempted = True
                    child = subprocess.Popen(['/usr/bin/python3', '-I', JOB + '/runner.py'], stdin=stdin, stdout=output, stderr=output,
                                             start_new_session=True, close_fds=True,
                                             env={'HOME':'/root', 'LC_ALL':'C', 'PATH':'/usr/sbin:/usr/bin:/sbin:/bin'})
                    dispatched = True
                private_write(JOB + '/runner', json.dumps(runner_identity(child.pid)))
                print(json.dumps(dict(status='STARTED', phase='QUEUED', operation=OPERATION)), flush=True)
            except Exception as failure:
                code = 'LAUNCH' if attempted or isinstance(failure, SyntaxError) else ('BUSY' if isinstance(failure, FileExistsError) and not claimed else 'UNSUPPORTED')
                safe = not attempted
                if safe and claimed:
                    safe = release_active()
                if ready and not dispatched:
                    state('FAILED', 'QUEUED', reason=code, safeToRetry=safe)
                if dispatched:
                    raise
                print(json.dumps(dict(status='FAILED', phase='QUEUED', operation=OPERATION, reason=code, safeToRetry=safe)), flush=True)
        """.trimIndent() + "\n"
        internal val POLL = COMMON + """
            setup()
            inspect(ROOT, True, True)
            inspect(JOB, True, True)
            import time
            snapshot = inspect(JOB + '/state', private=True)
            need(snapshot.st_size <= 2048)
            with open(JOB + '/state', encoding='utf-8') as f:
                data = json.load(f)
            phases = ('QUEUED', 'DOWNLOAD', 'LAYOUT', 'BACKUP', 'STAGE', 'SWAP', 'VERIFY', 'COMPLETE')
            need(data.get('operation') in ('UPDATE', 'REINSTALL') and data.get('phase') in phases)
            need(data.get('status') in ('RUNNING', 'FAILED', 'SUCCEEDED'))
            OPERATION = data['operation']
            if data['status'] == 'RUNNING':
                code = None
                if os.path.lexists(JOB + '/runner'):
                    need(inspect(JOB + '/runner', private=True).st_size <= 1024)
                    with open(JOB + '/runner', encoding='utf-8') as f:
                        runner = json.load(f)
                    need(type(runner.get('pid')) is int and runner['pid'] > 0)
                    try:
                        need(runner_identity(runner['pid']) == runner, 'RUNNER')
                    except (OSError, RuntimeError, IndexError):
                        code = 'RUNNER'
                elif time.time() - snapshot.st_mtime > 20:
                    code = 'RUNNER'
                if data['phase'] == 'QUEUED' and time.time() - snapshot.st_mtime > 60:
                    code = code or 'STALLED'
                if code:
                    need(inspect(JOB + '/state', private=True).st_size <= 2048)
                    with open(JOB + '/state', encoding='utf-8') as f:
                        latest = json.load(f)
                    need(latest.get('operation') == OPERATION and latest.get('phase') in phases)
                    need(latest.get('status') in ('RUNNING', 'FAILED', 'SUCCEEDED'))
                    data = latest if latest['status'] != 'RUNNING' or (code == 'STALLED' and latest['phase'] != 'QUEUED') else dict(latest, status='FAILED', reason=code, safeToRetry=False)
            print(json.dumps(data))
        """.trimIndent() + "\n"
        internal val RUNNER = COMMON + """
            LIVE = '/usr/local/x-ui'
            CONFIG = '/etc/x-ui'
            CERTROOT = '/root/cert'
            DB = CONFIG + '/x-ui.db'
            UNIT = '/etc/systemd/system/x-ui.service'
            CLI = '/usr/bin/x-ui'
            STAGE = '/usr/local/.xboard-stage-' + JOB.rsplit('/', 1)[1]
            PARKED = '/usr/local/.xboard-old-' + JOB.rsplit('/', 1)[1]
            phase = 'QUEUED'
            reason = 'UNSUPPORTED'
            stopped = swapped = target_started = prior_active = False
            baseline_tree = baseline_config = baseline_capture = baseline_resources = control = None
            lock = None
            armed = False
            def resources(path):
                db = db_open(path)
                references = set()
                def reference(value):
                    need(isinstance(value, str) and value.startswith('/') and os.path.normpath(value) == value, 'RESOURCES')
                    need(value.startswith(CONFIG + '/') or value.startswith(CERTROOT + '/'), 'RESOURCES')
                    need(value not in (DB, DB + '-wal', DB + '-shm', DB + '-journal'), 'RESOURCES')
                    parent = os.path.dirname(value)
                    while parent != '/':
                        inspect(parent, True)
                        parent = os.path.dirname(parent)
                    inspect(value)
                    references.add(value)
                def walk(value, key='', sensitive=False):
                    key = re.sub('[^a-z]', '', key.lower())
                    sensitive = sensitive or any(word in key for word in ('cert', 'key', 'tls', 'reality', 'secret', 'encrypt', 'ech'))
                    file_key = sensitive and key.endswith(('file', 'files', 'filename', 'path', 'folder'))
                    if isinstance(value, dict):
                        for k, v in value.items():
                            walk(v, k, sensitive)
                    elif isinstance(value, list):
                        for v in value:
                            walk(v, key, sensitive)
                    elif isinstance(value, str) and value:
                        if file_key or (sensitive and value.startswith(('/', './', '../', '~/', 'file:'))):
                            reference(value)
                        elif value.lstrip().startswith(('{', '[')):
                            try:
                                parsed = json.loads(value)
                            except ValueError:
                                need(not sensitive, 'RESOURCES')
                            else:
                                walk(parsed, key, sensitive)
                try:
                    for key, value in db.execute('SELECT key, value FROM settings'):
                        walk(value, key)
                    for table in table_names(db):
                        cols = column_info(db, table)
                        for row in db.execute('SELECT * FROM "' + table + '"'):
                            for col, value in zip(cols, row):
                                walk(value, col[1])
                    parents = {p:metadata(p, True) for p in ('/etc', CONFIG, '/root')}
                    for path in references:
                        parent = os.path.dirname(path)
                        while parent != '/':
                            parents[parent] = metadata(parent, True)
                            parent = os.path.dirname(parent)
                    tree = scan(CERTROOT) if os.path.lexists(CERTROOT) else None
                    return dict(references={p:file_record(p) for p in sorted(references)}, parents=parents, certificates=tree)
                except Exception as failure:
                    raise RuntimeError('RESOURCES') from failure
                finally:
                    db.close()
            def advance(value):
                global phase
                phase = value
                state('RUNNING', phase)
            def run(args, timeout=45):
                return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                      stdin=subprocess.DEVNULL, timeout=timeout).stdout.decode('utf-8').strip()
            def prop(name):
                return run(['/usr/bin/systemctl', 'show', 'x-ui.service', '--property=' + name, '--value'])
            def service(action):
                need(control is not None and controls() == control, 'CHANGED')
                run(['/usr/bin/systemctl', action, 'x-ui.service'])
            def service_environment(pid):
                with open('/proc/' + pid + '/environ', 'rb') as f:
                    environment = f.read(131073)
                need(len(environment) <= 131072, 'BACKEND')
                allowed = {'PATH', 'LANG', 'LANGUAGE', 'TERM', 'HOME', 'LOGNAME', 'USER', 'SHELL', 'INVOCATION_ID',
                           'JOURNAL_STREAM', 'SYSTEMD_EXEC_PID', 'MEMORY_PRESSURE_WATCH', 'MEMORY_PRESSURE_WRITE',
                           'XRAY_VMESS_AEAD_FORCED', 'XUI_DB_TYPE'}
                for item in environment.split(b'\0'):
                    if not item:
                        continue
                    need(b'=' in item, 'BACKEND')
                    key, value = item.split(b'=', 1)
                    key = key.decode('ascii')
                    need(key in allowed or re.fullmatch('LC_[A-Z_]+', key), 'BACKEND')
                    if key == 'XUI_DB_TYPE':
                        need(value == b'sqlite', 'BACKEND')
                    elif key == 'XRAY_VMESS_AEAD_FORCED':
                        need(value == b'false', 'BACKEND')
                    elif key == 'HOME':
                        need(value == b'/root', 'BACKEND')
                    elif key == 'PATH':
                        need(all(p in (b'/usr/local/sbin', b'/usr/local/bin', b'/usr/sbin', b'/usr/bin', b'/sbin', b'/bin')
                             for p in value.split(b':')), 'BACKEND')
            def digest(path, program=False):
                inspect(path, program=program)
                h = hashlib.sha256()
                with open(path, 'rb') as f:
                    for chunk in iter(lambda: f.read(1048576), b''):
                        h.update(chunk)
                return h.hexdigest()
            def file_record(path, program=False):
                return dict(metadata(path, program=program), sha256=digest(path, program=program))
            def scan(root, program=False):
                device = inspect(root, True, program=program).st_dev
                result = {'.':metadata(root, True, program=program)}
                count = total = 0
                for parent, dirs, files in os.walk(root, followlinks=False):
                    need(inspect(parent, True, program=program).st_dev == device)
                    for name in dirs:
                        path = parent + '/' + name
                        need(inspect(path, True, program=program).st_dev == device)
                        result[os.path.relpath(path, root) + '/'] = metadata(path, True, program=program)
                        count += 1
                    for name in files:
                        path = parent + '/' + name
                        s = inspect(path, program=program)
                        need(s.st_dev == device)
                        count += 1
                        total += s.st_size
                        need(count <= 20000 and total <= 4 * 1024**3)
                        result[os.path.relpath(path, root)] = file_record(path, program=program)
                return result
            def copy_program_file(source, destination):
                before = file_record(source, program=True)
                shutil.copy2(source, destination, follow_symlinks=False)
                os.chown(destination, before['uid'], before['gid'], follow_symlinks=False)
                need(file_record(source, program=True) == before and file_record(destination, program=True) == before, 'CHANGED')
                return destination
            def copy_program(source, destination):
                before = scan(source, program=True)
                shutil.copytree(source, destination, copy_function=copy_program_file)
                for name, value in before.items():
                    if name == '.' or name.endswith('/'):
                        path = destination if name == '.' else destination + '/' + name.rstrip('/')
                        os.chown(path, value['uid'], value['gid'], follow_symlinks=False)
                need(scan(source, program=True) == before and scan(destination, program=True) == before, 'CHANGED')
            def configuration(tree):
                result = {}
                for name, value in tree.items():
                    if name in ('x-ui.db-wal', 'x-ui.db-shm'):
                        need(value['mode'] & ~tree['x-ui.db']['mode'] == 0, 'CHANGED')
                    elif name == 'x-ui.db':
                        result[name] = {k:v for k, v in value.items() if k != 'sha256'}
                    else:
                        result[name] = value
                return result
            def directives(path):
                inspect(path)
                with open(path, encoding='utf-8') as f:
                    lines = f.read(65537)
                need(len(lines) <= 65536)
                return [s.strip() for s in lines.splitlines() if s.strip() and not s.lstrip().startswith(('#', ';'))]
            HASHES = {
                '3.11.0': {'amd64':'0ddfa4702f595442e2962acd81addc768fdacaa4ff2672c971a0463dae744916',
                           'arm64':'f50b4c30fd919d018f2f4c3d8db53a4ca027503d581328727770afb116a79d90'},
                '3.10.0': {'amd64':'b647a3e7d6695600bdd18cbfd34959991be09d4b1ad1e05efa790c0cf69ca40d',
                           'arm64':'a0bb393989b96833c84460dff21c968a6263f4e09b7ceff93e0f92975aeadc16'},
            }
            def archive(version, arch):
                path = JOB + '/' + version + '.tar.gz'
                url = 'https://github.com/MHSanaei/3x-ui/releases/download/v' + version + '/x-ui-linux-' + arch + '.tar.gz'
                run(['/usr/bin/curl', '-q', '--fail', '--silent', '--show-error', '--location', '--proto', '=https',
                     '--proto-redir', '=https', '--tlsv1.2', '--connect-timeout', '15', '--max-time', '180',
                     '--max-filesize', str(512 * 1024**2), '--output', path, url], 190)
                os.chmod(path, 0o600)
                need(digest(path) == HASHES[version][arch], 'ARCHIVE')
                destination = JOB + '/release-' + version
                os.mkdir(destination, 0o700)
                with tarfile.open(path, 'r:gz') as tar:
                    members = tar.getmembers()
                    need(1 <= len(members) <= 2000, 'ARCHIVE')
                    seen = set()
                    entries = []
                    total = 0
                    for m in members:
                        name = m.name
                        if name.startswith('./'):
                            name = name[2:]
                        if m.isdir():
                            name = name.rstrip('/')
                        parts = name.split('/')
                        need(parts[0] == 'x-ui' and all(p not in ('', '.', '..') for p in parts), 'ARCHIVE')
                        need(not name.startswith('/') and '\\' not in name and name not in seen, 'ARCHIVE')
                        need(m.isdir() or m.isreg(), 'ARCHIVE')
                        need(not (m.issym() or m.islnk() or m.isdev()) and not m.mode & 0o7000, 'ARCHIVE')
                        seen.add(name)
                        entries.append((name, m))
                        total += m.size
                        need(0 <= m.size <= 512 * 1024**2 and total <= 1024**3, 'ARCHIVE')
                    for name, m in sorted(entries, key=lambda item: (item[0].count('/'), item[0])):
                        dest = destination + '/' + name
                        need(os.path.isdir(os.path.dirname(dest)), 'ARCHIVE')
                        if m.isdir():
                            os.mkdir(dest, 0o700)
                        else:
                            with tar.extractfile(m) as src, open(dest, 'xb') as out:
                                shutil.copyfileobj(src, out)
                            os.chmod(dest, m.mode & 0o755)
                    for name, m in reversed(entries):
                        if m.isdir():
                            os.chmod(destination + '/' + name, m.mode & 0o755)
                tree = destination + '/x-ui'
                need(inspect(tree + '/x-ui').st_mode & 0o111, 'ARCHIVE')
                need(inspect(tree + '/bin/xray-linux-' + arch).st_mode & 0o111, 'ARCHIVE')
                inspect(tree + '/x-ui.sh')
                inspect(tree + '/x-ui.service.debian')
                return tree
            def table_names(db):
                names = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
                need(all(re.fullmatch('[A-Za-z_][A-Za-z0-9_]*', n) for n in names), 'SCHEMA')
                return names
            def column_info(db, name):
                fields = db.execute('PRAGMA table_xinfo("' + name + '")').fetchall()
                need(fields and all(re.fullmatch('[A-Za-z_][A-Za-z0-9_]*', r[1]) and r[6] == 0 for r in fields), 'SCHEMA')
                return fields
            def db_open(path):
                inspect(path)
                db = sqlite3.connect('file:' + quote(path, safe='/') + '?mode=ro', uri=True, timeout=10)
                try:
                    db.execute('PRAGMA query_only=ON')
                    db.execute('PRAGMA trusted_schema=OFF')
                    db.execute('BEGIN')
                    need(not db.execute("SELECT 1 FROM sqlite_master WHERE type IN ('trigger', 'view') OR upper(sql) LIKE '%VIRTUAL TABLE%' LIMIT 1").fetchall(), 'SCHEMA')
                    need(db.execute('PRAGMA quick_check').fetchall() == [('ok',)], 'VERIFY')
                    return db
                except Exception:
                    db.close()
                    raise
            def semantic(value):
                if isinstance(value, bytes):
                    return ['blob', base64.b64encode(value).decode('ascii')]
                if isinstance(value, float):
                    return ['float', value.hex()]
                return [type(value).__name__, value]
            def capture(path):
                db = db_open(path)
                try:
                    names = table_names(db)
                    need(all(n in names for n in ('users', 'settings', 'inbounds')), 'SCHEMA')
                    required = {'users': {'id', 'username', 'password'}, 'settings': {'key', 'value'},
                                'inbounds': {'id', 'settings', 'port', 'protocol', 'enable'}}
                    schema = {}
                    result = {}
                    for name in names:
                        fields = column_info(db, name)
                        cols = [r[1] for r in fields]
                        need(required.get(name, set()) <= set(cols), 'SCHEMA')
                        sql = db.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone()[0]
                        schema[name] = dict(columns=fields, sql=sql)
                        selected = ['"' + c + '"' for c in cols]
                        if not re.search(r'\bWITHOUT\s+ROWID\b', sql, re.I):
                            alias = next((a for a in ('_rowid_', 'rowid', 'oid') if a not in cols), None)
                            need(alias is not None, 'SCHEMA')
                            selected.insert(0, '"' + alias + '"')
                        rows = db.execute('SELECT ' + ','.join(selected) + ' FROM "' + name + '"').fetchmany(100001)
                        need(len(rows) <= 100000, 'SCHEMA')
                        result[name] = sorted(json.dumps([semantic(v) for v in row], separators=(',', ':')) for row in rows)
                    indexes = db.execute("SELECT name, tbl_name, sql FROM sqlite_master WHERE type='index' ORDER BY name").fetchall()
                    properties = {p:db.execute('PRAGMA ' + p).fetchone()[0] for p in ('user_version', 'application_id', 'encoding')}
                    need(len(result['users']) > 0, 'SCHEMA')
                    return dict(tables=schema, indexes=indexes, properties=properties), result
                finally:
                    db.close()
            def controls():
                need(all(not os.path.lexists(path) for path in absent_control_paths), 'CHANGED')
                if expected_dropins:
                    need(os.listdir(dropdir) == ['10-xui-sandbox.conf'], 'CHANGED')
                values = {p:file_record(p) for p in control_paths}
                for p in control_directories:
                    values[p] = metadata(p, True)
                need(prop('FragmentPath') == UNIT and prop('DropInPaths') == expected_dropins, 'CHANGED')
                for key in ('User', 'Group', 'WorkingDirectory', 'RootDirectory', 'RootImage', 'Environment',
                            'EnvironmentFiles', 'ExecStart', 'ExecReload', 'ExecStartPre', 'ExecStartPost', 'ExecStop', 'ExecStopPost',
                            'PassEnvironment', 'KillMode', 'NeedDaemonReload', 'LoadState', 'Type', 'Restart'):
                    values['property:' + key] = prop(key)
                manager = run(['/usr/bin/systemctl', 'show-environment'])
                manager_lines = manager.splitlines()
                need(all('=' in line and (line.split('=', 1)[0] in ('PATH', 'LANG', 'LANGUAGE', 'TERM') or
                     re.fullmatch('LC_[A-Z_]+', line.split('=', 1)[0])) for line in manager_lines), 'BACKEND')
                values['manager'] = sorted(manager_lines)
                return values
            def no_live_process():
                need(prop('ActiveState') == 'inactive' and prop('MainPID') == '0', 'CHANGED')
                for name in os.listdir('/proc'):
                    if name.isdigit():
                        try:
                            exe = os.readlink('/proc/' + name + '/exe')
                            need(not exe.startswith((LIVE + '/', PARKED + '/')), 'CHANGED')
                        except FileNotFoundError:
                            pass
            def fsync_tree(root):
                for parent, dirs, files in os.walk(root):
                    for name in files:
                        fd = os.open(parent + '/' + name, os.O_RDONLY | os.O_NOFOLLOW)
                        try:
                            os.fsync(fd)
                        finally:
                            os.close(fd)
                    fd = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
                    try:
                        os.fsync(fd)
                    finally:
                        os.close(fd)
            def config_guard():
                need(capture(DB) == baseline_capture, 'VERIFY')
                actual = scan(CONFIG)
                need(configuration(actual) == configuration(baseline_config), 'CHANGED')
                for name in ('x-ui.db-wal', 'x-ui.db-shm'):
                    if name in actual and name in baseline_config:
                        need({k:v for k, v in actual[name].items() if k != 'sha256'} ==
                             {k:v for k, v in baseline_config[name].items() if k != 'sha256'}, 'CHANGED')
                need(resources(DB) == baseline_resources, 'CHANGED')
            def guard(program):
                need(controls() == control and scan(LIVE, program=True) == program, 'CHANGED')
                config_guard()
            def running_target(check_environment=True):
                need(prop('ActiveState') == 'active', 'VERIFY')
                pid = prop('MainPID')
                need(pid.isdigit() and int(pid) > 0 and os.path.realpath('/proc/' + pid + '/exe') == LIVE + '/x-ui', 'VERIFY')
                need(digest(LIVE + '/x-ui', program=True) == digest(target + '/x-ui'), 'CHANGED')
                if check_environment:
                    service_environment(pid)
            def timed_out(signum, frame):
                raise TimeoutError('maintenance deadline')
            try:
                os.umask(0o077)
                setup()
                inspect(ROOT, True, True)
                inspect(JOB, True, True)
                inspect(ROOT + '/active', True, True)
                inspect(ROOT + '/active/job', private=True)
                with open(ROOT + '/active/job', encoding='utf-8') as active_file:
                    need(active_file.read(1024) == JOB, 'CHANGED')
                import time
                for attempt in range(100):
                    if os.path.lexists(JOB + '/runner'):
                        inspect(JOB + '/runner', private=True)
                        with open(JOB + '/runner', encoding='utf-8') as f:
                            need(json.load(f) == runner_identity(os.getpid()), 'RUNNER')
                        break
                    time.sleep(0.1)
                else:
                    raise RuntimeError('RUNNER')
                import fcntl, hashlib, shutil, sqlite3, subprocess, tarfile, base64, signal
                from urllib.parse import quote
                lock = os.open(ROOT + '/lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
                inspect(ROOT + '/lock', private=True)
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                signal.signal(signal.SIGALRM, timed_out)
                signal.alarm(1200)
                armed = True
                for directory in ('/usr', '/usr/local', '/usr/bin', '/etc', '/etc/systemd', '/etc/systemd/system'):
                    inspect(directory, True)
                for tree in (LIVE, CONFIG, CERTROOT):
                    if tree == CERTROOT and not os.path.lexists(tree):
                        continue
                    inspect(tree, True, program=tree == LIVE)
                    with open('/proc/self/mountinfo', encoding='utf-8') as f:
                        for line in f:
                            mount = re.sub(r'\\([0-7]{3})', lambda m: chr(int(m[1], 8)), line.split()[4])
                            need(mount != tree and not mount.startswith(tree + '/'))
                for path in ('/etc/sysconfig/x-ui', '/etc/conf.d/x-ui', '/run/systemd/system/x-ui.service',
                             '/run/systemd/system/x-ui.service.d', '/lib/systemd/system/x-ui.service',
                             '/usr/lib/systemd/system/x-ui.service', '/lib/systemd/system/x-ui.service.d',
                             '/usr/lib/systemd/system/x-ui.service.d'):
                    need(not os.path.lexists(path))
                need(not os.path.lexists(STAGE) and not os.path.lexists(PARKED))
                need(OPERATION in ('UPDATE', 'REINSTALL'))
                arch = {'x86_64':'amd64', 'aarch64':'arm64'}.get(os.uname().machine)
                need(arch is not None)
                advance('DOWNLOAD')
                reason = 'ARCHIVE'
                releases = {v:archive(v, arch) for v in ('3.10.0', '3.11.0')}
                need(digest(releases['3.11.0'] + '/x-ui.sh') == '26c229d5fb3b184e81581b4b223b801582ca7cf5908aaedeb8d1c7c70ad4cc25', 'ARCHIVE')
                need(digest(releases['3.11.0'] + '/x-ui.service.debian') == '95cb6e39499601148404cadf3b4d75b0489301ff7e7ab565cd9db21d23be957c', 'ARCHIVE')
                installed_hash = digest(LIVE + '/x-ui', program=True)
                source_version = next((v for v, tree in releases.items() if installed_hash == digest(tree + '/x-ui')), None)
                need(source_version is not None, 'SOURCE')
                source = releases[source_version]
                target_version = source_version if OPERATION == 'REINSTALL' else '3.11.0'
                target = releases[target_version]
                reason = 'UNSUPPORTED'
                advance('LAYOUT')
                stock = scan(source)
                current = scan(LIVE, program=True)
                for name, value in current.items():
                    need(name == 'bin/config.json' or (name in stock and
                         {k:v for k, v in value.items() if k not in ('uid', 'gid')} ==
                         {k:v for k, v in stock[name].items() if k not in ('uid', 'gid')}))
                need('x-ui' in current and 'bin/' in current and inspect(LIVE + '/x-ui', program=True).st_mode & 0o111)
                need(any(digest(CLI) == digest(tree + '/x-ui.sh') for tree in releases.values()), 'SERVICE')
                need(any(directives(UNIT) == directives(tree + '/x-ui.service.debian') for tree in releases.values()), 'SERVICE')
                control_paths = [UNIT, CLI]
                control_directories = ['/usr', '/usr/local', '/usr/bin', '/etc', '/etc/systemd', '/etc/systemd/system']
                env = '/etc/default/x-ui'
                if os.path.lexists(env):
                    need(directives(env) in ([], ['XUI_DB_TYPE=sqlite']), 'BACKEND')
                    control_paths.append(env)
                    control_directories.append('/etc/default')
                dropdir = '/etc/systemd/system/x-ui.service.d'
                expected_dropins = ''
                if os.path.lexists(dropdir):
                    inspect(dropdir, True)
                    need(os.listdir(dropdir) == ['10-xui-sandbox.conf'])
                    drop = dropdir + '/10-xui-sandbox.conf'
                    base = ['[Service]', 'ReadWritePaths=-/etc/x-ui -/var/log/x-ui -/usr/local/x-ui/bin -/usr/local/x-ui',
                            'ReadWriteDirectories=-/etc/x-ui -/var/log/x-ui -/usr/local/x-ui/bin -/usr/local/x-ui']
                    need(directives(drop) in (base, base + ['SystemCallFilter=@system-service', 'SystemCallErrorNumber=EPERM']))
                    control_paths.append(drop)
                    control_directories.append(dropdir)
                    expected_dropins = drop
                need(prop('LoadState') == 'loaded' and prop('NeedDaemonReload') == 'no')
                need(prop('User') in ('', 'root') and prop('Group') in ('', 'root'))
                need(prop('WorkingDirectory') == LIVE)
                for key in ('RootDirectory', 'RootImage', 'ExecStartPre', 'ExecStartPost', 'ExecStop', 'ExecStopPost', 'PassEnvironment'):
                    need(prop(key) == '')
                need(prop('Environment') in ('XRAY_VMESS_AEAD_FORCED=false', ''))
                need(prop('EnvironmentFiles') in ('', '/etc/default/x-ui (ignore_errors=yes)'))
                start = prop('ExecStart')
                need(start.count('path=') == 1 and re.search(r'path=/usr/local/x-ui/x-ui\s*;', start))
                need(re.search(r'argv\[\]=/usr/local/x-ui/x-ui\s*;', start))
                need(prop('KillMode') in ('mixed', 'control-group'))
                initial_state = prop('ActiveState')
                need(initial_state in ('active', 'inactive'))
                prior_active = initial_state == 'active'
                prior_pid = prop('MainPID')
                if prior_active:
                    pid = prior_pid
                    need(pid.isdigit() and int(pid) > 0 and os.path.realpath('/proc/' + pid + '/exe') == LIVE + '/x-ui', 'SERVICE')
                    service_environment(pid)
                else:
                    no_live_process()
                absent_control_paths = [p for p in (env, dropdir, '/etc/sysconfig/x-ui', '/etc/conf.d/x-ui',
                    '/run/systemd/system/x-ui.service', '/run/systemd/system/x-ui.service.d',
                    '/lib/systemd/system/x-ui.service', '/usr/lib/systemd/system/x-ui.service',
                    '/lib/systemd/system/x-ui.service.d', '/usr/lib/systemd/system/x-ui.service.d') if not os.path.lexists(p)]
                control = controls()
                capture(DB)
                initial_config = scan(CONFIG)
                need('x-ui.db-journal' not in initial_config, 'BACKUP')
                baseline_resources = resources(DB)
                advance('BACKUP')
                reason = 'BACKUP'
                backup = JOB + '/backup'
                os.mkdir(backup, 0o700)
                database_files = {'x-ui.db', 'x-ui.db-wal', 'x-ui.db-shm', 'x-ui.db-journal'}
                shutil.copytree(CONFIG, backup + '/configuration-files',
                                ignore=lambda parent, names: database_files.intersection(names) if parent == CONFIG else [])
                need(scan(backup + '/configuration-files') == {k:v for k, v in initial_config.items() if k not in database_files}, 'BACKUP')
                if baseline_resources['certificates'] is not None:
                    shutil.copytree(CERTROOT, backup + '/certificates')
                    need(scan(backup + '/certificates') == baseline_resources['certificates'], 'BACKUP')
                os.mkdir(backup + '/control', 0o700)
                for index, path in enumerate(control_paths):
                    shutil.copy2(path, backup + '/control/' + str(index))
                    need(file_record(backup + '/control/' + str(index)) == control[path], 'BACKUP')
                private_write(backup + '/prestop.json', json.dumps(dict(configuration=initial_config, resources=baseline_resources, control=control), sort_keys=True))
                fsync_tree(backup)
                if prior_active:
                    need(prop('ActiveState') == initial_state and prop('MainPID') == prior_pid, 'CHANGED')
                    stopped = True
                    service('stop')
                no_live_process()
                need(controls() == control, 'CHANGED')
                baseline_tree = scan(LIVE, program=True)
                need(set(baseline_tree) == set(current) and all(v == current[k] or
                     (k == 'bin/config.json' and {a:b for a, b in v.items() if a != 'sha256'} ==
                      {a:b for a, b in current[k].items() if a != 'sha256'}) for k, v in baseline_tree.items()), 'CHANGED')
                db = db_open(DB)
                snapshot = sqlite3.connect(backup + '/snapshot.db')
                try:
                    deadline = time.monotonic() + 60
                    def backup_progress(status, remaining, total):
                        need(time.monotonic() < deadline, 'BACKUP')
                    db.backup(snapshot, pages=256, progress=backup_progress)
                finally:
                    snapshot.close()
                    db.close()
                baseline_capture = capture(backup + '/snapshot.db')
                need(capture(DB) == baseline_capture, 'CHANGED')
                baseline_config = scan(CONFIG)
                need('x-ui.db-journal' not in baseline_config, 'BACKUP')
                need(configuration(baseline_config) == configuration(initial_config), 'CHANGED')
                need(resources(DB) == baseline_resources, 'CHANGED')
                copy_program(LIVE, backup + '/installation')
                shutil.copytree(CONFIG, backup + '/configuration')
                need(scan(backup + '/installation', program=True) == baseline_tree and scan(backup + '/configuration') == baseline_config, 'BACKUP')
                private_write(backup + '/baseline.json', json.dumps(dict(schema=baseline_capture[0], rows=baseline_capture[1]), sort_keys=True))
                private_write(backup + '/manifest.json', json.dumps(dict(tree=baseline_tree, configuration=baseline_config,
                              control=control, resources=baseline_resources, active=prior_active, source=source_version), sort_keys=True))
                fsync_tree(backup)
                advance('STAGE')
                copy_program(target, STAGE)
                if os.path.lexists(LIVE + '/bin/config.json'):
                    copy_program_file(LIVE + '/bin/config.json', STAGE + '/bin/config.json')
                elif os.path.lexists(STAGE + '/bin/config.json'):
                    os.unlink(STAGE + '/bin/config.json')
                for name in baseline_tree:
                    relative = '' if name == '.' else '/' + name.rstrip('/')
                    if os.path.lexists(STAGE + relative):
                        original = metadata(LIVE + relative, name == '.' or name.endswith('/'), program=True)
                        shutil.copystat(LIVE + relative, STAGE + relative, follow_symlinks=False)
                        os.chown(STAGE + relative, original['uid'], original['gid'], follow_symlinks=False)
                staged_tree = scan(STAGE, program=True)
                need(staged_tree['x-ui']['sha256'] == digest(target + '/x-ui'), 'ARCHIVE')
                fsync_tree(STAGE)
                guard(baseline_tree)
                no_live_process()
                advance('SWAP')
                reason = 'RECOVERY'
                os.rename(LIVE, PARKED)
                swapped = True
                need(scan(PARKED, program=True) == baseline_tree and controls() == control, 'CHANGED')
                config_guard()
                os.rename(STAGE, LIVE)
                fd = os.open('/usr/local', os.O_RDONLY | os.O_DIRECTORY)
                try:
                    os.fsync(fd)
                finally:
                    os.close(fd)
                guard(staged_tree)
                advance('VERIFY')
                reason = 'VERIFY'
                if prior_active:
                    target_started = True
                    service('start')
                    time.sleep(4)
                    running_target()
                    need(controls() == control, 'CHANGED')
                    service('stop')
                    no_live_process()
                    guard(staged_tree)
                    service('start')
                    time.sleep(3)
                    running_target()
                else:
                    no_live_process()
                guard(staged_tree)
                need(scan(PARKED, program=True) == baseline_tree, 'CHANGED')
                need(release_active(), 'CHANGED')
                state('SUCCEEDED', 'COMPLETE', source=source_version, target=target_version, active=prior_active, runtimeVerified=prior_active)
            except Exception as failure:
                known = ('RUNNER', 'UNSUPPORTED', 'SOURCE', 'SERVICE', 'BACKEND', 'SCHEMA', 'ARCHIVE', 'CHANGED', 'VERIFY', 'BACKUP', 'RECOVERY', 'RESOURCES')
                code = str(failure) if str(failure) in known else reason
                recovery_started = False
                if stopped or swapped or target_started:
                    try:
                        need(control is not None and controls() == control, 'CHANGED')
                        if target_started:
                            if prop('ActiveState') != 'inactive':
                                running_target(False)
                                service('stop')
                            no_live_process()
                            code = 'CHANGED' if code == 'CHANGED' else 'VERIFY'
                        else:
                            need(code != 'CHANGED', 'CHANGED')
                            no_live_process()
                            need(baseline_capture is not None and baseline_config is not None, 'RECOVERY')
                            config_guard()
                            if swapped:
                                need(scan(PARKED, program=True) == baseline_tree, 'CHANGED')
                                if os.path.lexists(LIVE):
                                    need(scan(LIVE, program=True) == staged_tree and not os.path.lexists(STAGE), 'CHANGED')
                                    os.rename(LIVE, STAGE)
                                os.rename(PARKED, LIVE)
                            need(scan(LIVE, program=True) == baseline_tree, 'CHANGED')
                            if prior_active:
                                recovery_started = True
                                service('start')
                                time.sleep(3)
                                need(prop('ActiveState') == 'active', 'RECOVERY')
                                pid = prop('MainPID')
                                need(pid.isdigit() and int(pid) > 0 and os.path.realpath('/proc/' + pid + '/exe') == LIVE + '/x-ui', 'RECOVERY')
                                need(digest(LIVE + '/x-ui', program=True) == installed_hash, 'RECOVERY')
                                guard(baseline_tree)
                            if swapped:
                                code = 'RESTORED'
                    except Exception:
                        code = 'RECOVERY'
                        if recovery_started:
                            try:
                                need(controls() == control and digest(LIVE + '/x-ui', program=True) == installed_hash, 'CHANGED')
                                pid = prop('MainPID')
                                need(pid.isdigit() and int(pid) > 0 and os.path.realpath('/proc/' + pid + '/exe') == LIVE + '/x-ui', 'CHANGED')
                                service('stop')
                                no_live_process()
                            except Exception:
                                pass
                safe = not stopped and not swapped and not target_started and release_active()
                state('FAILED', phase, reason=code, safeToRetry=safe)
            finally:
                if armed:
                    signal.alarm(0)
                if lock is not None:
                    os.close(lock)
        """.trimIndent() + "\n"
    }
}
