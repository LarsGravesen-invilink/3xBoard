package app.invilink.xboard.ssh

import android.content.Context
import android.util.Log
import app.invilink.xboard.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

internal fun protectionServers(servers: List<InstalledServer>, excluded: Set<String>): List<InstalledServer> {
    val blocked = servers.filter { it.panelId in excluded }
    return servers.filterNot { server -> blocked.any {
        it.sshHost.equals(server.sshHost, ignoreCase = true) ||
            (it.hostKeyAlgorithm == server.hostKeyAlgorithm && it.hostKeyBase64.isNotBlank() && it.hostKeyBase64 == server.hostKeyBase64)
    } }
}

internal fun uniqueProtectionServers(servers: List<InstalledServer>): List<InstalledServer> =
    servers.groupBy { it.sshHost.lowercase(java.util.Locale.ROOT) to it.sshPort }.values.map { group ->
        group.firstOrNull { it.credentials != null } ?: group.first()
    }

internal fun protectionInstallError(exitCode: Int): String = when (exitCode) {
    10 -> "для установки нужны права root"
    11 -> "не удалось определить систему"
    12 -> "автоустановка поддерживает Debian и Ubuntu"
    13 -> "не найден apt-get"
    14 -> "не удалось обновить списки пакетов apt"
    15 -> "не удалось установить пакет iptables"
    16 -> "после установки не найдены iptables и ip6tables"
    else -> "команда проверки завершилась с кодом $exitCode"
}

internal val installProtectionToolsCommand = """
    export PATH="${'$'}PATH:/usr/sbin:/usr/bin:/sbin:/bin"
    [ "${'$'}(id -u)" = 0 ] || exit 10
    if command -v iptables >/dev/null 2>&1 && command -v ip6tables >/dev/null 2>&1; then echo OK; exit 0; fi
    [ -r /etc/os-release ] || exit 11
    . /etc/os-release
    case "${'$'}ID" in debian|ubuntu) ;; *) exit 12;; esac
    command -v apt-get >/dev/null 2>&1 || exit 13
    export DEBIAN_FRONTEND=noninteractive
    apt-get -o DPkg::Lock::Timeout=30 update >/dev/null 2>&1 || exit 14
    apt-get -o DPkg::Lock::Timeout=30 install -y --no-install-recommends iptables >/dev/null 2>&1 || exit 15
    command -v iptables >/dev/null 2>&1 && command -v ip6tables >/dev/null 2>&1 || exit 16
    echo OK
""".trimIndent()

internal fun webUiProtectionCommand(port: Int, block: Boolean): String {
    require(port in 1..65535)
    val action = if (block) "block" else "unblock"
    return """
        export LC_ALL=C
        export PATH="${'$'}PATH:/usr/sbin:/usr/bin:/sbin:/bin"
        [ "${'$'}(id -u)" = 0 ] || exit 10
        if [ '$action' = block ]; then
            set -- ${'$'}SSH_CONNECTION
            [ "${'$'}#" = 4 ] && [ "${'$'}4" != '$port' ] || exit 17
        fi
        for tool in iptables ip6tables; do "${'$'}tool" -w 5 -S INPUT >/dev/null 2>&1 || exit 11; done
        check_rule() {
            err=${'$'}("${'$'}1" -w 5 -C INPUT ! -i lo -p tcp --dport $port -m comment --comment 3xboard-webui-$port -j DROP 2>&1) && return 0
            case "${'$'}err" in *"Bad rule"*|*"does a matching rule exist"*) return 1;; *) return 2;; esac
        }
        for tool in iptables ip6tables; do
            check_rule "${'$'}tool"; present=${'$'}?
            [ "${'$'}present" -le 1 ] || exit 12
            if [ '$action' = block ] && [ "${'$'}present" = 1 ]; then
                "${'$'}tool" -w 5 -I INPUT ! -i lo -p tcp --dport $port -m comment --comment 3xboard-webui-$port -j DROP || exit 13
            elif [ '$action' = unblock ] && [ "${'$'}present" = 0 ]; then
                "${'$'}tool" -w 5 -D INPUT ! -i lo -p tcp --dport $port -m comment --comment 3xboard-webui-$port -j DROP || exit 14
            fi
            check_rule "${'$'}tool"; present=${'$'}?
            if [ '$action' = block ]; then [ "${'$'}present" = 0 ] || exit 15
            else [ "${'$'}present" = 1 ] || exit 16; fi
        done
        echo OK
    """.trimIndent()
}

data class ServerManagerState(
    val protectionInstallBusy: Boolean = false,
    val webUiActive: Boolean = false,
    val webUiBusy: Boolean = false,
    val webUiProgress: Float = 0f,
    val webUiTargetActive: Boolean = false,
    val webUiHidden: Set<String> = emptySet(),
    val webUiPending: Set<String> = emptySet(),
    val servers: List<InstalledServer> = emptyList(),
    val sshOnlyServers: List<SshOnlyServer> = emptyList(),
    val sshOnlyPanelStatus: Map<String, PanelInstallationState> = emptyMap(),
    val sshOnlyLocations: Map<String, app.invilink.xboard.panels.PanelLocation> = emptyMap(),
    val installer: InstallerState = InstallerState(),
    val importMode: Boolean = false,
    val sshOnlyCandidate: SshOnlyServer? = null,
    val sshOnlySavedInstall: Boolean? = null,
    val sshOnlyConsoleId: String? = null,
    val hostTrust: SshHostIdentity? = null,
    val controlPanelId: String? = null,
    val confirmation: ServerConfirmation? = null,
    val renamePanelId: String? = null,
    val credentialsPanelId: String? = null,
    val reconnectPanelId: String? = null,
    val consolePanelId: String? = null,
    val consoleOutput: String = "",
    val consoleOutputTruncated: Boolean = false,
    val consoleNoOutputNotice: Boolean = false,
    val consoleActive: Boolean = false,
    val consoleAwaitingOutput: Boolean = false,
    val consoleDeadlineElapsed: Long? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val noticeId: String = java.util.UUID.randomUUID().toString(),
)

private data class WebUiRecord(val panelId: String, val host: String, val sshPort: Int, val panelPort: Int,
    val algorithm: String, val hostKey: String, val confirmed: Boolean) {
    fun matches(server: InstalledServer): Boolean = server.panelId == panelId && server.sshHost == host &&
        server.sshPort == sshPort && server.hostKeyAlgorithm == algorithm && server.hostKeyBase64 == hostKey
    fun json(): org.json.JSONObject = org.json.JSONObject().put("id", panelId).put("host", host).put("ssh", sshPort)
        .put("port", panelPort).put("algorithm", algorithm).put("key", hostKey).put("confirmed", confirmed)
}

internal fun requireSshOnlyIdentity(expected: SshOnlyServer, current: SshOnlyServer?, actual: SshHostIdentity) {
    check(current == expected) { "SSH-подключение изменилось или удалено; команда не отправлена." }
    check(actual == expected.identity) { "Ключ SSH не соответствует сохранённому серверу. Команда не отправлена." }
}

private class ConsoleException(message: String) : IllegalStateException(message)

internal const val CONSOLE_OUTPUT_LIMIT = 1_048_576
internal const val CONSOLE_QUEUE_CAPACITY = 1
internal class ConsoleScrollback {
    private val output = StringBuilder()
    var truncated = false
        private set

    fun append(chunk: String) {
        if (chunk.length >= CONSOLE_OUTPUT_LIMIT) {
            truncated = truncated || output.isNotEmpty() || chunk.length > CONSOLE_OUTPUT_LIMIT
            output.setLength(0)
            var start = chunk.length - CONSOLE_OUTPUT_LIMIT
            if (start > 0 && Character.isLowSurrogate(chunk[start]) && Character.isHighSurrogate(chunk[start - 1])) start++
            output.append(chunk, start, chunk.length)
        } else {
            var remove = (output.length + chunk.length - CONSOLE_OUTPUT_LIMIT).coerceAtLeast(0)
            if (remove > 0) {
                if (remove < output.length && Character.isLowSurrogate(output[remove]) && Character.isHighSurrogate(output[remove - 1])) remove++
                output.delete(0, remove)
                truncated = true
            }
            output.append(chunk)
        }
    }

    override fun toString(): String = output.toString()
}

data class ServerConfirmation(
    val panelId: String?,
    val action: String,
    val title: String,
    val warning: String,
)

