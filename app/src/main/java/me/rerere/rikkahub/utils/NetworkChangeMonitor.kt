package me.rerere.rikkahub.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference

private const val TAG = "NetworkChangeMonitor"

object NetworkChangeMonitor {
    @Volatile
    private var started: Boolean = false
    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var lastDefaultHandle: Long? = null
    private val clients: MutableList<WeakReference<OkHttpClient>> = mutableListOf()
    private val networkChangeListeners: MutableList<() -> Unit> = mutableListOf()

    @Synchronized
    fun addNetworkChangeListener(listener: () -> Unit) {
        networkChangeListeners.add(listener)
    }

    @Synchronized
    fun register(client: OkHttpClient) {
        val iter = clients.iterator()
        while (iter.hasNext()) {
            val c = iter.next().get()
            when {
                c == null -> iter.remove()
                c === client -> return
                else -> Unit
            }
        }
        clients.add(WeakReference(client))
        runCatching { Log.d(TAG, "registered OkHttp client (now ${clients.size} active)") }
    }

    fun start(context: Context, vararg client: OkHttpClient) {
        for (c in client) register(c)
        if (started) return
        synchronized(this) {
            if (started) return
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val handle = network.networkHandle
                    val prev = lastDefaultHandle
                    if (handle != prev) {
                        Log.i(TAG, "default network changed ($prev -> $handle), evicting pools")
                        lastDefaultHandle = handle
                        evictAll()
                        notifyNetworkChangeListeners()
                    }
                }
                override fun onLost(network: Network) {
                    if (lastDefaultHandle == network.networkHandle) {
                        lastDefaultHandle = null
                    }
                }
            }
            try {
                cm.registerDefaultNetworkCallback(cb)
                callback = cb
                started = true
                Log.i(TAG, "registered default network callback for ${clients.size} client(s)")
            } catch (t: Throwable) {
                Log.w(TAG, "registerDefaultNetworkCallback failed", t)
            }
        }
    }

    @Synchronized
    private fun evictAll() {
        val iter = clients.iterator()
        while (iter.hasNext()) {
            val c = iter.next().get()
            if (c == null) { iter.remove(); continue }
            runCatching { c.connectionPool.evictAll() }
                .onFailure { Log.w(TAG, "connectionPool.evictAll failed", it) }
        }
    }

    @Synchronized
    private fun notifyNetworkChangeListeners() {
        for (listener in networkChangeListeners) {
            runCatching { listener() }
                .onFailure { Log.w(TAG, "network change listener failed", it) }
        }
    }
}
