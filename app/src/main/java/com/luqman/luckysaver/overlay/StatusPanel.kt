package com.luqman.luckysaver.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.luqman.luckysaver.R
import com.luqman.luckysaver.core.WaApp
import com.luqman.luckysaver.ui.Thumbs
import com.luqman.luckysaver.whatsapp.WaStatus
import com.luqman.luckysaver.whatsapp.WaStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The strip that slides up over WhatsApp when the bubble is tapped in WhatsApp mode: the statuses
 * WhatsApp has loaded, newest first. The one being watched is almost always first or second, and
 * tapping it saves it. Touching anywhere outside closes the strip.
 *
 * Plain views, like the rest of the overlay: no Compose lifecycle to host inside a service.
 */
class StatusPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val scope: CoroutineScope,
    private val store: WaStatusStore,
    /** Reports a save to the bubble, so it can bounce or shake. */
    private val onSaved: (ok: Boolean, message: String) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val onSurface = ContextCompat.getColor(context, R.color.overlay_on_surface)
    private val muted = ContextCompat.getColor(context, R.color.overlay_on_surface_muted)
    private val green = ContextCompat.getColor(context, R.color.whatsapp_green)
    private var root: View? = null
    private var saving = false

    val isShowing: Boolean get() = root != null

    fun show(app: WaApp, apps: List<WaApp>, statuses: List<WaStatus>, saved: Set<String>, onSwitch: (WaApp) -> Unit) {
        dismiss(notify = false)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(ContextCompat.getColor(context, R.color.overlay_surface))
            }
            elevation = 12 * density
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        val savedKeys = saved.toMutableSet()
        val unsaved = { statuses.count { it.key !in savedKeys } }
        val saveAll = button("Save all") { }
        fun refreshSaveAll() {
            val n = unsaved()
            saveAll.text = if (n == 0) "All saved" else "Save all ($n)"
            saveAll.isEnabled = n > 0 && !saving
            saveAll.alpha = if (saveAll.isEnabled) 1f else 0.5f
        }

        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "${app.label} statuses"
                setTextColor(onSurface)
                textSize = 16f
                paint.isFakeBoldText = true
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (statuses.isNotEmpty()) addView(saveAll)
            addView(button("Close") { dismiss() }.apply { contentDescription = "Close statuses" })
        })

        if (apps.size > 1) panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            apps.forEach { a ->
                addView(chip(a.label, selected = a == app) { if (a != app) onSwitch(a) })
            }
        })

        if (statuses.isEmpty()) {
            panel.addView(TextView(context).apply {
                text = "No statuses loaded yet. Watch one in ${app.label}, then tap the bubble again."
                setTextColor(muted)
                textSize = 14f
                setPadding(0, dp(12), 0, dp(4))
            })
        } else {
            panel.addView(TextView(context).apply {
                text = "Newest first · tap one to save it"
                setTextColor(muted)
                textSize = 13f
                setPadding(0, dp(2), 0, dp(10))
            })
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            statuses.take(MAX_TILES).forEachIndexed { i, s ->
                row.addView(tile(s, s.key in savedKeys, i) { tile ->
                    if (s.key in savedKeys || saving) return@tile
                    saving = true
                    tile.setSaving(true)
                    refreshSaveAll()
                    scope.launch {
                        val ok = runCatching { store.save(s) }.isSuccess
                        saving = false
                        tile.setSaving(false)
                        if (ok) {
                            savedKeys += s.key
                            tile.setSaved(true)
                        }
                        refreshSaveAll()
                        onSaved(ok, if (ok) "Saved to your gallery" else "Couldn't save: ${app.label} may have removed it")
                    }
                })
            }
            panel.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
            if (statuses.size > MAX_TILES) panel.addView(TextView(context).apply {
                text = "Showing the newest $MAX_TILES of ${statuses.size}. Open LuckySaver for all."
                setTextColor(muted)
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
            })
            saveAll.setOnClickListener {
                if (saving) return@setOnClickListener
                saving = true
                refreshSaveAll()
                scope.launch {
                    val todo = statuses.filter { it.key !in savedKeys }
                    val count = store.saveAll(todo)
                    savedKeys += store.savedKeys(todo)
                    saving = false
                    row.childViewsAsTiles().forEach { it.setSaved(it.statusKey in savedKeys) }
                    refreshSaveAll()
                    onSaved(count > 0, if (count > 0) "Saved $count status${if (count == 1) "" else "es"}" else "Nothing saved")
                }
            }
            refreshSaveAll()
        }

        val wrapper = FrameLayout(context).apply {
            setPadding(dp(8), 0, dp(8), dp(8))
            addView(panel)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) dismiss()
                false
            }
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            windowAnimations = android.R.style.Animation_InputMethod
        }
        runCatching { windowManager.addView(wrapper, layout) }.onSuccess { root = wrapper }
    }

    fun dismiss(notify: Boolean = true) {
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
        root = null
        if (notify) onClosed()
    }

    private fun tile(status: WaStatus, saved: Boolean, index: Int, onClick: (StatusTile) -> Unit): StatusTile {
        val tile = StatusTile(context, status.key)
        tile.layoutParams = LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)).apply { marginEnd = dp(8) }
        tile.contentDescription = buildString {
            append(if (status.isVideo) "Video status" else "Photo status")
            append(", ${index + 1} of newest")
        }
        tile.setSaved(saved)
        tile.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick(tile)
        }
        if (status.isVideo) tile.showVideoBadge()
        scope.launch {
            Thumbs.load(context.contentResolver, status.uri.toString(), dp(TILE_W))?.let(tile::setThumb)
        }
        return tile
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(green)
        textSize = 14f
        paint.isFakeBoldText = true
        minHeight = dp(40)
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = if (selected) "✓ $label" else label
        setTextColor(if (selected) ContextCompat.getColor(context, R.color.on_whatsapp_green) else onSurface)
        textSize = 13f
        minHeight = dp(36)
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        background = GradientDrawable().apply {
            cornerRadius = 18 * density
            if (selected) setColor(green) else setStroke(dp(1), muted)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(8) }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun LinearLayout.childViewsAsTiles(): List<StatusTile> =
        (0 until childCount).mapNotNull { getChildAt(it) as? StatusTile }

    private fun dp(v: Int) = (v * density).toInt()

    private companion object {
        const val MAX_TILES = 12
        const val TILE_W = 72
        const val TILE_H = 128
    }
}

