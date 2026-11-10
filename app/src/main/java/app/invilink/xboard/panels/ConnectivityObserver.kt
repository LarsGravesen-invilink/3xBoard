package app.invilink.xboard.panels

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ConnectivityObserver(context: Context) : AutoCloseable {
    private val manager = (context.applicationContext ?: context)
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableOnline = MutableStateFlow(false)
    val online: StateFlow<Boolean> = mutableOnline.asStateFlow()
    private var current: Network? = null
    private var probeJob: Job? = null
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

    private fun start(network: Network) = synchronized(lock) {
        if (closed || current == network && probeJob?.isActive == true) return@synchronized
        probeJob?.cancel()
        current = network
        mutableOnline.value = false
        probeJob = scope.launch {
            while (isActive) {
                val available = probe(network, "https://www.google.com/generate_204") ||
                    probe(network, "https://yandex.ru/generate_204")
                synchronized(lock) { if (!closed && current == network) mutableOnline.value = available }
                delay(if (available) 30_000L else 10_000L)
            }
        }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { start(network) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) start(network)
        }
        override fun onLost(network: Network) = synchronized(lock) {
            if (!closed && current == network) {
                probeJob?.cancel()
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
            manager?.activeNetwork?.let { network ->
                if (manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) start(network)
            }
        } catch (_: Exception) { mutableOnline.value = false }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (registered) try { manager?.unregisterNetworkCallback(callback) } catch (_: Exception) { }
            registered = false
            current = null
            mutableOnline.value = false
        }
        scope.cancel()
    }
}
