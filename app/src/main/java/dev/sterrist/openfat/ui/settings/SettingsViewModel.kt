package dev.sterrist.openfat.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.sterrist.openfat.data.backup.BackupManager
import dev.sterrist.openfat.data.backup.ImportMode
import dev.sterrist.openfat.data.backup.ImportPlanner
import dev.sterrist.openfat.data.backup.ImportSummary
import dev.sterrist.openfat.data.backup.InvalidBackupException
import dev.sterrist.openfat.data.db.MealEntity
import dev.sterrist.openfat.data.db.SnackEntity
import dev.sterrist.openfat.data.repo.DiaryRepository
import dev.sterrist.openfat.data.repo.SettingsRepository
import dev.sterrist.openfat.data.remote.YazioSource
import dev.sterrist.openfat.domain.Norm
import dev.sterrist.openfat.domain.PortionUnit
import dev.sterrist.openfat.ui.common.appContainer
import dev.sterrist.openfat.ui.common.today
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SettingsMessage { NORM_SAVED, TARGET_SAVED, YAZIO_SAVED, YAZIO_ERROR, EXPORTED, IMPORTED, IMPORT_INVALID, IO_ERROR }

data class ImportPreview(
    val merge: ImportSummary,
    val replace: ImportSummary,
)

/** Подписи для CSV берутся из ресурсов на стороне UI. */
data class CsvLabels(
    val diaryHeader: List<String>,
    val weightHeader: List<String>,
    val snackLabel: (SnackEntity) -> String,
    val unitLabel: (PortionUnit) -> String,
)

data class SettingsUiState(
    val norm: Norm = Norm.DEFAULT,
    val targetWeight: Double? = null,
    val yazioConfigured: Boolean = false,
    val yazioEmail: String? = null,
    val busy: Boolean = false,
    val importPreview: ImportPreview? = null,
    val message: SettingsMessage? = null,
    val messageId: Long = 0,
)

class SettingsViewModel(
    private val diary: DiaryRepository,
    private val settings: SettingsRepository,
    private val yazio: YazioSource,
    private val backup: BackupManager,
) : ViewModel() {
    private val extra = MutableStateFlow(SettingsUiState())
    private var pendingImport: ImportPlanner.Sanitized? = null

    val state: StateFlow<SettingsUiState> = combine(
        diary.observeNormFor(today()),
        settings.observeTargetWeight(),
        combine(settings.yazioConfigured, settings.yazioMaskedEmail, ::Pair),
        extra,
    ) { norm, target, (yazioConfigured, yazioEmail), e ->
        e.copy(norm = norm, targetWeight = target, yazioConfigured = yazioConfigured, yazioEmail = yazioEmail)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun saveNorm(norm: Norm) = launch {
        diary.saveNorm(norm, fromDay = today())
        show(SettingsMessage.NORM_SAVED)
    }

    fun saveTargetWeight(kg: Double?) = launch {
        settings.setTargetWeight(kg)
        show(SettingsMessage.TARGET_SAVED)
    }

    fun loginYazio(email: String, password: String) = io(SettingsMessage.YAZIO_ERROR) {
        yazio.login(email, password)
        settings.saveYazioEmail(email)
        show(SettingsMessage.YAZIO_SAVED)
    }

    fun logoutYazio() {
        yazio.logout()
        show(SettingsMessage.YAZIO_SAVED)
    }

    fun exportJson(uri: Uri) = io { backup.exportJson(uri); show(SettingsMessage.EXPORTED) }

    fun exportDiaryCsv(uri: Uri, labels: CsvLabels) = io {
        backup.exportDiaryCsv(uri, MealEntity::name, labels.snackLabel, labels.unitLabel, labels.diaryHeader)
        show(SettingsMessage.EXPORTED)
    }

    fun exportWeightCsv(uri: Uri, labels: CsvLabels) = io {
        backup.exportWeightCsv(uri, labels.weightHeader)
        show(SettingsMessage.EXPORTED)
    }

    /** Читает файл и показывает сводку для обоих режимов; БД пока не меняется. */
    fun prepareImport(uri: Uri) = io {
        val sanitized = backup.read(uri)
        pendingImport = sanitized
        val preview = ImportPreview(
            merge = backup.plan(sanitized, ImportMode.MERGE).summary,
            replace = backup.plan(sanitized, ImportMode.REPLACE).summary,
        )
        extra.update { it.copy(importPreview = preview) }
    }

    fun applyImport(mode: ImportMode) {
        val sanitized = pendingImport ?: return
        extra.update { it.copy(importPreview = null) }
        io {
            // План пересчитывается заново: данные могли измениться, пока открыт диалог.
            backup.apply(backup.plan(sanitized, mode))
            pendingImport = null
            show(SettingsMessage.IMPORTED)
        }
    }

    fun cancelImport() {
        pendingImport = null
        extra.update { it.copy(importPreview = null) }
    }

    private fun show(message: SettingsMessage) =
        extra.update { it.copy(message = message, messageId = it.messageId + 1) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun io(onError: SettingsMessage = SettingsMessage.IO_ERROR, block: suspend () -> Unit) {
        if (extra.value.busy) return
        extra.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidBackupException) {
                show(SettingsMessage.IMPORT_INVALID)
            } catch (e: Exception) {
                show(onError)
            } finally {
                extra.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = appContainer()
                SettingsViewModel(c.diary, c.settings, c.yazio, c.backup)
            }
        }
    }
}
