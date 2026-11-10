package app.invilink.xboard.ssh

import android.util.Base64
import com.jcraft.jsch.HostKeyRepository
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class PinnedHostsTest {
    private val host = "example.test"
    private val key = ed25519Key(1)

    private fun ed25519Key(value: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            val algorithm = "ssh-ed25519".toByteArray(Charsets.US_ASCII)
            out.writeInt(algorithm.size)
            out.write(algorithm)
            out.writeInt(32)
            out.write(ByteArray(32) { value.toByte() })
        }
        return bytes.toByteArray()
    }

    private fun identity(key: ByteArray = this.key, port: Int = 22) = SshHostIdentity(
        host, port, "ssh-ed25519", Base64.encodeToString(key, Base64.NO_WRAP),
        "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP or Base64.NO_PADDING),
    )

    @Test
    fun unknownHostIsRejectedWithFingerprintUntilExplicitlyPinned() {
        var pin: SshHostIdentity? = null
        val first = SshConnection.PinnedHosts({ h, p ->
            assertEquals(host, h)
            assertEquals(22, p)
            pin
        }, host, 22)
        assertEquals(HostKeyRepository.NOT_INCLUDED, first.check(host, key))
        assertNull(first.verified)
        val unknown = first.failure as UnknownSshHost
        assertEquals(identity(), unknown.identity)
        // A rejected session cannot be made valid by changing the store mid-handshake.
        pin = unknown.identity
        assertEquals(HostKeyRepository.CHANGED, first.check(host, key))
        assertNull(first.verified)

        val retry = SshConnection.PinnedHosts({ _, _ -> pin }, host, 22)
        assertEquals(HostKeyRepository.OK, retry.check(host, key))
        assertEquals(identity(), retry.verified)
        assertNull(retry.failure)
    }

    @Test
    fun changedKeyOrAlgorithmIsRejectedWithoutVerification() {
        for (pin in listOf(identity(ed25519Key(2)), identity().copy(algorithm = "ssh-rsa"))) {
            val repository = SshConnection.PinnedHosts({ _, _ -> pin }, host, 22)
            assertEquals(HostKeyRepository.CHANGED, repository.check(host, key))
            assertNull(repository.verified)
            assertEquals(SshFailureReason.HOST_CHANGED, (repository.failure as SshConnectionException).reason)
        }
    }

    @Test
    fun addressPortAndStorageFailuresFailClosed() {
        var called = false
        val wrongAddress = SshConnection.PinnedHosts({ _, _ -> called = true; identity() }, host, 22)
        assertEquals(HostKeyRepository.CHANGED, wrongAddress.check("other.test", key))
        assertFalse(called)
        assertEquals(SshFailureReason.HOST_ADDRESS, (wrongAddress.failure as SshConnectionException).reason)

        val otherPort = SshConnection.PinnedHosts({ h, p ->
            assertEquals(host, h)
            assertEquals(2222, p)
            identity(port = 2222)
        }, host, 2222)
        assertEquals(HostKeyRepository.OK, otherPort.check("[$host]:2222", key))
        assertEquals(identity(port = 2222), otherPort.verified)

        val broken = SshConnection.PinnedHosts({ _, _ -> throw IllegalStateException("storage unavailable") }, host, 22)
        assertEquals(HostKeyRepository.CHANGED, broken.check(host, key))
        assertNull(broken.verified)
        assertEquals(SshFailureReason.HOST_STORAGE, (broken.failure as SshConnectionException).reason)
    }

    @Test
    fun sessionPasswordIsWipedEvenBeforeConnect() {
        val session = com.jcraft.jsch.JSch().getSession("user", host, 22)
        session.setPassword("secret".toByteArray(Charsets.UTF_8))
        val field = com.jcraft.jsch.Session::class.java.getDeclaredField("password").apply { isAccessible = true }
        val original = field.get(session) as ByteArray
        clearSessionPassword(session)
        assertTrue(original.all { it == 0.toByte() })
        assertEquals(0, (field.get(session) as ByteArray).size)
    }

    @Test
    fun recheckDoesNotRetainPreviouslyVerifiedIdentity() {
        val repository = SshConnection.PinnedHosts({ _, _ -> identity() }, host, 22)
        assertEquals(HostKeyRepository.OK, repository.check(host, key))
        assertEquals(HostKeyRepository.CHANGED, repository.check(host, ed25519Key(2)))
        assertNull(repository.verified)
        assertEquals(SshFailureReason.HOST_CHANGED, (repository.failure as SshConnectionException).reason)
    }
}
