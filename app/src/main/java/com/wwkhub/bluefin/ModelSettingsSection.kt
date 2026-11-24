package com.wwkhub.bluefin

import android.app.Activity
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

/**
 * The complete model form shown inside Settings. It is inflated only when the
 * user opens Settings, so it never participates in overlay startup.
 */
internal class ModelSettingsSection(
    private val activity: Activity,
    private val root: View,
    private val onOpenChat: () -> Unit,
) {
    private val format: MaterialAutoCompleteTextView = root.findViewById(R.id.model_format)
    private val url: TextInputEditText = root.findViewById(R.id.model_url)
    private val model: TextInputEditText = root.findViewById(R.id.model_name)
    private val key: TextInputEditText = root.findViewById(R.id.model_key)
    private val keyToggle: MaterialButton = root.findViewById(R.id.model_key_toggle)
    private val keyState: TextView = root.findViewById(R.id.model_key_state)
    private val keyClear: MaterialButton = root.findViewById(R.id.model_key_clear)
    private val test: MaterialButton = root.findViewById(R.id.model_test)
    private val testStatus: TextView = root.findViewById(R.id.model_test_status)
    private val advancedToggle: MaterialButton = root.findViewById(R.id.model_advanced_toggle)
    private val advancedGroup: View = root.findViewById(R.id.model_advanced_group)
    private val temperature: TextInputEditText = root.findViewById(R.id.model_temperature)
    private val maxTokens: TextInputEditText = root.findViewById(R.id.model_max_tokens)
    private val headers: TextInputEditText = root.findViewById(R.id.model_headers)
    private val save: MaterialButton = root.findViewById(R.id.model_save)
    private val goChat: MaterialButton = root.findViewById(R.id.model_go_chat)

    private var keyVisible = false
    private var testing = false
    private var formRevision = 0L

    init {
        val adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_list_item_1,
            ModelApiFormat.entries.map { activity.getString(it.label) },
        )
        format.setAdapter(adapter)
        format.inputType = android.text.InputType.TYPE_NULL
        format.setOnClickListener { format.showDropDown() }
        keyToggle.setOnClickListener { toggleKeyVisibility() }
        keyClear.setOnClickListener { confirmClearKey() }
        advancedToggle.setOnClickListener {
            advancedGroup.visibility =
                if (advancedGroup.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        test.setOnClickListener { runTest() }
        save.setOnClickListener { saveSettings() }
        goChat.setOnClickListener { saveSettings(openChat = true) }
        populate(ModelEndpointStore.load(activity))
        listOf(format, url, model, key, temperature, maxTokens, headers).forEach { field ->
            field.doAfterTextChanged { invalidateTestResult() }
        }
    }

    private fun invalidateTestResult() {
        formRevision += 1
        testStatus.text = ""
    }

    private fun populate(endpoint: ModelEndpoint) {
        format.setText(activity.getString(endpoint.format.label), false)
        url.setText(endpoint.url)
        model.setText(endpoint.model)
        temperature.setText(endpoint.temperature?.toString().orEmpty())
        maxTokens.setText(endpoint.maxOutputTokens.toString())
        headers.setText(endpoint.extraHeaders)
        key.setText("")
        refreshKeyState()
    }

    private fun toggleKeyVisibility() {
        keyVisible = !keyVisible
        key.transformationMethod = if (keyVisible) {
            HideReturnsTransformationMethod.getInstance()
        } else {
            PasswordTransformationMethod.getInstance()
        }
        keyToggle.setText(if (keyVisible) R.string.model_hide else R.string.model_show)
        key.setSelection(key.text?.length ?: 0)
    }

    private fun refreshKeyState() {
        val saved = ModelEndpointStore.hasSavedKey(activity)
        keyState.setText(if (saved) R.string.model_key_saved else R.string.model_key_not_saved)
        keyClear.isEnabled = saved
    }

    private fun confirmClearKey() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.model_clear_key_title)
            .setMessage(R.string.model_clear_key_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.model_clear_confirm) { _, _ ->
                if (!ApiKeyVault.clear(activity)) {
                    testStatus.text = "无法清除 API Key，请重试。"
                    return@setPositiveButton
                }
                key.setText("")
                refreshKeyState()
                invalidateTestResult()
                OverlayLauncher.sync(activity)
            }
            .show()
    }

    private fun saveSettings(openChat: Boolean = false) {
        val endpoint = readForm() ?: return
        val typedKey = key.text?.toString().orEmpty().trim()
        if (!canUseCredentials(endpoint, typedKey)) return
        if (openChat && typedKey.isEmpty() && savedKey().isNullOrBlank()) {
            testStatus.setText(R.string.model_key_required)
            return
        }
        if (!ApiKeyVault.saveConfiguration(activity, endpoint, typedKey)) {
            testStatus.text = "无法安全保存 API Key，请重试。"
            return
        }
        if (typedKey.isNotEmpty()) key.setText("")
        refreshKeyState()
        invalidateTestResult()
        OverlayLauncher.sync(activity)
        if (openChat) onOpenChat()
        else Toast.makeText(activity, R.string.model_saved, Toast.LENGTH_SHORT).show()
    }

    private fun canUseCredentials(endpoint: ModelEndpoint, typedKey: String): Boolean {
        val saved = ModelEndpointStore.load(activity)
        if (EndpointAddressPolicy.sameOrigin(saved.url, endpoint.url)) return true
        if (typedKey.isEmpty() && ModelEndpointStore.hasSavedKey(activity)) {
            testStatus.setText(R.string.model_key_for_new_address)
            return false
        }
        if (saved.extraHeaders.isNotBlank() && endpoint.extraHeaders.trim() == saved.extraHeaders.trim()) {
            testStatus.setText(R.string.model_headers_for_new_address)
            return false
        }
        return true
    }

    private fun savedKey(): String? = ApiKeyVault.readForEndpoint(activity, ModelEndpointStore.load(activity))

    private fun readForm(): ModelEndpoint? {
        val label = format.text.toString()
        val currentFormat = ModelApiFormat.entries.firstOrNull { activity.getString(it.label) == label }
        if (currentFormat == null) {
            testStatus.setText(R.string.model_format_required)
            return null
        }
        val rawUrl = url.text?.toString().orEmpty().trim()
        val check = EndpointAddressPolicy.checkChatAddress(rawUrl, currentFormat)
        if (check is EndpointAddressPolicy.Check.Invalid) {
            testStatus.text = check.reason
            return null
        }
        val modelName = model.text?.toString().orEmpty().trim()
        if (modelName.isEmpty()) {
            testStatus.text = "请填写模型名。"
            return null
        }
        val rawTemperature = temperature.text?.toString().orEmpty().trim()
        val temperatureLimit = if (currentFormat == ModelApiFormat.ANTHROPIC) 1 else 2
        val parsedTemperature = if (rawTemperature.isEmpty()) {
            null
        } else {
            rawTemperature.toDoubleOrNull()?.takeIf { it >= 0.0 && it <= temperatureLimit }
        }
        if (rawTemperature.isNotEmpty() && parsedTemperature == null) {
            testStatus.text = activity.getString(R.string.model_temperature_range, temperatureLimit)
            return null
        }
        val rawTokens = maxTokens.text?.toString().orEmpty().trim()
        val parsedTokens = if (rawTokens.isEmpty()) ModelEndpointStore.DEFAULT_MAX_TOKENS else rawTokens.toIntOrNull()
        if (parsedTokens == null || parsedTokens <= 0) {
            testStatus.text = "最大输出 token 需为正整数。"
            return null
        }
        val rawHeaders = headers.text?.toString().orEmpty()
        if (invalidModelHeaders(rawHeaders)) {
            testStatus.text = ChatFailureText.INVALID_HEADERS
            return null
        }
        return ModelEndpoint(
            format = currentFormat,
            url = rawUrl,
            model = modelName,
            temperature = parsedTemperature,
            maxOutputTokens = parsedTokens,
            extraHeaders = rawHeaders,
        )
    }

    private fun runTest() {
        if (testing) return
        val endpoint = readForm() ?: return
        val typedKey = key.text?.toString().orEmpty().trim()
        if (!canUseCredentials(endpoint, typedKey)) return
        val apiKey = typedKey.ifBlank { savedKey().orEmpty() }
        if (apiKey.isNullOrBlank()) {
            testStatus.setText(R.string.model_key_required)
            return
        }
        testing = true
        val testedRevision = formRevision
        test.isEnabled = false
        testStatus.setText(R.string.model_testing)
        Thread({
            val message = try {
                val result = ChatCompletionsClient().test(endpoint, apiKey)
                if (result.outputLimitReached) {
                    activity.getString(R.string.model_connected_output_limit)
                } else {
                    activity.getString(R.string.model_connected, result.elapsedMillis)
                }
            } catch (error: Exception) {
                ChatFailureText.forThrowable(error)
            }
            root.post {
                testing = false
                test.isEnabled = true
                if (formRevision == testedRevision) testStatus.text = message
            }
        }, "bluefin-model-test").apply { isDaemon = true }.start()
    }
}
