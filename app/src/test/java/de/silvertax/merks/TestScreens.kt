package de.silvertax.merks

import androidx.appcompat.app.AppCompatDelegate

/**
 * AppCompat keeps every screen it has seen in a static list and rebuilds them all when the theme or
 * the language changes. Screens left over from earlier tests are in that list too until the garbage
 * collector gets to them, and rebuilding such a dead screen breaks the simulation ("synchronization
 * barrier token") – sometimes, depending on the collector. Tests that switch the theme forget the
 * leftovers first.
 */
object TestScreens {
    fun forgetLeftovers() {
        val lock = AppCompatDelegate::class.java.getDeclaredField("sActivityDelegatesLock").apply { isAccessible = true }.get(null)
        val screens = AppCompatDelegate::class.java.getDeclaredField("sActivityDelegates").apply { isAccessible = true }.get(null)
        synchronized(lock!!) { (screens as MutableCollection<*>).clear() }
    }
}
