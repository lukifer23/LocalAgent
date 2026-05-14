package com.localagent.bridge

import android.content.Context
import android.util.Log
import com.localagent.runtime.DeviceIpv4
import com.localagent.runtime.HermesPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

class BridgeSocketServer(
    private val appContext: Context,
    private val port: Int = HermesPaths.BRIDGE_PORT,
    private val bridgeAuthToken: () -> String,
    private val wifiOnlyBind: () -> Boolean,
) {
    private val tag = "BridgeSocketServer"

    private val events =
        MutableSharedFlow<BridgeEvent>(
            replay = 0,
            extraBufferCapacity = 2048,
        )

    private val diagEvents =
        MutableSharedFlow<BridgeDiagEvent>(
            replay = 24,
            extraBufferCapacity = 32,
        )

    private val authFailuresByIp = ConcurrentHashMap<String, MutableList<Long>>()
    /** Sockets currently in [handleClient]; closed on [disconnectAllClients] or normal teardown. */
    private val activeClientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private var acceptJob: Job? = null
    private var server: ServerSocket? = null
    private var parentScope: CoroutineScope? = null

    val incoming: SharedFlow<BridgeEvent> = events
    val diagnostics: SharedFlow<BridgeDiagEvent> = diagEvents

    private suspend fun emitDiag(kind: String, message: String) {
        diagEvents.emit(BridgeDiagEvent(kind = kind, message = message))
    }

    fun start(scope: CoroutineScope) {
        parentScope = scope
        if (acceptJob?.isActive == true) {
            return
        }
        acceptJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    if (wifiOnlyBind() && !DeviceIpv4.isActiveTransportWifi(appContext)) {
                        emitDiag("wait_wifi", "Wi‑Fi-only bridge: not on Wi‑Fi — not binding")
                        Log.i(tag, "Bridge idle: waiting for Wi‑Fi (policy)")
                        delay(2000)
                        continue
                    }
                    try {
                        val socket =
                            ServerSocket().apply {
                                reuseAddress = true
                                bind(InetSocketAddress(port))
                            }
                        server = socket
                        Log.i(tag, "Server started on port $port")
                        emitDiag("bind", "Listening on 0.0.0.0:$port")

                        while (isActive) {
                            if (wifiOnlyBind() && !DeviceIpv4.isActiveTransportWifi(appContext)) {
                                emitDiag("wifi_lost", "Wi‑Fi dropped — closing listener")
                                break
                            }
                        val client =
                                try {
                                    socket.accept()
                                } catch (e: Exception) {
                                    if (isActive) {
                                        Log.e(tag, "Accept failed, restarting server in 2s", e)
                                        emitDiag("accept_error", e.message ?: "accept failed")
                                        delay(2000)
                                    }
                                    break
                                }

                            activeClientSockets.add(client)
                            launch {
                                try {
                                    handleClient(client)
                                } finally {
                                    activeClientSockets.remove(client)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(tag, "Server error, retrying in 5s", e)
                            emitDiag("server_error", e.message ?: "error")
                            delay(5000)
                        }
                    } finally {
                        runCatching { server?.close() }
                        server = null
                    }
                }
            }
    }

    private suspend fun CoroutineScope.handleClient(client: java.net.Socket) {
        val remoteAddr = client.remoteSocketAddress.toString()
        val ip =
            client.inetAddress?.hostAddress ?: remoteAddr.trimStart('/').substringBefore(':')
        emitDiag("tcp_connect", remoteAddr)
        Log.i(tag, "Client connected: $remoteAddr")
        try {
            BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8)).use { reader ->
                val writer = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)
                var authed = false
                while (isActive) {
                    val line = reader.readLine() ?: break
                    if (!authed) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue

                        val presented = BridgeWireAuth.parseAuthToken(trimmed)
                        if (presented == null) {
                            Log.w(tag, "Malformed AUTH line from $remoteAddr")
                            emitDiag("auth_malformed", remoteAddr)
                            recordFailedAuth(ip)
                            break
                        }
                        val ok =
                            BridgeWireAuth.tokensEqual(
                                bridgeAuthToken(),
                                presented,
                            )
                        if (!ok) {
                            Log.w(tag, "Auth failed for $remoteAddr")
                            emitDiag("auth_failed", ip)
                            recordFailedAuth(ip)
                            break
                        }
                        pruneFailures(ip)
                        authed = true
                        emitDiag("auth_ok", remoteAddr)
                        Log.i(tag, "Auth success for $remoteAddr")
                        writer.flush()
                        continue
                    }
                    runCatching { BridgeJson.parse(line) }
                        .onSuccess { ev ->
                            val ps = parentScope
                            if (!events.tryEmit(ev)) {
                                ps?.launch(Dispatchers.IO) {
                                    events.emit(ev)
                                }
                            }
                        }
                        .onFailure {
                            Log.e(tag, "Parse error from $remoteAddr: ${it.message}")
                            diagEvents.tryEmit(
                                BridgeDiagEvent(kind = "json_parse", message = it.message?.take(200) ?: "parse error"),
                            )
                        }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Client error ($remoteAddr): ${e.message}")
            emitDiag("client_error", e.message?.take(200) ?: "error")
        } finally {
            runCatching { client.close() }
            emitDiag("disconnect", remoteAddr)
            Log.i(tag, "Client disconnected: $remoteAddr")
        }
    }

    private fun recordFailedAuth(ip: String) {
        val now = System.currentTimeMillis()
        val windowMs = 60_000L
        val maxFails = 15
        val list =
            authFailuresByIp.compute(ip) { _, v ->
                v ?: mutableListOf()
            }!!
        synchronized(list) {
            list.removeAll { now - it > windowMs }
            list.add(now)
            if (list.size >= maxFails) {
                val excess = (list.size - maxFails + 1).coerceAtMost(24)
                Log.w(tag, "Auth rate limit for $ip (${list.size} failures / ${windowMs}ms)")
                diagEvents.tryEmit(BridgeDiagEvent(kind = "auth_rate", message = ip))
                try {
                    Thread.sleep((excess * 400L).coerceAtMost(10_000L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                list.clear()
            }
        }
    }

    private fun pruneFailures(ip: String) {
        authFailuresByIp.remove(ip)
    }

    /** Forcibly close all authenticated (or in-flight) bridge TCP clients; listener keeps running. */
    fun disconnectAllClients(): Int {
        val snap = activeClientSockets.toList()
        for (s in snap) {
            runCatching {
                s.shutdownInput()
                s.shutdownOutput()
                s.close()
            }
        }
        if (snap.isNotEmpty()) {
            Log.i(tag, "disconnectAllClients closed ${snap.size} socket(s)")
        }
        return snap.size
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        acceptJob?.cancel()
        acceptJob = null
        parentScope = null
    }

    fun restart(scope: CoroutineScope) {
        stop()
        start(scope)
    }

    companion object {
        /** Probe bridge with TCP connect + AUTH; server sends no payload — idle read times out on success. */
        fun probeAuthenticated(host: String, port: Int, token: String): Boolean {
            return try {
                java.net.Socket().use { s ->
                    s.tcpNoDelay = true
                    s.soTimeout = 6000
                    s.connect(InetSocketAddress(host, port), 4000)
                    val out = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                    out.write("${BridgeWireAuth.AUTH_PREFIX}$token\n")
                    out.flush()
                    try {
                        s.getInputStream().read().let { b ->
                            when (b) {
                                -1 -> false
                                else -> false
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        true
                    } catch (_: SocketException) {
                        false
                    }
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
