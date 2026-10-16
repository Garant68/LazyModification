package com.example.lazymodification.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/**
 * Перетаскиваемый пальцем ползунок прокрутки для ScrollView / NestedScrollView /
 * RecyclerView. Тап по полосе — прыжок, перетаскивание — плавная прокрутка.
 * Если содержимое помещается целиком, ползунок скрыт.
 */
class DragScrollThumb @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var scrollView: ScrollView? = null
    private var nested: NestedScrollView? = null
    private var recycler: RecyclerView? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val thumbW = 6f * density
    private val minThumbH = 40f * density
    private val edgePad = 4f * density
    private val baseColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)

    private var thumbTop = 0f
    private var thumbH = 40f
    private var dragging = false
    private var grabOffset = 0f

    /** Сообщает наружу о начале перетаскивания (например, чтобы выключить автопрокрутку лога). */
    var onUserDrag: (() -> Unit)? = null

    private val scrollWatcher = ViewTreeObserver.OnScrollChangedListener { refresh() }
    private val layoutWatcher = ViewTreeObserver.OnGlobalLayoutListener { refresh() }

    fun bind(view: ScrollView) {
        scrollView = view; nested = null; recycler = null
        hook(view)
    }

    fun bind(view: NestedScrollView) {
        scrollView = null; nested = view; recycler = null
        hook(view)
    }

    fun bind(view: RecyclerView) {
        scrollView = null; nested = null; recycler = view
        hook(view)
        view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(v: RecyclerView, dx: Int, dy: Int) { refresh() }
        })
    }

    private fun hook(v: View) {
        v.viewTreeObserver.addOnScrollChangedListener(scrollWatcher)
        v.viewTreeObserver.addOnGlobalLayoutListener(layoutWatcher)
        post { refresh() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refresh()
    }

    fun refresh() {
        val max = maxScroll()
        if (max <= 0 || height <= 0) {
            if (visibility != INVISIBLE) visibility = INVISIBLE
            return
        }
        if (visibility != VISIBLE) visibility = VISIBLE
        val track = height - edgePad * 2f
        if (track <= 0f) return
        thumbH = (track * visibleFraction()).coerceIn(minOf(40f * density, track), track)
        val frac = (currentOffset().toFloat() / max).coerceIn(0f, 1f)
        thumbTop = edgePad + (track - thumbH) * frac
        invalidate()
    }

    private fun maxScroll(): Int {
        scrollView?.let { v -> val c = v.getChildAt(0)?.height ?: 0; return c - v.height }
        nested?.let { v -> val c = v.getChildAt(0)?.height ?: 0; return c - v.height }
        recycler?.let { v -> return v.computeVerticalScrollRange() - v.computeVerticalScrollExtent() }
        return 0
    }

    private fun currentOffset(): Int {
        scrollView?.let { return it.scrollY }
        nested?.let { return it.scrollY }
        recycler?.let { return it.computeVerticalScrollOffset() }
        return 0
    }

    private fun visibleFraction(): Float {
        scrollView?.let { v -> val c = v.getChildAt(0)?.height ?: 0; return if (c <= 0) 1f else v.height.toFloat() / c }
        nested?.let { v -> val c = v.getChildAt(0)?.height ?: 0; return if (c <= 0) 1f else v.height.toFloat() / c }
        recycler?.let { v ->
            val r = v.computeVerticalScrollRange()
            return if (r <= 0) 1f else v.computeVerticalScrollExtent().toFloat() / r
        }
        return 1f
    }

    private fun seekTo(target: Int) {
        val clamped = target.coerceIn(0, maxOf(0, maxScroll()))
        scrollView?.let { it.scrollTo(0, clamped); return }
        nested?.let { it.scrollTo(0, clamped); return }
        recycler?.let { v ->
            val cur = v.computeVerticalScrollOffset()
            v.scrollBy(0, clamped - cur)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != VISIBLE) return
        val cx = width - edgePad - thumbW / 2f
        val alpha = if (dragging) 0xCC else 0x66
        paint.color = (baseColor and 0x00FFFFFF) or (alpha shl 24)
        canvas.drawRoundRect(cx - thumbW / 2f, thumbTop, cx + thumbW / 2f, thumbTop + thumbH, thumbW / 2f, thumbW / 2f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                grabOffset = if (event.y >= thumbTop && event.y <= thumbTop + thumbH) event.y - thumbTop else thumbH / 2f
                parent?.requestDisallowInterceptTouchEvent(true)
                onUserDrag?.invoke()
                dragTo(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) { dragTo(event.y); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dragTo(y: Float) {
        val track = height - edgePad * 2f
        if (track <= 0f) return
        val extent = track - thumbH
        val frac = if (extent <= 0f) 0f else ((y - edgePad - grabOffset) / extent).coerceIn(0f, 1f)
        seekTo((frac * maxScroll()).toInt())
        thumbTop = edgePad + extent * frac
        invalidate()
    }

    override fun onDetachedFromWindow() {
        for (v in listOfNotNull(scrollView, nested, recycler)) {
            try { v.viewTreeObserver.removeOnScrollChangedListener(scrollWatcher) } catch (_: Throwable) { }
        }
        super.onDetachedFromWindow()
    }
}