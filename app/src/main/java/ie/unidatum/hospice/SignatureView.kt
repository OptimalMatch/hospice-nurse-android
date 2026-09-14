package ie.unidatum.hospice

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import java.io.ByteArrayOutputStream

/**
 * The caregiver's signature, taken with a finger at a kitchen table. It leaves
 * here as a PNG on the visit document, so the thing a reviewer sees years later
 * is the mark the person actually made rather than a typed name.
 */
class SignatureView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val path = Path()
    private val strokes = mutableListOf<Path>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12263f"); style = Paint.Style.STROKE
        strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val guide = Paint().apply { color = Color.parseColor("#c8cfdb"); strokeWidth = 2f }
    var onChanged: (() -> Unit)? = null

    val isEmpty: Boolean get() = strokes.isEmpty() && path.isEmpty

    fun clear() { path.reset(); strokes.clear(); invalidate(); onChanged?.invoke() }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        canvas.drawLine(24f, height - 28f, width - 24f, height - 28f, guide)
        for (s in strokes) canvas.drawPath(s, paint)
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); path.moveTo(e.x, e.y) }
            MotionEvent.ACTION_MOVE -> path.lineTo(e.x, e.y)
            MotionEvent.ACTION_UP -> { strokes.add(Path(path)); path.reset(); onChanged?.invoke() }
        }
        invalidate()
        return true
    }

    /** The signature as a base64 PNG, trimmed to the width the document needs. */
    fun toBase64Png(): String {
        val w = if (width > 0) width else 800
        val h = if (height > 0) height else 260
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        for (s in strokes) c.drawPath(s, paint)
        val out = ByteArrayOutputStream()
        val scaled = if (w > 800) Bitmap.createScaledBitmap(bmp, 800, h * 800 / w, true) else bmp
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
