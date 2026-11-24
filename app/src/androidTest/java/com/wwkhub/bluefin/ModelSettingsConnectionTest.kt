package com.wwkhub.bluefin

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelSettingsConnectionTest {
    @Test
    fun testedDraftIsTheConfigurationUsedByTheFirstChatMessage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrument = InstrumentationRegistry.getInstrumentation()
        val previousEndpoint = ModelEndpointStore.load(context)
        val previousKey = ApiKeyVault.read(context)
        val preferences = context.getSharedPreferences(BluefinStore.PREFS, Context.MODE_PRIVATE)
        val previousSpeech = BluefinStore.readSpeechOn(preferences)
        val monitor = instrument.addMonitor(ChatScreen::class.java.name, null, false)
        try {
            BluefinStore.writeSpeechOn(preferences, false)
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(402))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """data: {"choices":[{"delta":{"content":"OK"}}]}""" + "\n\ndata: [DONE]\n\n",
                ))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """data: {"choices":[{"delta":{"content":"配置接通了。"}}]}""" + "\n\ndata: [DONE]\n\n",
                ))
                val oldEndpoint = previousEndpoint.copy(
                    format = ModelApiFormat.OPENAI,
                    url = server.url("/old").toString(),
                    model = "old-model",
                    extraHeaders = "",
                )
                ModelEndpointStore.save(context, oldEndpoint)
                assertTrue(ApiKeyVault.save(context, "old-test-key"))
                val modelUrl = server.url("/v1").toString()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.openModelSettings()
                        activity.findViewById<EditText>(R.id.model_url).setText(modelUrl)
                        activity.findViewById<EditText>(R.id.model_name).setText("new-model")
                        activity.findViewById<EditText>(R.id.model_key).setText("  new-test-key  ")
                        activity.findViewById<View>(R.id.model_test).performClick()
                    }
                    checkNotNull(server.takeRequest(8, TimeUnit.SECONDS))
                    await { status(scenario).contains("计费问题（402）") }
                    scenario.onActivity { it.findViewById<View>(R.id.model_test).performClick() }
                    val probe = checkNotNull(server.takeRequest(8, TimeUnit.SECONDS))
                    await { status(scenario).startsWith("连接正常") }
                    assertEquals("old-test-key", ApiKeyVault.read(context))
                    scenario.onActivity { it.findViewById<View>(R.id.model_go_chat).performClick() }
                    val screen = checkNotNull(instrument.waitForMonitorWithTimeout(monitor, 8_000))
                    try {
                        instrument.runOnMainSync {
                            screen.findViewById<EditText>(R.id.chat_input).setText("Connection regression")
                            screen.findViewById<View>(R.id.chat_send).performClick()
                        }
                        val send = checkNotNull(server.takeRequest(8, TimeUnit.SECONDS))
                        val probeBody = JSONObject(probe.body.readUtf8())
                        val sendBody = JSONObject(send.body.readUtf8())
                        assertEquals("/v1/chat/completions", probe.path)
                        assertEquals(probe.path, send.path)
                        assertEquals("Bearer new-test-key", probe.getHeader("Authorization"))
                        assertEquals(probe.getHeader("Authorization"), send.getHeader("Authorization"))
                        assertEquals("new-model", probeBody.getString("model"))
                        assertEquals(probeBody.getString("model"), sendBody.getString("model"))
                        assertTrue(probeBody.getBoolean("stream"))
                        assertTrue(sendBody.getBoolean("stream"))
                        await { ChatSession.peek()?.snapshot()?.lines?.lastOrNull()?.text == "配置接通了。" }
                    } finally {
                        instrument.runOnMainSync { screen.finish() }
                    }
                }
            }
        } finally {
            instrument.removeMonitor(monitor)
            ModelEndpointStore.save(context, previousEndpoint)
            if (previousKey == null) ApiKeyVault.clear(context) else ApiKeyVault.save(context, previousKey)
            BluefinStore.writeSpeechOn(preferences, previousSpeech)
        }
    }

    @Test
    fun anthropicSettingsUseTheSelectedProtocolAndDoNotReuseAnotherOriginsKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previousEndpoint = ModelEndpointStore.load(context)
        val previousKey = ApiKeyVault.read(context)
        try {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}\n\n" +
                        "data: {\"type\":\"message_stop\"}\n\n",
                ))
                val previousOrigin = previousEndpoint.copy(
                    format = ModelApiFormat.OPENAI, url = "https://old-gateway.example/v1", model = "old", extraHeaders = "",
                )
                assertTrue(ApiKeyVault.saveConfiguration(context, previousOrigin, "old-test-key"))
                val modelUrl = server.url("/anthropic").toString()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.openModelSettings()
                        val format = activity.findViewById<android.widget.AutoCompleteTextView>(R.id.model_format)
                        assertEquals(activity.getString(R.string.model_format_openai), format.text.toString())
                        val selected = ModelApiFormat.ANTHROPIC
                        format.setText(activity.getString(selected.label), false)
                        activity.findViewById<EditText>(R.id.model_url).setText(modelUrl)
                        activity.findViewById<EditText>(R.id.model_name).setText("new-model")
                    }
                    scenario.recreate()
                    scenario.onActivity { activity ->
                        assertEquals(activity.getString(R.string.model_format_anthropic),
                            activity.findViewById<EditText>(R.id.model_format).text.toString())
                        activity.findViewById<View>(R.id.model_test).performClick()
                        assertEquals(
                            activity.getString(R.string.model_key_for_new_address),
                            activity.findViewById<TextView>(R.id.model_test_status).text.toString(),
                        )
                        assertEquals(0, server.requestCount)
                        activity.findViewById<EditText>(R.id.model_key).setText("new-test-key")
                        activity.findViewById<View>(R.id.model_test).performClick()
                    }
                    val request = checkNotNull(server.takeRequest(8, TimeUnit.SECONDS))
                    assertEquals("/anthropic/v1/messages", request.path)
                    assertEquals("new-test-key", request.getHeader("x-api-key"))
                    await { status(scenario).startsWith("连接正常") }
                    scenario.onActivity { it.findViewById<View>(R.id.model_save).performClick() }
                    val saved = ModelEndpointStore.load(context)
                    assertEquals(ModelApiFormat.ANTHROPIC, saved.format)
                    assertEquals("new-test-key", ApiKeyVault.readForEndpoint(context, saved))
                    assertEquals(null, ApiKeyVault.readForEndpoint(context, previousOrigin))
                }
            }
        } finally {
            ModelEndpointStore.save(context, previousEndpoint)
            if (previousKey == null) ApiKeyVault.clear(context) else ApiKeyVault.save(context, previousKey)
        }
    }

    private fun status(scenario: ActivityScenario<MainActivity>): String {
        var text = ""
        scenario.onActivity { text = it.findViewById<TextView>(R.id.model_test_status).text.toString() }
        return text
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for model connection" }
            SystemClock.sleep(50)
        }
    }
}
