package de.silvertax.merks

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import de.silvertax.merks.parse.Kind
import de.silvertax.merks.parse.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Every widget, at every step of its size, shows what it shows without cutting a line off: in normal
 * and in larger print, with titles long enough to need two lines. The steps in NextWidget and
 * AllInOneWidget are measured numbers; this keeps them right when a layout changes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-rDE-w411dp-h891dp-xhdpi")
class WidgetSizeTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var next: List<Plan.Upcoming>
    private val now = System.currentTimeMillis()

    /** Normal print up to the largest the phone offers in its settings. */
    private val scales = listOf(1f, 1.15f, 1.3f, 1.5f)

    @Before fun longTitles() {
        val today = LocalDate.now()
        Store.save(app, Parsed(Kind.APPOINTMENT, "Termin bei Frau Ali Babaiyan in der Praxis am Markt", today.plusDays(1).atTime(10, 0), null, false), "x")
        Store.save(app, Parsed(Kind.APPOINTMENT, "Mülltonne rausstellen und das Altpapier dazu", today.plusDays(2).atTime(7, 0), null, false), "x")
        Store.save(app, Parsed(Kind.APPOINTMENT, "Geburtstag von Oma mit Kaffee und Kuchen", today.plusDays(9).atStartOfDay(), null, true), "x")
        next = Glance.nextUp(app)
        assertEquals(3, next.size)
    }

    private fun printed(fontScale: Float): Context =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { this.fontScale = fontScale })

    /** The height in dp the widget needs at width [w] dp. */
    private fun needed(rv: RemoteViews, c: Context, w: Float): Float {
        val d = c.resources.displayMetrics.density
        val v = rv.apply(c, null)
        v.measure(View.MeasureSpec.makeMeasureSpec((w * d).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return v.measuredHeight / d
    }

    @Test fun upNextFitsEveryStep() {
        for (fs in scales) {
            val c = printed(fs)
            for (w in listOf(110f, NextWidget.TILE_MIN_W)) {
                for (h in NextWidget.heights(fs)) {
                    val look = NextWidget.lookFor(w, h, fs)
                    val need = needed(NextWidget.views(c, next, now, look), c, w)
                    assertTrue("${w}x$h dp in print $fs: $look needs $need dp", need <= h + 0.5f)
                }
            }
        }
    }

    @Test fun allInOneFitsEveryStep() {
        for (fs in scales) {
            val c = printed(fs)
            for (w in listOf(110f, AllInOneWidget.HEAD_MIN_W, AllInOneWidget.WIDE_W)) {
                // Lower than the first step only the round buttons are shown, which fit down to 60 dp.
                for (h in listOf(60f) + AllInOneWidget.heights(fs)) {
                    val look = AllInOneWidget.lookFor(w, h, fs)
                    val need = needed(AllInOneWidget.views(c, next, now, look), c, w)
                    assertTrue("${w}x$h dp in print $fs: $look needs $need dp", need <= h + 0.5f)
                }
            }
        }
    }

    /** The word below or beside the button is never cut short, and the button keeps a size worth tapping. */
    @Test fun buttonsFitEveryStep() {
        for (fs in scales) {
            val c = printed(fs)
            val d = c.resources.displayMetrics.density
            for (w in listOf(40f, ButtonWidget.CELL_MIN_W, ButtonWidget.TALL_MIN_W, ButtonWidget.WIDE_MIN_W)) {
                for (h in listOf(40f, ButtonWidget.TALL_MIN_H)) {
                    for (widget in listOf(SpeakWidget(), PhotoWidget(), FileWidget())) {
                        val shape = ButtonWidget.shapeFor(w, h)
                        val v = widget.views(c, shape).apply(c, null)
                        v.measure(View.MeasureSpec.makeMeasureSpec((w * d).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((h * d).toInt(), View.MeasureSpec.EXACTLY))
                        v.layout(0, 0, (w * d).toInt(), (h * d).toInt())
                        val what = "${widget.javaClass.simpleName} $shape ${w}x$h dp in print $fs"
                        val button = v.findViewById<ImageView>(R.id.widget_icon)
                        assertTrue("$what: button ${button.width / d} dp", minOf(button.width, button.height) / d >= 24f)
                        // The circle behind the picture stays round.
                        assertTrue("$what: button ${button.width}x${button.height} px", kotlin.math.abs(button.width - button.height) <= 1)
                        val label = v.findViewById<TextView>(R.id.widget_label) ?: continue
                        val room = label.width - label.totalPaddingLeft - label.totalPaddingRight
                        assertTrue("$what: word cut short", label.paint.measureText(label.text.toString()) <= room + 1)
                        assertEquals("$what: word cut short", 0, label.layout.getEllipsisCount(0))
                        assertTrue("$what: word cut off below", label.bottom <= v.height - v.paddingBottom)
                    }
                }
            }
        }
    }
}
