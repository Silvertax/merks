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
 * listening, and they cheer when something is saved. Drawn here as 16 x 16 pixels, one letter a pixel
 * ("." is clear), and shown big with sharp pixels.
 */
enum class Mascot(
    val key: String,
    val label: Int,
    private val palette: Map<Char, Int>,
    private val idle: List<String>,
    private val blink: List<String>,
    private val happy: List<String>,
) {
    CAT(
        "cat",
        R.string.mascot_cat,
        mapOf('o' to 0xFF3A2E2A.toInt(), 'f' to 0xFFF2A23A.toInt(), 'd' to 0xFFD27A1E.toInt(), 'w' to 0xFFFFF3DE.toInt(), 'p' to 0xFFF4899B.toInt(), 'e' to 0xFF3A2E2A.toInt()),
        idle = listOf(
            "................",
            "..o..........o..",
            ".ofo........ofo.",
            ".opfo......ofpo.",
            ".offfoooooofffo.",
            ".offdffffffdffo.",
            ".offffffffffffo.",
            ".offeeffffeeffo.",
            ".offeeffffeeffo.",
            ".ofwwffppffwwfo.",
            ".offwwwwwwwwffo.",
            "..offwwwwwwffo..",
            "...oooffffooo...",
            "..offfwwwwfffo..",
            "..offwwffwwffo..",
            "..oooooooooooo..",
        ),
        blink = listOf(
            "................",
            "..o..........o..",
            ".ofo........ofo.",
            ".opfo......ofpo.",
            ".offfoooooofffo.",
            ".offdffffffdffo.",
            ".offffffffffffo.",
            ".offffffffffffo.",
            ".offeeffffeeffo.",
            ".ofwwffppffwwfo.",
            ".offwwwwwwwwffo.",
            "..offwwwwwwffo..",
            "...oooffffooo...",
            "..offfwwwwfffo..",
            "..offwwffwwffo..",
            "..oooooooooooo..",
        ),
        happy = listOf(
            "................",
            "..o..........o..",
            ".ofo........ofo.",
            ".opfo......ofpo.",
            ".offfoooooofffo.",
            ".offdffffffdffo.",
            ".offffffffffffo.",
            ".offfeffffefffo.",
            ".offefeffefeffo.",
            ".ofwwffppffwwfo.",
            ".offwwwoowwwffo.",
            "..offwwppwwffo..",
            "...oooffffooo...",
            "..offfwwwwfffo..",
            "..offwwffwwffo..",
            "..oooooooooooo..",
        ),
    ),
    OWL(
        "owl",
        R.string.mascot_owl,
        mapOf('o' to 0xFF3B2618.toInt(), 'b' to 0xFF8B5E3C.toInt(), 'l' to 0xFFE8C9A0.toInt(), 'w' to 0xFFFFFFFF.toInt(), 'e' to 0xFF2B2B2B.toInt(), 'y' to 0xFFF2B33D.toInt()),
        idle = listOf(
            "................",
            "..oo........oo..",
            "..obo......obo..",
            "..obboooooobbo..",
            ".obbbbbbbbbbbbo.",
            ".obwwwwbbwwwwbo.",
            ".obweewbbweewbo.",
            ".obweewbbweewbo.",
            ".obwwwwyywwwwbo.",
            ".obbbbbyybbbbbo.",
            ".obblllllllbbbo.",
            ".obllblllblllbo.",
            ".obllllllllllbo.",
            ".obbllllllllbbo.",
            "..obbyobboybbo..",
            "...oooo..oooo...",
        ),
        blink = listOf(
            "................",
            "..oo........oo..",
            "..obo......obo..",
            "..obboooooobbo..",
            ".obbbbbbbbbbbbo.",
            ".obbbbbbbbbbbbo.",
            ".obbbbbbbbbbbbo.",
            ".oboooobboooobo.",
            ".obbbbbyybbbbbo.",
            ".obbbbbyybbbbbo.",
            ".obblllllllbbbo.",
            ".obllblllblllbo.",
            ".obllllllllllbo.",
            ".obbllllllllbbo.",
            "..obbyobboybbo..",
            "...oooo..oooo...",
        ),
        happy = listOf(
            "................",
            "..oo........oo..",
            "..obo......obo..",
            "..obboooooobbo..",
            ".obbbbbbbbbbbbo.",
            ".obwwwwbbwwwwbo.",
            ".obweewbbweewbo.",
            ".obewwebbewwebo.",
            ".obwwwwyywwwwbo.",
            ".obbbbyyyybbbbo.",
            ".obblllllllbbbo.",
            ".obllblllblllbo.",
            ".obllllllllllbo.",
            ".obbllllllllbbo.",
            "..obbyobboybbo..",
            "...oooo..oooo...",
        ),
    ),
    FOX(
        "fox",
        R.string.mascot_fox,
        mapOf('o' to 0xFF3A2418.toInt(), 'f' to 0xFFEE7A2B.toInt(), 'w' to 0xFFFFF6EA.toInt(), 'e' to 0xFF2B1A10.toInt(), 'n' to 0xFF2B1A10.toInt(), 'd' to 0xFF9A3E12.toInt()),
        idle = listOf(
            "................",
            ".o............o.",
            ".oo..........oo.",
            ".ofo........ofo.",
            ".odfo......ofdo.",
            ".offfoooooofffo.",
            ".offffffffffffo.",
            ".offeeffffeeffo.",
            ".ofweeffffeewfo.",
            ".owwwffffffwwwo.",
            "..owwwwnnwwwwo..",
            "...owwwwwwwwo...",
            "....oowwwwoo....",
            "...offwwwwffo...",
            "..offfwwwwfffo..",
            "..oooooooooooo..",
        ),
        blink = listOf(
            "................",
            ".o............o.",
            ".oo..........oo.",
            ".ofo........ofo.",
            ".odfo......ofdo.",
            ".offfoooooofffo.",
            ".offffffffffffo.",
            ".offffffffffffo.",
            ".ofweeffffeewfo.",
            ".owwwffffffwwwo.",
            "..owwwwnnwwwwo..",
            "...owwwwwwwwo...",
            "....oowwwwoo....",
            "...offwwwwffo...",
            "..offfwwwwfffo..",
            "..oooooooooooo..",
        ),
        happy = listOf(
            "................",
            ".o............o.",
            ".oo..........oo.",
            ".ofo........ofo.",
            ".odfo......ofdo.",
            ".offfoooooofffo.",
            ".offffffffffffo.",
            ".offfeffffefffo.",
            ".ofwefeffefewfo.",
            ".owwwffffffwwwo.",
            "..owwwwnnwwwwo..",
            "...owwwoowwwo...",
            "....oowwwwoo....",
            "...offwwwwffo...",
            "..offfwwwwfffo..",
            "..oooooooooooo..",
        ),
    ),
    HEDGEHOG(
        "hedgehog",
        R.string.mascot_hedgehog,
        mapOf('o' to 0xFF3B2A20.toInt(), 's' to 0xFF7A5236.toInt(), 't' to 0xFFA57B55.toInt(), 'f' to 0xFFF2D3A8.toInt(), 'e' to 0xFF2B1A10.toInt(), 'n' to 0xFF2B1A10.toInt(), 'p' to 0xFFF4A0A0.toInt()),
        idle = listOf(
            "................",
            "....oo.oo.oo....",
            "...ossosssosso..",
            "..osstsssstssso.",
            ".ossssstsssssso.",
            ".osstssfffsstso.",
            "osssssffffffssso",
            "ossssffeffeffsso",
            "ossssffeffeffsso",
            "osssfpffnnffpfso",
            "osssffffffffffso",
            ".osssffffffffso.",
            ".ossssffffffsso.",
            "..osssssssssso..",
            "..offo....offo..",
            "..ooo......ooo..",
        ),
        blink = listOf(
            "................",
            "....oo.oo.oo....",
            "...ossosssosso..",
            "..osstsssstssso.",
            ".ossssstsssssso.",
            ".osstssfffsstso.",
            "osssssffffffssso",
            "ossssffffffffsso",
            "ossssffeffeffsso",
            "osssfpffnnffpfso",
            "osssffffffffffso",
            ".osssffffffffso.",
            ".ossssffffffsso.",
            "..osssssssssso..",
            "..offo....offo..",
            "..ooo......ooo..",
        ),
        happy = listOf(
            "................",
            "....oo.oo.oo....",
            "...ossosssosso..",
            "..osstsssstssso.",
            ".ossssstsssssso.",
            ".osstssfffsstso.",
            "osssssffffffssso",
            "ossssffeffeffsso",
            "ossssfefeefefsso",
            "osssfpffnnffpfso",
            "osssffffnnffffso",
            ".osssffffffffso.",
            ".ossssffffffsso.",
            "..osssssssssso..",
            "..offo....offo..",
            "..ooo......ooo..",
        ),
    ),
    ;

    enum class Face { IDLE, BLINK, HAPPY }

    private val bitmaps = HashMap<Face, Bitmap>()

    /** One face as a 16 x 16 picture, made once. */
    fun bitmap(face: Face): Bitmap = bitmaps.getOrPut(face) {
        val rows = when (face) {
            Face.IDLE -> idle
            Face.BLINK -> blink
            Face.HAPPY -> happy
        }
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            for ((y, row) in rows.withIndex()) {
                for ((x, c) in row.withIndex()) palette[c]?.let { setPixel(x, y, it) }
            }
        }
    }

    companion object {
        const val SIZE = 16

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
        val up = -pixel * 4
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
