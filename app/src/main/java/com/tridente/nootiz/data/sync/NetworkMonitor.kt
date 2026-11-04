package com.tridente.nootiz.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _hasTransport = MutableStateFlow(true)
    val hasTransport: StateFlow<Boolean> = _hasTransport.asStateFlow()

    private val _reconnectEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val reconnectEvents: SharedFlow<Unit> = _reconnectEvents.asSharedFlow()

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val active = computeHasActiveTransport()
            _hasTransport.value = active
            if (active) {
                _reconnectEvents.tryEmit(Unit)
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val active = computeHasActiveTransport()
            val wasOffline = !_hasTransport.value
            _hasTransport.value = active
            if (active && wasOffline) {
                _reconnectEvents.tryEmit(Unit)
            } else if (active) {
                // Also emit when switching transports (e.g. Cellular -> LAN Wi-Fi)
                _reconnectEvents.tryEmit(Unit)
            }
        }

        override fun onLost(network: Network) {
            _hasTransport.value = computeHasActiveTransport()
        }

        override fun onUnavailable() {
            _hasTransport.value = false
        }
    }

    fun start() {
        if (registered) return
        _hasTransport.value = computeHasActiveTransport()
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(callback)
            registered = true
        }
    }

    fun stop() {
        if (!registered) return
        runCatching {
            connectivityManager?.unregisterNetworkCallback(callback)
        }
        registered = false
    }

    fun computeHasActiveTransport(): Boolean {
        val cm = connectivityManager ?: return true
        return runCatching {
            val activeNet = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(activeNet) ?: return@runCatching false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }
}

