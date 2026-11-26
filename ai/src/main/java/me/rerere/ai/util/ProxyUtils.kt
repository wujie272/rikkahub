package me.rerere.ai.util

import me.rerere.ai.provider.ProviderProxy
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Route
import okhttp3.Response
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Create a copy of this [OkHttpClient] with proxy settings from [ProviderProxy].
 *
 * When the proxy is [ProviderProxy.None], returns `this` directly (zero overhead).
 * For [ProviderProxy.Http], clones the client via [OkHttpClient.newBuilder] and
 * sets the HTTP proxy (and authenticator if username/password are provided).
 *
 * Cloning via `newBuilder()` is cheap — OkHttp shares the connection pool, thread
 * pool, and dispatcher with the parent client.
 */
fun OkHttpClient.proxied(proxy: ProviderProxy): OkHttpClient {
    val http = when (proxy) {
        is ProviderProxy.None -> return this
        is ProviderProxy.Http -> proxy
    }

    return newBuilder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(http.address, http.port)))
        .apply {
            if (http.username.isNotBlank()) {
                val credential = Credentials.basic(http.username, http.password)
                proxyAuthenticator { _: Route?, response: Response ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
        }
        .build()
}
