package dev.sterrist.openfat.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.sterrist.openfat.R
import dev.sterrist.openfat.data.db.MealEntity
import dev.sterrist.openfat.data.repo.DiaryRepository
import dev.sterrist.openfat.ui.common.ConfirmDialog
import dev.sterrist.openfat.ui.common.appContainer
import dev.sterrist.openfat.ui.diary.NameDialog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MealsViewModel(private val diary: DiaryRepository) : ViewModel() {
    val meals: StateFlow<List<MealEntity>> = diary.observePermanentMeals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String) = viewModelScope.launch { diary.addMeal(name, oneTimeDate = null) }
    fun rename(id: String, name: String) = viewModelScope.launch { diary.renameMeal(id, name) }
    fun delete(id: String) = viewModelScope.launch { diary.deleteMeal(id) }
    fun restore(id: String) = viewModelScope.launch { diary.restoreMeal(id) }
    fun move(id: String, up: Boolean) = viewModelScope.launch { diary.moveMeal(id, up) }

    companion object {
        val Factory = viewModelFactory { initializer { MealsViewModel(appContainer().diary) } }
    }
}

/** Постоянные приёмы пищи: порядок, переименование, удаление и восстановление. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealsScreen(onBack: () -> Unit, viewModel: MealsViewModel = viewModel(factory = MealsViewModel.Factory)) {
    val meals by viewModel.meals.collectAsStateWithLifecycle()
    val active = meals.filter { !it.archived }
    val archived = meals.filter { it.archived }
    var adding by rememberSaveable { mutableStateOf(false) }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.meals_management)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_meal)) },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 88.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { Hint(stringResource(R.string.meals_hint)) }
            itemsIndexed(active, key = { _, m -> m.id }) { index, meal ->
                ListItem(
                    headlineContent = { Text(meal.name) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { viewModel.move(meal.id, up = true) }, enabled = index > 0) {
                                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up, meal.name))
                            }
                            IconButton(onClick = { viewModel.move(meal.id, up = false) }, enabled = index < active.lastIndex) {
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.move_down, meal.name))
                            }
                            IconButton(onClick = { renamingId = meal.id }) { Icon(Icons.Default.Edit, stringResource(R.string.rename)) }
                            IconButton(onClick = { deletingId = meal.id }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                        }
                    },
                )
            }
            if (archived.isNotEmpty()) {
                item { Hint(stringResource(R.string.deleted_meals), Modifier.padding(top = 16.dp)) }
                items(archived, key = { it.id }) { meal ->
                    ListItem(
                        headlineContent = { Text(meal.name, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = { TextButton(onClick = { viewModel.restore(meal.id) }) { Text(stringResource(R.string.restore)) } },
                    )
                }
            }
        }
    }

    if (adding) {
        NameDialog(
            title = stringResource(R.string.new_meal),
            initial = "",
            onConfirm = { viewModel.add(it); adding = false },
            onDismiss = { adding = false },
        )
    }
    renamingId?.let { id ->
        val meal = meals.firstOrNull { it.id == id }
        if (meal != null) {
            NameDialog(
                title = stringResource(R.string.rename_meal),
                initial = meal.name,
                onConfirm = { viewModel.rename(id, it); renamingId = null },
                onDismiss = { renamingId = null },
            )
        }
    }
    deletingId?.let { id ->
        val meal = meals.firstOrNull { it.id == id }
        if (meal != null) {
            ConfirmDialog(
                title = stringResource(R.string.delete_meal_title, meal.name),
                text = stringResource(R.string.delete_meal_text),
                confirmLabel = stringResource(R.string.delete),
                onConfirm = { viewModel.delete(id); deletingId = null },
                onDismiss = { deletingId = null },
            )
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

