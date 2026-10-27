package dev.hryshyn.remanence.probe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import java.util.Locale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import dev.hryshyn.remanence.capture.CaptureAttemptController
import dev.hryshyn.remanence.capture.CaptureAttemptSurface
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.CapturedPostcard
import dev.hryshyn.remanence.capture.StillCameraAdapter
import dev.hryshyn.remanence.core.recognition.PointD
import dev.hryshyn.remanence.ui.home.AccountCapabilityState
import dev.hryshyn.remanence.ui.home.BackendHealthUiState
import dev.hryshyn.remanence.ui.home.HomeScreen
import dev.hryshyn.remanence.ui.hold.HoldSpace
import dev.hryshyn.remanence.ui.motion.AstraMotionSpec
import dev.hryshyn.remanence.ui.scan.ScanMatchUiState
import dev.hryshyn.remanence.ui.scan.ScanWaitingGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.capsule.CollagePresentationHost
import dev.hryshyn.remanence.ui.capsule.CollagePresentationState
import dev.hryshyn.remanence.ui.collage.*
import dev.hryshyn.remanence.ui.create.*
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldFlowHeader
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.wiring.productionCollageCropSourceFactory
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Store listing material, not acceptance evidence. Pushed photos run through
 * the installed analysis/producer/renderer; every design the chooser would
 * offer is captured in the chooser and in the recipient's sheet.
 *
 * Input: files/store-input/capsules.txt, one `name|note|photo,photo,...` per
 * line, photos as <id>.jpg beside it. Output: files/store-output/<name>/.
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val input get() = File(context.getExternalFilesDir(null), "store-input")
    private val locale get() = InstrumentationRegistry.getArguments().getString("locale")
    private val suffix get() = locale?.let { "-$it" } ?: ""
    private val output get() = File(context.getExternalFilesDir(null), "store-output$suffix")

    private data class Spec(val name: String, val note: String?, val photos: List<String>)

    private var screen by mutableStateOf<@Composable () -> Unit>({})
    private var reviewing by mutableStateOf(false)

    @Test fun capsules() {
        rule.setContent { Localized { HoldTheme { screen() } } }
        val list = File(input, "capsules$suffix.txt")
        assumeTrue("push store-input first", list.exists())
        val only = InstrumentationRegistry.getArguments().getString("capsule")
        val specs = list.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val (name, note, photos) = line.split("|")
            Spec(name, note.ifBlank { null }, photos.split(",").map(String::trim))
        }.filter { only == null || it.name == only }
        for (spec in specs) capsule(spec)
    }

    /** Home, the content step and the scan wait, from pushed fixtures. */
    @Test fun screens() {
        rule.setContent { Localized { HoldTheme { screen() } } }
        val list = File(input, "screens$suffix.txt")
        assumeTrue("push store-input first", list.exists())
        val config = list.readLines().filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=") }
        val dir = File(output, "screens").apply { deleteRecursively(); mkdirs() }

        rule.runOnIdle { screen = { HomeScreen(BackendHealthUiState.AVAILABLE,
            AccountCapabilityState.CryptoReady("store-owner", "mira"), showLanguageSwitch = false) } }
        capture(File(dir, "home.png"))

        val photos = config.getValue("photos").split(",")
        val thumbs = photos.associateWith { id ->
            BitmapFactory.decodeFile(File(input, "$id.jpg").path, BitmapFactory.Options().apply { inSampleSize = 4 })
                .asImageBitmap()
        }
        rule.runOnIdle { screen = { ContentStep(config.getValue("to"), photos, thumbs, config.getValue("note")) } }
        capture(File(dir, "content.png"))

        val shot = BitmapFactory.decodeFile(File(input, config.getValue("scan")).path)
        val quad = config.getValue("quad").split(" ").map { point ->
            val (x, y) = point.split(",").map(String::toDouble); PointD(x, y)
        }
        val postcard = CapturedPostcard(shot, quad)
        for (motion in listOf(AstraMotionSpec.Resolved.INSTANT)) {
            rule.runOnIdle { screen = { Column(Modifier.fillMaxSize().background(HoldColors.Paper)
                .padding(horizontal = HoldSpace.pageGutter(), vertical = 16.dp)) {
                Text(stringResource(R.string.hold_scan_title),
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 27.sp, lineHeight = 32.sp))
                Spacer(Modifier.height(16.dp))
                ScanWaitingGroup(ScanMatchUiState.Matching, motion, {}, Modifier.fillMaxWidth(), openingPostcard = postcard)
            } } }
            capture(File(dir, "scan-waiting.png"))
        }

        // The live scan step: the real capture surface, its camera replaced by a pushed frame.
        val frame = config["viewfinder"]?.let { BitmapFactory.decodeFile(File(input, it).path).asImageBitmap() } ?: return
        val camera = object : StillCameraAdapter {
            override val preview: @Composable (Modifier) -> Unit = { modifier ->
                Image(frame, null, modifier, contentScale = ContentScale.Crop)
            }
            override fun bind(onReady: () -> Unit, onError: (String) -> Unit) = onReady()
            override fun captureStill(onDelivered: (ByteArray) -> Unit, onError: (String) -> Unit) = Unit
            override fun release() = Unit
        }
        val controller = CaptureAttemptController().apply { onPermissionResolved(CapturePermissionStep.Granted) }
        rule.runOnIdle { screen = { BoxWithConstraints(Modifier.fillMaxSize().background(HoldColors.Paper)) {
            val previewBudget = (maxHeight - 160.dp).coerceAtLeast(64.dp)
            Column(Modifier.fillMaxSize().padding(horizontal = HoldSpace.pageGutter(), vertical = 16.dp)) {
                Text(stringResource(R.string.hold_scan_title),
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 27.sp, lineHeight = 32.sp))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.receiver_scan_capture_body), style = MaterialTheme.typography.bodySmall,
                    color = HoldColors.Muted)
                Spacer(Modifier.height(16.dp))
                CaptureAttemptSurface(controller, "capture_shutter_front", "capture_retake_front",
                    onBeginAttempt = { false }, onDelivered = {}, onRetake = {}, modifier = Modifier.fillMaxWidth(),
                    adapterFactory = { camera }, requestPermissionOnAttach = false, previewMaxHeight = previewBudget,
                    shutterLabel = stringResource(R.string.create_use_postcard), showGuideInstruction = false)
            }
        } } }
        capture(File(dir, "viewfinder.png"))
    }

    /** Mirrors the private content step of CreateScreen with picked photos and a note. */
    @Composable private fun ContentStep(to: String, ids: List<String>,
                                        thumbs: Map<String, androidx.compose.ui.graphics.ImageBitmap?>, note: String) {
        Column(Modifier.fillMaxSize().background(HoldColors.Paper)) {
            HoldFlowHeader(onBack = {}, onClose = {})
            Column(Modifier.weight(1f).padding(horizontal = HoldSpace.pageGutter()).padding(bottom = 16.dp)) {
                Text(stringResource(R.string.hold_for_handle, to),
                    style = MaterialTheme.typography.bodySmall, color = HoldColors.Accent,
                    modifier = Modifier.padding(bottom = 17.dp)
                        .background(HoldColors.Lilac.copy(alpha = .65f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 11.dp, vertical = 7.dp))
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    Text(stringResource(R.string.create_for_one_person), color = HoldColors.Muted,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.hold_content_title), style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.create_choose_photographs), color = HoldColors.Muted,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.create_photographs_count, ids.size),
                            style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted, modifier = Modifier.weight(1f))
                        TextButton(onClick = {}) {
                            Text(stringResource(R.string.create_change_photographs), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    PickedPhotoTiles(ids, thumbs, ids.toSet(), onToggle = {})
                    Spacer(Modifier.height(18.dp))
                    OutlinedTextField(value = note, onValueChange = {},
                        label = { Text(stringResource(R.string.hold_note), style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall, minLines = 3, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.create_continue), Modifier.weight(1f), textAlign = TextAlign.Start)
                    Text("→", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }

    private fun capsule(spec: Spec) {
        val dir = File(output, spec.name).apply { deleteRecursively(); mkdirs() }
        fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val raw = spec.photos.map { File(input, "$it.jpg").readBytes() }
        val hashes = raw.map(::sha)
        val bytes = raw.indices.associate { CapsulePhotoIdentity.contentIdFor(hashes[it]) to raw[it] }
        val sources = bytes.map { (id, data) ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            GeneratorPreviewSource(id, data, bounds.outWidth, bounds.outHeight)
        }
        val generatorInput = GeneratorExpression.GeneratorInput("store-owner", 1L,
            sources.mapIndexed { index, source ->
                GeneratorExpression.PhotoRef(source.contentId, index, source.widthPx, source.heightPx, hashes[index])
            }, spec.note, null)
        val log = StringBuilder()
        val holder = RetainedOriginalHolder()
        sources.forEachIndexed { index, source ->
            log.append("retain ${source.contentId} ${source.widthPx}x${source.heightPx} -> ")
                .append(holder.retain(source.contentId, source.jpegBytes.copyOf(), generatorInput.photos[index].contentHash))
                .append('\n')
        }
        val cropSource = requireNotNull(productionCollageCropSourceFactory(context).create(holder))
        val session = CollageChoiceSession().apply { beginDraft("store-${spec.name}") }
        var built = -1
        rule.runOnIdle { screen = { Column(Modifier.fillMaxSize().background(HoldColors.Paper)) {
            HoldFlowHeader(onBack = {}, onClose = {})
            CollageChoiceHost(session, generatorInput, sources, spec.note, null, cropSource,
                modifier = Modifier.weight(1f).padding(horizontal = 18.dp).padding(bottom = 16.dp),
                onSelect = { session.select(it) },
                showSelectInNavigation = false,
                contentWrapper = { canvas, nav -> ComposeStep(canvas, nav) },
                candidateBuilder = { text ->
                    sources.forEach { log.append("analysis ${it.contentId} -> ")
                        .append(cropSource.analysisSource().analysisFor(it.contentId)?.let { a -> "faces=${a.faces.size}" }).append('\n') }
                    val photos = AllFamilyCandidateProducer.loadPhotos(sources.map {
                        PhotoDescriptor(it.contentId, it.widthPx, it.heightPx, false)
                    }, cropSource.analysisSource())?.map { photo ->
                        photo.copy(hasProminentFace = photo.analysis?.faces
                            ?.any { it.box.width * it.box.height > 0.003 } == true)
                    } ?: return@CollageChoiceHost null.also { built = 0 }
                    val family = AllFamilyInput("store-owner", SENDER, spec.note, false, false, photos)
                    val measure = AllFamilyCandidateProducer.prepareTextMeasurer(family,
                        FrozenGrammarPlans.TextMeasurer { note, width, size, line, font ->
                            val layout = text.layout(CollageRenderer.TextMeasureRequest(note, font,
                                size.toInt(), 400, false, line.toInt(), kotlin.math.floor(width).toInt(), Int.MAX_VALUE))
                            FrozenGrammarPlans.TextMetric(layout.size.height.toDouble(), layout.lineCount)
                        })
                    val outcome = withContext(Dispatchers.Default) {
                        AllFamilyCandidateProducer(AndroidCollageCropMatrixSource(CropAdmissionPolicy.BALANCED),
                            measure, CropAdmissionPolicy.BALANCED).produce(family)
                    }.also { log.append("outcome ").append(if (it is AllFamilyOutcome.Candidates)
                        "candidates=${it.candidates.size} omissions=${it.omissions.groupingBy { o -> o.kind }.eachCount()}" else it).append('\n') } as? AllFamilyOutcome.Candidates
                        ?: return@CollageChoiceHost null.also { built = 0 }
                    outcome.candidates.flatMap { candidate ->
                        AllFamilyV4Adapter.stylesForGrammar(candidate.grammarId).mapNotNull { style ->
                            val sealed = AllFamilyV4Adapter.seal(candidate, generatorInput, SENDER, style)
                                as? AllFamilyV4Adapter.Outcome.Sealed ?: return@mapNotNull null
                            CollageCandidate(GeneratorBer1Provider.candidateIdFor(sealed.expression),
                                style.id, candidate.branchId, sealed.expression)
                        }
                    }.also { built = it.size }
                })
        } } }
        rule.waitUntil(180_000) { built >= 0 && !session.isPreparing }
        val variants = session.candidates.toList()
        File(dir, "variants.txt").writeText(log.toString() + "built=$built shown=${variants.size}\n" +
            variants.joinToString("\n") { "${it.expression.grammarId} ${it.styleId} ${it.branchId}" } + "\n")
        variants.indices.forEach { index ->
            if (index > 0) rule.runOnIdle { session.next() }
            capture(File(dir, "chooser-%02d.png".format(index)))
            rule.runOnIdle { reviewing = true }
            capture(File(dir, "review-%02d.png".format(index)))
            rule.runOnIdle { reviewing = false }
        }
        variants.forEachIndexed { index, variant ->
            val state = CollagePresentationState(variant.expression, { id -> bytes.getValue(id).copyOf() }, spec.note)
            runBlocking { state.load() }
            rule.runOnIdle { screen = { CollagePresentationHost(state, "capsule", {}) } }
            capture(File(dir, "sheet-%02d.png".format(index)))
            val card = rule.onNodeWithTag("collage_canvas").captureToImage().asAndroidBitmap()
            File(dir, "card-%02d.png".format(index)).outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
            rule.runOnIdle { state.close() }
        }
        cropSource.close()
        holder.close()
    }

    /** `-e locale ru` renders every string resource in that language, as the per-app locale would. */
    @Composable private fun Localized(content: @Composable () -> Unit) {
        val tag = InstrumentationRegistry.getArguments().getString("locale") ?: return content()
        val base = LocalContext.current
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        // Keep the activity as base context: result launchers and back handling look it up from here.
        val resources = base.createConfigurationContext(configuration).resources
        val localized = object : ContextWrapper(base) { override fun getResources() = resources }
        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
            LocalResources provides resources, content = content)
    }

    /** Mirrors the private compose step of CreateScreen for the not-yet-chosen state. */
    @Composable private fun ComposeStep(canvas: @Composable () -> Unit, nav: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Text(stringResource(if (reviewing) R.string.create_review_title else R.string.hold_compose_title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) { canvas() }
            if (reviewing) {
                Text(stringResource(R.string.create_review_body), style = MaterialTheme.typography.bodyMedium,
                    color = HoldColors.Muted, modifier = Modifier.padding(vertical = 16.dp))
                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.create_attach_capsule), Modifier.weight(1f), textAlign = TextAlign.Start)
                    Text("→", style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.create_back_to_expression))
                }
                return@Column
            }
            nav()
            Text(stringResource(R.string.hold_crop_hint), textAlign = TextAlign.Center,
                color = HoldColors.Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.create_this_one), Modifier.weight(1f), textAlign = TextAlign.Start)
                Text("→", style = MaterialTheme.typography.titleLarge)
            }
        }
    }

    private fun capture(file: File) {
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object { const val SENDER = "mira" }
}
