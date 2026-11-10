package app.invilink.xboard.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

sealed interface PinResult {
    data object Accepted : PinResult
    data class Rejected(val remainingAttempts: Int) : PinResult
    data class Locked(val seconds: Long) : PinResult
}

data class PinLoginResult(val result: PinResult, val stealth: Boolean = false)

class PinConfigurationException(message: String) : IllegalArgumentException(message)

class AccessStoreException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class AccessStore(context: Context) {
    private val applicationContext = context.applicationContext ?: context
    private val storeId = applicationContext.packageName
    private val pinKeyAlias = "$storeId.xboard.access.pin.v1"
    private val biometricKeyPrefix = "$storeId.xboard.access.biometric.v1."
    private val random = SecureRandom()
    private val preferences: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }
    private var pinAuthorizationAt: Long? = null
    private var pendingEnrollment: PendingEnrollment? = null
    private var pendingUnlock: PendingUnlock? = null

    val isConfigured: Boolean
        get() = access { readState() != null }

    val biometricEnabled: Boolean
        get() = access {
            val biometric = readState()?.biometric ?: return@access false
            if (!biometric.isEnrolled) return@access false
            biometricKey(biometric)
            true
        }

    val fakePinEnabled: Boolean
        get() = access { requireState().fakePin?.enabled == true }

    val primaryPinRequired: Boolean
        get() = access { readState()?.fakePin?.primaryPinRequired == true }

    fun createPin(pin: String) {
        require(AccessPolicy.isValidPin(pin)) { "PIN must contain exactly four ASCII digits" }
        access {
            clearPending()
            pinAuthorizationAt = null
            check(readState() == null) { "PIN is already configured" }
            val salt = randomBytes(AccessPolicy.SALT_BYTES)
            val derived = AccessPolicy.derivePin(pin, salt)
            try {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
                generator.init(
                    KeyGenParameterSpec.Builder(pinKeyAlias, KeyProperties.PURPOSE_SIGN)
                        .setKeySize(256)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
                val key = generator.generateKey()
                val verifier = authenticate(key, PIN_DOMAIN, derived)
                persist(StoredAccessState(salt = salt, verifier = verifier))
                pinAuthorizationAt = SystemClock.elapsedRealtime()
            } finally {
                derived.fill(0)
            }
        }
    }

    fun verifyPin(pin: String): PinResult = verifyPinInternal(pin, allowFake = false).result

    fun verifyLoginPin(pin: String): PinLoginResult = verifyPinInternal(pin, allowFake = true)

    private fun matchesPin(pin: String, salt: ByteArray, verifier: ByteArray, domain: ByteArray): Boolean =
        AccessPolicy.matchesPin(pin, salt, verifier) { authenticate(pinKey(), domain, it) }

    private fun verifyPinInternal(pin: String, allowFake: Boolean): PinLoginResult = access {
        clearPending()
        pinAuthorizationAt = null
        var state = requireState()
        val clock = clockSnapshot()
        val locked = state.lockout
        if (locked != null) {
            val remaining = AccessPolicy.remainingSeconds(locked, clock.bootCount, clock.elapsedMillis)
            if (remaining > 0) {
                if (locked.bootCount != clock.bootCount || clock.elapsedMillis < locked.startedAtMillis) {
                    state = state.copy(lockout = locked.copy(
                        bootCount = clock.bootCount,
                        startedAtMillis = clock.elapsedMillis,
                    ))
                    persist(state)
                }
                return@access PinLoginResult(PinResult.Locked(remaining))
            }
            state = state.copy(lockout = null)
        }
        val reserved = AccessPolicy.reserveAttempt(state, clock.bootCount, clock.elapsedMillis)
        persist(reserved)
        val role = AccessPolicy.pinAccess(pin, state, allowFake) { derived, fake ->
            authenticate(pinKey(), if (fake) FAKE_PIN_DOMAIN else PIN_DOMAIN, derived)
        }
        val primary = role == PinAccess.PRIMARY
        val fake = role == PinAccess.FAKE
        if (primary || fake) {
            persist(reserved.copy(failedAttempts = 0, lockoutLevel = 0, lockout = null,
                fakePin = reserved.fakePin?.copy(primaryPinRequired = !primary && fake)))
            if (primary) pinAuthorizationAt = SystemClock.elapsedRealtime()
            PinLoginResult(PinResult.Accepted, stealth = !primary && fake)
        } else {
            val lockout = reserved.lockout
            PinLoginResult(if (lockout != null) {
                PinResult.Locked(AccessPolicy.remainingSeconds(lockout, clock.bootCount, SystemClock.elapsedRealtime()))
            } else {
                PinResult.Rejected(AccessPolicy.MAX_ATTEMPTS - reserved.failedAttempts)
            })
        }
    }

    fun configureFakePin(currentPin: String, newPin: String?, enabled: Boolean): PinResult {
        require(!enabled || newPin != null && AccessPolicy.isValidPin(newPin)) { "Дополнительный PIN должен содержать четыре цифры." }
        if (enabled && currentPin == newPin) throw PinConfigurationException("Основной и дополнительный PIN должны различаться.")
        return access {
            val result = verifyPin(currentPin)
            if (result != PinResult.Accepted) return@access result
            val state = requireState()
            val fake = if (enabled) {
                val salt = randomBytes(AccessPolicy.SALT_BYTES)
                val derived = AccessPolicy.derivePin(requireNotNull(newPin), salt)
                try { StoredFakePin(salt, authenticate(pinKey(), FAKE_PIN_DOMAIN, derived)) }
                finally { derived.fill(0) }
            } else state.fakePin?.copy(enabled = false, primaryPinRequired = false)
            persist(state.copy(fakePin = fake))
            PinResult.Accepted
        }
    }

    fun changePin(currentPin: String, newPin: String): PinResult {
        require(AccessPolicy.isValidPin(newPin)) { "Новый PIN должен содержать ровно четыре цифры ASCII (0–9)" }
        return access {
            val result = verifyPin(currentPin)
            if (result != PinResult.Accepted) return@access result
            pinAuthorizationAt = null
            val state = requireState()
            if (state.fakePin?.let { matchesPin(newPin, it.salt, it.verifier, FAKE_PIN_DOMAIN) } == true) {
                throw PinConfigurationException("Основной и дополнительный PIN должны различаться.")
            }
            try {
                val salt = randomBytes(AccessPolicy.SALT_BYTES)
                val derived = AccessPolicy.derivePin(newPin, salt)
                try {
                    val verifier = authenticate(pinKey(), PIN_DOMAIN, derived)
                    try {
                        persist(state.copy(
                            salt = salt,
                            verifier = verifier,
                            failedAttempts = 0,
                            lockoutLevel = 0,
                            lockout = null,
                        ))
                    } finally {
                        verifier.fill(0)
                    }
                } finally {
                    derived.fill(0)
                }
            } finally {
                state.verifier.fill(0)
            }
            pinAuthorizationAt = SystemClock.elapsedRealtime()
            PinResult.Accepted
        }
    }

    fun lockoutRemainingSeconds(): Long = access {
        var state = readState() ?: return@access 0L
        val lockout = state.lockout ?: return@access 0L
        val clock = clockSnapshot()
        if (clock.bootCount != lockout.bootCount || clock.elapsedMillis < lockout.startedAtMillis) {
            state = state.copy(lockout = lockout.copy(
                bootCount = clock.bootCount,
                startedAtMillis = clock.elapsedMillis,
            ))
            persist(state)
        }
        AccessPolicy.remainingSeconds(requireNotNull(state.lockout), clock.bootCount, clock.elapsedMillis)
    }

    fun disableBiometric() {
        access {
            clearPending()
            pinAuthorizationAt = null
            val state = readState() ?: return@access
            val biometric = state.biometric ?: return@access
            val alias = biometricAlias(biometric)
            keyStore.deleteEntry(alias)
            check(!keyStore.containsAlias(alias)) { "Biometric key deletion failed" }
            persist(state.copy(biometric = null))
        }
    }

    fun createBiometricEnrollmentCipher(): Cipher = access {
        clearPending()
        var state = requireState()
        val authorization = pinAuthorizationAt
        val now = SystemClock.elapsedRealtime()
        check(authorization != null && now >= authorization && now - authorization <= PIN_AUTHORIZATION_MILLIS) {
            "Verify the PIN before biometric enrollment"
        }
        requireStrongBiometric()
        check(state.biometric?.isEnrolled != true) { "Disable existing biometric enrollment first" }
        var biometric = state.biometric
        val key = if (biometric == null) {
            biometric = StoredBiometric(randomBytes(AccessPolicy.KEY_ID_BYTES))
            check(!keyStore.containsAlias(biometricAlias(biometric))) { "Biometric key already exists" }
            state = state.copy(biometric = biometric)
            persist(state)
            generateBiometricKey(biometricAlias(biometric))
        } else {
            biometricKey(biometric)
        }
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        pendingEnrollment = PendingEnrollment(
            cipher = cipher,
            keyId = biometric.keyId.copyOf(),
            challenge = randomBytes(AccessPolicy.CHALLENGE_BYTES),
        )
        cipher
    }

    fun finishBiometricEnrollment(cipher: Cipher) {
        access {
            val pending = pendingEnrollment
                ?: throw AccessStoreException("No biometric enrollment is pending")
            pendingEnrollment = null
            try {
                check(cipher === pending.cipher) { "Unexpected biometric enrollment cipher" }
                val state = requireState()
                val biometric = state.biometric
                    ?: throw AccessStoreException("Biometric enrollment was cancelled")
                check(!biometric.isEnrolled && MessageDigest.isEqual(biometric.keyId, pending.keyId)) {
                    "Biometric enrollment changed"
                }
                biometricKey(biometric)
                val ciphertext = cipher.doFinal(pending.challenge)
                val iv = cipher.iv ?: throw AccessStoreException("Missing biometric initialization vector")
                check(iv.size == AccessPolicy.IV_BYTES && ciphertext.size == AccessPolicy.CIPHERTEXT_BYTES) {
                    "Invalid biometric ciphertext"
                }
                val proof = MessageDigest.getInstance("SHA-256").digest(pending.challenge)
                persist(state.copy(biometric = biometric.copy(
                    iv = iv.copyOf(),
                    ciphertext = ciphertext,
                    proof = proof,
                )))
                pinAuthorizationAt = null
            } finally {
                pending.challenge.fill(0)
            }
        }
    }

    fun createBiometricUnlockCipher(): Cipher = access {
        clearPending()
        pinAuthorizationAt = null
        val state = requireState()
        check(state.fakePin?.primaryPinRequired != true) { "Войдите по PIN-коду." }
        val biometric = state.biometric
            ?: throw AccessStoreException("Biometric enrollment is not configured")
        check(biometric.isEnrolled) { "Biometric enrollment is incomplete" }
        requireStrongBiometric()
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            biometricKey(biometric),
            GCMParameterSpec(128, requireNotNull(biometric.iv)),
        )
        pendingUnlock = PendingUnlock(cipher, biometric)
        cipher
    }

    fun finishBiometricUnlock(cipher: Cipher): Boolean = access {
        val pending = pendingUnlock ?: throw AccessStoreException("No biometric unlock is pending")
        pendingUnlock = null
        check(cipher === pending.cipher) { "Unexpected biometric unlock cipher" }
        val state = requireState()
        check(state.fakePin?.primaryPinRequired != true) { "Войдите по PIN-коду." }
        val biometric = state.biometric
            ?: throw AccessStoreException("Biometric enrollment was disabled")
        check(sameBiometric(biometric, pending.biometric)) { "Biometric enrollment changed" }
        biometricKey(biometric)
        val challenge = cipher.doFinal(requireNotNull(biometric.ciphertext))
        val accepted = try {
            val actual = MessageDigest.getInstance("SHA-256").digest(challenge)
            try {
                val equal = MessageDigest.isEqual(requireNotNull(biometric.proof), actual)
                challenge.size == AccessPolicy.CHALLENGE_BYTES && equal
            } finally {
                actual.fill(0)
            }
        } finally {
            challenge.fill(0)
        }
        if (accepted) {
            persist(state.copy(failedAttempts = 0, lockoutLevel = 0, lockout = null))
        }
        accepted
    }

    private fun requireState(): StoredAccessState =
        readState() ?: throw AccessStoreException("PIN is not configured")

    private fun readState(): StoredAccessState? {
        val entries = preferences.all
        if (entries.isEmpty()) {
            val aliases = keyStore.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                check(alias != pinKeyAlias && !alias.startsWith(biometricKeyPrefix)) {
                    "Access state is missing while security keys exist"
                }
            }
            return null
        }
        check(entries.size == 1 && entries.containsKey(STATE_KEY)) { "Invalid access preferences" }
        val encoded = entries[STATE_KEY] as? String
            ?: throw AccessStoreException("Invalid access state encoding")
        check(encoded.length <= MAX_ENCODED_LENGTH) { "Access state is too large" }
        val envelope = Base64.decode(encoded, Base64.NO_WRAP)
        check(Base64.encodeToString(envelope, Base64.NO_WRAP) == encoded) { "Invalid access state encoding" }
        check(envelope.size > AccessPolicy.HASH_BYTES &&
            envelope.size <= AccessStateCodec.MAX_PAYLOAD_BYTES + AccessPolicy.HASH_BYTES) {
            "Invalid access state size"
        }
        val payload = envelope.copyOfRange(0, envelope.size - AccessPolicy.HASH_BYTES)
        val signature = envelope.copyOfRange(payload.size, envelope.size)
        val expected = authenticate(pinKey(), STATE_DOMAIN, payload)
        val valid = try {
            MessageDigest.isEqual(signature, expected)
        } finally {
            expected.fill(0)
        }
        check(valid) { "Access state integrity check failed" }
        return AccessStateCodec.decode(payload)
    }

    private fun persist(state: StoredAccessState) {
        try {
            val payload = AccessStateCodec.encode(state)
            val signature = authenticate(pinKey(), STATE_DOMAIN, payload)
            val encoded = Base64.encodeToString(payload + signature, Base64.NO_WRAP)
            check(preferences.edit().putString(STATE_KEY, encoded).commit()) { "Access state commit failed" }
            check(preferences.all.size == 1 && preferences.getString(STATE_KEY, null) == encoded) {
                "Access state readback failed"
            }
            check(readState() != null) { "Access state validation failed" }
        } catch (exception: Exception) {
            failedStores.add(storeId)
            throw AccessStoreException("Secure storage write failed; access is blocked", exception)
        }
    }

    private fun pinKey(): SecretKey {
        val key = keyStore.getKey(pinKeyAlias, null) as? SecretKey
            ?: throw AccessStoreException("PIN security key is unavailable")
        check(key.algorithm.equals(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ignoreCase = true) && key.encoded == null) {
            "Invalid PIN security key"
        }
        return key
    }

    private fun biometricKey(biometric: StoredBiometric): SecretKey {
        val key = keyStore.getKey(biometricAlias(biometric), null) as? SecretKey
            ?: throw AccessStoreException("Biometric security key is unavailable; use the PIN")
        check(key.algorithm.equals(KeyProperties.KEY_ALGORITHM_AES, ignoreCase = true) && key.encoded == null) {
            "Invalid biometric security key; use the PIN"
        }
        val info = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        check(info.isUserAuthenticationRequired && info.userAuthenticationValidityDurationSeconds <= 0 &&
            info.isInvalidatedByBiometricEnrollment &&
            info.purposes == (KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT) &&
            info.blockModes.contentEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM)) &&
            info.encryptionPaddings.contentEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE)) &&
            info.keySize == 256) {
            "Unsafe biometric security key; use the PIN"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            check(info.userAuthenticationType == KeyProperties.AUTH_BIOMETRIC_STRONG) {
                "Biometric security key requires an unexpected authenticator"
            }
        }
        return key
    }

    @Suppress("DEPRECATION")
    private fun generateBiometricKey(alias: String): SecretKey {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun requireStrongBiometric() {
        check(BiometricManager.from(applicationContext).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS) {
            "Strong biometric authentication is unavailable; use the PIN"
        }
    }

    private fun biometricAlias(biometric: StoredBiometric): String =
        biometricKeyPrefix + biometric.keyId.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun sameBiometric(first: StoredBiometric, second: StoredBiometric): Boolean =
        first.isEnrolled && second.isEnrolled &&
            MessageDigest.isEqual(first.keyId, second.keyId) &&
            MessageDigest.isEqual(first.iv, second.iv) &&
            MessageDigest.isEqual(first.ciphertext, second.ciphertext) &&
            MessageDigest.isEqual(first.proof, second.proof)

    private fun authenticate(key: SecretKey, domain: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
            init(key)
            update(domain)
            doFinal(data)
        }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun clockSnapshot(): ClockSnapshot {
        val bootCount = Settings.Global.getInt(applicationContext.contentResolver, Settings.Global.BOOT_COUNT)
        check(bootCount >= 0) { "Boot counter is unavailable" }
        return ClockSnapshot(bootCount, SystemClock.elapsedRealtime())
    }

    private fun clearPending() {
        pendingEnrollment?.challenge?.fill(0)
        pendingEnrollment = null
        pendingUnlock = null
    }

    private fun <T> access(operation: () -> T): T = synchronized(monitor) {
        if (storeId in failedStores) {
            throw AccessStoreException("Secure storage previously failed; access is blocked")
        }
        try {
            operation()
        } catch (exception: PinConfigurationException) {
            throw exception
        } catch (exception: AccessStoreException) {
            throw exception
        } catch (exception: Exception) {
            throw AccessStoreException("Secure access operation failed; use the PIN if available", exception)
        }
    }

    private data class ClockSnapshot(val bootCount: Int, val elapsedMillis: Long)
    private data class PendingEnrollment(val cipher: Cipher, val keyId: ByteArray, val challenge: ByteArray)
    private data class PendingUnlock(val cipher: Cipher, val biometric: StoredBiometric)

    private companion object {
        const val PREFERENCES_NAME = "xboard.access.v1"
        const val STATE_KEY = "authenticated_state"
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_ENCODED_LENGTH = 1_024
        const val PIN_AUTHORIZATION_MILLIS = 300_000L
        val PIN_DOMAIN = "xboard.pin.verifier.v1\u0000".toByteArray(Charsets.UTF_8)
        val FAKE_PIN_DOMAIN = "xboard.fake.pin.verifier.v1\u0000".toByteArray(Charsets.UTF_8)
        val STATE_DOMAIN = "xboard.access.state.v1\u0000".toByteArray(Charsets.UTF_8)
        val monitor = Any()
        val failedStores = mutableSetOf<String>()
    }
}
