package ua.inverter.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class ApiException(msg: String) : Exception(msg)

/** Одне поле з відповіді хмари: назва, службовий ключ, значення, одиниця. */
data class Field(val label: String, val key: String, val value: String, val unit: String) {
    val text: String = "$label $key".lowercase()
    val number: Double? = value.replace(",", ".").trim().toDoubleOrNull()
}

data class Reading(
    val soc: Double?,
    val loadW: Double?,
    val loadPct: Double?,
    val batteryV: Double?,
    val fields: List<Field>,
)

/**
 * Клієнт хмари DessMonitor / SmartESS (EyeBond).
 * Підпис запитів: SHA1(salt + SHA1(пароль) + action) для входу,
 * SHA1(salt + secret + token + action) для решти запитів.
 */
class InverterApi(ctx: Context) {
    private val prefs = Prefs(ctx)

    companion object {
        const val BASE = "https://api.dessmonitor.com/public/"
        const val DEFAULT_COMPANY_KEY = "bnrl_frRFjEz8Mkn"

        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** Те саме кодування, що й Python quote_plus. */
        private fun enc(v: String): String =
            URLEncoder.encode(v, "UTF-8").replace("*", "%2A").replace("%7E", "~")
    }

    private fun actionString(action: String, params: List<Pair<String, String>>): String {
        val sb = StringBuilder("&action=").append(action)
        for ((k, v) in params) sb.append('&').append(k).append('=').append(enc(v))
        return sb.toString()
    }

