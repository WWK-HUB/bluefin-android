package com.wwkhub.bluefin

import android.graphics.BitmapFactory
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Settings 「角色」 management. The built-in Bluefin is always first and cannot
 * be deleted or overwritten; imported packs are listed below with the current
 * appearance highlighted and optional dress-up axes under it.
 */
internal class CharacterSection(
    private val activity: MainActivity,
    parent: FrameLayout,
) {
    private val section: View = LayoutInflater.from(activity)
        .inflate(R.layout.section_character_settings, parent, true)
    private val list: LinearLayout = section.findViewById(R.id.character_list)

    init {
        section.findViewById<View>(R.id.character_import).setOnClickListener {
            activity.promptPackImportSource()
        }
        refresh()
    }

    fun refresh() {
        if (activity.isFinishing || activity.isDestroyed) return
        list.removeAllViews()
        val current = PackRepository.current(activity)
        (PackRepository.listBuiltIn(activity) + PackRepository.listImported(activity)).forEach { pack ->
            addRow(pack, current.id == pack.id, removable = !pack.builtIn)
            if (current.id == pack.id) addAxes(pack)
        }
    }

    private fun addRow(pack: PackAppearance, current: Boolean, removable: Boolean) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            if (current) setBackgroundResource(R.drawable.bg_character_current)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }
        row.addView(thumbnail(pack), LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            rightMargin = dp(10)
        })
        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val name = TextView(activity).apply {
            text = pack.displayName
            textSize = 15f
            setTextColor(activity.getColor(R.color.ink))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textColumn.addView(name)
        val detail = StringBuilder()
        if (pack.builtIn) {
            detail.append(activity.getString(R.string.character_builtin))
        } else {
            detail.append(activity.getString(R.string.character_version, pack.manifest.version))
            pack.manifest.author?.let {
                detail.append(" · ").append(activity.getString(R.string.character_author, it))
            }
        }
        if (current) detail.append(" · ").append(activity.getString(R.string.character_in_use))
        textColumn.addView(TextView(activity).apply {
            text = detail.toString()
            textSize = 12f
            setTextColor(activity.getColor(R.color.muted))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!current) {
            row.addView(MaterialButton(activity).apply {
                text = activity.getString(R.string.character_use)
                textSize = 13f
                minWidth = 0
                minHeight = dp(44)
                setOnClickListener {
                    activity.switchCharacter(pack.id)
                    refresh()
                }
            })
        }
        if (removable) {
            row.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = activity.getString(R.string.character_delete)
                textSize = 13f
                minWidth = 0
                minHeight = dp(44)
                setOnClickListener { confirmDelete(pack) }
            })
        }
        list.addView(row)
    }

    private fun addAxes(pack: PackAppearance) {
        val manifest = pack.manifest
        if (manifest.axes.isEmpty()) return
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }
        box.addView(TextView(activity).apply {
            text = activity.getString(R.string.character_axes)
            textSize = 13f
            setTextColor(activity.getColor(R.color.ink))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val currentScheme = PackRepository.readScheme(activity, manifest)
        val picks = manifest.picksFor(currentScheme)
        fun selectScheme(scheme: String) {
            val normalized = manifest.schemeFor(scheme)
            if (normalized == currentScheme) return
            PackRepository.writeScheme(activity, manifest, normalized)
            activity.switchCharacter(pack.id)
        }

        if (manifest.presets.isNotEmpty()) {
            box.addView(label(R.string.character_axis_preset))
            val custom = activity.getString(R.string.character_custom_scheme)
            val labels = listOf(custom) + manifest.presets.map { it.name?.preferred()?.ifBlank { it.id } ?: it.id }
            val selected = manifest.presets.indexOfFirst { it.id == currentScheme } + 1
            val spinner = Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
                setSelection(selected, false)
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        if (position == selected || !isAttachedToWindow) return
                        val preset = manifest.presets.getOrNull(position - 1) ?: return
                        selectScheme(preset.id)
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                }
            }
            box.addView(spinner)
        }

        manifest.axes.forEach { axis ->
            box.addView(labelText(axis.name.preferred().ifBlank { axis.id }))
            val spinner = Spinner(activity).apply {
                adapter = ArrayAdapter(
                    activity,
                    android.R.layout.simple_spinner_dropdown_item,
                    axis.options.map { it.name.preferred().ifBlank { it.id } },
                )
                val selected = axis.options.indexOfFirst { it.id == picks[axis.id] }.coerceAtLeast(0)
                setSelection(selected, false)
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        if (position == selected || !isAttachedToWindow) return
                        val option = axis.options.getOrNull(position) ?: return
                        val scheme = manifest.axes.joinToString("-") { axisDef ->
                            if (axisDef.id == axis.id) option.id else picks.getValue(axisDef.id)
                        }
                        selectScheme(scheme)
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                }
            }
            box.addView(spinner)
        }
        list.addView(box)
    }

    private fun label(resource: Int): TextView = TextView(activity).apply {
        text = activity.getString(resource)
        textSize = 12f
        setTextColor(activity.getColor(R.color.muted))
        setPadding(0, dp(6), 0, 0)
    }

    private fun labelText(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTextColor(activity.getColor(R.color.muted))
        setPadding(0, dp(6), 0, 0)
    }

    private fun thumbnail(pack: PackAppearance): View {
        val manifest = pack.manifest
        val picks = manifest.picksFor(PackRepository.readScheme(activity, manifest))
        val defaults = manifest.picksFor(manifest.defaultScheme())
        val axis = manifest.axes.lastOrNull { picks[it.id] != defaults[it.id] } ?: manifest.axes.firstOrNull()
        val paths = listOfNotNull(axis?.option(picks[axis.id])?.thumb, manifest.thumb).distinct()
        val bitmap = paths.firstNotNullOfOrNull { raw ->
            val path = PackPaths.normalizeArchivePath(raw) ?: return@firstNotNullOfOrNull null
            if (!PackPaths.isSupportedThumb(path)) return@firstNotNullOfOrNull null
            runCatching {
                val dir = pack.dir
                if (dir == null) {
                    activity.assets.open("${PreviewEntry.bundledPackPath(pack.id)}/$path")
                        .use { BitmapFactory.decodeStream(it) }
                } else {
                    val file = File(dir, path)
                    if (file.isFile && file.length() <= 1024L * 1024L) BitmapFactory.decodeFile(file.path) else null
                }
            }.getOrNull()
        }
        if (bitmap != null) {
            return ImageView(activity).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_character_current)
            }
        }
        return TextView(activity).apply {
            text = pack.displayName.take(1)
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.bg_orb)
        }
    }

    private fun confirmDelete(pack: PackAppearance) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.character_delete_title)
            .setMessage(R.string.character_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.character_delete) { _, _ ->
                activity.deleteCharacter(pack.id)
                refresh()
            }
            .show()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