/** One status in the strip: thumbnail, a play mark for videos, and a saving or saved overlay. */
private class StatusTile(context: Context, val statusKey: String) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val progress = ProgressBar(context).apply { visibility = View.GONE }
    private val savedMark = TextView(context).apply {
        text = "✓ Saved"
        textSize = 11f
        setTextColor(ContextCompat.getColor(context, R.color.on_whatsapp_green))
        background = GradientDrawable().apply {
            cornerRadius = 8 * density
            setColor(ContextCompat.getColor(context, R.color.whatsapp_green))
        }
        setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
        visibility = View.GONE
    }

    init {
        background = GradientDrawable().apply {
            cornerRadius = 10 * density
            setColor(ContextCompat.getColor(context, R.color.overlay_tile))
        }
        clipToOutline = true
        isClickable = true
        isFocusable = true
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(progress, LayoutParams((28 * density).toInt(), (28 * density).toInt(), Gravity.CENTER))
        addView(savedMark, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins((4 * density).toInt(), 0, 0, (4 * density).toInt())
        })
    }

    fun setThumb(bitmap: android.graphics.Bitmap) = image.setImageBitmap(bitmap)

    fun showVideoBadge() {
        addView(TextView(context).apply {
            text = "▶"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.overlay_on_surface))
            setShadowLayer(6f, 0f, 0f, ContextCompat.getColor(context, R.color.overlay_surface))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, (4 * density).toInt(), (6 * density).toInt(), 0)
        })
    }

    fun setSaving(on: Boolean) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        image.alpha = if (on) 0.5f else 1f
    }

    fun setSaved(on: Boolean) {
        savedMark.visibility = if (on) View.VISIBLE else View.GONE
        ViewCompat.setStateDescription(this, if (on) "Saved" else "Not saved, tap to save")
    }
}
