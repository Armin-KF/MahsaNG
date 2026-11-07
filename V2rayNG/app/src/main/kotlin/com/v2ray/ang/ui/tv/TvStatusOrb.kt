package com.v2ray.ang.ui.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import com.v2ray.ang.R
import kotlin.math.min
import kotlin.math.sin

/** Connection status emblem: a shield in a ring, with a halo that breathes when live and an arc that spins when busy. */
class TvStatusOrb @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Mode { IDLE, BUSY, LIVE }

    private val icon = AppCompatResources.getDrawable(context, R.drawable.ic_tv_shield)!!.mutate()
    private val stroke = resources.getDimension(R.dimen.tv_status_stroke)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val arcBounds = RectF()

    private var color = Color.GRAY
    private var mode = Mode.IDLE
    private var phase = 0f
    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    private var colorAnimator: ValueAnimator? = null

    fun setState(target: Int, next: Mode) {
        if (next != mode) {
            mode = next
            loop.cancel()
            loop.duration = if (mode == Mode.BUSY) 1100L else 2800L
            if (mode != Mode.IDLE && isAttachedToWindow) loop.start()
        }
        if (target != color) {
            colorAnimator?.cancel()
            colorAnimator = ValueAnimator.ofArgb(color, target).apply {
                duration = resources.getInteger(R.integer.tv_anim_status).toLong()
                addUpdateListener { color = it.animatedValue as Int; invalidate() }
                start()
            }
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mode != Mode.IDLE) loop.start()
    }

    override fun onDetachedFromWindow() {
        loop.cancel()
        colorAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f
        val ringRadius = radius * 0.64f
        val glow = when (mode) {
            Mode.LIVE -> 0.7f + 0.3f * sin(phase * 2 * Math.PI).toFloat()
            Mode.BUSY -> 0.55f
            Mode.IDLE -> 0.2f
        }
        halo.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(ColorUtils.setAlphaComponent(color, (120 * glow).toInt()), ColorUtils.setAlphaComponent(color, 0)),
            floatArrayOf(ringRadius / radius, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, halo)

        fill.color = ColorUtils.setAlphaComponent(color, 44)
        canvas.drawCircle(cx, cy, ringRadius, fill)

        arcBounds.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
        if (mode == Mode.BUSY) {
            ring.color = ColorUtils.setAlphaComponent(color, 70)
            canvas.drawCircle(cx, cy, ringRadius, ring)
            arc.color = color
            canvas.drawArc(arcBounds, phase * 360f - 90f, 100f, false, arc)
        } else {
            ring.color = color
            canvas.drawCircle(cx, cy, ringRadius, ring)
        }

        val half = (ringRadius * 0.5f).toInt()
        icon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        icon.setTint(color)
        icon.draw(canvas)
    }
}
