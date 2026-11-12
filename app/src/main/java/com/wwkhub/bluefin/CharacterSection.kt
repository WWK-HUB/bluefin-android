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
        val builtIn = PackRepository.builtIn(activity)
        val current = PackRepository.current(activity)
        addRow(builtIn, current.id == builtIn.id, removable = false)
        PackRepository.listImported(activity).forEach { pack ->
            addRow(pack, current.id == pack.id, removable = true)
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
        var initializing = true
        val currentScheme = PackRepository.readScheme(activity, manifest)
        val picks: MutableMap<String, String> = manifest.picksFor(currentScheme).toMutableMap()

        if (manifest.presets.isNotEmpty()) {
            box.addView(label(R.string.character_axis_preset))
            val labels = manifest.presets.map { it.name?.preferred()?.ifBlank { it.id } ?: it.id }
            val spinner = Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
                setSelection(manifest.presets.indexOfFirst { it.id == currentScheme }.coerceAtLeast(0))
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        if (initializing) return
                        val preset = manifest.presets.getOrNull(position) ?: return
                        PackRepository.writeScheme(activity, manifest, preset.id)
                        activity.switchCharacter(pack.id)
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
                setSelection(axis.options.indexOfFirst { it.id == picks[axis.id] }.coerceAtLeast(0))
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        if (initializing) return
                        val option = axis.options.getOrNull(position) ?: return
                        picks[axis.id] = option.id
                        val scheme = manifest.axes.joinToString("-") { axisDef ->
                            picks.getValue(axisDef.id)
                        }
                        PackRepository.writeScheme(activity, manifest, scheme)
                        activity.switchCharacter(pack.id)
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                }
            }
            box.addView(spinner)
        }
        initializing = false
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
        val thumbPath = pack.manifest.thumb
        val dir = pack.dir
        if (thumbPath != null && dir != null) {
            val file = File(dir, thumbPath)
            if (file.isFile && file.length() <= 1024L * 1024L && PackPaths.isSupportedThumb(thumbPath)) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    return ImageView(activity).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundResource(R.drawable.bg_character_current)
                    }
                }
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
