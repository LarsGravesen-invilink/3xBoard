package app.invilink.xboard.ssh

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.OpenBSDBCrypt
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.UUID

internal class InstallationFailure(message: String) : IllegalStateException(message)

class ThreeXInstaller(private val connection: SshConnection) {
    private val operation = Mutex()
    private var pendingIdentity: SshHostIdentity? = null
    private class InstallerFailure(message: String) : IllegalStateException(message)

    @Volatile
    var pendingJobId: String? = null
        private set

    internal suspend fun install(
        credentials: SshCredentials,
        identity: SshHostIdentity,
        options: InstallOptions,
        onProgress: suspend (InstallProgress) -> Unit,
        release: ThreeXRelease,
    ): InstalledServer = operation.withLock {
        safely(onProgress) {
            check(pendingJobId == null) {
                "Предыдущая команда установки уже отправлена. Откройте новое подключение для повторной проверки сервера."
            }
            val checked = credentials.validate()
            val selected = options.validate()
            require(selected.operation == InstallOperation.FRESH) { "Для существующей панели используйте отдельное обслуживание с сохранением данных." }
            progress(onProgress, "Проверка ключа SSH и доступа к серверу.")
            val verified = connect(checked, identity)
            if (selected.sslMode == SslMode.IP && selected.publicIp != connection.connectedServerAddress) {
                throw InstallerFailure("IP SSH-сервера изменился. Повторите подключение и подтвердите адрес сертификата.")
            }
            progress(onProgress, "Проверка root, ОС, наличия 3x-UI; занятые порты — только предупреждения.", 3)
            val report = connection.preflight(selected.panelPort.toInt(), needsHttpChallenge = true)
            if (!report.ready) throw InstallerFailure(report.conflicts.joinToString("\n").ifBlank { "Не подтверждены root-доступ или поддерживаемая ОС. Установка не запускалась." })
            if (report.existingInstallation) throw InstallerFailure("Обнаружена существующая 3x-UI. Выберите обновление или переустановку с сохранением данных; новая установка не запускалась.")
            if (report.warnings.isNotEmpty()) progress(onProgress, "Предупреждения не блокируют продолжение: " + report.warnings.joinToString(" "))
            checkedCommand(assignments(
                "XBOARD_TARGET" to certificateTarget(selected),
                "XUI_SSL_MODE" to if (selected.sslMode == SslMode.DOMAIN) "domain" else "ip",
            ) + release.applyTo(EXTRA_PREFLIGHT))
            val hash = passwordHash(selected.panelPassword)
            val jobId = UUID.randomUUID().toString()
            val job = "$JOB_ROOT/$jobId"
            val runner = assignments(
                "job" to job,
                "XUI_USERNAME" to selected.panelUsername,
                "XUI_PASSWORD" to selected.panelPassword,
                "XUI_PANEL_PORT" to selected.panelPort,
                "XUI_WEB_BASE_PATH" to selected.secretPath,
                "XUI_SSL_MODE" to if (selected.sslMode == SslMode.DOMAIN) "domain" else "ip",
                "XUI_DOMAIN" to selected.domain,
                "XUI_SERVER_IP" to connection.connectedServerAddress.orEmpty(),
                "XUI_ACME_EMAIL" to selected.acmeEmail,
                "XBOARD_TARGET" to certificateTarget(selected),
                "XBOARD_PASSWORD_HASH" to hash,
            ) + release.applyTo(RUNNER)
            val delimiter = "XBOARD_${jobId.replace("-", "")}_RUNNER"
            val launch = assignments("job" to job) + LAUNCH_PREFIX +
                "cat > \"\$job/runner\" <<'$delimiter'\n" + runner + "$delimiter\n" + LAUNCH_SUFFIX
            pendingIdentity = verified
            pendingJobId = jobId
            progress(onProgress, "Отправка подтверждённой установки в отдельную задачу сервера.")
            val started = input(launch, timeoutMillis = 30_000)
            check(started.exitCode == 0 && started.stderr.isBlank() && started.stdout == "STARTED\n") {
                "Запуск задачи не подтверждён. Повторная команда установки не отправлялась."
            }
            awaitResult(checked, verified, selected, jobId, onProgress)
        }
    }

    suspend fun resumeInstall(
        credentials: SshCredentials,
        identity: SshHostIdentity,
        options: InstallOptions,
        onProgress: suspend (InstallProgress) -> Unit,
    ): InstalledServer = operation.withLock {
        safely(onProgress) {
            val jobId = checkNotNull(pendingJobId) { "Нет ранее отправленной задачи установки." }
            val original = checkNotNull(pendingIdentity) { "Не сохранён проверенный ключ задачи." }
            val checked = credentials.validate()
            val selected = options.validate()
            require(sameIdentity(original, identity)) { "Задача принадлежит другому серверу SSH." }
            val verified = connect(checked, identity)
            progress(onProgress, "Проверяем результат ранее отправленной установки.")
            awaitResult(checked, verified, selected, jobId, onProgress, verifyAgain = true)
        }
    }

