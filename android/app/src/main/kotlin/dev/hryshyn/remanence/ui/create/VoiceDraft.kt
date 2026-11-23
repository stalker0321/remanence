package dev.hryshyn.remanence.ui.create

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.crypto.ManifestVoice
import dev.hryshyn.remanence.ui.capsule.MediaVoicePlayer
import dev.hryshyn.remanence.ui.capsule.VoicePlayer
import dev.hryshyn.remanence.ui.capsule.VoiceSound
import dev.hryshyn.remanence.ui.collage.SongBand
import dev.hryshyn.remanence.ui.hold.HoldColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The two tabs of the sound section (ADR-021): a song or a voice note. */
@Composable
internal fun SoundTabs(voiceTab: Boolean, onVoiceTab: (Boolean) -> Unit) {
    Row(
        Modifier.padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp)).background(HoldColors.Field).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SoundTab(stringResource(R.string.create_sound_song), !voiceTab, "create_sound_song") { onVoiceTab(false) }
        SoundTab(stringResource(R.string.create_sound_voice), voiceTab, "create_sound_voice") { onVoiceTab(true) }
    }
}

@Composable
private fun SoundTab(label: String, chosen: Boolean, tag: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            containerColor = if (chosen) HoldColors.Paper else HoldColors.Field,
            contentColor = HoldColors.Ink,
        ),
        modifier = Modifier.heightIn(min = 44.dp).semantics { role = Role.Tab; selected = chosen }.testTag(tag),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The voice tab, the owner's look of 23 November: tap to record, tap to stop,
 * at most a minute; then listen, record again or delete. The recording
 * replaces the song. Nothing is recorded for a gift to a phone number yet.
 */
@Composable
internal fun VoiceDraft(
    voice: RecordedVoice?,
    onVoice: (RecordedVoice?) -> Unit,
    songChosen: Boolean,
    phoneGift: Boolean,
    enabled: Boolean,
) {
    if (phoneGift) {
        Text(stringResource(R.string.voice_pending_unavailable), color = HoldColors.Muted,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("voice_pending_unavailable"))
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    var measuring by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var failure by rememberSaveable { mutableStateOf<Int?>(null) }

    fun stop() {
        val current = recorder ?: return
        recorder = null
        measuring = true
        scope.launch {
            try {
                onVoice(withContext(Dispatchers.IO) { current.finish() })
            } catch (_: VoiceRecorder.VoiceTooLarge) {
                failure = R.string.voice_too_large
            } catch (_: Exception) {
                failure = R.string.voice_failed
            } finally {
                measuring = false
            }
        }
    }

    fun record() {
        failure = null
        val next = VoiceRecorder(context)
        try {
            next.start(onLimit = { scope.launch { stop() } })
            elapsed = 0L
            recorder = next
        } catch (_: Exception) {
            failure = R.string.voice_failed
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) record() else failure = R.string.voice_denied
    }
    val askAndRecord = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            record()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(recorder) {
        val current = recorder ?: return@LaunchedEffect
        while (recorder === current) {
            current.sample()
            elapsed = current.elapsedMs
            delay(VoiceRecorder.SAMPLE_MS)
        }
    }
    // Leaving the step drops a recording in progress and frees the microphone.
    DisposableEffect(Unit) { onDispose { recorder?.cancel() } }

    Column(Modifier.fillMaxWidth().testTag("voice_draft")) {
        if (songChosen && voice == null && recorder == null) {
            Text(stringResource(R.string.voice_replaces_song), color = HoldColors.Muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        }
        when {
            recorder != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = ::stop, modifier = Modifier.testTag("voice_stop")) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(HoldColors.OnAccent))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.voice_stop))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.voice_elapsed, SongBand.voiceLength(maxOf(1000, elapsed.toInt())), SongBand.voiceLength(ManifestVoice.MAX_MS)),
                    style = MaterialTheme.typography.bodyMedium, color = HoldColors.Ink, modifier = Modifier.testTag("voice_elapsed"),
                )
            }
            voice != null -> RecordedRow(voice, enabled && !measuring, onRerecord = askAndRecord, onDelete = { onVoice(null) })
            else -> Button(onClick = askAndRecord, enabled = enabled && !measuring, modifier = Modifier.testTag("voice_record")) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(HoldColors.OnAccent))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.voice_record))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(failure ?: R.string.voice_hint),
            color = if (failure != null) MaterialTheme.colorScheme.error else HoldColors.Muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(if (failure != null) "voice_failure" else "voice_hint"),
        )
    }
}

@Composable
private fun RecordedRow(voice: RecordedVoice, enabled: Boolean, onRerecord: () -> Unit, onDelete: () -> Unit) {
    val player: VoicePlayer = remember(voice) { MediaVoicePlayer(VoiceSound(voice.sound())) }
    DisposableEffect(player) { onDispose { player.release() } }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = player::toggle, modifier = Modifier.testTag("voice_listen")) {
                Text(stringResource(if (player.playing) R.string.voice_pause_own else R.string.voice_listen_own))
            }
            Spacer(Modifier.width(12.dp))
            Text(SongBand.voiceLength(voice.durationMs), style = MaterialTheme.typography.bodyMedium,
                color = HoldColors.Ink, modifier = Modifier.testTag("voice_length"))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRerecord, enabled = enabled, modifier = Modifier.testTag("voice_rerecord")) {
                Text(stringResource(R.string.voice_rerecord))
            }
            androidx.compose.material3.TextButton(onClick = onDelete, enabled = enabled, modifier = Modifier.testTag("voice_delete")) {
                Text(stringResource(R.string.voice_delete))
            }
        }
    }
}
