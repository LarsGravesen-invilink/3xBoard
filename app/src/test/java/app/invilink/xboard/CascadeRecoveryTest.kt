package app.invilink.xboard

import android.content.SharedPreferences
import app.invilink.xboard.panels.CascadeRecoveryJournal
import app.invilink.xboard.panels.PanelConfig
import app.invilink.xboard.panels.RecoveryComparison
import app.invilink.xboard.panels.recoveryComparison
import app.invilink.xboard.panels.recoveryEndpoint
import app.invilink.xboard.panels.recoveryPreflight
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class CascadeRecoveryTest {
    private class Preferences {
        var data: String? = null
        var failCommit = false
        val instance: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> data ?: args!![1]
                "edit" -> {
                    var next = data
                    lateinit var editor: SharedPreferences.Editor
                    editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java)) { _, action, values ->
                        when (action.name) {
                            "putString" -> { next = values!![1] as String?; editor }
                            "remove" -> { next = null; editor }
                            "commit" -> if (failCommit) false else { data = next; true }
                            else -> error("Unexpected editor method ${action.name}")
                        }
                    } as SharedPreferences.Editor
                    editor
                }
                else -> error("Unexpected preferences method ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun journalSurvivesNewInstanceAndRequiresExplicitClear() {
        val prefs = Preferences()
        CascadeRecoveryJournal(prefs.instance).save(JSONObject().put("stage", "inbound_dispatched").put("before", JSONObject().put("port", 443)))
        val reopened = CascadeRecoveryJournal(prefs.instance)
        assertEquals("inbound_dispatched", reopened.load()!!.getString("stage"))
        assertEquals(443, reopened.load()!!.getJSONObject("before").getInt("port"))
        val record = reopened.load()!!.put("stage", "recovery_observed")
        reopened.save(record)
        assertEquals("recovery_observed", CascadeRecoveryJournal(prefs.instance).load()!!.getString("stage"))
        reopened.clear()
        assertNull(CascadeRecoveryJournal(prefs.instance).load())
    }

    @Test fun failedCommitIsReportedAndExistingRecordIsRetained() {
        val prefs = Preferences()
        val journal = CascadeRecoveryJournal(prefs.instance)
        journal.save(JSONObject().put("stage", "prepared"))
        prefs.failCommit = true
        try { journal.save(JSONObject().put("stage", "inbound_dispatched")); fail("commit must fail") }
        catch (_: IllegalStateException) { }
        assertEquals("prepared", journal.load()!!.getString("stage"))
        try { journal.clear(); fail("clear must fail") } catch (_: IllegalStateException) { }
        assertNotNull(journal.load())
    }

    @Test fun comparisonNeverAcceptsUnknownState() {
        assertEquals(RecoveryComparison.CONFLICT, recoveryComparison(false, false))
        assertEquals(RecoveryComparison.BEFORE, recoveryComparison(true, false))
        assertEquals(RecoveryComparison.AFTER, recoveryComparison(false, true))
        assertEquals(RecoveryComparison.BEFORE, recoveryComparison(true, true))
    }

    @Test fun preflightRejectsConflictInAnyComponentBeforeWrite() {
        for (index in 0..2) {
            val states = MutableList(3) { RecoveryComparison.AFTER }
            states[index] = RecoveryComparison.CONFLICT
            var wrote = false
            try { recoveryPreflight(states); wrote = true; fail("conflict must stop recovery") }
            catch (_: IllegalStateException) { }
            assertFalse(wrote)
        }
        recoveryPreflight(listOf(RecoveryComparison.BEFORE, RecoveryComparison.AFTER))
    }

    @Test fun endpointBindingIncludesIdentityAddressAndCredential() {
        val config = PanelConfig("id", "name", "https://example.test", "secret")
        assertEquals(recoveryEndpoint(config), recoveryEndpoint(config.copy(name = "renamed")))
        assertNotEquals(recoveryEndpoint(config), recoveryEndpoint(config.copy(id = "other")))
        assertNotEquals(recoveryEndpoint(config), recoveryEndpoint(config.copy(baseUrl = "https://other.test")))
        assertNotEquals(recoveryEndpoint(config), recoveryEndpoint(config.copy(token = "other")))
    }
}
