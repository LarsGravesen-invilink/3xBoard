package app.invilink.xboard

import app.invilink.xboard.security.AccessPolicy
import app.invilink.xboard.security.AccessStateCodec
import app.invilink.xboard.security.StoredAccessState
import app.invilink.xboard.security.StoredBiometric
import app.invilink.xboard.security.StoredLockout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccessSecurityTest {
    @Test
    fun pinRequiresExactlyFourAsciiDigits() {
        listOf("0000", "0123", "9999").forEach { assertTrue(AccessPolicy.isValidPin(it)) }
        listOf("", "123", "12345", " 123", "123\n", "12a4", "１２３４", "١٢٣٤", "1\u000023")
            .forEach { assertFalse(AccessPolicy.isValidPin(it)) }
    }

    @Test
    fun derivationIsDeterministicAndDependsOnPinAndSalt() {
        val salt = ByteArray(AccessPolicy.SALT_BYTES) { it.toByte() }
        val first = AccessPolicy.derivePin("0123", salt)
        assertEquals(AccessPolicy.HASH_BYTES, first.size)
        assertArrayEquals(first, AccessPolicy.derivePin("0123", salt))
        assertFalse(first.contentEquals(AccessPolicy.derivePin("0124", salt)))
        assertFalse(first.contentEquals(AccessPolicy.derivePin("0123", salt.copyOf().also { it[0] = 99 })))
        assertArrayEquals(ByteArray(AccessPolicy.SALT_BYTES) { it.toByte() }, salt)
    }

    @Test
    fun derivationRejectsInvalidInput() {
        rejects { AccessPolicy.derivePin("123", ByteArray(AccessPolicy.SALT_BYTES)) }
        rejects { AccessPolicy.derivePin("１２３４", ByteArray(AccessPolicy.SALT_BYTES)) }
        rejects { AccessPolicy.derivePin("0123", ByteArray(AccessPolicy.SALT_BYTES - 1)) }
    }

    @Test
    fun fifthAttemptReservesThirtySecondLockout() {
        var state = initialState()
        for (attempt in 1..4) {
            state = AccessPolicy.reserveAttempt(state, 7, 1_000)
            assertEquals(attempt, state.failedAttempts)
            assertNull(state.lockout)
            state = roundTrip(state)
        }
        state = AccessPolicy.reserveAttempt(state, 7, 1_000)
        assertEquals(0, state.failedAttempts)
        assertEquals(1, state.lockoutLevel)
        assertEquals(StoredLockout(7, 1_000, 30), state.lockout)
        roundTrip(state)
    }

    @Test
    fun escalationSurvivesSerializationAndCapsAtOneDay() {
        var state = initialState()
        repeat(20) { cycle ->
            state = state.copy(lockout = null)
            repeat(AccessPolicy.MAX_ATTEMPTS) {
                state = roundTrip(AccessPolicy.reserveAttempt(state, 7, 1_000))
            }
            assertEquals(AccessPolicy.lockoutSeconds(cycle.coerceAtMost(AccessPolicy.MAX_LOCKOUT_LEVEL)),
                requireNotNull(state.lockout).durationSeconds)
            assertEquals((cycle + 1).coerceAtMost(AccessPolicy.MAX_LOCKOUT_LEVEL), state.lockoutLevel)
        }
        assertEquals(86_400L, requireNotNull(state.lockout).durationSeconds)
    }

    @Test
    fun attemptsCannotBeReservedWhileLocked() {
        val state = initialState().copy(lockoutLevel = 1, lockout = StoredLockout(7, 1_000, 30))
        rejects { AccessPolicy.reserveAttempt(state, 7, 1_000) }
    }

    @Test
    fun invalidAttemptCountersAreRejected() {
        rejects { AccessPolicy.reserveAttempt(initialState().copy(failedAttempts = -1), 7, 1_000) }
        rejects { AccessPolicy.reserveAttempt(initialState().copy(failedAttempts = 5), 7, 1_000) }
        rejects { AccessPolicy.reserveAttempt(initialState(), -1, 1_000) }
        rejects { AccessPolicy.reserveAttempt(initialState(), 7, -1) }
    }

    @Test
    fun lockoutRoundsRemainingTimeUp() {
        val lockout = StoredLockout(7, 1_000, 30)
        assertEquals(30L, AccessPolicy.remainingSeconds(lockout, 7, 1_000))
        assertEquals(30L, AccessPolicy.remainingSeconds(lockout, 7, 1_001))
        assertEquals(29L, AccessPolicy.remainingSeconds(lockout, 7, 2_000))
        assertEquals(1L, AccessPolicy.remainingSeconds(lockout, 7, 30_999))
        assertEquals(0L, AccessPolicy.remainingSeconds(lockout, 7, 31_000))
        assertEquals(0L, AccessPolicy.remainingSeconds(lockout, 7, Long.MAX_VALUE))
    }

    @Test
    fun processRestartDoesNotResetSameBootDeadline() {
        val state = roundTrip(initialState().copy(
            lockoutLevel = 1,
            lockout = StoredLockout(7, 1_000, 30),
        ))
        assertEquals(19L, AccessPolicy.remainingSeconds(requireNotNull(state.lockout), 7, 12_000))
    }

    @Test
    fun rebootAndMonotonicRollbackConservativelyRequireFullDelay() {
        val lockout = StoredLockout(7, 1_000, 30)
        assertEquals(30L, AccessPolicy.remainingSeconds(lockout, 8, 100))
        assertEquals(30L, AccessPolicy.remainingSeconds(lockout, 8, 100_000))
        assertEquals(30L, AccessPolicy.remainingSeconds(lockout, 7, 999))
    }

    @Test
    fun rebasedRebootLockoutCountsDownNormally() {
        val lockout = StoredLockout(8, 10_000, 30)
        assertEquals(20L, AccessPolicy.remainingSeconds(lockout, 8, 20_000))
        assertEquals(0L, AccessPolicy.remainingSeconds(lockout, 8, 40_000))
    }

    @Test
    fun escalationLevelsAreBounded() {
        assertEquals(30L, AccessPolicy.lockoutSeconds(0))
        assertEquals(60L, AccessPolicy.lockoutSeconds(1))
        assertEquals(120L, AccessPolicy.lockoutSeconds(2))
        assertEquals(86_400L, AccessPolicy.lockoutSeconds(AccessPolicy.MAX_LOCKOUT_LEVEL))
        rejects { AccessPolicy.lockoutSeconds(-1) }
        rejects { AccessPolicy.lockoutSeconds(AccessPolicy.MAX_LOCKOUT_LEVEL + 1) }
    }

    @Test
    fun unconfiguredBiometricStateRoundTrips() {
        val state = roundTrip(initialState())
        assertNull(state.biometric)
        assertEquals(0, state.failedAttempts)
        assertEquals(0, state.lockoutLevel)
    }

    @Test
    fun pendingEnrollmentIsNotEnabled() {
        val state = roundTrip(initialState().copy(
            biometric = StoredBiometric(ByteArray(AccessPolicy.KEY_ID_BYTES) { it.toByte() }),
        ))
        assertNotNull(state.biometric)
        assertFalse(requireNotNull(state.biometric).isEnrolled)
    }

    @Test
    fun encryptedEnrollmentRoundTrips() {
        val state = roundTrip(initialState().copy(biometric = enrolledBiometric()))
        assertTrue(requireNotNull(state.biometric).isEnrolled)
        assertArrayEquals(enrolledBiometric().iv, requireNotNull(state.biometric).iv)
        assertArrayEquals(enrolledBiometric().ciphertext, requireNotNull(state.biometric).ciphertext)
        assertArrayEquals(enrolledBiometric().proof, requireNotNull(state.biometric).proof)
    }

    @Test
    fun everyTruncatedEnrollmentRecordIsRejected() {
        val encoded = AccessStateCodec.encode(initialState().copy(biometric = enrolledBiometric()))
        for (length in encoded.indices) {
            rejects { AccessStateCodec.decode(encoded.copyOf(length)) }
        }
    }

    @Test
    fun extraDataAndOversizedRecordsAreRejected() {
        val encoded = AccessStateCodec.encode(initialState())
        rejects { AccessStateCodec.decode(encoded + byteArrayOf(0)) }
        rejects { AccessStateCodec.decode(ByteArray(AccessStateCodec.MAX_PAYLOAD_BYTES + 1)) }
    }

    @Test
    fun unknownVersionAndInvalidDiscriminatorsAreRejected() {
        val encoded = AccessStateCodec.encode(initialState())
        rejects { AccessStateCodec.decode(encoded.copyOf().also { it[3] = 2 }) }
        rejects { AccessStateCodec.decode(encoded.copyOf().also { it[76] = 2 }) }
        rejects { AccessStateCodec.decode(encoded.copyOf().also { it[77] = 3 }) }
    }

    @Test
    fun invalidPersistedCountersAreRejected() {
        val encoded = AccessStateCodec.encode(initialState())
        rejects { AccessStateCodec.decode(encoded.copyOf().also { it[71] = 5 }) }
        rejects { AccessStateCodec.decode(encoded.copyOf().also { it[68] = -1 }) }
        rejects { AccessStateCodec.decode(encoded.copyOf().also { it[75] = 14 }) }
        rejects { AccessStateCodec.encode(initialState().copy(lockoutLevel = -1)) }
    }

    @Test
    fun invalidPinMaterialLengthsAreRejected() {
        rejects { AccessStateCodec.encode(initialState().copy(salt = ByteArray(31))) }
        rejects { AccessStateCodec.encode(initialState().copy(verifier = ByteArray(31))) }
    }

    @Test
    fun inconsistentLockoutsAreRejected() {
        rejects { AccessStateCodec.encode(initialState().copy(lockout = StoredLockout(7, 1_000, 30))) }
        rejects { AccessStateCodec.encode(initialState().copy(lockoutLevel = 1, lockout = StoredLockout(7, 1_000, 60))) }
        rejects { AccessStateCodec.encode(initialState().copy(lockoutLevel = 1, lockout = StoredLockout(-1, 1_000, 30))) }
        rejects { AccessStateCodec.encode(initialState().copy(lockoutLevel = 1, lockout = StoredLockout(7, -1, 30))) }
        rejects { AccessStateCodec.encode(initialState().copy(
            failedAttempts = 1,
            lockoutLevel = 1,
            lockout = StoredLockout(7, 1_000, 30),
        )) }
    }

    @Test
    fun partialOrMalformedBiometricPayloadsAreRejected() {
        val biometric = enrolledBiometric()
        listOf(
            biometric.copy(keyId = ByteArray(15)),
            biometric.copy(iv = null),
            biometric.copy(iv = ByteArray(11)),
            biometric.copy(ciphertext = null),
            biometric.copy(ciphertext = ByteArray(47)),
            biometric.copy(proof = null),
            biometric.copy(proof = ByteArray(31)),
        ).forEach { invalid ->
            rejects { AccessStateCodec.encode(initialState().copy(biometric = invalid)) }
        }
    }

    @Test
    fun mainAndFakePinsSelectSeparateRolesAndFakePinNeverAuthorizesMainOnlyChecks() {
        val authenticate: (ByteArray, Boolean) -> ByteArray = { bytes, fake ->
            java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf(if (fake) 2 else 1) + bytes)
        }
        val primarySalt = ByteArray(32) { 1 }
        val fakeSalt = ByteArray(32) { 2 }
        fun verifier(pin: String, salt: ByteArray, fake: Boolean): ByteArray {
            val derived = AccessPolicy.derivePin(pin, salt)
            return try { authenticate(derived, fake) } finally { derived.fill(0) }
        }
        val state = initialState().copy(salt = primarySalt, verifier = verifier("1357", primarySalt, false),
            fakePin = app.invilink.xboard.security.StoredFakePin(fakeSalt, verifier("2468", fakeSalt, true), primaryPinRequired = true))
        assertEquals(app.invilink.xboard.security.PinAccess.PRIMARY, AccessPolicy.pinAccess("1357", state, true, authenticate))
        assertEquals(app.invilink.xboard.security.PinAccess.FAKE, AccessPolicy.pinAccess("2468", state, true, authenticate))
        assertEquals(app.invilink.xboard.security.PinAccess.DENIED, AccessPolicy.pinAccess("2468", state, false, authenticate))
        assertEquals(app.invilink.xboard.security.PinAccess.DENIED, AccessPolicy.pinAccess("9999", state, true, authenticate))
        val disabled = state.copy(fakePin = requireNotNull(state.fakePin).copy(enabled = false, primaryPinRequired = false))
        assertEquals(app.invilink.xboard.security.PinAccess.DENIED, AccessPolicy.pinAccess("2468", disabled, true, authenticate))
        assertEquals(app.invilink.xboard.security.PinAccess.PRIMARY, AccessPolicy.pinAccess("1357", disabled, false, authenticate))
    }

    @Test
    fun fakePinMaterialAndPrimaryOnlyRequirementRoundTripWithoutChangingMainPinOrBiometrics() {
        val original = initialState().copy(biometric = enrolledBiometric())
        val fake = app.invilink.xboard.security.StoredFakePin(
            ByteArray(AccessPolicy.SALT_BYTES) { 7 }, ByteArray(AccessPolicy.HASH_BYTES) { 8 },
            enabled = true, primaryPinRequired = true,
        )
        val restored = roundTrip(original.copy(fakePin = fake))
        assertArrayEquals(original.salt, restored.salt)
        assertArrayEquals(original.verifier, restored.verifier)
        assertArrayEquals(original.biometric?.proof, restored.biometric?.proof)
        assertTrue(requireNotNull(restored.fakePin).enabled)
        assertTrue(requireNotNull(restored.fakePin).primaryPinRequired)
        assertArrayEquals(fake.salt, restored.fakePin?.salt)
        assertArrayEquals(fake.verifier, restored.fakePin?.verifier)
        val disabled = roundTrip(restored.copy(fakePin = fake.copy(enabled = false, primaryPinRequired = false)))
        assertFalse(requireNotNull(disabled.fakePin).enabled)
        assertFalse(requireNotNull(disabled.fakePin).primaryPinRequired)
    }

    @Test
    fun existingVersionOneRecordsRemainByteForByteCompatibleAndFakePinIsOffByDefault() {
        val encoded = AccessStateCodec.encode(initialState())
        assertEquals(1, encoded[3].toInt())
        val restored = AccessStateCodec.decode(encoded)
        assertNull(restored.fakePin)
        assertArrayEquals(encoded, AccessStateCodec.encode(restored))
    }

    @Test
    fun malformedAndTruncatedFakePinMaterialIsRejected() {
        val fake = app.invilink.xboard.security.StoredFakePin(ByteArray(32), ByteArray(32))
        rejects { AccessStateCodec.encode(initialState().copy(fakePin = fake.copy(salt = ByteArray(31)))) }
        rejects { AccessStateCodec.encode(initialState().copy(fakePin = fake.copy(verifier = ByteArray(31)))) }
        rejects { AccessStateCodec.encode(initialState().copy(fakePin = fake.copy(enabled = false, primaryPinRequired = true))) }
        val encoded = AccessStateCodec.encode(initialState().copy(fakePin = fake))
        for (length in encoded.indices) rejects { AccessStateCodec.decode(encoded.copyOf(length)) }
        rejects { AccessStateCodec.decode(encoded + byteArrayOf(0)) }
    }

    @Test
    fun attemptReservationPreservesFakePinAndDoesNotResetThePrimaryOnlyRequirement() {
        val state = initialState().copy(fakePin = app.invilink.xboard.security.StoredFakePin(
            ByteArray(32), ByteArray(32), primaryPinRequired = true))
        val reserved = roundTrip(AccessPolicy.reserveAttempt(state, 7, 1_000))
        assertTrue(requireNotNull(reserved.fakePin).primaryPinRequired)
        assertEquals(1, reserved.failedAttempts)
    }

    @Test
    fun stealthDisplayMasksLateServerAndPanelUpdatesWithoutDeletingTheOriginalState() {
        val server = app.invilink.xboard.ssh.InstalledServer(
            panelId = "private-panel", sshHost = "hidden.example.test", sshPort = 22, sshUsername = "root",
            hostKeyAlgorithm = "ssh-rsa", hostKeyBase64 = "private-key", panelUsername = "private-user",
            panelPassword = "private-password", panelPort = 2053, panelUrl = "https://hidden.example.test/private/",
            token = "private-token", secretPath = "private", sslMode = app.invilink.xboard.ssh.SslMode.DOMAIN,
            certificateTarget = "hidden.example.test",
        )
        val original = app.invilink.xboard.ui.ScreenModel(
            stage = app.invilink.xboard.ui.Stage.INSTALLER, stealthMode = true, primaryPinRequired = true,
            panels = listOf(app.invilink.xboard.panels.PanelSummary("private-panel", "Private server")),
            serverState = app.invilink.xboard.ssh.ServerManagerState(servers = listOf(server), error = "Private server details"),
            activePanelId = "private-panel", activePanelName = "Private server", activePanelAddress = server.panelUrl,
            addToken = "private-token", pendingChanges = 3, syncMessage = "Private queue", fakePinEnabled = true,
            settingsMessage = "Private operation", biometricAvailable = true, biometricEnabled = true,
        )
        val visible = original.forDisplay()
        assertEquals(app.invilink.xboard.ui.Stage.HOME, visible.stage)
        assertTrue(visible.panels.isEmpty())
        assertTrue(visible.serverState.servers.isEmpty())
        assertNull(visible.serverState.error)
        assertNull(visible.activePanelId)
        assertEquals("", visible.activePanelAddress)
        assertEquals("", visible.addToken)
        assertEquals(0, visible.pendingChanges)
        assertNull(visible.syncMessage)
        assertNull(visible.settingsMessage)
        assertFalse(visible.fakePinEnabled)
        assertFalse(visible.biometricAvailable)
        assertFalse(visible.biometricEnabled)
        assertEquals(1, original.panels.size)
        assertEquals(listOf(server), original.serverState.servers)
        val real = original.copy(stealthMode = false, primaryPinRequired = false).forDisplay()
        assertEquals(original.panels, real.panels)
        assertEquals(original.serverState.servers, real.serverState.servers)
    }

    @Test
    fun primaryPinOnlyLockSurvivesLockScreenProjectionAndBiometricsStayHidden() {
        val state = app.invilink.xboard.ui.ScreenModel(stage = app.invilink.xboard.ui.Stage.UNLOCK,
            primaryPinRequired = true, biometricAvailable = true)
        val visible = state.forDisplay()
        assertEquals(app.invilink.xboard.ui.Stage.UNLOCK, visible.stage)
        assertFalse(visible.biometricAvailable)
        assertTrue(visible.primaryPinRequired)
    }

    private fun initialState(): StoredAccessState = StoredAccessState(
        salt = ByteArray(AccessPolicy.SALT_BYTES) { it.toByte() },
        verifier = ByteArray(AccessPolicy.HASH_BYTES) { (it + 1).toByte() },
    )

    private fun enrolledBiometric(): StoredBiometric = StoredBiometric(
        keyId = ByteArray(AccessPolicy.KEY_ID_BYTES) { 1 },
        iv = ByteArray(AccessPolicy.IV_BYTES) { 2 },
        ciphertext = ByteArray(AccessPolicy.CIPHERTEXT_BYTES) { 3 },
        proof = ByteArray(AccessPolicy.HASH_BYTES) { 4 },
    )

    private fun roundTrip(state: StoredAccessState): StoredAccessState {
        val encoded = AccessStateCodec.encode(state)
        val decoded = AccessStateCodec.decode(encoded)
        assertArrayEquals(encoded, AccessStateCodec.encode(decoded))
        return decoded
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            return
        }
        fail("Invalid input was accepted")
    }
}
