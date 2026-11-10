package app.invilink.xboard

import app.invilink.xboard.panels.PanelApi
import app.invilink.xboard.panels.PanelAvailability
import app.invilink.xboard.panels.PanelConfig
import app.invilink.xboard.panels.PanelMetrics
import app.invilink.xboard.panels.PanelValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI

class PanelApiTest {
    @Test fun visibilityFiltersNamesAndSharedClients() {
        fun client(name: String) = app.invilink.xboard.panels.ClientDetail(name, true, 0, 0, null, null, "", null, "", "{}")
        fun inbound(id: Long, name: String, vararg clients: String) = app.invilink.xboard.panels.InboundDetail(
            id, name, "vless", 443, true, "tcp", "reality", 0, 0, null, null, clients.map(::client), "{}")
        val source = listOf(inbound(1, "HiDeprivate", "Shared", "inside"),
            inbound(2, "Visible", "shared", "HIDEclient", "keep"), inbound(3, "hide another", "other"))
        val result = app.invilink.xboard.panels.visiblePanelInbounds(source)
        assertEquals(listOf(2L), result.map { it.id })
        assertEquals(listOf("keep"), result.single().clients.map { it.email })
        assertEquals(3, source.size)
    }

    @Test fun visibilitySwitchPreservesOfflineBaselineAndPendingAliases() {
        fun client(name: String) = app.invilink.xboard.panels.ClientDetail(name, true, 0, 0, null, null, "", null, "", "{}")
        fun inbound(id: Long, name: String, vararg clients: String) = app.invilink.xboard.panels.InboundDetail(
            id, name, "vless", 443, true, "tcp", "reality", 0, 0, null, null, clients.map(::client), "{}")
        val baseline = listOf(inbound(1, "hide old", "shared"))
        val projected = listOf(inbound(1, "renamed", "shared"), inbound(2, "public", "Shared", "alias", "keep", "HIDEclient"))
        val hidden = app.invilink.xboard.panels.visiblePanelInbounds(projected, baseline, setOf("ALIAS"))
        assertEquals(listOf(2L), hidden.map { it.id })
        assertEquals(listOf("keep"), hidden.single().clients.map { it.email })
        val unlocked = app.invilink.xboard.panels.visiblePanelInbounds(projected, baseline, setOf("ALIAS"), true)
        assertEquals(projected, unlocked)
        assertEquals(hidden, app.invilink.xboard.panels.visiblePanelInbounds(projected, baseline, setOf("ALIAS")))
        assertEquals(4, projected.last().clients.size)
    }

    @Test fun panelTrafficUsesOnlyCurrentVisibleProjection() {
        fun inbound(id: Long, name: String, up: Long?) = app.invilink.xboard.panels.InboundDetail(
            id, name, "vless", 443, true, "tcp", "reality", up, 0, null, null, emptyList(), "{}")
        val source = listOf(inbound(1, "hide secret", 100), inbound(2, "public", 7))
        fun sum(rows: List<app.invilink.xboard.panels.InboundDetail>?) =
            app.invilink.xboard.panels.visibleTrafficSum(rows) { it.up }
        assertEquals(107L, sum(app.invilink.xboard.panels.visiblePanelInbounds(source, unlockAllEnabled = true)))
        assertEquals(7L, sum(app.invilink.xboard.panels.visiblePanelInbounds(source)))
        assertNull(sum(null))
        assertNull(sum(listOf(inbound(1, "public", null))))
        assertNull(sum(listOf(inbound(1, "public", Long.MAX_VALUE), inbound(2, "public", 1))))
    }

    @Test fun cascadeVisibilityRespectsBothInboundNamesAndSwitch() {
        val cascade = app.invilink.xboard.panels.CascadeMetadata("id", "a", "b", "entry", "exit", "out", "client", "client", app.invilink.xboard.panels.CascadeStep.COMPLETE)
        for (hidden in listOf(cascade.copy(entryInboundName = "HiDe entry"), cascade.copy(exitInboundName = "HIDE exit"))) {
            assertFalse(app.invilink.xboard.panels.visibleCascade(hidden))
            assertEquals(true, app.invilink.xboard.panels.visibleCascade(hidden, true))
        }
        assertEquals(true, app.invilink.xboard.panels.visibleCascade(cascade))
    }

    private val api = PanelApi()
    private val fixture = PanelConfig("fixture-1", "Тестовая панель", "https://example.test", "fixture-not-a-real-token")

    @Test fun subscriptionUrlUsesPanelSettingsInsteadOfProtocolLinks() {
        val settings = org.json.JSONObject("""{"subEnable":true,"subPort":2096,"subPath":"/secret/","subDomain":"sub.example.test","subCertFile":"cert","subKeyFile":"key"}""")
        assertEquals("https://sub.example.test:2096/secret/id123", api.subscriptionUrl(fixture, settings, "id123"))
        settings.put("subURI", "https://proxy.example.test/sub/")
        assertEquals("https://proxy.example.test/sub/id123", api.subscriptionUrl(fixture, settings, "id123"))
        settings.put("subEnable", false)
        assertNull(api.subscriptionUrl(fixture, settings, "id123"))
    }

