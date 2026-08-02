package com.odysseywatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the auditorium: screen at the top, seats laid out on the venue's own grid.
 *
 * Seats are classified into four visual states rather than two, because "available" on
 * its own is misleading here — the front rows and the held accessibility seats are
 * almost always free and are exactly what the user is trying to avoid.
 */
class SeatMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        val GOLD = Color.parseColor("#E0B25A")
        val GOLD_DIM = Color.parseColor("#6E5A2E")
        val OCCUPIED = Color.parseColor("#2A2A34")
        val EXCLUDED = Color.parseColor("#3A3A48")
        val BROKEN = Color.parseColor("#241A1A")
        val LABEL = Color.parseColor("#8A8A99")
        val SCREEN = Color.parseColor("#C8963E")
    }

    private var map: SeatMap? = null
    private var avail: Map<String, String> = emptyMap()
    private var minRow: Char = 'C'

    private val seatPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL
        textAlign = Paint.Align.CENTER
    }
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SCREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()

    fun bind(map: SeatMap, availability: Map<String, String>, minRow: Char) {
        this.map = map
        this.avail = availability
        this.minRow = minRow
        requestLayout()
        invalidate()
    }

    /** Counts for the caller's legend/summary: matching, other free, taken. */
    fun counts(): Triple<Int, Int, Int> {
        val m = map ?: return Triple(0, 0, 0)
        var good = 0; var otherFree = 0; var taken = 0
        for (s in m.seats) {
            val st = avail[s.id]
            when {
                Watcher.isGood(s, st, minRow) -> good++
                st == "Available" -> otherFree++
                else -> taken++
            }
        }
        return Triple(good, otherFree, taken)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val m = map
        val h = if (m == null || m.rows == 0) 200
        else {
            // Keep seats square: cell size is driven by width, height follows.
            val cell = (w - paddingLeft - paddingRight - rowLabelWidth()) / max(1, m.cols).toFloat()
            (cell * m.rows + screenHeight() + paddingTop + paddingBottom).toInt()
        }
        setMeasuredDimension(w, h)
    }

    private fun rowLabelWidth() = 56f
    private fun screenHeight() = 64f

    override fun onDraw(canvas: Canvas) {
        val m = map ?: return
        if (m.rows == 0 || m.cols == 0) return

        val left = paddingLeft + rowLabelWidth()
        val usableW = width - left - paddingRight
        val cell = usableW / m.cols.toFloat()
        val top = paddingTop + screenHeight()

        // screen
        val sx = left + usableW * 0.08f
        val sy = paddingTop + screenHeight() * 0.45f
        canvas.drawLine(sx, sy, left + usableW * 0.92f, sy, screenPaint)
        textPaint.textSize = 26f
        textPaint.color = SCREEN
        canvas.drawText("SCREEN", left + usableW / 2f, sy - 16f, textPaint)

        val gap = min(cell * 0.16f, 5f)
        val r = max(2f, (cell - gap) * 0.22f)

        // row labels, drawn once per row from the first seat we see in it
        val seenRows = HashSet<Int>()
        textPaint.textSize = min(cell * 0.62f, 26f)

        for (s in m.seats) {
            val st = avail[s.id]
            val good = Watcher.isGood(s, st, minRow)
            seatPaint.style = Paint.Style.FILL
            var stroke = false
            seatPaint.color = when {
                good -> GOLD
                st == "Available" -> { stroke = true; EXCLUDED }
                st == "Broken" -> BROKEN
                else -> OCCUPIED
            }

            val x = left + s.col * cell
            val y = top + s.rowIndex * cell
            rect.set(x + gap / 2f, y + gap / 2f, x + cell - gap / 2f, y + cell - gap / 2f)
            canvas.drawRoundRect(rect, r, r, seatPaint)
            if (stroke) {
                strokePaint.color = GOLD_DIM
                canvas.drawRoundRect(rect, r, r, strokePaint)
            }

            if (seenRows.add(s.rowIndex)) {
                textPaint.color = LABEL
                canvas.drawText(
                    s.row,
                    paddingLeft + rowLabelWidth() / 2f,
                    y + cell / 2f + textPaint.textSize / 3f,
                    textPaint
                )
            }
        }
    }
}
