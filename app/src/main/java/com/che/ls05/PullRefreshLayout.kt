package com.che.ls05

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min

/**
 * Aşağı çekip yenileme (AndroidX yok). İçinde tek kaydırılabilir görünüm (ScrollView) durur.
 * Görünüm en üstteyken aşağı çekilince üstte dairesel gösterge çıkar; parmağı dirençle izler.
 * Eşik (80 dp) geçilip bırakılırsa [onRefresh] çağrılır. true dönerse gösterge [setRefreshing] (false)
 * gelene kadar döner; false dönerse ya da eşik geçilmediyse yerine geri çekilir.
 * İçerik yerinde kalır; gösterge içeriğin üstünde çizilir.
 */
class PullRefreshLayout(context: Context, private val target: View) : FrameLayout(context) {

    /** Yenileme başladıysa true. */
    var onRefresh: (() -> Boolean)? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val size = (40 * density).toInt()
    private val threshold = 80f * density
    private val maxPull = 128f * density
    // Yenilerken göstergenin durduğu çekme mesafesi (üstten 24 dp).
    private val restPull = 64f * density

    private val indicator = Indicator(context)

    private var startY = 0f
    private var startX = 0f
    private var dragging = false
    private var pull = 0f
    private var refreshing = false
    private var anim: ValueAnimator? = null

    init {
        addView(target, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(indicator, LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        indicator.visibility = View.GONE
        indicator.contentDescription = "Verileri yenile"
        // Görünüm dışına taşan gösterge kırpılır (üstte gizli başlar).
        clipChildren = true
        place(0f)
    }

    val isRefreshing: Boolean get() = refreshing

    /** Okuma bitti ya da başladı. false gelince gösterge yerine çekilir. */
    fun setRefreshing(on: Boolean) {
        if (on == refreshing) return
        refreshing = on
        if (on) {
            indicator.spinning = true
            indicator.progress = 1f
            animateTo(restPull)
        } else {
            animateTo(0f) { indicator.spinning = false }
        }
    }

    private fun canChildScrollUp(): Boolean = target.canScrollVertically(-1)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isEnabled || refreshing || anim?.isRunning == true) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.y
                startX = ev.x
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount > 1) return false
                val dy = ev.y - startY
                val dx = ev.x - startX
                if (!dragging && dy > touchSlop && dy > abs(dx) && !canChildScrollUp()) {
                    dragging = true
                    startY += touchSlop
                    indicator.visibility = View.VISIBLE
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val raw = (ev.y - startY).coerceAtLeast(0f)
                // Direnç: parmağın yarısı kadar; eşikten sonra daha da ağırlaşır.
                val half = raw * 0.5f
                val p = if (half <= threshold) half else threshold + (half - threshold) * 0.4f
                place(min(p, maxPull))
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                release()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                animateTo(0f)
            }
        }
        return true
    }

    private fun release() {
        if (pull >= threshold) {
            val started = onRefresh?.invoke() == true
            if (started) {
                // onRefresh içinde setRefreshing(true) çağrıldıysa tekrar başlatma.
                if (!refreshing) setRefreshing(true)
                return
            }
        }
        animateTo(0f)
    }

    /** Göstergeyi çekme mesafesine göre yerleştirir. */
    private fun place(p: Float) {
        pull = p
        indicator.translationY = p - size
        if (!refreshing) {
            indicator.progress = (p / threshold).coerceIn(0f, 1f)
            indicator.rotation = p / threshold * 180f
        }
        indicator.alpha = (p / (size * 0.75f)).coerceIn(0f, 1f)
        indicator.visibility = if (p > 0f) View.VISIBLE else View.GONE
    }

    private fun animateTo(to: Float, end: (() -> Unit)? = null) {
        anim?.cancel()
        val from = pull
        if (from == to) {
            place(to)
            end?.invoke()
            return
        }
        val a = ValueAnimator.ofFloat(from, to)
        a.duration = 220L
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { place(it.animatedValue as Float) }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) {
                cancelled = true
            }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!cancelled) end?.invoke()
            }
        })
        anim = a
        a.start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    /** Kart renginde disk, üstünde nane yay. Çekerken yay büyür; yenilerken döner. */
    private class Indicator(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()

        var progress = 0f
            set(v) {
                field = v
                invalidate()
            }
        var spinning = false
            set(v) {
                field = v
                if (v) rotation = 0f
                invalidate()
            }

        init {
            disc.color = context.getColor(R.color.card)
            // İnce kenar: açık temada kart zeminle karışmasın (gölge yok, şartname 3).
            ring.style = Paint.Style.STROKE
            ring.strokeWidth = 1f * density
            ring.color = context.getColor(R.color.border)
            arc.style = Paint.Style.STROKE
            arc.strokeWidth = 3f * density
            arc.strokeCap = Paint.Cap.ROUND
            arc.color = context.getColor(R.color.accent)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f
            canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2f, disc)
            canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)
            val ar = r * 0.5f
            box.set(cx - ar, cy - ar, cx + ar, cy + ar)
            if (spinning) {
                val t = SystemClock.uptimeMillis()
                val start = (t % 1000L) / 1000f * 360f
                // Yay 60° ile 270° arasında gidip gelir.
                val phase = (t % 1400L) / 1400f
                val sweep = 60f + 210f * (if (phase < 0.5f) phase * 2f else (1f - phase) * 2f)
                canvas.drawArc(box, start, sweep, false, arc)
                postInvalidateOnAnimation()
            } else if (progress > 0f) {
                canvas.drawArc(box, -90f, 300f * progress, false, arc)
            }
        }
    }
}
