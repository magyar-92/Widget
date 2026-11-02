package ua.inverter.widget

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Голосові попередження через системний синтезатор мовлення Android (Google TTS). */
object Speaker {
    private val UK = Locale("uk", "UA")
    private const val TIMEOUT_MS = 30_000L

    /**
     * Промовляє [text] українською.
     * [onResult] отримує null при успіху або текст помилки.
     * [wait] = true блокує потік до кінця мовлення — лише для фонових потоків,
     * щоб Android не зупинив процес посеред фрази. З UI-потоку викликати з wait = false.
     */
    fun speak(ctx: Context, text: String, wait: Boolean = false, onResult: (String?) -> Unit = {}) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        var tts: TextToSpeech? = null

        fun finish(error: String?) {
            if (!finished.compareAndSet(false, true)) return
            main.post { tts?.shutdown() }
            onResult(error)
            latch.countDown()
        }

        fun onInit(status: Int) {
            val t = tts
            if (status != TextToSpeech.SUCCESS || t == null) {
                finish("Синтезатор мовлення недоступний на цьому телефоні")
            } else if (t.isLanguageAvailable(UK) < TextToSpeech.LANG_AVAILABLE) {
                finish(
                    "Немає українського голосу. Встановіть: Налаштування → Система → Мова → " +
                        "Синтез мовлення → Google → Встановити голосові дані → Українська"
                )
            } else if (t.setLanguage(UK) < TextToSpeech.LANG_AVAILABLE) {
                finish("Не вдалося увімкнути український голос")
            } else {
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION) // мовчить у беззвучному режимі
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        finish(null)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        finish("Помилка синтезу мовлення")
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        finish("Помилка синтезу мовлення (код $errorCode)")
                    }
                })
                if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "battery-alert") != TextToSpeech.SUCCESS) {
                    finish("Не вдалося запустити мовлення")
                }
            }
        }

        // TextToSpeech створюємо на головному потоці: тоді onInit гарантовано прийде після присвоєння tts
        main.post {
            tts = TextToSpeech(app) { status -> onInit(status) }
        }
        main.postDelayed({ finish("Синтезатор мовлення не відповів вчасно") }, TIMEOUT_MS)

        if (wait && Looper.myLooper() != Looper.getMainLooper()) {
            latch.await(TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS)
        }
    }

    /** Правильна форма слова після числа: 1 відсоток, 2 відсотки, 5 відсотків. */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val m100 = n % 100
        val m10 = n % 10
        return when {
            m100 in 11..14 -> many
            m10 == 1 -> one
            m10 in 2..4 -> few
            else -> many
        }
    }

    fun lowBatteryText(soc: Int, loadW: Int?): String = buildString {
        append("Увага! Заряд акумулятора інвертора $soc ${plural(soc, "відсоток", "відсотки", "відсотків")}. ")
        if (loadW != null) append("Навантаження $loadW ${plural(loadW, "ват", "вати", "ват")}. ")
        append("Варто зменшити навантаження.")
    }
}
