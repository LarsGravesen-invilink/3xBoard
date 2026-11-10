package app.invilink.xboard.ssh

import android.util.Base64
import androidx.annotation.Keep
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchAlgoNegoFailException
import com.jcraft.jsch.JSchException
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserAuth
import com.jcraft.jsch.UserAuthNone
import com.jcraft.jsch.UserInfo
import com.jcraft.jsch.UIKeyboardInteractive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class SshFailureReason(val description: String) {
    HOST_CHANGED("Ключ SSH действительно отличается от сохранённого. Соединение запрещено. Сверьте отпечаток с консолью провайдера; автоматически заменять ключ нельзя."),
    HOST_ADDRESS("Адрес ключа SSH не соответствует выбранному серверу. Соединение запрещено."),
    HOST_STORAGE("Не удалось прочитать или проверить сохранённый ключ SSH. Данные не сброшены; соединение запрещено."),
    AUTHENTICATION("Сервер отклонил аутентификацию. Проверьте логин, пароль и разрешение входа root по паролю. Поддерживаются password и одношаговый пароль PAM. SSH-ключ, OTP, смена пароля и многофакторная проверка могут требовать другого способа входа."),
    UNSUPPORTED_AUTH_CHALLENGE("Сервер запросил неподдерживаемую проверку. Поддерживается только один скрытый запрос пароля PAM; OTP, смена пароля и многошаговая проверка не поддерживаются. Это не означает, что пароль неверен."),
    ALGORITHMS("Не удалось согласовать современные алгоритмы SSH. Проверьте конфигурацию SSH сервера; небезопасные алгоритмы автоматически не включаются."),
    DNS("Не удалось определить IP-адрес SSH-сервера. Проверьте имя и DNS."),
    NETWORK("SSH-сервер недоступен по указанному адресу и порту. Проверьте порт, сеть и межсетевой экран."),
    TIMEOUT("Истекло время ожидания SSH-соединения. Проверьте доступность сервера и порт."),
    PRIVATE_KEY("Не удалось загрузить закрытый SSH-ключ. Проверьте его формат и пароль ключа."),
    CONNECTION("Не удалось завершить SSH-подключение. Это не подтверждает смену ключа сервера. Проверьте адрес, порт и настройки SSH."),
    PREFLIGHT("SSH-подключение установлено, но предварительная проверка VPS не завершена. Установка не запускалась. Проверьте, что на VPS доступна оболочка sh и разрешено выполнение SSH-команд."),
}

enum class SshAuthenticationAttempt { PASSWORD, KEYBOARD_INTERACTIVE }

class SshConnectionException(
    val reason: SshFailureReason,
    val attemptedMethods: Set<SshAuthenticationAttempt> = emptySet(),
) : IllegalStateException(reason.description)

class UnknownSshHost(val identity: SshHostIdentity) :
    IllegalStateException("Неизвестный ключ SSH. Сверьте отпечаток по независимому каналу и явно подтвердите доверие.")

data class SshCommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
    override fun toString(): String =
        "SshCommandResult(exitCode=$exitCode, stdout=<скрыто>, stderr=<скрыто>)"
}

internal suspend fun readConsoleText(
    input: InputStream,
    read: suspend (java.io.Reader, CharArray) -> Int,
    emit: suspend (String) -> Unit,
) = readConsoleText(input, read, emit, Charsets.UTF_8)

internal suspend fun readConsoleText(
    input: InputStream,
    read: suspend (java.io.Reader, CharArray) -> Int,
    emit: suspend (String) -> Unit,
    charset: java.nio.charset.Charset,
) {
    val chars = CharArray(8192)
    try {
        input.reader(charset).use { reader ->
            while (true) {
                val count = read(reader, chars)
                if (count < 0) break
                if (count > 0) emit(String(chars, 0, count))
            }
        }
    } finally {
        chars.fill('\u0000')
    }
}