    @Test
    fun memoryUsesActualByteCountersAndRejectsMissingOrInvalidMetrics() {
        fun memory(body: String) = app.invilink.xboard.panels.PanelResponseParser.memory(org.json.JSONObject(body))
        assertEquals(512L to 2048L, memory("""{"mem":{"current":512,"total":2048}}"""))
        assertNull(memory("{}"))
        assertNull(memory("""{"mem":{"current":0,"total":0}}"""))
        assertNull(memory("""{"mem":{"current":4096,"total":2048}}"""))
        assertNull(memory("""{"mem":{"current":-1,"total":2048}}"""))
        assertNull(memory("""{"mem":{"current":512,"total":2048},"unavailableMetrics":["mem"]}"""))
    }

    @Test
    fun preservesPortAndSecretPathWithoutChangingPathCase() {
        val base = api.normalizeBaseUrl("  HTTPS://EXAMPLE.TEST:8443/Secret%20Path  ")
        assertEquals("https://example.test:8443/Secret%20Path/", base)
        assertEquals(base, api.normalizeBaseUrl(base))
        assertEquals(
            "https://example.test:8443/Secret%20Path/panel/api/inbounds/list",
            URI(base).resolve("panel/api/inbounds/list").toString(),
        )
    }

    @Test
    fun supportsHttpAndEndpointHostPort() {
        assertEquals("https://example.test/", api.normalizeBaseUrl("https://example.test"))
        assertEquals("https://[2001:db8::1]:8443/secret/", api.normalizeBaseUrl("https://[2001:db8::1]:8443/secret"))
        assertEquals("http://example.test:8080/secret/", api.normalizeBaseUrl("HTTP://EXAMPLE.TEST:8080/secret"))
        assertEquals("https://example.test/", api.endpointUrl("example.test", ""))
        assertEquals("https://example.test:8443/", api.endpointUrl("example.test", "8443"))
        assertEquals("https://[2001:db8::1]:8443/", api.endpointUrl("2001:db8::1", "8443"))
        assertEquals("https://[2001:db8::1]/", api.endpointUrl("[2001:db8::1]", ""))
        assertEquals("http://example.test:2053/Secret/", api.endpointUrl("http://example.test/Secret", "2053"))
        assertEquals("http://example.test:2053/Secret/", api.endpointUrl("http://example.test:2053/Secret", "2053"))
        assertThrows(IllegalArgumentException::class.java) { api.endpointUrl("http://example.test:80/", "2053") }
        assertThrows(IllegalArgumentException::class.java) { api.endpointUrl("ftp://example.test/", "21") }
    }

    @Test
    fun rejectsUnsafeAuthoritiesSchemesAndSuffixes() {
        listOf(
            "ftp://example.test", "example.test", "https:///secret", "https:example.test",
            "https://user:fixture@example.test/", "https://example.test?", "https://example.test#",
            "https://example.test:0/", "https://example.test:65536/", "https://example.test:/",
            "https://example.test/\r\n", "https://example.test/secret?token=fixture",
        ).forEach { raw ->
            val error = assertThrows(IllegalArgumentException::class.java) { api.normalizeBaseUrl(raw) }
            assertNull(error.cause)
            assertFalse(error.message.orEmpty().contains(raw))
        }
    }

    @Test
    fun rejectsPathTraversalAndEncodedSeparators() {
        listOf("/a/../b", "/%2e%2e/", "/a%2Fb", "/%5csecret", "/%0asecret", "/%252e/", "/a//b")
            .forEach { path ->
                assertThrows(IllegalArgumentException::class.java) { api.normalizeBaseUrl("https://example.test$path") }
            }
    }

    @Test
    fun configStringRedactsTokenAndSecretUrlIncludingCopies() {
        listOf(fixture, fixture.copy(name = "Другая панель")).forEach { config ->
            assertEquals("PanelConfig([REDACTED])", config.toString())
            assertFalse(config.toString().contains(config.token))
            assertFalse(config.toString().contains(config.baseUrl))
        }
    }

    @Test
    fun validatesBoundsWithoutTruncatingNames() {
        val name = "П".repeat(128)
        assertEquals(name, PanelValidation.validate(fixture.copy(name = name)).name)
        assertEquals("https://example.test/", PanelValidation.validate(fixture).baseUrl)
        assertThrows(IllegalArgumentException::class.java) { PanelValidation.validate(fixture.copy(name = name + "П")) }
        assertThrows(IllegalArgumentException::class.java) { PanelValidation.validate(fixture.copy(id = " ")) }
        assertThrows(IllegalArgumentException::class.java) { PanelValidation.validate(fixture.copy(token = "")) }
        assertThrows(IllegalArgumentException::class.java) { PanelValidation.validate(fixture.copy(token = "x".repeat(8193))) }
        assertThrows(IllegalArgumentException::class.java) { PanelValidation.validate(fixture.copy(token = "fixture\nheader")) }
    }

    @Test
    fun unavailableMetricsStayNull() {
        val metrics = PanelMetrics()
        assertEquals(PanelAvailability.UNKNOWN, metrics.availability)
        assertEquals(0L, metrics.updatedAtMillis)
        assertNull(metrics.inboundCount)
        assertNull(metrics.clientCount)
        assertNull(metrics.trafficBytes)
        assertNull(metrics.cpuPercent)
        assertNull(metrics.error)
    }
}
