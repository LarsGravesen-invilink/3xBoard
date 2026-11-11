package app.invilink.xboard

import app.invilink.xboard.panels.InboundDraft
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundDraftTest {
    @Test fun acceptsExplicitNullOptionalSettingsWithoutWeakeningRequiredFields() {
        for (protocol in listOf("vless", "vmess", "trojan", "amneziawg")) {
            val row = InboundDraft.create().update("protocol", protocol).payload().put("id", 42)
                .put("streamSettings", JSONObject.NULL).put("sniffing", JSONObject.NULL).put("allocate", JSONObject.NULL)
            app.invilink.xboard.panels.validateFullInbound(row)
            val parsed = InboundDraft.fromRaw(row.toString())
            assertEquals(protocol, parsed.protocol)
            assertTrue(parsed.matchesServer(row.toString()))
            val missing = JSONObject(row.toString()).apply { remove("settings") }
            assertThrows(IllegalArgumentException::class.java) { app.invilink.xboard.panels.validateFullInbound(missing) }
            val malformed = JSONObject(row.toString()).put("streamSettings", "[]")
            assertThrows(Exception::class.java) { app.invilink.xboard.panels.validateFullInbound(malformed) }
            val absent = JSONObject(row.toString()).apply { remove("sniffing") }
            assertThrows(IllegalArgumentException::class.java) { app.invilink.xboard.panels.validateFullInbound(absent) }
        }
    }
    @Test fun amneziaDefaultsAndEditableSettings() {
        val draft = InboundDraft.create().update("protocol", "amneziawg").update("name", ":RU test")
        val server = JSONObject(draft.payload().getString("settings")).getJSONObject("server")
        assertEquals(1280, server.getInt("mtu"))
        assertEquals("8.8.8.8", server.getString("primaryDns"))
        assertEquals("1.1.1.1", server.getString("secondaryDns"))
        assertTrue(draft.name.startsWith("🇷🇺"))
        assertTrue(draft.port.toInt() in 10000..59999)
        assertEquals(9, JSONObject(draft.update("awg:jc", "9").payload().getString("settings")).getJSONObject("server").getInt("jc"))
        assertThrows(IllegalArgumentException::class.java) { draft.update("awg:jmin", "51").payload() }
    }
    private fun reality(): InboundDraft = InboundDraft.create()
        .update("network", "xhttp")
        .update("security", "reality")
        .update("transport:mode", "packet-up")
        .update("transport:path", "/tunnel")
        .update("transport:host", "cdn.example.test")
        .update("reality:target", "example.test:443")
        .update("reality:serverNames", "example.test, www.example.test")
        .update("generate:reality", "")

    private fun stream(draft: InboundDraft): JSONObject = JSONObject(draft.prepareForSave().payload().getString("streamSettings"))

    @Test
    fun concurrentChangesToUnknownFieldsAreNotOverwritten() {
        val original = InboundDraft.create().payload().put("id", 7).put("futureSetting", "first").put("up", 1)
        val draft = InboundDraft.fromRaw(original.toString())
        assertTrue(draft.matchesServer(JSONObject(original.toString()).put("up", 2).toString()))
        assertFalse(draft.matchesServer(JSONObject(original.toString()).put("futureSetting", "changed").toString()))
        assertFalse(draft.matchesServer(JSONObject(original.toString()).put("newSetting", true).toString()))
    }

    @Test
    fun sniChoicesAcceptUserDomainsBeyondTenWithoutChangingOtherFields() {
        val domains = (1..15).map { "site$it.example.test" }
        val original = InboundDraft.create()
        val changed = original.update("sniCandidates", domains.joinToString("\n"))
        assertEquals(domains, changed.sniChoices())
        assertEquals(domains.last(), changed.update("sniMode", domains.last()).sniMode)
        assertEquals(original.streamSettings, changed.streamSettings)
        assertEquals(original.protocolSettings, changed.protocolSettings)
        assertEquals(original.sniffing, changed.sniffing)
    }

    @Test
    fun sniChoicesNormalizeManualInputAndIgnoreBlankOrInvalidDropdownEntries() {
        val draft = InboundDraft.create().update("sniCandidates", " EXAMPLE.TEST , example.test\n\nпример.рф\nhttps://invalid.test")
        assertEquals(listOf("example.test", InboundDraft.normalizeSni("пример.рф")), draft.sniChoices())
        assertEquals("example.test", draft.update("sniMode", "example.test").sniMode)
    }

    @Test
    fun editingCandidateListDoesNotReplaceTheSelectedSniOrAnySettings() {
        val draft = InboundDraft.create().copy(sniMode = "preserve", sniCustom = "keep.example.test",
            streamSettings = "{\"network\":\"tcp\",\"security\":\"reality\",\"future\":{\"keep\":true}}")
        val changed = draft.update("sniCandidates", "one.example.test")
        assertEquals(listOf("one.example.test"), changed.sniChoices())
        assertEquals("preserve", changed.sniMode)
        assertEquals(draft.sniCustom, changed.sniCustom)
        assertEquals(draft.streamSettings, changed.streamSettings)
    }

    @Test
    fun realitySpiderXDefaultsToSlashAndKeepsAnExplicitPath() {
        val draft = reality()
        assertEquals("/", draft.fieldValue("reality:client:spiderX"))
        val explicit = draft.update("reality:client:spiderX", "/custom")
        assertEquals("/custom", explicit.prepareForSave().fieldValue("reality:client:spiderX"))
        assertEquals(draft.fieldValue("reality:privateKey"), explicit.fieldValue("reality:privateKey"))
        assertEquals(draft.fieldValue("reality:shortIds"), explicit.fieldValue("reality:shortIds"))
    }

    @Test
    fun createsXhttpRealityWithPanelWireKeysAndSeparateVlessDecryption() {
        val draft = reality()
            .update("reality:client:fingerprint", "chrome")
            .update("reality:client:serverName", "example.test")
            .update("reality:client:spiderX", "/index")
            .update("reality:show", "false")
            .update("reality:xver", "1")
            .update("reality:maxTimediff", "60000")
            .update("reality:minClientVer", "1.8.0")
            .update("reality:maxClientVer", "26.1.1")
            .update("reality:mldsa65Seed", "manual-seed")
            .update("reality:client:mldsa65Verify", "manual-verify")
        val payload = draft.prepareForSave().payload()
        val config = JSONObject(payload.getString("streamSettings"))
        assertEquals("none", JSONObject(payload.getString("settings")).getString("decryption"))
        assertEquals("reality", config.getString("security"))
        assertEquals("xhttp", config.getString("network"))
        assertFalse(config.has("reality_settings"))
        val transport = config.getJSONObject("xhttpSettings")
        assertEquals("packet-up", transport.getString("mode"))
        assertEquals("/tunnel", transport.getString("path"))
        assertEquals("cdn.example.test", transport.getString("host"))
        val server = config.getJSONObject("realitySettings")
        assertEquals(43, server.getString("privateKey").length)
        assertEquals(2, server.getJSONArray("serverNames").length())
        assertTrue(server.get("show") is Boolean)
        assertTrue(server.get("maxTimediff") is Number)
        assertEquals(60000L, server.getLong("maxTimediff"))
        assertEquals(1, server.getInt("xver"))
        assertEquals("manual-seed", server.getString("mldsa65Seed"))
        val client = server.getJSONObject("settings")
        assertEquals(43, client.getString("publicKey").length)
        assertFalse(server.has("publicKey"))
        assertEquals("chrome", client.getString("fingerprint"))
        assertEquals("/index", client.getString("spiderX"))
        assertEquals("manual-verify", client.getString("mldsa65Verify"))
    }

    @Test
    fun xhttpSupportsAllSecurityChoicesWithoutForcingTls() {
        val draft = InboundDraft.create().update("network", "xhttp")
        assertEquals(listOf("none", "tls", "reality"), draft.securityChoices())
        assertEquals("none", stream(draft).getString("security"))
        InboundDraft.xhttpModes.forEach { mode ->
            assertEquals(mode, stream(draft.update("transport:mode", mode)).getJSONObject("xhttpSettings").getString("mode"))
        }
        assertThrows(IllegalArgumentException::class.java) { draft.update("transport:mode", "invalid").payload() }
        assertFalse(stream(draft.update("transport:mode", "")).getJSONObject("xhttpSettings").has("mode"))
    }

    @Test
    fun tlsServerFilesAndClientSettingsUseActualPanelPaths() {
        val draft = InboundDraft.create().update("network", "xhttp").update("security", "tls")
            .update("tls:serverName", "example.test")
            .update("tls:certificateFile", "/etc/ssl/fullchain.pem")
            .update("tls:keyFile", "/etc/ssl/privkey.pem")
            .update("tls:alpn", "h2, http/1.1")
            .update("tls:minVersion", "1.2").update("tls:maxVersion", "1.3")
            .update("tls:client:fingerprint", "firefox")
            .update("tls:client:verifyPeerCertByName", "example.test")
            .update("tls:client:pinnedPeerCertSha256", "pin-one, pin-two")
            .update("tls:rejectUnknownSni", "true")
            .update("tls:oneTimeLoading", "false")
            .update("tls:ocspStapling", "3600")
        val config = stream(draft).getJSONObject("tlsSettings")
        assertEquals("example.test", config.getString("serverName"))
        assertEquals("h2", config.getJSONArray("alpn").getString(0))
        assertTrue(config.get("rejectUnknownSni") is Boolean)
        assertEquals("firefox", config.getJSONObject("settings").getString("fingerprint"))
        assertEquals("example.test", config.getJSONObject("settings").getString("verifyPeerCertByName"))
        assertEquals(2, config.getJSONObject("settings").getJSONArray("pinnedPeerCertSha256").length())
        val cert = config.getJSONArray("certificates").getJSONObject(0)
        assertEquals("/etc/ssl/fullchain.pem", cert.getString("certificateFile"))
        assertEquals("/etc/ssl/privkey.pem", cert.getString("keyFile"))
        assertTrue(cert.get("ocspStapling") is Number)
        assertTrue(cert.get("oneTimeLoading") is Boolean)
    }

    @Test
    fun tlsRequiresPlausibleCredentialsAndValidVersionRange() {
        val draft = InboundDraft.create().update("security", "tls").update("tls:serverName", "example.test")
        assertThrows(IllegalArgumentException::class.java) { draft.payload() }
        val files = draft.update("tls:certificateFile", "/server/cert.pem").update("tls:keyFile", "/server/key.pem")
        assertThrows(IllegalArgumentException::class.java) { files.update("tls:minVersion", "1.3").update("tls:maxVersion", "1.2").payload() }
        val inline = draft.update("tls:certificate", "-----BEGIN CERTIFICATE-----\nYWJjZGVmZ2hpamtsbW5vcA==\n-----END CERTIFICATE-----")
            .update("tls:key", "-----BEGIN PRIVATE KEY-----\nYWJjZGVmZ2hpamtsbW5vcA==\n-----END PRIVATE KEY-----")
        assertEquals(3, stream(inline).getJSONObject("tlsSettings").getJSONArray("certificates").getJSONObject(0).getJSONArray("certificate").length())
        assertThrows(IllegalArgumentException::class.java) { inline.update("tls:key", "not PEM").payload() }
    }

    @Test
    fun unknownFieldsAndLegacyDestSurviveEditingAndRepeatedSave() {
        val raw = reality().prepareForSave().payload().put("id", 7).put("futureRoot", JSONObject("{\"value\":9}"))
        val config = JSONObject(raw.getString("streamSettings"))
        config.put("futureStream", JSONObject("{\"flag\":true}"))
        val server = config.getJSONObject("realitySettings")
        server.put("dest", server.get("target")); server.remove("target")
        server.put("futureReality", "keep")
        server.getJSONObject("settings").put("futureClient", "keep-client")
        config.getJSONObject("xhttpSettings").put("futureTransport", 42)
        raw.put("streamSettings", config.toString())
        val settings = JSONObject(raw.getString("settings")).put("futureProtocol", JSONObject("{\"x\":1}"))
        raw.put("settings", settings.toString())
        val original = InboundDraft.fromRaw(raw.toString())
        val edited = original.update("reality:target", "other.example.test:443").update("transport:path", "/changed")
            .update("transport:extra", "{\"scMaxEachPostBytes\":1000000,\"future\":{\"retain\":true}}")
            .update("sockopt", "{\"tcpFastOpen\":true,\"futureSocket\":17}")
        val saved = edited.payload()
        val savedStream = JSONObject(saved.getString("streamSettings"))
        assertEquals(9, saved.getJSONObject("futureRoot").getInt("value"))
        assertTrue(savedStream.getJSONObject("futureStream").getBoolean("flag"))
        assertEquals("keep", savedStream.getJSONObject("realitySettings").getString("futureReality"))
        assertEquals("keep-client", savedStream.getJSONObject("realitySettings").getJSONObject("settings").getString("futureClient"))
        assertEquals("other.example.test:443", savedStream.getJSONObject("realitySettings").getString("dest"))
        assertFalse(savedStream.getJSONObject("realitySettings").has("target"))
        assertEquals(42, savedStream.getJSONObject("xhttpSettings").getInt("futureTransport"))
        assertTrue(savedStream.getJSONObject("xhttpSettings").getJSONObject("extra").getJSONObject("future").getBoolean("retain"))
        assertEquals(17, savedStream.getJSONObject("sockopt").getInt("futureSocket"))
        assertEquals(1, JSONObject(saved.getString("settings")).getJSONObject("futureProtocol").getInt("x"))
        assertEquals(original.fieldValue("reality:privateKey"), edited.fieldValue("reality:privateKey"))
        assertEquals(saved.toString(), InboundDraft.fromRaw(saved.toString()).payload().toString())
        assertEquals("/tunnel", original.fieldValue("transport:path"))
    }

    @Test
    fun invalidAdvancedJsonNeverMutatesOriginalDraft() {
        val draft = reality()
        val before = draft.payload().toString()
        listOf("{\"a\":1,\"a\":2}", "{\"a\":1} trailing", "{a:1}", "[]", "{\"a\":01}", "{\"a\":true,}").forEach { json ->
            assertThrows(IllegalArgumentException::class.java) { draft.validateAdvancedJson("streamSettings", json) }
            assertThrows(IllegalArgumentException::class.java) { draft.update("streamSettings", json).payload() }
        }
        assertThrows(IllegalArgumentException::class.java) { draft.validateAdvancedJson("streamSettings", "{\"security\":\"none\"}") }
        assertEquals(before, draft.payload().toString())
    }

    @Test
    fun invalidNumericInputRemainsVisibleAndBlocksSaveUntilFixed() {
        val original = reality().update("reality:maxTimediff", "100")
        val invalid = original.update("reality:maxTimediff", "-")
        assertEquals("-", invalid.fieldValue("reality:maxTimediff"))
        assertEquals("100", original.fieldValue("reality:maxTimediff"))
        assertThrows(IllegalArgumentException::class.java) { invalid.payload() }
        assertEquals(200L, stream(invalid.update("reality:maxTimediff", "200")).getJSONObject("realitySettings").getLong("maxTimediff"))
        assertThrows(IllegalArgumentException::class.java) { reality().update("reality:xver", "3").payload() }
    }

    @Test
    fun visionIsRejectedOnXhttpWithoutSilentlyClearingClientFlow() {
        val settings = "{\"decryption\":\"none\",\"clients\":[{\"id\":\"fixture\",\"flow\":\"xtls-rprx-vision\"}]}"
        val xhttp = reality().update("protocolSettings", settings)
        assertNotNull(xhttp.flowWarning())
        assertThrows(IllegalArgumentException::class.java) { xhttp.payload() }
        assertEquals(settings, xhttp.protocolSettings)
        assertEquals("tcp", stream(xhttp.update("network", "tcp")).getString("network"))
    }

    @Test
    fun explicitStreamJsonDoesNotRandomizeManualSniOrReplaceKeys() {
        val original = reality()
        val config = JSONObject(original.streamSettings)
        config.getJSONObject("realitySettings").put("serverNames", org.json.JSONArray().put("manual.example.test"))
        val draft = original.update("sniMode", "random").update("streamSettings", config.toString())
        assertEquals("preserve", draft.sniMode)
        val saved = stream(draft).getJSONObject("realitySettings")
        assertEquals("manual.example.test", saved.getJSONArray("serverNames").getString(0))
        assertEquals(original.fieldValue("reality:privateKey"), saved.getString("privateKey"))
    }

    @Test
    fun generationIsExplicitAndPreservesExistingShortIdsAndUnknowns() {
        val draft = reality().update("reality:shortIds", "aabb, ccdd")
        val first = stream(draft)
        assertEquals(first.toString(), stream(draft).toString())
        val regenerated = draft.generate("reality")
        assertNotEquals(draft.fieldValue("reality:privateKey"), regenerated.fieldValue("reality:privateKey"))
        assertNotEquals(draft.fieldValue("reality:client:publicKey"), regenerated.fieldValue("reality:client:publicKey"))
        assertEquals("aabb, ccdd", regenerated.fieldValue("reality:shortIds"))
        assertEquals(2, stream(regenerated).getJSONObject("realitySettings").getJSONArray("serverNames").length())
    }
}
