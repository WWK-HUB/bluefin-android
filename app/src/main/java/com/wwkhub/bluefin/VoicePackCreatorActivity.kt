package com.wwkhub.bluefin

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.wwkhub.bluefin.voice.audio.AudioImportError
import com.wwkhub.bluefin.voice.audio.AudioTranscoder
import com.wwkhub.bluefin.voice.audio.ReferenceRecorder
import com.wwkhub.bluefin.voice.audio.TranscodeResult
import com.wwkhub.bluefin.voice.audio.VoicePackDraft
import com.wwkhub.bluefin.voice.audio.WavWriter
import java.io.File
import java.util.Random
import java.util.concurrent.Executors

/**
 * Builds a voice pack from a clip the user records or picks.
 *
 * The pack that comes out is byte-for-byte the shape a zip import produces —
 * `voice.json` + `reference.wav` installed through [VoicePackRepository.install]
 * — so the existing picker, preview and cloud clone paths all work on it with
 * no extra wiring.
 */
class VoicePackCreatorActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bluefin-voice-pack-create")
    }
    private val random = Random()

    private lateinit var nameInput: TextInputEditText
    private lateinit var aboutInput: TextInputEditText
    private lateinit var textInput: TextInputEditText
    private lateinit var clipStatus: TextView
    private lateinit var recordButton: android.widget.Button
    private lateinit var pickButton: android.widget.Button
    private lateinit var previewButton: android.widget.Button
    private lateinit var saveButton: android.widget.Button

    /** Transient WAV for the current candidate clip. */
    private var clipFile: File? = null
    private var clipSeconds = 0.0
    private var clipTruncated = false
    private var player: MediaPlayer? = null
    private var recorder: ReferenceRecorder? = null
    private var busy = false
    private var saveId: String? = null

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importReference(uri)
    }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startRecording()
        } else {
            toast(getString(R.string.voice_create_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_pack_creator)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val padding = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }

        nameInput = findViewById(R.id.voice_create_name)
        aboutInput = findViewById(R.id.voice_create_about)
        textInput = findViewById(R.id.voice_create_text)
        clipStatus = findViewById(R.id.voice_create_clip_status)
        recordButton = findViewById(R.id.voice_create_record)
        pickButton = findViewById(R.id.voice_create_pick)
        previewButton = findViewById(R.id.voice_create_preview)
        saveButton = findViewById(R.id.voice_create_save)

        findViewById<View>(R.id.voice_create_close).setOnClickListener { finish() }
        recordButton.setOnClickListener { onRecordClicked() }
        pickButton.setOnClickListener { pickClip() }
        previewButton.setOnClickListener { playClip() }
        saveButton.setOnClickListener { save() }

        savedInstanceState?.let { state ->
            saveId = state.getString("saveId")
            if (saveId?.let { VoicePackRepository.find(this, it) } != null) {
                setResult(Activity.RESULT_OK)
                finish()
                return
            }
            val name = state.getString("clip")
            if (name != null && name.matches(Regex("voice-create-[0-9]+\\.wav"))) {
                clipFile = File(cacheDir, name).takeIf { it.isFile }
                clipSeconds = state.getDouble("clipSeconds")
                clipTruncated = state.getBoolean("clipTruncated")
            }
            if (state.getBoolean("recording")) toast(getString(R.string.voice_create_record_interrupted))
        }
        refreshClipUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("clip", clipFile?.name)
        outState.putString("saveId", saveId)
        outState.putDouble("clipSeconds", clipSeconds)
        outState.putBoolean("clipTruncated", clipTruncated)
        outState.putBoolean("recording", recorder != null)
    }

    override fun onDestroy() {
        releasePlayer()
        recorder?.cancel()
        recorder = null
        if (isFinishing) clipFile?.delete()
        clipFile = null
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- recording

    private fun onRecordClicked() {
        if (busy) return
        val active = recorder
        if (active != null) {
            active.stop()
            recordButton.isEnabled = false
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            startRecording()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.voice_create_permission_title)
                .setMessage(R.string.voice_create_permission_message)
                .setNegativeButton(R.string.cancel) { _, _ ->
                    toast(getString(R.string.voice_create_permission_denied))
                }
                .setPositiveButton(R.string.voice_create_permission_grant) { _, _ ->
                    requestMic.launch(Manifest.permission.RECORD_AUDIO)
                }
                .show()
        }
    }

    private fun startRecording() {
        releasePlayer()
        val started = ReferenceRecorder(
            onLevel = { level ->
                if (!isFinishing && !isDestroyed) {
                    recordButton.text = getString(
                        R.string.voice_create_recording,
                        (level * 100).toInt(),
                    )
                }
            },
            onDone = { samples, sampleRate -> onRecorded(samples, sampleRate) },
        )
        val rate = started.start()
        if (rate == null) {
            toast(getString(R.string.voice_create_record_failed))
            return
        }
        recorder = started
        pickButton.isEnabled = false
        previewButton.isEnabled = false
        saveButton.isEnabled = false
        recordButton.isEnabled = true
        recordButton.text = getString(R.string.voice_create_recording, 0)
        clipStatus.text = getString(R.string.voice_create_recording_hint)
    }

    private fun onRecorded(samples: FloatArray?, sampleRate: Int) {
        if (isFinishing || isDestroyed) return
        recorder = null
        setBusy(false)
        recordButton.setText(R.string.voice_create_record)
        if (samples == null) {
            refreshClipUi()
            return
        }
        val seconds = samples.size.toDouble() / sampleRate
        if (seconds < AudioTranscoder.MIN_REFERENCE_SECONDS) {
            // Keep whatever clip was already accepted: a failed retry must not
            // throw away a good file the user had picked earlier.
            refreshClipUi()
            toast(getString(R.string.voice_create_too_short))
            return
        }
        val target = File(cacheDir, "voice-create-${System.nanoTime()}.wav")
        try {
            WavWriter.write(target, samples, sampleRate)
        } catch (error: Exception) {
            target.delete()
            toast(getString(R.string.voice_create_failed, error.message.orEmpty()))
            refreshClipUi()
            return
        }
        clipFile?.delete()
        clipFile = target
        clipSeconds = seconds
        clipTruncated = false
        refreshClipUi()
    }

    // ------------------------------------------------------------- file picking

    private fun pickClip() {
        if (busy) return
        pickAudio.launch(arrayOf("audio/*", "application/ogg", "application/octet-stream"))
    }

    internal fun importReference(uri: Uri) {
        releasePlayer()
        val previous = clipFile
        setBusy(true)
        clipStatus.text = getString(R.string.voice_create_transcoding)
        worker.execute {
            val target = File(cacheDir, "voice-create-${System.nanoTime()}.wav")
            val result = AudioTranscoder.toReferenceWav(this, uri, target)
            main.post {
                if (isFinishing || isDestroyed) {
                    target.delete()
                    return@post
                }
                setBusy(false)
                when (result) {
                    is TranscodeResult.Ok -> {
                        previous?.delete()
                        clipFile = result.wav
                        clipSeconds = result.seconds
                        clipTruncated = result.truncated
                        refreshClipUi()
                        if (result.truncated) toast(getString(R.string.voice_create_truncated))
                    }
                    is TranscodeResult.Failed -> {
                        target.delete()
                        previous?.takeIf { it.isFile }?.let { clipFile = it }
                        refreshClipUi()
                        toast(importErrorText(result.reason))
                    }
                }
            }
        }
    }

    private fun importErrorText(reason: AudioImportError): String = getString(
        when (reason) {
            AudioImportError.NOT_AUDIO -> R.string.voice_create_error_not_audio
            AudioImportError.UNSUPPORTED_CODEC -> R.string.voice_create_error_unsupported
            AudioImportError.TOO_LARGE -> R.string.voice_create_error_too_large
            AudioImportError.TOO_SHORT -> R.string.voice_create_too_short
            AudioImportError.DECODE_FAILED -> R.string.voice_create_error_decode
        },
    )

    // ------------------------------------------------------------------ preview

    private fun playClip() {
        val file = clipFile?.takeIf { it.isFile } ?: return
        releasePlayer()
        val media = MediaPlayer()
        player = media
        try {
            media.setDataSource(file.absolutePath)
            media.setOnCompletionListener { releasePlayer() }
            media.setOnErrorListener { _, _, _ ->
                releasePlayer()
                true
            }
            media.prepare()
            media.start()
            previewButton.isEnabled = false
        } catch (error: Exception) {
            releasePlayer()
            toast(getString(R.string.speech_failed))
        }
    }

    private fun releasePlayer() {
        player?.let { media ->
            runCatching { media.stop() }
            runCatching { media.release() }
        }
        player = null
        previewButton.isEnabled = clipFile != null && !busy
    }

    // -------------------------------------------------------------------- save

    private fun save() {
        if (busy) return
        val clip = clipFile?.takeIf { it.isFile && it.length() > 0L }
        if (clip == null) {
            toast(getString(R.string.voice_create_need_clip))
            return
        }
        val rawName = nameInput.text?.toString()?.trim().orEmpty()
        if (rawName.isEmpty()) {
            toast(getString(R.string.voice_create_need_name))
            nameInput.requestFocus()
            return
        }
        val transcript = textInput.text?.toString()?.trim().orEmpty()
        if (transcript.isEmpty()) {
            toast(getString(R.string.voice_create_need_text))
            textInput.requestFocus()
            return
        }
        if (VoicePackRepository.listImported(this).size >= VoicePackRepository.MAX_IMPORTED) {
            toast(getString(R.string.voice_create_limit, VoicePackRepository.MAX_IMPORTED))
            return
        }

        val name = VoicePackDraft.uniqueName(
            rawName,
            VoicePackRepository.list(this).map { it.name },
        )
        val about = aboutInput.text?.toString()?.trim().orEmpty()
            .take(VoicePackDraft.MAX_ABOUT_CHARS)

        releasePlayer()
        setBusy(true)
        clipStatus.text = getString(R.string.voice_create_saving)
        val id = saveId ?: newUniqueId().also { saveId = it }
        worker.execute {
            var failure: String? = null
            var created: VoicePack? = null
            try {
                val pack = VoicePackRepository.create(
                    context = this,
                    id = id,
                    name = name,
                    about = about,
                    referenceText = transcript.take(VoicePackDraft.MAX_TEXT_CHARS),
                    referenceWav = clip,
                )
                VoicePackRepository.setCurrent(this, pack.id)
                created = pack
            } catch (error: VoicePackException) {
                failure = voicePackProblem(this, error)
            } catch (error: Exception) {
                failure = getString(R.string.speech_voice_reject_generic)
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                setBusy(false)
                val pack = created
                if (pack != null) {
                    toast(getString(R.string.voice_create_done, pack.name))
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    clipStatus.text = getString(R.string.voice_create_failed, failure.orEmpty())
                    toast(failure.orEmpty())
                }
            }
        }
    }

    /** Retries a few times so a same-millisecond save cannot collide. */
    private fun newUniqueId(): String {
        val existing = VoicePackRepository.list(this).mapTo(HashSet()) { it.id }
        repeat(4) {
            val id = VoicePackDraft.newId(
                System.currentTimeMillis(),
                random.nextInt(36 * 36 * 36),
            )
            if (id !in existing) return id
        }
        return VoicePackDraft.newId(System.currentTimeMillis(), random.nextInt())
    }

    // --------------------------------------------------------------------- ui

    private fun setBusy(value: Boolean) {
        busy = value
        saveButton.isEnabled = !value
        pickButton.isEnabled = !value
        recordButton.isEnabled = !value && recorder == null
        previewButton.isEnabled = !value && clipFile != null
        if (!value) refreshClipUi()
    }

    private fun refreshClipUi() {
        val file = clipFile?.takeIf { it.isFile }
        val recording = recorder != null
        if (recording) return
        when {
            file == null -> {
                clipStatus.text = getString(R.string.voice_create_clip_empty)
                previewButton.isEnabled = false
            }
            else -> {
                val rounded = "%.1f".format(clipSeconds)
                clipStatus.text = if (clipTruncated) {
                    getString(R.string.voice_create_clip_ready_truncated, rounded)
                } else {
                    getString(R.string.voice_create_clip_ready, rounded)
                }
                previewButton.isEnabled = !busy
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
