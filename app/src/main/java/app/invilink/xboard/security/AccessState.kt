package app.invilink.xboard.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal enum class PinAccess { DENIED, PRIMARY, FAKE }

internal object AccessPolicy {
    const val PIN_LENGTH = 4
    const val MAX_ATTEMPTS = 5
    const val PBKDF2_ITERATIONS = 120_000
    const val SALT_BYTES = 32
    const val HASH_BYTES = 32
    const val KEY_ID_BYTES = 16
    const val IV_BYTES = 12
    const val CHALLENGE_BYTES = 32
    const val CIPHERTEXT_BYTES = CHALLENGE_BYTES + 16
    const val MAX_LOCKOUT_LEVEL = 13
    const val MAX_LOCKOUT_SECONDS = 86_400L

    fun isValidPin(pin: String): Boolean =
        pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

    fun derivePin(pin: String, salt: ByteArray): ByteArray {
        require(isValidPin(pin)) { "PIN must contain exactly four ASCII digits" }
        require(salt.size == SALT_BYTES) { "Invalid salt length" }
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, HASH_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    fun matchesPin(pin: String, salt: ByteArray, expected: ByteArray, authenticate: (ByteArray) -> ByteArray): Boolean {
        if (!isValidPin(pin)) return false
        val derived = derivePin(pin, salt)
        return try {
            val actual = authenticate(derived)
            try { java.security.MessageDigest.isEqual(expected, actual) } finally { actual.fill(0) }
        } finally { derived.fill(0) }
    }

    fun pinAccess(pin: String, state: StoredAccessState, allowFake: Boolean,
        authenticate: (ByteArray, Boolean) -> ByteArray): PinAccess {
        val primary = matchesPin(pin, state.salt, state.verifier) { authenticate(it, false) }
        val fake = state.fakePin?.takeIf { allowFake && it.enabled }?.let { record ->
            matchesPin(pin, record.salt, record.verifier) { authenticate(it, true) }
        } == true
        return when { primary -> PinAccess.PRIMARY; fake -> PinAccess.FAKE; else -> PinAccess.DENIED }
    }

    fun lockoutSeconds(level: Int): Long {
        require(level in 0..MAX_LOCKOUT_LEVEL)
        return (30L shl level).coerceAtMost(MAX_LOCKOUT_SECONDS)
    }

    fun reserveAttempt(state: StoredAccessState, bootCount: Int, elapsedMillis: Long): StoredAccessState {
        require(state.lockout == null)
        require(state.failedAttempts in 0 until MAX_ATTEMPTS)
        require(state.lockoutLevel in 0..MAX_LOCKOUT_LEVEL)
        require(bootCount >= 0 && elapsedMillis >= 0)
        val failures = state.failedAttempts + 1
        return if (failures == MAX_ATTEMPTS) {
            state.copy(
                failedAttempts = 0,
                lockoutLevel = (state.lockoutLevel + 1).coerceAtMost(MAX_LOCKOUT_LEVEL),
                lockout = StoredLockout(bootCount, elapsedMillis, lockoutSeconds(state.lockoutLevel)),
            )
        } else {
            state.copy(failedAttempts = failures)
        }
    }

    fun remainingSeconds(lockout: StoredLockout, bootCount: Int, elapsedMillis: Long): Long {
        require(bootCount >= 0 && elapsedMillis >= 0)
        if (bootCount != lockout.bootCount || elapsedMillis < lockout.startedAtMillis) {
            return lockout.durationSeconds
        }
        val remainingMillis = lockout.durationSeconds * 1_000 -
            (elapsedMillis - lockout.startedAtMillis)
        return if (remainingMillis <= 0) 0 else (remainingMillis + 999) / 1_000
    }
}

internal data class StoredLockout(
    val bootCount: Int,
    val startedAtMillis: Long,
    val durationSeconds: Long,
)

internal data class StoredBiometric(
    val keyId: ByteArray,
    val iv: ByteArray? = null,
    val ciphertext: ByteArray? = null,
    val proof: ByteArray? = null,
) {
    val isEnrolled: Boolean
        get() = iv != null && ciphertext != null && proof != null
}

internal data class StoredFakePin(
    val salt: ByteArray,
    val verifier: ByteArray,
    val enabled: Boolean = true,
    val primaryPinRequired: Boolean = false,
)

internal data class StoredAccessState(
    val salt: ByteArray,
    val verifier: ByteArray,
    val failedAttempts: Int = 0,
    val lockoutLevel: Int = 0,
    val lockout: StoredLockout? = null,
    val biometric: StoredBiometric? = null,
    val fakePin: StoredFakePin? = null,
)

internal object AccessStateCodec {
    private const val VERSION = 1
    const val MAX_PAYLOAD_BYTES = 512

