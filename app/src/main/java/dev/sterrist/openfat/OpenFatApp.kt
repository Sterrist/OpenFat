package dev.sterrist.openfat

import android.app.Application
import android.content.Context
import dev.sterrist.openfat.data.backup.BackupManager
import dev.sterrist.openfat.data.db.OpenFatDatabase
import dev.sterrist.openfat.data.remote.HttpClient
import dev.sterrist.openfat.data.remote.OpenFoodFactsSource
import dev.sterrist.openfat.data.remote.YazioClient
import dev.sterrist.openfat.data.remote.YazioSource
import dev.sterrist.openfat.data.repo.DiaryRepository
import dev.sterrist.openfat.data.repo.ProductRepository
import dev.sterrist.openfat.data.repo.SettingsRepository
import dev.sterrist.openfat.data.repo.WeightRepository

/** Ручной DI: все зависимости приложения создаются здесь один раз. */
class AppContainer(context: Context) {
    private val db = OpenFatDatabase.build(context)
    private val http = HttpClient(userAgent = "OpenFat/${BuildInfo.VERSION} (Android; dev.sterrist.openfat)")

    val settings = SettingsRepository(
        db.settingDao(),
        context.getSharedPreferences(SettingsRepository.SECRETS_FILE, Context.MODE_PRIVATE),
    )
    val yazio = YazioSource(
        client = YazioClient(http),
        accessToken = settings::currentYazioAccessToken,
        refreshToken = settings::currentYazioRefreshToken,
        saveTokens = { t -> settings.saveYazioTokens(t.accessToken, t.refreshToken) },
        clearTokens = settings::clearYazioTokens,
    )
    val diary = DiaryRepository(db)
    val weights = WeightRepository(db)
    val products = ProductRepository(
        db.productDao(),
        sources = listOf(
            yazio,
            OpenFoodFactsSource(http),
        ),
    )
    val backup = BackupManager(db, context.contentResolver)
}

private object BuildInfo {
    const val VERSION = "0.1"
}

class OpenFatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
