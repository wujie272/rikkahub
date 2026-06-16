package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

private const val TAG_SSH = "SshTool"
private const val PROBE_PER_NETWORK_TIMEOUT_MS = 2_500
private const val SOCKET_CONNECT_TIMEOUT_MS = 10_000
private const val SERVER_ALIVE_INTERVAL_MS = 30_000
private const val SERVER_ALIVE_COUNT_MAX = 3

internal val sshDnsCache: DnsCache = DnsCache().also { cache ->
    runCatching { me.rerere.rikkahub.utils.NetworkChangeMonitor.addNetworkChangeListener { cache.invalidateAll() } }
}

private const val MAX_RETURNED_STDOUT = 8_000
private const val MAX_RETURNED_STDERR = 2_000

private fun cap(s: String, max: Int): String =
    if (s.length > max) s.take(max) + "\n…[truncated; ${s.length - max} bytes more]" else s

internal fun shellSingleQuote(s: String): String =
    "'" + s.replace("'", "'\\''") + "'"

internal fun wrapDetachedCommand(command: String): String =
    "nohup sh -c ${shellSingleQuote(command)} >/dev/null 2>&1 & echo \$!"

internal fun resolveToIPv4(host: String): String? {
    sshDnsCache.get(host)?.let { cached ->
        Log.i(TAG_SSH, "resolveToIPv4: $host -> $cached (dns cache hit)")
        return cached
    }
    return try {
        val addrs = InetAddress.getAllByName(host)
        if (addrs.isNotEmpty() && addrs[0].hostAddress.equals(host, ignoreCase = true)) return null
        val v4 = addrs.firstOrNull { it is Inet4Address } ?: return null
        v4.hostAddress?.also {
            sshDnsCache.put(host, it)
            Log.i(TAG_SSH, "resolveToIPv4: $host -> $it (skipping ${addrs.size - 1} other records)")
        }
    } catch (t: Throwable) {
        Log.w(TAG_SSH, "resolveToIPv4: $host failed", t)
        null
    }
}

