package com.wwkhub.bluefin

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wwkhub.bluefin.voice.audio.WavWriter
import com.wwkhub.bluefin.voice.tts.CloudIdentity
import com.wwkhub.bluefin.voice.tts.CloudTtsClient
import com.wwkhub.bluefin.voice.tts.CloudVoiceDeletion
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun restoredCreatorKeepsFieldsAndAudioAfterShortRetry() {
        val wave = wave()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        try {
            ActivityScenario.launch(VoicePackCreatorActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.voice_create_name).setText("Audit voice")
                    activity.findViewById<EditText>(R.id.voice_create_text).setText("Synthetic audio")
                    activity.importReference(Uri.fromFile(wave))
                }
                await { scenario.onActivity { assertTrue(it.findViewById<Button>(R.id.voice_create_preview).isEnabled) } }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals("Audit voice", activity.findViewById<EditText>(R.id.voice_create_name).text.toString())
                    assertEquals("Synthetic audio", activity.findViewById<EditText>(R.id.voice_create_text).text.toString())
                    assertTrue(activity.findViewById<Button>(R.id.voice_create_preview).isEnabled)
                    activity.findViewById<Button>(R.id.voice_create_record).performClick()
                }
                SystemClock.sleep(250)
                scenario.onActivity { activity -> activity.findViewById<Button>(R.id.voice_create_record).performClick() }
                await { scenario.onActivity { assertTrue(it.findViewById<Button>(R.id.voice_create_preview).isEnabled) } }
            }
        } finally {
            wave.delete()
        }
    }

    @Test
    fun previewDoesNotEnableAutomaticReading() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val prefs = activity.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
                BluefinStore.writeSpeechOn(prefs, false)
                BluefinStore.writeAutoSpeech(prefs, true)
                BluefinStore.writeCloudTtsBase(prefs, null)
                val parent = android.widget.LinearLayout(activity)
                val section = SpeechSettingsSection(activity, parent)
                parent.findViewById<Button>(R.id.speech_voice_preview).performClick()
                assertFalse(BluefinStore.readSpeechOn(prefs))
                assertTrue(BluefinStore.readAutoSpeech(prefs))
                section.dispose()
            }
        }
    }

    @Test
    fun deletionRetriesOriginalServiceAndTokensStayScoped() {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                val firstBase = first.url("/").toString().trimEnd('/')
                val secondBase = second.url("/").toString().trimEnd('/')
                val confirms = AtomicBoolean(false)
                first.dispatcher = service("first-token", confirms::get)
                second.dispatcher = service("second-token") { true }
                assertEquals("first-token", CloudIdentity.ensureToken(context, CloudTtsClient(firstBase)))
                assertEquals("second-token", CloudIdentity.ensureToken(context, CloudTtsClient(secondBase)))
                val id = "test-" + UUID.randomUUID().toString().take(12)
                val wave = wave()
                try {
                    VoicePackRepository.create(context, id, "Audit voice", "", "Synthetic sound", wave)
                    assertTrue(CloudVoiceDeletion.trackBinding(context, id, firstBase) != null)
                    context.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE).edit()
                        .putString(BluefinStore.KEY_CLOUD_TTS_BASE, secondBase).apply()
                    assertTrue(CloudVoiceDeletion.request(context, id))
                    assertFalse(VoicePackRepository.find(context, id) != null)
                    assertFalse(CloudVoiceDeletion.complete(context, id))
                    assertTrue(CloudVoiceDeletion.isPending(context, id))
                    confirms.set(true)
                    assertTrue(CloudVoiceDeletion.complete(context, id))
                    assertFalse(CloudVoiceDeletion.isPending(context, id))
                    assertEquals(1, second.requestCount)
                } finally {
                    wave.delete()
                }
            }
        }
    }

    private fun service(token: String, confirms: () -> Boolean) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path == "/v1/register" -> MockResponse()
                .setBody("""{"token":"$token","expiresAt":4102444800}""")
            request.getHeader("Authorization") != "Bearer $token" -> MockResponse().setResponseCode(401)
            request.method == "DELETE" && confirms() -> MockResponse().setBody("""{"deleted":true}""")
            else -> MockResponse().setResponseCode(503)
        }
    }

    private fun wave(): File = File.createTempFile("voice-test", ".wav", context.cacheDir).also {
        WavWriter.write(it, FloatArray(72_000) { i -> (kotlin.math.sin(i * 0.2) * 0.2).toFloat() }, 24_000)
    }

    private fun await(check: () -> Unit) {
        repeat(50) {
            try {
                check()
                return
            } catch (_: AssertionError) {
                SystemClock.sleep(100)
            }
        }
        check()
    }
}