    private fun httpGet(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "inverter-widget/1.0")
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException("HTTP $code")
            return JSONObject(body)
        } finally {
            c.disconnect()
        }
    }

    private fun check(action: String, j: JSONObject): JSONObject {
        val err = j.optInt("err", -1)
        if (err != 0) throw ApiException("$action: ${j.optString("desc", "помилка")} (код $err)")
        return j
    }

    fun authenticate() {
        val user = prefs.user ?: throw ApiException("Не вказано логін")
        val pwd = prefs.pwdSha1 ?: throw ApiException("Не вказано пароль")
        var last: Exception? = null
        // authSource — поточний спосіб входу DessMonitor; auth — старий, як запасний
        for ((action, source) in listOf("authSource" to "1", "auth" to "0")) {
            try {
                val params = listOf(
                    "usr" to user,
                    "company-key" to prefs.companyKey,
                    "source" to source,
                    "_app_client_" to "android",
                    "_app_id_" to "inverter-widget",
                    "_app_version_" to "1.0",
                )
                val a = actionString(action, params)
                val salt = System.currentTimeMillis().toString()
                val sign = sha1(salt + pwd + a)
                val dat = check(action, httpGet("$BASE?sign=$sign&salt=$salt$a")).getJSONObject("dat")
                prefs.token = dat.getString("token")
                prefs.secret = dat.getString("secret")
                prefs.tokenExp = System.currentTimeMillis() / 1000 + dat.optLong("expire", 3600) - 60
                return
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: ApiException("Не вдалося увійти")
    }

    private fun signedCall(action: String, params: List<Pair<String, String>>): JSONObject {
        val token = prefs.token ?: throw ApiException("Немає токена")
        val secret = prefs.secret ?: throw ApiException("Немає токена")
        val a = actionString(action, params)
        val salt = System.currentTimeMillis().toString()
        val sign = sha1(salt + secret + token + a)
        return check(action, httpGet("$BASE?sign=$sign&salt=$salt&token=$token$a"))
    }

    fun call(action: String, params: List<Pair<String, String>>, retryAuth: Boolean = true): JSONObject {
        if (prefs.token == null || System.currentTimeMillis() / 1000 >= prefs.tokenExp) authenticate()
        return try {
            signedCall(action, params)
        } catch (e: ApiException) {
            if (!retryAuth) throw e
            authenticate() // токен міг протухнути на сервері — входимо ще раз
            signedCall(action, params)
        }
    }

    /** Знаходить інвертор в акаунті й запам'ятовує pn/sn/devcode/devaddr. Повертає опис. */
    fun discover(): String {
        val want = prefs.wantedSn?.trim()?.takeIf { it.isNotEmpty() }
        val found = mutableListOf<String>()
        val plants = call("queryPlants", listOf("pagesize" to "50"))
            .optJSONObject("dat")?.optJSONArray("plant") ?: JSONArray()
        for (i in 0 until plants.length()) {
            val pid = plants.optJSONObject(i)?.opt("pid")?.toString() ?: continue
            val cols = call("webQueryCollectorsEs", listOf("pid" to pid, "page" to "0", "pagesize" to "50"))
                .optJSONObject("dat")?.optJSONArray("collector") ?: continue
            for (k in 0 until cols.length()) {
                val pn = cols.optJSONObject(k)?.optString("pn").orEmpty()
                if (pn.isEmpty()) continue
                val devs = call("queryCollectorDevices", listOf("pn" to pn))
                    .optJSONObject("dat")?.optJSONArray("dev") ?: continue
                for (d in 0 until devs.length()) {
                    val dev = devs.optJSONObject(d) ?: continue
                    val sn = dev.optString("sn")
                    found.add(sn)
                    if (want == null || want == sn || want == pn) {
                        prefs.pn = pn
                        prefs.sn = sn
                        prefs.devcode = dev.opt("devcode").toString()
                        prefs.devaddr = dev.opt("devaddr").toString()
                        return "Інвертор SN $sn (логер $pn, devcode ${prefs.devcode})"
                    }
                }
            }
        }
        if (found.isEmpty()) throw ApiException("В акаунті не знайдено жодного інвертора")
        throw ApiException("SN $want не знайдено. Є: ${found.joinToString()}")
    }

    fun read(): Reading {
        if (prefs.sn == null || prefs.pn == null) discover()
        val dev = listOf(
            "pn" to prefs.pn!!,
            "devcode" to prefs.devcode!!,
            "devaddr" to prefs.devaddr!!,
            "sn" to prefs.sn!!,
        )
        val fields = mutableListOf<Field>()
        collect(call("queryDeviceLastData", dev + ("i18n" to "en")).opt("dat"), fields)
        var r = interpret(fields)
        if (r.soc == null || r.loadW == null) {
            // У деяких моделей заряд є лише в параметрах або в «енергопотоці»
            val extra = listOf(
                "queryDeviceParsEs" to listOf("i18n" to "en_US", "source" to "1"),
                "webQueryDeviceEnergyFlowEs" to listOf("i18n" to "en_US"),
            )
            for ((action, p) in extra) {
                try {
                    collect(call(action, p + dev, retryAuth = false).opt("dat"), fields)
                } catch (_: Exception) {
                }
                r = interpret(fields)
                if (r.soc != null && r.loadW != null) break
            }
        }
        if (r.soc == null && r.loadW == null && r.loadPct == null) {
            throw ApiException("Хмара відповіла, але заряду й навантаження серед полів не знайдено")
        }
        return r
    }

    /** Рекурсивно збирає всі об'єкти виду {title|name|par, val, unit}. */
    private fun collect(node: Any?, out: MutableList<Field>) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
            is JSONObject -> {
                if (node.has("val")) {
                    val label = listOf("title", "name").map { node.optString(it) }.firstOrNull { it.isNotBlank() }
                    val key = node.optString("par")
                    if (label != null || key.isNotBlank()) {
                        out.add(Field(label ?: key, key, node.optString("val"), node.optString("unit")))
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val v = node.opt(keys.next())
                    if (v is JSONObject || v is JSONArray) collect(v, out)
                }
            }
        }
    }

    private fun first(fields: List<Field>, vararg tests: (Field) -> Boolean): Field? {
        for (t in tests) fields.firstOrNull { it.number != null && t(it) }?.let { return it }
        return null
    }

    private fun interpret(fields: List<Field>): Reading {
        val isPct = { f: Field -> f.unit.isBlank() || f.unit.contains("%") }
        val socField = first(
            fields,
            { it.text.contains("battery capacity") && isPct(it) },
            { it.text.contains("battery percentage") && isPct(it) },
            { it.text.contains("battery_capacity") && isPct(it) },
            { (Regex("\\bsoc\\b").containsMatchIn(it.text) || it.text.contains("state of charge")) && isPct(it) },
            { (it.text.contains("battery level") || it.text.contains("battery percent")) && isPct(it) },
        )?.takeIf { it.number!! in 0.0..100.0 }

        val notLoad = { f: Field ->
            listOf("apparent", "pv", "rated", "grid", "input", "charg", "setting").any { f.text.contains(it) }
        }
        val loadField = first(
            fields,
            { it.text.contains("ac output active power") },
            { it.text.contains("load active power") || it.text.contains("load_active_power") },
            { it.text.contains("output active power") && !notLoad(it) },
            { (it.text.contains("load power") || it.text.contains("output power")) && !notLoad(it) },
        )
        val loadW = loadField?.let { f ->
            val v = f.number!!
            if (f.unit.trim().equals("kw", ignoreCase = true)) v * 1000 else v
        }

        val loadPct = first(
            fields,
            { it.text.contains("load percent") || it.text.contains("load_percent") },
        )?.number

        val battV = first(
            fields,
            { it.text.contains("battery voltage") && !notLoad(it) && it.unit.equals("V", ignoreCase = true) },
            { it.text.contains("battery voltage") && !notLoad(it) },
        )?.number

        return Reading(socField?.number, loadW, loadPct, battV, fields.toList())
    }
}
