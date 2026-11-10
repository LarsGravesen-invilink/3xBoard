package app.invilink.xboard

import app.invilink.xboard.panels.InboundDraft
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundSniffingTest {
    private fun sniff(draft: InboundDraft): JSONObject = JSONObject(draft.payload().getString("sniffing"))

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    private fun existing(sniffing: String, security: String = "none"): InboundDraft {
        val stream = when (security) {
            "tls" -> """{"network":"ws","security":"tls","wsSettings":{"path":"/original","headers":{"Host":"cdn.example.test"}},"tlsSettings":{"serverName":"original.example.test","certificates":[{"certificateFile":"/server/cert.pem","keyFile":"/server/key.pem"}],"settings":{"fingerprint":"chrome"}},"futureStream":{"keep":true}}"""
            "reality" -> """{"network":"tcp","security":"reality","realitySettings":{"target":"original.example.test:443","serverNames":["original.example.test"],"privateKey":"${"A".repeat(43)}","shortIds":["aabb"],"settings":{"publicKey":"${"B".repeat(43)}","fingerprint":"firefox"},"futureReality":17},"futureStream":{"keep":true}}"""
            else -> """{"network":"tcp","security":"none","futureStream":{"keep":true}}"""
        }
        val raw = JSONObject()
            .put("id", 7).put("remark", "original").put("port", 443).put("protocol", "vless")
            .put("enable", true).put("total", 0).put("expiryTime", 0)
            .put("settings", """{"decryption":"none","clients":[{"id":"c80d1334-bb45-4d6e-9a12-1c94b80fc326","email":"keep@example.test"}],"futureProtocol":9}""")
            .put("streamSettings", stream).put("sniffing", sniffing).put("allocate", "{}")
            .put("tag", "original-tag").put("futureRoot", JSONObject("{\"keep\":true}"))
        return InboundDraft.fromRaw(raw.toString())
    }

    @Test
    fun defaultStaysDisabledAndReadingControlsDoesNotMutateDraft() {
        val draft = InboundDraft.create()
        val before = draft.payload().toString()
        assertEquals("false", draft.fieldValue("sniffing:enabled"))
        assertEquals("false", draft.fieldValue("sniffing:routeOnly"))
        assertEquals("false", draft.fieldValue("sniffing:metadataOnly"))
        assertEquals("", draft.fieldValue("sniffing:domainsExcluded"))
        assertEquals(listOf("http", "tls", "quic", "fakedns"), InboundDraft.sniffingDestOverrides)
        InboundDraft.sniffingDestOverrides.forEach { draft.fieldValue("sniffing:destOverride:$it") }
        assertEquals(before, draft.payload().toString())
        assertFalse(sniff(draft).getBoolean("enabled"))
    }

    @Test
    fun createsEnabledSniffingWithTypedArrayAndBooleanFlags() {
        var draft = InboundDraft.create().update("sniffing", "{}")
            .update("sniffing:enabled", "true")
            .update("sniffing:routeOnly", "true")
            .update("sniffing:metadataOnly", "true")
        InboundDraft.sniffingDestOverrides.forEach { draft = draft.update("sniffing:destOverride:$it", "true") }
        val config = sniff(draft.prepareForSave())
        assertEquals(InboundDraft.sniffingDestOverrides, strings(config.getJSONArray("destOverride")))
        listOf("enabled", "routeOnly", "metadataOnly").forEach {
            assertTrue(config.get(it) is Boolean)
            assertTrue(config.getBoolean(it))
        }
        assertFalse(config.has("domainsExcluded"))
    }

    @Test
    fun eachTypedFieldKeepsDisabledStateAndUnrelatedFields() {
        val draft = existing("""{"enabled":false,"destOverride":["quic","future","tls","http"],"metadataOnly":false,"routeOnly":false,"future":{"nested":[3,true]}}""")
        val original = sniff(draft)
        listOf("routeOnly" to "true", "metadataOnly" to "true", "destOverride:fakedns" to "true", "domainsExcluded" to "example.test").forEach { (key, value) ->
            val config = sniff(draft.update("sniffing:$key", value))
            assertFalse(config.getBoolean("enabled"))
            assertEquals(original.getJSONObject("future").toString(), config.getJSONObject("future").toString())
            if (!key.startsWith("destOverride:")) assertEquals(original.getJSONArray("destOverride").toString(), config.getJSONArray("destOverride").toString())
            if (key != "routeOnly") assertFalse(config.getBoolean("routeOnly"))
            if (key != "metadataOnly") assertFalse(config.getBoolean("metadataOnly"))
        }
        assertEquals(original.toString(), sniff(draft).toString())
    }

    @Test
    fun overrideCheckboxEditsPreserveUnknownEntriesAndExistingOrder() {
        val draft = existing("""{"enabled":false,"destOverride":["quic","fakedns+others","future","tls","http","future"],"futureFlag":17}""")
        val unchanged = draft.update("sniffing:destOverride:tls", "true")
        assertEquals(sniff(draft).toString(), sniff(unchanged).toString())
        val removed = unchanged.update("sniffing:destOverride:tls", "false")
        assertEquals(listOf("quic", "fakedns+others", "future", "http", "future"), strings(sniff(removed).getJSONArray("destOverride")))
        val appended = removed.update("sniffing:destOverride:fakedns", "true")
        assertEquals(listOf("quic", "fakedns+others", "future", "http", "future", "fakedns"), strings(sniff(appended).getJSONArray("destOverride")))
        assertEquals(17, sniff(appended).getInt("futureFlag"))
        assertFalse(sniff(appended).getBoolean("enabled"))
        assertThrows(IllegalArgumentException::class.java) { draft.update("sniffing:destOverride:future", "true") }
    }

    @Test
    fun missingSniffingFieldsAreNotFilledInWhenEditingOneFlag() {
        val draft = existing("{}")
        assertEquals("false", draft.fieldValue("sniffing:enabled"))
        assertEquals("false", draft.fieldValue("sniffing:destOverride:http"))
        assertEquals("{}", sniff(draft).toString())
        val edited = sniff(draft.update("sniffing:routeOnly", "true"))
        assertEquals(1, edited.length())
        assertTrue(edited.getBoolean("routeOnly"))
        val disabled = sniff(draft.update("sniffing:enabled", "false"))
        assertEquals(1, disabled.length())
        assertFalse(disabled.getBoolean("enabled"))
    }

    @Test
    fun domainsExcludedUsesTrimmedStringArrayAndBlankMeansEmptyArray() {
        val draft = existing("""{"enabled":false,"future":17}""")
            .update("sniffing:domainsExcluded", " example.test, .internal.test\nregexp:.*\\.example\\.org$\n\n")
        assertEquals(listOf("example.test", ".internal.test", "regexp:.*\\.example\\.org$"), strings(sniff(draft).getJSONArray("domainsExcluded")))
        assertEquals("example.test\n.internal.test\nregexp:.*\\.example\\.org$", draft.fieldValue("sniffing:domainsExcluded"))
        val cleared = sniff(draft.update("sniffing:domainsExcluded", " \n , "))
        assertEquals(0, cleared.getJSONArray("domainsExcluded").length())
        assertEquals(17, cleared.getInt("future"))
        assertFalse(cleared.getBoolean("enabled"))
    }

    @Test
    fun invalidDomainsRemainVisibleAndBlockSaveUntilCorrected() {
        val draft = InboundDraft.create().update("sniffing:domainsExcluded", "keep.example.test")
        listOf("[\"example.test\"]", "{\"domain\":1}", "\"example.test\"", "bad domain", "bad\u0000domain").forEach { text ->
            val invalid = draft.update("sniffing:domainsExcluded", text)
            assertEquals(text, invalid.fieldValue("sniffing:domainsExcluded"))
            assertEquals(draft.sniffing, invalid.sniffing)
            assertThrows(IllegalArgumentException::class.java) { invalid.payload() }
            val fixed = invalid.update("sniffing:domainsExcluded", "fixed.example.test")
            assertEquals(listOf("fixed.example.test"), strings(sniff(fixed).getJSONArray("domainsExcluded")))
            val reset = invalid.update("sniffing", "{\"enabled\":false,\"domainsExcluded\":[]}")
            assertEquals(0, sniff(reset).getJSONArray("domainsExcluded").length())
        }
    }

    @Test
    fun advancedJsonRejectsWrongTypesWithoutRejectingFutureFieldsOrOverrides() {
        val draft = InboundDraft.create()
        listOf(
            "{\"enabled\":\"false\"}", "{\"routeOnly\":1}", "{\"metadataOnly\":null}",
            "{\"destOverride\":\"http,tls\"}", "{\"destOverride\":[\"http\",false]}",
            "{\"domainsExcluded\":\"example.test\"}", "{\"domainsExcluded\":[1]}",
            "{\"domainsExcluded\":null}", "{\"domainsExcluded\":{}}",
        ).forEach { json ->
            assertThrows(IllegalArgumentException::class.java) { draft.validateAdvancedJson("sniffing", json) }
            assertThrows(IllegalArgumentException::class.java) { draft.update("sniffing", json).payload() }
        }
        val json = """{"enabled":false,"destOverride":["fakedns+others","future"],"domainsExcluded":["regexp:.*[a-z]{2}"],"future":{"values":[1,null,true]}}"""
        draft.validateAdvancedJson("sniffing", json)
        val edited = draft.update("sniffing", json).update("sniffing:metadataOnly", "true")
        val saved = sniff(edited)
        assertEquals("fakedns+others", saved.getJSONArray("destOverride").getString(0))
        assertEquals(JSONObject(json).getJSONObject("future").toString(), saved.getJSONObject("future").toString())
        assertEquals(JSONObject(json).getJSONArray("domainsExcluded").toString(), saved.getJSONArray("domainsExcluded").toString())
        assertFalse(saved.getBoolean("enabled"))
    }

    @Test
    fun sniffingUpdatesNeverModifyTransportSecurityKeysClientsOrSavePolicy() {
        listOf("none", "tls", "reality").forEach { security ->
            val draft = existing("""{"enabled":false,"destOverride":["tls","http"],"future":17}""", security)
            val before = draft.prepareForSave().payload()
            if (security == "reality") {
                val stream = JSONObject(before.getString("streamSettings"))
                assertEquals("/", stream.getJSONObject("realitySettings").getJSONObject("settings").getString("spiderX"))
            }
            val edited = draft.update("sniffing:enabled", "true")
                .update("sniffing:destOverride:quic", "true")
                .update("sniffing:routeOnly", "true")
                .update("sniffing:metadataOnly", "true")
                .update("sniffing:domainsExcluded", "example.test")
            assertEquals(draft.streamSettings, edited.streamSettings)
            assertEquals(draft.protocolSettings, edited.protocolSettings)
            assertEquals(draft.sniMode, edited.sniMode)
            assertEquals(draft.sniCustom, edited.sniCustom)
            assertEquals(draft.sniCandidates, edited.sniCandidates)
            assertEquals(draft.network, edited.network)
            assertEquals(draft.security, edited.security)
            val after = edited.prepareForSave().payload()
            val keys = before.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key != "sniffing") assertEquals(key, before.get(key).toString(), after.get(key).toString())
            }
            assertEquals(after.toString(), InboundDraft.fromRaw(after.toString()).prepareForSave().payload().toString())
        }
        val random = InboundDraft.create().update("security", "reality")
        val updated = random.update("sniffing:enabled", "true")
        assertEquals("random", updated.sniMode)
        assertEquals(random.streamSettings, updated.streamSettings)
    }

    @Test
    fun advancedEditorsDoNotClearValidationOfUnrelatedFields() {
        val invalidDomains = InboundDraft.create().update("sniffing:domainsExcluded", "bad domain")
        val withStream = invalidDomains.update("streamSettings", invalidDomains.streamSettings)
        assertEquals("bad domain", withStream.fieldValue("sniffing:domainsExcluded"))
        assertThrows(IllegalArgumentException::class.java) { withStream.payload() }
        val invalidStream = InboundDraft.create().update("reality:maxTimediff", "-")
        val withSniffing = invalidStream.update("sniffing", "{\"enabled\":false}")
        assertEquals("-", withSniffing.fieldValue("reality:maxTimediff"))
        assertThrows(IllegalArgumentException::class.java) { withSniffing.payload() }
    }

    @Test
    fun malformedExistingOverrideArrayCannotBeSilentlyReplacedByCheckbox() {
        val draft = existing("{\"enabled\":false,\"destOverride\":[\"http\",9]}")
        val before = draft.sniffing
        assertThrows(IllegalArgumentException::class.java) { draft.update("sniffing:destOverride:tls", "true") }
        assertEquals(before, draft.sniffing)
        assertThrows(IllegalArgumentException::class.java) { draft.update("sniffing:enabled", "yes") }
    }
}
