package app.postmark.memory.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Postmark")
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Architecture approved · M0 foundation",
            modifier = Modifier.testTag("home_build_label"),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.testTag("create_action"),
        ) {
            Text("Create")
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.testTag("scan_action"),
        ) {
            Text("Scan")
        }
    }
}
