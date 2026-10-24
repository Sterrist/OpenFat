package dev.sterrist.openfat.data.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import dev.sterrist.openfat.MainActivity
import dev.sterrist.openfat.R
import dev.sterrist.openfat.data.db.MedicationEntity
import dev.sterrist.openfat.data.repo.MedicationRepository
import dev.sterrist.openfat.domain.Dose
import dev.sterrist.openfat.domain.MedicationSchedule
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Напоминания о приёме препаратов. Держит один будильник AlarmManager на ближайшее напоминание;
 * по срабатыванию показывает уведомления о наступивших приёмах и заводит следующий.
 * Неотмеченный приём напоминается снова через 1, 2, 3… часа, шаг растёт до суток (см. [MedicationSchedule]).
 */
class MedicationReminders(context: Context, private val repo: MedicationRepository) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val mutex = Mutex()

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            app.getString(R.string.med_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = app.getString(R.string.med_channel_description) }
        notifications.createNotificationChannel(channel)
    }

    /** Точные будильники разрешены: на Android 12+ это отдельное разрешение, которое пользователь может выключить. */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    /**
     * Показывает напоминания, наступившие с прошлой проверки, убирает уведомления об отмеченных
     * или отменённых приёмах и заводит будильник на следующее напоминание. Безопасно вызывать сколько угодно раз.
     */
    suspend fun sync() = mutex.withLock {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone).toEpochDay()
        val fromDay = today - MedicationSchedule.LOOKBACK_DAYS
        val medications = repo.all().filter { it.active }.associateBy { it.id }
        val resolved = repo.intakesSince(fromDay).mapTo(HashSet()) { doseKey(it.medicationId, it.date, it.time) }
        val doses = medications.values
            .flatMap { MedicationSchedule.doses(it.id, it.timeList, it.startAt, fromDay, today + 1, zone, it.repeat) }
            .filter { doseKey(it.medicationId, it.date, it.time) !in resolved }
        val pending = doses.filter { it.dueAt <= now }

        // При первом запуске старые приёмы не вываливаются пачкой: ждут своего следующего напоминания.
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, now)
        if (NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            for (dose in pending) {
                val reminder = MedicationSchedule.lastReminderAt(dose.dueAt, now) ?: continue
                if (reminder > lastCheck) {
                    notify(medications.getValue(dose.medicationId), dose, today, first = reminder == dose.dueAt)
                }
            }
        }
        val pendingTags = pending.mapTo(HashSet()) { tag(it.medicationId, it.date, it.time) }
        notifications.activeNotifications
            .filter { it.tag?.startsWith(TAG_PREFIX) == true && it.tag !in pendingTags }
            .forEach { notifications.cancel(it.tag, it.id) }
        prefs.edit { putLong(KEY_LAST_CHECK, now) }

        // Раз в неделю или месяц следующий приём может быть далеко за окном выше: ищем его отдельно.
        val upcoming = medications.values.flatMap { m ->
            val day = m.repeat.nextOccurrence(today + 2) ?: return@flatMap emptyList()
            MedicationSchedule.doses(m.id, m.timeList, m.startAt, day, day, zone, m.repeat)
        }
        schedule((doses + upcoming).minOfOrNull { MedicationSchedule.nextReminderAt(it.dueAt, now) })
    }

    /** Убирает уведомление сразу, не дожидаясь [sync]. */
    fun cancel(medicationId: String, date: Long, time: Int) =
        notifications.cancel(tag(medicationId, date, time), NOTIFICATION_ID)

    private fun schedule(at: Long?) {
        val intent = PendingIntent.getBroadcast(
            app,
            0,
            Intent(app, MedicationReceiver::class.java).setAction(MedicationReceiver.ACTION_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        when {
            at == null -> alarms.cancel(intent)
            canScheduleExact() -> alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            // Без разрешения на точные будильники система может задержать напоминание на несколько минут.
            else -> alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    private fun notify(medication: MedicationEntity, dose: Dose, today: Long, first: Boolean) {
        val text = app.getString(
            if (first) R.string.med_notify_due else R.string.med_notify_overdue,
            timeLabel(dose.date, dose.time, today),
        )
        val title = medication.dose?.let { "${medication.name} · $it" } ?: medication.name
        val open = PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_MEDICATIONS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_medication)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(dose.dueAt)
            .setShowWhen(true)
            .setContentIntent(open)
            .setGroup(GROUP)
            .addAction(0, app.getString(R.string.med_action_taken), actionIntent(MedicationReceiver.ACTION_TAKE, dose))
            .addAction(0, app.getString(R.string.med_action_skip), actionIntent(MedicationReceiver.ACTION_SKIP, dose))
            .build()
        try {
            notifications.notify(tag(dose.medicationId, dose.date, dose.time), NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Разрешение на уведомления отозвали между проверкой и показом.
            Log.w(LOG_TAG, "notification denied", e)
        }
    }

    private fun actionIntent(action: String, dose: Dose): PendingIntent = PendingIntent.getBroadcast(
        app,
        0,
        Intent(app, MedicationReceiver::class.java)
            .setAction(action)
            // Свой URI на каждый приём, чтобы PendingIntent разных уведомлений не склеивались.
            .setData("openfat://dose/${dose.medicationId}/${dose.date}/${dose.time}".toUri()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun timeLabel(date: Long, time: Int, today: Long): String {
        val clock = String.format(ru, "%02d:%02d", time / 60, time % 60)
        return when (date) {
            today -> clock
            today - 1 -> app.getString(R.string.med_yesterday_at, clock)
            else -> app.getString(R.string.med_date_at, LocalDate.ofEpochDay(date).format(shortDate), clock)
        }
    }

    companion object {
        const val CHANNEL_ID = "medication_reminders"
        private const val GROUP = "medications"
        private const val PREFS = "medication_reminders"
        private const val KEY_LAST_CHECK = "last_check"
        private const val TAG_PREFIX = "dose:"
        private const val NOTIFICATION_ID = 1
        private const val LOG_TAG = "MedicationReminders"
        private val ru = Locale.forLanguageTag("ru")
        private val shortDate = DateTimeFormatter.ofPattern("d MMM", ru)

        private fun doseKey(medicationId: String, date: Long, time: Int) = "$medicationId|$date|$time"

        private fun tag(medicationId: String, date: Long, time: Int) = "$TAG_PREFIX$medicationId:$date:$time"
    }
}
