package com.lihaila.skylark.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A proxy server. [outbound] is a sing-box outbound object (without "tag"). */
data class Node(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val outbound: String,
    val subId: String? = null,
) {
    val type: String get() = runCatching { JSONObject(outbound).getString("type") }.getOrDefault("?")
    val server: String get() = runCatching { JSONObject(outbound).optString("server") }.getOrDefault("")
    val port: Int get() = runCatching { JSONObject(outbound).optInt("server_port") }.getOrDefault(0)

    fun toJson() = JSONObject().put("id", id).put("name", name).put("outbound", outbound).put("subId", subId)

    companion object {
        fun fromJson(o: JSONObject) = Node(
            id = o.getString("id"),
            name = o.getString("name"),
            outbound = o.getString("outbound"),
            subId = o.optString("subId").takeIf { it.isNotEmpty() && it != "null" },
        )
    }
}

data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val updatedAt: Long = 0,
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("url", url).put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject) = Subscription(o.getString("id"), o.getString("name"), o.getString("url"), o.optLong("updatedAt"))
    }
}

enum class Mode { RULE, GLOBAL, DIRECT }

enum class RuleType(val label: String) {
    DOMAIN_SUFFIX("DOMAIN-SUFFIX"),
    DOMAIN("DOMAIN"),
    DOMAIN_KEYWORD("DOMAIN-KEYWORD"),
    IP_CIDR("IP-CIDR"),
    GEOIP("GEOIP"),
}

enum class RuleAction { PROXY, DIRECT, REJECT }

data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val type: RuleType,
    val value: String,
    val action: RuleAction,
) {
    fun toJson() = JSONObject().put("id", id).put("type", type.name).put("value", value).put("action", action.name)

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            o.getString("id"), RuleType.valueOf(o.getString("type")), o.getString("value"), RuleAction.valueOf(o.getString("action")),
        )
    }
}

enum class PerAppMode { OFF, BYPASS, ONLY }

data class Settings(
    val bypassChina: Boolean = true,
    val blockAds: Boolean = true,
    val ipv6: Boolean = false,
    val perAppMode: PerAppMode = PerAppMode.OFF,
    val perAppPackages: Set<String> = emptySet(),
    val remoteDns: String = "1.1.1.1",
    val directDns: String = "223.5.5.5",
) {
    fun toJson() = JSONObject()
        .put("bypassChina", bypassChina).put("blockAds", blockAds).put("ipv6", ipv6)
        .put("perAppMode", perAppMode.name).put("perAppPackages", JSONArray(perAppPackages.toList()))
        .put("remoteDns", remoteDns).put("directDns", directDns)

    companion object {
        fun fromJson(o: JSONObject?): Settings {
            if (o == null) return Settings()
            val pkgs = o.optJSONArray("perAppPackages")
            return Settings(
                bypassChina = o.optBoolean("bypassChina", true),
                blockAds = o.optBoolean("blockAds", true),
                ipv6 = o.optBoolean("ipv6", false),
                perAppMode = runCatching { PerAppMode.valueOf(o.optString("perAppMode")) }.getOrDefault(PerAppMode.OFF),
                perAppPackages = if (pkgs == null) emptySet() else (0 until pkgs.length()).map { pkgs.getString(it) }.toSet(),
                remoteDns = o.optString("remoteDns", "1.1.1.1"),
                directDns = o.optString("directDns", "223.5.5.5"),
            )
        }
    }
}

data class AppState(
    val nodes: List<Node> = emptyList(),
    val subs: List<Subscription> = emptyList(),
    val selectedId: String? = null,
    val mode: Mode = Mode.RULE,
    val rules: List<Rule> = emptyList(),
    val settings: Settings = Settings(),
) {
    val selected: Node? get() = nodes.firstOrNull { it.id == selectedId } ?: nodes.firstOrNull()
}
