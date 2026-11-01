package com.wes.projos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A liquid glass highlight.
 *
 * ring = true  -> roaming cursor: an even white outline around the focused item.
 * ring = false -> dock selection: a soft pill inset in the dock (bright when the dock has focus, dim otherwise).
 *
 * While it travels between two items it is drawn as two blobs joined by a thin neck
 * (a spring drives the leading blob, a slower spring drives the trailing blob).
 * When it stops, it settles into the crisp resting shape.
 *
 * Set [provider] to return the target rectangle in this view's own coordinates.
 */
class LiquidLens(context: Context, private val ring: Boolean) : View(context) {

    private class Body {
        var x = 0f
        var y = 0f
        var w = 0f
        var h = 0f
        var vx = 0f
        var vy = 0f
        var vw = 0f
        var vh = 0f
    }

    private val d = resources.displayMetrics.density
    private val head = Body()
    private val tail = Body()

    private var tx = 0f
    private var ty = 0f
    private var tw = 0f
    private var th = 0f
    private var tr = 0f
    private var cr = 0f

    private var shown = false
    private var moving = false
    private var crisp = 1f
    private var act = 1f
    private var actTarget = 1f
    private var follow = 0L
    private var last = 0L
    private var looping = false

    var provider: (() -> RectF?)? = null

    /** corner radius in px for the resting shape; 0 means a full pill */
    var radius: Float = 0f

    var active: Boolean = true
        set(v) {
            field = v
            actTarget = if (v) 1f else 0f
            kick()
        }

    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val blob = Path()
    private val tmp = Path()
    private val mat = Matrix()

    private val frame = Runnable { step() }

    // ---------- control ----------

    private fun aimNow() {
        val r = provider?.invoke() ?: return
        tx = r.left
        ty = r.top
        tw = r.width()
        th = r.height()
        tr = if (radius <= 0f) min(tw, th) / 2f else min(radius, min(tw, th) / 2f)
    }

    private fun snap() {
        for (b in arrayOf(head, tail)) {
            b.x = tx
            b.y = ty
            b.w = tw
            b.h = th
            b.vx = 0f
            b.vy = 0f
            b.vw = 0f
            b.vh = 0f
        }
        cr = tr
    }

    /** appear on the current target with no travel (fades in) */
    fun reveal() {
        aimNow()
        snap()
        shown = true
        moving = false
        crisp = 0f
        follow = SystemClock.uptimeMillis() + 450
        kick()
    }

    /** glide from where it is to the current target */
    fun glide() {
        if (!shown) {
            reveal()
            return
        }
        if (!moving) snap()
        aimNow()
        moving = true
        follow = SystemClock.uptimeMillis() + 450
        kick()
    }

    fun hide() {
        shown = false
        moving = false
        invalidate()
    }

    private fun kick() {
        if (!looping) {
            looping = true
            last = SystemClock.uptimeMillis()
            postOnAnimation(frame)
        }
        invalidate()
    }

    // ---------- animation ----------

    private fun spring(b: Body, k: Float, c: Float, dt: Float) {
        b.vx += ((tx - b.x) * k - b.vx * c) * dt
        b.x += b.vx * dt
        b.vy += ((ty - b.y) * k - b.vy * c) * dt
        b.y += b.vy * dt
        b.vw += ((tw - b.w) * k - b.vw * c) * dt
        b.w += b.vw * dt
        b.vh += ((th - b.h) * k - b.vh * c) * dt
        b.h += b.vh * dt
    }

    private fun err(b: Body): Float =
        abs(tx - b.x) + abs(ty - b.y) + abs(tw - b.w) + abs(th - b.h)

    private fun spd(b: Body): Float =
        abs(b.vx) + abs(b.vy) + abs(b.vw) + abs(b.vh)

    private fun step() {
        val now = SystemClock.uptimeMillis()
        val dt = max(0.001f, min(0.033f, (now - last) / 1000f))
        last = now

        if (shown) {
            if (moving || now < follow) aimNow()
            if (moving) {
                spring(head, 360f, 25f, dt)
                spring(tail, 150f, 22f, dt)
                cr += (tr - cr) * min(1f, dt * 16f)
                if (err(head) < 0.2f * d && err(tail) < 0.5f * d && spd(head) < 8f * d && spd(tail) < 8f * d) {
                    snap()
                    moving = false
                    crisp = 0f
                }
            } else if (crisp < 1f) {
                crisp = min(1f, crisp + dt / 0.14f)
            }
        }
        act += (actTarget - act) * min(1f, dt * 12f)
        invalidate()

        val busy = (shown && (moving || crisp < 1f || now < follow)) || abs(actTarget - act) > 0.01f
        if (busy) {
            postOnAnimation(frame)
        } else {
            looping = false
        }
    }

