package app.invilink.xboard.panels

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.math.BigDecimal
import java.net.IDN
import java.util.Base64
import java.util.Locale

data class InboundDraft(
    val id: Long? = null,
    val name: String = "",
    val listen: String = "",
    val port: String = "443",
    val protocol: String = "vless",
    val network: String = "tcp",
    val security: String = "none",
    val quotaGiB: String = "0",
    val expiryTime: String = "0",
    val enabled: Boolean = true,
    val protocolSettings: String = "{\"clients\":[],\"decryption\":\"none\",\"fallbacks\":[]}",
    val streamSettings: String = "{\"network\":\"tcp\",\"security\":\"none\"}",
    val sniffing: String = "{\"enabled\":false,\"destOverride\":[\"http\",\"tls\",\"quic\"],\"metadataOnly\":false,\"routeOnly\":false}",
    val allocate: String = "{\"strategy\":\"always\"}",
    val extra: String = "{}",
    private val original: String = "{}",
    val sniMode: String = "random",
    val sniCustom: String = "",
    val sniCandidates: List<String> = defaultSniCandidates,
    private val fieldInputs: Map<String, String> = emptyMap(),
) {
    override fun toString() = "InboundDraft($id, [REDACTED])"

    val canChangeProtocol: Boolean
        get() = id == null && runCatching {
            val settings = jsonObject(protocolSettings, "Настройки протокола")
            listOf("clients", "accounts", "peers").all { key ->
                !settings.has(key) || settings.optJSONArray(key)?.length() == 0
            }
        }.getOrDefault(false)

    fun update(field: String, value: String): InboundDraft = when (field) {
        "name" -> copy(name = if (protocol == "amneziawg") normalizeAwgName(value) else value)
        "listen" -> copy(listen = value)
        "port" -> copy(port = value)
        "protocol" -> {
            require(id == null || value == protocol) { "Протокол существующего инбаунда менять нельзя." }
            if (value == protocol) this else {
                require(value in protocols) { "Неизвестный протокол." }
                val settings = jsonObject(protocolSettings, "Настройки протокола")
                require(canChangeProtocol) { "Нельзя менять протокол при наличии клиентов, учётных записей или peers." }
                val defaults = defaults(value)
                if (value == "amneziawg") {
                    val server = defaults.getJSONObject("server")
                    val random = java.security.SecureRandom()
                    for (i in 0..3) server.put("h${i + 1}", (5L + i * 536_870_910L + random.nextInt(536_870_910)).toString())
                }
                val keys = defaults.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!settings.has(key)) settings.put(key, defaults.get(key))
                }
                copy(protocol = value, protocolSettings = settings.toString(2),
                    port = if (value == "amneziawg" && port == "443") (10000 + java.security.SecureRandom().nextInt(50000)).toString() else port,
                    security = if (value == "amneziawg") "none" else security,
                    streamSettings = if (value == "amneziawg") JSONObject(streamSettings).put("security", "none").toString(2) else streamSettings,
                    name = if (value == "amneziawg") normalizeClientName(name) else name)
            }
        }
        "network", "security" -> {
            require(value in (if (field == "network") networks else securities + "xtls")) { "Неизвестный $field." }
            val stream = jsonObject(streamSettings, "Настройки транспорта")
            stream.put(field, value)
            if (field == "network") copy(network = value, streamSettings = stream.toString(2))
            else copy(security = value, streamSettings = stream.toString(2)).withDefaultRealitySpiderX()
        }
        "quotaGiB" -> copy(quotaGiB = value)
        "expiryTime" -> copy(expiryTime = value)
        "protocolSettings" -> copy(protocolSettings = value)
        "streamSettings" -> copy(streamSettings = value, fieldInputs = fieldInputs.filterKeys { it.startsWith("sniffing:") }, sniMode = "preserve")
        "sniffing" -> copy(sniffing = value, fieldInputs = fieldInputs.filterKeys { !it.startsWith("sniffing:") })
        "sniffing:enabled", "sniffing:routeOnly", "sniffing:metadataOnly", "sniffing:domainsExcluded" -> updateSniffingField(field, value)
        "allocate" -> copy(allocate = value)
        "extra" -> copy(extra = value)
        "sniMode" -> {
            require(value == sniMode || value in listOf("preserve", "random", "custom") + sniChoices()) { "Неизвестный режим SNI." }
            copy(sniMode = value)
        }
        "sniCustom" -> copy(sniCustom = value)
        "sniCandidates" -> copy(sniCandidates = value.split(',', '\n'))
        "generate:reality" -> generate("reality")
        "generate:shortId" -> generate("shortId")
        "generate:password" -> generate("password")
        "generate:tag" -> generate("tag")
        else -> if (field.startsWith("awg:")) updateAwgField(field.substringAfter(':'), value)
            else updateOtherField(field, value)
    }

    private fun updateOtherField(field: String, value: String): InboundDraft = when (field) {
        "settings:method", "settings:password", "settings:decryption" -> {
            val settings = jsonObject(protocolSettings, "Настройки протокола")
            settings.put(field.substringAfter(':'), value)
            copy(protocolSettings = settings.toString(2))
        }
        "tls:certificateFile", "tls:keyFile" -> updateCertificate(field.substringAfter(':'), value)
        "reality:target", "reality:privateKey", "reality:shortIds",
        "transport:path", "transport:host", "transport:serviceName", "transport:mode" -> updateStreamField(field, value)
        "tag" -> {
            val additional = jsonObject(extra, "Дополнительные поля")
            additional.put("tag", value)
            copy(extra = additional.toString(2))
        }
        else -> if (field.startsWith("sniffing:destOverride:")) updateSniffingField(field, value)
            else if (field in streamFields || field in certificateFields) updateStreamField(field, value)
            else throw IllegalArgumentException("Неизвестное поле инбаунда: $field")
    }

    private fun updateAwgField(key: String, value: String): InboundDraft {
        require(protocol == "amneziawg")
        val settings = jsonObject(protocolSettings, "Настройки протокола")
        val server = settings.optJSONObject("server") ?: JSONObject().also { settings.put("server", it) }
        when (key) {
            in setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "mtu", "subnetCidr") -> {
                val number = value.toLongOrNull()
                if (number == null || number !in 0..4294967295L) return copy(fieldInputs = fieldInputs + ("awg:$key" to value))
                server.put(key, number)
            }
            in setOf("kernelMode", "ipv6Enabled", "randomTrailers", "disableCookies") -> server.put(key, value.toBooleanStrict())
            in setOf("h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5", "primaryDns", "secondaryDns", "subnetIp", "ipv6Subnet", "externalInterface", "ipv6ExternalInterface", "headerProtectionKey", "contentPaddingAddition", "rekeyAfterTime", "rekeyTimeout", "rejectAfterTime", "keepaliveTimeout", "maxHandshakeAttempts") -> server.put(key, value)
            else -> throw IllegalArgumentException("Неизвестное поле AmneziaWG.")
        }
        return copy(protocolSettings = settings.toString(2), fieldInputs = fieldInputs - "awg:$key")
    }

    val currentSni: String
        get() = runCatching { storedSni(jsonObject(streamSettings, "Настройки транспорта")) }.getOrDefault("")

    fun sniChoices(): List<String> = sniCandidates.mapNotNull { candidate ->
        runCatching { normalizeSni(candidate.trim()) }.getOrNull()
    }.distinct()

    fun networkChoices(): List<String> = if (protocol in mainstreamProtocols) {
        listOf("tcp", "raw", "ws", "grpc", "httpupgrade", "xhttp")
    } else emptyList()

    fun securityChoices(): List<String> = buildList {
        add("none")
        if (protocol in mainstreamProtocols && network in setOf("tcp", "raw", "ws", "grpc", "httpupgrade", "xhttp", "http", "h2", "splithttp")) {
            add("tls")
            if (protocol in setOf("vless", "trojan") && network in setOf("tcp", "raw", "grpc", "xhttp", "http", "h2")) add("reality")
        }
    }

    fun fieldValue(field: String): String {
        fieldInputs[field]?.let { return it }
        if (field.startsWith("sniffing:")) {
            val config = jsonObject(sniffing, "Sniffing")
            val key = field.substringAfter(':')
            return when {
                key.startsWith("destOverride:") -> {
                    val values = config.optJSONArray("destOverride")
                    (values != null && (0 until values.length()).any { values.opt(it) == key.substringAfter(':') }).toString()
                }
                key == "domainsExcluded" -> config.optJSONArray(key)?.let { values ->
                    (0 until values.length()).joinToString("\n") { values.getString(it) }
                }.orEmpty()
                key in sniffingBooleans -> config.optBoolean(key, false).toString()
                else -> throw IllegalArgumentException("Неизвестное поле sniffing.")
            }
        }
        if (field.startsWith("awg:")) return jsonObject(protocolSettings, "Настройки протокола").optJSONObject("server")?.optString(field.substringAfter(':')).orEmpty()
        if (field == "tag") return jsonObject(extra, "Дополнительные поля").optString("tag")
        if (field.startsWith("settings:")) return jsonObject(protocolSettings, "Настройки протокола").optString(field.substringAfter(':'))
        val stream = jsonObject(streamSettings, "Настройки транспорта")
        val value = if (field in certificateFields) {
            val certs = stream.optJSONObject(tlsKey())?.optJSONArray("certificates")
            certs?.optJSONObject(certificateIndex(certs))?.opt(field.substringAfter(':'))
        } else {
            val path = streamPath(field, stream)
            var node = stream
            for (part in path.dropLast(1)) node = node.optJSONObject(part) ?: JSONObject()
            node.opt(path.last())
        }
        return when (value) {
            null, JSONObject.NULL -> if (field == "reality:client:spiderX") "/" else ""
            is JSONArray -> (0 until value.length()).joinToString(if (field in pemFields) "\n" else ", ") { value.optString(it) }
            is JSONObject -> value.toString(2)
            else -> value.toString()
        }
    }

    fun validateAdvancedJson(field: String, text: String) {
        require(field in setOf("protocolSettings", "streamSettings", "sniffing", "allocate", "extra", "transport:extra", "sockopt")) {
            "Неизвестное поле JSON."
        }
        val value = jsonObject(text, field)
        if (field == "sniffing") validateTypes(value, sniffingBooleans, emptySet(), setOf("destOverride", "domainsExcluded"))
        if (field == "streamSettings") {
            require(!value.has("network") || value.optString("network") == network) { "Меняйте network через выбор транспорта в форме." }
            require(!value.has("security") || value.optString("security") == security) { "Меняйте security через выбор защиты в форме." }
        }
    }

    fun flowWarning(): String? {
        if (protocol != "vless" || (network in setOf("tcp", "raw") && security in setOf("tls", "reality", "xtls"))) return null
        val clients = jsonObject(protocolSettings, "Настройки протокола").optJSONArray("clients") ?: return null
        return if ((0 until clients.length()).any { clients.optJSONObject(it)?.optString("flow")?.isNotBlank() == true })
            "У клиентов задан flow (Vision): требуется TCP/RAW с TLS/REALITY. Flow не удаляется автоматически; исправьте clients в settings либо верните совместимую комбинацию."
        else null
    }

    fun generate(field: String): InboundDraft = when (field) {
        "reality" -> {
            require(security == "reality") { "Выберите REALITY для генерации ключей." }
            val stream = jsonObject(streamSettings, "Настройки транспорта")
            val config = stream.optJSONObject("realitySettings") ?: JSONObject().also { stream.put("realitySettings", it) }
            val pair = ConnectionGenerators.x25519()
            config.put("privateKey", pair.getValue("privateKey"))
            val client = config.optJSONObject("settings") ?: JSONObject().also { config.put("settings", it) }
            client.put("publicKey", pair.getValue("publicKey"))
            if (!config.has("shortIds")) config.put("shortIds", JSONArray().put(ConnectionGenerators.shortId()))
            copy(streamSettings = stream.toString(2))
        }
        "shortId" -> updateStreamField("reality:shortIds", ConnectionGenerators.shortId())
        "password" -> {
            require(protocol == "shadowsocks") { "Серверный ключ здесь доступен только для Shadowsocks." }
            update("settings:password", ConnectionGenerators.shadowsocksPassword(fieldValue("settings:method")))
        }
        "tag" -> update("tag", ConnectionGenerators.tag())
        else -> throw IllegalArgumentException("Для этого поля генерация не поддерживается.")
    }

    private fun withDefaultRealitySpiderX(): InboundDraft {
        if (security != "reality") return this
        val stream = jsonObject(streamSettings, "Настройки транспорта")
        require(!stream.has("realitySettings") || stream.optJSONObject("realitySettings") != null) { "realitySettings должен быть JSON-объектом." }
        val reality = stream.optJSONObject("realitySettings") ?: JSONObject().also { stream.put("realitySettings", it) }
        require(!reality.has("settings") || reality.optJSONObject("settings") != null) { "Клиентские settings REALITY должны быть JSON-объектом." }
        val client = reality.optJSONObject("settings") ?: JSONObject().also { reality.put("settings", it) }
        if (client.has("spiderX")) return this
        client.put("spiderX", "/")
        return copy(streamSettings = stream.toString(2))
    }

    fun prepareForSave(): InboundDraft {
        val base = withDefaultRealitySpiderX()
        if (base.security !in setOf("tls", "xtls", "reality") || base.sniMode == "preserve") return base
        val resolved = if (base.sniMode == "random") {
            val candidates = base.sniCandidates.filter { it.isNotBlank() }.map { normalizeSni(it.trim()) }.distinct()
            require(candidates.isNotEmpty()) { "Для случайного SNI укажите хотя бы один домен." }
            base.copy(sniMode = candidates[ConnectionGenerators.candidateIndex(candidates.size)])
        } else base
        val stream = jsonObject(resolved.streamSettings, "Настройки транспорта")
        resolved.applySni(stream)
        return resolved.copy(streamSettings = stream.toString(2))
    }

    private fun applySni(stream: JSONObject) {
        if (security !in setOf("tls", "xtls", "reality") || sniMode == "preserve") return
        require(sniMode != "random") { "Перед сохранением случайного SNI вызовите prepareForSave()." }
        val hostname = normalizeSni(if (sniMode == "custom") sniCustom else sniMode)
        if (security == "reality") {
            val config = stream.optJSONObject("realitySettings") ?: JSONObject().also { stream.put("realitySettings", it) }
            config.put("serverNames", JSONArray().put(hostname))
            config.optJSONObject("settings")?.let { if (it.has("serverName")) it.put("serverName", hostname) }
            if (config.optString("target").isBlank() && config.optString("dest").isBlank()) {
                config.put(if (config.has("dest") && !config.has("target")) "dest" else "target", "$hostname:443")
            }
        } else {
            val config = stream.optJSONObject(tlsKey()) ?: JSONObject().also { stream.put(tlsKey(), it) }
            if (config.has("serverNames")) config.put("serverNames", JSONArray().put(hostname))
            if (config.has("serverName") || !config.has("serverNames")) config.put("serverName", hostname)
            config.optJSONObject("settings")?.let { if (it.has("serverName")) it.put("serverName", hostname) }
        }
    }

    private fun tlsKey(): String = if (security == "xtls") "xtlsSettings" else "tlsSettings"

    private fun certificateIndex(certs: JSONArray?): Int = if (certs == null) 0 else
        (0 until certs.length()).firstOrNull { certs.optJSONObject(it)?.optString("usage", "encipherment") == "encipherment" } ?: certs.length()

    private fun updateCertificate(key: String, value: String): InboundDraft = updateStreamField("tls:$key", value)

    private fun streamPath(field: String, stream: JSONObject): List<String> = when {
        field in certificateFields -> {
            val certs = stream.optJSONObject(tlsKey())?.optJSONArray("certificates")
            listOf(tlsKey(), "certificates", certificateIndex(certs).toString(), field.substringAfter(':'))
        }
        field == "reality:target" -> listOf("realitySettings", if (stream.optJSONObject("realitySettings")?.let { it.has("dest") && !it.has("target") } == true) "dest" else "target")
        field == "transport:host" && network == "ws" -> listOf("wsSettings", "headers", "Host")
        field == "sockopt" -> listOf("sockopt")
        field.startsWith("tls:client:") -> listOf(tlsKey(), "settings", field.substringAfterLast(':'))
        field.startsWith("tls:") -> listOf(tlsKey(), field.substringAfter(':'))
        field.startsWith("reality:client:") -> listOf("realitySettings", "settings", field.substringAfterLast(':'))
        field.startsWith("reality:") -> listOf("realitySettings", field.substringAfter(':'))
        field.startsWith("transport:") -> listOf(transportKey(), field.substringAfter(':'))
        else -> throw IllegalArgumentException("Неизвестное поле транспорта: $field")
    }

    private fun transportKey(): String = when (network) {
        "ws" -> "wsSettings"
        "grpc" -> "grpcSettings"
        "httpupgrade" -> "httpupgradeSettings"
        "xhttp", "splithttp" -> "${network}Settings"
        "http", "h2" -> "httpSettings"
        "raw" -> "rawSettings"
        else -> "tcpSettings"
    }

    private fun updateSniffingField(field: String, value: String): InboundDraft {
        val config = jsonObject(sniffing, "Sniffing")
        val key = field.substringAfter(':')
        if (key == "domainsExcluded") {
            val domains = runCatching {
                require(value.trimStart().firstOrNull() !in listOf('[', '{', '"')) {
                    "Введите домены по одному на строку или через запятую, без JSON."
                }
                val entries = value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                require(entries.all { entry -> entry.none { it.isISOControl() } &&
                    (entry.startsWith("regexp:") || entry.none { it.isWhitespace() || it in "\"[]{}" }) }) {
                    "Исключения: домены без пробелов или regexp:шаблон."
                }
                JSONArray(entries)
            }
            if (domains.isFailure) return copy(fieldInputs = fieldInputs + (field to value))
            config.put(key, domains.getOrThrow())
        } else {
            require(value == "true" || value == "false") { "Нужно true или false." }
            val checked = value == "true"
            if (key.startsWith("destOverride:")) {
                val choice = key.substringAfter(':')
                require(choice in sniffingDestOverrides) { "Неизвестный destOverride." }
                validateTypes(config, emptySet(), emptySet(), setOf("destOverride"))
                val current = config.optJSONArray("destOverride") ?: JSONArray()
                val values = (0 until current.length()).map { current.getString(it) }
                val updated = if (checked) {
                    if (choice in values) values else values + choice
                } else values.filterNot { it == choice }
                config.put("destOverride", JSONArray(updated))
            } else {
                require(key in sniffingBooleans) { "Неизвестное поле sniffing." }
                config.put(key, checked)
            }
        }
        return copy(sniffing = config.toString(2), fieldInputs = fieldInputs - field)
    }

    private fun updateStreamField(field: String, value: String): InboundDraft {
        val parsed = runCatching {
            when (field) {
                in jsonFields -> jsonObject(value, field)
                in booleanFields -> when (value) {
                    "true" -> true
                    "false" -> false
                    "" -> null
                    else -> throw IllegalArgumentException("Нужно true или false.")
                }
                in integerFields -> if (value.isBlank()) null else {
                    require(value.matches(Regex("[0-9]+"))) { "Нужно неотрицательное целое число." }
                    value.toLongOrNull() ?: throw IllegalArgumentException("Число слишком велико.")
                }
                in arrayFields -> JSONArray(if (value.isBlank() && field != "reality:shortIds") emptyList<String>()
                    else value.split(',', '\n').map { it.trim() })
                in pemFields -> JSONArray(value.lines())
                else -> if (field == "transport:mode" && value.isEmpty()) null else value
            }
        }
        if (parsed.isFailure) return copy(fieldInputs = fieldInputs + (field to value))
        val stream = jsonObject(streamSettings, "Настройки транспорта")
        val path = streamPath(field, stream)
        val node = if (field in certificateFields) {
            val config = stream.optJSONObject(tlsKey()) ?: JSONObject().also { stream.put(tlsKey(), it) }
            val certs = config.optJSONArray("certificates") ?: JSONArray().also { config.put("certificates", it) }
            val index = certificateIndex(certs)
            certs.optJSONObject(index) ?: JSONObject().also { certs.put(index, it) }
        } else {
            var node = stream
            for (part in path.dropLast(1)) {
                require(!node.has(part) || node.optJSONObject(part) != null) { "$part должен быть JSON-объектом; исправьте streamSettings." }
                node = node.optJSONObject(part) ?: JSONObject().also { node.put(part, it) }
            }
            node
        }
        val converted = parsed.getOrNull()
        if (converted == null) node.remove(path.last()) else node.put(path.last(), converted)
        val manualSni = field in setOf("tls:serverName", "reality:serverNames", "reality:client:serverName")
        return copy(streamSettings = stream.toString(2), fieldInputs = fieldInputs - field,
            sniMode = if (manualSni) "preserve" else sniMode)
    }

    fun matchesServer(raw: String): Boolean {
        val baseline = jsonObject(original, "Исходный инбаунд")
        val current = jsonObject(raw, "Текущий инбаунд")
        return formKeys.all { key ->
            if (key in setOf("settings", "streamSettings", "sniffing", "allocate")) {
                nested(baseline, key).toString() == nested(current, key).toString()
            } else baseline.opt(key)?.toString() == current.opt(key)?.toString()
        } && (baseline.keys().asSequence().toSet() + current.keys().asSequence().toSet())
            .filterNot { it in formKeys || it in setOf("up", "down", "clientStats", "allTime") }
            .all { key -> baseline.opt(key)?.toString() == current.opt(key)?.toString() }
    }

    internal fun parsedProtocolSettings(): JSONObject = jsonObject(protocolSettings, "Настройки протокола")

    fun payload(): JSONObject {
        require(fieldInputs.isEmpty()) { "Исправьте формат полей: ${fieldInputs.keys.joinToString()}. Черновик не сохранён." }
        require(name.length <= 128 && name.none { it.isISOControl() }) {
            "Название должно содержать не более 128 символов, без управляющих символов."
        }
        val portText = port.trim()
        val portNumber = portText.takeIf { it.matches(Regex("[0-9]{1,5}")) }?.toIntOrNull()
        require(portNumber != null && portNumber in 1..65535) { "Укажите один TCP/UDP-порт от 1 до 65535." }
        require(protocol in knownProtocols) { "Неизвестный протокол." }
        require(id != null || protocol in protocols) { "Создание этого протокола пока не поддерживается." }
        require(network in networks) { "Неизвестный транспорт." }
        require(security in securities || (id != null && security == "xtls")) { "Неизвестный тип защиты." }
        val baselineStream = nested(jsonObject(original, "Исходный инбаунд"), "streamSettings")
        if (id == null || protocol != jsonObject(original, "Исходный инбаунд").optString("protocol") ||
            network != baselineStream.optString("network", "tcp") || security != baselineStream.optString("security", "none")) {
            require(security in securityChoices()) { "Защита $security несовместима с выбранным протоколом или транспортом." }
        }
        require(listen.none { it.isISOControl() }) { "Адрес прослушивания содержит управляющие символы." }
        val quota = quotaGiB.trim().toBigDecimalOrNull()
        require(quota != null && quota.signum() >= 0) { "Лимит трафика должен быть неотрицательным числом в ГиБ." }
        val bytes = try {
            quota.multiply(bytesPerGiB).longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Лимит трафика слишком велик или содержит долю байта.")
        }
        val expiryText = expiryTime.trim()
        val expiry = expiryText.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()
        require(expiry != null) { "Срок должен быть неотрицательным целым числом Unix-миллисекунд; 0 — бессрочно." }
        require(id == null || id > 0) { "Некорректный ID инбаунда." }
        val result = jsonObject(original, "Исходный инбаунд")
        if (id != null && result.has("protocol")) {
            require(protocol == result.getString("protocol")) { "Протокол существующего инбаунда менять нельзя." }
        }
        val additional = jsonObject(extra, "Дополнительные поля")
        val keys = additional.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in formKeys) result.put(key, additional.get(key))
        }
        val settings = jsonObject(protocolSettings, "Настройки протокола")
        val stream = jsonObject(streamSettings, "Настройки транспорта")
        val sniff = jsonObject(sniffing, "Sniffing")
        validateTypes(sniff, sniffingBooleans, emptySet(), setOf("destOverride", "domainsExcluded"))
        val allocation = jsonObject(allocate, "Распределение портов")
        listOf("clients", "accounts", "peers").forEach { key ->
            require(!settings.has(key) || settings.optJSONArray(key) != null) { "Поле $key должно быть JSON-массивом." }
        }
        validateProtocol(settings)
        stream.put("network", network)
        stream.put("security", security)
        applySni(stream)
        validateSecurity(stream)
        result.put("remark", name)
        result.put("listen", listen.trim())
        result.put("port", portNumber)
        result.put("protocol", protocol)
        result.put("enable", enabled)
        result.put("total", bytes)
        result.put("expiryTime", expiry)
        result.put("settings", settings.toString())
        result.put("streamSettings", stream.toString())
        result.put("sniffing", sniff.toString())
        result.put("allocate", allocation.toString())
        if (id == null) result.remove("id") else result.put("id", id)
        return result
    }

    private fun validateProtocol(settings: JSONObject) {
        when (protocol) {
            "amneziawg" -> {
                val server = requireNotNull(settings.optJSONObject("server")) { "Для AmneziaWG нужен объект server." }
                fun number(key: String, min: Long, max: Long): Long {
                    val value = server.opt(key)?.toString()?.toLongOrNull()
                    require(value != null && value in min..max) { "Некорректное значение AmneziaWG: $key." }
                    return value
                }
                number("mtu", 1, 65535)
                number("jc", 0, 128)
                val min = number("jmin", 0, 65535)
                val max = number("jmax", 0, 65535)
                require(min <= max) { "Jmin не может превышать Jmax." }
                for ((key, limit) in mapOf("s1" to 1552L, "s2" to 1608L, "s3" to 1636L, "s4" to 32L)) number(key, 0, limit)
                require(server.getLong("s1") + 56 != server.getLong("s2")) { "S1 + 56 не должно совпадать с S2." }
            }
            "vless" -> {
                require(settings.optString("decryption").isNotBlank()) { "Укажите decryption в настройках VLESS (обычно none)." }
                val clients = settings.optJSONArray("clients")
                val hasFlow = clients != null && (0 until clients.length()).any { index ->
                    clients.optJSONObject(index)?.optString("flow")?.isNotBlank() == true
                }
                require(!hasFlow || (network in setOf("tcp", "raw") && security in setOf("tls", "reality", "xtls"))) {
                    "У клиентов задан flow (Vision): нужен TCP/RAW с TLS/REALITY. Смена транспорта не должна ломать их подключения."
                }
            }
            "shadowsocks" -> {
                require(settings.optString("method").isNotBlank()) { "Укажите method для Shadowsocks." }
                require(settings.optString("password").isNotBlank()) { "Укажите серверный password для Shadowsocks в JSON настроек протокола." }
            }
            "mixed" -> {
                require(settings.optString("auth") in setOf("password", "noauth")) { "Для Mixed укажите auth: password или noauth." }
                if (settings.optString("auth") == "password") {
                    val accounts = settings.optJSONArray("accounts")
                    require(accounts != null && accounts.length() > 0 && (0 until accounts.length()).all { index ->
                        val account = accounts.optJSONObject(index)
                        account != null && account.optString("user").isNotBlank() && account.optString("pass").isNotBlank()
                    }) { "Для Mixed заполните accounts: массив объектов с user и pass. Режим noauth отключает авторизацию." }
                }
            }
            "dokodemo-door" -> {
                require(settings.optString("network", "tcp") in setOf("tcp", "udp", "tcp,udp")) { "Для dokodemo-door network: tcp, udp или tcp,udp." }
                if (!settings.optBoolean("followRedirect", false)) {
                    val targetPort = settings.opt("port")?.toString()?.toIntOrNull()
                    require(settings.optString("address").isNotBlank() && targetPort != null && targetPort in 1..65535) {
                        "Для dokodemo-door укажите address и port назначения либо включите followRedirect."
                    }
                }
            }
        }
    }

    private fun validateSecurity(stream: JSONObject) {
        listOf("tlsSettings", "xtlsSettings", "realitySettings", "sockopt", transportKey()).forEach { key ->
            require(!stream.has(key) || stream.optJSONObject(key) != null) { "$key должен быть JSON-объектом." }
        }
        if (network in setOf("xhttp", "splithttp")) stream.optJSONObject(transportKey())?.let { config ->
            require(!config.has("mode") || config.optString("mode") in xhttpModes) { "XHTTP mode: auto, packet-up, stream-up или stream-one." }
            require(!config.has("extra") || config.optJSONObject("extra") != null) { "XHTTP extra должен быть JSON-объектом." }
        }
        if (security in setOf("tls", "xtls", "reality")) {
            val config = stream.optJSONObject(if (security == "reality") "realitySettings" else tlsKey())
            require(config == null || !config.has("settings") || config.optJSONObject("settings") != null) { "Клиентские settings защиты должны быть JSON-объектом." }
        }
        if (security == "tls" || security == "xtls") {
            val config = stream.optJSONObject(if (security == "tls") "tlsSettings" else "xtlsSettings")
            val certs = config?.optJSONArray("certificates")
            require(certs != null && (0 until certs.length()).any { index ->
                val cert = certs.optJSONObject(index)
                cert != null && cert.optString("usage", "encipherment") == "encipherment" &&
                    (if (cert.optString("certificateFile").isNotBlank() || cert.optString("keyFile").isNotBlank())
                        serverFile(cert.optString("certificateFile")) && serverFile(cert.optString("keyFile"))
                    else plausiblePem(cert.optJSONArray("certificate"), "CERTIFICATE") && plausiblePrivateKey(cert.optJSONArray("key")))
            }) { "Для TLS нужны certificateFile и keyFile (пути на сервере) либо встроенные PEM certificate и key." }
            (0 until certs.length()).forEach { index ->
                val cert = certs.optJSONObject(index) ?: throw IllegalArgumentException("TLS certificates должен содержать объекты.")
                validateTypes(cert, setOf("oneTimeLoading", "buildChain"), setOf("ocspStapling"), setOf("certificate", "key"))
            }
            config?.let { tls ->
                listOf("minVersion", "maxVersion").forEach { key ->
                    require(!tls.has(key) || tls.optString(key).isEmpty() || tls.optString(key) in tlsVersions) { "TLS $key: допустимы 1.0–1.3." }
                }
                val min = tls.optString("minVersion")
                val max = tls.optString("maxVersion")
                require(min.isBlank() || max.isBlank() || min <= max) { "Минимальная версия TLS больше максимальной." }
                validateTypes(tls, setOf("rejectUnknownSni", "disableSystemRoot", "enableSessionResumption"), emptySet(), setOf("alpn", "curvePreferences"))
                tls.optJSONObject("settings")?.let { client ->
                    validateTypes(client, emptySet(), emptySet(), setOf("pinnedPeerCertSha256"))
                }
            }
        }
        if (security == "reality") {
            require(protocol in setOf("vless", "trojan") && network in setOf("tcp", "raw", "http", "h2", "grpc", "xhttp")) {
                "REALITY требует VLESS/Trojan и совместимый транспорт: TCP, HTTP/2, gRPC или XHTTP."
            }
            val config = stream.optJSONObject("realitySettings")
                ?: throw IllegalArgumentException("Добавьте объект realitySettings в streamSettings.")
            val target = config.optString("target").ifBlank { config.optString("dest") }
            val targetPort = target.substringAfterLast(':').takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
            require(target.isNotBlank() && target.none { it.isWhitespace() || it.isISOControl() } &&
                (target.startsWith('/') || target.startsWith('@') ||
                    (targetPort != null && targetPort in 1..65535 &&
                        (target.matches(Regex("[0-9]+")) || target.lastIndexOf(':') > 0)))) {
                "Для REALITY укажите target (или dest): адрес:порт, номер порта или путь Unix-сокета."
            }
            val key = config.optString("privateKey")
            require(key.matches(Regex("[A-Za-z0-9_-]{43}=?")) && runCatching { Base64.getUrlDecoder().decode(key).size == 32 }.getOrDefault(false)) {
                "Для REALITY укажите приватный X25519-ключ в realitySettings.privateKey."
            }
            require(nonemptyStrings(config.optJSONArray("serverNames"))) { "Для REALITY заполните массив serverNames." }
            validateTypes(config, setOf("show"), setOf("xver", "maxTimediff"), setOf("serverNames", "shortIds"))
            require(!config.has("xver") || config.optInt("xver") in 0..2) { "REALITY xver: 0, 1 или 2." }
            listOf("minClientVer", "maxClientVer").forEach { key ->
                require(!config.has(key) || config.optString(key).isEmpty() || config.optString(key).matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
                    "REALITY $key: версия в формате x.y.z либо пусто."
                }
            }
            config.optJSONObject("settings")?.let { client ->
                val publicKey = client.optString("publicKey")
                require(publicKey.isEmpty() || x25519Key(publicKey)) { "REALITY settings.publicKey: нужен публичный X25519-ключ." }
            }
            val shortIds = config.optJSONArray("shortIds")
            require(shortIds != null && shortIds.length() > 0 && (0 until shortIds.length()).all { index ->
                val shortId = shortIds.opt(index) as? String
                shortId != null && shortId.length <= 16 && shortId.length % 2 == 0 && shortId.matches(Regex("[0-9a-fA-F]*"))
            }) { "Для REALITY заполните shortIds: чётное число hex-символов, до 16; пустой ID допустим." }
        }
    }

    companion object {
        val sniffingDestOverrides = listOf("http", "tls", "quic", "fakedns")
        private val sniffingBooleans = setOf("enabled", "routeOnly", "metadataOnly")
        val xhttpModes = listOf("auto", "packet-up", "stream-up", "stream-one")
        val tlsVersions = listOf("1.0", "1.1", "1.2", "1.3")
        val fingerprints = listOf("", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized", "randomizednoalpn", "unsafe")
        private val certificateFields = setOf("tls:certificateFile", "tls:keyFile", "tls:certificate", "tls:key", "tls:ocspStapling", "tls:oneTimeLoading", "tls:buildChain")
        private val pemFields = setOf("tls:certificate", "tls:key")
        private val jsonFields = setOf("transport:extra", "sockopt")
        private val arrayFields = setOf("tls:alpn", "tls:curvePreferences", "tls:client:pinnedPeerCertSha256", "reality:shortIds", "reality:serverNames")
        private val integerFields = setOf("tls:ocspStapling", "reality:xver", "reality:maxTimediff")
        private val booleanFields = setOf("tls:oneTimeLoading", "tls:buildChain", "tls:rejectUnknownSni", "tls:disableSystemRoot", "tls:enableSessionResumption", "reality:show")
        private val streamFields = jsonFields + arrayFields + integerFields + booleanFields + setOf(
            "tls:serverName", "tls:minVersion", "tls:maxVersion", "tls:cipherSuites", "tls:echServerKeys", "tls:masterKeyLog",
            "tls:client:fingerprint", "tls:client:verifyPeerCertByName", "tls:client:echConfigList",
            "reality:target", "reality:privateKey", "reality:minClientVer", "reality:maxClientVer", "reality:mldsa65Seed", "reality:masterKeyLog",
            "reality:client:publicKey", "reality:client:serverName", "reality:client:fingerprint", "reality:client:spiderX", "reality:client:mldsa65Verify",
            "transport:path", "transport:host", "transport:serviceName", "transport:mode",
        )

        private fun x25519Key(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{43}=?")) &&
            runCatching { Base64.getUrlDecoder().decode(value).size == 32 }.getOrDefault(false)

        private fun serverFile(value: String): Boolean = value.isNotBlank() && value.none { it.isISOControl() } && !value.contains("-----BEGIN")

        private fun plausiblePem(lines: JSONArray?, kind: String): Boolean {
            if (lines == null || lines.length() == 0 || (0 until lines.length()).any { lines.opt(it) !is String }) return false
            val text = (0 until lines.length()).joinToString("\n") { lines.getString(it) }
            val body = text.substringAfter("-----BEGIN $kind-----", "").substringBefore("-----END $kind-----", "")
            return text.contains("-----END $kind-----") && body.filterNot { it.isWhitespace() }.let {
                it.length >= 16 && it.matches(Regex("[A-Za-z0-9+/=]+"))
            }
        }

        private fun plausiblePrivateKey(lines: JSONArray?): Boolean = listOf("PRIVATE KEY", "RSA PRIVATE KEY", "EC PRIVATE KEY").any { plausiblePem(lines, it) }

        private fun validateTypes(config: JSONObject, booleans: Set<String>, integers: Set<String>, arrays: Set<String>) {
            booleans.forEach { require(!config.has(it) || config.opt(it) is Boolean) { "$it должен быть boolean." } }
            integers.forEach { key ->
                require(!config.has(key) || (config.opt(key) is Number && config.opt(key)?.toString()?.toLongOrNull()?.let { it >= 0 } == true)) {
                    "$key должен быть неотрицательным целым числом."
                }
            }
            arrays.forEach { key ->
                val values = config.optJSONArray(key)
                require(!config.has(key) || (values != null && (0 until values.length()).all { values.opt(it) is String })) { "$key должен быть массивом строк." }
            }
        }

        internal fun normalizeAwgName(value: String): String {
            val normalized = normalizeClientName(value)
            val prefix = Regex("^:([A-Za-z]{2})(?![A-Za-z:])").find(normalized) ?: return normalized
            val flag = countryFlagForClientName(prefix.groupValues[1] + ":")
            return if (flag.isEmpty()) normalized else flag + normalized.substring(prefix.value.length)
        }

        val mainstreamProtocols: List<String> = listOf("vless", "vmess", "trojan", "shadowsocks")
        val protocols: List<String> = mainstreamProtocols + listOf("amneziawg", "dokodemo-door", "mixed")
        val defaultSniCandidates: List<String> = listOf(
            "www.microsoft.com", "www.apple.com", "www.cloudflare.com", "www.amazon.com", "www.google.com",
            "www.bing.com", "www.wikipedia.org", "www.github.com", "www.mozilla.org", "www.samsung.com",
        )
        val shadowsocksMethods: List<String> = listOf(
            "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
            "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305",
        )

        fun normalizeSni(value: String): String {
            require(value.isNotEmpty() && value.none {
                it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() || it in ":/\\*?#@[]"
            }) {
                "SNI: укажите доменное имя без схемы, порта, wildcard и пробелов."
            }
            val ascii = try { IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) } catch (_: Exception) {
                throw IllegalArgumentException("SNI: некорректное доменное имя.")
            }
            val labels = ascii.split('.')
            require(ascii.length <= 253 && labels.size >= 2 && labels.all {
                it.length in 1..63 && it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
            } && labels.last().any { it in 'a'..'z' }) { "SNI: нужны DNS-метки длиной 1–63 символа, имя до 253 символов; IP-адрес недопустим." }
            return ascii
        }

        val networks: List<String> = listOf("tcp", "raw", "ws", "grpc", "httpupgrade", "xhttp", "http", "h2", "kcp", "quic", "splithttp", "hysteria")
        val securities: List<String> = listOf("none", "tls", "reality")
        private val knownProtocols = protocols + listOf("http", "socks", "tunnel", "tun", "wireguard", "amneziawg", "hysteria", "hysteria2", "tuic", "mtproto", "masque")
        private val bytesPerGiB = BigDecimal(1073741824L)
        private val formKeys = setOf("id", "remark", "listen", "port", "protocol", "enable", "total", "expiryTime", "settings", "streamSettings", "sniffing", "allocate")

        fun create(): InboundDraft = InboundDraft()

        fun createWithReality(): InboundDraft = create().update("security", "reality").generate("reality")

        fun fromRaw(raw: String): InboundDraft {
            val root = jsonObject(raw, "Инбаунд")
            val id = try { BigDecimal(root.get("id").toString()).longValueExact() } catch (_: Exception) {
                throw IllegalArgumentException("В полном ответе инбаунда отсутствует корректный ID.")
            }
            require(id > 0) { "Некорректный ID инбаунда." }
            val settings = nested(root, "settings")
            val stream = nested(root, "streamSettings")
            val additional = jsonObject(raw, "Инбаунд")
            formKeys.forEach { additional.remove(it) }
            val total = try { BigDecimal(root.opt("total")?.toString() ?: "0").longValueExact() } catch (_: Exception) {
                throw IllegalArgumentException("Некорректный лимит трафика в ответе панели.")
            }
            return InboundDraft(
                id = id,
                name = root.optString("remark"),
                listen = root.optString("listen"),
                port = root.get("port").toString(),
                protocol = root.getString("protocol"),
                network = stream.optString("network", "tcp"),
                security = stream.optString("security", "none"),
                quotaGiB = BigDecimal(total).divide(bytesPerGiB).stripTrailingZeros().toPlainString(),
                expiryTime = root.opt("expiryTime")?.toString() ?: "0",
                enabled = root.optBoolean("enable", true),
                protocolSettings = settings.toString(2),
                streamSettings = stream.toString(2),
                sniffing = nested(root, "sniffing").toString(2),
                allocate = nested(root, "allocate").toString(2),
                extra = additional.toString(2),
                original = root.toString(),
                sniMode = "preserve",
                sniCustom = storedSni(stream),
            )
        }

        private fun storedSni(stream: JSONObject): String {
            val config = stream.optJSONObject(when (stream.optString("security")) {
                "reality" -> "realitySettings"
                "xtls" -> "xtlsSettings"
                else -> "tlsSettings"
            }) ?: return ""
            return config.optString("serverName").ifBlank {
                config.optJSONArray("serverNames")?.optString(0).orEmpty().ifBlank {
                    config.optJSONObject("settings")?.optString("serverName").orEmpty()
                }
            }
        }

        private fun defaults(protocol: String): JSONObject = JSONObject(when (protocol) {
            "amneziawg" -> "{\"server\":{\"subnetIp\":\"10.8.1.0\",\"subnetCidr\":24,\"mtu\":1280,\"primaryDns\":\"8.8.8.8\",\"secondaryDns\":\"1.1.1.1\",\"jc\":5,\"jmin\":10,\"jmax\":50,\"s1\":30,\"s2\":45,\"s3\":10,\"s4\":5,\"h1\":\"1\",\"h2\":\"2\",\"h3\":\"3\",\"h4\":\"4\",\"i1\":\"\"},\"clients\":[]}"
            "vless" -> "{\"clients\":[],\"decryption\":\"none\",\"fallbacks\":[]}"
            "vmess" -> "{\"clients\":[]}"
            "trojan" -> "{\"clients\":[],\"fallbacks\":[]}"
            "shadowsocks" -> "{\"method\":\"2022-blake3-aes-256-gcm\",\"password\":\"\",\"network\":\"tcp,udp\",\"clients\":[],\"ivCheck\":false}"
            "dokodemo-door" -> "{\"address\":\"\",\"port\":0,\"network\":\"tcp,udp\",\"followRedirect\":false}"
            "mixed" -> "{\"auth\":\"password\",\"accounts\":[],\"udp\":false,\"ip\":\"127.0.0.1\"}"
            else -> throw IllegalArgumentException("Неизвестный протокол.")
        })

        private fun nested(root: JSONObject, key: String): JSONObject = when (val value = root.opt(key)) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> value
            is String -> if (value.isBlank()) JSONObject() else jsonObject(value, key)
            else -> throw IllegalArgumentException("Поле $key должно быть JSON-объектом.")
        }

        private fun nonemptyStrings(array: JSONArray?): Boolean = array != null && array.length() > 0 &&
            (0 until array.length()).all { (array.opt(it) as? String)?.isNotBlank() == true }

        private fun jsonObject(text: String, label: String): JSONObject = try {
            validateJsonStrings(text)
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                require(reader.peek() == JsonToken.BEGIN_OBJECT)
                val result = readJson(reader, 0) as JSONObject
                require(reader.peek() == JsonToken.END_DOCUMENT)
                result
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("$label: нужен корректный JSON-объект без комментариев и текста после него.")
        }

        private fun validateJsonStrings(text: String) {
            var index = 0
            while (index < text.length) {
                val token = text[index++]
                if (token != '"') {
                    if (token in " \t\r\n{}[]:,") continue
                    val start = index - 1
                    while (index < text.length && text[index] !in " \t\r\n{}[]:,\"") index++
                    val literal = text.substring(start, index)
                    require(literal in setOf("true", "false", "null") ||
                        literal.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                    continue
                }
                var closed = false
                while (index < text.length) {
                    val char = text[index++]
                    require(char >= ' ')
                    if (char == '"') {
                        closed = true
                        break
                    }
                    if (char == '\\') {
                        require(index < text.length)
                        val escape = text[index++]
                        require(escape in "\"\\/bfnrtu")
                        if (escape == 'u') {
                            require(index + 4 <= text.length && text.substring(index, index + 4).all { it in "0123456789abcdefABCDEF" })
                            index += 4
                        }
                    }
                }
                require(closed)
            }
        }

        private fun readJson(reader: JsonReader, depth: Int): Any {
            require(depth <= 128)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    val result = JSONObject()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        require(!result.has(key))
                        result.put(key, readJson(reader, depth + 1))
                    }
                    reader.endObject()
                    result
                }
                JsonToken.BEGIN_ARRAY -> {
                    val result = JSONArray()
                    reader.beginArray()
                    while (reader.hasNext()) result.put(readJson(reader, depth + 1))
                    reader.endArray()
                    result
                }
                JsonToken.STRING -> reader.nextString()
                JsonToken.NUMBER -> {
                    val literal = reader.nextString()
                    require(literal.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                    val number = BigDecimal(literal)
                    try { number.longValueExact() } catch (_: ArithmeticException) { number }
                }
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
                else -> throw IllegalArgumentException("Некорректный JSON.")
            }
        }
    }
}
