package app.invilink.xboard

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.invilink.xboard.ui.updateClientName
import app.invilink.xboard.panels.ClientDetail
import app.invilink.xboard.panels.ClientDraft
import app.invilink.xboard.panels.ClientEditSession
import app.invilink.xboard.panels.InboundDetail
import app.invilink.xboard.panels.PanelApi
import app.invilink.xboard.panels.PanelConfig
import app.invilink.xboard.panels.clientDisplayName
import app.invilink.xboard.panels.countryFlagForClientName
import app.invilink.xboard.panels.normalizeClientConnectionLink
import app.invilink.xboard.panels.normalizeClientName
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ClientNameTest {
    private val ru = "\uD83C\uDDF7\uD83C\uDDFA"
    private val de = "\uD83C\uDDE9\uD83C\uDDEA"
    private val uuid = "00000000-0000-0000-0000-000000000001"
    private val inbound = InboundDetail(
        1, "Test", "vless", 443, true, "tcp", "none",
        null, null, null, null, emptyList(), "{}",
    )

    private fun draft(name: String) = ClientDraft(mapOf(
        "email" to name, "id" to uuid, "trafficResetDay" to "1",
        "subId" to "RU:subscription", "comment" to "DE:comment",
    ))

    @Test
    fun visibleClientNameDoesNotChangeServerIdentity() {
        val client = ClientDetail("Имяклиента", true, null, null, null, null, "sub", null, "", "{}",
            displayName = "Имя клиента")
        assertEquals("Имя клиента", client.visibleName)
        assertEquals("Имяклиента", client.email)
        assertEquals("Имяклиента", client.copy(displayName = null).visibleName)
        assertEquals("Имяклиента", client.copy(displayName = " ").visibleName)
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    private fun decode(text: String) = URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")

    @Test
    fun convertsImmediatelyWhenColonCompletesThePrefix() {
        assertEquals("", normalizeClientName(""))
        assertEquals("R", normalizeClientName("R"))
        assertEquals("RU", normalizeClientName("RU"))
        assertEquals(ru, normalizeClientName("RU:"))
        assertEquals(ru + "client", normalizeClientName("ru:client"))
        assertEquals(de + " Client", normalizeClientName("dE: Client"))
    }

    @Test
    fun flagEntryPreservesCursorAndBackspaceRemovesWholeFlag() {
        val converted = updateClientName(TextFieldValue("RU", TextRange(2)), TextFieldValue("RU:", TextRange(3)))
        assertEquals(ru, converted.text)
        assertEquals(TextRange(ru.length), converted.selection)
        val typed = updateClientName(converted, TextFieldValue(ru + "Имя", TextRange(ru.length + 3)))
        assertEquals(ru + "Имя", typed.text)
        val deleted = updateClientName(converted, TextFieldValue(ru.substring(0, 2), TextRange(2)))
        assertEquals("", deleted.text)
        assertEquals(TextRange(0), deleted.selection)
        val middle = updateClientName(TextFieldValue(ru + "Имя", TextRange(4)), TextFieldValue(ru.substring(0, 2) + "Имя", TextRange(2)))
        assertEquals("Имя", middle.text)
        assertEquals(TextRange(0), middle.selection)
    }

    @Test
    fun inboundFlagEditingPreservesSpacesAndRemovesCompleteFlag() {
        val normalize: (String) -> String = app.invilink.xboard.panels.InboundDraft::normalizeAwgName
        val converted = updateClientName(TextFieldValue("RU", TextRange(2)), TextFieldValue("RU:", TextRange(3)), normalize)
        assertEquals(ru, converted.text)
        assertEquals(TextRange(4), converted.selection)
        val spaced = updateClientName(converted, TextFieldValue(ru + " имя с пробелами", TextRange(20)), normalize)
        assertEquals(ru + " имя с пробелами", spaced.text)
        val deleted = updateClientName(converted, TextFieldValue(ru.substring(0, 2), TextRange(2)), normalize)
        assertEquals("", deleted.text)
        val draft = app.invilink.xboard.panels.InboundDraft.create().update("protocol", "amneziawg").update("name", spaced.text)
        assertEquals(spaced.text, draft.payload().getString("remark"))
    }

    @Test
    fun flagCommandsWorkAnywhereAndMoreThanOnce() {
        assertEquals("Имя" + ru + "текст" + de, normalizeClientName("Имя:RU:текст:DE:"))
        assertEquals(ru + "Имя" + de, normalizeClientName("RU:Имя:DE:"))
        assertEquals("Имя:XX:", normalizeClientName("Имя:XX:"))
        val value = "Имя:RU:текст:DE:"
        val result = updateClientName(TextFieldValue("Имя", TextRange(3)), TextFieldValue(value, TextRange(value.length)))
        assertEquals("Имя" + ru + "текст" + de, result.text)
        assertEquals(TextRange(result.text.length), result.selection)
    }

    @Test
    fun preservesSuffixWhitespaceAndUnicodeVerbatim() {
        val suffix = "  Имя + e\u0301 / 中文 \uD83D\uDC69\u200D\uD83D\uDCBB DE:tail  "
        assertEquals(ru + suffix, normalizeClientName("RU:" + suffix))
        listOf("Имя", "e\u0301", ru + " Имя", "XX:Имя", "RUS:Имя", "RU-client", "РФ:Имя", " RU:Имя")
            .forEach { assertEquals(it, normalizeClientName(it)) }
    }

    @Test
    fun countryMatchingIsLocaleIndependent() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(countryFlagForClientName("IN:") + "name", normalizeClientName("in:name"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun normalizedNamesAreIdempotentAndRenderedWithOnlyOneFlag() {
        val normalized = normalizeClientName("RU: Имя")
        assertEquals(normalized, normalizeClientName(normalized))
        assertEquals("", countryFlagForClientName(normalized))
        assertEquals(normalized, clientDisplayName(normalized))
        val rendered = listOf(countryFlagForClientName(normalized), clientDisplayName(normalized))
            .filter { it.isNotEmpty() }.joinToString(" ")
        assertEquals(ru + " Имя", rendered)
        assertEquals(ru, countryFlagForClientName("RU: Имя"))
        assertEquals("Имя", clientDisplayName("RU: Имя"))
    }

    @Test
    fun payloadNormalizesOnlyTheNameAndKeepsOtherFields() {
        val payload = draft("RU: Имя e\u0301").payload(inbound)
        assertEquals(ru + "Имяe\u0301", payload.getString("email"))
        assertEquals(uuid, payload.getString("id"))
        assertEquals("RU:subscription", payload.getString("subId"))
        assertEquals("DE:comment", payload.getString("comment"))
        assertEquals("Имяe\u0301", draft("Имя e\u0301").payload(inbound).getString("email"))
        assertEquals(ru + "Имя", draft(ru + " Имя").payload(inbound).getString("email"))
        assertEquals("Имя клиента", decode(normalizeClientConnectionLink("vless://id@example.test:443#old", "Имя клиента").substringAfter('#')))
    }

    @Test
    fun unchangedLegacyEditKeepsStoredNameButExplicitRenameUsesFlag() {
        val record = JSONObject().put("id", 7).put("uuid", uuid).put("email", "RU: Старое")
            .put("enable", true).put("trafficResetDay", 1)
        val envelope = JSONObject().put("client", record).put("inboundIds", JSONArray().put(1))
        val session = ClientEditSession.fromResponse(envelope)
        val otherFieldEdit = session.draft.copy(values = session.draft.values + ("comment" to "Changed"))
        assertEquals("RU: Старое", session.updatePayload(otherFieldEdit, inbound).getString("email"))
        val renamed = session.draft.copy(values = session.draft.values + ("email" to normalizeClientName("RU: Новое")))
        assertEquals(ru + " Новое", session.updatePayload(renamed, inbound).getString("email"))
        assertEquals("RU: Старое", session.originalEmail)
    }

    @Test
    fun connectionFragmentsNormalizeWithoutTouchingCredentialsOrQuery() {
        val base = "vless://$uuid@example.test:443?security=none&path=%2FRU%3A&token=RU:secret#"
        val name = "RU: Имя+e\u0301 中文"
        listOf(name, encode(name)).forEach { fragment ->
            val normalized = normalizeClientConnectionLink(base + fragment)
            assertEquals(base, normalized.substringBefore('#') + "#")
            assertEquals(ru + " Имя+e\u0301 中文", decode(normalized.substringAfter('#')))
            assertEquals(normalized, normalizeClientConnectionLink(normalized))
        }
    }

    @Test
    fun unmodifiedAndMalformedLinksStayByteForByteUnchanged() {
        listOf(
            "vless://id@example.test:443#" + encode(ru + " Имя"),
            "trojan://RU:secret@example.test:443#Имя+e\u0301",
            "ss://credentials@example.test:443#XX:Имя",
            "vless://id@example.test:443#RU:%ZZ",
            "vmess://not-valid-base64!", "vmess://" + Base64.getEncoder().encodeToString("[]".toByteArray()),
            "https://example.test/sub/RU:token",
        ).forEach { assertEquals(it, normalizeClientConnectionLink(it)) }
    }

    @Test
    fun vmessNamesNormalizeInsideBase64JsonWithoutChangingConnectionFields() {
        val config = JSONObject().put("v", "2").put("ps", "DE: Имя+e\u0301")
            .put("id", uuid).put("add", "example.test").put("port", "443")
            .put("path", "/RU:keep").put("future", JSONObject().put("keep", true))
        listOf(Base64.getEncoder(), Base64.getUrlEncoder().withoutPadding()).forEach { encoder ->
            val link = "vmess://" + encoder.encodeToString(config.toString().toByteArray(Charsets.UTF_8))
            val normalized = normalizeClientConnectionLink(link)
            val json = String(Base64.getUrlDecoder().decode(normalized.substring(8).replace('+', '-').replace('/', '_')), Charsets.UTF_8)
            val result = JSONObject(json)
            assertEquals(de + " Имя+e\u0301", result.getString("ps"))
            result.put("ps", config.getString("ps"))
            assertEquals(config.toString(), result.toString())
            assertEquals(normalized, normalizeClientConnectionLink(normalized))
        }
    }

    @Test
    fun vlessExportUsesOnlyClientNameAndPreservesEveryConnectionParameter() {
        val base = "vless://$uuid@example.test:443?security=reality&pbk=keep&sid=12&type=tcp&flow=xtls-rprx-vision"
        val name = "RU: Имя+клиента"
        for (suffix in listOf("#" + encode("Инбаунд — RU: Имя+клиента"), "#broken%label", "")) {
            val result = normalizeClientConnectionLink(base + suffix, name)
            assertEquals(base, result.substringBefore('#'))
            assertEquals(ru + " Имя+клиента", decode(result.substringAfter('#')))
            assertEquals(result, normalizeClientConnectionLink(result, name))
        }
    }

    @Test
    fun apiLooksUpOriginalNameAndNormalizesOnlyConnectionLabels() = runBlocking {
        assertPanelLinks(connectionUnavailable = false)
    }

    @Test
    fun subscriptionRemainsUsableWhenConnectionEndpointFails() = runBlocking {
        assertPanelLinks(connectionUnavailable = true)
    }

    @Test
    fun activityAndTrafficResetUsePanelPostEndpointsForExactlyTheSelectedClient() = runBlocking {
        val email = "RU: Имя + client@example.test"
        val requests = mutableListOf<String>()
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                repeat(2) { index -> server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    requests.add(requireNotNull(reader.readLine()))
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = if (index == 0) JSONObject().put("success", true).put("obj", JSONArray().put(email)).toString()
                        else """{"success":true,"obj":null}"""
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                } }
            } catch (error: Throwable) { if (!server.isClosed) failure.set(error) }
        }
        try {
            val config = PanelConfig("fixture", "Test", "http://127.0.0.1:${server.localPort}/", "fixture-token")
            assertEquals(setOf(email), PanelApi().onlineClients(config))
            PanelApi().resetClientTraffic(config, email)
            worker.join(5_000)
            assertFalse(worker.isAlive)
            failure.get()?.let { throw AssertionError(it) }
            assertEquals(listOf("POST /panel/api/clients/onlines HTTP/1.1",
                "POST /panel/api/clients/resetTraffic/${encode(email)} HTTP/1.1"), requests)
        } finally {
            server.close()
            worker.join(5_000)
        }
    }

    private suspend fun assertPanelLinks(connectionUnavailable: Boolean) {
        val requests = mutableListOf<String>()
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        val serverFailure = AtomicReference<Throwable?>()
        val subscription = "https://example.test/sub/" + encode("RU:sub")
        val worker = thread(name = "client-name-api-fixture", isDaemon = true) {
            try {
                repeat(2) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val request = requireNotNull(reader.readLine())
                        requests.add(request)
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                        }
                        val isSettings = request.startsWith("POST /panel/api/setting/all ")
                        val link = "vless://$uuid@example.test:443#" + encode("Инбаунд — RU: Имя")
                        val unavailable = connectionUnavailable && !isSettings
                        val body = if (isSettings) """{"success":true,"obj":{"subEnable":true,"subURI":"https://example.test/sub/"}}"""
                            else """{"success":true,"obj":["$link"]}"""
                        val bytes = (if (unavailable) "" else body).toByteArray(Charsets.UTF_8)
                        val status = if (unavailable) "503 Service Unavailable" else "200 OK"
                        val headers = "HTTP/1.1 $status\r\n" +
                            "Content-Type: application/json; charset=UTF-8\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(headers.toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (!server.isClosed) serverFailure.set(failure)
            }
        }
        try {
            val config = PanelConfig("fixture", "Test", "http://127.0.0.1:${server.localPort}/", "fixture-token")
            val client = ClientDetail("RU: Имя", true, null, null, null, null, "RU:sub", null, "", "{}")
            val result = PanelApi().clientLinks(config, client)
            worker.join(5_000)
            assertFalse("Fixture thread did not finish", worker.isAlive)
            serverFailure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
            assertEquals(listOf("GET /panel/api/clients/links/" + encode(client.email) + " HTTP/1.1",
                "POST /panel/api/setting/all HTTP/1.1"), requests)
            if (connectionUnavailable) {
                assertEquals(emptyList<String>(), result.connections)
            } else {
                assertEquals(ru + " Имя", decode(result.connections.single().substringAfter('#')))
                assertFalse(result.connections.single().contains("RU%3A"))
            }
            assertEquals(listOf(subscription), result.subscriptions)
        } finally {
            server.close()
            worker.join(5_000)
        }
    }
}
