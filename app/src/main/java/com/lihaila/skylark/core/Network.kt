package com.lihaila.skylark.core

import com.lihaila.skylark.data.AppStore
import com.lihaila.skylark.data.Node
import com.lihaila.skylark.data.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

object Network {

    /** TCP handshake time to the server in ms, or -1 if unreachable. */
    suspend fun tcpPing(node: Node, timeoutMs: Int = 3000): Int = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(node.server, node.port), timeoutMs)
                ((System.nanoTime() - start) / 1_000_000).toInt()
            }
        }.getOrDefault(-1)
    }

    suspend fun pingAll(nodes: List<Node>, onResult: (String, Int) -> Unit) = coroutineScope {
        val limit = Semaphore(16)
        nodes.map { n -> async { limit.withPermit { onResult(n.id, tcpPing(n)) } } }.awaitAll()
    }

    suspend fun fetchSubscription(url: String): List<Node> = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "Skylark/1.0 (sing-box 1.14; v2ray links)")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().readText()
            LinkParser.parseText(body).ifEmpty { error("No servers found in subscription") }
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads [sub] and replaces its nodes. Returns the node count. */
    suspend fun updateSubscription(sub: Subscription): Int {
        val nodes = fetchSubscription(sub.url)
        AppStore.replaceSubNodes(sub.copy(updatedAt = System.currentTimeMillis()), nodes)
        return nodes.size
    }
}