class ServerManager(
    context: Context,
    private val scope: CoroutineScope,
    private val onInstalled: suspend (InstalledServer, String) -> Unit,
    private val onRemove: suspend (String) -> Unit,
    private val onRename: suspend (String, String) -> Unit,
    private val withRemoteRemoval: suspend (String, Boolean, suspend () -> Unit) -> Unit,
    private val canManage: () -> Boolean = { true },
    private val isOnline: () -> Boolean,
    private val existingPanelId: (String) -> String? = { null },
    private val protectionExclusions: () -> Set<String> = { emptySet() },
) {
    private val lock = Any()
    private val store = ServerStore(context.applicationContext ?: context)
    private val sshLocationResolver = app.invilink.xboard.panels.PanelLocationResolver()
    private val connection = SshConnection(store)
    private var runner = ThreeXInstaller(connection)
    private var maintenance = ThreeXMaintenance(connection)
    private val uninstaller = ThreeXUninstaller(connection)
    private val webUiPrefs = (context.applicationContext ?: context).getSharedPreferences("xboard.webui.protection", Context.MODE_PRIVATE)
    private val webUiLoaded = runCatching {
        val array = org.json.JSONArray(webUiPrefs.getString("targets", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            WebUiRecord(item.getString("id"), item.getString("host"), item.getInt("ssh"), item.getInt("port"),
                item.getString("algorithm"), item.getString("key"), item.getBoolean("confirmed"))
        }.also { records -> require(records.map { it.panelId }.distinct().size == records.size) }
    }
    private var webUiRecords = webUiLoaded.getOrDefault(emptyList())
    private val mutableState = MutableStateFlow(ServerManagerState(
        webUiActive = webUiRecords.isNotEmpty(),
        webUiHidden = webUiRecords.filter { it.confirmed }.map { it.panelId }.toSet(),
        webUiPending = webUiRecords.filterNot { it.confirmed }.map { it.panelId }.toSet(),
    ))
    val state: StateFlow<ServerManagerState> = mutableState.asStateFlow()

    private fun protectionServers(servers: List<InstalledServer>): List<InstalledServer> =
        protectionServers(servers, protectionExclusions())

    internal suspend fun installAwgKernel(panelId: String) {
        check(canManage() && isOnline()) { "Для установки нужна активная сессия и сеть." }
        val server = state.value.servers.singleOrNull { it.panelId == panelId }
            ?: error("SSH-сервер не привязан к этой панели. Используйте консоль SSH.")
        val credentials = server.credentials ?: error("SSH-данные не сохранены. Используйте консоль SSH.")
        val ssh = SshConnection(store)
        try {
            val identity = ssh.connect(credentials)
            check(matchesServer(identity, server) && identity.algorithm == server.hostKeyAlgorithm && identity.keyBase64 == server.hostKeyBase64) {
                "Ключ SSH не совпадает с сохранённым. Команда не отправлена."
            }
            check(canManage() && isOnline() && state.value.servers.any { it == server }) { "Данные сервера или сессия изменились. Команда не отправлена." }
            val result = ssh.execute("test \"\$(id -u)\" = 0 && /usr/local/x-ui/x-ui install-awg-kernel", timeoutMillis = 600_000, maxBytes = 65536)
            check(result.exitCode == 0) { "Установка не подтверждена (код ${result.exitCode}). Проверьте сервер вручную; автоматически команда не повторяется." }
        } finally { ssh.close() }
    }

    fun prepareProtectionTools() = synchronized(lock) {
        if (mutableState.value.protectionInstallBusy || mutableState.value.busy || mutableState.value.webUiBusy) {
            mutableState.value = mutableState.value.copy(error = "Не удалось проверить iptables: выполняется другая операция.")
            return@synchronized
        }
        if (!canManage() || !isOnline()) {
            mutableState.value = mutableState.value.copy(error = "Не удалось проверить iptables: нет подключения к сети.")
            return@synchronized
        }
        val servers = uniqueProtectionServers(protectionServers(mutableState.value.servers))
        mutableState.value = mutableState.value.copy(protectionInstallBusy = true, error = null)
        scope.launch {
            val failures = mutableListOf<String>()
            try {
                for (server in servers) {
                    val ssh = SshConnection(store)
                    try {
                        val identity = ssh.connect(server.credentials ?: error("Нет сохранённых SSH-данных"))
                        check(matchesServer(identity, server) && identity.algorithm == server.hostKeyAlgorithm && identity.keyBase64 == server.hostKeyBase64) {
                            "Сохранённая SSH-запись не совпадает с сервером"
                        }
                        val result = ssh.execute(installProtectionToolsCommand, timeoutMillis = 240_000, maxBytes = 4096)
                        check(result.exitCode == 0) { protectionInstallError(result.exitCode) }
                        check(result.stdout.trim() == "OK") { "неожиданный ответ сервера" }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val detail = when (e) {
                            is SshConnectionException -> e.reason.description
                            is UnknownSshHost -> "ключ SSH ещё не подтверждён"
                            is IllegalStateException -> e.message?.take(240) ?: "неизвестная ошибка"
                            else -> "SSH-подключение или выполнение команды не удалось"
                        }
                        failures += "${server.sshHost}:${server.sshPort}: $detail"
                    }
                    finally { ssh.close() }
                }
            } finally {
                synchronized(lock) {
                    mutableState.value = mutableState.value.copy(protectionInstallBusy = false,
                        error = if (failures.isNotEmpty()) "Не удалось проверить или установить iptables. ${failures.take(3).joinToString("; ")}" +
                            if (failures.size > 3) "; ещё ${failures.size - 3}" else "" else mutableState.value.error)
                }
            }
        }
    }

    private fun saveWebUiRecords(records: List<WebUiRecord>) {
        val array = org.json.JSONArray()
        records.forEach { array.put(it.json()) }
        check(webUiPrefs.edit().putString("targets", array.toString()).commit()) { "Не удалось сохранить состояние WebUI." }
        synchronized(lock) {
            webUiRecords = records
            mutableState.value = mutableState.value.copy(webUiActive = records.isNotEmpty(),
                webUiHidden = records.filter { it.confirmed }.map { it.panelId }.toSet(),
                webUiPending = records.filterNot { it.confirmed }.map { it.panelId }.toSet())
        }
    }

    fun toggleWebUi(available: suspend (String) -> Int?, after: () -> Unit) = synchronized(lock) {
        if (mutableState.value.webUiBusy || mutableState.value.protectionInstallBusy || mutableState.value.busy || !canManage() || !isOnline()) return@synchronized
        if (webUiLoaded.isFailure) {
            mutableState.value = mutableState.value.copy(error = "Не удалось прочитать сохранённое состояние WebUI; изменения запрещены.")
            return@synchronized
        }
        val allServers = mutableState.value.servers.toList()
        val block = webUiRecords.isEmpty()
        val servers = if (block) protectionServers(allServers) else allServers
        mutableState.value = mutableState.value.copy(webUiBusy = true, webUiProgress = 0f, webUiTargetActive = block, error = null, message = null)
        scope.launch {
            var failed = 0
            try {
                val candidates: List<WebUiRecord> = if (block) {
                    val checked = mutableMapOf<String, Int?>()
                    for ((index, server) in servers.withIndex()) {
                        checked[server.panelId] = try { available(server.panelId) }
                            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                        synchronized(lock) { mutableState.value = mutableState.value.copy(webUiProgress = 0.5f * (index + 1) / servers.size) }
                    }
                    servers.filter { server ->
                        val port = checked[server.panelId]
                        val group = servers.filter { it.sshHost.equals(server.sshHost, true) &&
                            (checked[it.panelId] ?: it.panelPort) == port }
                        val safePort = port != null && port in 1..65535 && allServers.none {
                            it.sshHost.equals(server.sshHost, true) && it.sshPort == port
                        }
                        safePort && group.all { checked[it.panelId] != null }
                    }.map { WebUiRecord(it.panelId, it.sshHost, it.sshPort, checkNotNull(checked[it.panelId]), it.hostKeyAlgorithm, it.hostKeyBase64, false) }
                } else webUiRecords.toList()
                for ((index, record) in candidates.withIndex()) {
                    val completed = if (block) 0.5f + 0.5f * (index + 1) / candidates.size else (index + 1).toFloat() / candidates.size
                    val server = servers.firstOrNull { record.matches(it) }
                    if (server == null) {
                        failed++
                        synchronized(lock) { mutableState.value = mutableState.value.copy(webUiProgress = completed) }
                        continue
                    }
                    val ssh = SshConnection(store)
                    try {
                        val credentials = server.credentials ?: error("Нет сохранённых SSH-данных")
                        val identity = ssh.connect(credentials)
                        check(matchesServer(identity, server) && identity.algorithm == record.algorithm && identity.keyBase64 == record.hostKey)
                        if (block) saveWebUiRecords(webUiRecords.filterNot { it.panelId == record.panelId } + record)
                        else saveWebUiRecords(webUiRecords.map { if (it.panelId == record.panelId) it.copy(confirmed = false) else it })
                        val result = ssh.execute(webUiProtectionCommand(record.panelPort, block), timeoutMillis = 30_000, maxBytes = 8192)
                        check(result.exitCode == 0 && result.stdout.trim() == "OK")
                        saveWebUiRecords(if (block) webUiRecords.map { if (it.panelId == record.panelId) it.copy(confirmed = true) else it }
                            else webUiRecords.filterNot { it.panelId == record.panelId })
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed++ }
                    finally {
                        ssh.close()
                        synchronized(lock) { mutableState.value = mutableState.value.copy(webUiProgress = completed) }
                    }
                }
                synchronized(lock) {
                    mutableState.value = mutableState.value.copy(
                        error = if (failed > 0) "WebUI: ошибок: $failed. Состояние некоторых правил может быть неизвестно; повторите восстановление доступа." else null)
                }
            } finally {
                synchronized(lock) { mutableState.value = mutableState.value.copy(webUiBusy = false) }
                after()
            }
        }
    }

    private var operation: Job? = null
    private var consoleTimer: Job? = null
    private var disconnectJob: Job? = null
    private var generation = 0L
    private var installing = false
    private var sessionIdentity: SshHostIdentity? = null
    private var pendingTrustCredentials: SshCredentials? = null
    private var pendingAction: SelectedAction? = null
    private var pendingConsoleCommand: String? = null
    private var consoleCharset = Charsets.UTF_8
    private var consoleInput: kotlinx.coroutines.channels.Channel<String>? = null
    private var consoleInterrupts: kotlinx.coroutines.channels.Channel<Unit>? = null
    private var confirmedInstall: InstallSelection? = null
    private var attempt: Attempt? = null
    private var verifiedInstall: InstalledServer? = null
    private var verifiedPanelName = ""
    private var release: ThreeXRelease? = null
    private var releaseJob: Job? = null
    private var releaseGeneration = 0L
    private var removingPanelId: String? = null

    val pendingJobId: String?
        get() = maintenance.pendingJobId ?: runner.pendingJobId

    private data class SelectedAction(val server: InstalledServer, val action: String)
    private data class InstallSelection(
        val credentials: SshCredentials,
        val options: InstallOptions,
        val identity: SshHostIdentity,
    )
    private class Attempt(
        val credentialsHash: ByteArray,
        val optionsHash: ByteArray,
        val identity: SshHostIdentity,
        val operation: InstallOperation,
    ) {
        fun clear() {
            credentialsHash.fill(0)
            optionsHash.fill(0)
        }
    }

    fun load() = synchronized(lock) {
        startTask("Не удалось открыть защищённое хранилище серверов. Данные не изменены.") { ticket ->
            val (servers, sshOnly) = withContext(Dispatchers.IO) { store.load() to store.loadSshOnly() }
            publish(ticket) { it.copy(servers = servers, sshOnlyServers = sshOnly) }
        }
    }

    private suspend fun prepareSshOnlyCandidate(ticket: Long, credentials: SshCredentials, identity: SshHostIdentity, preflight: ServerPreflight) {
        check(preflight.installationState == PanelInstallationState.ABSENT) { "Отсутствие 3x-UI не подтверждено." }
        val existing = withContext(Dispatchers.IO) { store.loadSshOnly() }
            .firstOrNull { it.identity.host == identity.host && it.identity.port == identity.port }
        checkCurrent(ticket)
        val candidate = SshOnlyServer(existing?.id ?: java.util.UUID.randomUUID().toString(),
            existing?.name ?: credentials.host, credentials, identity, existing?.autoImportStarted ?: false)
        publish(ticket) { it.copy(sshOnlyCandidate = candidate, message = null,
            installer = it.installer.copy(preflight = preflight, hostIdentity = identity, connected = true)) }
    }

    fun acknowledgeSshOnlySaved() = synchronized(lock) {
        mutableState.value = mutableState.value.copy(sshOnlySavedInstall = null)
    }

    fun discardSshOnlyCandidate() = synchronized(lock) {
        if (mutableState.value.busy) return@synchronized
        mutableState.value = mutableState.value.copy(sshOnlyCandidate = null)
    }

    fun confirmSshOnlyCandidate(installNow: Boolean) = synchronized(lock) {
        if (mutableState.value.busy || !canManage()) return@synchronized
        val candidate = mutableState.value.sshOnlyCandidate ?: return@synchronized
        startTask("Не удалось сохранить SSH-подключение. Повторите проверку.") { ticket ->
            checkCurrent(ticket)
            val servers = withContext(Dispatchers.IO) {
                store.upsertSshOnly(candidate)
                store.loadSshOnly()
            }
            publish(ticket) { state ->
                state.copy(sshOnlyCandidate = null, sshOnlyServers = servers, sshOnlySavedInstall = installNow, importMode = if (installNow) false else state.importMode,
                    message = "SSH-подключение сохранено.", installer = state.installer.copy(
                        preflightOpen = installNow, connected = installNow, hostIdentity = if (installNow) candidate.identity else null))
            }
            if (installNow) checkRelease()
        }
    }

    fun removeSshOnly(id: String) = synchronized(lock) {
        if (mutableState.value.busy || !canManage()) return@synchronized
        val server = mutableState.value.sshOnlyServers.singleOrNull { it.id == id } ?: return@synchronized
        startTask("Не удалось удалить SSH-подключение. Данные сохранены.") { ticket ->
            val remaining = withContext(Dispatchers.IO) {
                store.removeSshOnly(server)
                store.loadSshOnly()
            }
            publish(ticket) { it.copy(sshOnlyServers = remaining, message = "SSH-подключение удалено с устройства.") }
        }
    }

    fun beginSshOnlyInstall(id: String): Boolean = synchronized(lock) {
        if (!canManage() || mutableState.value.busy || installing) return@synchronized false
        val server = mutableState.value.sshOnlyServers.singleOrNull { it.id == id } ?: return@synchronized false
        resetWizard()
        releaseGeneration++
        releaseJob?.cancel()
        releaseJob = null
        release = null
        mutableState.value = mutableState.value.copy(installer = InstallerState(credentials = server.credentials))
        checkRelease()
        connectDraft(server.credentials)
        true
    }

    fun beginInstall() = synchronized(lock) {
        if (mutableState.value.busy || installing) return@synchronized
        resetWizard()
        releaseGeneration++
        releaseJob?.cancel()
        releaseJob = null
        release = null
        mutableState.value = mutableState.value.copy(installer = mutableState.value.installer.copy(availableVersion = null))
        checkRelease()
    }

    fun beginImport() = synchronized(lock) {
        if (mutableState.value.busy || installing) return@synchronized
        resetWizard()
        mutableState.value = mutableState.value.copy(importMode = true)
    }

    fun checkRelease() = synchronized(lock) {
        if (releaseJob?.isActive == true) return@synchronized
        val requestGeneration = ++releaseGeneration
        mutableState.value = mutableState.value.copy(installer = mutableState.value.installer.copy(versionLoading = true, versionError = null))
        releaseJob = scope.launch {
            try {
                val latest = ThreeXReleases.latest()
                synchronized(lock) {
                    if (requestGeneration != releaseGeneration) return@synchronized
                    release = latest
                    mutableState.value = mutableState.value.copy(installer = mutableState.value.installer.copy(
                        availableVersion = latest.version, versionLoading = false, versionError = null))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) {
                    if (requestGeneration != releaseGeneration) return@synchronized
                    release = null
                    mutableState.value = mutableState.value.copy(installer = mutableState.value.installer.copy(
                        availableVersion = null, versionLoading = false, versionError = "Не удалось проверить актуальный релиз. Проверьте интернет и повторите."))
                }
            }
        }
    }

    private fun resetWizard() {
        interruptLocked()
        clearDialogs()
        disconnectLocked()
        runner = ThreeXInstaller(connection)
        maintenance = ThreeXMaintenance(connection)
        verifiedInstall = null
        verifiedPanelName = ""
        attempt?.clear()
        attempt = null
        mutableState.value = mutableState.value.copy(importMode = false, sshOnlyCandidate = null, installer = InstallerState(availableVersion = release?.version), busy = false, error = null, message = null)
    }

    fun updateImportPanelPassword(value: String) = synchronized(lock) {
        if (!mutableState.value.importMode || !draftEditable() || value.length > 128 || value.any { it.isISOControl() }) return@synchronized
        mutableState.value = mutableState.value.copy(installer = mutableState.value.installer.copy(importPanelPassword = value, error = null), error = null)
    }

    fun updateCredential(key: String, value: String) = synchronized(lock) {
        if (!draftEditable()) return@synchronized
        val current = mutableState.value.installer.credentials
        val updated = when (key) {
            "host" -> current.copy(host = value)
            "port" -> current.copy(port = value)
            "username" -> current.copy(username = value)
            "password" -> current.copy(password = value)
            "privateKey" -> current.copy(privateKey = value)
            "keyPassphrase" -> current.copy(keyPassphrase = value)
            else -> return@synchronized
        }
        if (updated == current) return@synchronized
        disconnectLocked()
        clearInstallConfirmation()
        mutableState.value = mutableState.value.copy(
            installer = mutableState.value.installer.copy(
                credentials = updated, connected = false, preflight = null, hostIdentity = null, error = null,
            ),
            hostTrust = null, error = null, message = null,
        )
        pendingTrustCredentials = null
    }

    fun setAuthMethod(method: SshAuthMethod) = synchronized(lock) {
        if (!draftEditable() || mutableState.value.installer.credentials.authMethod == method) return@synchronized
        disconnectLocked()
        clearInstallConfirmation()
        pendingTrustCredentials = null
        mutableState.value = mutableState.value.copy(
            installer = mutableState.value.installer.copy(
                credentials = mutableState.value.installer.credentials.copy(
                    authMethod = method, password = "", privateKey = "", keyPassphrase = "",
                ),
                connected = false, preflight = null, hostIdentity = null, error = null,
            ),
            hostTrust = null, error = null, message = null,
        )
    }

    fun updateOption(key: String, value: String) = synchronized(lock) {
        if (!draftEditable()) return@synchronized
        val current = mutableState.value.installer.options
        val updated = when (key) {
            "operation" -> {
                val selected = InstallOperation.entries.firstOrNull { it.name == value } ?: return@synchronized
                val existing = mutableState.value.installer.preflight?.existingInstallation == true
                if ((selected == InstallOperation.FRESH) == existing || pendingJobId != null) return@synchronized
                if (selected == InstallOperation.UPDATE && mutableState.value.installer.preflight?.installedVersion == release?.version) return@synchronized
                current.copy(operation = selected)
            }
            "panelName" -> current.copy(panelName = value)
            "panelUsername" -> current.copy(panelUsername = value)
            "panelPassword" -> current.copy(panelPassword = value)
            "panelPort" -> current.copy(panelPort = value)
            "secretPath" -> current.copy(secretPath = value)
            "domain" -> current.copy(domain = value)
            "acmeEmail" -> current.copy(acmeEmail = value)
            "rememberSsh" -> when (value) {
                "true" -> current.copy(rememberSsh = true)
                "false" -> current.copy(rememberSsh = false)
                else -> return@synchronized
            }
            "sslMode" -> when (value) {
                "DOMAIN" -> current.copy(sslMode = SslMode.DOMAIN)
                "IP" -> current.copy(sslMode = SslMode.IP)
                else -> return@synchronized
            }
            "generate:username" -> current.copy(panelUsername = InstallOptions.create().panelUsername)
            "generate:password" -> current.copy(panelPassword = InstallOptions.create().panelPassword)
            "generate:path" -> current.copy(secretPath = InstallOptions.create().secretPath)
            else -> return@synchronized
        }
        clearInstallConfirmation()
        mutableState.value = mutableState.value.copy(
            installer = mutableState.value.installer.copy(options = updated, error = null), error = null, message = null,
        )
    }

    fun setSslMode(mode: SslMode) = updateOption("sslMode", mode.name)

    fun rememberSsh(remember: Boolean) = updateOption("rememberSsh", remember.toString())

    fun connect() = synchronized(lock) {
        if (!draftEditable()) return@synchronized
        val credentials = try {
            val draft = mutableState.value.installer.credentials
            draft.copy(host = draft.host.trim(), port = draft.port.trim(), username = draft.username.trim()).validate()
        } catch (invalid: IllegalArgumentException) {
            draftError(invalid.message ?: "Проверьте адрес, порт, логин и выбранные данные доступа SSH.")
            return@synchronized
        }
        clearInstallConfirmation()
        pendingTrustCredentials = null
        mutableState.value = mutableState.value.copy(
            hostTrust = null, error = null, message = null,
            installer = mutableState.value.installer.copy(credentials = credentials, error = null),
        )
        connectDraft(credentials)
    }

    fun trustHost() = synchronized(lock) {
        if (mutableState.value.busy || installing) return@synchronized
        val identity = mutableState.value.hostTrust ?: return@synchronized
        val credentials = pendingTrustCredentials ?: return@synchronized
        if (credentials != mutableState.value.installer.credentials ||
            credentials.host != identity.host || credentials.port.toIntOrNull() != identity.port) {
            dismissTrust()
            return@synchronized
        }
        pendingTrustCredentials = null
        mutableState.value = mutableState.value.copy(hostTrust = null)
        startTask("Не удалось сохранить доверие к ключу SSH. Данные не сброшены; подключение не продолжено.") { ticket ->
            withContext(Dispatchers.IO) { store.trustHost(identity) }
            checkCurrent(ticket)
            connectDraftNow(ticket, credentials)
        }
    }

    fun dismissTrust() = synchronized(lock) {
        if (mutableState.value.busy) return@synchronized
        pendingTrustCredentials = null
        mutableState.value = mutableState.value.copy(hostTrust = null)
    }

    fun prepareOptions() = synchronized(lock) {
        if (!draftEditable()) return@synchronized
        val draft = mutableState.value.installer
        if (!connection.connected || !draft.connected || draft.preflight?.ready != true || sessionIdentity == null) {
            draftError("Сначала подключитесь по SSH и пройдите проверку root, ОС и конфликтов.")
            return@synchronized
        }
        val resolvedOptions = if (pendingJobId == null) {
            draft.options.forConnectedServer(connection.connectedServerAddress)
        } else draft.options
        mutableState.value = mutableState.value.copy(
            installer = draft.copy(step = InstallStep.OPTIONS, options = resolvedOptions, preflightOpen = false, error = null), error = null, message = null,
        )
    }

    fun cancelPreflight() = synchronized(lock) {
        if (mutableState.value.busy) return@synchronized
        disconnectLocked()
        mutableState.value = mutableState.value.copy(installer = mutableState.value.installer.copy(
            preflightOpen = false, preflight = null, connected = false, error = null), message = null, error = null)
    }

    fun requestInstall() = synchronized(lock) {
        if (mutableState.value.busy || installing || mutableState.value.installer.step == InstallStep.COMPLETE) return@synchronized
        if (!isOnline()) {
            draftError("Для установки или переустановки 3x-UI требуется подключение к интернету. Операция не добавлена в очередь.")
            return@synchronized
        }
        clearDialogs()
        if (verifiedInstall != null) {
            mutableState.value = mutableState.value.copy(
                confirmation = ServerConfirmation(
                    verifiedInstall?.panelId, SAVE_RESULT, "Сохранить результат установки",
                    "Установка уже проверена. Будут повторены только защищённое локальное сохранение и добавление панели в 3xBoard. Команды установки не отправляются.",
                ),
                error = null,
            )
            return@synchronized
        }
        val draft = mutableState.value.installer
        val credentials: SshCredentials
        val options: InstallOptions
        try {
            credentials = draft.credentials.validate()
            options = (if (pendingJobId == null) draft.options.forConnectedServer(connection.connectedServerAddress) else draft.options).validate()
        } catch (invalid: IllegalArgumentException) {
            draftError(invalid.message ?: "Проверьте параметры выбранной операции.")
            return@synchronized
        }
        val identity = draft.hostIdentity
        if (identity == null || credentials.host != identity.host || credentials.port.toInt() != identity.port) {
            draftError("Повторно проверьте подключение и ключ SSH.")
            return@synchronized
        }
        val selection = InstallSelection(credentials, options, identity)
        val pending = pendingJobId != null
        if (!pending && release == null) {
            draftError("Сначала проверьте актуальную версию 3x-UI.")
            checkRelease()
            return@synchronized
        }
        if (!pending && ((options.operation == InstallOperation.FRESH) == (draft.preflight?.existingInstallation == true))) {
            draftError("Для существующей 3x-UI выберите обновление или переустановку с сохранением данных. Новая установка поверх существующей запрещена.")
            return@synchronized
        }
        if (pending) {
            if (!matchesAttempt(selection)) {
                draftError("Для продолжения нужны исходные данные SSH и все исходные параметры установки. Новая установка не запускается.")
                return@synchronized
            }
        } else if (!connection.connected || !draft.connected || draft.preflight?.ready != true ||
            !sameIdentity(sessionIdentity, selection.identity)) {
            draftError("Сначала выполните подключение и успешную предварительную проверку сервера.")
            return@synchronized
        }
        if (!pending && options.operation == InstallOperation.FRESH && draft.preflight?.checkedPanelPort != options.panelPort.toInt()) {
            startTask("SSH-вход подтверждён, но проверка выбранного порта не завершилась. Установка не запускалась.") { ticket ->
                val report = connection.preflight(options.panelPort.toInt(), needsHttpChallenge = true)
                publish(ticket) { it.copy(installer = it.installer.copy(preflight = report),
                    message = "Проверка выбранного порта завершена. Предупреждения не запрещают продолжение.") }
                if (!report.ready || report.existingInstallation) {
                    publish(ticket) { it.copy(error = if (report.existingInstallation) "Обнаружена существующая 3x-UI. Вернитесь к подключению и выберите обслуживание; новая установка не запускалась." else report.conflicts.joinToString("\n")) }
                } else {
                    synchronized(lock) {
                        requireTicket(ticket)
                        offerInstallConfirmation(selection, false)
                    }
                }
            }
            return@synchronized
        }
        offerInstallConfirmation(selection, pending)
    }

    private fun offerInstallConfirmation(selection: InstallSelection, pending: Boolean) {
        val draft = mutableState.value.installer
        confirmedInstall = selection
        mutableState.value = mutableState.value.copy(
            confirmation = ServerConfirmation(
                null, if (pending) RESUME_INSTALL else INSTALL,
                if (pending) "Проверить результат установки" else when (selection.options.operation) {
                    InstallOperation.FRESH -> "Установить 3x-UI с правами root"
                    InstallOperation.UPDATE -> "Обновить 3x-UI с сохранением данных"
                    InstallOperation.REINSTALL -> "Переустановить программу без сброса данных"
                },
                if (pending) {
                    "Предыдущая задача уже отправлена на VPS. Будет проверен её результат без повторной отправки команды установки."
                } else if (selection.options.operation != InstallOperation.FRESH) {
                    "Перед заменой программных файлов будет создана резервная копия. " +
                        (if (selection.options.operation == InstallOperation.REINSTALL) "Текущая версия сохранится. " else "Версия будет обновлена до ${release?.version}. ") +
                        "Настройки и данные сохранятся; панель будет запущена и добавлена в 3xBoard. Возможен перерыв VPN."
                } else {
                    "Установить 3x-UI и xRay на выбранном VPS?\nSSL: ${selection.options.certificateTarget}. Порт панели: ${selection.options.panelPort}. " +
                        "Для SSL откройте порт 80 извне; DNS и firewall настраиваются отдельно." +
                        (if (draft.preflight?.warnings.orEmpty().isEmpty()) "" else "\n\nПредупреждения:\n" + draft.preflight?.warnings.orEmpty().joinToString("\n"))
                },
            ),
            installer = draft.copy(credentials = selection.credentials, options = selection.options, error = null),
            error = null, message = null,
        )
    }

    fun confirm() = synchronized(lock) {
        if (mutableState.value.busy || installing) return@synchronized
        val confirmation = mutableState.value.confirmation ?: return@synchronized
        mutableState.value = mutableState.value.copy(confirmation = null, error = null, message = null)
        when (confirmation.action) {
            INSTALL, RESUME_INSTALL -> {
                if (!isOnline()) {
                    confirmedInstall = null
                    draftError("Для установки или переустановки 3x-UI требуется подключение к интернету. Операция не добавлена в очередь.")
                    return@synchronized
                }
                val selection = confirmedInstall ?: return@synchronized
                confirmedInstall = null
                if (selection.credentials != mutableState.value.installer.credentials ||
                    selection.options != mutableState.value.installer.options ||
                    (confirmation.action == RESUME_INSTALL && !matchesAttempt(selection)) ||
                    (confirmation.action == INSTALL && pendingJobId != null)) {
                    draftError("Параметры изменились. Повторно проверьте и подтвердите действие.")
                    return@synchronized
                }
                install(selection, confirmation.action == RESUME_INSTALL)
            }
            SAVE_RESULT -> {
                if (verifiedInstall == null) return@synchronized
                startTask("Проверенный результат пока не добавлен в 3xBoard. Повторите только сохранение, не установку.", true) { ticket ->
                    saveVerified(ticket)
                }
            }
            else -> {
                val selected = pendingAction ?: return@synchronized
                if (selected.action != confirmation.action || selected.server.panelId != confirmation.panelId ||
                    currentServer(selected.server.panelId) != selected.server) {
                    pendingAction = null
                    reportError("Запись сервера изменилась. Откройте управление и подтвердите действие заново.")
                    return@synchronized
                }
                when (selected.action) {
                    "RENAME" -> {
                        pendingAction = null
                        mutableState.value = mutableState.value.copy(renamePanelId = selected.server.panelId)
                    }
                    "SHOW_PANEL_SETTINGS" -> {
                        pendingAction = null
                        mutableState.value = mutableState.value.copy(credentialsPanelId = selected.server.panelId)
                    }
                    "REMOVE_LOCAL" -> {
                        pendingAction = null
                        startTask("Не удалось удалить сервер из 3xBoard.") { ticket ->
                            withContext(NonCancellable) {
                                withRemoteRemoval(selected.server.panelId, false) { removeLocal(ticket, selected.server) }
                            }
                        }
                    }
                    else -> connectAction(selected)
                }
            }
        }
    }

    fun dismissConfirmation() = synchronized(lock) {
        if (mutableState.value.busy || installing) return@synchronized
        confirmedInstall = null
        pendingAction = null
        mutableState.value = mutableState.value.copy(confirmation = null)
    }

    fun openControls(panelId: String) = synchronized(lock) {
        if (mutableState.value.busy || installing || currentServer(panelId) == null) return@synchronized
        clearDialogs()
        mutableState.value = mutableState.value.copy(controlPanelId = panelId, error = null, message = null)
    }

    fun closeControls() = synchronized(lock) {
        if (mutableState.value.busy || installing) return@synchronized
        clearDialogs()
        mutableState.value = mutableState.value.copy(controlPanelId = null)
    }

    internal suspend fun checkSshOnlyPanels() {
        val servers = state.value.sshOnlyServers
        for (server in servers) {
            if (!canManage() || !isOnline()) return
            val ssh = SshConnection(store)
            val status = try {
                connectSshOnly(server, ssh)
                ssh.preflight().installationState
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PanelInstallationState.UNKNOWN }
            finally { ssh.close() }
            val host = server.identity.host.let { if (':' in it) "[$it]" else it }
            val location = sshLocationResolver.resolve("https://$host/")
            synchronized(lock) {
                if (mutableState.value.sshOnlyServers.any { it == server }) {
                    val locations = mutableState.value.sshOnlyLocations
                    mutableState.value = mutableState.value.copy(
                        sshOnlyPanelStatus = mutableState.value.sshOnlyPanelStatus + (server.id to status),
                        sshOnlyLocations = if (location != null) locations + (server.id to location) else locations,
                    )
                }
            }
            if (status == PanelInstallationState.PRESENT && !server.autoImportStarted && !state.value.busy) {
                autoImportSshOnly(server)
            }
        }
    }

    private suspend fun autoImportSshOnly(server: SshOnlyServer) {
        val claimed = try { withContext(Dispatchers.IO) { store.claimSshOnlyImport(server) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return }
        synchronized(lock) {
            mutableState.value = mutableState.value.copy(sshOnlyServers = mutableState.value.sshOnlyServers.map {
                if (it == server) claimed else it
            })
        }
        val ssh = SshConnection(store)
        try {
            val identity = connectSshOnly(claimed, ssh)
            check(!state.value.busy && canManage() && isOnline()) { "Сессия занята или недоступна." }
            val found = ThreeXPanelImporter(ssh).discover(claimed.credentials, identity, state.value.servers)
            val existing = state.value.servers.firstOrNull { it.sharesInstallationWith(found) }
            val panel = existing?.copy(token = found.token, credentials = claimed.credentials,
                panelUsername = found.panelUsername.ifBlank { existing.panelUsername },
                panelPassword = found.panelPassword.ifEmpty { existing.panelPassword })
                ?: found.copy(panelId = existingPanelId(found.panelUrl) ?: found.panelId)
            withContext(NonCancellable) {
                val servers = withContext(Dispatchers.IO) {
                    val current = store.load()
                    val updated = current.filterNot { it.panelId == panel.panelId } + panel
                    store.save(updated)
                    updated
                }
                onInstalled(panel, claimed.name)
                val remaining = withContext(Dispatchers.IO) { store.removeSshOnlyAfterPanelSaved(panel) }
                synchronized(lock) {
                    mutableState.value = mutableState.value.copy(servers = servers, sshOnlyServers = remaining,
                        message = "Обнаруженная 3x-UI добавлена в приложение.")
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            synchronized(lock) {
                if (mutableState.value.sshOnlyServers.any { it.id == claimed.id }) {
                    mutableState.value = mutableState.value.copy(error = "Автоматический импорт 3x-UI не подтверждён. Проверьте панель и токены вручную; повторная попытка не запускается автоматически.")
                }
            }
        } finally { ssh.close() }
    }

    internal suspend fun connectSshOnly(server: SshOnlyServer, ssh: SshConnection): SshHostIdentity {
        check(canManage() && isOnline()) { "Для SSH необходимы активная сессия и сеть." }
        val stored = withContext(Dispatchers.IO) { store.loadSshOnly().singleOrNull { it.id == server.id } }
        requireSshOnlyIdentity(server, stored, server.identity)
        val identity = ssh.connect(server.credentials)
        requireSshOnlyIdentity(server, stored, identity)
        check(canManage() && isOnline()) { "Сессия закрыта; команда не отправлена." }
        val current = withContext(Dispatchers.IO) { store.loadSshOnly().singleOrNull { it.id == server.id } }
        check(current == server) { "SSH-подключение изменилось; команда не отправлена." }
        return identity
    }

    fun openSshOnlyConsole(id: String) = synchronized(lock) {
        if (!canManage() || mutableState.value.busy || mutableState.value.sshOnlyServers.none { it.id == id }) return@synchronized
        mutableState.value = mutableState.value.copy(sshOnlyConsoleId = id, consoleOutput = "", error = null)
    }

    fun closeSshOnlyConsole() = synchronized(lock) {
        if (mutableState.value.sshOnlyConsoleId == null) return@synchronized
        if (mutableState.value.busy) { interruptLocked(); disconnectLocked() }
        consoleInput?.cancel(); consoleInput = null
        consoleInterrupts?.cancel(); consoleInterrupts = null
        mutableState.value = mutableState.value.copy(sshOnlyConsoleId = null, consoleActive = false, busy = false,
            consoleOutput = "", error = null, message = "SSH закрыт. Отправленная команда могла продолжить выполнение.")
    }

    fun sendSshOnlyConsole(command: String): Boolean = synchronized(lock) {
        if (!canManage() || !isValidConsoleCommand(command)) return@synchronized false
        if (mutableState.value.consoleActive) return@synchronized consoleInput?.trySend(command + "\n")?.isSuccess == true
        if (mutableState.value.busy || !isOnline()) return@synchronized false
        val server = mutableState.value.sshOnlyServers.singleOrNull { it.id == mutableState.value.sshOnlyConsoleId } ?: return@synchronized false
        startTask("SSH-сессия не подтверждена. Если команда отправлена, она может продолжать выполняться; автоматического повтора нет.") { ticket ->
            val ssh = SshConnection(store)
            val input = kotlinx.coroutines.channels.Channel<String>(CONSOLE_QUEUE_CAPACITY)
            val interrupts = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
            try {
                connectSshOnly(server, ssh)
                val root = ssh.execute("id -u", timeoutMillis = 15_000, maxBytes = 1024)
                check(root.exitCode == 0 && root.stdout.trim() == "0") { "Для консоли требуется root." }
                val scrollback = ConsoleScrollback()
                synchronized(lock) { requireTicket(ticket); consoleInput = input; consoleInterrupts = interrupts }
                ssh.interactiveShell(input, interrupts, onStarted = {
                    synchronized(lock) {
                        requireTicket(ticket)
                        check(input.trySend(command + "\n").isSuccess)
                        mutableState.value = mutableState.value.copy(consoleActive = true)
                    }
                }) { chunk ->
                    scrollback.append(chunk)
                    publish(ticket) { it.copy(consoleOutput = scrollback.toString(), consoleOutputTruncated = scrollback.truncated) }
                }
            } finally {
                input.cancel(); interrupts.cancel(); ssh.close()
                synchronized(lock) {
                    if (consoleInput === input) consoleInput = null
                    if (consoleInterrupts === interrupts) consoleInterrupts = null
                    if (ticket == generation) mutableState.value = mutableState.value.copy(consoleActive = false)
                }
            }
        }
        true
    }

    fun openConsole() = synchronized(lock) {
        if (!canManage() || mutableState.value.busy || installing) return@synchronized
        val panelId = mutableState.value.controlPanelId ?: return@synchronized
        if (currentServer(panelId) == null) return@synchronized
        clearDialogs()
        mutableState.value = mutableState.value.copy(consolePanelId = panelId, consoleOutput = "", consoleOutputTruncated = false, consoleNoOutputNotice = false, error = null, message = null)
    }

    fun closeConsole() = synchronized(lock) {
        if (mutableState.value.consolePanelId == null || installing) return@synchronized
        consoleTimer?.cancel()
        consoleTimer = null
        consoleInput?.cancel()
        consoleInput = null
        val interrupted = mutableState.value.busy
        if (interrupted) {
            interruptLocked()
            disconnectLocked()
        }
        clearDialogs()
        mutableState.value = mutableState.value.copy(consolePanelId = null, consoleOutput = "", consoleOutputTruncated = false, consoleNoOutputNotice = false, consoleActive = false,
            consoleAwaitingOutput = false, consoleDeadlineElapsed = null,
            busy = false, error = null,
            installer = mutableState.value.installer.copy(busy = false),
            message = if (interrupted) "SSH-соединение закрывается. Отправленная команда может продолжить выполняться на сервере; автоматического повтора нет." else null)
    }

    fun dismissConsoleNoOutputNotice() = synchronized(lock) {
        mutableState.value = mutableState.value.copy(consoleNoOutputNotice = false)
    }

    fun sendConsoleCommand(command: String, confirmed: Boolean, encoding: String = "UTF-8"): Boolean = synchronized(lock) {
        if (!canManage() || installing || !confirmed) return@synchronized false
        if ('\u0000' in command) {
            reportError("Ввод содержит NUL.")
            return@synchronized false
        }
        val charset = when (encoding) {
            "UTF-8", "windows-1251", "KOI8-R" -> java.nio.charset.Charset.forName(encoding)
            else -> return@synchronized false
        }
        if (mutableState.value.consoleActive && charset != consoleCharset) return@synchronized false
        if (!charset.newEncoder().canEncode(command)) {
            reportError("Ввод содержит символы, недоступные в выбранной кодировке.")
            return@synchronized false
        }
        if (mutableState.value.consoleActive) {
            val accepted = consoleInput?.trySend(command + "\n")?.isSuccess == true
            if (!accepted) reportError("Предыдущий ввод ещё отправляется. Текст сохранён в поле; повторите отправку позже.")
            else mutableState.value = mutableState.value.copy(error = null, consoleAwaitingOutput = true)
            return@synchronized accepted
        }
        if (mutableState.value.busy) return@synchronized false
        val server = mutableState.value.consolePanelId?.let(::currentServer) ?: return@synchronized false
        if (!isOnline()) {
            reportError("Для отправки команды необходимо подключение к сети. Команда не поставлена в очередь.")
            return@synchronized false
        }
        clearDialogs()
        val selected = SelectedAction(server, "CONSOLE")
        pendingAction = selected
        pendingConsoleCommand = command
        consoleCharset = charset
        mutableState.value = mutableState.value.copy(consoleOutput = "", consoleOutputTruncated = false, consoleNoOutputNotice = false, error = null, message = null)
        connectAction(selected)
        true
    }

    fun consoleInterrupt() = synchronized(lock) {
        if (mutableState.value.consoleActive && consoleInterrupts?.trySend(Unit)?.isSuccess != true) {
            reportError("Не удалось поставить Ctrl-C на отправку: сессия закрывается.")
        }
        Unit
    }

    fun requestAction(action: String) = synchronized(lock) {
        if (mutableState.value.busy || installing) return@synchronized
        val server = mutableState.value.controlPanelId?.let(::currentServer) ?: return@synchronized
        if (action == "UNINSTALL_AND_REMOVE" && server.imported) {
            reportError("Удаление 3x-UI с сервера доступно только если 3x-UI было установлено через 3xBoard.")
            return@synchronized
        }
        if (action == "UNINSTALL_AND_REMOVE" && !isOnline()) {
            reportError("Для удаления 3x-UI требуется подключение к интернету. Операция не добавлена в очередь.")
            return@synchronized
        }
        if (action == "RENAME") {
            clearDialogs()
            mutableState.value = mutableState.value.copy(renamePanelId = server.panelId, error = null, message = null)
            return@synchronized
        }
        if (action == "SHOW_PANEL_SETTINGS") {
            clearDialogs()
            mutableState.value = mutableState.value.copy(credentialsPanelId = server.panelId, error = null, message = null)
            return@synchronized
        }
        val text = when (action) {
            "REBOOT_SERVER" -> "Перезапустить сервер" to
                "SSH, панель и VPN будут временно недоступны."
            "SHOW_PANEL_SETTINGS" -> "Показать настройки панели" to
                "Открыть сохранённые данные доступа?"
            "RESTART_XRAY" -> "Перезагрузить xRay" to
                "VPN-подключения могут прерваться."
            "RENAME" -> "Переименовать сервер" to
                "Название изменится только в 3xBoard."
            "REMOVE_LOCAL" -> "Удалить сервер из 3xBoard" to
                "Запись, данные доступа и несинхронизированные изменения будут удалены из 3xBoard. Панель на VPS продолжит работать."
            "UNINSTALL_AND_REMOVE" -> "Удалить и очистить VPS/VDS" to
                "3x-UI, её инбаунды, клиенты и локальная очередь будут удалены. VPN этой панели отключится. " +
                    "ОС, другие службы и общие сертификаты сохранятся. Резервная копия не создаётся; удаление необратимо."
            else -> {
                reportError("Неизвестное действие сервера.")
                return@synchronized
            }
        }
        clearDialogs()
        pendingAction = SelectedAction(server, action)
        mutableState.value = mutableState.value.copy(
            confirmation = ServerConfirmation(server.panelId, action, text.first, text.second), error = null, message = null,
        )
    }

    fun rename(name: String) = synchronized(lock) {
        if (mutableState.value.busy || installing) return@synchronized
        val panelId = mutableState.value.renamePanelId ?: return@synchronized
        val server = currentServer(panelId) ?: return@synchronized
        val normalized = name.trim()
        try {
            require(normalized.isNotEmpty())
            ServerValidation.text(normalized, 128, "Название сервера")
        } catch (_: Exception) {
            reportError("Введите название от 1 до 128 символов без управляющих и скрытых символов.")
            return@synchronized
        }
        startTask("Не удалось сохранить локальное название сервера. Данные SSH не изменены.") { ticket ->
            checkStored(ticket, server)
            onRename(panelId, normalized)
            checkCurrent(ticket)
            val servers = withContext(Dispatchers.IO) {
                store.load().also { store.save(it) }
            }
            publish(ticket) { it.copy(servers = servers, renamePanelId = null, message = "Название сервера сохранено в 3xBoard.") }
        }
    }

    fun dismissRename() = synchronized(lock) {
        if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(renamePanelId = null)
    }

    fun savePanelCredentials(panelId: String, username: String, password: String) = synchronized(lock) {
        if (mutableState.value.busy || installing || mutableState.value.credentialsPanelId != panelId) return@synchronized
        val server = currentServer(panelId) ?: return@synchronized
        try {
            ServerValidation.text(username, 128, "Логин панели")
            require(username.isNotBlank() && password.isNotEmpty() && password.none { it.isISOControl() } &&
                Charsets.UTF_8.newEncoder().canEncode(password) && password.toByteArray(Charsets.UTF_8).size <= 128)
            if (!server.imported) ServerValidation.segment(username, 1, 64, "Логин панели")
        } catch (_: Exception) {
            reportError("Проверьте логин и пароль панели: они не должны быть пустыми или содержать управляющие символы.")
            return@synchronized
        }
        startTask("Не удалось сохранить данные панели в защищённом хранилище.") { ticket ->
            checkStored(ticket, server)
            val updated = server.copy(panelUsername = username, panelPassword = password)
            val servers = withContext(Dispatchers.IO) {
                val current = store.load()
                check(current.firstOrNull { it.panelId == panelId } == server)
                current.map { if (it.panelId == panelId) updated else it }.also(store::save)
            }
            publish(ticket) { it.copy(servers = servers, message = "Данные входа сохранены в 3xBoard.") }
        }
    }

    fun dismissCredentials() = synchronized(lock) {
        mutableState.value = mutableState.value.copy(credentialsPanelId = null)
    }

    fun reconnect(credentials: SshCredentials) = synchronized(lock) {
        if (mutableState.value.busy || installing) return@synchronized
        val selected = pendingAction ?: return@synchronized
        if (mutableState.value.reconnectPanelId != selected.server.panelId) return@synchronized
        val checked = try {
            credentials.validate().also {
                require(it.host == selected.server.sshHost && it.port.toInt() == selected.server.sshPort)
            }
        } catch (_: Exception) {
            reportError("Укажите корректные данные SSH именно для выбранного адреса и порта. Другой сервер использовать нельзя.")
            return@synchronized
        }
        executeAction(selected, checked)
    }

    fun dismissReconnect() = synchronized(lock) {
        if (mutableState.value.busy) return@synchronized
        val wasConsole = pendingAction?.action == "CONSOLE"
        pendingAction = null
        pendingConsoleCommand = null
        mutableState.value = mutableState.value.copy(reconnectPanelId = null, error = null,
            consoleOutput = if (wasConsole) "Подключение отменено. Команда не отправлена." else mutableState.value.consoleOutput)
    }

    fun cancelWizard(): Boolean = synchronized(lock) {
        if (installing || mutableState.value.installer.progress.running) return@synchronized false
        resetWizard()
        true
    }

    fun suspendSensitiveUi() = synchronized(lock) {
        clearDialogs()
        mutableState.value = mutableState.value.copy(controlPanelId = null, consolePanelId = null, consoleOutput = "", consoleOutputTruncated = false, consoleNoOutputNotice = false)
        if (installing || removingPanelId != null || mutableState.value.installer.progress.running) return@synchronized
        val interrupted = mutableState.value.busy
        interruptLocked()
        disconnectLocked()
        mutableState.value = mutableState.value.copy(
            busy = false,
            installer = mutableState.value.installer.copy(
                credentials = withoutSecrets(mutableState.value.installer.credentials),
                options = mutableState.value.installer.options.copy(panelPassword = ""), importPanelPassword = "",
                credentialsVisible = false, busy = false, connected = false, hostIdentity = null, preflight = null,
            ),
            message = if (interrupted) "SSH закрыт при блокировке. Выполнение ранее отправленной команды не отменялось и автоматически не повторяется." else mutableState.value.message,
        )
    }

    fun close() = synchronized(lock) {
        val wasInstalling = installing
        interruptLocked()
        clearDialogs()
        disconnectLocked()
        mutableState.value = mutableState.value.copy(
            controlPanelId = null, consolePanelId = null, consoleOutput = "", consoleOutputTruncated = false, consoleNoOutputNotice = false,
            installer = mutableState.value.installer.copy(
                credentials = withoutSecrets(mutableState.value.installer.credentials),
                options = mutableState.value.installer.options.copy(panelPassword = ""), importPanelPassword = "",
                busy = false, connected = false, hostIdentity = null, preflight = null, credentialsVisible = false,
                progress = mutableState.value.installer.progress.copy(running = false),
            ),
            busy = false,
            message = if (wasInstalling || pendingJobId != null) {
                "SSH закрыт. Ранее отправленная задача может продолжать работу на VPS; при новом подключении состояние сервера будет проверено заново."
            } else mutableState.value.message,
        )
    }

    private fun connectDraft(credentials: SshCredentials) {
        startTask("Не удалось безопасно подключиться по SSH или завершить предварительную проверку. Проверьте доступ и ключ сервера.") { ticket ->
            connectDraftNow(ticket, credentials)
        }
    }

    private suspend fun connectDraftNow(ticket: Long, credentials: SshCredentials) {
        awaitDisconnect(ticket)
        val pending = pendingJobId != null
        val original = synchronized(lock) { attempt?.identity }
        if (pending) {
            check(original != null && credentials.host == original.host && credentials.port.toInt() == original.port)
        }
        publish(ticket) {
            it.copy(installer = it.installer.copy(credentials = credentials, connected = false, hostIdentity = null, preflight = null))
        }
        if (BuildConfig.DEBUG) Log.d("XBoardSsh", "phase=connect_started auth_method=${credentials.authMethod.name}")
        val identity = try {
            connection.connect(credentials)
        } catch (unknown: UnknownSshHost) {
            if (BuildConfig.DEBUG) Log.d("XBoardSsh", "phase=host_trust_required")
            synchronized(lock) {
                requireTicket(ticket)
                pendingTrustCredentials = credentials
                mutableState.value = mutableState.value.copy(hostTrust = unknown.identity)
            }
            return
        }
        checkCurrent(ticket)
        synchronized(lock) {
            requireTicket(ticket)
            sessionIdentity = identity
        }
        if (pending) check(sameIdentity(original, identity))
        if (BuildConfig.DEBUG) Log.d("XBoardSsh", "phase=authenticated")
        if (mutableState.value.importMode) {
            val report = connection.preflight()
            if (report.installationState == PanelInstallationState.ABSENT) {
                prepareSshOnlyCandidate(ticket, credentials, identity, report)
                return
            }
            if (report.installationState == PanelInstallationState.UNKNOWN) {
                publish(ticket) { it.copy(message = null, installer = it.installer.copy(
                    connected = true, hostIdentity = identity, preflight = report,
                    error = "Не удалось подтвердить наличие или отсутствие 3x-UI. Проверьте права root и состояние сервера; данные не изменены.")) }
                return
            }
            publish(ticket) { it.copy(message = "Обнаружение адреса панели и API-токена…") }
            val discovered = ThreeXPanelImporter(connection).discover(credentials, identity, mutableState.value.servers,
                mutableState.value.installer.importPanelPassword)
            val existing = mutableState.value.servers.firstOrNull { it.sharesInstallationWith(discovered) }
            synchronized(lock) {
                requireTicket(ticket)
                verifiedInstall = existing?.copy(token = discovered.token, credentials = credentials,
                    panelUsername = discovered.panelUsername.ifBlank { existing.panelUsername },
                    panelPassword = discovered.panelPassword.ifEmpty { existing.panelPassword })
                    ?: discovered.copy(panelId = existingPanelId(discovered.panelUrl) ?: discovered.panelId)
                verifiedPanelName = ""
            }
            saveVerified(ticket)
            return
        }
        publish(ticket) {
            it.copy(installer = it.installer.copy(connected = true, hostIdentity = identity),
                message = "Вход по SSH выполнен. Выполняется предварительная проверка VPS.")
        }
        val preflight = try {
            connection.preflight()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw SshConnectionException(SshFailureReason.PREFLIGHT)
        }
        if (BuildConfig.DEBUG) Log.d("XBoardSsh", "phase=preflight_finished root_checked=${preflight.rootChecked} root=${preflight.root} supported=${preflight.supported} ready=${preflight.ready} conflicts=${preflight.conflicts.size}")
        if (pending) {
            publish(ticket) {
                it.copy(installer = it.installer.copy(
                    connected = true, hostIdentity = identity, preflight = preflight, preflightOpen = true, error = null,
                    options = it.installer.options.copy(operation = attempt?.operation ?: it.installer.options.operation),
                    pendingMaintenance = maintenance.pendingJobId != null,
                ), error = null,
                    message = "SSH проверен. Можно проверить результат ранее отправленной задачи или открыть новое подключение.")
            }
        } else {
            publish(ticket) {
                it.copy(installer = it.installer.copy(
                    connected = true, hostIdentity = identity, preflight = preflight, preflightOpen = true,
                     options = it.installer.options.copy(operation = if (preflight.existingInstallation) {
                         if (preflight.installedVersion == "3.10.0" && it.installer.options.operation == InstallOperation.UPDATE) InstallOperation.UPDATE
                         else InstallOperation.REINSTALL
                     } else InstallOperation.FRESH).forConnectedServer(connection.connectedServerAddress),
                    error = if (preflight.ready) null else "Проверка не разрешила продолжение. См. ограничения ОС, root или технические ошибки ниже; данные не удаляются.",
                ), error = null, message = null)
            }
        }
    }

    private fun install(selection: InstallSelection, resume: Boolean) {
        if (!resume) {
            attempt?.clear()
            attempt = Attempt(credentialsHash(selection.credentials), optionsHash(selection.options), selection.identity, selection.options.operation)
        }
        startTask("Установка не подтверждена. Задача могла продолжить работу на VPS; повторная установка автоматически не запускается.", true) { ticket ->
            awaitDisconnect(ticket)
            check(isOnline()) { "Для установки или переустановки 3x-UI требуется подключение к интернету. Операция не добавлена в очередь." }
            val progress: suspend (InstallProgress) -> Unit = { received ->
                publish(ticket) {
                    it.copy(installer = it.installer.copy(
                        step = InstallStep.PROGRESS,
                         progress = received.copy(percent = maxOf(it.installer.progress.percent, received.percent.coerceIn(2, 99))),
                        error = received.error,
                    ))
                }
            }
            if (selection.options.operation != InstallOperation.FRESH) {
                publish(ticket) { it.copy(installer = it.installer.copy(pendingMaintenance = true)) }
                val result = maintenance.run(selection.credentials, selection.identity, selection.options.operation, resume, progress,
                    checkNotNull(release) { "Сначала проверьте доступную версию 3x-UI." })
                if (!result.previouslyActive) {
                    val started = connection.execute("systemctl start x-ui.service", timeoutMillis = 30_000, maxBytes = 4096)
                    check(started.exitCode == 0) { "Не удалось запустить службу панели после установки." }
                }
                val discovered = ThreeXPanelImporter(connection).discover(selection.credentials, selection.identity, mutableState.value.servers)
                val previous = mutableState.value.servers.firstOrNull { it.sharesInstallationWith(discovered) }
                synchronized(lock) {
                    requireTicket(ticket)
                    verifiedInstall = previous?.copy(token = discovered.token, credentials = selection.credentials)
                        ?: discovered.copy(panelId = existingPanelId(discovered.panelUrl) ?: discovered.panelId)
                    verifiedPanelName = selection.options.panelName
                }
                saveVerified(ticket)
                return@startTask
            }
            val server = if (resume) {
                runner.resumeInstall(selection.credentials, selection.identity, selection.options, progress)
            } else {
                runner.install(selection.credentials, selection.identity, selection.options, progress,
                    checkNotNull(release) { "Сначала проверьте доступную версию 3x-UI." })
            }
            synchronized(lock) {
                verifiedInstall = server.copy(credentials = selection.credentials.takeIf { selection.options.rememberSsh })
                verifiedPanelName = selection.options.panelName
                if (ticket == generation) sessionIdentity = selection.identity
            }
            checkCurrent(ticket)
            saveVerified(ticket)
        }
    }

    fun retrySave() = synchronized(lock) {
        if (mutableState.value.busy || verifiedInstall == null) return@synchronized
        startTask("Не удалось сохранить панель. Повторите сохранение.", true) { ticket -> saveVerified(ticket) }
    }

    private suspend fun saveVerified(ticket: Long) {
        val server: InstalledServer
        val name: String
        synchronized(lock) {
            requireTicket(ticket)
            server = checkNotNull(verifiedInstall)
            name = verifiedPanelName
        }
        val config = server.panelConfig(name)
        publish(ticket) { it.copy(installer = it.installer.copy(savingResult = true,
            progress = InstallProgress(99, "Сохранение панели в 3xBoard…", running = true))) }
        val servers = withContext(Dispatchers.IO) {
            val existing = store.load()
            val updated = existing.filterNot { it.panelId == server.panelId } + server
            store.save(updated)
            updated
        }
        publish(ticket) { it.copy(servers = servers) }
        checkCurrent(ticket)
        withContext(NonCancellable) { onInstalled(server, config.name) }
        checkCurrent(ticket)
        val remainingSshOnly = withContext(Dispatchers.IO) { store.removeSshOnlyAfterPanelSaved(server) }
        publish(ticket) { it.copy(sshOnlyServers = remainingSshOnly) }
        checkCurrent(ticket)
        synchronized(lock) {
            requireTicket(ticket)
            verifiedInstall = null
            verifiedPanelName = ""
            attempt?.clear()
            attempt = null
            mutableState.value = mutableState.value.copy(
                installer = mutableState.value.installer.copy(
                    step = InstallStep.COMPLETE,
                    credentials = withoutSecrets(mutableState.value.installer.credentials),
                    installed = server, credentialsVisible = false, error = null, savingResult = false, importPanelPassword = "",
                    progress = InstallProgress(100, "Установка, SSL и доступ проверены; данные сохранены в 3xBoard. ${ThreeXInstaller.API_LIMITATIONS}"),
                ),
                error = null, message = "Сервер установлен и добавлен в 3xBoard.",
            )
        }
    }

    private fun connectAction(selected: SelectedAction) {
        val credentials = selected.server.credentials
        if (credentials == null) {
            mutableState.value = mutableState.value.copy(reconnectPanelId = selected.server.panelId)
        } else if (connection.connected && matchesServer(sessionIdentity, selected.server)) {
            executeAction(selected, null)
        } else {
            executeAction(selected, credentials)
        }
    }

    private fun executeAction(selected: SelectedAction, credentials: SshCredentials?) {
        val failure = if (selected.action == "CONSOLE")
            "Не удалось получить результат команды. Если она уже отправлена, выполнение на сервере могло продолжиться; автоматического повтора нет."
        else "Действие не подтверждено. Проверьте SSH, ключ, права root и состояние панели. Записи сохранены; автоматического повтора нет."
        startTask(failure) { ticket ->
            checkStored(ticket, selected.server)
            awaitDisconnect(ticket)
            if (credentials != null) {
                val checked = credentials.validate()
                require(checked.host == selected.server.sshHost && checked.port.toInt() == selected.server.sshPort)
                val identity = connection.connect(checked)
                check(matchesServer(identity, selected.server))
                synchronized(lock) {
                    requireTicket(ticket)
                    sessionIdentity = identity
                }
            }
            checkCurrent(ticket)
            check(connection.connected && synchronized(lock) { matchesServer(sessionIdentity, selected.server) })
            val consoleCommand = synchronized(lock) {
                requireTicket(ticket)
                check(pendingAction == selected)
                val command = if (selected.action == "CONSOLE") checkNotNull(pendingConsoleCommand) else null
                pendingConsoleCommand = null
                pendingAction = null
                mutableState.value = mutableState.value.copy(reconnectPanelId = null)
                command
            }
            val message = when (selected.action) {
                "CONSOLE" -> {
                    try {
                        val identity = synchronized(lock) { sessionIdentity }
                        if (identity == null || identity.algorithm != selected.server.hostKeyAlgorithm ||
                            identity.keyBase64 != selected.server.hostKeyBase64) throw ConsoleException("Ключ SSH не соответствует выбранному серверу. Команда не отправлена.")
                        val root = connection.execute("id -u", timeoutMillis = 15_000, maxBytes = 1024)
                        if (root.exitCode != 0 || root.stdout.trim() != "0") throw ConsoleException("Для консоли необходимо SSH-подключение с правами root. Команда не отправлена.")
                        val output = ConsoleScrollback()
                        val input = kotlinx.coroutines.channels.Channel<String>(CONSOLE_QUEUE_CAPACITY)
                        val interrupts = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
                        synchronized(lock) {
                            requireTicket(ticket)
                            consoleInput = input
                            consoleInterrupts = interrupts
                        }
                        try {
                            val exit = connection.interactiveShell(input, interrupts, charset = consoleCharset, onStarted = {
                                synchronized(lock) {
                                    requireTicket(ticket)
                                    input.trySend(checkNotNull(consoleCommand) + "\n")
                                    val deadline = android.os.SystemClock.elapsedRealtime() + 300_000L
                                    mutableState.value = mutableState.value.copy(consoleActive = true, consoleAwaitingOutput = true, consoleDeadlineElapsed = deadline)
                                    consoleTimer?.cancel()
                                    consoleTimer = scope.launch {
                                        delay((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                                        synchronized(lock) {
                                            if (ticket == generation && mutableState.value.consoleActive && mutableState.value.consoleDeadlineElapsed == deadline) {
                                                interruptLocked()
                                                disconnectLocked()
                                                clearDialogs()
                                                mutableState.value = mutableState.value.copy(consoleOutput = "", consoleOutputTruncated = false,
                                                    consoleNoOutputNotice = false, consoleActive = false, consoleAwaitingOutput = false,
                                                    consoleDeadlineElapsed = null, busy = false, error = null, message = null,
                                                    installer = mutableState.value.installer.copy(busy = false))
                                            }
                                        }
                                    }
                                }
                            }) { chunk ->
                                output.append(chunk)
                                publish(ticket) { it.copy(consoleOutput = output.toString(), consoleOutputTruncated = output.truncated, consoleAwaitingOutput = false) }
                            }
                            publish(ticket) { it.copy(consoleOutput = output.toString() + "\nСессия завершена (код: $exit).") }
                        } finally {
                            input.cancel()
                            interrupts.cancel()
                            synchronized(lock) {
                                if (consoleInput === input) consoleInput = null
                                if (consoleInterrupts === interrupts) consoleInterrupts = null
                                if (ticket == generation) {
                                    consoleTimer?.cancel()
                                    consoleTimer = null
                                    mutableState.value = mutableState.value.copy(consoleActive = false, consoleAwaitingOutput = false, consoleDeadlineElapsed = null)
                                }
                            }
                        }
                        ""
                    } finally {
                        synchronized(lock) { if (ticket == generation) disconnectLocked() }
                    }
                }
                "REBOOT_SERVER" -> ThreeXInstaller(connection).serverAction(ServerAction.REBOOT_SERVER)
                "RESTART_XRAY" -> ThreeXInstaller(connection).serverAction(ServerAction.RESTART_XRAY)
                "UNINSTALL_AND_REMOVE" -> {
                    check(isOnline()) { "Для удаления 3x-UI требуется подключение к интернету. Операция не добавлена в очередь." }
                    check(!selected.server.imported) { "Удаление 3x-UI с сервера доступно только если 3x-UI было установлено через 3xBoard." }
                    synchronized(lock) {
                        requireTicket(ticket)
                        removingPanelId = selected.server.panelId
                        mutableState.value = mutableState.value.copy(message = "Удаление 3x-UI…")
                    }
                    try {
                        withContext(NonCancellable) {
                            withRemoteRemoval(selected.server.panelId, true) {
                                checkStored(ticket, selected.server)
                                uninstaller.uninstall(selected.server)
                                removeLocal(ticket, selected.server)
                            }
                        }
                    } finally {
                        synchronized(lock) {
                            removingPanelId = null
                            if (ticket == generation) mutableState.value = mutableState.value.copy(message = null)
                        }
                    }
                    "3x-UI удален с сервера. Сервер удален из 3xBoard"
                }
                else -> error("action")
            }
            publish(ticket) { it.copy(reconnectPanelId = null, message = message.takeIf(String::isNotEmpty)) }
            synchronized(lock) {
                requireTicket(ticket)
                pendingAction = null
                if (selected.action != "CONSOLE") disconnectLocked()
            }
        }
    }

    private suspend fun removeLocal(ticket: Long, server: InstalledServer) {
        checkStored(ticket, server)
        withContext(NonCancellable) {
            onRemove(server.panelId)
            val servers = withContext(Dispatchers.IO) {
                val current = store.load()
                check(current.firstOrNull { it.panelId == server.panelId } == server)
                current.filterNot { candidate ->
                    candidate.panelId == server.panelId || (removingPanelId == server.panelId &&
                        candidate.sharesInstallationWith(server))
                }.also { store.save(it) }
            }
            synchronized(lock) {
                mutableState.value = mutableState.value.copy(servers = servers, controlPanelId = null,
                    reconnectPanelId = null, credentialsPanelId = null, renamePanelId = null,
                    message = "Локальная запись сервера удалена из 3xBoard.")
            }
        }
    }

    private suspend fun checkStored(ticket: Long, server: InstalledServer) {
        val stored = withContext(Dispatchers.IO) { store.load().firstOrNull { it.panelId == server.panelId } }
        checkCurrent(ticket)
        check(stored == server)
    }

    private fun startTask(failure: String, installation: Boolean = false, block: suspend (Long) -> Unit) {
        if (!canManage()) {
            reportError("Операция невозможна, повторите попытку позже")
            return
        }
        if (mutableState.value.busy || operation?.isActive == true) return
        val ticket = ++generation
        installing = installation
        mutableState.value = mutableState.value.copy(
            busy = true, error = null, message = null, noticeId = java.util.UUID.randomUUID().toString(),
            installer = mutableState.value.installer.copy(
                busy = true, error = null,
                 progress = if (installation) InstallProgress(percent = 2, message = "Подготовка подключения…", running = true)
                else mutableState.value.installer.progress,
                step = if (installation) InstallStep.PROGRESS else mutableState.value.installer.step,
            ),
        )
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block(ticket)
            } catch (exception: Exception) {
                if (exception is CancellationException) currentCoroutineContext().ensureActive()
                if (BuildConfig.DEBUG) {
                    val reason = when (exception) {
                        is SshConnectionException -> exception.reason.name
                        is ServerStoreException -> "SECURE_STORAGE"
                        else -> "OPERATION_FAILED"
                    }
                    val methods = (exception as? SshConnectionException)?.attemptedMethods.orEmpty().map { it.name }.sorted().joinToString(",")
                    Log.d("XBoardSsh", "phase=operation_failed reason=$reason attempted_methods=[$methods] installation=$installation")
                }
                synchronized(lock) {
                    if (ticket == generation) {
                        val safe = when {
                            verifiedInstall != null -> "Панель установлена, но её сохранение в 3xBoard не завершено. Нажмите «Добавить панель» или вернитесь к подключению."
                            exception is ConsoleException -> exception.message ?: failure
                            exception is UninstallException -> exception.message ?: failure
                            exception is MaintenanceException -> exception.message ?: failure
                            exception is InstallationFailure -> exception.message ?: failure
                            installation && pendingJobId != null -> "Не удалось получить результат установки. Нажмите «Новое подключение» для повторной проверки сервера. Ранее отправленная команда может ещё выполняться на VPS."
                             exception is SshConnectionException -> exception.reason.description
exception is ServerStoreException -> exception.message ?: failure
                              mutableState.value.importMode && (exception is IllegalStateException || exception is IllegalArgumentException) -> exception.message ?: failure
                              else -> failure
                        }
                        mutableState.value = mutableState.value.copy(
                            error = safe, message = null,
                            installer = mutableState.value.installer.copy(
                                connected = connection.connected, error = safe,
                                pendingMaintenance = maintenance.pendingJobId != null,
                                progress = if (installation) mutableState.value.installer.progress.copy(running = false, error = safe)
                                else mutableState.value.installer.progress,
                            ),
                        )
                    }
                }
            } finally {
                synchronized(lock) {
                    if (ticket == generation) {
                        installing = false
                        operation = null
                        mutableState.value = mutableState.value.copy(
                            busy = false,
                            installer = mutableState.value.installer.copy(
                                busy = false, connected = connection.connected,
                                progress = mutableState.value.installer.progress.copy(running = false),
                            ),
                        )
                    }
                }
            }
        }
        operation = job
        job.start()
    }

    private suspend fun awaitDisconnect(ticket: Long) {
        val job = synchronized(lock) { disconnectJob }
        job?.join()
        checkCurrent(ticket)
    }

    private fun disconnectLocked() {
        sessionIdentity = null
        mutableState.value = mutableState.value.copy(
            installer = mutableState.value.installer.copy(connected = false, hostIdentity = null, preflight = null),
        )
        val previous = disconnectJob
        disconnectJob = scope.launch(Dispatchers.IO + NonCancellable) {
            previous?.join()
            connection.close()
        }
    }

    private fun interruptLocked() {
        consoleInput?.cancel()
        consoleInput = null
        mutableState.value = mutableState.value.copy(consoleActive = false)
        generation++
        val previousOperation = operation
        operation = null
        installing = false
        val previousDisconnect = disconnectJob
        disconnectJob = scope.launch(Dispatchers.IO + NonCancellable) {
            previousDisconnect?.join()
            previousOperation?.cancel()
        }
    }

    private suspend fun checkCurrent(ticket: Long) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) { requireTicket(ticket) }
    }

    private fun requireTicket(ticket: Long) {
        if (ticket != generation) throw CancellationException("Операция закрыта.")
    }

    private fun publish(ticket: Long, transform: (ServerManagerState) -> ServerManagerState) = synchronized(lock) {
        requireTicket(ticket)
        mutableState.value = transform(mutableState.value)
    }

    private fun draftEditable(): Boolean = !mutableState.value.busy && !installing &&
        mutableState.value.installer.step != InstallStep.COMPLETE && verifiedInstall == null

    private fun currentServer(panelId: String): InstalledServer? = mutableState.value.servers.firstOrNull { it.panelId == panelId }

    private fun clearInstallConfirmation() {
        confirmedInstall = null
        if (mutableState.value.confirmation?.action in setOf(INSTALL, RESUME_INSTALL, SAVE_RESULT)) {
            mutableState.value = mutableState.value.copy(confirmation = null)
        }
    }

    private fun clearDialogs() {
        confirmedInstall = null
        pendingAction = null
        pendingConsoleCommand = null
        pendingTrustCredentials = null
        mutableState.value = mutableState.value.copy(
            hostTrust = null, confirmation = null, renamePanelId = null, credentialsPanelId = null, reconnectPanelId = null,
            installer = mutableState.value.installer.copy(credentialsVisible = false),
        )
    }

    private fun draftError(message: String) {
        mutableState.value = mutableState.value.copy(error = message, installer = mutableState.value.installer.copy(error = message))
    }

    private fun reportError(message: String) {
        mutableState.value = mutableState.value.copy(error = message, noticeId = java.util.UUID.randomUUID().toString())
    }

    private fun matchesAttempt(selection: InstallSelection): Boolean {
        val original = attempt ?: return false
        val credentials = credentialsHash(selection.credentials)
        val options = optionsHash(selection.options)
        return try {
            MessageDigest.isEqual(original.credentialsHash, credentials) &&
                MessageDigest.isEqual(original.optionsHash, options) && sameIdentity(original.identity, selection.identity)
        } finally {
            credentials.fill(0)
            options.fill(0)
        }
    }

    private fun credentialsHash(credentials: SshCredentials): ByteArray = digest(
        credentials.host, credentials.port, credentials.username, credentials.authMethod.name,
        credentials.password, credentials.privateKey, credentials.keyPassphrase,
    )

    private fun optionsHash(options: InstallOptions): ByteArray = if (options.operation != InstallOperation.FRESH) {
        digest(options.operation.name)
    } else digest(
        options.panelName, options.panelUsername, options.panelPassword, options.panelPort, options.secretPath,
        options.sslMode.name, options.domain, options.publicIp, options.acmeEmail, options.rememberSsh.toString(), options.operation.name,
    )

    private fun digest(vararg values: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            try {
                val size = bytes.size
                digest.update(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
                digest.update(bytes)
            } finally {
                bytes.fill(0)
            }
        }
        return digest.digest()
    }

    private fun withoutSecrets(credentials: SshCredentials): SshCredentials =
        credentials.copy(password = "", privateKey = "", keyPassphrase = "")

    private fun sameIdentity(first: SshHostIdentity?, second: SshHostIdentity?): Boolean = first != null && second != null &&
        first.host == second.host && first.port == second.port

    private fun matchesServer(identity: SshHostIdentity?, server: InstalledServer): Boolean = identity != null &&
        identity.host == server.sshHost && identity.port == server.sshPort

    private companion object {
        const val INSTALL = "INSTALL"
        const val RESUME_INSTALL = "RESUME_INSTALL"
        const val SAVE_RESULT = "SAVE_RESULT"
    }
}
