package ua.inverter.widget

import android.Manifest
import android.app.Activity
import android.os.Build
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
        val threshold = findViewById<EditText>(R.id.threshold)
        val btn = findViewById<Button>(R.id.check)
        val test = findViewById<Button>(R.id.testAlert)
        val out = findViewById<TextView>(R.id.result)

        user.setText(prefs.user ?: "")
        sn.setText(prefs.wantedSn ?: "")
        threshold.setText(prefs.alertThreshold.toString())
        askNotificationPermission()

        test.setOnClickListener {
            if (!Alerts.canNotify(this)) {
                askNotificationPermission()
                out.text = "Дозвольте сповіщення для застосунку «Інвертор» і натисніть ще раз"
            } else {
                Alerts.show(this, "🔋 Тест: заряд АКБ ${prefs.soc?.toInt() ?: "--"}%", "Так виглядатиме сповіщення про низький заряд.")
                out.text = "Тестове сповіщення надіслано"
            }
        }
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
            threshold.text.toString().toIntOrNull()?.takeIf { it in 1..99 }?.let {
                if (it != prefs.alertThreshold) prefs.lowAlerted = false
                prefs.alertThreshold = it
            }
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
                val started = System.currentTimeMillis()
                val step = { msg: String ->
                    val sec = (System.currentTimeMillis() - started) / 1000
                    runOnUiThread { out.text = "$msg ($sec с)" }
                }
                val text = try {
                    val api = InverterApi(this, step)
                    val dev = if (prefs.sn == null) api.discover() else "Інвертор SN ${prefs.sn}"
                    val r = UpdateWorker.refresh(this, step) ?: throw ApiException(prefs.lastError ?: "невідома помилка")
                    UpdateWorker.schedule(this)
                    buildString {
                        append("✅ $dev\n\n")
                        append("АКБ: ${r.soc?.let { "${it.toInt()}%" } ?: "не знайдено"}\n")
                        append("Навантаження: ${r.loadW?.let { "$it W" } ?: r.loadPct?.let { "$it %" } ?: "не знайдено"}\n")
                        append("Сонце: ${r.pvW?.let { InverterWidget.formatW(it) } ?: "не знайдено"}\n")
                        r.batteryV?.let { append("Напруга АКБ: $it V\n") }
                        append("Сповіщення: нижче ${prefs.alertThreshold}%")
                        append(if (Alerts.canNotify(this@MainActivity)) "\n" else " (сповіщення вимкнені!)\n")
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

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Alerts.canNotify(this)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