internal suspend fun writeConsoleInput(
    input: kotlinx.coroutines.channels.ReceiveChannel<String>,
    interrupts: kotlinx.coroutines.channels.ReceiveChannel<Unit>,
    write: suspend (String) -> Unit,
) {
    while (true) {
        val text = select<String?> {
            interrupts.onReceive { "\u0003" }
            input.onReceiveCatching { it.getOrNull() }
        } ?: return
        var offset = 0
        while (offset < text.length) {
            if (text != "\u0003" && interrupts.tryReceive().isSuccess) write("\u0003")
            var end = minOf(offset + 4096, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            write(text.substring(offset, end))
            offset = end
        }
    }
}

internal fun clearSessionPassword(session: Session) {
    // JSch 0.2.21's setter replaces the array without wiping it.
    runCatching {
        val field = Session::class.java.getDeclaredField("password").apply { isAccessible = true }
        (field.get(session) as? ByteArray)?.fill(0)
    }
    session.setPassword(ByteArray(0))
}

fun shellQuote(value: String): String {
    require(value.length <= 16_384 && '\u0000' !in value) {
        "Аргумент оболочки содержит NUL или превышает 16384 символа."
    }
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

class SshConnection(private val store: ServerStore) : AutoCloseable {
    private val operation = Mutex()
    private val stateLock = Any()
    private var link: Link? = null
    private var generation = 0L

    val connected: Boolean
        get() = synchronized(stateLock) { link?.connected == true }

    val connectedServerAddress: String?
        get() = synchronized(stateLock) { link?.serverAddress }

    suspend fun connect(credentials: SshCredentials): SshHostIdentity = operation.withLock {
        val checked = credentials.validate()
        val attempt = synchronized(stateLock) { generation }
        var connecting: Link? = null
        try {
            withContext(Dispatchers.IO) {
                val client = JSch().apply { setInstanceLogger(SilentLogger) }
                val session = client.getSession(checked.username, checked.host, checked.port.toInt())
                val candidate = Link(session)
                connecting = candidate
                val repository = PinnedHosts(store::knownHost, checked.host, checked.port.toInt())
                val passwordInfo = if (checked.authMethod == SshAuthMethod.PASSWORD) PasswordUserInfo(checked.password, checked.username) else null
                try {
                    currentCoroutineContext().ensureActive()
                    val previous = synchronized(stateLock) {
                        check(generation == attempt) { "Подключение SSH отменено закрытием соединения." }
                        link.also { link = candidate }
                    }
                    previous?.shutdown()
                    session.setLogger(SilentLogger)
                    session.setHostKeyRepository(repository)
                    session.setSocketFactory(candidate.socketFactory)
                    session.setDaemonThread(true)
                    session.setUserInfo(passwordInfo)
                    session.setConfig("StrictHostKeyChecking", "yes")
                    session.setConfig("PreferredAuthentications", if (checked.authMethod == SshAuthMethod.PASSWORD) "password,keyboard-interactive" else "publickey")
                    session.setConfig("MaxAuthTries", if (passwordInfo != null) "2" else "1")
                    session.setConfig("enable_auth_none", if (passwordInfo != null) "yes" else "no")
                    if (passwordInfo != null) {
                        session.setConfig("userauth.none", PasswordAuthDiscovery::class.java.name)
                        session.setConfig("userauth.password", PasswordAuthentication::class.java.name)
                        session.setConfig("userauth.keyboard-interactive", PasswordKeyboardInteractive::class.java.name)
                    }
                    session.setConfig("try_additional_pubkey_algorithms", "no")
                    session.setConfig("compression.c2s", "none")
                    session.setConfig("compression.s2c", "none")
                    session.setConfig("xdh", com.jcraft.jsch.bc.XDH::class.java.name)
                    session.setConfig("ssh-ed25519", com.jcraft.jsch.bc.SignatureEd25519::class.java.name)
                    session.setConfig("kex", KEX)
                    session.setConfig("server_host_key", SIGNATURES)
                    session.setConfig("PubkeyAcceptedAlgorithms", SIGNATURES)
                    session.setConfig("cipher.c2s", CIPHERS)
                    session.setConfig("cipher.s2c", CIPHERS)
                    session.setConfig("mac.c2s", MACS)
                    session.setConfig("mac.s2c", MACS)
                    withTimeout(AUTH_TIMEOUT.toLong()) {
                        blocking({ disconnect(candidate) }) {
                            candidate.requireOpen()
                            when (checked.authMethod) {
                                SshAuthMethod.PASSWORD -> {
                                    val password = checked.password.toByteArray(Charsets.UTF_8)
                                    try {
                                        session.setPassword(password)
                                    } finally {
                                        password.fill(0)
                                    }
                                }
                                SshAuthMethod.PRIVATE_KEY -> {
                                    val key = checked.privateKey.toByteArray(Charsets.UTF_8)
                                    val passphrase = checked.keyPassphrase.toByteArray(Charsets.UTF_8)
                                    try {
                                        client.addIdentity("xboard-memory-key", key, null, passphrase.takeIf { it.isNotEmpty() })
                                    } finally {
                                        key.fill(0)
                                        passphrase.fill(0)
                                    }
                                }
                            }
                            candidate.requireOpen()
                            session.connect(AUTH_TIMEOUT)
                            candidate.requireOpen()
                            check(repository.failure == null) { "Проверка ключа SSH не завершена." }
                            val identity = repository.verified
                                ?: throw IllegalStateException("Сервер SSH не прошёл проверку ключа.")
                            session.setServerAliveInterval(15_000)
                            session.setServerAliveCountMax(3)
                            candidate.authenticated = true
                            identity
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    disconnect(candidate)
                    throw SshConnectionException(SshFailureReason.TIMEOUT, passwordInfo?.attemptedMethods().orEmpty())
                } catch (e: CancellationException) {
                    disconnect(candidate)
                    throw e
                } catch (failure: Exception) {
                    disconnect(candidate)
                    throw repository.failure ?: connectionFailure(failure, repository.verified != null, passwordInfo)
                } finally {
                    passwordInfo?.clear()
                    session.setUserInfo(null)
                    // Cancellation may happen before JSch enters connect's own cleanup.
                    clearSessionPassword(session)
                    runCatching { client.removeAllIdentity() }
                }
            }
        } catch (e: CancellationException) {
            connecting?.let(::disconnect)
            throw e
        }
    }

    suspend fun execute(
        command: String,
        timeoutMillis: Long = 30_000,
        maxBytes: Int = 1_048_576,
    ): SshCommandResult = executeBuffered(command, null, timeoutMillis, maxBytes)

    suspend fun executeInput(
        command: String,
        input: ByteArray,
        timeoutMillis: Long = 30_000,
        maxBytes: Int = 1_048_576,
    ): SshCommandResult = executeBuffered(command, input, timeoutMillis, maxBytes)

    private suspend fun executeBuffered(
        command: String,
        input: ByteArray?,
        timeoutMillis: Long,
        maxBytes: Int,
    ): SshCommandResult {
        require(maxBytes in 1..16_777_216) { "Лимит вывода должен быть от 1 байта до 16 МиБ на поток." }
        return runCommand(command, input, timeoutMillis) { active, channel, stdout, stderr, stdin ->
            coroutineScope {
                val out = async { blocking({ disconnect(active) }) { readBounded(stdout, maxBytes) } }
                val err = async { blocking({ disconnect(active) }) { readBounded(stderr, maxBytes) } }
                blocking({ disconnect(active) }) { channel.connect(CONNECT_TIMEOUT) }
                val writer = async { writeInput(active, stdin, input) }
                writer.await()
                val output = out.await()
                val errors = err.await()
                val exit = awaitExit(active, channel)
                SshCommandResult(exit, output, errors)
            }
        }
    }

    suspend fun stream(
        command: String,
        input: ByteArray? = null,
        timeoutMillis: Long = 1_800_000,
        onLine: suspend (String) -> Unit,
    ): Int = runCommand(command, input, timeoutMillis) { active, channel, stdout, stderr, stdin ->
        coroutineScope {
            val lines = kotlinx.coroutines.channels.Channel<String>(64)
            val out = launch { readLines(active, stdout) { lines.send(it) } }
            val err = launch { readLines(active, stderr) { lines.send(it) } }
            val completion = launch {
                out.join()
                err.join()
                lines.close()
            }
            var count = 0
            try {
                blocking({ disconnect(active) }) { channel.connect(CONNECT_TIMEOUT) }
                val writer = async { writeInput(active, stdin, input) }
                for (line in lines) {
                    check(++count <= 20_000) { "Превышен лимит 20000 строк вывода SSH." }
                    onLine(line)
                }
                writer.await()
                completion.join()
                awaitExit(active, channel)
            } finally {
                lines.cancel()
            }
        }
    }

    internal suspend fun interactiveShell(
        input: kotlinx.coroutines.channels.ReceiveChannel<String>,
        interrupts: kotlinx.coroutines.channels.ReceiveChannel<Unit>,
        onStarted: () -> Unit,
        charset: java.nio.charset.Charset = Charsets.UTF_8,
        onChunk: suspend (String) -> Unit,
    ): Int = operation.withLock {
        withContext(Dispatchers.IO) {
            val active = synchronized(stateLock) { link?.takeIf { it.connected } }
                ?: throw IllegalStateException("Нет проверенного SSH-соединения.")
            var shell: ChannelShell? = null
            try {
                active.requireOpen()
                val channel = active.session.openChannel("shell") as ChannelShell
                shell = channel
                active.attachChannel(channel)
                channel.setPty(true)
                channel.setPtyType("dumb")
                channel.setAgentForwarding(false)
                channel.setXForwarding(false)
                val stdout = channel.inputStream
                val stdin = channel.outputStream
                blocking({ disconnect(active) }) { channel.connect(CONNECT_TIMEOUT) }
                coroutineScope {
                    val writer = launch {
                        writeConsoleInput(input, interrupts) { text ->
                            val bytes = text.toByteArray(charset)
                            try {
                                blocking({ disconnect(active) }) { stdin.write(bytes); stdin.flush() }
                            } finally { bytes.fill(0) }
                        }
                    }
                    try {
                        onStarted()
                        readConsoleText(stdout, { reader, chars ->
                            blocking({ disconnect(active) }) { reader.read(chars) }
                        }, onChunk, charset)
                    } finally { writer.cancel() }
                }
                channel.exitStatus
            } catch (e: CancellationException) {
                disconnect(active)
                throw e
            } finally {
                shell?.let { runCatching { it.disconnect() }; active.detachChannel(it) }
            }
        }
    }

    internal suspend fun streamConsole(
        command: String,
        timeoutMillis: Long = 120_000,
        onStarted: () -> Unit = {},
        onChunk: suspend (String) -> Unit,
    ): Int = runCommand(command, null, timeoutMillis) { active, channel, stdout, stderr, stdin ->
        coroutineScope {
            val chunks = kotlinx.coroutines.channels.Channel<String>(64)
            val out = launch { readConsoleChunks(active, stdout) { chunks.send(it) } }
            val err = launch { readConsoleChunks(active, stderr) { chunks.send(it) } }
            val completion = launch {
                out.join()
                err.join()
                chunks.close()
            }
            try {
                blocking({ disconnect(active) }) { channel.connect(CONNECT_TIMEOUT) }
                onStarted()
                val writer = async { writeInput(active, stdin, null) }
                for (chunk in chunks) onChunk(chunk)
                writer.await()
                completion.join()
                awaitExit(active, channel)
            } finally {
                chunks.cancel()
            }
        }
    }

    private suspend fun readConsoleChunks(active: Link, input: InputStream, emit: suspend (String) -> Unit) {
        readConsoleText(input, { reader, chars -> blocking({ disconnect(active) }) { reader.read(chars) } }, emit)
    }

    suspend fun preflight(panelPort: Int? = null, needsHttpChallenge: Boolean = true): ServerPreflight =
        runSshPreflight(this, panelPort, needsHttpChallenge)

    override fun close() {
        val previous = synchronized(stateLock) {
            generation++
            link.also { link = null }
        }
        previous?.shutdown()
    }

    private fun disconnect(active: Link) {
        synchronized(stateLock) { if (link === active) link = null }
        active.shutdown()
    }

    private suspend fun <T> runCommand(
        command: String,
        input: ByteArray?,
        timeoutMillis: Long,
        body: suspend (Link, ChannelExec, InputStream, InputStream, OutputStream) -> T,
    ): T {
        require(command.isNotBlank() && command.length <= 65_536 && '\u0000' !in command) {
            "Команда SSH пуста, содержит NUL или превышает 65536 символов."
        }
        require(timeoutMillis in 1L..7_200_000L) { "Тайм-аут SSH должен быть от 1 мс до 2 часов." }
        require(input == null || input.size <= 4_194_304) { "Ввод SSH превышает 4 МиБ." }
        var executing: Link? = null
        try {
            return withTimeout(timeoutMillis) {
                operation.withLock {
                    withContext(Dispatchers.IO) {
                        val active = synchronized(stateLock) {
                            link?.takeIf { it.connected }
                        } ?: throw IllegalStateException("Нет проверенного аутентифицированного SSH-соединения.")
                        executing = active
                        var channel: ChannelExec? = null
                        try {
                            currentCoroutineContext().ensureActive()
                            active.requireOpen()
                            val exec = active.session.openChannel("exec") as ChannelExec
                            channel = exec
                            active.attachChannel(exec)
                            exec.setPty(false)
                            exec.setAgentForwarding(false)
                            exec.setXForwarding(false)
                            exec.setCommand(command)
                            exec.setInputStream(null)
                            val stdout = exec.inputStream
                            val stderr = exec.errStream
                            val stdin = exec.outputStream
                            body(active, exec, stdout, stderr, stdin)
                        } catch (e: CancellationException) {
                            disconnect(active)
                            throw e
                        } catch (_: Exception) {
                            disconnect(active)
                            throw IllegalStateException(
                                "Выполнение SSH не подтверждено: соединение прервано, превышен лимит или произошла ошибка. Команда не повторялась."
                            )
                        } finally {
                            channel?.let { exec ->
                                runCatching { exec.disconnect() }
                                active.detachChannel(exec)
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            executing?.let(::disconnect)
            throw e
        }
    }

    private suspend fun writeInput(active: Link, output: OutputStream, input: ByteArray?) {
        blocking({ disconnect(active) }) {
            active.requireOpen()
            output.use {
                if (input != null) {
                    var offset = 0
                    while (offset < input.size) {
                        active.requireOpen()
                        val size = minOf(8192, input.size - offset)
                        it.write(input, offset, size)
                        offset += size
                    }
                    it.flush()
                }
            }
        }
    }

    private suspend fun awaitExit(active: Link, channel: ChannelExec): Int {
        while (!channel.isClosed) {
            check(active.connected && channel.isConnected) { "SSH-соединение прервано до получения статуса." }
            delay(20)
        }
        return channel.exitStatus.also {
            check(it >= 0) { "Сервер не подтвердил код завершения SSH-команды." }
        }
    }

    private fun readBounded(input: InputStream, limit: Int): String {
        val bytes = ByteArray(8192)
        val output = WipingBuffer()
        try {
            input.use {
                while (true) {
                    val count = it.read(bytes)
                    if (count < 0) break
                    check(count <= limit - output.size()) { "Превышен лимит вывода SSH." }
                    output.write(bytes, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            bytes.fill(0)
            output.wipe()
        }
    }

    private suspend fun readLines(active: Link, input: InputStream, emit: suspend (String) -> Unit) {
        val chars = CharArray(2048)
        val line = StringBuilder()
        var afterCr = false
        var count = 0
        try {
            input.reader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val size = blocking({ disconnect(active) }) { reader.read(chars) }
                    if (size < 0) break
                    for (index in 0 until size) {
                        val char = chars[index]
                        if (char == '\n' && afterCr) {
                            afterCr = false
                            continue
                        }
                        afterCr = char == '\r'
                        if (char == '\n' || char == '\r') {
                            check(++count <= 20_000) { "Превышен лимит строк SSH." }
                            emit(line.toString())
                            line.setLength(0)
                        } else {
                            check(line.length < 16_384) { "Строка вывода SSH превышает 16384 символа." }
                            line.append(char)
                        }
                    }
                }
                if (line.isNotEmpty()) emit(line.toString())
            }
        } finally {
            chars.fill('\u0000')
            line.setLength(0)
        }
    }

    private class PasswordUserInfo(private var password: String?, username: String) : UserInfo, UIKeyboardInteractive {
        private val passwordPrompt = Regex(
            "[ \\t]*(?:(?iu:password|пароль)|${Regex.escape(username)}(?i:'s password)|(?iu:password for|пароль для) ${Regex.escape(username)})[ \\t]*:?[ \\t]*",
        )
        private val unsupportedContext = Regex(
            "otp|passcode|one[ -]?time|verification|authenticator|token|\\b(?:pin|code|2fa|mfa)\\b|chang|expir|(?:new|old|current)[ \\t]+password|однораз|код|токен|смен|измен|(?:нов|стар|текущ)[а-яё]*[ \\t]+парол|ист[её]к",
            RegexOption.IGNORE_CASE,
        )
        private val attempted = mutableSetOf<SshAuthenticationAttempt>()
        @Volatile var deniedChallenge = false
            private set

        @Synchronized
        fun beginMethod(method: SshAuthenticationAttempt): Boolean = attempted.add(method)

        @Synchronized
        fun attemptedMethods(): Set<SshAuthenticationAttempt> = attempted.toSet()

        override fun getPassword(): String? = null
        override fun getPassphrase(): String? = null
        override fun promptPassword(message: String?): Boolean = false
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) = Unit

        @Synchronized
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String>? {
            if (prompt?.isEmpty() == true && echo?.isEmpty() == true) return emptyArray()
            if (prompt?.size != 1 || echo?.size != 1 || echo[0] ||
                !passwordPrompt.matches(prompt[0]) ||
                unsupportedContext.containsMatchIn(name.orEmpty()) ||
                unsupportedContext.containsMatchIn(instruction.orEmpty())) {
                deniedChallenge = true
                password = null
                return null
            }
            val answer = password ?: run {
                deniedChallenge = true
                return null
            }
            password = null
            return arrayOf(answer)
        }

        @Synchronized
        fun clear() {
            password = null
        }
    }

    @Keep
    class PasswordAuthDiscovery : UserAuthNone() {
        override fun start(session: Session): Boolean {
            if (super.start(session)) throw JSchException("Auth fail")
            return false
        }
    }

    @Keep
    class PasswordAuthentication : UserAuth() {
        override fun start(session: Session): Boolean {
            val info = session.userInfo as? PasswordUserInfo ?: return false
            if (!info.beginMethod(SshAuthenticationAttempt.PASSWORD)) return false
            val authentication = Class.forName("com.jcraft.jsch.UserAuthPassword")
                .getDeclaredConstructor().apply { isAccessible = true }.newInstance() as UserAuth
            return authentication.start(session)
        }
    }

    @Keep
    class PasswordKeyboardInteractive : UserAuth() {
        override fun start(session: Session): Boolean {
            val info = session.userInfo as? PasswordUserInfo ?: return false
            if (!info.beginMethod(SshAuthenticationAttempt.KEYBOARD_INTERACTIVE)) return false
            val password = Session::class.java.getDeclaredField("password").apply { isAccessible = true }
            (password.get(session) as ByteArray?)?.fill(0)
            password.set(session, null)
            val maxTries = Session::class.java.getDeclaredField("max_auth_tries").apply { isAccessible = true }
            val failures = Session::class.java.getDeclaredField("auth_failures").apply { isAccessible = true }
            val previousMax = maxTries.getInt(session)
            maxTries.setInt(session, minOf(previousMax, failures.getInt(session) + 1))
            try {
                val authentication = Class.forName("com.jcraft.jsch.UserAuthKeyboardInteractive")
                    .getDeclaredConstructor().apply { isAccessible = true }.newInstance() as UserAuth
                return authentication.start(session)
            } finally {
                maxTries.setInt(session, previousMax)
            }
        }
    }

    private class WipingBuffer : ByteArrayOutputStream() {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    private class Link(val session: Session) {
        private val lock = Any()
        private var stopped = false
        private var socket: Socket? = null
        private var channel: Channel? = null
        @Volatile var authenticated = false

        val connected: Boolean
            get() = synchronized(lock) { !stopped && authenticated && session.isConnected }

        val serverAddress: String?
            get() = synchronized(lock) {
                if (!stopped && authenticated && session.isConnected) socket?.inetAddress?.hostAddress else null
            }

        val socketFactory = object : SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                val created = Socket()
                try {
                    synchronized(lock) {
                        check(!stopped) { "SSH-соединение закрыто." }
                        socket = created
                    }
                    created.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
                    created.soTimeout = AUTH_TIMEOUT
                    requireOpen()
                    return created
                } catch (e: Exception) {
                    runCatching { created.close() }
                    throw e
                }
            }

            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        }

        fun requireOpen() = synchronized(lock) {
            check(!stopped) { "SSH-соединение закрыто." }
        }

        fun attachChannel(value: Channel) = synchronized(lock) {
            check(!stopped && channel == null) { "SSH-канал недоступен." }
            channel = value
        }

        fun detachChannel(value: Channel) = synchronized(lock) {
            if (channel === value) channel = null
        }

        fun shutdown() {
            val resources = synchronized(lock) {
                if (stopped) return
                stopped = true
                authenticated = false
                (socket to channel).also {
                    socket = null
                    channel = null
                }
            }
            runCatching { resources.first?.close() }
            runCatching { resources.second?.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    internal class PinnedHosts(
        private val knownHost: (String, Int) -> SshHostIdentity?,
        private val host: String,
        private val port: Int,
    ) : HostKeyRepository {
        private val address = if (port == 22) host else "[$host]:$port"
        @Volatile var failure: IllegalStateException? = null
            private set
        @Volatile var verified: SshHostIdentity? = null
            private set

        override fun check(host: String, key: ByteArray): Int {
            verified = null
            if (failure != null) return HostKeyRepository.CHANGED
            if (host != address) {
                failure = SshConnectionException(SshFailureReason.HOST_ADDRESS)
                return HostKeyRepository.CHANGED
            }
            return try {
                val identity = SshHostIdentity(
                    this.host,
                    port,
                    HostKey(address, key).type,
                    Base64.encodeToString(key, Base64.NO_WRAP),
                    "SHA256:" + Base64.encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(key),
                        Base64.NO_WRAP or Base64.NO_PADDING,
                    ),
                )
                val pinned = knownHost(this.host, port)
                when {
                    pinned == null -> {
                        failure = UnknownSshHost(identity)
                        HostKeyRepository.NOT_INCLUDED
                    }
                    pinned.host != this.host || pinned.port != port -> {
                        failure = SshConnectionException(SshFailureReason.HOST_STORAGE)
                        HostKeyRepository.CHANGED
                    }
                    pinned.algorithm != identity.algorithm ||
                        !MessageDigest.isEqual(Base64.decode(pinned.keyBase64, Base64.NO_WRAP), key) -> {
                        failure = SshConnectionException(SshFailureReason.HOST_CHANGED)
                        HostKeyRepository.CHANGED
                    }
                    else -> {
                        verified = identity
                        HostKeyRepository.OK
                    }
                }
            } catch (_: Exception) {
                failure = SshConnectionException(SshFailureReason.HOST_STORAGE)
                HostKeyRepository.CHANGED
            }
        }

        override fun getHostKey(): Array<HostKey> = getHostKey(null, null)

        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

        override fun getKnownHostsRepositoryID(): String = "xboard-explicit-pins"
        override fun add(hostkey: HostKey?, ui: UserInfo?): Unit = rejectMutation()
        override fun remove(host: String?, type: String?): Unit = rejectMutation()
        override fun remove(host: String?, type: String?, key: ByteArray?): Unit = rejectMutation()
        private fun rejectMutation(): Nothing = throw IllegalStateException("Доверие ключу SSH изменяется только после явного подтверждения пользователя.")
    }

    private fun connectionFailure(failure: Exception, verified: Boolean, passwordInfo: PasswordUserInfo?): SshConnectionException {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
        val reason = when {
            causes.any { it is UnknownHostException } -> SshFailureReason.DNS
            causes.any { it is SocketTimeoutException } -> SshFailureReason.TIMEOUT
            causes.any { it is ConnectException || it is java.net.NoRouteToHostException } -> SshFailureReason.NETWORK
            causes.any { it is JSchAlgoNegoFailException } -> SshFailureReason.ALGORITHMS
            verified && passwordInfo?.deniedChallenge == true -> SshFailureReason.UNSUPPORTED_AUTH_CHALLENGE
            causes.any { it.message?.startsWith("Auth fail") == true || it.message?.startsWith("Auth cancel") == true } && verified ->
                SshFailureReason.AUTHENTICATION
            causes.any { it.message?.contains("invalid privatekey") == true || it.message?.contains("decrypt private key") == true } -> SshFailureReason.PRIVATE_KEY
            else -> SshFailureReason.CONNECTION
        }
        return SshConnectionException(reason, passwordInfo?.attemptedMethods().orEmpty())
    }

    private object SilentLogger : Logger {
        override fun isEnabled(level: Int): Boolean = false
        override fun log(level: Int, message: String?) = Unit
    }

    companion object {
        private const val CONNECT_TIMEOUT = 30_000
        private const val AUTH_TIMEOUT = 60_000
        private const val SIGNATURES = "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256"
        private const val KEX = "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group16-sha512,diffie-hellman-group18-sha512,diffie-hellman-group14-sha256"
        private const val CIPHERS = "chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr"
        private const val MACS = "hmac-sha2-512-etm@openssh.com,hmac-sha2-256-etm@openssh.com,hmac-sha2-512,hmac-sha2-256"
    }
}

private suspend fun <T> blocking(abort: () -> Unit, action: () -> T): T = coroutineScope {
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { abort() }
        launch(Dispatchers.IO) {
            try {
                currentCoroutineContext().ensureActive()
                continuation.resume(action())
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }
}
