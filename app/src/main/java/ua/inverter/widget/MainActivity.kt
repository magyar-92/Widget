package ua.inverter.widget

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.util.concurrent.Executors

/** Екран налаштувань: логін SmartESS, перевірка з'єднання, список усіх полів з хмари. */
class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = Prefs(this)
        val user = findViewById<EditText>(R.id.user)
        val pwd = findViewById<EditText>(R.id.password)
        val sn = findViewById<EditText>(R.id.sn)
        val btn = findViewById<Button>(R.id.check)
        val out = findViewById<TextView>(R.id.result)

        user.setText(prefs.user ?: "")
        sn.setText(prefs.wantedSn ?: "")
        if (prefs.pwdSha1 != null) pwd.hint = getString(R.string.password_saved)
        prefs.lastError?.let { out.text = "Остання помилка: $it" }

        btn.setOnClickListener {
            val u = user.text.toString().trim()
            if (u.isEmpty()) {
                out.text = "Введіть логін SmartESS"
                return@setOnClickListener
            }
            if (u != prefs.user || sn.text.toString().trim() != (prefs.wantedSn ?: "")) prefs.forgetDevice()
            prefs.user = u
            prefs.wantedSn = sn.text.toString().trim()
            val p = pwd.text.toString()
            if (p.isNotEmpty()) {
                prefs.pwdSha1 = InverterApi.sha1(p)
                prefs.forgetDevice()
                pwd.setText("")
                pwd.hint = getString(R.string.password_saved)
            }
            if (prefs.pwdSha1 == null) {
                out.text = "Введіть пароль SmartESS"
                return@setOnClickListener
            }
            btn.isEnabled = false
            out.text = "Підключаюся до хмари…"
            io.execute {
                val text = try {
                    val api = InverterApi(this)
                    val dev = if (prefs.sn == null) api.discover() else "Інвертор SN ${prefs.sn}"
                    val r = UpdateWorker.refresh(this) ?: throw ApiException(prefs.lastError ?: "невідома помилка")
                    UpdateWorker.schedule(this)
                    buildString {
                        append("✅ $dev\n\n")
                        append("АКБ: ${r.soc?.let { "${it}%" } ?: "не знайдено"}\n")
                        append("Навантаження: ${r.loadW?.let { "$it W" } ?: r.loadPct?.let { "$it %" } ?: "не знайдено"}\n")
                        r.batteryV?.let { append("Напруга АКБ: $it V\n") }
                        append("\nТепер додайте віджет «Інвертор» на головний екран. ")
                        append("Натискання на віджет оновлює дані.\n\n")
                        append("Усі поля з хмари (для діагностики):\n")
                        r.fields.forEach { f -> append("• ${f.label} = ${f.value} ${f.unit}\n") }
                    }
                } catch (e: Exception) {
                    "❌ ${e.message ?: e}"
                }
                runOnUiThread {
                    out.text = text
                    btn.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
