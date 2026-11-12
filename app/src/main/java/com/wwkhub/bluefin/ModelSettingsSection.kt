package com.wwkhub.bluefin

import android.app.Activity
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
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
    private val provider: MaterialAutoCompleteTextView = root.findViewById(R.id.model_provider)
    private val regionGroup: MaterialButtonToggleGroup = root.findViewById(R.id.model_region_group)
    private val regionMainland: MaterialButton = root.findViewById(R.id.model_region_mainland)
    private val regionIntl: MaterialButton = root.findViewById(R.id.model_region_intl)
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

    private var currentProviderId = ModelCatalog.DEFAULT_PROVIDER_ID
    private var currentRegion = ModelCatalog.Region.MAINLAND
    private var syncing = false
    private var keyVisible = false
    private var testing = false

    init {
        val adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_list_item_1,
            ModelCatalog.all.map { it.label },
        )
        provider.setAdapter(adapter)
        provider.inputType = android.text.InputType.TYPE_NULL
        provider.setOnClickListener { provider.showDropDown() }
        provider.setOnItemClickListener { _, _, position, _ ->
            if (syncing) return@setOnItemClickListener
            val next = ModelCatalog.all.getOrNull(position) ?: return@setOnItemClickListener
            switchProvider(next)
        }
        regionGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (syncing || !isChecked) return@addOnButtonCheckedListener
            val next = if (checkedId == R.id.model_region_intl) {
                ModelCatalog.Region.INTERNATIONAL
            } else {
                ModelCatalog.Region.MAINLAND
            }
            switchRegion(next)
        }
        keyToggle.setOnClickListener { toggleKeyVisibility() }
        keyClear.setOnClickListener { confirmClearKey() }
        advancedToggle.setOnClickListener {
            advancedGroup.visibility =
                if (advancedGroup.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        test.setOnClickListener { runTest() }
        save.setOnClickListener { saveSettings() }
        goChat.setOnClickListener { onOpenChat() }
        populate(ModelEndpointStore.load(activity))
    }

    private fun populate(endpoint: ModelEndpoint) {
        syncing = true
        currentProviderId = endpoint.providerId
        currentRegion = endpoint.region
        provider.setText(endpoint.provider.label, false)
        url.setText(endpoint.url)
        model.setText(endpoint.model)
        model.hint = endpoint.modelHint
        temperature.setText(endpoint.temperature?.toString().orEmpty())
        maxTokens.setText(endpoint.maxOutputTokens.toString())
        headers.setText(endpoint.extraHeaders)
        key.setText("")
        key.hint = activity.getString(R.string.model_key_keep)
        regionGroup.visibility = if (endpoint.provider.hasRegionChoice) View.VISIBLE else View.GONE
        regionGroup.check(
            if (endpoint.region == ModelCatalog.Region.INTERNATIONAL) {
                R.id.model_region_intl
            } else {
                R.id.model_region_mainland
            },
        )
        syncing = false
        refreshKeyState()
    }

    private fun switchProvider(next: ModelProvider) {
        if (next.id == currentProviderId) return
        val previousId = currentProviderId
        val previousRegion = currentRegion
        val result = ModelCatalog.switchPrefill(
            currentUrl = url.text?.toString().orEmpty(),
            currentModel = model.text?.toString().orEmpty(),
            previousProviderId = previousId,
            previousRegion = previousRegion,
            nextProviderId = next.id,
            nextRegion = previousRegion,
        )
        syncing = true
        currentProviderId = next.id
        currentRegion = ModelCatalog.regionFor(next.id, previousRegion)
        provider.setText(next.label, false)
        url.setText(result.url)
        model.setText(result.model)
        model.hint = next.modelHint
        regionGroup.visibility = if (next.hasRegionChoice) View.VISIBLE else View.GONE
        regionGroup.check(
            if (currentRegion == ModelCatalog.Region.INTERNATIONAL) {
                R.id.model_region_intl
            } else {
                R.id.model_region_mainland
            },
        )
        syncing = false
        Toast.makeText(activity, R.string.model_confirm_key, Toast.LENGTH_SHORT).show()
    }

    private fun switchRegion(nextRegion: ModelCatalog.Region) {
        if (nextRegion == currentRegion) return
        val result = ModelCatalog.switchPrefill(
            currentUrl = url.text?.toString().orEmpty(),
            currentModel = model.text?.toString().orEmpty(),
            previousProviderId = currentProviderId,
            previousRegion = currentRegion,
            nextProviderId = currentProviderId,
            nextRegion = nextRegion,
        )
        currentRegion = nextRegion
        url.setText(result.url)
        if (model.text.isNullOrBlank()) model.setText(result.model)
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
                ApiKeyVault.clear(activity)
                key.setText("")
                refreshKeyState()
                testStatus.text = ""
                OverlayLauncher.sync(activity)
            }
            .show()
    }

    private fun saveSettings() {
        val rawUrl = url.text?.toString().orEmpty().trim()
        if (rawUrl.isNotEmpty() && EndpointAddressPolicy.check(rawUrl) is EndpointAddressPolicy.Check.Invalid) {
            testStatus.text = (EndpointAddressPolicy.check(rawUrl) as EndpointAddressPolicy.Check.Invalid).reason
            return
        }
        val rawTemperature = temperature.text?.toString().orEmpty().trim()
        val parsedTemperature = if (rawTemperature.isEmpty()) {
            null
        } else {
            rawTemperature.toDoubleOrNull()?.takeIf { it in 0.0..2.0 }
        }
        if (rawTemperature.isNotEmpty() && parsedTemperature == null) {
            testStatus.text = "温度需在 0 到 2 之间。"
            return
        }
        val rawTokens = maxTokens.text?.toString().orEmpty().trim()
        val parsedTokens = rawTokens.toIntOrNull() ?: ModelEndpointStore.DEFAULT_MAX_TOKENS
        if (parsedTokens <= 0) {
            testStatus.text = "最大输出 token 需为正整数。"
            return
        }
        val rawHeaders = headers.text?.toString().orEmpty()
        if (!headersValid(rawHeaders)) {
            testStatus.text = "附加请求头需为每行「名称: 值」。"
            return
        }
        val endpoint = ModelEndpoint(
            providerId = currentProviderId,
            region = currentRegion,
            url = rawUrl,
            model = model.text?.toString().orEmpty().trim(),
            temperature = parsedTemperature,
            maxOutputTokens = parsedTokens,
            extraHeaders = rawHeaders,
        )
        ModelEndpointStore.save(activity, endpoint)
        val typedKey = key.text?.toString().orEmpty()
        if (typedKey.isNotBlank()) {
            ApiKeyVault.save(activity, typedKey)
            key.setText("")
        }
        refreshKeyState()
        testStatus.text = ""
        OverlayLauncher.sync(activity)
        Toast.makeText(activity, R.string.model_saved, Toast.LENGTH_SHORT).show()
    }

    private fun headersValid(raw: String): Boolean {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .all { line ->
                val index = line.indexOf(':')
                index > 0 && line.substring(index + 1).trim().isNotEmpty()
            }
    }

    private fun runTest() {
        if (testing) return
        val rawUrl = url.text?.toString().orEmpty().trim()
        val check = EndpointAddressPolicy.check(rawUrl)
        if (check is EndpointAddressPolicy.Check.Invalid) {
            testStatus.text = check.reason
            return
        }
        val modelName = model.text?.toString().orEmpty().trim()
        if (modelName.isEmpty()) {
            testStatus.text = "请填写模型名。"
            return
        }
        val typedKey = key.text?.toString().orEmpty()
        val apiKey = if (typedKey.isNotBlank()) typedKey else ApiKeyVault.read(activity)
        if (apiKey.isNullOrBlank()) {
            testStatus.text = "请先填写 API Key。"
            return
        }
        val endpoint = ModelEndpoint(
            providerId = currentProviderId,
            region = currentRegion,
            url = rawUrl,
            model = modelName,
            temperature = null,
            maxOutputTokens = maxTokens.text?.toString()?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: ModelEndpointStore.DEFAULT_MAX_TOKENS,
            extraHeaders = headers.text?.toString().orEmpty(),
        )
        testing = true
        test.isEnabled = false
        testStatus.setText(R.string.model_testing)
        Thread({
            val message = try {
                val elapsed = ChatCompletionsClient().test(endpoint, apiKey)
                activity.getString(R.string.model_connected) + "（${elapsed} ms）"
            } catch (error: ChatHttpStatusException) {
                ChatFailureText.forStatus(error.status)
            } catch (error: Exception) {
                ChatFailureText.forThrowable(error)
            }
            root.post {
                testing = false
                test.isEnabled = true
                testStatus.text = message
            }
        }, "bluefin-model-test").apply { isDaemon = true }.start()
    }
}