    fun encode(state: StoredAccessState): ByteArray {
        validate(state)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(if (state.fakePin == null) VERSION else 2)
            output.write(state.salt)
            output.write(state.verifier)
            output.writeInt(state.failedAttempts)
            output.writeInt(state.lockoutLevel)
            val lockout = state.lockout
            output.writeBoolean(lockout != null)
            if (lockout != null) {
                output.writeInt(lockout.bootCount)
                output.writeLong(lockout.startedAtMillis)
                output.writeLong(lockout.durationSeconds)
            }
            val biometric = state.biometric
            output.writeByte(if (biometric == null) 0 else if (biometric.isEnrolled) 2 else 1)
            if (biometric != null) {
                output.write(biometric.keyId)
                if (biometric.isEnrolled) {
                    output.write(requireNotNull(biometric.iv))
                    output.write(requireNotNull(biometric.ciphertext))
                    output.write(requireNotNull(biometric.proof))
                }
            }
            state.fakePin?.let { fake ->
                output.writeByte((if (fake.enabled) 1 else 0) or (if (fake.primaryPinRequired) 2 else 0))
                output.write(fake.salt)
                output.write(fake.verifier)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): StoredAccessState {
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Invalid access state size" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val version = input.readInt()
        require(version == VERSION || version == 2) { "Unsupported access state version" }
        val salt = input.readFixed(AccessPolicy.SALT_BYTES)
        val verifier = input.readFixed(AccessPolicy.HASH_BYTES)
        val failures = input.readInt()
        val level = input.readInt()
        val lockout = when (input.readUnsignedByte()) {
            0 -> null
            1 -> StoredLockout(input.readInt(), input.readLong(), input.readLong())
            else -> throw IllegalArgumentException("Invalid lockout state")
        }
        val biometric = when (input.readUnsignedByte()) {
            0 -> null
            1 -> StoredBiometric(input.readFixed(AccessPolicy.KEY_ID_BYTES))
            2 -> StoredBiometric(
                keyId = input.readFixed(AccessPolicy.KEY_ID_BYTES),
                iv = input.readFixed(AccessPolicy.IV_BYTES),
                ciphertext = input.readFixed(AccessPolicy.CIPHERTEXT_BYTES),
                proof = input.readFixed(AccessPolicy.HASH_BYTES),
            )
            else -> throw IllegalArgumentException("Invalid biometric state")
        }
        val fakePin = if (version == 2) {
            val flags = input.readUnsignedByte()
            require(flags in 0..3) { "Invalid Fake PIN state" }
            StoredFakePin(input.readFixed(AccessPolicy.SALT_BYTES), input.readFixed(AccessPolicy.HASH_BYTES),
                enabled = flags and 1 != 0, primaryPinRequired = flags and 2 != 0)
        } else null
        require(input.available() == 0) { "Unexpected access state data" }
        return StoredAccessState(salt, verifier, failures, level, lockout, biometric, fakePin)
            .also(::validate)
    }

    private fun validate(state: StoredAccessState) {
        require(state.salt.size == AccessPolicy.SALT_BYTES)
        require(state.verifier.size == AccessPolicy.HASH_BYTES)
        require(state.failedAttempts in 0 until AccessPolicy.MAX_ATTEMPTS)
        require(state.lockoutLevel in 0..AccessPolicy.MAX_LOCKOUT_LEVEL)
        state.lockout?.let {
            require(state.failedAttempts == 0 && state.lockoutLevel > 0)
            require(it.bootCount >= 0 && it.startedAtMillis >= 0)
            require(it.durationSeconds == AccessPolicy.lockoutSeconds(state.lockoutLevel - 1))
        }
        state.fakePin?.let {
            require(it.salt.size == AccessPolicy.SALT_BYTES && it.verifier.size == AccessPolicy.HASH_BYTES)
            require(!it.primaryPinRequired || it.enabled)
        }
        state.biometric?.let {
            require(it.keyId.size == AccessPolicy.KEY_ID_BYTES)
            val hasPayload = it.iv != null || it.ciphertext != null || it.proof != null
            require(!hasPayload || it.isEnrolled)
            if (it.isEnrolled) {
                require(it.iv?.size == AccessPolicy.IV_BYTES)
                require(it.ciphertext?.size == AccessPolicy.CIPHERTEXT_BYTES)
                require(it.proof?.size == AccessPolicy.HASH_BYTES)
            }
        }
    }

    private fun DataInputStream.readFixed(size: Int): ByteArray =
        ByteArray(size).also { readFully(it) }
}
