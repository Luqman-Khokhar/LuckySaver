package com.luqman.luckysaver.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * The floating button: an icon inside a circle, with a ring around the edge that shows download
 * progress. Drawn by hand rather than composed from widgets so the ring can sweep smoothly
 * without a layout pass on every frame.
 */
class BubbleView(context: Context, iconRes: Int) : View(context) {

    sealed interface State {
        data object Idle : State
        /** Resolving the link: no percentage to show yet, so the arc spins. */
        data object Working : State
        data class Progress(val fraction: Float) : State
        data object Success : State
        data object Error : State
    }

    private val density = resources.displayMetrics.density
    private val ringWidth = 4f * density
    private val icon: Drawable? = ContextCompat.getDrawable(context, iconRes)
    private val ringBounds = RectF()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_BODY
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        color = COLOR_TRACK
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        strokeCap = Paint.Cap.ROUND
    }

    private var spinner: ValueAnimator? = null
    private var spinAngle = 0f
    private var shownFraction = 0f
    private var fractionAnimator: ValueAnimator? = null

    var state: State = State.Idle
        private set

    init {
        val size = (56 * density).toInt()
        minimumWidth = size
        minimumHeight = size
        elevation = 8 * density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (56 * density).toInt()
        setMeasuredDimension(size, size)
    }

    fun setState(next: State) {
        val previous = state
        state = next
        if (next is State.Working) startSpinner() else stopSpinner()
        when (next) {
            is State.Progress -> animateFractionTo(next.fraction)
            is State.Idle -> shownFraction = 0f
            is State.Success -> {
                // Fill the ring before bouncing, so a fast download still reads as "completed".
                animateFractionTo(1f)
                bounce()
            }
            is State.Error -> shake()
            else -> Unit
        }
        if (previous != next) invalidate()
    }

    private fun animateFractionTo(target: Float) {
        fractionAnimator?.cancel()
        fractionAnimator = ValueAnimator.ofFloat(shownFraction, target.coerceIn(0f, 1f)).apply {
            duration = 220
            addUpdateListener {
                shownFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startSpinner() {
        if (spinner?.isRunning == true) return
        spinner = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spinAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopSpinner() {
        spinner?.cancel()
        spinner = null
    }

    fun bounce() {
        animate().cancel()
        animate().scaleX(1.3f).scaleY(1.3f).setDuration(130)
            .withEndAction {
                animate().scaleX(0.94f).scaleY(0.94f).setDuration(110)
                    .withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(110).start() }
                    .start()
            }
            .start()
    }

    private fun shake() {
        animate().cancel()
        animate().translationX(-14f).setDuration(60)
            .withEndAction {
                animate().translationX(14f).setDuration(60)
                    .withEndAction { animate().translationX(0f).setDuration(60).start() }
                    .start()
            }
            .start()
    }

    fun pressed(down: Boolean) {
        animate().scaleX(if (down) 0.85f else 1f).scaleY(if (down) 0.85f else 1f)
            .setDuration(80).start()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - ringWidth

        canvas.drawCircle(cx, cy, radius, bodyPaint)

        icon?.let {
            val inset = (14 * density).toInt()
            it.setBounds(inset, inset, width - inset, height - inset)
            it.draw(canvas)
        }

        ringBounds.set(
            cx - radius - ringWidth / 2, cy - radius - ringWidth / 2,
            cx + radius + ringWidth / 2, cy + radius + ringWidth / 2,
        )

        when (val s = state) {
            is State.Idle -> Unit
            is State.Working -> {
                ringPaint.color = COLOR_WORKING
                canvas.drawCircle(cx, cy, radius + ringWidth / 2, trackPaint)
                canvas.drawArc(ringBounds, spinAngle - 90f, 90f, false, ringPaint)
            }
            is State.Progress, is State.Success -> {
                ringPaint.color = if (s is State.Success) COLOR_SUCCESS else COLOR_WORKING
                canvas.drawCircle(cx, cy, radius + ringWidth / 2, trackPaint)
                canvas.drawArc(ringBounds, -90f, 360f * shownFraction, false, ringPaint)
            }
            is State.Error -> {
                ringPaint.color = COLOR_ERROR
                canvas.drawCircle(cx, cy, radius + ringWidth / 2, ringPaint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        stopSpinner()
        fractionAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val COLOR_BODY = 0xEE101418.toInt()
        val COLOR_TRACK = Color.argb(60, 255, 255, 255)
        const val COLOR_WORKING = 0xFF4C8DFF.toInt()
        const val COLOR_SUCCESS = 0xFF1DB954.toInt()
        const val COLOR_ERROR = 0xFFE23B3B.toInt()
    }
}
