package com.wwkhub.bluefin

import android.app.Activity
import android.view.View
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

/** Editable global persona, shown in Settings after the model group. */
internal class PersonaEditorSection(
    private val activity: Activity,
    private val root: View,
) {
    private val text: TextInputEditText = root.findViewById(R.id.persona_text)
    private val save: MaterialButton = root.findViewById(R.id.persona_save)
    private val restore: MaterialButton = root.findViewById(R.id.persona_restore)

    init {
        text.setText(PersonaRepository.read(activity))
        save.setOnClickListener {
            PersonaRepository.save(activity, text.text?.toString().orEmpty())
            text.setText(PersonaRepository.read(activity))
            Toast.makeText(activity, R.string.persona_saved, Toast.LENGTH_SHORT).show()
        }
        restore.setOnClickListener { confirmRestore() }
    }

    private fun confirmRestore() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.persona_restore_title)
            .setMessage(R.string.persona_restore_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.persona_restore_confirm) { _, _ ->
                PersonaRepository.restoreDefault(activity)
                text.setText(PersonaText.restoreDefault())
            }
            .show()
    }
}
