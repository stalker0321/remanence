package dev.hryshyn.remanence.diagnostics

import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.create.RealStillFingerprintProcessor
import dev.hryshyn.remanence.core.recognition.CaptureQualityGate
import dev.hryshyn.remanence.core.recognition.CaptureQualityInput
import dev.hryshyn.remanence.core.recognition.CaptureQualityMeter
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintExtractor
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.PerspectiveWarper
import dev.hryshyn.remanence.core.recognition.PostcardContourDetector
import dev.hryshyn.remanence.core.recognition.PostcardCropSelector
import dev.hryshyn.remanence.core.recognition.QualityReason
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.recognition.StillCapturePipeline

/**
 * Opt-in diagnostic harness for the locked postcard dataset. It is skipped
 * unless all required properties are explicitly supplied:
 *
 * - `remanence.dataset.root`: read-only extracted dataset root;
 * - `remanence.dataset.output`: disposable output directory outside this repository.
 * - `remanence.repo.root`: the canonical worktree used for safety boundaries.
 * - optional `remanence.dataset.expected-summary` and
 *   `remanence.dataset.expected-cases`: external locked-corpus oracles.
 *
 * The harness intentionally has no default dataset, output, repository, or
 * oracle. It derives the completed capture inventory from each card manifest,
 * capture-status file, and current image files; runs the production processor,
 * traces the same bounded stages for post-crop metrics, and writes redacted
 * JSONL only after internal production-agreement checks have passed. Agreement
 * is consistency evidence, not an independent truth source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptureDatasetHarnessTest {

    private val profile = RecognitionProfile.mvpOrbV1()

    private fun loadNative() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { error ->
                val alreadyLoaded = error is UnsatisfiedLinkError &&
                    error.message?.contains("already loaded") == true
                assumeTrue("desktop OpenCV natives unavailable: $error", alreadyLoaded)
            }
    }

    @Test
    fun lockedDatasetRunsProductionPipelineAndWritesRedactedJsonl() {
        val rootRaw = System.getProperty(DATASET_ROOT_PROPERTY)
        val outputRaw = System.getProperty(DATASET_OUTPUT_PROPERTY)
        val repositoryRaw = System.getProperty(REPOSITORY_ROOT_PROPERTY)
        assumeTrue(
            "dataset harness requires explicit dataset, output, and repository-root properties",
            !rootRaw.isNullOrBlank() &&
                !outputRaw.isNullOrBlank() &&
                !repositoryRaw.isNullOrBlank(),
        )
        loadNative()
        val repositoryRoot = actualWorktree(Paths.get(requireNotNull(repositoryRaw)))
        val root = existingDirectory(Paths.get(requireNotNull(rootRaw)), repositoryRoot)
        val outputDirectory = safeOutputDirectory(requireNotNull(outputRaw), root, repositoryRoot)

        val cases = datasetCases(root)
        assertTrue("completed dataset inventory must not be empty", cases.isNotEmpty())

        val processors = mapOf(
            FingerprintSide.FRONT to RealStillFingerprintProcessor(profile, FingerprintSide.FRONT),
            FingerprintSide.BACK to RealStillFingerprintProcessor(profile, FingerprintSide.BACK),
        )
        val traces = cases.map { testCase ->
            val bytes = Files.readAllBytes(testCase.path)
            try {
                val productionReasons = productionReasons(processors.getValue(testCase.side).process(bytes))
                val trace = trace(bytes, testCase.side)
                assertEquals("production agreement for ${testCase.redactedId}", productionReasons, trace.reasons)
                trace
            } finally {
                bytes.fill(0)
            }
        }

        val actualSummary = summary(traces)
        System.getProperty(EXPECTED_SUMMARY_PROPERTY)?.takeIf { it.isNotBlank() }?.let { oracleRaw ->
            assertExpectedSummary(actualSummary, oracleRaw, root, repositoryRoot)
        }
        System.getProperty(EXPECTED_CASES_PROPERTY)?.takeIf { it.isNotBlank() }?.let { oracleRaw ->
            assertExpectedCases(traces, cases, oracleRaw, root, repositoryRoot)
        }

        val output = outputDirectory.resolve(OUTPUT_FILE_NAME)
        requireSafeOutputFile(output, root, repositoryRoot)
        val records = traces.mapIndexed { index, trace -> trace.toJson(cases[index].redactedId) }
        val redactedJsonl = records.joinToString(separator = "\n", postfix = "\n")
        Files.newOutputStream(
            output,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { stream ->
            stream.write(redactedJsonl.toByteArray(Charsets.UTF_8))
        }
        assertEquals("one redacted record per input", cases.size, records.size)
        assertEquals(
            "JSONL line count",
            cases.size,
            output.toFile().readLines(Charsets.UTF_8).size,
        )
    }

    @Test
    fun malformedExtraImageIsRejectedByManifestDrivenInventory() = withFixture { root ->
        root.resolve("cards/001_sample/images/T01_extra.jpg").toFile().writeText("not a capture")

        assertThrows(IllegalArgumentException::class.java) { datasetCases(root) }
    }

    @Test
    fun missingExpectedSideIsRejectedByManifestDrivenInventory() = withFixture { root ->
        Files.delete(root.resolve("cards/001_sample/images/T01_back.jpg"))

        assertThrows(IllegalArgumentException::class.java) { datasetCases(root) }
    }

    @Test
    fun symlinkedOutputAncestorIsRejectedBeforeCreation() = withTemporaryDirectory { root ->
        val datasetRoot = root.resolve("dataset")
        Files.createDirectories(datasetRoot)
        val repositoryRoot = testWorktree(root.resolve("worktree"))
        val realParent = root.resolve("real-parent")
        Files.createDirectories(realParent)
        val symlinkParent = root.resolve("symlink-parent")
        Files.createSymbolicLink(symlinkParent, realParent)

        assertThrows(IllegalArgumentException::class.java) {
            safeOutputDirectory(
                symlinkParent.resolve("analysis").toString(),
                datasetRoot,
                repositoryRoot,
            )
        }
    }

    @Test
    fun moduleCwdOutputIsRejectedAsRepositoryDescendant() = withTemporaryDirectory { root ->
        val datasetRoot = root.resolve("dataset")
        Files.createDirectories(datasetRoot)
        val repositoryRoot = testWorktree(root.resolve("worktree"))
        assertThrows(IllegalArgumentException::class.java) {
            safeOutputDirectory("android/build/capture-diagnostics-test", datasetRoot, repositoryRoot)
        }
    }

    @Test
    fun externalRepositoryRootIsUsedIndependentOfProcessCwd() = withTemporaryDirectory { root ->
        val repositoryRoot = testWorktree(root.resolve("external-worktree"))
        val datasetRoot = root.resolve("dataset")
        Files.createDirectories(datasetRoot)

        assertThrows(IllegalArgumentException::class.java) {
            safeOutputDirectory(
                repositoryRoot.resolve("android/build/capture-diagnostics-test").toString(),
                datasetRoot,
                repositoryRoot,
            )
        }
    }

    @Test
    fun checkedStatusMustCloseOverImportedAndSkippedTasks() = withFixture { root ->
        root.resolve("cards/001_sample/capture_status.json").toFile().writeText(
            """
            {"checked_tasks":[],"imported_tasks":[],"skipped_or_not_done":[]}
            """.trimIndent(),
        )

        assertThrows(IllegalArgumentException::class.java) { datasetCases(root) }
    }

    @Test
    fun checkedStatusRejectsTaskInBothImportedAndSkipped() = withFixture { root ->
        root.resolve("cards/001_sample/capture_status.json").toFile().writeText(
            """
            {"checked_tasks":["T01"],"imported_tasks":["T01"],"skipped_or_not_done":["T01"]}
            """.trimIndent(),
        )

        assertThrows(IllegalArgumentException::class.java) { datasetCases(root) }
    }

    @Test
    fun wrongExpectedCaseReasonOracleIsRejected() = withTemporaryDirectory { root ->
        val repositoryRoot = testWorktree(root.resolve("worktree"))
        withTemporaryDirectory { external ->
            val datasetRoot = root.resolve("dataset")
            Files.createDirectories(datasetRoot)
            val oracle = external.resolve("expected-cases.jsonl")
            oracle.toFile().writeText(
                """
                {"case":"001/T01/front","outcome":"REJECTED","fallback":null,"reasons":["TOO_DARK"]}
                """.trimIndent(),
            )

            val testCase = DatasetCase(
                path = datasetRoot.resolve("not-read.jpg"),
                cardId = "001",
                cardNumber = 1,
                task = "T01",
                side = FingerprintSide.FRONT,
                redactedId = "001/T01/front",
            )
            assertThrows(AssertionError::class.java) {
                assertExpectedCases(
                    traces = listOf(TraceResult.rejected(CROP_REASON)),
                    cases = listOf(testCase),
                    oracleRaw = oracle.toString(),
                    datasetRoot = datasetRoot,
                    repositoryRoot = repositoryRoot,
                )
            }
        }
    }

    @Test
    fun wrongExpectedSummaryOracleIsRejected() = withTemporaryDirectory { root ->
        val repositoryRoot = testWorktree(root.resolve("worktree"))
        withTemporaryDirectory { external ->
            val datasetRoot = root.resolve("dataset")
            Files.createDirectories(datasetRoot)
            val oracle = external.resolve("expected-summary.json")
            oracle.toFile().writeText(
                """
                {"caseCount":110,"uniqueCases":110,"accepted":68,"rejected":42,"fallback":96,"tooBlurry":30,"tooDark":15}
                """.trimIndent(),
            )

            assertThrows(AssertionError::class.java) {
                assertExpectedSummary(
                    DatasetSummary(110, 110, 69, 41, 96, 30, 15),
                    oracle.toString(),
                    datasetRoot,
                    repositoryRoot,
                )
            }
        }
    }

    private fun datasetCases(root: Path): List<DatasetCase> {
        val manifests = Files.walk(root).use { paths ->
            paths.iterator().asSequence()
                .filter { path ->
                    val relative = relativePath(root, path)
                    MANIFEST_PATH_PATTERN.matches(relative)
                }
                .map { path -> parseManifest(root, path) }
                .toList()
        }

        if (manifests.isEmpty()) failInventory("no card manifests found")
        if (manifests.map { it.cardId }.toSet().size != manifests.size ||
            manifests.map { it.folder }.toSet().size != manifests.size
        ) {
            failInventory("duplicate card manifest identity")
        }
        val expected = linkedMapOf<String, DatasetCase>()
        manifests.forEach { manifest ->
            val completedTasks = completedTasks(
                root.resolve("cards/${manifest.folder}/capture_status.json"),
                manifest.tasks.keys,
            )
            completedTasks.forEach { task ->
                val sides = manifest.tasks[task] ?: failInventory("status names an unknown task")
                if (sides != setOf(FingerprintSide.FRONT, FingerprintSide.BACK)) {
                    failInventory("completed task is not a front/back pair")
                }
                sides.forEach { side ->
                    val key = caseKey(manifest.cardId, task, side)
                    val image = root.resolve(
                        "cards/${manifest.folder}/images/${task}_${sideName(side)}.jpg",
                    )
                    val newCase = DatasetCase(
                        path = image,
                        cardId = manifest.cardId,
                        cardNumber = manifest.cardNumber,
                        task = task,
                        side = side,
                        redactedId = "$key",
                    )
                    if (expected.put(key, newCase) != null) {
                        failInventory("duplicate completed capture key")
                    }
                }
            }
        }

        val actual = actualCaptureFiles(root, manifests)
        if (actual.keys != expected.keys) failInventory("current files do not match completed captures")
        expected.forEach { (key, testCase) ->
            if (!Files.isRegularFile(testCase.path, LinkOption.NOFOLLOW_LINKS)) {
                failInventory("expected capture is not a regular file")
            }
            if (actual[key] != testCase.path) failInventory("capture path does not match its manifest")
        }
        return expected.values.sortedWith(compareBy({ it.cardNumber }, { it.task }, { it.side.name }))
    }

    private fun parseManifest(root: Path, path: Path): ManifestSpec {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            failInventory("manifest is not a regular file")
        }
        val relative = relativePath(root, path)
        val match = MANIFEST_PATH_PATTERN.matchEntire(relative) ?: failInventory("invalid manifest path")
        val folder = match.groupValues[1]
        val json = JSONObject(path.toFile().readText(Charsets.UTF_8))
        val cardId = json.getString("card_id")
        val slug = json.getString("slug")
        val cardNumber = cardId.toIntOrNull() ?: failInventory("invalid card id")
        if (!CARD_ID_PATTERN.matches(cardId) || folder != "${cardId}_$slug") {
            failInventory("manifest identity does not match its folder")
        }
        val taskArray = json.getJSONArray("tasks")
        val tasks = linkedMapOf<String, Set<FingerprintSide>>()
        for (index in 0 until taskArray.length()) {
            val task = taskArray.getJSONObject(index)
            val taskId = task.getString("id")
            if (!TASK_ID_PATTERN.matches(taskId) || tasks.containsKey(taskId)) {
                failInventory("invalid or duplicate task id")
            }
            val sides = parseSides(task.getJSONArray("sides"))
            if (sides != setOf(FingerprintSide.FRONT, FingerprintSide.BACK)) {
                failInventory("manifest task is not a complete front/back pair")
            }
            tasks[taskId] = sides
        }
        val referenceImage = json.optJSONObject("source")
            ?.optString("reference_image")
            ?.takeIf { it.isNotBlank() }
            ?: failInventory("manifest has no reference image")
        val referencePath = Paths.get(referenceImage)
        if (referencePath.nameCount != 1 || referencePath.fileName.toString() != referenceImage) {
            failInventory("manifest reference image is not a leaf name")
        }
        return ManifestSpec(folder, cardId, cardNumber, tasks, referenceImage)
    }

    private fun parseSides(array: JSONArray): Set<FingerprintSide> {
        val sides = linkedSetOf<FingerprintSide>()
        for (index in 0 until array.length()) {
            val side = when (array.getString(index)) {
                "front" -> FingerprintSide.FRONT
                "back" -> FingerprintSide.BACK
                else -> failInventory("manifest contains an unknown side")
            }
            if (!sides.add(side)) failInventory("manifest contains a duplicate side")
        }
        return sides
    }

    private fun completedTasks(root: Path, manifestTaskIds: Set<String>): Set<String> {
        val statusPath = root
        if (!Files.exists(statusPath, LinkOption.NOFOLLOW_LINKS)) return emptySet()
        if (Files.isSymbolicLink(statusPath) || !Files.isRegularFile(statusPath, LinkOption.NOFOLLOW_LINKS)) {
            failInventory("capture status is not a regular file")
        }
        val json = JSONObject(statusPath.toFile().readText(Charsets.UTF_8))
        val checked = jsonStringArray(json, "checked_tasks")
        val imported = jsonStringArray(json, "imported_tasks")
        val skipped = jsonStringArray(json, "skipped_or_not_done")
        if (checked.size != checked.toSet().size ||
            imported.size != imported.toSet().size ||
            skipped.size != skipped.toSet().size
        ) {
            failInventory("capture status contains duplicate tasks")
        }
        if (checked.any { it !in manifestTaskIds } ||
            imported.any { it !in manifestTaskIds } ||
            skipped.any { it !in manifestTaskIds }
        ) {
            failInventory("capture status names an unknown task")
        }
        val checkedSet = checked.toSet()
        val importedSet = imported.toSet()
        val skippedSet = skipped.toSet()
        // This dataset schema uses checked_tasks for completed/attempted tasks;
        // its exact closure is manifest tasks = checked_tasks disjoint-union
        // skipped_or_not_done, with imported_tasks a subset of checked_tasks.
        if (!imported.all(checked::contains) ||
            importedSet.intersect(skippedSet).isNotEmpty() ||
            checkedSet.intersect(skippedSet).isNotEmpty() ||
            checkedSet + skippedSet != manifestTaskIds
        ) {
            failInventory("capture status is inconsistent")
        }
        return importedSet
    }

    private fun jsonStringArray(json: JSONObject, name: String): List<String> {
        val array = json.optJSONArray(name) ?: failInventory("capture status field is not an array")
        return (0 until array.length()).map { index -> array.getString(index) }
    }

    private fun actualCaptureFiles(root: Path, manifests: List<ManifestSpec>): Map<String, Path> {
        val actual = linkedMapOf<String, Path>()
        val allowedReferences = manifests.map { "cards/${it.folder}/${it.referenceImage}" }.toSet()
        Files.walk(root).use { paths ->
            paths.iterator().asSequence().forEach { path ->
                val relative = relativePath(root, path)
                if (!IMAGE_PATH_PATTERN.matches(relative)) return@forEach
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    failInventory("image is not a regular file")
                }
                if (relative in allowedReferences) {
                    return@forEach
                }
                if (REFERENCE_PATH_PATTERN.matches(relative)) failInventory("unrecognized reference image")
                val match = CAPTURE_PATH_PATTERN.matchEntire(relative)
                    ?: failInventory("unrecognized image path")
                val cardId = match.groupValues[1].substringBefore('_')
                val task = match.groupValues[2]
                val side = when (match.groupValues[3]) {
                    "front" -> FingerprintSide.FRONT
                    "back" -> FingerprintSide.BACK
                    else -> failInventory("unrecognized capture side")
                }
                val key = caseKey(cardId, task, side)
                if (actual.put(key, path) != null) failInventory("duplicate current capture key")
            }
        }
        return actual
    }

    private fun relativePath(root: Path, path: Path): String =
        root.relativize(path).toString().replace('\u005c', '/')

    private fun caseKey(cardId: String, task: String, side: FingerprintSide): String =
        "$cardId/$task/${sideName(side)}"

    private fun sideName(side: FingerprintSide): String = when (side) {
        FingerprintSide.FRONT -> "front"
        FingerprintSide.BACK -> "back"
    }

    private fun failInventory(message: String): Nothing =
        throw IllegalArgumentException("dataset inventory rejected: $message")

    private fun actualWorktree(raw: Path): Path {
        if (!raw.isAbsolute) failInventory("repository root must be absolute")
        val path = canonicalExistingPath(raw, raw)
        val settingsKts = path.resolve("android/settings.gradle.kts")
        val settingsGroovy = path.resolve("android/settings.gradle")
        if ((!Files.isRegularFile(settingsKts, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(settingsGroovy, LinkOption.NOFOLLOW_LINKS)) ||
            Files.isSymbolicLink(path.resolve("android"))
        ) {
            failInventory("repository root is not an Android worktree")
        }
        val dotGit = path.resolve(".git")
        if (Files.isSymbolicLink(dotGit) ||
            (!Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS))
        ) {
            failInventory("repository root has no usable .git entry")
        }
        gitCommonDirectory(path)
        return path
    }

    private fun testWorktree(root: Path): Path {
        Files.createDirectories(root.resolve("android"))
        root.resolve("android/settings.gradle.kts").toFile().writeText("// test worktree")
        Files.createDirectories(root.resolve(".git"))
        return actualWorktree(root)
    }

    private fun existingDirectory(raw: Path, base: Path): Path {
        val path = canonicalExistingPath(raw, base)
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) failInventory("path is not a directory")
        return path
    }

    private fun safeOutputDirectory(raw: String, datasetRoot: Path, repositoryRoot: Path): Path {
        val rawPath = try {
            Paths.get(raw)
        } catch (_: RuntimeException) {
            failInventory("output path is invalid")
        }
        val gitCommonDirectory = gitCommonDirectory(repositoryRoot)
        val candidate = canonicalDirectoryForCreation(rawPath, repositoryRoot)
        rejectBoundary(candidate, datasetRoot, repositoryRoot, gitCommonDirectory)
        createDirectoriesNoFollow(candidate)
        val verified = existingDirectory(candidate, repositoryRoot)
        rejectBoundary(verified, datasetRoot, repositoryRoot, gitCommonDirectory)
        return verified
    }

    private fun requireSafeOutputFile(path: Path, datasetRoot: Path, repositoryRoot: Path) {
        if (!path.isAbsolute) failInventory("output file must be absolute")
        val output = path.normalize()
        val parent = output.parent ?: failInventory("output has no parent")
        val canonicalParent = existingDirectory(parent, repositoryRoot)
        if (canonicalParent != parent) failInventory("output parent is not canonical")
        if (Files.isSymbolicLink(output)) failInventory("output file is a symlink")
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
        ) {
            failInventory("output file is not regular")
        }
        rejectBoundary(output, datasetRoot, repositoryRoot, gitCommonDirectory(repositoryRoot))
    }

    private fun canonicalExistingPath(raw: Path, base: Path): Path {
        val resolved = if (raw.isAbsolute) raw else base.resolve(raw)
        if (!resolved.isAbsolute) failInventory("path cannot be resolved without process CWD")
        val absolute = resolved.normalize()
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) failInventory("path does not exist")
        var current: Path? = absolute
        var leaf = true
        while (current != null) {
            val path = current
            if (Files.isSymbolicLink(path)) failInventory("path contains a symlink")
            if (leaf) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                ) {
                    failInventory("path leaf is not usable")
                }
            } else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                failInventory("path ancestor is not a directory")
            }
            leaf = false
            current = path.parent
        }
        return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
    }

    private fun canonicalDirectoryForCreation(raw: Path, base: Path): Path {
        val resolved = if (raw.isAbsolute) raw else base.resolve(raw)
        if (!resolved.isAbsolute) failInventory("output cannot be resolved without process CWD")
        val absolute = resolved.normalize()
        val missing = ArrayList<String>()
        var current = absolute
        while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            val name = current.fileName ?: failInventory("output path has no usable parent")
            missing += name.toString()
            current = current.parent ?: failInventory("output path has no usable parent")
        }
        if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            failInventory("output ancestor is unsafe")
        }
        checkDirectoryAncestors(current)
        var canonical = current.toRealPath(LinkOption.NOFOLLOW_LINKS)
        missing.asReversed().forEach { canonical = canonical.resolve(it) }
        return canonical
    }

    private fun createDirectoriesNoFollow(target: Path) {
        val missing = ArrayList<String>()
        var current = target
        while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            val name = current.fileName ?: failInventory("output path has no usable parent")
            missing += name.toString()
            current = current.parent ?: failInventory("output path has no usable parent")
        }
        checkDirectoryAncestors(current)
        missing.asReversed().forEach { name ->
            val next = current.resolve(name)
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(next)
                } catch (_: FileAlreadyExistsException) {
                    // Recheck below; a concurrent replacement is rejected.
                }
            }
            if (Files.isSymbolicLink(next) || !Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
                failInventory("output component is unsafe")
            }
            current = next
        }
    }

    private fun checkDirectoryAncestors(start: Path) {
        var current: Path? = start
        while (current != null) {
            val path = current
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                failInventory("output ancestor is unsafe")
            }
            current = path.parent
        }
    }

    private fun gitCommonDirectory(repositoryRoot: Path): Path {
        val dotGit = repositoryRoot.resolve(".git")
        if (Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            return existingDirectory(dotGit, repositoryRoot)
        }
        if (!Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            failInventory("repository .git entry is not usable")
        }
        val line = dotGit.toFile().readLines(Charsets.UTF_8).firstOrNull()
            ?: failInventory("repository .git pointer is empty")
        if (!line.startsWith("gitdir:")) failInventory("repository .git pointer is invalid")
        val raw = try {
            Paths.get(line.removePrefix("gitdir:").trim())
        } catch (_: RuntimeException) {
            failInventory("repository .git pointer path is invalid")
        }
        val gitDirectory = canonicalExistingPath(raw, repositoryRoot)
        if (!Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS)) {
            failInventory("repository git directory is not a directory")
        }
        val common = gitDirectory.parent?.parent
            ?: failInventory("repository git common directory is unavailable")
        return existingDirectory(common, repositoryRoot)
    }

    private fun rejectBoundary(candidate: Path, datasetRoot: Path, vararg boundaries: Path?) {
        if (!candidate.isAbsolute) failInventory("boundary path must be absolute")
        val normalized = candidate.normalize()
        val allBoundaries = listOf(datasetRoot) + boundaries
        if (allBoundaries.filterNotNull().any { boundary ->
                if (!boundary.isAbsolute) failInventory("boundary root must be absolute")
                val normalizedBoundary = boundary.normalize()
                normalized == normalizedBoundary || normalized.startsWith(normalizedBoundary)
            }
        ) {
            failInventory("path is inside a protected boundary")
        }
    }

    private fun assertExpectedSummary(
        actual: DatasetSummary,
        oracleRaw: String,
        datasetRoot: Path,
        repositoryRoot: Path,
    ) {
        val oraclePath = canonicalExistingPath(
            Paths.get(oracleRaw),
            repositoryRoot,
        )
        if (!Files.isRegularFile(oraclePath, LinkOption.NOFOLLOW_LINKS)) {
            failInventory("summary oracle is not a regular file")
        }
        rejectBoundary(oraclePath, datasetRoot, repositoryRoot, gitCommonDirectory(repositoryRoot))
        val json = JSONObject(oraclePath.toFile().readText(Charsets.UTF_8))
        val expectedKeys = setOf("caseCount", "uniqueCases", "accepted", "rejected", "fallback", "tooBlurry", "tooDark")
        val actualKeys = json.keys().asSequence().toSet()
        if (actualKeys != expectedKeys) failInventory("summary oracle shape is invalid")
        val expected = DatasetSummary(
            caseCount = jsonInt(json, "caseCount"),
            uniqueCases = jsonInt(json, "uniqueCases"),
            accepted = jsonInt(json, "accepted"),
            rejected = jsonInt(json, "rejected"),
            fallback = jsonInt(json, "fallback"),
            tooBlurry = jsonInt(json, "tooBlurry"),
            tooDark = jsonInt(json, "tooDark"),
        )
        assertEquals("dataset summary oracle", expected, actual)
    }

    private fun assertExpectedCases(
        traces: List<TraceResult>,
        cases: List<DatasetCase>,
        oracleRaw: String,
        datasetRoot: Path,
        repositoryRoot: Path,
    ) {
        val oraclePath = canonicalExistingPath(Paths.get(oracleRaw), repositoryRoot)
        if (!Files.isRegularFile(oraclePath, LinkOption.NOFOLLOW_LINKS)) {
            failInventory("case oracle is not a regular file")
        }
        rejectBoundary(oraclePath, datasetRoot, repositoryRoot, gitCommonDirectory(repositoryRoot))
        val expected = linkedMapOf<String, CaseExpectation>()
        Files.readAllLines(oraclePath, Charsets.UTF_8).forEachIndexed { index, line ->
            if (line.isBlank()) failInventory("case oracle contains a blank line")
            val json = JSONObject(line)
            val keys = json.keys().asSequence().toSet()
            if (keys != setOf("case", "outcome", "fallback", "reasons")) {
                failInventory("case oracle shape is invalid at line ${index + 1}")
            }
            val case = json.optString("case").takeIf { it.isNotBlank() }
                ?: failInventory("case oracle has an empty case at line ${index + 1}")
            val expectation = CaseExpectation(
                outcome = when (val outcome = json.optString("outcome")) {
                    "ACCEPTED", "REJECTED" -> outcome
                    else -> failInventory("case oracle has an invalid outcome at line ${index + 1}")
                },
                fallback = jsonBooleanOrNull(json, "fallback", index + 1),
                reasons = jsonReasons(json, index + 1),
            )
            if (expected.put(case, expectation) != null) {
                failInventory("case oracle contains a duplicate case")
            }
        }

        if (traces.size != cases.size) failInventory("trace and case counts differ")
        val actual = linkedMapOf<String, CaseExpectation>()
        cases.zip(traces).forEach { (testCase, trace) ->
            actual[testCase.redactedId] = CaseExpectation(
                outcome = if (trace.accepted) "ACCEPTED" else "REJECTED",
                fallback = trace.fallback,
                reasons = trace.reasons,
            )
        }
        assertEquals("case oracle keys", expected.keys, actual.keys)
        actual.forEach { (case, value) ->
            assertEquals("case oracle expectation for $case", expected[case], value)
        }
    }

    private fun jsonBooleanOrNull(json: JSONObject, name: String, line: Int): Boolean? {
        if (!json.has(name)) failInventory("case oracle is missing $name at line $line")
        return when (val value = json.opt(name)) {
            JSONObject.NULL -> null
            is Boolean -> value
            null -> failInventory("case oracle has an absent $name at line $line")
            else -> failInventory("case oracle has an invalid $name at line $line")
        }
    }

    private fun jsonReasons(json: JSONObject, line: Int): Set<QualityReason> {
        val array = json.optJSONArray("reasons")
            ?: failInventory("case oracle reasons is not an array at line $line")
        val reasons = linkedSetOf<QualityReason>()
        for (index in 0 until array.length()) {
            val name = array.optString(index).takeIf { it.isNotBlank() }
                ?: failInventory("case oracle has an invalid reason at line $line")
            val reason = runCatching { QualityReason.valueOf(name) }
                .getOrElse { failInventory("case oracle has an unknown reason at line $line") }
            if (!reasons.add(reason)) failInventory("case oracle repeats a reason at line $line")
        }
        return reasons
    }

    private fun jsonInt(json: JSONObject, name: String): Int {
        val value = json.opt(name)
        if (value !is Number || value.toDouble() != value.toInt().toDouble()) {
            failInventory("summary oracle value is not an integer")
        }
        return value.toInt()
    }

    private fun summary(traces: List<TraceResult>): DatasetSummary = DatasetSummary(
        caseCount = traces.size,
        uniqueCases = traces.size,
        accepted = traces.count { it.accepted },
        rejected = traces.count { !it.accepted },
        fallback = traces.count { it.fallback == true },
        tooBlurry = traces.count { QualityReason.TOO_BLURRY in it.reasons },
        tooDark = traces.count { QualityReason.TOO_DARK in it.reasons },
    )

    private data class DatasetSummary(
        val caseCount: Int,
        val uniqueCases: Int,
        val accepted: Int,
        val rejected: Int,
        val fallback: Int,
        val tooBlurry: Int,
        val tooDark: Int,
    )

    private data class CaseExpectation(
        val outcome: String,
        val fallback: Boolean?,
        val reasons: Set<QualityReason>,
    )

    private data class ManifestSpec(
        val folder: String,
        val cardId: String,
        val cardNumber: Int,
        val tasks: Map<String, Set<FingerprintSide>>,
        val referenceImage: String,
    )

    private fun withFixture(block: (Path) -> Unit) = withTemporaryDirectory { root ->
        val card = root.resolve("cards/001_sample")
        Files.createDirectories(card.resolve("images"))
        card.resolve("manifest.json").toFile().writeText(
            """
            {"schema_version":"0.1","dataset_plan_version":"test","card_id":"001","slug":"sample","source":{"reference_image":"reference.jpg"},"tasks":[{"id":"T01","sides":["front","back"]}]}
            """.trimIndent(),
        )
        card.resolve("capture_status.json").toFile().writeText(
            """
            {"checked_tasks":["T01"],"imported_tasks":["T01"],"skipped_or_not_done":[]}
            """.trimIndent(),
        )
        card.resolve("images/T01_front.jpg").toFile().writeText("front")
        card.resolve("images/T01_back.jpg").toFile().writeText("back")
        block(root)
    }

    private fun withTemporaryDirectory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("capture-diagnostics-test-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun productionReasons(result: ProcessedStill): Set<QualityReason> = when (result) {
        is ProcessedStill.Accepted -> {
            result.serializedBytes.fill(0)
            emptySet()
        }
        is ProcessedStill.Rejected -> result.reasons
    }

    /** Repeats only the actual production stages to expose post-crop metrics. */
    private fun trace(bytes: ByteArray, side: FingerprintSide): TraceResult {
        val pipeline = StillCapturePipeline()
        val detector = PostcardContourDetector(profile)
        val cropSelector = PostcardCropSelector(profile)
        val warper = PerspectiveWarper(profile)
        val meter = CaptureQualityMeter()
        val gate = CaptureQualityGate(profile)
        val extractor = FingerprintExtractor(profile)
        val working = try {
            pipeline.process(bytes)
        } catch (_: IllegalArgumentException) {
            return TraceResult.rejected(CROP_REASON)
        }
        try {
            val pixels = working.copyArgbPixels()
            try {
                val selection = cropSelector.select(
                    candidates = detector.detect(pixels, working.width, working.height),
                    frameWidth = working.width,
                    frameHeight = working.height,
                )
                val candidate = selection.candidate
                val warped = try {
                    warper.warp(pixels, working.width, working.height, candidate.corners)
                } catch (_: IllegalArgumentException) {
                    return TraceResult.rejected(
                        reasons = CROP_REASON,
                        fallback = selection.usedGuideFallback,
                    )
                }
                try {
                    val signals = meter.measure(warped.pixels, warped.width, warped.height)
                    val qualityInput = CaptureQualityInput(
                        signals = signals,
                        detectedAreaRatio = candidate.areaRatio,
                        rectangularity = candidate.rectangularity,
                        cropAspectRatio = warped.width.toDouble() / warped.height.toDouble(),
                        croppedShortEdgePx = minOf(warped.width, warped.height),
                    )
                    val reasons = gate.evaluate(qualityInput)
                    if (reasons.isNotEmpty()) {
                        return TraceResult(
                            accepted = false,
                            reasons = reasons,
                            fallback = selection.usedGuideFallback,
                            width = warped.width,
                            height = warped.height,
                            candidateAreaRatio = candidate.areaRatio,
                            rectangularity = candidate.rectangularity,
                            cropAspectRatio = qualityInput.cropAspectRatio,
                            shortEdgePx = qualityInput.croppedShortEdgePx,
                            signals = signals,
                        )
                    }

                    val fingerprint = extractor.extract(
                        warpedArgb = warped.pixels,
                        width = warped.width,
                        height = warped.height,
                        side = side,
                    )
                    try {
                        if (fingerprint.keypoints.isEmpty() ||
                            fingerprint.descriptors.size != fingerprint.keypoints.size
                        ) {
                            return TraceResult(
                                accepted = false,
                                reasons = setOf(QualityReason.FEATURES_INSUFFICIENT),
                                fallback = selection.usedGuideFallback,
                                width = warped.width,
                                height = warped.height,
                                candidateAreaRatio = candidate.areaRatio,
                                rectangularity = candidate.rectangularity,
                                cropAspectRatio = qualityInput.cropAspectRatio,
                                shortEdgePx = qualityInput.croppedShortEdgePx,
                                signals = signals,
                                orbKeypoints = fingerprint.keypoints.size,
                            )
                        }
                        val serialized = FingerprintCodec.serialize(fingerprint)
                        serialized.fill(0)
                        return TraceResult(
                            accepted = true,
                            reasons = emptySet(),
                            fallback = selection.usedGuideFallback,
                            width = warped.width,
                            height = warped.height,
                            candidateAreaRatio = candidate.areaRatio,
                            rectangularity = candidate.rectangularity,
                            cropAspectRatio = qualityInput.cropAspectRatio,
                            shortEdgePx = qualityInput.croppedShortEdgePx,
                            signals = signals,
                            orbKeypoints = fingerprint.keypoints.size,
                        )
                    } finally {
                        fingerprint.descriptors.forEach { it.fill(0) }
                    }
                } finally {
                    warped.pixels.fill(0)
                }
            } finally {
                pixels.fill(0)
            }
        } finally {
            working.close()
        }
    }

    private data class DatasetCase(
        val path: Path,
        val cardId: String,
        val cardNumber: Int,
        val task: String,
        val side: FingerprintSide,
        val redactedId: String,
    )

    private data class TraceResult(
        val accepted: Boolean,
        val reasons: Set<QualityReason>,
        val fallback: Boolean? = null,
        val width: Int? = null,
        val height: Int? = null,
        val candidateAreaRatio: Double? = null,
        val rectangularity: Double? = null,
        val cropAspectRatio: Double? = null,
        val shortEdgePx: Int? = null,
        val signals: dev.hryshyn.remanence.core.recognition.CaptureQualitySignals? = null,
        val orbKeypoints: Int? = null,
    ) {
        fun toJson(redactedId: String): String = buildString {
            append("{\"case\":\"").append(redactedId).append("\",\"outcome\":\"")
                .append(if (accepted) "ACCEPTED" else "REJECTED")
                .append("\",\"productionAgreement\":\"MATCH\",\"fallback\":")
                .append(fallback?.toString() ?: "null")
                .append(",\"metrics\":{")
                .append("\"width\":").append(width ?: "null")
                .append(",\"height\":").append(height ?: "null")
                .append(",\"detectedAreaRatio\":").append(number(candidateAreaRatio))
                .append(",\"rectangularity\":").append(number(rectangularity))
                .append(",\"cropAspectRatio\":").append(number(cropAspectRatio))
                .append(",\"shortEdgePx\":").append(shortEdgePx ?: "null")
                .append(",\"laplacianVariance\":").append(number(signals?.laplacianVariance))
                .append(",\"nearBlackFraction\":").append(number(signals?.nearBlackFraction))
                .append(",\"clippedWhiteFraction\":").append(number(signals?.clippedWhiteFraction))
                .append(",\"largestGlareFraction\":").append(number(signals?.largestGlareFraction))
                .append(",\"orbKeypoints\":").append(orbKeypoints ?: "null")
                .append("},\"reasons\":[")
                .append(reasons.sortedBy { it.name }.joinToString(",") { "\"${it.name}\"" })
                .append("]}")
        }

        companion object {
            fun rejected(reasons: Set<QualityReason>, fallback: Boolean? = null) = TraceResult(
                accepted = false,
                reasons = reasons,
                fallback = fallback,
            )

            private fun number(value: Double?): String = value
                ?.takeIf { it.isFinite() }
                ?.let { String.format(Locale.US, "%.6f", it) }
                ?: "null"
        }
    }

    private companion object {
        const val DATASET_ROOT_PROPERTY = "remanence.dataset.root"
        const val DATASET_OUTPUT_PROPERTY = "remanence.dataset.output"
        const val REPOSITORY_ROOT_PROPERTY = "remanence.repo.root"
        const val EXPECTED_SUMMARY_PROPERTY = "remanence.dataset.expected-summary"
        const val EXPECTED_CASES_PROPERTY = "remanence.dataset.expected-cases"
        const val OUTPUT_FILE_NAME = "capture-diagnostics.jsonl"
        val CARD_ID_PATTERN = Regex("\\d{3}")
        val TASK_ID_PATTERN = Regex("T\\d{2}")
        val MANIFEST_PATH_PATTERN = Regex("^cards/([^/]+)/manifest\\.json$")
        val IMAGE_PATH_PATTERN = Regex("^.*\\.(?i:jpg|jpeg|png|webp)$")
        val REFERENCE_PATH_PATTERN = Regex("^cards/[^/]+/reference\\.jpg$")
        val CAPTURE_PATH_PATTERN = Regex("^cards/(\\d{3}_[^/]+)/images/(T\\d{2})_(front|back)\\.jpg$")
        val CROP_REASON = setOf(QualityReason.CROP_UNCERTAIN)
    }
}
