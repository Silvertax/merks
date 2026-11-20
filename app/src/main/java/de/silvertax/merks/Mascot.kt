package de.silvertax.merks

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Little pixel friends who keep you company in the app, chosen in the settings: in the list, while
 * listening, and they cheer when something is saved. Their pictures are in [MascotArt] (drawn by
 * tools/mascots/draw_mascots.py) and are shown big with sharp pixels.
 */
enum class Mascot(private val art: MascotArt) {
    CAT(MascotArt.CAT),
    OWL(MascotArt.OWL),
    FOX(MascotArt.FOX),
    HEDGEHOG(MascotArt.HEDGEHOG),
    SEAL(MascotArt.SEAL),
    ;

    val key: String get() = art.key
    val label: Int get() = art.label

    enum class Face { IDLE, BLINK, HAPPY }

    private val bitmaps = HashMap<Face, Bitmap>()

    /** One face as a picture of [SIZE] x [SIZE] pixels, made once. */
    fun bitmap(face: Face): Bitmap = bitmaps.getOrPut(face) {
        val rows = when (face) {
            Face.IDLE -> art.idle
            Face.BLINK -> art.blink
            Face.HAPPY -> art.happy
        }
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            for ((y, row) in rows.withIndex()) {
                for ((x, c) in row.withIndex()) art.palette[c]?.let { setPixel(x, y, it) }
            }
        }
    }

    private val framed = HashMap<Int, Bitmap>()

    /**
     * The mascot as a widget picture of [px] x [px] screen pixels, the mascot taking up about [fill] of it
     * in the middle. Each of its pixels becomes a whole block of screen pixels, so it stays sharp; and it is
     * the same picture each time, so a widget with several sizes keeps it once.
     */
    fun picture(px: Int, fill: Float = 1f): Bitmap = framed.getOrPut(px * 1000 + (fill * 100).toInt()) {
        val side = SIZE * maxOf(1, (px * fill / SIZE).toInt())
        val margin = (px - side) / 2
        Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also {
            val paint = android.graphics.Paint().apply { isFilterBitmap = false; isAntiAlias = false }
            android.graphics.Canvas(it).drawBitmap(bitmap(Face.IDLE), null, android.graphics.Rect(margin, margin, margin + side, margin + side), paint)
        }
    }

    /** [picture] for a view of [dp] x [dp]. */
    fun picture(ctx: android.content.Context, dp: Int, fill: Float = 1f): Bitmap =
        picture(Math.round(dp * ctx.resources.displayMetrics.density), fill)

    companion object {
        const val SIZE = 32

        /** The mascot for the widgets' "Speak" button, or null for the microphone. */
        fun forWidgets(ctx: android.content.Context): Mascot? = Prefs.mascot(ctx)?.takeIf { Prefs.mascotInWidgets(ctx) }

        fun of(key: String?): Mascot? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Shows the chosen mascot, or nothing. It breathes a little and blinks now and then, follows the voice
 * while listening ([listen]) and jumps for joy ([cheer]); a tap makes it cheer too.
 */
class MascotView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var mascot: Mascot? = null
        set(value) {
            field = value
            visibility = if (value == null) GONE else VISIBLE
            invalidate()
        }

    private var face = Mascot.Face.IDLE
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val target = Rect()
    private var bob: ValueAnimator? = null

    // How big one of the mascot's pixels is on the screen.
    private val pixel: Float get() = max(1f, min(width, height) / Mascot.SIZE.toFloat())

    private val blink = object : Runnable {
        override fun run() {
            if (face == Mascot.Face.IDLE) {
                setFace(Mascot.Face.BLINK)
                postDelayed({ if (face == Mascot.Face.BLINK) setFace(Mascot.Face.IDLE) }, 140)
            }
            postDelayed(this, Random.nextLong(2500, 5500))
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnClickListener { cheer() }
        if (isInEditMode) mascot = Mascot.CAT
    }

    private fun setFace(f: Mascot.Face) {
        face = f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val m = mascot ?: return
        // Whole screen pixels per mascot pixel where it fits, so all of them come out the same size.
        val side = (min(width, height) / Mascot.SIZE) * Mascot.SIZE
        val size = if (side > 0) side else min(width, height)
        val left = (width - size) / 2
        val top = height - size
        target.set(left, top, left + size, top + size)
        canvas.drawBitmap(m.bitmap(face), null, target, paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Breathing: up and down by one of its pixels. Nothing moves when the phone has animations off.
        if (ValueAnimator.areAnimatorsEnabled()) {
            bob = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 1800
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { if (!jumping) translationY = -pixel * (it.animatedValue as Float) }
                start()
            }
        }
        postDelayed(blink, Random.nextLong(1500, 4000))
    }

    override fun onDetachedFromWindow() {
        bob?.cancel()
        bob = null
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    private var jumping = false

    /** Follows the voice while listening: stretches up a little when it is loud. */
    fun listen(level: Float) {
        if (mascot == null || jumping) return
        pivotX = width / 2f
        pivotY = height.toFloat()
        val l = level.coerceIn(0f, 1f)
        animate().scaleY(1f + 0.18f * l).scaleX(1f - 0.06f * l).setDuration(120).start()
    }

    /** Jumps for joy, twice, with a happy face. */
    fun cheer() {
        if (mascot == null || jumping) return
        jumping = true
        setFace(Mascot.Face.HAPPY)
        scaleX = 1f
        scaleY = 1f
        val up = -pixel * 6
        animate().translationY(up).setDuration(160).withEndAction {
            animate().translationY(0f).setDuration(160).withEndAction {
                animate().translationY(up * 0.6f).setDuration(140).withEndAction {
                    animate().translationY(0f).setDuration(140).withEndAction {
                        postDelayed({
                            jumping = false
                            setFace(Mascot.Face.IDLE)
                        }, 500)
                    }.start()
                }.start()
            }.start()
        }.start()
    }
}
