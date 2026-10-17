package dev.sterrist.openfat.data.repo

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.sterrist.openfat.data.db.SettingDao
import dev.sterrist.openfat.data.db.SettingEntity
import dev.sterrist.openfat.domain.ManualStreak
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Настройки. Целевой вес лежит в БД и попадает в бэкап.
 * Токены YAZIO — в отдельных SharedPreferences, которые исключены из бэкапов и экспорта.
 */
class SettingsRepository(
    private val dao: SettingDao,
    private val secrets: SharedPreferences,
) {
    private val yazioAccessState = MutableStateFlow(secrets.getString(KEY_YAZIO_ACCESS, null))
    private val yazioRefreshState = MutableStateFlow(secrets.getString(KEY_YAZIO_REFRESH, null))
    init {
        // Поиск USDA убран — стираем ранее сохранённый ключ с телефона.
        if (secrets.contains(KEY_LEGACY_USDA)) secrets.edit { remove(KEY_LEGACY_USDA) }
    }

    private val yazioConfiguredState = MutableStateFlow(!yazioAccessState.value.isNullOrBlank())
    private val yazioEmailState = MutableStateFlow(secrets.getString(KEY_YAZIO_EMAIL_MASKED, null))

    val yazioConfigured: StateFlow<Boolean> = yazioConfiguredState.asStateFlow()

    /** Почта входа в YAZIO, уже замаскированная (exa****@mail.ru); полный адрес не хранится. */
    val yazioMaskedEmail: StateFlow<String?> = yazioEmailState.asStateFlow()

    fun saveYazioEmail(email: String) {
        val masked = maskEmail(email)
        secrets.edit { putString(KEY_YAZIO_EMAIL_MASKED, masked) }
        yazioEmailState.value = masked
    }

    fun currentYazioAccessToken(): String? = yazioAccessState.value
    fun currentYazioRefreshToken(): String? = yazioRefreshState.value

    fun saveYazioTokens(access: String, refresh: String?) {
        secrets.edit {
            putString(KEY_YAZIO_ACCESS, access)
            if (refresh.isNullOrBlank()) remove(KEY_YAZIO_REFRESH) else putString(KEY_YAZIO_REFRESH, refresh)
        }
        yazioAccessState.value = access
        yazioRefreshState.value = refresh
        yazioConfiguredState.value = true
    }

    fun clearYazioTokens() {
        secrets.edit { remove(KEY_YAZIO_ACCESS); remove(KEY_YAZIO_REFRESH); remove(KEY_YAZIO_EMAIL_MASKED) }
        yazioEmailState.value = null
        yazioAccessState.value = null
        yazioRefreshState.value = null
        yazioConfiguredState.value = false
    }

    fun observeTargetWeight(): Flow<Double?> =
        dao.observe(KEY_TARGET_WEIGHT).map { it?.toDoubleOrNull() }

    suspend fun setTargetWeight(kg: Double?) {
        if (kg == null) dao.delete(KEY_TARGET_WEIGHT) else dao.upsert(SettingEntity(KEY_TARGET_WEIGHT, kg.toString()))
    }

    /** Дни серии, указанные вручную; хранятся в БД и попадают в бэкап. */
    fun observeManualStreak(): Flow<ManualStreak?> =
        combine(dao.observe(KEY_STREAK_DAYS), dao.observe(KEY_STREAK_ANCHOR)) { days, anchor ->
            val d = days?.toIntOrNull()
            val a = anchor?.toLongOrNull()
            if (d != null && a != null && d > 0) ManualStreak(d, a) else null
        }

    suspend fun setManualStreak(streak: ManualStreak?) {
        if (streak == null || streak.days <= 0) {
            dao.delete(KEY_STREAK_DAYS)
            dao.delete(KEY_STREAK_ANCHOR)
        } else {
            dao.upsertAll(
                listOf(
                    SettingEntity(KEY_STREAK_DAYS, streak.days.toString()),
                    SettingEntity(KEY_STREAK_ANCHOR, streak.anchorDay.toString()),
                ),
            )
        }
    }

    companion object {
        const val KEY_TARGET_WEIGHT = "target_weight_kg"
        const val KEY_STREAK_DAYS = "streak_manual_days"
        const val KEY_STREAK_ANCHOR = "streak_manual_anchor_day"
        const val MAX_MANUAL_STREAK_DAYS = 36_500
        const val SECRETS_FILE = "secrets"
        private const val KEY_LEGACY_USDA = "usda_api_key"
        private const val KEY_YAZIO_ACCESS = "yazio_access_token"
        private const val KEY_YAZIO_REFRESH = "yazio_refresh_token"
        private const val KEY_YAZIO_EMAIL_MASKED = "yazio_email_masked"

        /** Первые 3 символа имени, затем звёздочки и домен: example@mail.ru → exa****@mail.ru. */
        fun maskEmail(email: String): String {
            val value = email.trim()
            val at = value.lastIndexOf('@')
            val local = if (at >= 0) value.substring(0, at) else value
            val domain = if (at >= 0) value.substring(at) else ""
            return local.take(3) + "****" + domain
        }

        /** Ключи настроек, которые можно принимать из файла импорта. */
        val IMPORTABLE_KEYS = setOf(KEY_TARGET_WEIGHT, KEY_STREAK_DAYS, KEY_STREAK_ANCHOR)

        /** Проверка значения настройки из файла импорта (файл — недоверенные данные). */
        fun isValidImportedSetting(key: String, value: String): Boolean = when (key) {
            KEY_TARGET_WEIGHT -> value.toDoubleOrNull()?.let { it in 1.0..700.0 } == true
            KEY_STREAK_DAYS -> value.toIntOrNull()?.let { it in 1..MAX_MANUAL_STREAK_DAYS } == true
            KEY_STREAK_ANCHOR -> value.toLongOrNull()?.let { it in 0..200_000 } == true
            else -> false
        }
    }
}
