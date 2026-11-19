package de.silvertax.merks

/** The app in tests: opening it must not ask GitHub for updates. Tests that want an answer give their own. */
class TestApp : MerksApp() {
    override fun onCreate() {
        Updater.offline = true
        super.onCreate()
    }
}
