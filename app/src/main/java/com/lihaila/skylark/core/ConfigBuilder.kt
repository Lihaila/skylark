package com.lihaila.skylark.core

import android.content.Context
import com.lihaila.skylark.data.AppState
import com.lihaila.skylark.data.Mode
import com.lihaila.skylark.data.PerAppMode
import com.lihaila.skylark.data.RuleAction
import com.lihaila.skylark.data.RuleType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Builds a complete sing-box config from the app's simple settings. */
object ConfigBuilder {

    private val bundledRuleSets = listOf("geosite-cn", "geoip-cn", "geosite-category-ads-all")

    /** Copies the bundled rule sets out of assets so sing-box can read them as local files. */
    fun ruleDir(context: Context): File {
        val dir = File(context.filesDir, "rules").apply { mkdirs() }
        for (name in bundledRuleSets) {
            val f = File(dir, "$name.srs")
            if (!f.exists()) context.assets.open("rules/$name.srs").use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        return dir
    }

    fun build(context: Context, state: AppState): String {
        val node = state.selected
        val s = state.settings
        val mode = if (node == null) Mode.DIRECT else state.mode
        val ruleDir = ruleDir(context)
        val usingRules = mode == Mode.RULE

        // ---------- outbounds ----------
        val outbounds = JSONArray()
        if (node != null) outbounds.put(JSONObject(node.outbound).put("tag", "proxy"))
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        // ---------- rule sets ----------
        val ruleSets = JSONArray()
        fun local(tag: String) = ruleSets.put(
            JSONObject().put("type", "local").put("tag", tag).put("format", "binary").put("path", File(ruleDir, "$tag.srs").absolutePath)
        )
        val customGeoip = state.rules.filter { it.type == RuleType.GEOIP && !it.value.equals("LAN", true) }
            .map { it.value.lowercase() }.distinct()
        if (usingRules) {
            if (s.bypassChina || customGeoip.contains("cn")) { local("geosite-cn"); local("geoip-cn") }
            if (s.blockAds) local("geosite-category-ads-all")
            for (code in customGeoip) {
                if (code == "cn") continue
                ruleSets.put(
                    JSONObject().put("type", "remote").put("tag", "geoip-$code").put("format", "binary")
                        .put("url", "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-$code.srs")
                        .put("download_detour", if (node != null) "proxy" else "direct")
                )
            }
        }

        // ---------- route ----------
        val rules = JSONArray()
        rules.put(JSONObject().put("action", "sniff"))
        rules.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        rules.put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))

        val dnsRules = JSONArray()
        if (usingRules) {
            for (r in state.rules) {
                val rule = when (r.type) {
                    RuleType.DOMAIN_SUFFIX -> JSONObject().put("domain_suffix", JSONArray(listOf(r.value)))
                    RuleType.DOMAIN -> JSONObject().put("domain", JSONArray(listOf(r.value)))
                    RuleType.DOMAIN_KEYWORD -> JSONObject().put("domain_keyword", JSONArray(listOf(r.value)))
                    RuleType.IP_CIDR -> JSONObject().put("ip_cidr", JSONArray(listOf(r.value)))
                    RuleType.GEOIP -> if (r.value.equals("LAN", true)) JSONObject().put("ip_is_private", true)
                    else JSONObject().put("rule_set", "geoip-${r.value.lowercase()}")
                }
                when (r.action) {
                    RuleAction.PROXY -> rule.put("outbound", if (node != null) "proxy" else "direct")
                    RuleAction.DIRECT -> rule.put("outbound", "direct")
                    RuleAction.REJECT -> rule.put("action", "reject")
                }
                rules.put(rule)

                // Domains sent direct should also resolve with the local DNS so CDNs pick a nearby server.
                if (r.action == RuleAction.DIRECT && r.type != RuleType.IP_CIDR && r.type != RuleType.GEOIP) {
                    dnsRules.put(JSONObject(rule.toString()).apply { remove("outbound"); put("server", "dns-direct") })
                }
            }
            if (s.blockAds) {
                rules.put(JSONObject().put("rule_set", "geosite-category-ads-all").put("action", "reject"))
                dnsRules.put(JSONObject().put("rule_set", "geosite-category-ads-all").put("action", "reject"))
            }
            if (s.bypassChina) {
                rules.put(JSONObject().put("rule_set", JSONArray(listOf("geosite-cn", "geoip-cn"))).put("outbound", "direct"))
                dnsRules.put(JSONObject().put("rule_set", "geosite-cn").put("server", "dns-direct"))
            }
        }

        val route = JSONObject()
            .put("rules", rules)
            .put("rule_set", ruleSets)
            .put("final", if (mode == Mode.DIRECT) "direct" else "proxy")
            .put("auto_detect_interface", true)
            .put("default_domain_resolver", "dns-direct")

        // ---------- dns ----------
        val dnsServers = JSONArray()
            .put(JSONObject().put("type", "udp").put("tag", "dns-direct").put("server", s.directDns))
        if (node != null) {
            dnsServers.put(JSONObject().put("type", "https").put("tag", "dns-remote").put("server", s.remoteDns).put("detour", "proxy"))
        }
        val dns = JSONObject()
            .put("servers", dnsServers)
            .put("rules", dnsRules)
            .put("final", if (mode == Mode.DIRECT || node == null) "dns-direct" else "dns-remote")
            .put("strategy", if (s.ipv6) "prefer_ipv4" else "ipv4_only")

        // ---------- tun ----------
        val address = JSONArray().put("172.19.0.1/30")
        if (s.ipv6) address.put("fdfe:dcba:9876::1/126")
        val tun = JSONObject()
            .put("type", "tun").put("tag", "tun-in")
            .put("address", address)
            .put("mtu", 9000)
            // The "system" stack's local TCP relay stalls on some phones (Pixel 8a); gVisor is self-contained.
            .put("stack", "gvisor")
            .put("auto_route", true)
            .put("strict_route", true)
        val pkgs = JSONArray(s.perAppPackages.toList())
        when (s.perAppMode) {
            PerAppMode.BYPASS -> if (pkgs.length() > 0) tun.put("exclude_package", pkgs)
            PerAppMode.ONLY -> if (pkgs.length() > 0) tun.put("include_package", pkgs)
            PerAppMode.OFF -> {}
        }

        return JSONObject()
            .put("log", JSONObject().put("level", "info"))
            // Reality rejects clients whose clock is off; don't trust the phone's clock.
            .put("ntp", JSONObject().put("enabled", true).put("server", "ntp.aliyun.com").put("server_port", 123)
                .put("interval", "30m").put("domain_resolver", "dns-direct"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", outbounds)
            .put("route", route)
            .put("experimental", JSONObject().put("cache_file", JSONObject().put("enabled", true)))
            .toString(2)
    }
}
