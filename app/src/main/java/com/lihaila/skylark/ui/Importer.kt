package com.lihaila.skylark.ui

import com.lihaila.skylark.core.LinkParser
import com.lihaila.skylark.core.Network
import com.lihaila.skylark.data.AppStore
import com.lihaila.skylark.data.Subscription
import java.net.URI

sealed class ImportResult {
    data class Nodes(val count: Int) : ImportResult()
    data class Sub(val count: Int) : ImportResult()
    data class Failed(val message: String) : ImportResult()
    data object Nothing : ImportResult()
}

/** One entry point for anything the user pastes, scans or shares into the app. */
object Importer {

    suspend fun import(raw: String?): ImportResult {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ImportResult.Nothing

        // skylark://install-config?url=... style deep link → subscription
        if (text.startsWith("skylark://", true)) {
            val url = Regex("url=([^&]+)").find(text)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            return if (url != null) addSubscription(url, "") else ImportResult.Nothing
        }

        // A bare http(s) URL is a subscription.
        if (Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(text)) return addSubscription(text, "")

        val nodes = LinkParser.parseText(text)
        if (nodes.isEmpty()) return ImportResult.Nothing
        AppStore.addNodes(nodes)
        return ImportResult.Nodes(nodes.size)
    }

    suspend fun addSubscription(url: String, name: String): ImportResult = runCatching {
        val sub = Subscription(name = name.ifBlank { runCatching { URI(url).host }.getOrNull() ?: "Subscription" }, url = url.trim())
        ImportResult.Sub(Network.updateSubscription(sub))
    }.getOrElse { ImportResult.Failed(it.message ?: it.toString()) }

    suspend fun updateAll(): Pair<Int, String?> {
        var ok = 0
        var lastError: String? = null
        for (sub in AppStore.state.value.subs) {
            runCatching { Network.updateSubscription(sub) }
                .onSuccess { ok++ }
                .onFailure { lastError = "${sub.name}: ${it.message}" }
        }
        return ok to lastError
    }
}
