package ua.inverter.widget

import android.content.Context

/** Налаштування та останні показники (SharedPreferences). Пароль зберігається лише як SHA-1. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("inverter", Context.MODE_PRIVATE)

    private fun str(k: String): String? = sp.getString(k, null)
    private fun put(k: String, v: String?) = sp.edit().putString(k, v).apply()
    private fun dbl(k: String): Double? = str(k)?.toDoubleOrNull()
    private fun putDbl(k: String, v: Double?) = put(k, v?.toString())

    var user: String?
        get() = str("user")
        set(v) = put("user", v)
    var pwdSha1: String?
        get() = str("pwdSha1")
        set(v) = put("pwdSha1", v)
    var companyKey: String
        get() = str("companyKey") ?: InverterApi.DEFAULT_COMPANY_KEY
        set(v) = put("companyKey", v)
    /** SN інвертора або логера, якщо в акаунті кілька пристроїв (необов'язково). */
    var wantedSn: String?
        get() = str("wantedSn")
        set(v) = put("wantedSn", v)

    // Знайдений пристрій
    var pn: String?
        get() = str("pn")
        set(v) = put("pn", v)
    var sn: String?
        get() = str("sn")
        set(v) = put("sn", v)
    var devcode: String?
        get() = str("devcode")
        set(v) = put("devcode", v)
    var devaddr: String?
        get() = str("devaddr")
        set(v) = put("devaddr", v)

    // Токен API
    var token: String?
        get() = str("token")
        set(v) = put("token", v)
    var secret: String?
        get() = str("secret")
        set(v) = put("secret", v)
    var tokenExp: Long
        get() = sp.getLong("tokenExp", 0L)
        set(v) = sp.edit().putLong("tokenExp", v).apply()

    // Останні показники
    var soc: Double?
        get() = dbl("soc")
        set(v) = putDbl("soc", v)
    var loadW: Double?
        get() = dbl("loadW")
        set(v) = putDbl("loadW", v)
    var loadPct: Double?
        get() = dbl("loadPct")
        set(v) = putDbl("loadPct", v)
    var batteryV: Double?
        get() = dbl("batteryV")
        set(v) = putDbl("batteryV", v)
    var lastSuccess: Long
        get() = sp.getLong("lastSuccess", 0L)
        set(v) = sp.edit().putLong("lastSuccess", v).apply()
    var lastAttempt: Long
        get() = sp.getLong("lastAttempt", 0L)
        set(v) = sp.edit().putLong("lastAttempt", v).apply()
    var lastError: String?
        get() = str("lastError")
        set(v) = put("lastError", v)
    /** Тимчасовий напис замість часу оновлення ("оновлюю…"). */
    var status: String?
        get() = str("status")?.takeIf { System.currentTimeMillis() - sp.getLong("statusAt", 0L) < 90_000 }
        set(v) = sp.edit().putString("status", v).putLong("statusAt", System.currentTimeMillis()).apply()

    fun saveReading(r: Reading) {
        soc = r.soc
        loadW = r.loadW
        loadPct = r.loadPct
        batteryV = r.batteryV
        lastSuccess = System.currentTimeMillis()
        lastError = null
    }

    fun forgetDevice() {
        pn = null; sn = null; devcode = null; devaddr = null
        token = null; secret = null; tokenExp = 0L
    }
}
