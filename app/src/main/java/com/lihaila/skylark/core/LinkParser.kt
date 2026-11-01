package com.lihaila.skylark.core

import android.util.Base64
import com.lihaila.skylark.data.Node
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.net.URI
import java.net.URLDecoder

/**
 * Turns share links (ss, vmess, vless, trojan, hysteria2, tuic, anytls, socks) and
 * subscription bodies (base64 link lists, Clash YAML, sing-box JSON) into nodes.
 */
object LinkParser {

    private val schemes = listOf("ss", "vmess", "vless", "trojan", "hysteria2", "hy2", "tuic", "anytls", "socks", "socks5")

    fun isShareLink(text: String): Boolean = schemes.any { text.trim().startsWith("$it://", ignoreCase = true) }

    /** Parses any pasted text: one link, many links, or a whole subscription body. */
    fun parseText(raw: String): List<Node> {
        val text = raw.trim().removePrefix("﻿")
        if (text.isEmpty()) return emptyList()

        if (text.startsWith("{")) parseSingBox(text)?.let { return it }
        if (Regex("(?m)^proxies:").containsMatchIn(text)) return parseClash(text)

        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.any { isShareLink(it) }) return lines.mapNotNull { runCatching { parseLink(it) }.getOrNull() }

        // Probably a base64-encoded link list.
        val decoded = decodeBase64(text.replace("\\s".toRegex(), "")) ?: return emptyList()
        if (decoded == text) return emptyList()
        return parseText(decoded)
    }

    fun parseLink(link: String): Node? {
        val scheme = link.substringBefore("://").lowercase()
        return when (scheme) {
            "ss" -> parseSs(link)
            "vmess" -> parseVmess(link)
            "vless" -> parseVlessLike(link, "vless")
            "trojan" -> parseVlessLike(link, "trojan")
            "hysteria2", "hy2" -> parseHy2(link)
            "tuic" -> parseTuic(link)
            "anytls" -> parseAnyTls(link)
            "socks", "socks5" -> parseSocks(link)
            else -> null
        }
    }

    // ---------- individual schemes ----------

    private fun parseSs(link: String): Node? {
        var body = link.removePrefix("ss://")
        val name = body.substringAfter('#', "").let(::decode)
        body = body.substringBefore('#')
        val query = body.substringAfter('?', "")
        body = body.substringBefore('?').trimEnd('/')

        val method: String
        val password: String
        val host: String
        val port: Int
        if (body.contains('@')) {
            val userInfo = body.substringBeforeLast('@')
            val hostPort = body.substringAfterLast('@')
            val creds = decodeBase64(userInfo)?.takeIf { it.contains(':') } ?: decode(userInfo)
            method = creds.substringBefore(':')
            password = creds.substringAfter(':')
            host = hostPort.substringBeforeLast(':').trim('[', ']')
            port = hostPort.substringAfterLast(':').toInt()
        } else {
            val plain = decodeBase64(body) ?: return null
            method = plain.substringBefore(':')
            password = plain.substringAfter(':').substringBeforeLast('@')
            val hostPort = plain.substringAfterLast('@')
            host = hostPort.substringBeforeLast(':').trim('[', ']')
            port = hostPort.substringAfterLast(':').toInt()
        }
        val out = JSONObject().put("type", "shadowsocks").put("server", host).put("server_port", port)
            .put("method", method).put("password", password)
        val plugin = params(query)["plugin"]
        if (!plugin.isNullOrEmpty()) {
            val pluginName = plugin.substringBefore(';')
            out.put("plugin", if (pluginName == "simple-obfs") "obfs-local" else pluginName)
            out.put("plugin_opts", plugin.substringAfter(';', ""))
        }
        return Node(name = name.ifEmpty { "$host:$port" }, outbound = out.toString())
    }

    private fun parseVmess(link: String): Node? {
        val json = JSONObject(decodeBase64(link.removePrefix("vmess://").substringBefore('#')) ?: return null)
        val host = json.getString("add")
        val port = json.get("port").toString().toInt()
        val out = JSONObject().put("type", "vmess").put("server", host).put("server_port", port)
            .put("uuid", json.getString("id"))
            .put("security", json.optString("scy").ifEmpty { "auto" })
            .put("alter_id", json.opt("aid")?.toString()?.toIntOrNull() ?: 0)
        val tls = json.optString("tls")
        if (tls == "tls") {
            out.put("tls", tlsObject(
                sni = json.optString("sni").ifEmpty { json.optString("host") }.ifEmpty { host },
                insecure = false, alpn = json.optString("alpn"), fp = json.optString("fp"),
            ))
        }
        transport(json.optString("net"), json.optString("path"), json.optString("host"), json.optString("path"), json.optString("type"))
            ?.let { out.put("transport", it) }
        return Node(name = json.optString("ps").ifEmpty { "$host:$port" }, outbound = out.toString())
    }

    private fun parseVlessLike(link: String, type: String): Node {
        val uri = URI(link.substringBefore('#'))
        val p = params(uri.rawQuery ?: "")
        val name = decode(link.substringAfter('#', ""))
        val host = uri.host.trim('[', ']')
        val port = if (uri.port > 0) uri.port else 443
        val secret = decode(uri.rawUserInfo ?: "")
        val out = JSONObject().put("type", type).put("server", host).put("server_port", port)
        if (type == "vless") {
            out.put("uuid", secret)
            p["flow"]?.takeIf { it.isNotEmpty() }?.let { out.put("flow", it) }
        } else {
            out.put("password", secret)
        }
        val security = p["security"] ?: if (type == "trojan") "tls" else "none"
        if (security == "tls" || security == "reality" || security == "xtls") {
            val tls = tlsObject(
                sni = p["sni"] ?: p["peer"] ?: p["host"] ?: host,
                insecure = p["allowInsecure"] == "1" || p["insecure"] == "1" || p["allowInsecure"] == "true",
                alpn = p["alpn"].orEmpty(), fp = p["fp"].orEmpty(),
            )
            if (security == "reality") {
                tls.put("reality", JSONObject().put("enabled", true).put("public_key", realityKey(p["pbk"].orEmpty())).put("short_id", p["sid"].orEmpty()))
                if (!tls.has("utls")) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
            }
            out.put("tls", tls)
        }
        transport(p["type"].orEmpty(), p["path"].orEmpty(), p["host"].orEmpty(), p["serviceName"].orEmpty(), p["headerType"].orEmpty())
            ?.let { out.put("transport", it) }
        return Node(name = name.ifEmpty { "$host:$port" }, outbound = out.toString())
    }

    private fun parseHy2(link: String): Node {
        val normalized = link.replaceFirst(Regex("^hy2://", RegexOption.IGNORE_CASE), "hysteria2://")
        val uri = URI(normalized.substringBefore('#'))
        val p = params(uri.rawQuery ?: "")
        val host = uri.host.trim('[', ']')
        // Port hopping ("443,20000-30000") isn't a valid URI port; fall back to the first number.
        val port = if (uri.port > 0) uri.port else Regex(":(\\d+)").find(uri.rawAuthority ?: "")?.groupValues?.get(1)?.toInt() ?: 443
        val out = JSONObject().put("type", "hysteria2").put("server", host).put("server_port", port)
            .put("password", decode(uri.rawUserInfo ?: ""))
            .put("tls", tlsObject(p["sni"] ?: host, p["insecure"] == "1", p["alpn"].orEmpty(), ""))
        if (p["obfs"] == "salamander") {
            out.put("obfs", JSONObject().put("type", "salamander").put("password", p["obfs-password"].orEmpty()))
        }
        return Node(name = decode(link.substringAfter('#', "")).ifEmpty { "$host:$port" }, outbound = out.toString())
    }

    private fun parseTuic(link: String): Node {
        val uri = URI(link.substringBefore('#'))
        val p = params(uri.rawQuery ?: "")
        val host = uri.host.trim('[', ']')
        val user = decode(uri.rawUserInfo ?: "")
        val out = JSONObject().put("type", "tuic").put("server", host).put("server_port", uri.port)
            .put("uuid", user.substringBefore(':')).put("password", user.substringAfter(':', ""))
            .put("congestion_control", p["congestion_control"] ?: "bbr")
            .put("udp_relay_mode", p["udp_relay_mode"] ?: "native")
            .put("tls", tlsObject(p["sni"] ?: host, p["allow_insecure"] == "1" || p["insecure"] == "1", p["alpn"] ?: "h3", ""))
        return Node(name = decode(link.substringAfter('#', "")).ifEmpty { "$host:${uri.port}" }, outbound = out.toString())
    }

    private fun parseAnyTls(link: String): Node {
        val uri = URI(link.substringBefore('#'))
        val p = params(uri.rawQuery ?: "")
        val host = uri.host.trim('[', ']')
        val out = JSONObject().put("type", "anytls").put("server", host).put("server_port", uri.port)
            .put("password", decode(uri.rawUserInfo ?: ""))
            .put("tls", tlsObject(p["sni"] ?: host, p["insecure"] == "1", p["alpn"].orEmpty(), p["fp"].orEmpty()))
        return Node(name = decode(link.substringAfter('#', "")).ifEmpty { "$host:${uri.port}" }, outbound = out.toString())
    }

    private fun parseSocks(link: String): Node {
        val uri = URI(link.substringBefore('#').replaceFirst("socks5://", "socks://"))
        val out = JSONObject().put("type", "socks").put("server", uri.host).put("server_port", uri.port)
        uri.rawUserInfo?.let { info ->
            val creds = decodeBase64(info)?.takeIf { it.contains(':') } ?: decode(info)
            out.put("username", creds.substringBefore(':')).put("password", creds.substringAfter(':', ""))
        }
        return Node(name = decode(link.substringAfter('#', "")).ifEmpty { "${uri.host}:${uri.port}" }, outbound = out.toString())
    }

    // ---------- subscription formats ----------

    private val supportedOutbounds = setOf("shadowsocks", "vmess", "vless", "trojan", "hysteria2", "tuic", "anytls", "socks", "http", "hysteria", "shadowtls", "ssh")

    private fun parseSingBox(text: String): List<Node>? = runCatching {
        val arr = JSONObject(text).getJSONArray("outbounds")
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("type") in supportedOutbounds }
            .map { o ->
                val name = o.optString("tag").ifEmpty { o.optString("server") }
                o.remove("tag"); o.remove("detour")
                Node(name = name, outbound = o.toString())
            }
    }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun parseClash(text: String): List<Node> {
        val root = Yaml(LoaderOptions()).load<Any>(text) as? Map<String, Any?> ?: return emptyList()
        val proxies = root["proxies"] as? List<Map<String, Any?>> ?: return emptyList()
        return proxies.mapNotNull { runCatching { clashProxy(it) }.getOrNull() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun clashProxy(m: Map<String, Any?>): Node? {
        fun s(k: String) = m[k]?.toString().orEmpty()
        fun b(k: String) = m[k] == true || m[k] == "true"
        val server = s("server")
        val port = s("port").substringBefore(',').substringBefore('-').toInt()
        val sni = s("servername").ifEmpty { s("sni") }.ifEmpty { server }
        val alpn = (m["alpn"] as? List<*>)?.joinToString(",").orEmpty()
        val fp = s("client-fingerprint")
        val out = JSONObject().put("server", server).put("server_port", port)
        when (s("type")) {
            "ss" -> {
                out.put("type", "shadowsocks").put("method", s("cipher")).put("password", s("password"))
                val opts = m["plugin-opts"] as? Map<String, Any?>
                when (s("plugin")) {
                    "obfs" -> out.put("plugin", "obfs-local").put("plugin_opts", "obfs=${opts?.get("mode")};obfs-host=${opts?.get("host") ?: ""}")
                    "v2ray-plugin" -> out.put("plugin", "v2ray-plugin").put("plugin_opts",
                        listOfNotNull("mode=websocket", if (opts?.get("tls") == true) "tls" else null,
                            opts?.get("host")?.let { "host=$it" }, opts?.get("path")?.let { "path=$it" }).joinToString(";"))
                }
            }
            "vmess" -> {
                out.put("type", "vmess").put("uuid", s("uuid")).put("alter_id", s("alterId").toIntOrNull() ?: 0)
                    .put("security", s("cipher").ifEmpty { "auto" })
                if (b("tls")) out.put("tls", tlsObject(sni, b("skip-cert-verify"), alpn, fp))
                clashTransport(m)?.let { out.put("transport", it) }
            }
            "vless" -> {
                out.put("type", "vless").put("uuid", s("uuid"))
                s("flow").takeIf { it.isNotEmpty() }?.let { out.put("flow", it) }
                if (b("tls") || m["reality-opts"] != null) {
                    val tls = tlsObject(sni, b("skip-cert-verify"), alpn, fp)
                    (m["reality-opts"] as? Map<String, Any?>)?.let { r ->
                        tls.put("reality", JSONObject().put("enabled", true).put("public_key", realityKey(r["public-key"]?.toString().orEmpty()))
                            .put("short_id", r["short-id"]?.toString().orEmpty()))
                        if (!tls.has("utls")) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
                    }
                    out.put("tls", tls)
                }
                clashTransport(m)?.let { out.put("transport", it) }
            }
            "trojan" -> {
                out.put("type", "trojan").put("password", s("password")).put("tls", tlsObject(sni, b("skip-cert-verify"), alpn, fp))
                clashTransport(m)?.let { out.put("transport", it) }
            }
            "hysteria2" -> {
                out.put("type", "hysteria2").put("password", s("password").ifEmpty { s("auth") })
                    .put("tls", tlsObject(sni, b("skip-cert-verify"), alpn, ""))
                if (s("obfs") == "salamander") out.put("obfs", JSONObject().put("type", "salamander").put("password", s("obfs-password")))
            }
            "tuic" -> out.put("type", "tuic").put("uuid", s("uuid")).put("password", s("password"))
                .put("congestion_control", s("congestion-controller").ifEmpty { "bbr" })
                .put("udp_relay_mode", s("udp-relay-mode").ifEmpty { "native" })
                .put("tls", tlsObject(sni, b("skip-cert-verify"), alpn.ifEmpty { "h3" }, ""))
            "anytls" -> out.put("type", "anytls").put("password", s("password")).put("tls", tlsObject(sni, b("skip-cert-verify"), alpn, fp))
            "socks5" -> out.put("type", "socks").apply {
                if (s("username").isNotEmpty()) put("username", s("username")).put("password", s("password"))
            }
            "http" -> out.put("type", "http").apply {
                if (s("username").isNotEmpty()) put("username", s("username")).put("password", s("password"))
                if (b("tls")) put("tls", tlsObject(sni, b("skip-cert-verify"), "", ""))
            }
            else -> return null
        }
        return Node(name = s("name").ifEmpty { "$server:$port" }, outbound = out.toString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun clashTransport(m: Map<String, Any?>): JSONObject? = when (m["network"]?.toString()) {
        "ws" -> {
            val o = m["ws-opts"] as? Map<String, Any?>
            val host = (o?.get("headers") as? Map<String, Any?>)?.get("Host")?.toString().orEmpty()
            transport("ws", o?.get("path")?.toString().orEmpty(), host, "", "")
        }
        "grpc" -> transport("grpc", "", "", (m["grpc-opts"] as? Map<String, Any?>)?.get("grpc-service-name")?.toString().orEmpty(), "")
        "h2", "http" -> {
            val o = (m["h2-opts"] ?: m["http-opts"]) as? Map<String, Any?>
            val host = when (val h = o?.get("host")) { is List<*> -> h.firstOrNull()?.toString().orEmpty(); null -> ""; else -> h.toString() }
            val path = when (val p = o?.get("path")) { is List<*> -> p.firstOrNull()?.toString().orEmpty(); null -> ""; else -> p.toString() }
            transport("http", path, host, "", "")
        }
        else -> null
    }

    // ---------- helpers ----------

    /** sing-box wants unpadded URL-safe base64; many links carry padding or the standard alphabet. */
    private fun realityKey(k: String) = k.trim().trimEnd('=').replace('+', '-').replace('/', '_')

    private fun tlsObject(sni: String, insecure: Boolean, alpn: String, fp: String): JSONObject {
        val tls = JSONObject().put("enabled", true).put("server_name", sni)
        if (insecure) tls.put("insecure", true)
        if (alpn.isNotEmpty()) tls.put("alpn", JSONArray(alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
        if (fp.isNotEmpty() && fp != "none") tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        return tls
    }

    /** sing-box transport from v2ray-style fields. "tcp" with no http header means no transport. */
    private fun transport(net: String, path: String, host: String, serviceName: String, headerType: String): JSONObject? {
        val o = JSONObject()
        when (net.lowercase()) {
            "ws" -> {
                o.put("type", "ws")
                val cleanPath = path.ifEmpty { "/" }
                val ed = Regex("[?&]ed=(\\d+)").find(cleanPath)?.groupValues?.get(1)
                o.put("path", cleanPath.replace(Regex("[?&]ed=\\d+"), ""))
                if (ed != null) o.put("max_early_data", ed.toInt()).put("early_data_header_name", "Sec-WebSocket-Protocol")
                if (host.isNotEmpty()) o.put("headers", JSONObject().put("Host", host))
            }
            "grpc" -> o.put("type", "grpc").put("service_name", serviceName.ifEmpty { path })
            "h2", "http" -> {
                o.put("type", "http")
                if (path.isNotEmpty()) o.put("path", path)
                if (host.isNotEmpty()) o.put("host", JSONArray(host.split(',')))
            }
            "httpupgrade" -> {
                o.put("type", "httpupgrade").put("path", path.ifEmpty { "/" })
                if (host.isNotEmpty()) o.put("host", host)
            }
            "tcp", "" -> {
                if (headerType != "http") return null
                o.put("type", "http")
                if (path.isNotEmpty()) o.put("path", path)
                if (host.isNotEmpty()) o.put("host", JSONArray(host.split(',')))
            }
            else -> return null
        }
        return o
    }

    private fun params(query: String): Map<String, String> =
        query.split('&').filter { it.contains('=') }.associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=')) }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    fun decodeBase64(s: String): String? {
        val clean = s.trim().replace('-', '+').replace('_', '/').replace("\\s".toRegex(), "")
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8) }
            .getOrNull()?.takeIf { decoded -> decoded.isNotEmpty() && decoded.none { it == '�' } }
    }
}
