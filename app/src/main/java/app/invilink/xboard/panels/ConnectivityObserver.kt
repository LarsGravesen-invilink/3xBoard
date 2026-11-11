package app.invilink.xboard.panels

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun connectivityStatus(hasInternet: Boolean, validated: Boolean, probeSucceeded: Boolean): Boolean =
    hasInternet && (validated || probeSucceeded)

class ConnectivityObserver(context: Context) : AutoCloseable {
    private val manager = (context.applicationContext ?: context)
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableOnline = MutableStateFlow(false)
    val online: StateFlow<Boolean> = mutableOnline.asStateFlow()
    private var current: Network? = null
    private var probeJob: Job? = null
    private var generation = 0L
    private var registered = false
    private var closed = false

    private fun probe(network: Network, address: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = network.openConnection(URL(address)) as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.responseCode == 204
        } catch (_: Exception) { false }
        finally { connection?.disconnect() }
    }

    private fun update(network: Network, capabilities: NetworkCapabilities?, force: Boolean = false) = synchronized(lock) {
        if (closed) return@synchronized
        val internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (!internet) {
            if (current == network) {
                generation++
                probeJob?.cancel()
                probeJob = null
                current = null
                mutableOnline.value = false
            }
            return@synchronized
        }
        if (validated) {
            generation++
            probeJob?.cancel()
            probeJob = null
            current = network
            mutableOnline.value = connectivityStatus(internet, validated, false)
            return@synchronized
        }
        if (!force && current == network && probeJob?.isActive == true) return@synchronized
        val changed = current != network
        generation++
        val ticket = generation
        probeJob?.cancel()
        current = network
        if (changed) mutableOnline.value = false
        probeJob = scope.launch {
            while (isActive) {
                val available = coroutineScope {
                    val google = async { probe(network, "https://www.google.com/generate_204") }
                    val yandex = async { probe(network, "https://yandex.ru/generate_204") }
                    google.await() or yandex.await()
                }
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (!closed && current == network && generation == ticket) {
                        mutableOnline.value = connectivityStatus(true, false, available)
                    }
                }
                delay(if (available) 30_000L else 3_000L)
            }
        }
    }

    fun refresh() {
        val active = try { manager?.activeNetwork } catch (_: Exception) { null }
        if (active == null) {
            synchronized(lock) {
                if (!closed) {
                    generation++
                    probeJob?.cancel()
                    probeJob = null
                    current = null
                    mutableOnline.value = false
                }
            }
            return
        }
        val capabilities = try { manager?.getNetworkCapabilities(active) } catch (_: Exception) { null }
        update(active, capabilities, force = true)
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val capabilities = try { manager?.getNetworkCapabilities(network) } catch (_: Exception) { null }
            if (capabilities != null) update(network, capabilities)
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            update(network, capabilities)
        }
        override fun onLost(network: Network) = synchronized(lock) {
            if (!closed && current == network) {
                generation++
                probeJob?.cancel()
                probeJob = null
                current = null
                mutableOnline.value = false
            }
        }
        override fun onUnavailable() = synchronized(lock) {
            if (!closed && current == null) mutableOnline.value = false
        }
    }

    init {
        try {
            manager?.registerDefaultNetworkCallback(callback)
            registered = manager != null
            refresh()
        } catch (_: Exception) { mutableOnline.value = false }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            if (registered) try { manager?.unregisterNetworkCallback(callback) } catch (_: Exception) { }
            registered = false
            current = null
            mutableOnline.value = false
        }
        scope.cancel()
    }
}
