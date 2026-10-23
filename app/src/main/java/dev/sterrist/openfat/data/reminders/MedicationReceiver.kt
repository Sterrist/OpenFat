package dev.sterrist.openfat.data.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.sterrist.openfat.AppContainer
import dev.sterrist.openfat.OpenFatApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Будильник напоминаний и кнопки «Принял» / «Пропустить» в уведомлении. Не экспортируется. */
class MedicationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context) { container ->
        val action = intent.action
        if (action == ACTION_TAKE || action == ACTION_SKIP) {
            val segments = intent.data?.pathSegments.orEmpty()
            val medicationId = segments.getOrNull(0)
            val date = segments.getOrNull(1)?.toLongOrNull()
            val time = segments.getOrNull(2)?.toIntOrNull()
            if (medicationId != null && date != null && time != null) {
                container.reminders.cancel(medicationId, date, time)
                container.medications.mark(medicationId, date, time, taken = action == ACTION_TAKE)
            }
        }
        container.reminders.sync()
    }

    companion object {
        const val ACTION_ALARM = "dev.sterrist.openfat.MEDICATION_ALARM"
        const val ACTION_TAKE = "dev.sterrist.openfat.MEDICATION_TAKE"
        const val ACTION_SKIP = "dev.sterrist.openfat.MEDICATION_SKIP"
    }
}

/** Перезагрузка, смена времени или пояса, обновление приложения, выдача разрешения на точные будильники. */
class MedicationRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context) { it.reminders.sync() }
}

private fun BroadcastReceiver.runAsync(context: Context, block: suspend (AppContainer) -> Unit) {
    val container = (context.applicationContext as OpenFatApp).container
    val result = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            block(container)
        } catch (e: Exception) {
            Log.e("MedicationReceiver", "reminder sync failed", e)
        } finally {
            result.finish()
        }
    }
}