private fun enumerateCandidateNetworks(ctx: Context): List<Pair<String, Network?>> {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return listOf("default" to null)
    val out = mutableListOf<Pair<String, Network?>>()
    @Suppress("DEPRECATION")
    val all = try { cm.allNetworks.toList() } catch (_: Throwable) { emptyList() }
    fun caps(n: Network) = try { cm.getNetworkCapabilities(n) } catch (_: Throwable) { null }

    val wifis = all.filter { caps(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        .sortedByDescending {
            val c = caps(it)
            val validated = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val internet = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            (if (validated) 2 else 0) + (if (internet) 1 else 0)
        }
    wifis.forEachIndexed { i, n -> out += (if (wifis.size == 1) "wifi" else "wifi$i") to n }
    all.firstOrNull { caps(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true }
        ?.let { out += "ethernet" to it }
    all.firstOrNull { caps(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true }
        ?.let { out += "cellular" to it }
    out += "default" to null
    return out
}

internal data class SshAuth(
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
)

internal fun SshAuth.isUsable() = !password.isNullOrBlank() || !privateKey.isNullOrBlank()

internal fun newJSch(context: Context): JSch {
    val jsch = JSch()
    val knownHosts = knownHostsFile(context)
    if (!knownHosts.exists()) {
        try { knownHosts.createNewFile() } catch (_: Throwable) {}
    }
    try { jsch.setKnownHosts(knownHosts.absolutePath) } catch (_: Throwable) {}
    return jsch
}

internal fun knownHostsFile(context: Context): File = File(context.filesDir, "known_hosts")

internal fun openSshSession(
    jsch: JSch,
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    timeoutMs: Int,
    network: Network? = null,
): Session {
    if (!auth.privateKey.isNullOrBlank()) {
        val keyBytes = auth.privateKey.toByteArray(Charsets.UTF_8)
        val passBytes = auth.passphrase?.toByteArray(Charsets.UTF_8)
        jsch.addIdentity("rikkahub-ssh-key-${System.nanoTime()}", keyBytes, null, passBytes)
    }
    val ipv4 = resolveToIPv4(host)
    val effectiveHost = ipv4 ?: host
    val session = jsch.getSession(user, effectiveHost, port)
    if (ipv4 != null && ipv4 != host) { session.setHostKeyAlias(host) }
    if (!auth.password.isNullOrBlank()) session.setPassword(auth.password)
    session.setConfig(Properties().apply {
        setProperty("StrictHostKeyChecking", "accept-new")
        setProperty("PreferredAuthentications", "publickey,keyboard-interactive,password")
    })
    session.setSocketFactory(NetworkBoundSocketFactory(network, SOCKET_CONNECT_TIMEOUT_MS))
    session.serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
    session.serverAliveCountMax = SERVER_ALIVE_COUNT_MAX
    session.connect(timeoutMs)
    return session
}

private class NetworkBoundSocketFactory(
    private val network: Network?,
    private val connectTimeoutMs: Int,
) : com.jcraft.jsch.SocketFactory {
    override fun createSocket(host: String, port: Int): java.net.Socket {
        val s = java.net.Socket()
        if (network != null) {
            try { network.bindSocket(s) } catch (_: Throwable) { }
        }
        s.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
        return s
    }
    override fun getInputStream(socket: java.net.Socket): java.io.InputStream = socket.getInputStream()
    override fun getOutputStream(socket: java.net.Socket): java.io.OutputStream = socket.getOutputStream()
}

internal data class ProbeOutcome(
    val winningNetwork: Network?,
    val winningLabel: String?,
    val failures: List<Pair<String, String>>,
    val resolvedIp: String,
    val totalMs: Long,
)

internal suspend fun probeReachability(context: Context, host: String, port: Int): ProbeOutcome {
    val probeStart = System.currentTimeMillis()
    val resolvedIp = resolveToIPv4(host) ?: host
    val attempts = enumerateCandidateNetworks(context)
    val results = withContext(Dispatchers.IO) {
        coroutineScope {
            attempts.map { (label, candidate) ->
                async {
                    val s = java.net.Socket()
                    try {
                        if (candidate != null) {
                            try { candidate.bindSocket(s) } catch (t: Throwable) {
                                Log.w(TAG_SSH, "bindSocket to $label failed", t)
                            }
                        }
                        s.connect(java.net.InetSocketAddress(resolvedIp, port), PROBE_PER_NETWORK_TIMEOUT_MS)
                        Triple(label, candidate, null as String?)
                    } catch (e: Throwable) {
                        Triple(label, candidate, "${e::class.java.simpleName}: ${e.message ?: "unknown"}")
                    } finally {
                        try { s.close() } catch (_: Throwable) {}
                    }
                }
            }.awaitAll()
        }
    }
    val winner = results.firstOrNull { it.third == null }
    val failures = results.filter { it.third != null }.map { it.first to (it.third ?: "unknown") }
    val totalMs = System.currentTimeMillis() - probeStart
    if (winner != null) {
        Log.i(TAG_SSH, "tcp probe ok via ${winner.first} in ${totalMs}ms")
        return ProbeOutcome(winner.second, winner.first, failures, resolvedIp, totalMs)
    }
    return ProbeOutcome(null, null, failures, resolvedIp, totalMs)
}

internal fun unreachableEnvelope(host: String, port: Int, outcome: ProbeOutcome): JsonObject = buildJsonObject {
    put("error", "tcp_unreachable")
    put("host", host)
    put("ip", outcome.resolvedIp)
    put("port", port)
    put("attempts", buildJsonObject {
        outcome.failures.forEach { (label, reason) -> put(label, reason) }
    })
    put("recovery", "Direct TCP to ${outcome.resolvedIp}:$port failed across every available " +
            "network (${outcome.totalMs}ms total). If Termux ssh from the same device reaches " +
            "this host, RikkaHub's process is being filtered. Check Settings → Network → " +
            "Private DNS (try Off), any active VPN's per-app routing, and Settings → Apps → " +
            "RikkaHub → Mobile data & Wi-Fi (enable Background data and Unrestricted data usage).")
}

internal fun runOnSession(session: Session, command: String, timeoutMs: Int, stdin: String? = null): JsonObject {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val channel = session.openChannel("exec") as ChannelExec
    var hitDeadline = false
    try {
        channel.setCommand(command)
        channel.outputStream = stdout
        channel.setErrStream(stderr)
        channel.setInputStream(ByteArrayInputStream((stdin ?: "").toByteArray(Charsets.UTF_8)))
        channel.connect(timeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!channel.isClosed) {
            if (System.currentTimeMillis() >= deadline) { hitDeadline = true; break }
            try { Thread.sleep(50) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); break
            }
        }
        if (hitDeadline) {
            return buildJsonObject {
                put("error", "command_timeout")
                put("recovery", "Command did not complete within ${timeoutMs / 1000}s. Bump " +
                        "timeout_seconds, or pass background=true to launch it detached.")
                put("partial_stdout", cap(stdout.toString(Charsets.UTF_8), MAX_RETURNED_STDOUT))
                put("partial_stderr", cap(stderr.toString(Charsets.UTF_8), MAX_RETURNED_STDERR))
            }
        }
        val exitCode = channel.exitStatus
        return buildJsonObject {
            put("success", exitCode == 0)
            put("exit_code", exitCode)
            put("stdout", cap(stdout.toString(Charsets.UTF_8), MAX_RETURNED_STDOUT))
            put("stderr", cap(stderr.toString(Charsets.UTF_8), MAX_RETURNED_STDERR))
        }
    } finally {
        try { channel.disconnect() } catch (_: Throwable) {}
    }
}

internal suspend fun runCancellableSshOp(
    timeoutMs: Long,
    block: suspend (AtomicReference<Session?>) -> JsonObject,
): JsonObject {
    val sessionRef = AtomicReference<Session?>(null)
    return try {
        withTimeoutOrNull(timeoutMs) { block(sessionRef) }
            ?: buildJsonObject { put("error", "timeout") }
    } finally {
        sessionRef.getAndSet(null)?.let { s ->
            try { s.disconnect() } catch (_: Throwable) {}
        }
    }
}

internal suspend fun execOneShot(
    context: Context,
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    command: String,
    timeoutMs: Int,
    sessionRef: AtomicReference<Session?>,
    stdin: String? = null,
): JsonObject {
    val outcome = probeReachability(context, host, port)
    if (outcome.winningNetwork == null && outcome.failures.isNotEmpty()) {
        return unreachableEnvelope(host, port, outcome)
    }
    return runInterruptible(Dispatchers.IO) {
        val jsch = newJSch(context)
        val handshakeStart = System.currentTimeMillis()
        val session = try {
            openSshSession(jsch, host, port, user, auth, timeoutMs, network = outcome.winningNetwork)
        } catch (e: Throwable) {
            Log.w(TAG_SSH, "ssh handshake failed in ${System.currentTimeMillis() - handshakeStart}ms", e)
            return@runInterruptible wrapConnectError(host, e)
        }
        sessionRef.set(session)
        Log.i(TAG_SSH, "ssh session up via ${outcome.winningLabel ?: "default"} in ${System.currentTimeMillis() - handshakeStart}ms")
        try {
            runOnSession(session, command, timeoutMs, stdin)
        } catch (e: Throwable) {
            buildJsonObject { put("error", "exec failed: ${e.message ?: "unknown"}") }
        } finally {
            sessionRef.set(null)
            try { session.disconnect() } catch (_: Throwable) {}
        }
    }
}

internal fun wrapConnectError(host: String, e: Throwable): JsonObject {
    val msg = e.message.orEmpty()
    val isHostKeyChange = msg.contains("HostKey", ignoreCase = true) ||
            msg.contains("host key", ignoreCase = true) ||
            msg.contains("identification has changed", ignoreCase = true) ||
            msg.contains("REMOTE HOST IDENTIFICATION", ignoreCase = true)
    if (isHostKeyChange) {
        return buildJsonObject {
            put("error", "host_key_changed")
            put("host", host)
            put("recovery", "Stored key for $host doesn't match what the server presented. " +
                    "If the user trusts this host, call ssh_forget_host_key with " +
                    "host=\"$host\" then retry. Do NOT forget the key without explicit " +
                    "user confirmation — a changed key can also indicate an attacker.")
            put("raw", msg)
        }
    }
    val isAuthFailure = msg.contains("Auth fail", ignoreCase = true) ||
            msg.contains("auth cancel", ignoreCase = true) ||
            msg.contains("USERAUTH fail", ignoreCase = true) ||
            msg.contains("Authentication failed", ignoreCase = true) ||
            msg.contains("Permission denied (publickey", ignoreCase = true) ||
            msg.contains("Permission denied (password", ignoreCase = true)
    if (isAuthFailure) {
        return buildJsonObject {
            put("error", "auth_failed")
            put("host", host)
            put("recovery", "Credentials rejected by $host. Verify the password / private_key / " +
                    "username with the user before retrying.")
            put("raw", msg)
        }
    }
    return buildJsonObject {
        put("error", "connect_failed")
        put("host", host)
        put("reason", msg.ifBlank { e::class.simpleName ?: "unknown" })
    }
}

internal fun forgetHostKey(context: Context, host: String): Int {
    val jsch = newJSch(context)
    val repo = jsch.hostKeyRepository
    val before = repo.hostKey?.count { it.host == host } ?: 0
    if (before == 0) return 0
    repo.remove(host, null)
    try {
        knownHostsFile(context).bufferedWriter().use { w ->
            repo.hostKey?.forEach { hk ->
                val marker = hk.marker?.takeIf { it.isNotEmpty() }
                val line = buildString {
                    if (marker != null) append('@').append(marker).append(' ')
                    append(hk.host).append(' ').append(hk.type).append(' ').append(hk.key)
                }
                w.write(line)
                w.newLine()
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG_SSH, "forgetHostKey: failed to persist known_hosts after remove", e)
    }
    return before
}

fun sshExecTool(context: Context): Tool = Tool(
    name = "ssh_exec",
    description = "Connect to a remote host via SSH and run a single shell command. " +
            "Returns stdout, stderr, and exit code. For hosts you'll use repeatedly, " +
            "prefer save_ssh_host + ssh_exec_saved instead.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", buildJsonObject { put("type", "string"); put("description", "Hostname or IP address") })
                put("port", buildJsonObject { put("type", "integer"); put("description", "SSH port, default 22") })
                put("user", buildJsonObject { put("type", "string"); put("description", "SSH username") })
                put("password", buildJsonObject { put("type", "string"); put("description", "Password (use only if no private_key)") })
                put("private_key", buildJsonObject { put("type", "string"); put("description", "Full PEM/OpenSSH private key contents") })
                put("passphrase", buildJsonObject { put("type", "string"); put("description", "Optional passphrase for the private key") })
                put("command", buildJsonObject { put("type", "string"); put("description", "Shell command to run on the remote host") })
                put("stdin", buildJsonObject { put("type", "string"); put("description", "Optional data piped to stdin") })
                put("background", buildJsonObject { put("type", "boolean"); put("description", "If true, launch detached and return PID. Default false.") })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Total timeout, default 30, max 300") })
            },
            required = listOf("host", "user", "command")
        )
    },
    execute = {
        val p = it.jsonObject
        val host = p["host"]?.jsonPrimitive?.contentOrNull ?: error("host is required")
        val user = p["user"]?.jsonPrimitive?.contentOrNull ?: error("user is required")
        val command = p["command"]?.jsonPrimitive?.contentOrNull ?: error("command is required")
        val port = p["port"]?.jsonPrimitive?.intOrNull ?: 22
        val stdin = p["stdin"]?.jsonPrimitive?.contentOrNull
        val background = p["background"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val auth = SshAuth(
            password = p["password"]?.jsonPrimitive?.contentOrNull,
            privateKey = p["private_key"]?.jsonPrimitive?.contentOrNull,
            passphrase = p["passphrase"]?.jsonPrimitive?.contentOrNull,
        )
        val timeoutSec = (p["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 300)
        if (!auth.isUsable()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "must provide password or private_key") }.toString()
            ))
        }
        if (background && stdin != null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "stdin and background are mutually exclusive") }.toString()
            ))
        }
        val effectiveCommand = if (background) wrapDetachedCommand(command) else command
        val payload = runCancellableSshOp(timeoutSec * 1000L) { sessionRef ->
            execOneShot(context, host, port, user, auth, effectiveCommand, timeoutSec * 1000, sessionRef, stdin)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
