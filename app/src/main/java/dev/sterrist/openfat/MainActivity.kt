package dev.sterrist.openfat

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.sterrist.openfat.ui.OpenFatNavHost
import dev.sterrist.openfat.ui.theme.OpenFatTheme

class MainActivity : ComponentActivity() {
    /** Счётчик запросов открыть «Препараты» из уведомления: каждое нажатие — новое значение. */
    private var openMedications by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тема всегда тёмная: светлые иконки системных панелей независимо от системной темы.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            OpenFatTheme {
                OpenFatNavHost(openMedicationsRequest = openMedications)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_MEDICATIONS, false) == true) openMedications++
    }

    companion object {
        const val EXTRA_OPEN_MEDICATIONS = "open_medications"
    }
}
