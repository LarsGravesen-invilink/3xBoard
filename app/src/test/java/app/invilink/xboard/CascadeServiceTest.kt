package app.invilink.xboard.panels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CascadeServiceTest {
    @Test fun createdInboundDeletionAllowsClientsAndRejectsIdentityConflicts() {
        val row = JSONObject().put("id", 7).put("tag", "cascade-entry").put("remark", "cascade")
            .put("settings", """{"clients":[{"email":"managed"},{"email":"added"}]}""")
        fun rows(vararg values: JSONObject) = org.json.JSONArray().apply { values.forEach { put(it) } }
        assertEquals(row.toString(), cascadeCreatedInboundForDeletion(rows(row), 7, "cascade-entry", "cascade")!!.toString())
        assertNull(cascadeCreatedInboundForDeletion(rows(), 7, "cascade-entry", "cascade"))
        assertNull(cascadeCreatedInboundForDeletion(rows(), null, "cascade-entry", "cascade"))
        assertThrows(IllegalArgumentException::class.java) { cascadeCreatedInboundForDeletion(rows(row), null, "cascade-entry", "cascade") }
        assertThrows(IllegalArgumentException::class.java) { cascadeCreatedInboundForDeletion(rows(row), 8, "cascade-entry", "cascade") }
        assertThrows(IllegalArgumentException::class.java) { cascadeCreatedInboundForDeletion(rows(row), 7, "other", "cascade") }
        assertThrows(IllegalArgumentException::class.java) { cascadeCreatedInboundForDeletion(rows(row), 7, "cascade-entry", "renamed") }
        assertThrows(IllegalArgumentException::class.java) {
            cascadeCreatedInboundForDeletion(rows(row, JSONObject().put("id", 8).put("tag", "cascade-entry").put("remark", "cascade")), 7, "cascade-entry", "cascade")
        }
        assertNull(cascadeCreatedInboundForDeletion(rows(row), 7, "cascade-entry", "hide cascade"))
        assertNull(cascadeCreatedInboundForDeletion(rows(JSONObject(row.toString()).put("remark", "hide cascade")), 7, "cascade-entry", "cascade"))
    }
    @Test fun hiddenCascadeNamesAreIndependentAndAvoidCollisions() {
        val empty = CascadeOccupiedNames(emptySet(), emptySet())
        val both = selectCascadeNames("🇩🇪", "🇺🇸", empty, hideEntry = true, hideExit = true)
        assertTrue(both.entryInbound.startsWith("hide "))
        assertTrue(both.exitInbound.startsWith("hide "))
        val one = selectCascadeNames("🇩🇪", "🇺🇸", CascadeOccupiedNames(setOf(both.entryInbound), emptySet()), hideEntry = true)
        assertNotEquals(both.entryInbound, one.entryInbound)
        assertFalse(one.exitInbound.startsWith("hide "))
    }
    @Test fun wizardGeneratesRealityForBothTransports() {
        val service = CascadeService(Gateway(), Journal())
        for (network in listOf("tcp", "xhttp")) {
            val draft = service.realityInbound("Test $network", "inbound-$network", 12345,
                "www.microsoft.com", network, "/wizard", "chrome")
            val payload = draft.payload()
            val stream = JSONObject(payload.getString("streamSettings"))
            assertEquals(network, stream.getString("network"))
            assertEquals("reality", stream.getString("security"))
            assertTrue(JSONObject(payload.getString("sniffing")).getBoolean("enabled"))
            assertEquals(0, JSONObject(payload.getString("settings")).getJSONArray("clients").length())
            if (network == "xhttp") assertEquals("/wizard", stream.getJSONObject("xhttpSettings").getString("path"))
        }
    }

    @Test fun existingTagReferencesFailClosed() {
        val inbound = JSONObject().put("tag", "inbound-7")
        requireCascadeTagUnused(JSONObject("""{"routing":{"rules":[{"inboundTag":["other"]}]}}"""), inbound)
        for (template in listOf(
            """{"routing":{"rules":[{"inboundTag":["inbound-7"]}]}}""",
            """{"other":{"nested":["prefix-inbound-7-suffix"]}}""",
            """{"inbound-7":true}"""
        )) assertThrows(IllegalArgumentException::class.java) { requireCascadeTagUnused(JSONObject(template), inbound) }
        assertThrows(IllegalArgumentException::class.java) { requireCascadeTagUnused(JSONObject(), JSONObject()) }
    }

    @Test fun snapshotProtectsInboundBeforeIdIsConfirmed() {
        val m = CascadeMetadata("cascade", "entry", "exit", "in", "out", "link", "a", "b",
            CascadeStep.ENTRY_INBOUND, inFlight = true, entryInboundCreated = false,
            entryInboundSnapshot = """{"id":7} """ )
        assertTrue(cascadeOwnsInbound(m, "entry", 7))
        assertFalse(cascadeOwnsInbound(m, "exit", 7))
        assertFalse(cascadeOwnsInbound(m, "entry", 8))
        assertTrue(cascadeOwnsInbound(m.copy(exitInboundId = 9), "exit", 9))
    }

    @Test fun existingInboundInspectionFailsClosed() {
        fun inbound() = JSONObject("""{"id":7,"port":443,"remark":"public","protocol":"vless","settings":"{\"clients\":[]}","streamSettings":"{\"security\":\"reality\"}"}""")
        val empty = org.json.JSONArray()
        requireCascadeReusableInbound(inbound(), empty, 7, 443)
        requireCascadeReusableInbound(inbound().put("remark", "hide secret"), empty, 7, 443)
        assertFalse(cascadeInboundSafeToDelete(inbound().put("remark", "hide secret"), empty))
        assertThrows(IllegalArgumentException::class.java) { requireCascadeReusableInbound(inbound(), null, 7, 443) }
        assertThrows(IllegalArgumentException::class.java) { requireCascadeReusableInbound(inbound(), empty, 8, 443) }
        assertThrows(IllegalArgumentException::class.java) { requireCascadeReusableInbound(inbound(), empty, 7, 444) }
        assertThrows(IllegalArgumentException::class.java) { requireCascadeReusableInbound(inbound().put("protocol", "trojan"), empty, 7, 443) }
        assertThrows(IllegalArgumentException::class.java) { requireCascadeReusableInbound(inbound().put("streamSettings", "{}"), empty, 7, 443) }
        assertThrows(IllegalArgumentException::class.java) { requireCascadeReusableInbound(inbound().put("settings", "{}"), empty, 7, 443) }
        assertThrows(IllegalArgumentException::class.java) { requireCascadeReusableInbound(inbound().put("settings", """{"clients":[{}]}"""), empty, 7, 443) }
        assertThrows(IllegalArgumentException::class.java) { requireCascadeReusableInbound(inbound(), org.json.JSONArray("""[{"inboundIds":[7]}]"""), 7, 443) }
        assertThrows(IllegalArgumentException::class.java) { requireCascadeReusableInbound(inbound(), org.json.JSONArray("""[{}]"""), 7, 443) }
    }
    private fun migrationBaseline(clients: String): InboundDraft {
        val draft = InboundDraft.create().update("security", "reality")
            .update("reality:target", "example.test:443")
            .update("reality:serverNames", "example.test")
            .update("generate:reality", "").copy(sniMode = "preserve")
            .copy(protocolSettings = """{"decryption":"none","clients":$clients}""")
        return InboundDraft.fromRaw(draft.payload().put("id", 7).toString())
    }

    @Test fun migrationPayloadPreservesClientsAndOrdinaryValidationRemainsStrict() {
        val baseline = migrationBaseline("""[{"email":"managed","id":"uuid","flow":"xtls-rprx-vision","extra":42},{"email":"other","id":"other","flow":""}]""")
        val draft = baseline.update("network", "xhttp").update("transport:path", "/tunnel")
        assertThrows(IllegalArgumentException::class.java) { draft.payload() }
        val payload = cascadeInboundPayload(draft, baseline, "managed")
        assertEquals(JSONObject(baseline.protocolSettings).getJSONArray("clients").toString(),
            JSONObject(payload.getString("settings")).getJSONArray("clients").toString())
        assertEquals("xhttp", JSONObject(payload.getString("streamSettings")).getString("network"))
        assertEquals("xtls-rprx-vision", JSONObject(draft.protocolSettings).getJSONArray("clients").getJSONObject(0).getString("flow"))
    }

    @Test fun migrationPayloadFailsClosedForAmbiguousOrChangedClients() {
        val cases = listOf(
            """[{"email":"managed","id":"uuid","flow":"xtls-rprx-vision"},{"email":"other","flow":"xtls-rprx-vision"}]""",
            """[{"email":"managed","id":"uuid","flow":"xtls-rprx-vision"},{"email":"managed","id":"other","flow":""}]""",
            """[{"email":"managed","id":"uuid","flow":""}]""",
            """[{"email":"managed","id":"uuid","flow":"xtls-rprx-vision"},{"email":"other","flow":null}]"""
        )
        for (clients in cases) {
            val baseline = migrationBaseline(clients)
            val draft = baseline.update("network", "xhttp").update("transport:path", "/tunnel")
            assertThrows(IllegalArgumentException::class.java) { cascadeInboundPayload(draft, baseline, "managed") }
        }
        val baseline = migrationBaseline("""[{"email":"managed","id":"uuid","flow":"xtls-rprx-vision"}]""")
        val draft = baseline.update("network", "xhttp").copy(protocolSettings = """{"decryption":"none","clients":[]}""")
        assertThrows(IllegalArgumentException::class.java) { cascadeInboundPayload(draft, baseline, "managed") }
        assertThrows(IllegalArgumentException::class.java) { cascadeInboundPayload(draft.copy(port = "0"), baseline, "managed") }
    }

    @Test fun migrationPayloadStillValidatesOtherFields() {
        val baseline = migrationBaseline("""[{"email":"managed","id":"uuid","flow":"xtls-rprx-vision"}]""")
        val draft = baseline.update("network", "xhttp").copy(port = "0")
        assertThrows(IllegalArgumentException::class.java) { cascadeInboundPayload(draft, baseline, "managed") }
        assertThrows(IllegalArgumentException::class.java) { cascadeInboundPayload(draft.copy(id = 8), baseline, "managed") }
        val duplicate = draft.copy(port = "443", protocolSettings = baseline.protocolSettings.replace("\"decryption\"", "\"decryption\":\"none\",\"decryption\""))
        assertThrows(IllegalArgumentException::class.java) { cascadeInboundPayload(duplicate, baseline, "managed") }
    }

    @Test fun cascadeSniUpdatesOnlyMatchingRealityDestination() {
        val original = JSONObject("""{"security":"reality","network":"tcp","realitySettings":{"serverNames":["old.example"],"target":"old.example:443","settings":{}}}""")
        val next = JSONObject(original.toString())
        next.getJSONObject("realitySettings").put("serverNames", org.json.JSONArray().put("new.example"))
        val draft = InboundDraft(streamSettings = next.toString(), security = "reality", sniMode = "preserve")
        val result = prepareCascadeRealitySni(original, draft)
        assertEquals("new.example:443", JSONObject(result.streamSettings).getJSONObject("realitySettings").getString("target"))
        val custom = JSONObject(original.toString())
        custom.getJSONObject("realitySettings").put("target", "different.example:443")
        val customNext = JSONObject(custom.toString())
        customNext.getJSONObject("realitySettings").put("serverNames", org.json.JSONArray().put("new.example"))
        val customDraft = draft.copy(streamSettings = customNext.toString())
        val preserved = prepareCascadeRealitySni(custom, customDraft)
        assertEquals("different.example:443", JSONObject(preserved.streamSettings).getJSONObject("realitySettings").getString("target"))
        assertEquals("preserve", preserved.sniMode)
    }

    @Test fun cascadeSniHandlesTargetAndDestIndependently() {
        val original = JSONObject("""{"security":"reality","realitySettings":{"serverNames":["old.example"],"target":"old.example:8443","dest":"custom.example:443","settings":{"serverName":"old.example"}}}""")
        val next = JSONObject(original.toString())
        next.getJSONObject("realitySettings").put("serverNames", org.json.JSONArray().put("new.example"))
        val result = prepareCascadeRealitySni(original, InboundDraft(streamSettings = next.toString(), security = "reality", sniMode = "preserve"))
        val reality = JSONObject(result.streamSettings).getJSONObject("realitySettings")
        assertEquals("new.example:8443", reality.getString("target"))
        assertEquals("custom.example:443", reality.getString("dest"))
        assertEquals("new.example", reality.getJSONObject("settings").getString("serverName"))
        original.getJSONObject("realitySettings").getJSONObject("settings").put("serverName", "custom.example")
        next.getJSONObject("realitySettings").getJSONObject("settings").put("serverName", "new.example")
        val custom = prepareCascadeRealitySni(original, InboundDraft(streamSettings = next.toString(), security = "reality", sniMode = "preserve"))
        assertEquals("custom.example", JSONObject(custom.streamSettings).getJSONObject("realitySettings").getJSONObject("settings").getString("serverName"))
    }

    @Test fun cascadeSniRejectsUnauthorizedDestinationChanges() {
        for (field in listOf("target", "dest")) {
            for (oldValue in listOf("old.example:443", "custom.example:443", "old.example:65536")) {
                val original = JSONObject("""{"security":"reality","realitySettings":{"serverNames":["old.example"],"settings":{}}}""")
                original.getJSONObject("realitySettings").put(field, oldValue)
                fun draft(value: String?): InboundDraft {
                    val next = JSONObject(original.toString())
                    val reality = next.getJSONObject("realitySettings")
                    reality.put("serverNames", org.json.JSONArray().put("new.example"))
                    if (value == null) reality.remove(field) else reality.put(field, value)
                    return InboundDraft(streamSettings = next.toString(), security = "reality", sniMode = "preserve")
                }
                assertThrows(IllegalArgumentException::class.java) { prepareCascadeRealitySni(original, draft("attacker.example:443")) }
                assertThrows(IllegalArgumentException::class.java) { prepareCascadeRealitySni(original, draft(null)) }
                if (oldValue != "old.example:443") {
                    assertThrows(IllegalArgumentException::class.java) { prepareCascadeRealitySni(original, draft("new.example:443")) }
                    val preserved = prepareCascadeRealitySni(original, draft(oldValue))
                    assertEquals(oldValue, JSONObject(preserved.streamSettings).getJSONObject("realitySettings").getString(field))
                } else {
                    val updated = prepareCascadeRealitySni(original, draft("new.example:443"))
                    assertEquals("new.example:443", JSONObject(updated.streamSettings).getJSONObject("realitySettings").getString(field))
                }
            }
        }
        val numeric = JSONObject("""{"security":"reality","realitySettings":{"serverNames":["old.example"],"target":443}}""")
        val numericNext = JSONObject(numeric.toString())
        numericNext.getJSONObject("realitySettings").put("serverNames", org.json.JSONArray().put("new.example"))
        val numericResult = prepareCascadeRealitySni(numeric, InboundDraft(streamSettings = numericNext.toString(), security = "reality", sniMode = "preserve"))
        assertEquals(443, JSONObject(numericResult.streamSettings).getJSONObject("realitySettings").getInt("target"))
        numericNext.getJSONObject("realitySettings").put("target", 8443)
        assertThrows(IllegalArgumentException::class.java) {
            prepareCascadeRealitySni(numeric, InboundDraft(streamSettings = numericNext.toString(), security = "reality", sniMode = "preserve"))
        }
        val original = JSONObject("""{"security":"reality","realitySettings":{"serverNames":["old.example"],"target":"custom.example:443"}}""")
        val next = JSONObject(original.toString())
        next.getJSONObject("realitySettings").put("serverNames", org.json.JSONArray().put("new.example")).put("dest", "injected.example:443")
        assertThrows(IllegalArgumentException::class.java) {
            prepareCascadeRealitySni(original, InboundDraft(streamSettings = next.toString(), security = "reality", sniMode = "preserve"))
        }
    }

    @Test fun cascadeGlowTracksFailuresAndConfirmedRecovery() {
        val online = PanelMetrics(availability = PanelAvailability.ONLINE, latencyMillis = 100)
        val offline = PanelMetrics(availability = PanelAvailability.OFFLINE)
        val unknown = CascadeProbe(CascadeProbeStatus.UNKNOWN)
        val connected = CascadeProbe(CascadeProbeStatus.CONNECTED, 100)
        val first = nextCascadeHealth(null, 1, 1, offline, online, unknown)
        assertEquals(CascadeGlow.WARNING, first.glow)
        val second = nextCascadeHealth(first, 2, 2, offline, online, unknown)
        assertEquals(CascadeGlow.ERROR, second.glow)
        assertTrue(second.redLatched)
        val unconfirmed = nextCascadeHealth(second, 3, 3, online, online, unknown)
        assertEquals(CascadeGlow.ERROR, unconfirmed.glow)
        val restored = nextCascadeHealth(unconfirmed, 4, 4, online, online, connected)
        assertEquals(CascadeGlow.NORMAL, restored.glow)
        assertFalse(restored.redLatched)
        val probeFailed = CascadeProbe(CascadeProbeStatus.FAILED)
        val linkFirst = nextCascadeHealth(restored, 5, 5, online, online, probeFailed)
        val linkSecond = nextCascadeHealth(linkFirst, 6, 6, online, online, probeFailed)
        assertEquals(CascadeGlow.WARNING, linkFirst.glow)
        assertEquals(CascadeGlow.ERROR, linkSecond.glow)
        val uncertain = nextCascadeHealth(linkFirst, 7, 7, online, online, unknown)
        assertEquals(CascadeGlow.UNKNOWN, uncertain.glow)
        assertEquals(CascadeGlow.WARNING, nextCascadeHealth(uncertain, 8, 8, online, online, probeFailed).glow)
    }

    @Test fun slowPingThresholdCoversAllCascadeMeasurements() {
        val online = PanelMetrics(availability = PanelAvailability.ONLINE, latencyMillis = 500)
        val connected = CascadeProbe(CascadeProbeStatus.CONNECTED, 500)
        assertFalse(isSlowPing(null))
        assertFalse(isSlowPing(500))
        assertTrue(isSlowPing(501))
        assertEquals(CascadeGlow.NORMAL, nextCascadeHealth(null, 1, 1, online, online, connected).glow)
        assertEquals(CascadeGlow.WARNING, nextCascadeHealth(null, 1, 1, online.copy(latencyMillis = 501), online, connected).glow)
        assertEquals(CascadeGlow.WARNING, nextCascadeHealth(null, 1, 1, online, online.copy(latencyMillis = 501), connected).glow)
        assertEquals(CascadeGlow.WARNING, nextCascadeHealth(null, 1, 1, online, online, connected.copy(latencyMillis = 501)).glow)
    }

    @Test fun cascadeIconColorIsStableAndWithinPalette() {
        val ids = (0..40).map { "cascade-$it" } + "polygenelubricants"
        val colors = ids.map { cascadeIconColorIndex(it, 8) }
        assertTrue(colors.all { it in 0..7 })
        assertEquals(colors, ids.map { cascadeIconColorIndex(it, 8) })
        assertTrue(colors.toSet().size > 1)
        assertThrows(IllegalArgumentException::class.java) { cascadeIconColorIndex("cascade", 0) }
    }

    @Test fun routingVerificationAcceptsPanelApiRuleButRejectsConflicts() {
        val outbound = JSONObject("""{"tag":"cascade-out","protocol":"vless","settings":{"address":"example.test"}}""")
        val api = JSONObject("""{"type":"field","inboundTag":["api"],"outboundTag":"api"}""")
        val route = JSONObject("""{"type":"field","inboundTag":["cascade-in"],"outboundTag":"cascade-out"}""")
        fun template(vararg rules: JSONObject) = JSONObject()
            .put("outbounds", org.json.JSONArray().put(outbound))
            .put("routing", JSONObject().put("rules", org.json.JSONArray(rules.toList())))
        assertTrue(cascadeRoutingMatches(template(api, route), "cascade-in", "cascade-out", outbound))
        assertTrue(cascadeRoutingMatches(template(route), "cascade-in", "cascade-out", outbound))
        assertFalse(cascadeRoutingMatches(template(api), "cascade-in", "cascade-out", outbound))
        val blocker = JSONObject("""{"type":"field","network":"tcp,udp","outboundTag":"direct"}""")
        assertFalse(cascadeRoutingMatches(template(blocker, route), "cascade-in", "cascade-out", outbound))
        assertFalse(cascadeRoutingMatches(template(api, route, route), "cascade-in", "cascade-out", outbound))
        val limited = JSONObject(route.toString()).put("network", "tcp")
        assertFalse(cascadeRoutingMatches(template(api, limited), "cascade-in", "cascade-out", outbound))
    }

    @Test fun cleanupRefusesHiddenOrPossiblySharedInbounds() {
        fun row(name: String, embedded: String = "[]") = JSONObject()
            .put("id", 7).put("remark", name)
            .put("settings", JSONObject().put("clients", org.json.JSONArray(embedded)).toString())
        val none = org.json.JSONArray()
        assertTrue(cascadeInboundSafeToDelete(row("ordinary"), none))
        for (name in listOf("hide old", "HiDeprivate", "HIDE")) {
            assertFalse(cascadeInboundSafeToDelete(row(name), none))
        }
        assertFalse(cascadeInboundSafeToDelete(row("ordinary", """[{"email":"external"}]"""), none))
        assertFalse(cascadeInboundSafeToDelete(row("ordinary"), null))
        assertFalse(cascadeInboundSafeToDelete(row("ordinary"), org.json.JSONArray("""[{"inboundIds":[7]}]""")))
        assertFalse(cascadeInboundSafeToDelete(row("ordinary"), org.json.JSONArray("""[{}]""")))
        assertFalse(cascadeInboundSafeToDelete(JSONObject().put("id", 7).put("remark", "ordinary"), none))
    }

    @Test fun cleanupAllowsOnlyConfirmedExclusiveManagedClient() {
        fun row(name: String, rows: String) = JSONObject().put("id", 7).put("remark", name)
            .put("settings", JSONObject().put("clients", org.json.JSONArray(rows)))
        val managed = """[{"email":"managed","id":"uuid"}]"""
        val list = org.json.JSONArray("""[{"email":"managed","inboundIds":[7]}]""")
        val envelope = JSONObject("""{"client":{"email":"managed","uuid":"uuid","enable":true},"inboundIds":[7]}""")
        assertTrue(cascadeInboundSafeToDelete(row("ordinary", managed), list, "managed", envelope))
        assertFalse(cascadeInboundSafeToDelete(row("hide secret", managed), list, "managed", envelope))
        assertFalse(cascadeInboundSafeToDelete(row("ordinary", managed), list, "managed", JSONObject(envelope.toString()).put("inboundIds", org.json.JSONArray("[7,8]"))))
        assertFalse(cascadeInboundSafeToDelete(row("ordinary", managed), list, "managed", JSONObject("""{"client":{"email":"managed","uuid":"other","enable":true},"inboundIds":[7]}""")))
        assertFalse(cascadeInboundSafeToDelete(row("ordinary", """[{"email":"managed","id":"uuid"},{"email":"other","id":"x"}]"""), list, "managed", envelope))
        assertFalse(cascadeInboundSafeToDelete(row("ordinary", managed), org.json.JSONArray(), "managed", envelope))
        assertFalse(cascadeInboundSafeToDelete(row("ordinary", managed), null, "managed", envelope))
    }

    @Test fun captureRejectsChangedInboundAndAdditionalClients() {
        val expected = JSONObject("""{"id":7,"remark":"hide test","tag":"cascade","settings":"{\"clients\":[],\"decryption\":\"none\"}","streamSettings":"{\"network\":\"tcp\"}","sniffing":"{}","allocate":"{}"}""")
        val expectedClient = JSONObject("""{"email":"managed","id":"uuid","subId":"sub","flow":"","enable":true}""")
        fun row(): JSONObject = JSONObject(expected.toString()).put("settings", JSONObject("""{"clients":[{"email":"managed","id":"uuid","subId":"sub","enable":true}],"decryption":"none"}""").toString())
        val inventory = org.json.JSONArray("""[{"email":"managed","inboundIds":[7]}]""")
        val envelope = JSONObject("""{"client":{"email":"managed","uuid":"uuid","enable":true},"inboundIds":[7]}""")
        assertTrue(cascadeCaptureMatches(row(), inventory, envelope, expected, expectedClient))
        assertFalse(cascadeCaptureMatches(row().put("tag", "changed"), inventory, envelope, expected, expectedClient))
        assertFalse(cascadeCaptureMatches(row().put("streamSettings", "{}"), inventory, envelope, expected, expectedClient))
        val extra = row()
        val settings = JSONObject(extra.getString("settings"))
        settings.getJSONArray("clients").put(JSONObject("""{"email":"custom","id":"other"}"""))
        extra.put("settings", settings.toString())
        assertFalse(cascadeCaptureMatches(extra, inventory, envelope, expected, expectedClient))
        assertFalse(cascadeCaptureMatches(row(), null, envelope, expected, expectedClient))
    }

    @Test fun resumedCleanupRequiresGlobalManagedClientAbsence() {
        assertFalse(cascadeManagedClientAbsent(null, "managed"))
        assertTrue(cascadeManagedClientAbsent(org.json.JSONArray("""[{"email":"custom"}]"""), "managed"))
        assertFalse(cascadeManagedClientAbsent(org.json.JSONArray("""[{"email":"managed","inboundIds":[]}]"""), "managed"))
        assertFalse(cascadeManagedClientAbsent(org.json.JSONArray("""[{}]"""), "managed"))
    }

    @Test fun inboundClientVerificationUsesActualInboundAndEssentialFields() {
        val expected = JSONObject("""{"email":"🇩🇪→🇺🇸Каскад1","id":"uuid","subId":"sub","flow":"","enable":true,"trafficResetDay":1,"limitHwid":0}""")
        val client = JSONObject("""{"email":"🇩🇪→🇺🇸Каскад1","id":"uuid","subId":"sub","enable":true}""")
        val settings = JSONObject().put("clients", org.json.JSONArray().put(client))
        assertTrue(cascadeInboundClientMatches(JSONObject().put("settings", settings), expected))
        assertTrue(cascadeInboundClientMatches(JSONObject().put("settings", settings.toString()), expected))
        client.put("id", "wrong")
        assertFalse(cascadeInboundClientMatches(JSONObject().put("settings", settings), expected))
        client.put("id", "uuid").put("enable", false)
        assertFalse(cascadeInboundClientMatches(JSONObject().put("settings", settings), expected))
        client.remove("enable")
        assertFalse(cascadeInboundClientMatches(JSONObject().put("settings", settings), expected))
    }

    private fun request() = CascadeRequest("test-id", "entry", "exit", 443, 8443,
        "exit.example.test", "entry.example.test", "exit.example.test", entryName = ":DE:", exitName = ":US:", allowXrayRestart = true)

    private class Journal : CascadeJournal {
        val history = mutableListOf<CascadeMetadata>()
        var reserved = false
        var removed = false
        override suspend fun remove(id: String) { removed = true }
        override suspend fun entries(): List<CascadeMetadata> = history.lastOrNull()?.let(::listOf).orEmpty()
        override suspend fun reserve(metadata: CascadeMetadata): Boolean {
            if (reserved) return false
            reserved = true
            history += metadata
            return true
        }
        override suspend fun save(metadata: CascadeMetadata) { history += metadata }
    }

    private class Gateway : CascadePanelGateway {
        val calls = mutableListOf<String>()
        var failAt: String? = null
        var cancelAt: String? = null
        var route: Boolean? = null
        var entry: InboundDraft? = null
        var exit: InboundDraft? = null
        var entryClient: ClientDraft? = null
        var exitClient: ClientDraft? = null
        var outbound = ""
        var occupied = CascadeOccupiedNames(emptySet(), emptySet())
        override suspend fun occupiedNames(panelId: String): CascadeOccupiedNames = occupied
        private fun call(name: String) {
            calls += name
            if (name == cancelAt) throw CancellationException(name)
            if (name == failAt) error(name)
        }
        var cleanupMetadata: CascadeMetadata? = null
        override suspend fun cleanup(metadata: CascadeMetadata) { cleanupMetadata = metadata; call("cleanup") }
        override suspend fun preflight(request: CascadeRequest) = call("preflight")
        override suspend fun snapshotExistingInbound(panelId: String, inboundId: Long, port: Int): String {
            call("snapshot:$panelId")
            return InboundDraft.create().update("security", "reality")
                .update("reality:target", "example.test:443").update("reality:serverNames", "example.test")
                .update("generate:reality", "").copy(name = "hide $panelId", port = port.toString(), sniMode = "preserve")
                .payload().put("id", inboundId).toString()
        }
        override suspend fun updateExistingInbound(panelId: String, snapshot: String, draft: InboundDraft): Long {
            call("update:$panelId")
            if (panelId == "entry") entry = draft else exit = draft
            return checkNotNull(draft.id)
        }
        override suspend fun captureInbound(panelId: String, inboundId: Long, draft: InboundDraft, client: ClientDraft): String {
            call("capture:$panelId")
            return checkNotNull(if (panelId == "entry") entry else exit).payload().put("id", inboundId).toString()
        }
        override suspend fun createInbound(panelId: String, draft: InboundDraft): Long {
            call("inbound:$panelId")
            if (panelId == "entry") entry = draft else exit = draft
            return if (panelId == "entry") 11 else 22
        }
        override suspend fun createClient(panelId: String, inboundId: Long, client: ClientDraft) {
            call("client:$panelId:$inboundId")
            if (panelId == "entry") entryClient = client else exitClient = client
        }
        override suspend fun installRouting(panelId: String, inboundTag: String, outboundTag: String, outbound: String) {
            call("routing:$panelId")
            assertEquals(entry!!.payload().getString("tag"), inboundTag)
            assertEquals(JSONObject(outbound).getString("tag"), outboundTag)
            this.outbound = outbound
        }
        override suspend fun restartAndCheck(panelId: String) = call("restart:$panelId")
        override suspend fun verifyConfiguration(request: CascadeRequest, metadata: CascadeMetadata,
            entry: InboundDraft, entryClient: ClientDraft, exit: InboundDraft, exitClient: ClientDraft, outbound: String) {
            call("configuration")
            assertEquals(this.entry, entry); assertEquals(this.exit, exit)
            assertEquals(this.entryClient, entryClient); assertEquals(this.exitClient, exitClient)
            assertEquals(this.outbound, outbound)
            assertTrue(metadata.inFlight)
        }
        override suspend fun verify(request: CascadeRequest, metadata: CascadeMetadata): Boolean? {
            call("verify")
            return route
        }
    }

    @Test fun existingInboundsAreRejectedBeforeAnyChanges() = runBlocking {
        for ((entryId, exitId) in listOf(7L to null, null to 8L, 7L to 8L)) {
            val gateway = Gateway()
            val journal = Journal()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { CascadeService(gateway, journal).create(request().copy(entryInboundId = entryId, exitInboundId = exitId)) }
            }
            assertTrue(gateway.calls.isEmpty())
            assertTrue(journal.history.isEmpty())
        }
    }

    @Test fun removalKeepsJournalOnFailureAndCanBeRetried() = runBlocking {
        val gateway = Gateway().apply { failAt = "cleanup" }
        val journal = Journal()
        val metadata = CascadeMetadata("id", "entry", "exit", "in", "out", "link", "a", "b",
            CascadeStep.COMPLETE, entryInboundId = 7, exitInboundId = 8)
        val service = CascadeService(gateway, journal)
        assertThrows(IllegalStateException::class.java) { runBlocking { service.remove(metadata) } }
        assertFalse(journal.removed)
        gateway.failAt = null
        service.remove(metadata)
        assertTrue(journal.removed)
        assertEquals(2, gateway.calls.count { it == "cleanup" })
    }

    @Test fun untouchedExistingInboundDoesNotRequirePostCreationSnapshot() = runBlocking {
        val gateway = Gateway()
        val journal = Journal()
        val metadata = CascadeMetadata("id", "entry", "exit", "in", "out", "link", "a", "b",
            CascadeStep.ENTRY_INBOUND, entryInboundCreated = false, exitInboundCreated = false,
            entryInboundSnapshot = """{"id":7}""", exitInboundSnapshot = """{"id":8}""")
        CascadeService(gateway, journal).remove(metadata)
        assertTrue(journal.removed)
    }

    @Test fun legacyExistingJournalFailsClosedAndKeepsJournal() = runBlocking {
        val gateway = Gateway()
        val journal = Journal()
        val metadata = CascadeMetadata("id", "entry", "exit", "in", "out", "link", "a", "b",
            CascadeStep.COMPLETE, entryInboundId = 7, exitInboundId = 8,
            entryInboundCreated = false, entryInboundSnapshot = """{"id":7}""")
        assertThrows(IllegalStateException::class.java) { runBlocking { CascadeService(gateway, journal).remove(metadata) } }
        assertFalse(journal.removed)
        assertNull(gateway.cleanupMetadata)
    }

    @Test fun completeFlowRecordsAllTransitionsAndResults() = runBlocking {
        for ((route, expected) in listOf(true to CascadeResult.ROUTE_VERIFIED, false to CascadeResult.ROUTE_FAILED, null to CascadeResult.APPLIED_UNVERIFIED)) {
            val gateway = Gateway().apply { this.route = route }
            val journal = Journal()
            val result = CascadeService(gateway, journal).create(request())
            assertEquals(expected, result.result)
            assertEquals(CascadeStep.COMPLETE, result.step)
            assertEquals(CascadeCompatibility.NOT_APPLICABLE, result.compatibility)
            assertEquals(listOf("preflight", "inbound:entry", "client:entry:11", "inbound:exit", "client:exit:22", "routing:entry", "restart:exit", "restart:entry", "configuration", "verify"), gateway.calls)
            assertEquals(18, journal.history.size)
            assertEquals(CascadeStep.ENTRY_INBOUND, journal.history.first().step)
            assertFalse(journal.history.first().inFlight)
            CascadeStep.entries.filter { it != CascadeStep.COMPLETE }.forEachIndexed { index, step ->
                assertEquals(step, journal.history[1 + index * 2].step)
                assertTrue(journal.history[1 + index * 2].inFlight)
                assertEquals(step, journal.history[2 + index * 2].step)
                assertFalse(journal.history[2 + index * 2].inFlight)
            }
            assertEquals(result, journal.history.last())
        }
    }

    @Test fun generatedTcpAndXhttpConfigsAgreeAcrossBothEnds() = runBlocking {
        for (transport in CascadeTransport.entries) {
            val gateway = Gateway()
            val result = CascadeService(gateway, Journal()).create(request().copy(transport = transport, fingerprint = "firefox"))
            val entry = gateway.entry!!.payload()
            val exit = gateway.exit!!.payload()
            fun detail(payload: JSONObject): InboundDetail = InboundDetail(
                1, payload.getString("remark"), payload.getString("protocol"), payload.getInt("port"), true,
                if (transport == CascadeTransport.TCP) "tcp" else "xhttp", "reality",
                null, null, null, null, emptyList(), payload.getString("settings"))
            val entryClientPayload = gateway.entryClient!!.payload(detail(entry))
            val exitClientPayload = gateway.exitClient!!.payload(detail(exit))
            assertEquals(1L, entryClientPayload.getLong("trafficResetDay"))
            assertEquals(1L, exitClientPayload.getLong("trafficResetDay"))
            assertEquals(if (transport == CascadeTransport.TCP) "xtls-rprx-vision" else "", exitClientPayload.optString("flow"))
            val entryStream = JSONObject(entry.getString("streamSettings"))
            val exitStream = JSONObject(exit.getString("streamSettings"))
            val out = JSONObject(gateway.outbound)
            val stream = out.getJSONObject("streamSettings")
            val reality = exitStream.getJSONObject("realitySettings")
            val outReality = stream.getJSONObject("realitySettings")
            val next = out.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            val user = next.getJSONArray("users").getJSONObject(0)
            assertEquals("vless", out.getString("protocol"))
            assertEquals("exit.example.test", next.getString("address"))
            assertEquals(8443, next.getInt("port"))
            assertEquals(result.entryTag, entry.getString("tag"))
            assertEquals("🇩🇪 Германия → 🇺🇸 Соединенные Штаты", entry.getString("remark"))
            assertEquals("🇺🇸 Соединенные Штаты", exit.getString("remark"))
            assertNotEquals(result.entryTag, result.entryInboundName)
            assertEquals(result.entryInboundName, entry.getString("remark"))
            assertEquals(result.exitInboundName, exit.getString("remark"))
            assertTrue(JSONObject(entry.getString("sniffing")).getBoolean("enabled"))
            assertTrue(JSONObject(exit.getString("sniffing")).getBoolean("enabled"))
            assertEquals("🇩🇪→🇺🇸Каскад1", result.entryClientName)
            assertEquals("🇺🇸Каскад1", result.exitClientName)
            assertTrue(result.entryClientName.none(Char::isWhitespace))
            assertTrue(result.exitClientName.none(Char::isWhitespace))
            assertEquals(result.entryClientName, entryClientPayload.getString("email"))
            assertEquals(result.exitClientName, exitClientPayload.getString("email"))
            assertEquals(result.exitTag, exit.getString("tag"))
            assertEquals(result.outboundTag, out.getString("tag"))
            assertEquals(gateway.exitClient!!.values.getValue("id"), user.getString("id"))
            assertEquals("none", user.getString("encryption"))
            assertEquals(reality.getJSONObject("settings").getString("publicKey"), outReality.getString("publicKey"))
            assertEquals(reality.getJSONArray("shortIds").getString(0), outReality.getString("shortId"))
            assertEquals(reality.getJSONArray("serverNames").getString(0), outReality.getString("serverName"))
            assertEquals("firefox", outReality.getString("fingerprint"))
            assertEquals("reality", stream.getString("security"))
            val network = if (transport == CascadeTransport.TCP) "tcp" else "xhttp"
            assertEquals(network, entryStream.getString("network"))
            assertEquals(network, exitStream.getString("network"))
            assertEquals(network, stream.getString("network"))
            if (transport == CascadeTransport.TCP) {
                assertEquals("xtls-rprx-vision", user.getString("flow"))
                assertEquals("xtls-rprx-vision", gateway.entryClient!!.values["flow"])
                assertEquals("xtls-rprx-vision", gateway.exitClient!!.values["flow"])
                assertFalse(stream.has("xhttpSettings"))
            } else {
                assertFalse(user.has("flow"))
                assertEquals("", gateway.entryClient!!.values["flow"])
                assertEquals("", gateway.exitClient!!.values["flow"])
                assertEquals(CascadeCompatibility.XHTTP_UNVERIFIED, result.compatibility)
                val path = stream.getJSONObject("xhttpSettings").getString("path")
                assertTrue(path.startsWith("/cascade-"))
                assertEquals(path, entryStream.getJSONObject("xhttpSettings").getString("path"))
                assertEquals(path, exitStream.getJSONObject("xhttpSettings").getString("path"))
                assertEquals("auto", stream.getJSONObject("xhttpSettings").getString("mode"))
            }
        }
    }

    @Test fun namesSkipPanelAndJournalCollisions() = runBlocking {
        val base = cascadeNames(1, ":DE:", ":US:")
        val gateway = Gateway().apply { occupied = CascadeOccupiedNames(setOf(base.entryInbound), setOf("🇺🇸Каскад2")) }
        val journal = Journal()
        val result = CascadeService(gateway, journal).create(request())
        assertEquals(cascadeNames(2, ":DE:", ":US:").entryInbound, result.entryInboundName)
        assertEquals(cascadeNames(2, ":DE:", ":US:").exitInbound, result.exitInboundName)
        assertEquals("🇩🇪→🇺🇸Каскад1", result.entryClientName)
        assertEquals("🇺🇸Каскад1", result.exitClientName)
        val names = selectCascadeNames(":DE:", ":US:", CascadeOccupiedNames(
            setOf(result.entryInboundName), setOf("🇺🇸каскад1")))
        assertEquals(base.entryInbound, names.entryInbound)
    }

    @Test fun journalNamesReserveNumbersEvenWhenPanelsDoNotContainThem() = runBlocking {
        val previous = CascadeMetadata("old", "entry", "exit", "tag1", "tag2", "tag3",
            "🇩🇪 → 🇺🇸 Каскад 1", "🇺🇸 Каскад 1", CascadeStep.ENTRY_INBOUND,
            entryInboundName = cascadeNames(1, ":DE:", ":US:").entryInbound, exitInboundName = cascadeNames(1, ":DE:", ":US:").exitInbound)
        val journal = object : CascadeJournal {
            override suspend fun entries() = listOf(previous)
            override suspend fun reserve(metadata: CascadeMetadata) = true
            override suspend fun save(metadata: CascadeMetadata) = Unit
        }
        val result = CascadeService(Gateway(), journal).create(request())
        assertEquals(cascadeNames(2, ":DE:", ":US:").entryInbound, result.entryInboundName)
    }

    @Test fun eachPartialFailureLeavesAnInFlightJournalAndStops() = runBlocking {
        val stages = listOf("inbound:entry" to CascadeStep.ENTRY_INBOUND, "client:entry:11" to CascadeStep.ENTRY_CLIENT,
            "inbound:exit" to CascadeStep.EXIT_INBOUND, "client:exit:22" to CascadeStep.EXIT_CLIENT,
            "routing:entry" to CascadeStep.ROUTING, "restart:exit" to CascadeStep.RESTART_EXIT,
            "restart:entry" to CascadeStep.RESTART_ENTRY, "configuration" to CascadeStep.VERIFY, "verify" to CascadeStep.VERIFY)
        for ((operation, step) in stages) {
            val gateway = Gateway().apply { failAt = operation }
            val journal = Journal()
            val error = assertThrows(IllegalStateException::class.java) { runBlocking { CascadeService(gateway, journal).create(request()) } }
            assertEquals(operation, error.cause!!.message)
            val metadata = journal.history.last()
            assertNull(gateway.cleanupMetadata)
            assertEquals(step, metadata.step)
            assertTrue(metadata.inFlight)
            assertEquals(operation, gateway.calls.last())
            assertFalse(journal.removed)
            assertEquals(if (step.ordinal > CascadeStep.ENTRY_INBOUND.ordinal) 11L else null, metadata.entryInboundId)
            assertEquals(if (step.ordinal > CascadeStep.EXIT_INBOUND.ordinal) 22L else null, metadata.exitInboundId)
        }
    }

    @Test fun inFlightRemovalNeverCallsGateway() = runBlocking {
        val gateway = Gateway()
        val journal = Journal()
        val metadata = CascadeMetadata("cascade", "entry", "exit", "entry-tag", "exit-tag", "out-tag",
            "entry-client", "exit-client", CascadeStep.ROUTING, inFlight = true, entryInboundId = 11, exitInboundId = 22)
        assertThrows(IllegalStateException::class.java) { runBlocking { CascadeService(gateway, journal).remove(metadata) } }
        assertTrue(gateway.calls.isEmpty())
        assertFalse(journal.removed)
    }

    @Test fun failedCleanupKeepsJournalForRetry() = runBlocking {
        val gateway = Gateway().apply { failAt = "cleanup" }
        val journal = Journal()
        val metadata = CascadeMetadata("cascade", "entry", "exit", "entry-tag", "exit-tag", "out-tag",
            "entry-client", "exit-client", CascadeStep.ENTRY_INBOUND)
        assertThrows(IllegalStateException::class.java) { runBlocking { CascadeService(gateway, journal).remove(metadata) } }
        assertFalse(journal.removed)
        gateway.failAt = null
        CascadeService(gateway, journal).remove(metadata)
        assertTrue(journal.removed)
    }

    @Test fun preflightFailureAndDuplicateReservationNeverMutatePanels() = runBlocking {
        val gateway = Gateway().apply { failAt = "preflight" }
        val journal = Journal()
        assertThrows(IllegalStateException::class.java) { runBlocking { CascadeService(gateway, journal).create(request()) } }
        assertTrue(journal.history.isEmpty())
        val duplicate = Journal().apply { reserved = true }
        val second = Gateway()
        assertThrows(IllegalStateException::class.java) { runBlocking { CascadeService(second, duplicate).create(request()) } }
        assertEquals(listOf("preflight"), second.calls)
        assertTrue(duplicate.history.isEmpty())
    }

    @Test fun invalidRequestsFailBeforePreflightOrJournal() = runBlocking {
        val base = request()
        val invalid = listOf(base.copy(allowXrayRestart = false), base.copy(id = "bad/id"),
            base.copy(exitPanelId = base.entryPanelId), base.copy(entryPort = 0), base.copy(exitPort = 65536),
            base.copy(exitAddress = "host/path"), base.copy(fingerprint = "unsafe"), base.copy(entryName = "bad/name"),
            base.copy(entryServerName = "https://invalid.test"))
        for (request in invalid) {
            val gateway = Gateway(); val journal = Journal()
            assertThrows(IllegalArgumentException::class.java) { runBlocking { CascadeService(gateway, journal).create(request) } }
            assertTrue(gateway.calls.isEmpty())
            assertTrue(journal.history.isEmpty())
        }
    }

    @Test fun persistentJournalEnforcesTransitionsAndRejectsCorruption() = runBlocking {
        val storage = mutableMapOf<String, String>()
        var failCommit = false
        var pending: Pair<String, String>? = null
        val editor = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(android.content.SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { pending = (args!![0] as String) to (args[1] as String); proxy }
                "commit" -> { if (!failCommit) pending?.let { storage[it.first] = it.second }; pending = null; !failCommit }
                else -> error(method.name)
            }
        } as android.content.SharedPreferences.Editor
        val preferences = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> storage.toMap()
                "contains" -> storage.containsKey(args!![0])
                "getString" -> storage[args!![0]] ?: args[1]
                "edit" -> editor
                else -> error(method.name)
            }
        } as android.content.SharedPreferences
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val context = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafeField.get(null), TestContext::class.java) as TestContext
        context.preferences = preferences
        val journal = PersistentCascadeJournal(context)
        val initial = CascadeMetadata("journal", "entry", "exit", "entry-tag", "exit-tag", "out-tag", "entry-name", "exit-name", CascadeStep.ENTRY_INBOUND)
        assertTrue(journal.reserve(initial))
        assertFalse(journal.reserve(initial))
        assertEquals(initial, journal.get(initial.id))
        assertEquals(listOf(initial), journal.entries())
        assertFalse(journal.reserve(initial.copy(id = "other")))
        val saved = storage.getValue(initial.id)
        val legacy = JSONObject(saved).apply { remove("entryInboundName"); remove("exitInboundName") }
        storage[initial.id] = legacy.toString()
        assertEquals("entry-tag", journal.get(initial.id)!!.entryInboundName)
        assertEquals("exit-tag", journal.get(initial.id)!!.exitInboundName)
        storage[initial.id] = saved
        val separate = initial.copy(id = "separate", entryTag = "tag-other-1", exitTag = "tag-other-2",
            outboundTag = "tag-other-3", entryClientName = "client-other-1", exitClientName = "client-other-2",
            entryInboundName = "Входной узел 2", exitInboundName = "Выходной узел 2")
        assertTrue(journal.reserve(separate))
        assertEquals(separate, journal.get("separate"))
        val reused = initial.copy(id = "reused", entryTag = "reuse1", exitTag = "reuse2", outboundTag = "reuse3",
            entryClientName = "reuse-client1", exitClientName = "reuse-client2", entryInboundName = "hide existing",
            exitInboundName = "new exit", entryInboundCreated = false,
            entryInboundSnapshot = """{"id":7,"remark":"hide existing","privateKey":"secret"}""")
        assertTrue(journal.reserve(reused))
        assertEquals(reused, journal.get("reused"))
        assertFalse(reused.toString().contains("secret"))
        assertFalse(journal.reserve(reused.copy(id = "reuse-again", entryInboundName = "different", exitInboundName = "different exit",
            entryClientName = "different1", exitClientName = "different2")))
        assertFalse(storage.getValue(initial.id).contains("privateKey"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { journal.save(initial.copy(step = CascadeStep.ROUTING, inFlight = true)) } }
        val started = initial.copy(inFlight = true)
        journal.save(started)
        val completed = started.copy(inFlight = false, entryInboundId = 12)
        journal.save(completed)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { journal.save(completed.copy(step = CascadeStep.ENTRY_CLIENT, inFlight = true, entryInboundId = 13)) } }
        journal.save(completed.copy(step = CascadeStep.ENTRY_CLIENT, inFlight = true))
        failCommit = true
        assertThrows(IllegalStateException::class.java) { runBlocking { journal.save(completed.copy(step = CascadeStep.ENTRY_CLIENT, inFlight = false)) } }
        storage[initial.id] = "not json"
        assertThrows(Exception::class.java) { runBlocking { journal.get(initial.id) } }
        assertThrows(Exception::class.java) { runBlocking { journal.reserve(initial) } }
        Unit
    }

    private class TestContext : android.content.ContextWrapper(null) {
        lateinit var preferences: android.content.SharedPreferences
        override fun getApplicationContext(): android.content.Context = this
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = preferences
    }

    @Test fun cancellationPropagatesAndRetainsInFlightState() = runBlocking {
        val gateway = Gateway().apply { cancelAt = "routing:entry" }
        val journal = Journal()
        assertThrows(CancellationException::class.java) { runBlocking { CascadeService(gateway, journal).create(request()) } }
        assertEquals(CascadeStep.ROUTING, journal.history.last().step)
        assertTrue(journal.history.last().inFlight)
        assertEquals("routing:entry", gateway.calls.last())
        assertFalse(journal.removed)
    }
}
