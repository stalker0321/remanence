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
import dev.hryshyn.rv01probe.probe.BackupEligibility
import dev.hryshyn.rv01probe.probe.D2ResumeController
import dev.hryshyn.rv01probe.probe.OperatorConfirmedLockKind
import dev.hryshyn.rv01probe.probe.OperatorConfirmedP1Inputs
import dev.hryshyn.rv01probe.probe.ProbeController
import java.util.concurrent.Executors
import dev.hryshyn.rv01probe.ui.ProbeScreen

class MainActivity : ComponentActivity() {
    private lateinit var controller: ProbeController
    private lateinit var pTransport: AndroidProbePTransportPort
    private lateinit var eligibilityPort: AndroidProbeEligibilityPort
    private lateinit var uStorePort: AndroidProbeUStorePort
    private lateinit var d2PTransport: AndroidProbePTransportPort
    private lateinit var d2Resume: D2ResumeController
    private val p1Confirmation = OperatorConfirmedP1Inputs()
    private val safExecutor = Executors.newSingleThreadExecutor()

    private val exportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            pTransport.select(uri)
            controller.exportSidecar()
        }
    }

    private val exportD2Document = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            pTransport.select(uri)
            controller.exportD2Sidecar()
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

    private val selectD2Document = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            d2PTransport.select(uri)
            d2FileChosenState.value = true
        }
    }

    /** Compose-observed D2 file selection; set only by the SAF callback. */
    private val d2FileChosenState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pTransport = AndroidProbePTransportPort(this, safExecutor)
        eligibilityPort = AndroidProbeEligibilityPort(this, p1Confirmation)
        uStorePort = AndroidProbeUStorePort(this, p1Confirmation)
        d2PTransport = AndroidProbePTransportPort(this, safExecutor)
        d2Resume = D2ResumeController(
            uStore = uStorePort,
            pTransport = d2PTransport,
            scheduler = AndroidProbeScheduler(Handler(Looper.getMainLooper())),
        )
        controller = ProbeController(
            eligibilityPort = eligibilityPort,
            uStore = uStorePort,
            pTransport = pTransport,
            scheduler = AndroidProbeScheduler(Handler(Looper.getMainLooper())),
        )
        setContent {
            var state by remember { mutableStateOf(controller.state) }
            var p1Snapshot by remember { mutableStateOf(p1Confirmation.snapshot()) }
            var d2State by remember { mutableStateOf(d2Resume.state) }
            var handoffText by remember { mutableStateOf("") }
            fun refreshP1() {
                p1Snapshot = p1Confirmation.snapshot()
            }
            DisposableEffect(controller) {
                val removeListener = controller.addListener { updated ->
                    runOnUiThread { state = updated }
                }
                onDispose { removeListener() }
            }
            DisposableEffect(d2Resume) {
                val removeListener = d2Resume.addListener { updated ->
                    runOnUiThread { d2State = updated }
                }
                onDispose { removeListener() }
            }
            MaterialTheme {
                ProbeScreen(
                    state = state,
                    confirmation = p1Snapshot,
                    onStart = { controller.begin() },
                    onRecheck = { controller.recheckEligibility() },
                    onConfirmLock = {
                        p1Confirmation.confirmLockKind(it)
                        refreshP1()
                    },
                    onClearLock = {
                        p1Confirmation.clearLockKind()
                        refreshP1()
                    },
                    onConfirmBackup = {
                        p1Confirmation.confirmBackupEligibility(it)
                        refreshP1()
                    },
                    onClearBackup = {
                        p1Confirmation.clearBackupEligibility()
                        refreshP1()
                    },
                    onConfirmBackupNow = {
                        p1Confirmation.confirmBackupNowCompleted()
                        refreshP1()
                    },
                    onClearBackupNow = {
                        p1Confirmation.clearBackupNowCompleted()
                        refreshP1()
                    },
                    onExport = { exportDocument.launch("rv01-sidecar.bin") },
                    onExportD2 = { exportD2Document.launch("rv01-sidecar-d2.bin") },
                    d2State = d2State,
                    handoffText = handoffText,
                    onHandoffTextChange = { handoffText = it },
                    d2FileChosen = d2FileChosenState.value,
                    onSelectD2File = {
                        selectD2Document.launch(arrayOf("application/octet-stream"))
                    },
                    onRunD2Resume = { d2Resume.resume(handoffText) },
                    onCancelD2Resume = { d2Resume.cancel() },
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
        d2Resume.close()
        safExecutor.shutdownNow()
        super.onDestroy()
    }
}
