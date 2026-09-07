package dev.hryshyn.rv01probe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.hryshyn.rv01probe.probe.LocalDryRunFactory
import dev.hryshyn.rv01probe.probe.LocalDryRunResult
import dev.hryshyn.rv01probe.ui.ProbeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var result by remember { mutableStateOf<LocalDryRunResult?>(null) }
            MaterialTheme {
                ProbeScreen(
                    result = result,
                    onRunDryRun = { result = LocalDryRunFactory.runBlockStoreDryRun() },
                )
            }
        }
    }
}
