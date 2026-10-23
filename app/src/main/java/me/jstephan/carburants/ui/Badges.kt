package me.jstephan.carburants.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import me.jstephan.carburants.data.Fuel

/** Bitmaps drawn by the app: logo tiles, initials badges (as the card does) and fuel badges. */
object Badges {
    private val PALETTE = longArrayOf(
        0xFF1565C0,
        0xFF2E7D32,
        0xFFC62828,
        0xFF6A1B9A,
        0xFFEF6C00,
        0xFF00838F,
        0xFF4E342E,
        0xFF283593,
    ).map(Long::toInt)

    /** Stable colour per brand. */
    fun brandColor(name: String): Int {
        var h = 0L
        for (ch in name) h = (h * 31 + ch.code) and 0xFFFFFFFFL
        return PALETTE[(h % PALETTE.size).toInt()]
    }

    fun initials(name: String): String {
        val words = name.replace(Regex("[^A-Za-zÀ-ÿ0-9 ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        val text = if (words.size == 1) words[0].take(2) else "${words[0][0]}${words[1][0]}"
        return text.uppercase()
    }

    /** A [size] px rounded square filled with [color], and a canvas to draw on it. */
    private fun tile(size: Int, color: Int): Pair<Bitmap, Canvas> {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size * 0.22f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        return bitmap to canvas
    }

    /** Coloured rounded square with the brand's initials. */
    fun initialsBadge(label: String, size: Int): Bitmap {
        val (bitmap, canvas) = tile(size, brandColor(label))
        drawCentered(canvas, initials(label).ifEmpty { "?" }, size / 2f, size / 2f, size * 0.42f, Color.WHITE)
        return bitmap
    }

    /** The logo on a white rounded tile, so dark or transparent logos stay readable on the car's dark UI. */
    fun logoTile(logo: Bitmap, size: Int): Bitmap {
        val (bitmap, canvas) = tile(size, Color.WHITE)
        val inner = size * 0.80f
        val scale = minOf(inner / logo.width, inner / logo.height)
        val w = logo.width * scale
        val h = logo.height * scale
        val dst = RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2)
        canvas.drawBitmap(logo, Rect(0, 0, logo.width, logo.height), dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return bitmap
    }

    /** Pump-coloured square: the name at the pump and its EU identifier under it. */
    fun fuelBadge(fuel: Fuel, size: Int): Bitmap {
        val (bitmap, canvas) = tile(size, fuel.color)
        val label = if (fuel == Fuel.E10) "SP95" else fuel.label
        if (fuel.code.isEmpty()) {
            drawCentered(canvas, label, size / 2f, size / 2f, fitText(label, size * 0.30f, size * 0.84f), fuel.onColor)
        } else {
            drawCentered(canvas, label, size / 2f, size * 0.36f, fitText(label, size * 0.26f, size * 0.84f), fuel.onColor)
            // EU identifier in a white pill, coloured like the badge.
            val pillW = size * 0.62f
            val pillH = size * 0.30f
            val top = size * 0.56f
            val pill = RectF((size - pillW) / 2, top, (size + pillW) / 2, top + pillH)
            canvas.drawRoundRect(pill, pillH / 2, pillH / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            drawCentered(canvas, fuel.code, size / 2f, pill.centerY(), size * 0.22f, fuel.color)
        }
        return bitmap
    }

    private fun fitText(text: String, preferred: Float, maxWidth: Float): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = preferred
        }
        val width = paint.measureText(text)
        return if (width <= maxWidth) preferred else preferred * maxWidth / width
    }

    private fun drawCentered(canvas: Canvas, text: String, cx: Float, cy: Float, textSize: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(text, cx, cy - bounds.exactCenterY(), paint)
    }
}
