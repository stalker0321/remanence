package dev.hryshyn.remanence.ui.capsule

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Narrow provider root; never expose identity, ciphertext, databases or all cache files. */
internal object CapsuleExportFiles {
    private const val MAX_FILES = 3
    private const val TTL_MILLIS = 60 * 60 * 1000L
    const val MAX_BYTES = 16 * 1024 * 1024

    fun write(context: Context, png: ByteArray, now: Long = System.currentTimeMillis()): File {
        require(png.isNotEmpty() && png.size <= MAX_BYTES)
        val directory = File(context.cacheDir, "capsule-exports").apply { check(mkdirs() || isDirectory) }
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, file ->
                if (index >= MAX_FILES - 1 || now - file.lastModified() > TTL_MILLIS) check(file.delete())
            }
        val target = File(directory, "remanence-${UUID.randomUUID()}.png")
        try {
            target.outputStream().use { it.write(png) }
            return target
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".capsuleexport", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Remanence", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

/** Explicit export only. Captures a content layer, never the window or its system bars. */
internal class CapsuleImageExport(
    val contentModifier: Modifier,
    val capture: suspend () -> ByteArray,
    val validate: () -> Unit,
)

@Composable
internal fun rememberCapsuleImageExport(validate: () -> Unit): CapsuleImageExport {
    val layer = rememberGraphicsLayer()
    val currentValidate by rememberUpdatedState(validate)
    return remember(layer) {
        CapsuleImageExport(
            contentModifier = Modifier.drawWithContent {
                layer.record {
                    drawRect(HoldColors.Paper)
                    this@drawWithContent.drawContent()
                }
                drawLayer(layer)
            },
            validate = { currentValidate() },
            capture = {
                currentValidate()
                check(layer.size.width > 0 && layer.size.height > 0)
                check(layer.size.width.toLong() * layer.size.height <= 8_000_000L)
                val bitmap = layer.toImageBitmap().asAndroidBitmap()
                try {
                    withContext(Dispatchers.IO) {
                        ByteArrayOutputStream().use { stream ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                            check(stream.size() <= CapsuleExportFiles.MAX_BYTES)
                            stream.toByteArray()
                        }
                    }.also { png ->
                        try { currentValidate() } catch (failure: Throwable) { png.fill(0); throw failure }
                    }
                } finally {
                    // This is a dedicated exported bitmap, never a bitmap used by the renderer.
                    bitmap.recycle()
                }
            },
        )
    }
}

@Composable
internal fun CapsuleImageExportButton(export: CapsuleImageExport, enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // Not saved state. A cancelled document picker or disposed route clears the bytes.
    val pending = remember { mutableStateOf<ByteArray?>(null) }
    val title = stringResource(R.string.capsule_export_share)
    DisposableEffect(export) { onDispose { pending.value?.fill(0); pending.value = null } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bytes = pending.value
        pending.value = null
        if (bytes != null) scope.launch {
            try {
                if (uri != null) {
                    export.validate()
                    withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { bytes.fill(0); busy = false }
        } else busy = false
    }
    fun perform(toFile: Boolean) {
        menu = false
        busy = true
        failed = false
        scope.launch {
            var bytes: ByteArray? = null
            var file: File? = null
            try {
                bytes = export.capture()
                if (toFile) {
                    pending.value = bytes
                    bytes = null // ownership transferred to picker callback / disposal
                    save.launch("remanence.png")
                } else {
                    val png = checkNotNull(bytes)
                    withContext(Dispatchers.IO) { file = CapsuleExportFiles.write(context, png) }
                    export.validate() // expiry/account switch while writing must not hand the URI out
                    context.startActivity(Intent.createChooser(CapsuleExportFiles.shareIntent(context, checkNotNull(file)), title))
                    file = null // intentional plaintext export; recipient owns its external copy
                    busy = false
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { pending.value?.fill(0); pending.value = null; failed = true; busy = false }
            finally { bytes?.fill(0); file?.delete() }
        }
    }
    HoldTextButton(onClick = { menu = true }, enabled = enabled && !busy,
        modifier = modifier.testTag("capsule_share")) {
        Text(stringResource(if (busy) R.string.capsule_export_working else R.string.capsule_export_share))
    }
    if (menu) AlertDialog(onDismissRequest = { menu = false },
        title = { Text(title) },
        text = { Column {
            Text(stringResource(R.string.capsule_export_warning))
            HoldTextButton(onClick = { perform(false) }, modifier = Modifier.testTag("capsule_export_apps")) {
                Text(stringResource(R.string.capsule_export_apps))
            }
            HoldTextButton(onClick = { perform(true) }, modifier = Modifier.testTag("capsule_export_file")) {
                Text(stringResource(R.string.capsule_export_file))
            }
        } }, confirmButton = {}, dismissButton = {
            HoldTextButton(onClick = { menu = false }) { Text(stringResource(R.string.hold_close)) }
        })
    if (failed) AlertDialog(onDismissRequest = { failed = false },
        text = { Text(stringResource(R.string.capsule_export_failed)) },
        confirmButton = { HoldTextButton(onClick = { failed = false }) { Text(stringResource(R.string.hold_close)) } })
}
