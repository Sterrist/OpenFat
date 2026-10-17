package dev.sterrist.openfat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.sterrist.openfat.R
import dev.sterrist.openfat.ui.addfood.AddFoodScreen
import dev.sterrist.openfat.ui.diary.AddTarget
import dev.sterrist.openfat.ui.diary.DiaryScreen
import dev.sterrist.openfat.ui.history.CaloriesHistoryScreen
import dev.sterrist.openfat.ui.product.ProductsScreen
import dev.sterrist.openfat.ui.settings.MealsScreen
import dev.sterrist.openfat.ui.settings.SettingsScreen
import dev.sterrist.openfat.ui.weight.WeightScreen
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable data object DiaryRoute
@Serializable data object WeightRoute
@Serializable data object SettingsRoute
@Serializable data object HistoryRoute
@Serializable data object MealsRoute
@Serializable data object ProductsRoute

/** Добавление еды: в приём [mealId], в перекус [snackId] или в новый перекус на [newSnackMinutes]. */
@Serializable
data class AddFoodRoute(
    val day: Long,
    val mealId: String? = null,
    val snackId: String? = null,
    val newSnackMinutes: Int = -1,
)

private data class Tab(val route: Any, val routeClass: KClass<*>, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(DiaryRoute, DiaryRoute::class, R.string.tab_diary, Icons.Default.Restaurant),
    Tab(WeightRoute, WeightRoute::class, R.string.tab_weight, Icons.Default.MonitorWeight),
    Tab(SettingsRoute, SettingsRoute::class, R.string.tab_settings, Icons.Default.Settings),
)

@Composable
fun OpenFatNavHost() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBar = tabs.any { tab -> destination?.hasRoute(tab.routeClass) == true }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
        // Отступы от системных панелей (статус-бар, вырез камеры) обрабатывают Scaffold самих экранов.
        // Здесь только высота нижней навигации, иначе верхний отступ «съедается» и шапки уходят под шторку.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            NavHost(nav, startDestination = DiaryRoute) {
                composable<DiaryRoute> {
                    DiaryScreen(
                        onAddFood = { day, target ->
                            nav.navigate(
                                when (target) {
                                    is AddTarget.Meal -> AddFoodRoute(day, mealId = target.mealId)
                                    is AddTarget.Snack -> AddFoodRoute(day, snackId = target.snackId)
                                    is AddTarget.NewSnack -> AddFoodRoute(day, newSnackMinutes = target.minutes)
                                },
                            )
                        },
                        onOpenHistory = { nav.navigate(HistoryRoute) },
                    )
                }
                composable<WeightRoute> { WeightScreen() }
                composable<SettingsRoute> {
                    SettingsScreen(
                        onOpenMeals = { nav.navigate(MealsRoute) },
                        onOpenProducts = { nav.navigate(ProductsRoute) },
                    )
                }
                composable<AddFoodRoute> { AddFoodScreen(onBack = { nav.popBackStack() }) }
                composable<HistoryRoute> { CaloriesHistoryScreen(onBack = { nav.popBackStack() }) }
                composable<MealsRoute> { MealsScreen(onBack = { nav.popBackStack() }) }
                composable<ProductsRoute> { ProductsScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }
}
