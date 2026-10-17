package dev.sterrist.openfat.ui.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.sterrist.openfat.R
import dev.sterrist.openfat.data.repo.SettingsRepository
import dev.sterrist.openfat.domain.Streak

/** Цвет огня, когда сегодня уже есть записи. */
private val FireColor = Color(0xFFFF9800)

/** Огонёк серии и число дней справа от него. Серый, пока сегодня ничего не записано. */
@Composable
fun StreakBadge(streak: Streak, onClick: () -> Unit) {
    val description = pluralStringResource(R.plurals.streak_description, streak.days, streak.days) + ". " +
        stringResource(if (streak.loggedToday) R.string.streak_today_logged else R.string.streak_today_empty)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            }
            .padding(horizontal = 12.dp),
    ) {
        Icon(
            Icons.Default.LocalFireDepartment,
            contentDescription = null,
            tint = if (streak.loggedToday) FireColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Text(
            streak.days.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = if (streak.loggedToday) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Пояснение серии и ввод дней, которые пользователь вёл учёт до сегодняшнего дня
 * (например, в другом приложении). Дни с записями в OpenFat считаются автоматически.
 */
@Composable
fun StreakDialog(streak: Streak, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    val beforeToday = streak.days - if (streak.loggedToday) 1 else 0
    var text by rememberSaveable { mutableStateOf(beforeToday.toString()) }
    val value = text.toIntOrNull()?.takeIf { it in 0..SettingsRepository.MAX_MANUAL_STREAK_DAYS }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = if (streak.loggedToday) FireColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        title = { Text(pluralStringResource(R.plurals.streak_title, streak.days, streak.days)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.streak_explanation))
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> if (v.length <= 5 && v.all { it.isDigit() }) text = v },
                    label = { Text(stringResource(R.string.streak_days_before_today)) },
                    supportingText = { Text(stringResource(R.string.streak_days_hint)) },
                    isError = value == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onSave) }, enabled = value != null) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
