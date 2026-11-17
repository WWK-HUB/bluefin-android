package com.wwkhub.bluefin

/**
 * Splits the current pack's manifest vocabulary into the two action sections
 * shown by both the in-app sheet and the orb panel. The manifest is the single
 * source of truth: built-in, bundled and imported packs all list exactly what
 * their own figure.json declares, and 「站立」 appears once, inside motions.
 */
internal object ActionVocab {
    data class Sections(
        val motions: List<Pair<String, String>>,
        val expressions: List<Pair<String, String>>,
    ) {
        val isEmpty: Boolean get() = motions.isEmpty() && expressions.isEmpty()
    }

    fun of(manifest: PackManifest?, language: String = "zh"): Sections {
        val vocab = manifest?.vocab.orEmpty()
        return Sections(
            motions = vocab.filter { it.kind == "motion" }.map { it.id to it.label(language) },
            expressions = vocab.filter { it.kind == "expression" }.map { it.id to it.label(language) },
        )
    }
}