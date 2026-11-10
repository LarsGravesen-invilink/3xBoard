package app.invilink.xboard.panels

import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PanelLocationResolver {
    private data class CacheEntry(val location: PanelLocation?, val createdAtNanos: Long)

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, CacheEntry>(CACHE_SIZE, 0.75f, true)

    suspend fun resolve(normalizedBaseUrl: String): PanelLocation? {
        currentCoroutineContext().ensureActive()
        val host = try {
            URI(PanelValidation.normalizeBaseUrl(normalizedBaseUrl)).host
                ?.removeSurrounding("[", "]")?.lowercase(Locale.ROOT) ?: return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val cached = mutex.withLock {
            cache[host]?.let { entry ->
                val ttl = if (entry.location == null) FAILURE_TTL_NANOS else SUCCESS_TTL_NANOS
                if (System.nanoTime() - entry.createdAtNanos < ttl) {
                    entry
                } else {
                    cache.remove(host)
                    null
                }
            }
        }
        if (cached != null) return cached.location
        val location = try {
            withContext(Dispatchers.IO) { lookup(host) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            null
        }
        currentCoroutineContext().ensureActive()
        mutex.withLock {
            cache[host] = CacheEntry(location, System.nanoTime())
            if (cache.size > CACHE_SIZE) {
                val iterator = cache.entries.iterator()
                iterator.next()
                iterator.remove()
            }
        }
        return location
    }

    private suspend fun lookup(host: String): PanelLocation? {
        val context = currentCoroutineContext()
        context.ensureActive()
        val addresses = InetAddress.getAllByName(host)
        context.ensureActive()
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) return null
        val ip = addresses.first().hostAddress ?: return null
        val url = URI("https", null, "ipwho.is", -1, "/$ip", null, null).toURL()
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            context.ensureActive()
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) return null
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) return null
            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - output.size()))
                    if (count == -1) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            context.ensureActive()
            val json = JSONObject(body)
            if (json.opt("success") != true) return null
            val countryCode = (json.opt("country_code") as? String)?.trim()?.uppercase(Locale.ROOT)
                ?.takeIf { it in COUNTRY_CODES } ?: return null
            val countryName = (json.opt("country") as? String)?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 128 && it.none(Char::isISOControl) } ?: return null
            val city = (json.opt("city") as? String)?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 128 && it.none(Char::isISOControl) }
            return PanelLocation(countryCode, countryName, city)
        } finally {
            connection.disconnect()
        }
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            val third = bytes[2].toInt() and 0xff
            return when {
                first == 0 || first == 10 || first == 127 || first >= 224 -> false
                first == 100 && second in 64..127 -> false
                first == 169 && second == 254 -> false
                first == 172 && second in 16..31 -> false
                first == 192 && second == 168 -> false
                first == 192 && second == 0 && (third == 0 || third == 2) -> false
                first == 192 && second == 88 && third == 99 -> false
                first == 198 && second in 18..19 -> false
                first == 198 && second == 51 && third == 100 -> false
                first == 203 && second == 0 && third == 113 -> false
                else -> true
            }
        }
        if (bytes.size != 16 || (address is Inet6Address && address.scopeId != 0)) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff
        if ((first and 0xe0) != 0x20) return false
        if (first == 0x20 && second == 0x01) {
            if (third in 0..1) return false
            if (third == 0x0d && fourth == 0xb8) return false
        }
        if (first == 0x20 && second == 0x02) return false
        if (first == 0x3f && second == 0xff && (third and 0xf0) == 0) return false
        return true
    }

    private companion object {
        const val CACHE_SIZE = 64
        const val TIMEOUT_MILLIS = 2500
        const val MAX_RESPONSE_BYTES = 16 * 1024
        const val SUCCESS_TTL_NANOS = 24L * 60 * 60 * 1_000_000_000
        const val FAILURE_TTL_NANOS = 15L * 60 * 1_000_000_000
        val COUNTRY_CODES = Locale.getISOCountries().toSet()
    }
}
