package app.whittle.linux

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/**
 * The phone's IPv4 address on Wi-Fi, or null when it isn't on Wi-Fi: where a computer on the same network reaches the
 * SSH server. Watched from the first [address] read for as long as the app runs, which costs nothing between changes.
 */
class WifiAddress(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val current = MutableStateFlow<String?>(null)
    private val networks = mutableMapOf<Network, String?>()
    private var watching = false

    /** The address now, kept up to date. */
    val address: StateFlow<String?>
        get() {
            watch()
            return current.asStateFlow()
        }

    @Synchronized
    private fun watch() {
        if (watching) return
        watching = true
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivity.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = changed { networks[network] = ipv4(linkProperties) }

            override fun onLost(network: Network) = changed { networks.remove(network) }
        })
    }

    private fun changed(change: () -> Unit) = synchronized(this) {
        change()
        current.value = networks.values.firstNotNullOfOrNull { it }
    }

    private fun ipv4(properties: LinkProperties): String? =
        properties.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
}