    suspend fun serverAction(action: ServerAction): String = operation.withLock {
        if (action == ServerAction.SHOW_PANEL_SETTINGS) {
            return@withLock "Параметры основной панели следует показывать из сохранённого InstalledServer в интерфейсе приложения. SSH-команды и перевыпуск токена не выполнялись."
        }
        try {
            check(pendingJobId == null) { "Дождитесь завершения отправленной задачи установки." }
            val result = input(
                when (action) {
                    ServerAction.REBOOT_SERVER -> REBOOT
                    ServerAction.RESTART_XRAY -> RELOAD_XRAY
                    ServerAction.SHOW_PANEL_SETTINGS -> error("Недоступное действие.")
                },
                timeoutMillis = 30_000,
            )
            check(result.exitCode == 0 && result.stderr.isBlank() && result.stdout == "ACCEPTED\n")
            when (action) {
                ServerAction.REBOOT_SERVER -> "systemd принял запрос перезагрузки. Фактическое завершение перезагрузки ещё не подтверждено."
                ServerAction.RESTART_XRAY -> "Выполнен systemctl reload x-ui для перезапуска Xray; служба панели активна. Токен не изменён."
                ServerAction.SHOW_PANEL_SETTINGS -> error("Недоступное действие.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw IllegalStateException("Действие не подтверждено. Проверьте SSH, root-доступ и службу x-ui. Команда автоматически не повторялась; токен не перевыпускался.")
        }
    }

    private suspend fun connect(credentials: SshCredentials, expected: SshHostIdentity): SshHostIdentity {
        require(credentials.host == expected.host && credentials.port.toInt() == expected.port) {
            "Адрес SSH не совпадает с подтверждённым ключом."
        }
        val actual = connection.connect(credentials)
        if (!sameIdentity(actual, expected)) {
            connection.close()
            error("Ключ SSH не совпадает с подтверждённым ключом.")
        }
        return actual
    }

    private suspend fun awaitResult(
        credentials: SshCredentials,
        identity: SshHostIdentity,
        options: InstallOptions,
        jobId: String,
        onProgress: suspend (InstallProgress) -> Unit,
        verifyAgain: Boolean = false,
    ): InstalledServer {
        require(Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(jobId))
        var previous = ""
        repeat(3600) {
            val response = pollWithReconnect(credentials, identity, jobId, onProgress, PHASE_PERCENT[previous] ?: 3)
            check(response.exitCode == 0 && response.stderr.isBlank()) {
                "Не удалось прочитать защищённое состояние задачи. Установка не повторялась."
            }
            val lines = response.stdout.split('\n')
            check(lines.size >= 3 && lines.last().isEmpty())
            val status = lines[0].removePrefix("status\t")
            val phase = lines[1].removePrefix("phase\t")
            check(lines[0] == "status\t$status" && lines[1] == "phase\t$phase")
            val message = STAGES[phase] ?: error("Неизвестный этап установки.")
            when (status) {
                "RUNNING" -> {
                    check(lines.size == 3)
                    if (phase != previous) {
                        progress(onProgress, message, PHASE_PERCENT.getValue(phase))
                        previous = phase
                    }
                }
                "FAILED" -> {
                    val detail = if (lines == listOf("status\tFAILED", "phase\t$phase", "error\tCERTIFICATE_INVALID", "")) {
                        "Сохранённый SSL-сертификат, SAN, срок действия (>24 ч), ключ, права или пути ACME не прошли проверку. Файлы не перезаписывались; исправьте SSL отдельно."
                    } else {
                        check(lines.size == 3)
                        when (phase) {
                            "ARCHIVE_VERIFIED", "SCRIPT_DOWNLOAD" -> "Проверьте доступ сервера к GitHub и загрузку управляющего скрипта; повторного запуска не было."
                            "EXTRACT" -> "Не удалось завершить распаковку архива или подготовку файлов панели; повторного запуска не было."
                            else -> "Автоматического повтора или удаления прежних данных нет."
                        }
                    }
                    throw InstallerFailure("Задача установки остановлена на этапе: $message $detail")
                }
                "SUCCEEDED" -> {
                    check(phase == "COMPLETE")
                    val installed = decodeResult(lines.drop(2), credentials, identity, options, jobId)
                    if (verifyAgain) {
                        progress(onProgress, "Повторная проверка действующего TLS, пароля и API-токена без переустановки.")
                        recheck(installed, jobId)
                    }
                    onProgress(InstallProgress(percent = 100, message = "Установка, TLS, пароль и API-токен проверены. $API_LIMITATIONS", running = false))
                    pendingJobId = null
                    pendingIdentity = null
                    return installed
                }
                else -> error("Некорректный статус задачи.")
            }
            delay(2_000)
        }
        throw InstallerFailure("Истекло время ожидания результата. Откройте новое подключение для повторной проверки сервера.")
    }

    private suspend fun pollWithReconnect(
        credentials: SshCredentials,
        identity: SshHostIdentity,
        jobId: String,
        onProgress: suspend (InstallProgress) -> Unit,
        percent: Int,
    ): SshCommandResult {
        var lastFailure: Exception? = null
        repeat(4) { attempt ->
            try {
                if (attempt > 0) {
                    progress(onProgress, "Восстановление SSH и проверка уже запущенной установки; повторного запуска нет.", percent)
                    delay(attempt * 2_000L)
                    connect(credentials, identity)
                }
                return input(assignments("job" to "$JOB_ROOT/$jobId") + POLL, maxBytes = 32_768)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SshConnectionException) {
                if (failure.reason in setOf(SshFailureReason.HOST_CHANGED, SshFailureReason.HOST_ADDRESS,
                        SshFailureReason.HOST_STORAGE, SshFailureReason.AUTHENTICATION,
                        SshFailureReason.UNSUPPORTED_AUTH_CHALLENGE, SshFailureReason.PRIVATE_KEY)) throw failure
                lastFailure = failure
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        throw InstallerFailure("Не удалось восстановить SSH для проверки результата. Повторная установка не запускалась. ${lastFailure?.let { "Проверьте SSH-соединение." }.orEmpty()}")
    }

    private suspend fun recheck(server: InstalledServer, jobId: String) {
        val script = assignments(
            "job" to "$JOB_ROOT/$jobId",
            "XBOARD_JOB" to "$JOB_ROOT/$jobId",
            "XBOARD_TARGET" to server.certificateTarget,
            "XBOARD_RECHECK" to "1",
            "XUI_SSL_MODE" to if (server.sslMode == SslMode.DOMAIN) "domain" else "ip",
            "XUI_USERNAME" to server.panelUsername,
            "XUI_PASSWORD" to server.panelPassword,
            "XUI_PANEL_PORT" to server.panelPort.toString(),
            "XUI_WEB_BASE_PATH" to server.secretPath,
            "XUI_ACCESS_URL" to server.panelUrl,
            "XUI_API_TOKEN" to server.token,
            "XUI_DB_TYPE" to "sqlite",
        ) + RECHECK_PREFIX + VERIFY_AND_EXPORT + "printf 'VERIFIED\\n'\n"
        val result = input(script, timeoutMillis = 240_000)
        check(result.exitCode == 0 && result.stderr.isBlank() && result.stdout == "VERIFIED\n") {
            "Текущие TLS, служба или учётные данные не прошли проверку. Переустановка не выполнялась."
        }
    }

    private fun decodeResult(
        lines: List<String>,
        credentials: SshCredentials,
        identity: SshHostIdentity,
        options: InstallOptions,
        jobId: String,
    ): InstalledServer {
        check(lines.size == RESULT_KEYS.size + 3 && lines.first() == "XBOARD_RESULT_V1" &&
            lines[lines.lastIndex - 1] == "END" && lines.last().isEmpty())
        val fields = linkedMapOf<String, String>()
        for (line in lines.drop(1).dropLast(2)) {
            val parts = line.split('\t')
            check(parts.size == 2 && parts[0] in RESULT_KEYS && parts[1].length <= 4096)
            check(Regex("[A-Za-z0-9+/]*={0,2}").matches(parts[1]))
            val bytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val value = try {
                check(Base64.encodeToString(bytes, Base64.NO_WRAP) == parts[1])
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } finally {
                bytes.fill(0)
            }
            check(value.length <= 2048 && value.none { it.isISOControl() })
            check(fields.put(parts[0], value) == null)
        }
        check(fields.keys == RESULT_KEYS)
        val username = fields.getValue("XUI_USERNAME")
        val password = fields.getValue("XUI_PASSWORD")
        val port = fields.getValue("XUI_PANEL_PORT")
        val path = fields.getValue("XUI_WEB_BASE_PATH")
        val token = fields.getValue("XUI_API_TOKEN")
        val target = certificateTarget(options)
        val url = "https://$target:${options.panelPort}/${options.secretPath}"
        check(username == options.panelUsername && password == options.panelPassword && port == options.panelPort)
        check(path == options.secretPath && fields.getValue("XUI_ACCESS_URL") in setOf(url, "$url/"))
        check(fields.getValue("XUI_DB_TYPE") == "sqlite" && Regex("[A-Za-z0-9_-]{20,512}").matches(token))
        return InstalledServer(
            panelId = jobId,
            sshHost = credentials.host,
            sshPort = credentials.port.toInt(),
            sshUsername = credentials.username,
            hostKeyAlgorithm = identity.algorithm,
            hostKeyBase64 = identity.keyBase64,
            panelUsername = username,
            panelPassword = password,
            panelPort = port.toInt(),
            panelUrl = "$url/",
            token = token,
            secretPath = path,
            sslMode = options.sslMode,
            certificateTarget = target,
            credentials = credentials.takeIf { options.rememberSsh },
        )
    }

    private suspend fun <T> safely(
        onProgress: suspend (InstallProgress) -> Unit,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val job = pendingJobId
        val message = if (failure is InstallerFailure) {
            "${failure.message.orEmpty()}${job?.let { " Идентификатор задачи: $it." }.orEmpty()}"
        } else if (job == null) {
            "Установка не подтверждена. Проверьте параметры, ключ SSH, root-доступ и поддержку ОС."
        } else {
            "Задача $job не подтверждена или завершилась ошибкой. Откройте новое подключение для проверки сервера. Ранее отправленная команда могла продолжить работу на VPS."
        }
        try {
            onProgress(InstallProgress(message = message, running = false, error = message))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Unit
        }
        throw InstallationFailure(message)
    }

    private suspend fun progress(callback: suspend (InstallProgress) -> Unit, message: String, percent: Int = 3) {
        callback(InstallProgress(percent = percent, message = message, running = true))
    }

    private suspend fun checkedCommand(script: String) {
        val result = input(script, timeoutMillis = 120_000)
        if (result.exitCode != 0 && result.stderr.startsWith("LEGACY_REMNANTS:")) {
            val code = result.stderr.substringAfter("LEGACY_REMNANTS:").substringBefore(':')
            val reason = when (code) {
                "RECEIPT", "LEGACY_RECEIPT" -> "Нет проверяемой приватной квитанции завершённого удаления XBOARD_NATIVE_REMOVE_V2. Маркера REMOVED без подтверждённого старого формата недостаточно."
                "ENVIRONMENT" -> "В файле окружения есть неизвестные настройки; допустимы только пустой файл, комментарии и XUI_DB_TYPE=sqlite."
                "ARCHIVE" -> "Оставшийся архив не совпадает с проверенным SHA-256 выбранного релиза."
                "SERVICE", "CORE", "PROCESS" -> "Не подтверждено отсутствие файлов, загруженной службы или процессов прежней панели."
                "BUSY" -> "История задач или активная задача не подтверждены как завершённые."
                "CERTIFICATE" -> "В каталоге логов найдены SSL-материалы; автоматическая очистка запрещена."
                else -> "Не подтверждены безопасные владельцы, пути, отсутствие монтирований или неизменность остатков."
            }
            throw InstallerFailure("$reason Проверьте квитанции в /root/.xboard-install и остатки /etc/default/x-ui, /etc/sysconfig/x-ui, /var/log/x-ui, /usr/local/x-ui-linux-{amd64,arm64}.tar.gz вручную. SSL и история не удалялись; обслуживание отсутствующей панели не требуется. [$code]")
        }
        if (result.exitCode != 0 && result.stderr.startsWith("CERTIFICATE_INVALID:")) {
            throw InstallerFailure("Сохранённый SSL-сертификат, SAN, срок действия (>24 ч), ключ, права или пути ACME не прошли проверку. SSL-файлы не изменялись; исправьте их отдельно.")
        }
        check(result.exitCode == 0 && result.stdout == "CLEAN\n" && result.stderr.isBlank()) {
            "Обнаружены небезопасные SSL-пути, прежние файлы панели или незавершённые задачи. Автоматическая перезапись запрещена."
        }
    }

    private suspend fun input(
        script: String,
        timeoutMillis: Long = 30_000,
        maxBytes: Int = 4096,
    ): SshCommandResult {
        val bytes = script.toByteArray(Charsets.UTF_8)
        return try {
            connection.executeInput(SSH_BASH, bytes, timeoutMillis, maxBytes)
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun passwordHash(password: String): String = withContext(Dispatchers.Default) {
        val chars = password.toCharArray()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        try {
            OpenBSDBCrypt.generate("2a", chars, salt, 10)
        } finally {
            chars.fill('\u0000')
            salt.fill(0)
        }
    }

    private fun assignments(vararg values: Pair<String, String>): String =
        values.joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key=${shellQuote(value)}" }

    private fun certificateTarget(options: InstallOptions): String =
        options.certificateTarget

    private fun sameIdentity(first: SshHostIdentity, second: SshHostIdentity): Boolean =
        first.host == second.host && first.port == second.port

    companion object {
        const val API_LIMITATIONS = "REST API не заменяет SSH для перезагрузки ОС и systemctl reload x-ui. Внешняя доступность, DNS и межсетевой экран не подтверждены; IP-сертификат требует регулярного ACME-продления."
        private const val JOB_ROOT = "/root/.xboard-install"
        private const val SSH_BASH = "env -i HOME=/root LC_ALL=C PATH=/usr/sbin:/usr/bin:/sbin:/bin /bin/bash --noprofile --norc -s"
        private val RESULT_KEYS = linkedSetOf(
            "XUI_USERNAME", "XUI_PASSWORD", "XUI_PANEL_PORT", "XUI_WEB_BASE_PATH",
            "XUI_ACCESS_URL", "XUI_API_TOKEN", "XUI_DB_TYPE",
        )
        private val PHASE_PERCENT = mapOf(
            "QUEUED" to 3, "BOOTSTRAP" to 8, "DOWNLOAD" to 18, "PATCH" to 25,
            "INSTALL" to 30, "ARCHIVE_VERIFIED" to 42, "SCRIPT_DOWNLOAD" to 45, "EXTRACT" to 50, "CONFIGURE" to 58,
            "ACME" to 70, "RESULT" to 85, "TLS" to 90, "API" to 95, "COMPLETE" to 99,
        )
        private val STAGES = mapOf(
            "QUEUED" to "Задача принята сервером.",
            "BOOTSTRAP" to "Установка необходимых пакетов через apt.",
            "DOWNLOAD" to "Загрузка закреплённого официального installer по HTTPS.",
            "PATCH" to "Подготовка передачи параметров и повторного использования SSL.",
            "INSTALL" to "Запущен официальный установщик выбранной версии 3x-UI.",
            "ARCHIVE_VERIFIED" to "SHA-256 архива релиза совпал с закреплённым значением.",
            "SCRIPT_DOWNLOAD" to "Загрузка и проверка управляющего скрипта 3x-UI.",
            "EXTRACT" to "Распаковка проверенного архива 3x-UI.",
            "CONFIGURE" to "Настройка новой панели без передачи пароля в аргументах процессов.",
            "ACME" to "Проверка и повторное использование TLS либо выпуск ACME для выбранного домена или IP.",
            "RESULT" to "Проверка свежести и прав файла результата.",
            "TLS" to "Проверка файлов сертификата, ключа и службы x-ui.",
            "API" to "Проверка HTTPS, пароля и Bearer-токена через loopback.",
            "COMPLETE" to "Проверки завершены.",
        )

        private fun script(value: String): String = value.trimIndent().replace('§', '$') + "\n"

        private val JOB_GUARDS = script("""
            private_dir() {
                [[ -d "§1" && ! -L "§1" && §(stat -c '%u:%g:%a' "§1") == 0:0:700 ]]
            }
            private_file() {
                [[ -f "§1" && ! -L "§1" && §(stat -c '%u:%g:%a:%h' "§1") == 0:0:600:1 ]]
            }
            terminal_history() {
                local directory status cmdline
                shopt -s nullglob dotglob
                for directory in /root/.xboard-install/*; do
                    [[ §directory == /root/.xboard-install/active ]] && continue
                    [[ §{directory##*/} =~ ^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}§ ]] || return 1
                    private_dir "§directory" || return 1
                    private_file "§directory/status" || return 1
                    status=§(cat "§directory/status")
                    [[ §status == SUCCEEDED || §status == FAILED ]] || return 1
                done
                for cmdline in /proc/[0-9]*/cmdline; do
                    if grep -zExq '/root/\.xboard-install/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/runner' "§cmdline" 2>/dev/null; then return 1; fi
                done
                shopt -u nullglob dotglob
            }
            check_history() {
                local active=/root/.xboard-install/active
                if [[ -e /root/.xboard-install || -L /root/.xboard-install ]]; then
                    private_dir /root/.xboard-install || return 1
                    exec 9</root/.xboard-install
                    flock -n 9 || return 1
                    terminal_history || return 1
                    if [[ -e §active || -L §active ]]; then
                        private_dir "§active" || return 1
                        [[ -z §(find "§active" -mindepth 1 -maxdepth 1 -print -quit) ]] || return 1
                        rmdir -- "§active" || return 1
                    fi
                fi
            }
        """)

        private val CORE_GUARDS = script("""
            require_core_absent() {
                local p
                for p in /usr/local/x-ui /etc/x-ui /usr/bin/x-ui \
                    /etc/systemd/system/x-ui.service /lib/systemd/system/x-ui.service /usr/lib/systemd/system/x-ui.service \
                    /run/systemd/system/x-ui.service /run/systemd/system/x-ui.service.d /etc/systemd/system/x-ui.service.d \
                    /lib/systemd/system/x-ui.service.d /usr/lib/systemd/system/x-ui.service.d \
                    /etc/systemd/system/multi-user.target.wants/x-ui.service; do
                    [[ ! -e "§p" && ! -L "§p" ]] || return 1
                done
                for p in /root/cert /root/.acme.sh; do
                    if [[ -e "§p" || -L "§p" ]]; then private_dir "§p" || return 1; fi
                done
            }
            require_clean() {
                local p
                require_core_absent || return 1
                for p in /etc/default/x-ui /etc/sysconfig/x-ui /var/log/x-ui \
                    /usr/local/x-ui-linux-amd64.tar.gz /usr/local/x-ui-linux-arm64.tar.gz; do
                    [[ ! -e "§p" && ! -L "§p" ]] || return 1
                done
            }
        """)

        private val EXTRA_PREFLIGHT: String by lazy {
            JOB_GUARDS + CORE_GUARDS + script("""
            set -euo pipefail
            [[ §(id -u) == 0 && §(id -g) == 0 ]]
            for p in /root /usr /usr/bin /etc /etc/systemd /etc/systemd/system /usr/local; do
                [[ -d "§p" && ! -L "§p" && §(stat -c %u "§p") == 0 ]]
                (( (8#§(stat -c %a "§p") & 0022) == 0 ))
            done
            for p in /usr/local/x-ui /etc/x-ui \
                /etc/systemd/system/x-ui.service /lib/systemd/system/x-ui.service /usr/lib/systemd/system/x-ui.service \
                /run/systemd/system/x-ui.service /etc/systemd/system/multi-user.target.wants/x-ui.service; do
                [[ ! -e "§p" && ! -L "§p" ]] || exit 1
            done
            for tool in apt-get bash sha256sum stat nohup setsid base64 grep find flock python3; do command -v "§tool" >/dev/null; done
            if ! check_history; then
                printf 'LEGACY_REMNANTS:BUSY: verify terminal job history and active claim in /root/.xboard-install\n' >&2
                exit 1
            fi
            if command -v python3 >/dev/null && command -v openssl >/dev/null; then
                export XBOARD_TARGET XUI_SSL_MODE
                python3 - <<'XBOARD_PREFLIGHT_CERTIFICATE' >/dev/null
            """) + CERTIFICATE_CHECK + script("""
            XBOARD_PREFLIGHT_CERTIFICATE
            fi
            python3 -I - <<'XBOARD_PRESERVE_REMNANTS'
            import os, re, stat, subprocess, uuid
            result = subprocess.run(['/usr/bin/systemctl', 'show', 'x-ui.service', '--property=LoadState,ActiveState', '--all'],
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=10)
            if result.returncode not in (0, 1) or set(result.stdout.splitlines()) != {'LoadState=not-found', 'ActiveState=inactive'}:
                raise SystemExit('3x-UI service absence is not confirmed')
            roots = ('/usr/bin/x-ui', '/etc/default/x-ui', '/etc/sysconfig/x-ui', '/var/log/x-ui',
                '/usr/local/x-ui-linux-amd64.tar.gz', '/usr/local/x-ui-linux-arm64.tar.gz',
                '/etc/systemd/system/x-ui.service.d', '/run/systemd/system/x-ui.service.d',
                '/lib/systemd/system/x-ui.service.d', '/usr/lib/systemd/system/x-ui.service.d')
            with open('/proc/self/mountinfo', encoding='utf-8') as source:
                mounts = [re.sub(r'\\([0-7]{3})', lambda m: chr(int(m[1], 8)), line.split()[4]) for line in source]
            pending = []
            seen = set()
            for path in roots:
                if not os.path.lexists(path): continue
                if path == '/lib/systemd/system/x-ui.service.d' and os.path.realpath('/lib') == '/usr/lib':
                    path = '/usr/lib/systemd/system/x-ui.service.d'
                if path in seen: continue
                seen.add(path)
                parent = os.path.dirname(path)
                info = os.lstat(path)
                parent_info = os.stat(parent)
                if (os.path.realpath(parent) != parent or parent_info.st_uid != 0 or parent_info.st_mode & 0o022 or
                    info.st_uid != 0 or stat.S_ISLNK(info.st_mode) or
                    not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)) or
                    any(m == path or m.startswith(path + '/') for m in mounts)):
                    raise SystemExit('Unsafe installation remnant: ' + path)
                pending.append(path)
            for path in pending:
                destination = os.path.join(os.path.dirname(path), '.xboard-preserved-' + uuid.uuid4().hex + '-' + os.path.basename(path))
                if os.path.lexists(destination): raise SystemExit('Remnant backup already exists')
                os.rename(path, destination)
            XBOARD_PRESERVE_REMNANTS
            require_core_absent
            metadata=0
            for p in /etc/default/x-ui /etc/sysconfig/x-ui /var/log/x-ui /usr/local/x-ui-linux-amd64.tar.gz /usr/local/x-ui-linux-arm64.tar.gz; do
                if [[ -e "§p" || -L "§p" ]]; then metadata=1; fi
            done
            if [[ §metadata == 1 ]]; then
                if ! command -v python3 >/dev/null; then
                    printf 'LEGACY_REMNANTS:TOOLS: python3 is required to verify completed-uninstall remnants\n' >&2
                    exit 1
                fi
                python3 -I - <<'XBOARD_LEGACY_REMNANTS'
            """) + LEGACY_REMNANT_CLEANUP + script("""
            XBOARD_LEGACY_REMNANTS
            fi
            require_clean
            printf 'CLEAN\n'
            """)
        }

        private val LAUNCH_PREFIX = JOB_GUARDS + CORE_GUARDS + script("""
            set -euo pipefail
            umask 077
            [[ §(id -u) == 0 ]]
            [[ §job =~ ^/root/\.xboard-install/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}§ ]]
            require_clean
            if [[ ! -e /root/.xboard-install && ! -L /root/.xboard-install ]]; then
                mkdir -m 700 /root/.xboard-install
            fi
            private_dir /root/.xboard-install
            check_history
            mkdir -m 700 /root/.xboard-install/active
            printf '%s\n' "§job" > /root/.xboard-install/active/job
            mkdir -m 700 "§job"
            printf 'QUEUED\n' > "§job/phase"
            printf 'RUNNING\n' > "§job/status"
            : > "§job/result"
        """)

        private val LAUNCH_SUFFIX = script("""
            chmod 600 "§job/runner" "§job/phase" "§job/status" "§job/result"
            nohup setsid env -i HOME=/root LC_ALL=C PATH=/usr/sbin:/usr/bin:/sbin:/bin /bin/bash --noprofile --norc "§job/runner" </dev/null >/dev/null 2>&1 &
            runner_pid=§!
            printf '%s\n' "§runner_pid" > /root/.xboard-install/active/pid.launch
            mv /root/.xboard-install/active/pid.launch /root/.xboard-install/active/pid
            disown
            printf 'STARTED\n'
        """)

        private val LEGACY_REMNANT_CLEANUP = script("""
            import fcntl, hashlib, json, os, re, signal, stat, subprocess, sys
            ROOT = '/root/.xboard-install'
            ENV = ('/etc/default/x-ui', '/etc/sysconfig/x-ui')
            LOG = '/var/log/x-ui'
            ARCHIVES = {
                '/usr/local/x-ui-linux-amd64.tar.gz': '0ddfa4702f595442e2962acd81addc768fdacaa4ff2672c971a0463dae744916',
                '/usr/local/x-ui-linux-arm64.tar.gz': 'f50b4c30fd919d018f2f4c3d8db53a4ca027503d581328727770afb116a79d90',
            }
            CORE = ('/usr/local/x-ui', '/etc/x-ui', '/usr/bin/x-ui', '/etc/xui', '/etc/3x-ui', '/etc/3xui',
                    '/usr/local/3x-ui', '/usr/local/xui', '/etc/conf.d/x-ui',
                    '/etc/systemd/system/multi-user.target.wants/x-ui.service') + tuple(
                parent + '/x-ui.service' + suffix for parent in
                ('/etc/systemd/system', '/run/systemd/system', '/lib/systemd/system', '/usr/lib/systemd/system')
                for suffix in ('', '.d'))
            UUID = r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}'
            PROOF = {}
            class Refused(Exception):
                pass
            def need(value, code):
                if not value:
                    raise Refused(code)
            def signature(s):
                return (s.st_dev, s.st_ino, s.st_mode, s.st_uid, s.st_gid, s.st_nlink, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
            def inspect(path, directory=False, private=False, code='PARENT'):
                s = os.lstat(path)
                need(os.path.realpath(path) == path and s.st_uid == 0 and s.st_gid == 0 and not s.st_mode & 0o022, code)
                need(stat.S_ISDIR(s.st_mode) if directory else stat.S_ISREG(s.st_mode) and s.st_nlink == 1, code)
                if private:
                    need(stat.S_IMODE(s.st_mode) == (0o700 if directory else 0o600), code)
                return s
            def read(path, limit=65536):
                need(os.path.lexists(path), 'RECEIPT')
                before = signature(inspect(path, private=True, code='RECEIPT'))
                fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
                with os.fdopen(fd, 'rb') as f:
                    need(signature(os.fstat(f.fileno())) == before, 'CHANGED')
                    data = f.read(limit + 1)
                need(len(data) <= limit and signature(os.lstat(path)) == before, 'RECEIPT')
                PROOF[path] = before
                return data
            def mounts():
                with open('/proc/self/mountinfo') as f:
                    return [re.sub(r'\\([0-7]{3})', lambda m: chr(int(m[1], 8)), row.split()[4]) for row in f]
            def under(path, root):
                return path == root or path.startswith(root + '/')
            def unmounted(path):
                need(not any(under(m, path) for m in mounts()), 'MOUNT')
            def parents():
                for path in ('/', '/root', '/usr', '/usr/bin', '/usr/local', '/etc', '/etc/systemd', '/etc/systemd/system', '/var', '/var/log'):
                    inspect(path, directory=True)
                for path in ('/etc/default', '/etc/sysconfig'):
                    if os.path.lexists(path):
                        inspect(path, directory=True)
            def boundary():
                parents()
                need(os.path.lexists(ROOT), 'RECEIPT')
                s = inspect(ROOT, directory=True, private=True, code='RECEIPT')
                need(signature(os.fstat(9))[:2] == signature(s)[:2], 'BUSY')
                fcntl.flock(9, fcntl.LOCK_EX | fcntl.LOCK_NB)
                unmounted(ROOT)
                need(not os.path.lexists(ROOT + '/active'), 'BUSY')
                need(not os.path.lexists('/root/.xboard-maintenance/active'), 'BUSY')
                need(not any(os.path.lexists(p) for p in CORE), 'CORE')
            def receipt():
                authorized = False
                legacy = False
                for name in os.listdir(ROOT):
                    need(re.fullmatch(UUID, name), 'BUSY')
                    job = ROOT + '/' + name
                    inspect(job, directory=True, private=True, code='RECEIPT')
                    status = read(job + '/status')
                    need(status in (b'SUCCEEDED\n', b'FAILED\n'), 'BUSY')
                    marker = job + '/uninstall-complete'
                    if not os.path.lexists(marker):
                        continue
                    data = read(marker)
                    if data == b'REMOVED\n':
                        legacy = True
                        continue
                    try:
                        value = json.loads(data)
                        need(isinstance(value, dict) and set(value) == {'version', 'panelId', 'target'}, 'RECEIPT')
                        need(value['version'] == 'XBOARD_NATIVE_REMOVE_V2' and value['panelId'] == name and
                             isinstance(value['target'], str) and re.fullmatch(r'[0-9a-f]{64}', value['target']), 'RECEIPT')
                        need(status == b'SUCCEEDED\n' and read(job + '/phase') == b'COMPLETE\n', 'RECEIPT')
                        need(not os.path.lexists(job + '/runner'), 'BUSY')
                        started = job + '/uninstall-started'
                        if os.path.lexists(started):
                            initial = json.loads(read(started))
                            need(isinstance(initial, dict) and set(initial) == {'version', 'panelId', 'target', 'roots', 'controls'} and
                                 all(initial[k] == value[k] for k in value), 'RECEIPT')
                            roots, controls = initial['roots'], initial['controls']
                            need(isinstance(roots, dict) and set(roots) in
                                 ({'/usr/local/x-ui', '/etc/x-ui'}, {'/usr/local/x-ui', '/etc/x-ui', '/etc/systemd/system/x-ui.service.d'}), 'RECEIPT')
                            need(all(isinstance(v, list) and len(v) == 2 and all(type(n) == int and n >= 0 for n in v) for v in roots.values()), 'RECEIPT')
                            need(isinstance(controls, dict) and set(controls) in
                                 ({'/usr/bin/x-ui', '/etc/systemd/system/x-ui.service'},
                                  {'/usr/bin/x-ui', '/etc/systemd/system/x-ui.service', '/etc/systemd/system/x-ui.service.d/10-xui-sandbox.conf'}), 'RECEIPT')
                            need(all(isinstance(v, str) and re.fullmatch(r'[0-9a-f]{64}', v) for v in controls.values()), 'RECEIPT')
                        authorized = True
                    except Refused:
                        raise
                    except Exception:
                        raise Refused('RECEIPT')
                need(authorized, 'LEGACY_RECEIPT' if legacy else 'RECEIPT')
            def absent():
                boundary()
                for path, before in PROOF.items():
                    need(signature(inspect(path, private=True, code='RECEIPT')) == before, 'CHANGED')
                result = subprocess.run(['/usr/bin/systemctl', 'show', '--all', 'x-ui.service',
                    '--property=LoadState,ActiveState,FragmentPath,DropInPaths'], stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10)
                rows = result.stdout.decode('utf-8').splitlines()
                need(result.returncode in (0, 1) and len(rows) == 4 and set(rows) ==
                     {'LoadState=not-found', 'ActiveState=inactive', 'FragmentPath=', 'DropInPaths='}, 'SERVICE')
                protected = ('/usr/local/x-ui', '/etc/x-ui', '/usr/bin/x-ui') + ENV + (LOG,) + tuple(ARCHIVES)
                for pid in os.listdir('/proc'):
                    if not pid.isdigit():
                        continue
                    try:
                        exe = os.readlink('/proc/' + pid + '/exe')
                        if exe.endswith(' (deleted)'):
                            exe = exe[:-10]
                        need(not any(under(exe, root) for root in protected), 'PROCESS')
                        with open('/proc/' + pid + '/cmdline', 'rb') as f:
                            command = f.read(1048577)
                        need(len(command) <= 1048576, 'PROCESS')
                        args = command.decode('utf-8', errors='replace').split('\0')
                        need(not any(any(under(arg, root) for root in protected) or re.fullmatch(
                             re.escape(ROOT) + '/' + UUID + r'/(?:runner|install\.sh|verified\.sh|certificate-check\.py)', arg) for arg in args), 'PROCESS')
                        with open('/proc/' + pid + '/cgroup') as f:
                            need(not any(under(row.rstrip().split(':', 2)[2], '/system.slice/x-ui.service') for row in f), 'PROCESS')
                    except (FileNotFoundError, ProcessLookupError):
                        continue
            def log_tree():
                records = {}
                unmounted(LOG)
                top = inspect(LOG, directory=True, code='LOG_TREE')
                for parent, dirs, files in os.walk(LOG, followlinks=False):
                    s = inspect(parent, directory=True, code='LOG_TREE')
                    need(s.st_dev == top.st_dev, 'MOUNT')
                    records[parent] = signature(s)
                    for name in dirs + files:
                        need(not name.lower().endswith(('.pem', '.key', '.crt', '.cer', '.p12', '.pfx')) and
                             name.lower() not in ('cert', 'certs', 'certificate', 'certificates', '.acme.sh'), 'CERTIFICATE')
                        path = parent + '/' + name
                        s = inspect(path, directory=name in dirs, code='LOG_TREE')
                        need(s.st_dev == top.st_dev, 'MOUNT')
                        if name in files:
                            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
                            with os.fdopen(fd, 'rb') as f:
                                need(signature(os.fstat(f.fileno())) == signature(s), 'CHANGED')
                                tail = b''
                                for block in iter(lambda: f.read(1048576), b''):
                                    data = tail + block
                                    need(not re.search(br'-----BEGIN (?:[A-Z ]*PRIVATE KEY|CERTIFICATE)-----', data), 'CERTIFICATE')
                                    tail = data[-128:]
                            records[path] = signature(s)
                return records
            def plan():
                result = {}
                for path in ENV + (LOG,) + tuple(ARCHIVES):
                    if not os.path.lexists(path):
                        continue
                    unmounted(path)
                    if path == LOG:
                        result[path] = log_tree()
                        continue
                    before = signature(inspect(path, code='ENVIRONMENT' if path in ENV else 'ARCHIVE'))
                    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
                    with os.fdopen(fd, 'rb') as f:
                        need(signature(os.fstat(f.fileno())) == before, 'CHANGED')
                        if path in ENV:
                            data = f.read(65537)
                            need(len(data) <= 65536, 'ENVIRONMENT')
                            for line in data.decode('utf-8').splitlines():
                                line = line.strip()
                                if line and not line.startswith(('#', ';')):
                                    need(re.fullmatch(r'XUI_DB_TYPE[ \t]*=[ \t]*(?:sqlite|"sqlite"|\x27sqlite\x27)', line), 'ENVIRONMENT')
                        else:
                            h = hashlib.sha256()
                            for block in iter(lambda: f.read(1048576), b''):
                                h.update(block)
                            need(h.hexdigest() == ARCHIVES[path], 'ARCHIVE')
                    need(signature(os.lstat(path)) == before, 'CHANGED')
                    result[path] = before
                return result
            def remove_log(records):
                for parent, dirs, files, fd in os.fwalk(LOG, topdown=False, follow_symlinks=False):
                    unmounted(LOG)
                    need(parent in records and signature(os.fstat(fd))[:5] == records[parent][:5], 'CHANGED')
                    for name in files:
                        path = parent + '/' + name
                        need(path in records and signature(os.stat(name, dir_fd=fd, follow_symlinks=False)) == records[path], 'CHANGED')
                        os.unlink(name, dir_fd=fd)
                    for name in dirs:
                        path = parent + '/' + name
                        need(path in records and signature(os.stat(name, dir_fd=fd, follow_symlinks=False))[:5] == records[path][:5], 'CHANGED')
                        os.rmdir(name, dir_fd=fd)
                unmounted(LOG)
                need(signature(inspect(LOG, directory=True, code='LOG_TREE'))[:5] == records[LOG][:5], 'CHANGED')
                os.rmdir(LOG)
            try:
                need(os.geteuid() == 0 and os.getegid() == 0, 'ROOT')
                signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(Refused('TIMEOUT')))
                signal.alarm(100)
                boundary()
                receipt()
                absent()
                approved = plan()
                for path in approved:
                    absent()
                    current = plan()
                    need(path in current and current[path] == approved[path], 'CHANGED')
                    if path == LOG:
                        remove_log(approved[path])
                    else:
                        os.unlink(path)
                absent()
                need(not any(os.path.lexists(p) for p in ENV + (LOG,) + tuple(ARCHIVES)), 'CHANGED')
            except Exception as error:
                code = str(error) if isinstance(error, Refused) else 'UNVERIFIED'
                print('LEGACY_REMNANTS:' + code + ': verify completed-uninstall receipt in /root/.xboard-install and only /etc/default/x-ui, /etc/sysconfig/x-ui, /var/log/x-ui, /usr/local/x-ui-linux-{amd64,arm64}.tar.gz; SSL was preserved', file=sys.stderr)
                raise SystemExit(1)
        """)

        private val CERTIFICATE_CHECK = script("""
            import ipaddress, os, pathlib, re, shlex, ssl, stat, subprocess, sys, time
            def fail():
                raise ValueError('certificate')
            def safe(path, directory=False, private=False):
                s = os.lstat(path)
                if s.st_uid != 0 or s.st_gid != 0 or s.st_mode & 0o022:
                    fail()
                if directory:
                    if not stat.S_ISDIR(s.st_mode) or (private and stat.S_IMODE(s.st_mode) != 0o700):
                        fail()
                elif not stat.S_ISREG(s.st_mode) or s.st_nlink != 1 or (private and stat.S_IMODE(s.st_mode) != 0o600):
                    fail()
            def present(path):
                return os.path.lexists(path)
            def credential(path):
                if not os.path.isabs(path) or os.path.normpath(path) != path:
                    fail()
                for parent in reversed(pathlib.Path(path).parents):
                    safe(str(parent), directory=True)
                safe(path, private=True)
            def run(args):
                return subprocess.run(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                      stderr=subprocess.DEVNULL, check=True, timeout=15).stdout
            try:
                target, mode = os.environ['XBOARD_TARGET'], os.environ['XUI_SSL_MODE']
                if mode == 'ip':
                    if str(ipaddress.IPv4Address(target)) != target:
                        fail()
                elif mode != 'domain' or not re.fullmatch(r'[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?', target) or '..' in target:
                    fail()
                certdir = '/root/cert/' + (target if mode == 'domain' else 'ip')
                cert, key = certdir + '/fullchain.pem', certdir + '/privkey.pem'
                safe('/root', directory=True)
                if present('/root/cert'):
                    safe('/root/cert', directory=True, private=True)
                exists = present(certdir)
                if exists:
                    safe(certdir, directory=True, private=True)
                    credential(cert)
                    credential(key)
                    info = ssl._ssl._test_decode_cert(cert)
                    sans = info.get('subjectAltName', ())
                    expected = 'DNS' if mode == 'domain' else 'IP Address'
                    if not any(kind == expected and value == target for kind, value in sans):
                        fail()
                    now = time.time()
                    if ssl.cert_time_to_seconds(info['notBefore']) > now or ssl.cert_time_to_seconds(info['notAfter']) <= now + 86400:
                        fail()
                    run(['openssl', 'x509', '-in', cert, '-noout', '-checkend', '86400'])
                    if run(['openssl', 'x509', '-in', cert, '-pubkey', '-noout']) != run(['openssl', 'pkey', '-in', key, '-pubout']):
                        fail()
                    run(['openssl', 'pkey', '-in', key, '-check', '-noout'])
                acme = '/root/.acme.sh'
                if present(acme):
                    safe(acme, directory=True, private=True)
                    for root, dirs, files in os.walk(acme, followlinks=False):
                        for name in dirs:
                            safe(os.path.join(root, name), directory=True)
                        for name in files:
                            file = os.path.join(root, name)
                            safe(file, private=name.endswith(('.conf', '.key')))
                            if name.endswith('.conf'):
                                for line in pathlib.Path(file).read_text().splitlines():
                                    match = re.fullmatch(r'(?:export )?([A-Za-z_][A-Za-z_0-9]*)=(.*)', line.strip())
                                    if not match:
                                        continue
                                    name, raw = match.groups()
                                    values = shlex.split(raw)
                                    if len(values) > 1:
                                        fail()
                                    value = values[0] if values else ''
                                    if not value:
                                        continue
                                    if name.endswith(('_FILE', '_FILE_PATH')):
                                        credential(value)
                                    if root in (acme + '/' + target, acme + '/' + target + '_ecc') and name.startswith('Le_Real') and name.endswith('Path'):
                                        allowed = {'Le_RealCertPath': certdir + '/cert.pem', 'Le_RealCACertPath': certdir + '/ca.pem',
                                                   'Le_RealKeyPath': key, 'Le_RealFullChainPath': cert}
                                        if allowed.get(name) != value:
                                            fail()
                                        if present(value):
                                            credential(value)
                    safe(acme + '/acme.sh')
                    if not os.access(acme + '/acme.sh', os.X_OK):
                        fail()
                    if not exists and any(present(acme + '/' + target + suffix) for suffix in ('', '_ecc')):
                        fail()
                print('reuse' if exists else 'new')
            except Exception:
                job = os.environ.get('XBOARD_JOB')
                if job and os.environ.get('XBOARD_RECHECK') != '1':
                    fd = os.open(job + '/error', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600) if not present(job + '/error') else None
                    if fd is not None:
                        with os.fdopen(fd, 'w') as f:
                            f.write('CERTIFICATE_INVALID\n')
                print('CERTIFICATE_INVALID: retained SSL files, validity, SAN, key or ACME paths require attention; nothing was replaced', file=sys.stderr)
                raise SystemExit(1)
        """)

        private val RUNNER: String by lazy {
            JOB_GUARDS + CORE_GUARDS + script("""
            set -euo pipefail
            umask 077
            trap '' HUP
            job_owned=0
            claim_owned=0
            active=/root/.xboard-install/active
            finish() {
                rc=§?
                trap - EXIT
                set +e
                if [[ §job_owned == 1 ]] && private_dir /root/.xboard-install && private_dir "§job" && private_file "§job/status"; then
                    for file in runner cookies certificate-check.py; do
                        if private_file "§job/§file"; then rm -- "§job/§file"; fi
                    done
                    status=§(cat "§job/status")
                    if [[ §rc != 0 && §status == RUNNING ]]; then
                        status_next=§(mktemp "§job/status.XXXXXXXX")
                        if [[ -n §status_next ]] && printf 'FAILED\n' > "§status_next" && chmod 600 "§status_next" && mv -f "§status_next" "§job/status"; then
                            status=FAILED
                        elif private_file "§status_next"; then
                            rm -- "§status_next"
                        fi
                    fi
                    if [[ §claim_owned == 1 && ( §status == SUCCEEDED || §status == FAILED ) ]] &&
                        private_dir "§active" && private_file "§active/job" && [[ §(cat "§active/job") == "§job" ]]; then
                        release_pid=0
                        if [[ ! -e "§active/pid" && ! -L "§active/pid" ]]; then
                            release_pid=1
                        elif private_file "§active/pid"; then
                            pid=§(cat "§active/pid")
                            if [[ §pid == "§§" ]] || { [[ §pid =~ ^[1-9][0-9]*§ ]] && ! kill -0 "§pid" 2>/dev/null; }; then
                                release_pid=1
                            fi
                        fi
                        release_shape=0
                        if unknown=§(find "§active" -mindepth 1 -maxdepth 1 ! -name job ! -name pid -print -quit) && [[ -z §unknown ]]; then
                            release_shape=1
                        fi
                        if [[ §release_pid == 1 && §release_shape == 1 ]]; then
                            if [[ -e "§active/pid" ]]; then rm -- "§active/pid"; fi
                            rm -- "§active/job"
                            rmdir -- "§active"
                        fi
                    fi
                fi
                exit "§rc"
            }
            trap finish EXIT
            exec 9<&-
            [[ §(id -u) == 0 && §(id -g) == 0 ]]
            [[ §{job:-} =~ ^/root/\.xboard-install/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}§ ]]
            [[ -d /root && ! -L /root && §(stat -c %u /root) == 0 ]]
            (( (8#§(stat -c %a /root) & 0022) == 0 ))
            private_dir /root/.xboard-install
            private_dir "§job"
            [[ §{BASH_SOURCE[0]} == "§job/runner" ]]
            for file in runner status; do private_file "§job/§file"; done
            [[ §(cat "§job/status") == RUNNING ]]
            job_owned=1
            private_dir "§active"
            private_file "§active/job"
            [[ §(cat "§active/job") == "§job" ]]
            claim_owned=1
            for file in phase result; do private_file "§job/§file"; done
            for attempt in {1..100}; do
                [[ -f "§active/pid" ]] && break
                sleep 0.1
            done
            private_file "§active/pid"
            pid=§(cat "§active/pid")
            [[ §pid =~ ^[1-9][0-9]*§ ]]
            if [[ §pid != "§§" ]]; then
                for attempt in {1..10}; do
                    kill -0 "§pid" 2>/dev/null || break
                    sleep 0.1
                done
                if kill -0 "§pid" 2>/dev/null; then exit 1; fi
            fi
            [[ ! -e "§active/pid.next" && ! -L "§active/pid.next" ]]
            printf '%s\n' "§§" > "§active/pid.next"
            mv "§active/pid.next" "§active/pid"
            phase() {
                printf '%s\n' "§1" > "§job/phase.next"
                chmod 600 "§job/phase.next"
                mv -f "§job/phase.next" "§job/phase"
            }
            require_clean
            phase BOOTSTRAP
            case "§(. /etc/os-release; printf '%s' "§ID")" in ubuntu|debian|armbian) ;; *) exit 1 ;; esac
            export DEBIAN_FRONTEND=noninteractive
            apt-get update >/dev/null 2>&1
            apt-get install -y --no-install-recommends curl openssl ca-certificates python3 >/dev/null 2>&1
            export XUI_USERNAME XUI_PASSWORD XUI_PANEL_PORT XUI_WEB_BASE_PATH XUI_SSL_MODE XUI_DOMAIN XUI_SERVER_IP XUI_ACME_EMAIL
            export XBOARD_TARGET XBOARD_PASSWORD_HASH
            export XBOARD_JOB="§job"
            cat > "§job/certificate-check.py" <<'XBOARD_CERTIFICATE'
            """) + CERTIFICATE_CHECK + script("""
            XBOARD_CERTIFICATE
            chmod 600 "§job/certificate-check.py"
            python3 "§job/certificate-check.py" >/dev/null
            export XUI_NONINTERACTIVE=1 XUI_DB_TYPE=sqlite XUI_ENABLE_FAIL2BAN=false XUI_ACME_HTTP_PORT=80 XUI_SSL_IPV6=
            curl() { command /usr/bin/curl -q --proto '=https' --proto-redir '=https' --tlsv1.2 "§@"; }
            export -f curl
            phase DOWNLOAD
            curl --fail --silent --show-error --location --connect-timeout 15 --max-time 180 \
                --output "§job/install.sh" \
                'https://raw.githubusercontent.com/MHSanaei/3x-ui/c621ba9014a6aa21fd7c9ecf45655e44e24ebf13/install.sh'
            chmod 600 "§job/install.sh"
            phase PATCH
            python3 - <<'XBOARD_PATCH'
            import hashlib, os, pathlib
            p = pathlib.Path(os.environ['XBOARD_JOB']) / 'install.sh'
            data = p.read_bytes()
            if hashlib.sha256(data).hexdigest() != '9684bae4f769b2127fcceceb4f200045657381311867529e6ba76c6b58e2825d':
                raise SystemExit(1)
            text = data.decode('utf-8')
            def replace_once(old, new):
                global text
                if text.count(old) != 1:
                    raise SystemExit(1)
                text = text.replace(old, new, 1)
            replace_once('    local required_files=("x-ui.sh")\n', '    xboard_phase SCRIPT_DOWNLOAD\n    local required_files=("x-ui.sh")\n')
            replace_once('    tar zxvf x-ui-linux-§(arch).tar.gz\n',
                         '    xboard_phase EXTRACT\n    tar zxvf x-ui-linux-§(arch).tar.gz\n')
            replace_once('config_after_install() {\n', 'config_after_install() {\n    xboard_phase CONFIGURE\n')
            replace_once('prompt_and_setup_ssl() {\n', '''prompt_and_setup_ssl() {
                xboard_phase ACME
                local xboard_state
                xboard_state=§(python3 "§XBOARD_JOB/certificate-check.py") || exit 1
                if [[ "§xboard_state" == reuse ]]; then
                    local cert_dir=/root/cert/ip
                    if [[ "§XUI_SSL_MODE" == domain ]]; then cert_dir="/root/cert/§XBOARD_TARGET"; fi
                    "§xui_folder/x-ui" cert -webCert "§cert_dir/fullchain.pem" -webCertKey "§cert_dir/privkey.pem" || exit 1
                    xboard_require_tls || exit 1
                    SSL_SCHEME=https
                    SSL_HOST="§XBOARD_TARGET"
                    SSL_ISSUED_DOMAIN="§XBOARD_TARGET"
                    return 0
                fi
            ''')
            replace_once('install_acme() {\n', '''install_acme() {
                if [[ -e /root/.acme.sh || -L /root/.acme.sh ]]; then
                    [[ -x /root/.acme.sh/acme.sh && ! -L /root/.acme.sh/acme.sh ]] || return 1
                    return 0
                fi
            ''')
            for line in text.splitlines(keepends=True):
                if '~/.acme.sh/acme.sh --set-default-ca ' in line or '~/.acme.sh/acme.sh --upgrade --auto-upgrade' in line:
                    text = text.replace(line, '    :\n')
            text = text.replace('--standalone --httpport', '--server letsencrypt --standalone --httpport')
            text = text.replace('--register-account -m', '--register-account --server letsencrypt -m')
            text = text.replace('chmod 644 §certPath/fullchain.pem', 'chmod 600 §certPath/fullchain.pem')
            text = text.replace('chmod 644 §{certDir}/fullchain.pem', 'chmod 600 §{certDir}/fullchain.pem')
            for line in text.splitlines(keepends=True):
                if 'rm -rf ' in line and ('~/.acme.sh/' in line or '"§certPath"' in line or '§{certDir}' in line):
                    text = text.replace(line, '        :\n')
            replace_once('            prompt_and_setup_ssl "§{config_port}" "§{config_webBasePath}" "§{server_ip}"\n',
                         '            prompt_and_setup_ssl "§{config_port}" "§{config_webBasePath}" "§{server_ip}"\n            xboard_require_tls || exit 1\n')
            replace_once('    local server_ip=""\n    for ip_address in "§{URL_lists[@]}"; do\n',
                         '    local server_ip="§{XUI_SERVER_IP:-}"\n    for ip_address in "§{URL_lists[@]}"; do\n        [[ -n "§server_ip" ]] && break\n')
            replace_once('            §{xui_folder}/x-ui setting -username "§{config_username}" -password "§{config_password}" -port "§{config_port}" -webBasePath "§{config_webBasePath}"\n',
                         '            §{xui_folder}/x-ui setting -port "§{config_port}" -webBasePath "§{config_webBasePath}" || exit 1\n            xboard_set_credentials || exit 1\n')
            replace_once('            §{xui_folder}/x-ui setting -username "§{config_username}" -password "§{config_password}"\n', '            exit 1\n')
            if ' -password ' in text:
                raise SystemExit(1)
            helpers = '''xboard_phase() {
                printf '%s\\n' "§1" > "§XBOARD_JOB/phase.next" || exit 1
                chmod 600 "§XBOARD_JOB/phase.next" || exit 1
                mv -f "§XBOARD_JOB/phase.next" "§XBOARD_JOB/phase" || exit 1
            }
            xboard_require_tls() {
                local cert_dir=/root/cert/ip configured
                if [[ "§XUI_SSL_MODE" == domain ]]; then cert_dir="/root/cert/§XBOARD_TARGET"; fi
                [[ -s "§cert_dir/fullchain.pem" && -s "§cert_dir/privkey.pem" ]] || return 1
                configured=§("§xui_folder/x-ui" setting -getCert true) || return 1
                grep -Fxq -- "cert: §cert_dir/fullchain.pem" <<< "§configured" || return 1
                grep -Fxq -- "key: §cert_dir/privkey.pem" <<< "§configured" || return 1
            }
            xboard_set_credentials() {
                python3 - <<'XBOARD_CREDENTIALS'
            import os, re, sqlite3, stat
            path = '/etc/x-ui/x-ui.db'
            s = os.lstat(path)
            if not stat.S_ISREG(s.st_mode) or s.st_uid != 0 or s.st_nlink != 1:
                raise SystemExit(1)
            hashed = os.environ['XBOARD_PASSWORD_HASH']
            if not re.fullmatch(r'[§]2a[§]10[§][./A-Za-z0-9]{53}', hashed):
                raise SystemExit(1)
            with sqlite3.connect('file:' + path + '?mode=rw', uri=True) as db:
                rows = db.execute('SELECT id, username FROM users').fetchall()
                if len(rows) > 1:
                    raise SystemExit(1)
                if rows:
                    db.execute('UPDATE users SET username=?, password=?, login_epoch=login_epoch+1 WHERE id=?',
                               (os.environ['XUI_USERNAME'], hashed, rows[0][0]))
                else:
                    db.execute('INSERT INTO users(username,password,login_epoch) VALUES(?,?,0)',
                               (os.environ['XUI_USERNAME'], hashed))
            XBOARD_CREDENTIALS
            }
            '''
            p.with_name('verified.sh').write_text(helpers + text, encoding='utf-8')
            XBOARD_PATCH
            chmod 600 "§job/verified.sh"
            require_clean
            [[ ! -e /etc/x-ui/install-result.env && ! -L /etc/x-ui/install-result.env ]]
            touch "§job/started"
            phase INSTALL
            /bin/bash --noprofile --norc "§job/verified.sh" v3.11.0 </dev/null >/dev/null 2>&1
            phase RESULT
            python3 - <<'XBOARD_FRESH'
            import os, stat
            job = os.environ['XBOARD_JOB']
            for path in ('/etc', '/etc/x-ui'):
                s = os.lstat(path)
                if not stat.S_ISDIR(s.st_mode) or s.st_uid != 0 or s.st_mode & 0o022:
                    raise SystemExit(1)
            s = os.lstat('/etc/x-ui/install-result.env')
            start = os.stat(job + '/started')
            if not stat.S_ISREG(s.st_mode) or s.st_uid != 0 or s.st_gid != 0 or stat.S_IMODE(s.st_mode) != 0o600 or s.st_nlink != 1:
                raise SystemExit(1)
            if not 1 <= s.st_size <= 16384 or s.st_mtime_ns <= start.st_mtime_ns or s.st_ctime_ns <= start.st_ctime_ns:
                raise SystemExit(1)
            XBOARD_FRESH
            expected_user=§XUI_USERNAME
            expected_password=§XUI_PASSWORD
            expected_port=§XUI_PANEL_PORT
            expected_path=§XUI_WEB_BASE_PATH
            unset XUI_USERNAME XUI_PASSWORD XUI_PANEL_PORT XUI_WEB_BASE_PATH XUI_ACCESS_URL XUI_API_TOKEN XUI_DB_TYPE
            source /etc/x-ui/install-result.env
            [[ §XUI_USERNAME == "§expected_user" && §XUI_PASSWORD == "§expected_password" ]]
            [[ §XUI_PANEL_PORT == "§expected_port" && §XUI_WEB_BASE_PATH == "§expected_path" && §XUI_DB_TYPE == sqlite ]]
            export XUI_USERNAME XUI_PASSWORD XUI_PANEL_PORT XUI_WEB_BASE_PATH XUI_ACCESS_URL XUI_API_TOKEN XUI_DB_TYPE
            phase TLS
        """) + VERIFY_AND_EXPORT + script("""
            phase COMPLETE
            printf 'SUCCEEDED\n' > "§job/status.next"
            chmod 600 "§job/status.next"
            mv -f "§job/status.next" "§job/status"
            """)
        }

        private val RECHECK_PREFIX = script("""
            set -euo pipefail
            umask 077
            [[ §(id -u) == 0 ]]
            [[ -d "§job" && ! -L "§job" && §(stat -c '%u:%g:%a' "§job") == 0:0:700 ]]
            export XBOARD_JOB XBOARD_TARGET XBOARD_RECHECK XUI_SSL_MODE
            export XUI_USERNAME XUI_PASSWORD XUI_PANEL_PORT XUI_WEB_BASE_PATH XUI_ACCESS_URL XUI_API_TOKEN XUI_DB_TYPE
        """)

        private val VERIFY_AND_EXPORT = script("""
            python3 - <<'XBOARD_CERTIFICATE_VERIFY' >/dev/null
        """) + CERTIFICATE_CHECK + script("""
            XBOARD_CERTIFICATE_VERIFY
            python3 - <<'XBOARD_VERIFY'
            import base64, json, os, pathlib, re, stat, subprocess, tempfile, time, urllib.parse
            env = os.environ
            job = pathlib.Path(env['XBOARD_JOB'])
            target, mode = env['XBOARD_TARGET'], env['XUI_SSL_MODE']
            username, password = env['XUI_USERNAME'], env['XUI_PASSWORD']
            port, path, token = env['XUI_PANEL_PORT'], env['XUI_WEB_BASE_PATH'], env['XUI_API_TOKEN']
            if mode not in ('domain', 'ip') or not re.fullmatch(r'[a-z0-9.-]{1,253}', target):
                raise SystemExit(1)
            if not re.fullmatch(r'[0-9]{1,5}', port) or not 1 <= int(port) <= 65535:
                raise SystemExit(1)
            if not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', path) or not re.fullmatch(r'[A-Za-z0-9_-]{20,512}', token):
                raise SystemExit(1)
            url = 'https://' + target + ':' + port + '/' + path
            if env['XUI_ACCESS_URL'] not in (url, url + '/'):
                raise SystemExit(1)
            def run(args):
                return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30).stdout
            certdir = '/root/cert/' + (target if mode == 'domain' else 'ip')
            cert, key = certdir + '/fullchain.pem', certdir + '/privkey.pem'
            configured = run(['/usr/local/x-ui/x-ui', 'setting', '-getCert', 'true']).decode('utf-8')
            if configured.splitlines().count('cert: ' + cert) != 1 or configured.splitlines().count('key: ' + key) != 1:
                raise SystemExit(1)
            for directory in ('/root', '/root/cert', certdir):
                s = os.lstat(directory)
                if not stat.S_ISDIR(s.st_mode) or s.st_uid != 0 or s.st_mode & 0o022:
                    raise SystemExit(1)
            for file in (cert, key):
                s = os.lstat(file)
                if not stat.S_ISREG(s.st_mode) or s.st_uid != 0 or s.st_nlink != 1 or s.st_size == 0 or s.st_mode & 0o022:
                    raise SystemExit(1)
            if stat.S_IMODE(os.stat(key).st_mode) & 0o077:
                raise SystemExit(1)
            run(['openssl', 'x509', '-in', cert, '-noout', '-checkend', '86400'])
            run(['openssl', 'x509', '-in', cert, '-noout', '-checkhost' if mode == 'domain' else '-checkip', target])
            if run(['openssl', 'x509', '-in', cert, '-pubkey', '-noout']) != run(['openssl', 'pkey', '-in', key, '-pubout']):
                raise SystemExit(1)
            for attempt in range(60):
                active = subprocess.run(['systemctl', 'is-active', '--quiet', 'x-ui'],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10).returncode == 0
                sockets = run(['ss', '-H', '-ltn']).decode('ascii')
                listening = any(len(parts) >= 5 and parts[3].rsplit(':', 1)[-1] == port
                                for parts in (line.split() for line in sockets.splitlines()))
                if active and listening:
                    break
                time.sleep(1)
            else:
                raise SystemExit(1)
            def phase(value):
                if env.get('XBOARD_RECHECK') == '1':
                    return
                temp = job / 'phase.next'
                temp.write_text(value + '\n', encoding='ascii')
                temp.chmod(0o600)
                temp.replace(job / 'phase')
            phase('API')
            cookie_fd, cookie_path = tempfile.mkstemp(prefix='verify-', suffix='.cookies', dir=job)
            os.close(cookie_fd)
            cookies = pathlib.Path(cookie_path)
            def quote(value):
                if any(ord(c) < 32 or ord(c) == 127 for c in value):
                    raise SystemExit(1)
                return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
            def request(suffix, bearer=False, body=None, csrf=None, session=False):
                config = ['silent', 'show-error', 'fail', 'proto = "=https"', 'proto-redir = "=https"',
                          'tlsv1.2', 'connect-timeout = 10', 'max-time = 25', 'max-filesize = 262144',
                          'proxy = ""', 'noproxy = "*"', 'cacert = "/etc/ssl/certs/ca-certificates.crt"',
                          'resolve = ' + quote(target + ':' + port + ':127.0.0.1'),
                          'url = ' + quote(url + '/' + suffix)]
                if bearer:
                    config.append('header = ' + quote('Authorization: Bearer ' + token))
                if session:
                    config.extend(['cookie = ' + quote(str(cookies)), 'cookie-jar = ' + quote(str(cookies))])
                if csrf is not None:
                    config.append('header = ' + quote('X-CSRF-Token: ' + csrf))
                if body is not None:
                    config.extend(['header = "Content-Type: application/x-www-form-urlencoded"', 'data = ' + quote(body)])
                output = subprocess.run(['/usr/bin/curl', '-q', '--config', '-'],
                                        input=('\n'.join(config) + '\n').encode('utf-8'),
                                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True, timeout=30).stdout
                if len(output) > 262144:
                    raise SystemExit(1)
                result = json.loads(output)
                if not isinstance(result, dict) or result.get('success') is not True:
                    raise SystemExit(1)
                return result
            try:
                state = request('panel/api/server/status', bearer=True)
                if not isinstance(state.get('obj'), dict):
                    raise SystemExit(1)
                csrf = request('csrf-token', session=True).get('obj')
                if not isinstance(csrf, str) or not re.fullmatch(r'[A-Za-z0-9_-]{43}', csrf):
                    raise SystemExit(1)
                request('login', session=True, csrf=csrf,
                        body=urllib.parse.urlencode({'username': username, 'password': password, 'twoFactorCode': ''}))
                if not isinstance(request('panel/api/server/status', session=True).get('obj'), dict):
                    raise SystemExit(1)
                run(['systemctl', 'is-active', '--quiet', 'x-ui'])
                keys = ('XUI_USERNAME', 'XUI_PASSWORD', 'XUI_PANEL_PORT', 'XUI_WEB_BASE_PATH',
                        'XUI_ACCESS_URL', 'XUI_API_TOKEN', 'XUI_DB_TYPE')
                lines = ['XBOARD_RESULT_V1'] + [k + '\t' + base64.b64encode(env[k].encode('utf-8')).decode('ascii') for k in keys] + ['END', '']
                if env.get('XBOARD_RECHECK') != '1':
                    output = job / 'result.next'
                    output.write_text('\n'.join(lines), encoding='ascii')
                    output.chmod(0o600)
                    output.replace(job / 'result')
            finally:
                cookies.unlink(missing_ok=True)
            XBOARD_VERIFY
        """)

        private val POLL = script("""
            set -euo pipefail
            [[ §(id -u) == 0 ]]
            [[ -d /root && ! -L /root && §(stat -c %u /root) == 0 ]]
            (( (8#§(stat -c %a /root) & 0022) == 0 ))
            for directory in /root/.xboard-install "§job"; do
                [[ -d "§directory" && ! -L "§directory" ]]
                [[ §(stat -c '%u:%g:%a' "§directory") == 0:0:700 ]]
            done
            for file in phase status result; do
                p="§job/§file"
                [[ -f "§p" && ! -L "§p" && §(stat -c '%u:%g:%a:%h' "§p") == 0:0:600:1 ]]
                [[ §(stat -c %s "§p") -le 16384 ]]
            done
            IFS= read -r status < "§job/status"
            IFS= read -r phase < "§job/phase"
            case "§status" in RUNNING|FAILED|SUCCEEDED) ;; *) exit 1 ;; esac
            printf 'status\t%s\nphase\t%s\n' "§status" "§phase"
            if [[ §status == SUCCEEDED ]]; then cat "§job/result"; fi
            if [[ §status == FAILED && -e "§job/error" ]]; then
                [[ -f "§job/error" && ! -L "§job/error" && §(stat -c '%u:%g:%a:%h' "§job/error") == 0:0:600:1 ]]
                [[ §(cat "§job/error") == CERTIFICATE_INVALID ]]
                printf 'error\tCERTIFICATE_INVALID\n'
            fi
        """)

        private val REBOOT = script("""
            set -euo pipefail
            [[ §(id -u) == 0 ]]
            systemctl reboot --no-block >/dev/null 2>&1
            printf 'ACCEPTED\n'
        """)

        private val RELOAD_XRAY = script("""
            set -euo pipefail
            [[ §(id -u) == 0 ]]
            systemctl reload x-ui >/dev/null 2>&1
            systemctl is-active --quiet x-ui
            printf 'ACCEPTED\n'
        """)
    }
}
