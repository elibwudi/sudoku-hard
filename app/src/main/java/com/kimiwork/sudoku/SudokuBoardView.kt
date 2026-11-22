package com.kimiwork.sudoku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 自定义棋盘 View：负责绘制格子、高亮、数字与草稿标记，并把点击映射为格号。
 */
class SudokuBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var game: Game? = null
        set(value) { field = value; invalidate() }

    var onCellSelected: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val givenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x21, 0x24, 0x33)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val entryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x4A, 0x5B, 0xD4)
        textAlign = Paint.Align.CENTER
    }
    private val wrongPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xE5, 0x39, 0x35)
        textAlign = Paint.Align.CENTER
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x8A, 0x90, 0xA3)
        textAlign = Paint.Align.CENTER
    }
    private val selectedFill = Paint().apply { color = Color.rgb(0xC9, 0xD2, 0xFF) }
    private val peerFill = Paint().apply { color = Color.rgb(0xEC, 0xEF, 0xFB) }
    private val sameFill = Paint().apply { color = Color.rgb(0xDE, 0xE3, 0xFF) }
    private val thinLine = Paint().apply {
        color = Color.rgb(0xD9, 0xDC, 0xE6)
        strokeWidth = density
    }
    private val thickLine = Paint().apply {
        color = Color.rgb(0x3A, 0x3F, 0x52)
        strokeWidth = 2.2f * density
    }
    private val cellRect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w) // 正方形棋盘
    }

    override fun onDraw(canvas: Canvas) {
        val g = game ?: return
        val cell = width / 9f
        canvas.drawColor(Color.WHITE)

        // ---- 高亮：同行列宫 → 同数字 → 选中格 ----
        if (g.selected >= 0) {
            val s = g.selected
            val sr = s / 9
            val sc = s % 9
            val sb = SudokuEngine.boxOf(s)
            for (i in 0 until 81) {
                if (i / 9 == sr || i % 9 == sc || SudokuEngine.boxOf(i) == sb) {
                    fillCell(canvas, i, cell, peerFill)
                }
            }
            val v = g.valueAt(s)
            if (v != 0) {
                for (i in 0 until 81) {
                    if (i != s && g.valueAt(i) == v) fillCell(canvas, i, cell, sameFill)
                }
            }
            fillCell(canvas, s, cell, selectedFill)
        }

        // ---- 数字与草稿 ----
        givenPaint.textSize = cell * 0.60f
        entryPaint.textSize = cell * 0.60f
        wrongPaint.textSize = cell * 0.60f
        notePaint.textSize = cell * 0.24f

        for (i in 0 until 81) {
            val r = i / 9
            val c = i % 9
            val cx = (c + 0.5f) * cell
            if (g.puzzle[i] != 0) {
                canvas.drawCenteredDigit(g.puzzle[i], cx, (r + 0.5f) * cell, givenPaint)
            } else if (g.entries[i] != 0) {
                val p = if (g.isWrong(i)) wrongPaint else entryPaint
                canvas.drawCenteredDigit(g.entries[i], cx, (r + 0.5f) * cell, p)
            } else if (g.notes[i] != 0) {
                for (d in 1..9) {
                    if (g.notes[i] and SudokuEngine.bitOf(d) == 0) continue
                    val sr = (d - 1) / 3
                    val sc = (d - 1) % 3
                    val nx = c * cell + (sc + 0.5f) * cell / 3f
                    val ny = r * cell + (sr + 0.5f) * cell / 3f
                    canvas.drawCenteredDigit(d, nx, ny, notePaint)
                }
            }
        }

        // ---- 网格线 ----
        for (k in 0..9) {
            val p = if (k % 3 == 0) thickLine else thinLine
            val pos = k * cell
            canvas.drawLine(pos, 0f, pos, height.toFloat(), p)
            canvas.drawLine(0f, pos, width.toFloat(), pos, p)
        }
    }

    private fun fillCell(canvas: Canvas, i: Int, cell: Float, paint: Paint) {
        val r = i / 9
        val c = i % 9
        cellRect.set(c * cell, r * cell, (c + 1) * cell, (r + 1) * cell)
        canvas.drawRect(cellRect, paint)
    }

    private fun Canvas.drawCenteredDigit(d: Int, cx: Float, cy: Float, paint: Paint) {
        val baseline = cy - (paint.ascent() + paint.descent()) / 2f
        drawText(d.toString(), cx, baseline, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val cell = width / 9f
            val c = (event.x / cell).toInt().coerceIn(0, 8)
            val r = (event.y / cell).toInt().coerceIn(0, 8)
            onCellSelected?.invoke(r * 9 + c)
            return true
        }
        return super.onTouchEvent(event)
    }
}
