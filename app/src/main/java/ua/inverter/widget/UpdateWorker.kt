package ua.inverter.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        refresh(applicationContext)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "inverter-periodic"
        private const val NOW = "inverter-now"

        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Синхронне оновлення: запит до хмари, збереження, перемальовування віджета. */
        fun refresh(ctx: Context, onStep: (String) -> Unit = {}): Reading? {
            val prefs = Prefs(ctx)
            prefs.lastAttempt = System.currentTimeMillis()
            var reading: Reading? = null
            if (prefs.user == null || prefs.pwdSha1 == null) {
                prefs.lastError = "Відкрийте застосунок і введіть логін SmartESS"
            } else {
                try {
                    reading = InverterApi(ctx, onStep).read()
                    prefs.saveReading(reading)
                } catch (e: Exception) {
                    prefs.lastError = e.message ?: e.toString()
                }
            }
            prefs.status = null
            InverterWidget.render(ctx)
            return reading
        }

        /** Автооновлення раз на 15 хв (мінімум, який дозволяє Android). */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(network)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        }
    }
}