    // ---------- drawing ----------

    override fun onDraw(c: Canvas) {
        if (!shown) return
        if (moving) drawBlob(c) else drawRest(c)
    }

    private fun drawRest(c: Canvas) {
        val a = crisp
        val r = RectF(tx, ty, tx + tw, ty + th)
        if (ring) {
            r.inset(1.5f * d, 1.5f * d)
            fillP.shader = null
            fillP.color = Color.argb((26 * a).toInt(), 255, 255, 255)
            c.drawRoundRect(r, tr, tr, fillP)
            glowP.strokeWidth = 7f * d
            glowP.color = Color.argb((46 * a).toInt(), 255, 255, 255)
            c.drawRoundRect(r, tr, tr, glowP)
            strokeP.strokeWidth = 3f * d
            strokeP.color = Color.argb((245 * a).toInt(), 255, 255, 255)
            c.drawRoundRect(r, tr, tr, strokeP)
        } else {
            val top = ((50f + 62f * act) * a).toInt()
            val bot = ((20f + 28f * act) * a).toInt()
            fillP.shader = LinearGradient(
                0f, ty, 0f, ty + th,
                Color.argb(top, 255, 255, 255), Color.argb(bot, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
            c.drawRoundRect(r, tr, tr, fillP)
            fillP.shader = null
            r.inset(d / 2f, d / 2f)
            strokeP.strokeWidth = d
            strokeP.color = Color.argb(((70f + 130f * act) * a).toInt(), 255, 255, 255)
            c.drawRoundRect(r, tr, tr, strokeP)
        }
    }

    private fun drawBlob(c: Canvas) {
        val hr = RectF(head.x, head.y, head.x + head.w, head.y + head.h)
        val tcx = tail.x + tail.w / 2f
        val tcy = tail.y + tail.h / 2f
        val dx = hr.centerX() - tcx
        val dy = hr.centerY() - tcy
        val dist = hypot(dx, dy)

        val sc = if (dist > 12f * d) 0.88f else 1f
        val tw2 = tail.w * sc
        val th2 = tail.h * sc
        val trc = RectF(tcx - tw2 / 2f, tcy - th2 / 2f, tcx + tw2 / 2f, tcy + th2 / 2f)
        val rad = max(0f, min(cr, min(hr.width(), hr.height()) / 2f))
        val rad2 = max(0f, min(cr * sc, min(trc.width(), trc.height()) / 2f))

        blob.reset()
        blob.addRoundRect(hr, rad, rad, Path.Direction.CW)
        tmp.reset()
        tmp.addRoundRect(trc, rad2, rad2, Path.Direction.CW)
        var ok = blob.op(tmp, Path.Op.UNION)

        if (dist > 2f) {
            val ang = atan2(dy, dx)
            val s = abs(sin(ang))
            val co = abs(cos(ang))
            val aH = (s * hr.width() + co * hr.height()) / 2f
            val aT = (s * trc.width() + co * trc.height()) / 2f
            val m = min(aH, aT)
            if (m > 1f) {
                val p = m * max(0.35f, 1f - dist / (8f * m))
                tmp.reset()
                tmp.moveTo(0f, -aT)
                tmp.cubicTo(dist * 0.4f, -p, dist * 0.6f, -p, dist, -aH)
                tmp.lineTo(dist, aH)
                tmp.cubicTo(dist * 0.6f, p, dist * 0.4f, p, 0f, aT)
                tmp.close()
                mat.reset()
                mat.postRotate(Math.toDegrees(ang.toDouble()).toFloat())
                mat.postTranslate(tcx, tcy)
                tmp.transform(mat)
                ok = blob.op(tmp, Path.Op.UNION) && ok
            }
        }
        if (!ok) {
            blob.reset()
            blob.addRoundRect(hr, rad, rad, Path.Direction.CW)
        }

        fillP.shader = null
        fillP.color = Color.argb(if (ring) 46 else (70f + 40f * act).toInt(), 255, 255, 255)
        c.drawPath(blob, fillP)
        strokeP.strokeWidth = (if (ring) 2f else 1.2f) * d
        strokeP.color = Color.argb(if (ring) 235 else (120f + 80f * act).toInt(), 255, 255, 255)
        c.drawPath(blob, strokeP)
    }
}
