package de.silvertax.merks

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.widget.RemoteViews

/**
 * The colours of the home screen widgets, so they look like the app: light or dark as chosen in its
 * settings (or like the phone), black in OLED mode, and in the chosen accent colour.
 *
 * Android 12 and newer only. Older home screens cannot recolour the shapes a widget is made of, so
 * there the widgets keep their teal look and follow the phone's light or dark mode, as before.
 */
object WidgetTheme {
    class Colors(
        val surface: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val outline: Int,
        val primary: Int,
        val onPrimary: Int,
        val tonal: Int,
        val onTonal: Int,
        val amber: Int,
        val onAmber: Int,
        val accent: Int,
    )

    /** The colours of the light or, with [dark], the dark look, in [accent], with OLED as chosen. */
    fun colors(ctx: Context, dark: Boolean, accent: String = Appearance.base(ctx)): Colors {
        val config = Configuration(ctx.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(ctx.createConfigurationContext(config), R.style.Theme_Merks)
        val t = themed.theme
        Appearance.accentOverlay(accent).takeIf { it != 0 }?.let { t.applyStyle(it, true) }
        if (dark && Prefs.oled(ctx)) t.applyStyle(R.style.ThemeOverlay_Merks_Oled, true)
        return Colors(
            surface = t.color(R.attr.merksWidgetSurface),
            onSurface = t.color(R.attr.merksWidgetOnSurface),
            onSurfaceVariant = t.color(R.attr.merksWidgetOnSurfaceVariant),
            outline = t.color(R.attr.merksWidgetOutline),
            primary = t.color(R.attr.merksWidgetPrimary),
            onPrimary = t.color(R.attr.merksWidgetOnPrimary),
            tonal = t.color(R.attr.merksWidgetTonal),
            onTonal = t.color(R.attr.merksWidgetOnTonal),
            amber = themed.getColor(R.color.w_amber),
            onAmber = themed.getColor(R.color.w_on_amber),
            accent = t.color(R.attr.merksWidgetAccent),
        )
    }

    private fun Resources.Theme.color(attr: Int): Int {
        val v = TypedValue()
        check(resolveAttribute(attr, v, true)) { "widget colour missing in the theme" }
        return v.data
    }

    // The colours are asked for by every layout of every widget; they only change with the settings.
    private var cached: Pair<String, List<Pair<Colors, Colors>>>? = null

    /**
     * The colours for a light and for a dark home screen, one pair for each colour in use (several in
     * the mixed mode, the app's own first). The home screen picks by its own mode, so a widget that
     * goes with the phone switches by itself; when the app is set to light or dark, both are the same.
     */
    fun mix(ctx: Context): List<Pair<Colors, Colors>> {
        val theme = Prefs.theme(ctx)
        val key = listOf(theme, Appearance.colourKey(ctx), Prefs.oled(ctx)).joinToString("|")
        cached?.let { (k, colors) -> if (k == key) return colors }
        val colors = Appearance.mix(ctx).map { accent ->
            when (theme) {
                Appearance.LIGHT -> colors(ctx, false, accent).let { it to it }
                Appearance.DARK -> colors(ctx, true, accent).let { it to it }
                else -> colors(ctx, false, accent) to colors(ctx, true, accent)
            }
        }
        cached = key to colors
        return colors
    }

    /** The colours of the app's own accent, light and dark. */
    fun pair(ctx: Context): Pair<Colors, Colors> = mix(ctx).first()

    /** Colours [rv] with [paint]; nothing happens before Android 12 (see above). */
    fun paint(ctx: Context, rv: RemoteViews, paint: Painter.() -> Unit) {
        if (Build.VERSION.SDK_INT < 31) return
        val mix = mix(ctx)
        Painter(rv, mix.map { it.first }, mix.map { it.second }).paint()
    }

    /**
     * Made by [paint] only, on Android 12 and newer: the colour methods of RemoteViews come with it.
     * A [slot] picks one of the mixed colours (see Appearance.mix); with one colour, all slots are that one.
     */
    @SuppressLint("NewApi")
    class Painter internal constructor(private val rv: RemoteViews, private val lights: List<Colors>, private val darks: List<Colors>) {
        private fun light(slot: Int) = lights[Math.floorMod(slot, lights.size)]
        private fun dark(slot: Int) = darks[Math.floorMod(slot, darks.size)]
        private fun list(c: Int) = ColorStateList.valueOf(c)

        /** The shape behind a view: the card, a button, a line. */
        fun background(id: Int, slot: Int = 0, pick: Colors.() -> Int) =
            rv.setColorStateList(id, "setBackgroundTintList", list(light(slot).pick()), list(dark(slot).pick()))

        /** The picture in an image view, e.g. the microphone on a button. */
        fun image(id: Int, slot: Int = 0, pick: Colors.() -> Int) =
            rv.setColorStateList(id, "setImageTintList", list(light(slot).pick()), list(dark(slot).pick()))

        fun text(id: Int, slot: Int = 0, pick: Colors.() -> Int) =
            rv.setColorInt(id, "setTextColor", light(slot).pick(), dark(slot).pick())

        /** Amber when switched off (a reminder), the accent's tonal colour otherwise; see drawable/w_badge.xml. */
        private fun badgeList(c: Colors, on: Colors.() -> Int, off: Colors.() -> Int) = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(c.off(), c.on()),
        )

        fun badge(id: Int, slot: Int = 0) = rv.setColorStateList(
            id, "setBackgroundTintList", badgeList(light(slot), { tonal }, { amber }), badgeList(dark(slot), { tonal }, { amber }),
        )

        fun badgeText(id: Int, slot: Int = 0) = rv.setColorStateList(
            id, "setTextColor", badgeList(light(slot), { onTonal }, { onAmber }), badgeList(dark(slot), { onTonal }, { onAmber }),
        )

        /** The card every widget sits on. */
        fun card() = background(android.R.id.background) { surface }
    }
}
