package dev.hryshyn.rv01probe

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.hryshyn.rv01probe.probe.AndroidProbePTransportPort
import dev.hryshyn.rv01probe.probe.AndroidProbeScheduler
import dev.hryshyn.rv01probe.probe.AndroidProbeUStorePort
import dev.hryshyn.rv01probe.probe.AndroidProbeEligibilityPort
import dev.hryshyn.rv01probe.probe.ProbeController
import java.util.concurrent.Executors
import dev.hryshyn.rv01probe.ui.ProbeScreen

class MainActivity : ComponentActivity() {
    private lateinit var controller: ProbeController
    private lateinit var pTransport: AndroidProbePTransportPort
    private val safExecutor = Executors.newSingleThreadExecutor()

    private val exportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            pTransport.select(uri)
            controller.exportSidecar()
        }
    }

    private val importDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pTransport.select(uri)
            controller.verifyBeforeWipe()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pTransport = AndroidProbePTransportPort(this, safExecutor)
        controller = ProbeController(
            eligibilityPort = AndroidProbeEligibilityPort(this),
            uStore = AndroidProbeUStorePort(this),
            pTransport = pTransport,
            scheduler = AndroidProbeScheduler(Handler(Looper.getMainLooper())),
        )
        setContent {
            var state by remember { mutableStateOf(controller.state) }
            DisposableEffect(controller) {
                val removeListener = controller.addListener { updated ->
                    runOnUiThread { state = updated }
                }
                onDispose { removeListener() }
            }
            MaterialTheme {
                ProbeScreen(
                    state = state,
                    onStart = { controller.begin() },
                    onExport = { exportDocument.launch("rv01-sidecar.bin") },
                    onImportAndVerify = {
                        importDocument.launch(arrayOf("application/octet-stream"))
                    },
                    onRetry = { controller.retry() },
                    onCancel = { controller.cancel() },
                    onCleanup = { controller.cleanup() },
                )
            }
        }
    }

    override fun onDestroy() {
        controller.processRecreated()
        controller.close()
        safExecutor.shutdownNow()
        super.onDestroy()
    }
}
