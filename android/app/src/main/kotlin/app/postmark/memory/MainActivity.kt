package app.postmark.memory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.postmark.memory.ui.home.BackendHealthUiState
import app.postmark.memory.ui.home.HomeScreen
import postmark.core.data.network.ApiBaseUrl
import postmark.core.data.network.HealthCheckResult
import postmark.core.data.network.HealthRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val repository = remember {
                HealthRepository.create(ApiBaseUrl.parse(BuildConfig.API_BASE_URL))
            }
            var state by remember { mutableStateOf(BackendHealthUiState.CHECKING) }
            LaunchedEffect(repository) {
                state = when (repository.check()) {
                    HealthCheckResult.Available -> BackendHealthUiState.AVAILABLE
                    is HealthCheckResult.Unavailable -> BackendHealthUiState.UNAVAILABLE
                }
            }
            MaterialTheme {
                HomeScreen(state)
            }
        }
    }
}
