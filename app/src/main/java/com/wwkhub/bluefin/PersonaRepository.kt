package com.wwkhub.bluefin

import android.content.Context

/** Global persona, stored separately from the transcript and endpoint settings. */
internal object PersonaRepository {
    private const val PREFS = "bluefin_persona"
    private const val KEY_TEXT = "persona_text"

    fun read(context: Context): String =
        PersonaText.normalize(prefs(context).getString(KEY_TEXT, null))

    fun save(context: Context, text: String) {
        val normalized = PersonaText.normalize(text)
        prefs(context).edit().putString(KEY_TEXT, normalized).apply()
    }

    fun restoreDefault(context: Context) {
        prefs(context).edit().remove(KEY_TEXT).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
