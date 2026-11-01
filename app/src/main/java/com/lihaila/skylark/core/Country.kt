package com.lihaila.skylark.core

import android.content.Context
import android.telephony.TelephonyManager
import com.lihaila.skylark.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** The user's home country/region, used so Smart mode sends local sites direct. */
object Country {

    /** Best guess from SIM, then mobile network, then the phone's region setting. Upper-case ISO code. */
    fun detect(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val candidates = listOf(
            runCatching { tm?.simCountryIso }.getOrNull(),
            runCatching { tm?.networkCountryIso }.getOrNull(),
            Locale.getDefault().country,
        )
        return candidates.firstOrNull { !it.isNullOrBlank() && it.length == 2 }?.uppercase() ?: ""
    }

    /** The country actually used: the user's choice, or the detected one. */
    fun effective(context: Context, s: Settings): String = s.homeCountry.ifBlank { detect(context) }

    fun displayName(code: String, locale: Locale = Locale.getDefault()): String =
        if (code.isBlank()) "?" else Locale("", code).getDisplayCountry(locale).ifBlank { code }

    /** Country-code top-level domain. Almost always the ISO code, with a few exceptions. */
    fun ccTld(code: String): String = when (code.uppercase()) {
        "GB" -> "uk"
        else -> code.lowercase()
    }

    fun geoipFile(context: Context, code: String) = File(ConfigBuilder.ruleDir(context), "geoip-${code.lowercase()}.srs")

    /**
     * Downloads the country's IP list once (a few KB to a few hundred KB). If it can't be fetched,
     * Smart mode still works from the country's domain suffix alone.
     */
    suspend fun ensureGeoip(context: Context, code: String): Boolean = withContext(Dispatchers.IO) {
        if (code.isBlank()) return@withContext false
        val file = geoipFile(context, code)
        if (file.exists() && file.length() > 0) return@withContext true
        val name = "geoip-${code.lowercase()}.srs"
        // GitHub first, then CDN mirrors for networks where GitHub is blocked or slow.
        val urls = listOf(
            "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/$name",
            "https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/$name",
            "https://fastly.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/$name",
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/$name",
        )
        val ok = urls.any { url -> download(url, file) }
        android.util.Log.i("Skylark", "geoip $code download ${if (ok) "ok" else "failed"}")
        ok
    }

    private fun download(url: String, file: File): Boolean =
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            try {
                if (conn.responseCode != 200) return@runCatching false
                val bytes = conn.inputStream.readBytes()
                // Every sing-box binary rule set starts with "SRS".
                if (bytes.size < 4 || bytes[0] != 'S'.code.toByte() || bytes[1] != 'R'.code.toByte() || bytes[2] != 'S'.code.toByte()) {
                    return@runCatching false
                }
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(file)
            } finally {
                conn.disconnect()
            }
        }.onFailure { android.util.Log.i("Skylark", "geoip fetch $url: $it") }.getOrDefault(false)
}
