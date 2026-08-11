package it.eldavo.ylih

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.data.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.Collator
import java.util.Locale

/**
 * Android 12 and below have no per-app language, so this small mechanism is the whole feature:
 * the offered list has to be the translations that are actually in the build, and applying one
 * has to reach both the resources and `Locale.getDefault()`, which every figure in ui/Format.kt
 * is formatted through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class AppLocaleTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val settings get() = SettingsStore(app)
    private lateinit var beforeTest: Locale

    /** `apply` sets the process-wide default locale, which would otherwise leak into every
     *  test that formats a date after this class has run. */
    @Before
    fun rememberDefaultLocale() {
        beforeTest = Locale.getDefault()
    }

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(beforeTest)
        runBlocking { settings.setLanguage(AppLocale.SYSTEM) }
    }

    @Test
    fun `the picker offers the languages the app is actually translated into`() {
        val tags = AppLocale.supportedTags(app)

        // The list AGP generates from the values-* folders, which is also the one the system
        // reads on Android 13+: if this ever comes back empty the two pickers have diverged.
        assertTrue("expected the whole translation set, got ${tags.size}", tags.size > 70)
        assertTrue("the unqualified values/ folder is named by res/resources.properties", "en-US" in tags)
        assertTrue(tags.containsAll(listOf("it", "ja", "pt-BR", "az-Cyrl", "iw")))
    }

    /**
     * Chinese is one language and two scripts, and the translations live in `values-b+zh+Hans`
     * and `values-b+zh+Hant`. They also existed as `values-zh-rCN` and `values-zh-rTW`, holding a
     * second, different translation — so China and Taiwan were served wording that no other
     * Chinese region got, and the Traditional file carried mainland vocabulary. Nothing in the
     * build reports a locale translated twice, so this pins the shape that replaced it: one
     * folder per script, each actually reachable.
     */
    @Test
    fun `both chinese scripts are offered and reach their own translation`() {
        val tags = AppLocale.supportedTags(app)
        assertTrue(
            "expected one entry per script, got ${tags.filter { it.startsWith("zh") }}",
            tags.containsAll(listOf("zh-Hans", "zh-Hant")),
        )

        val simplified = stringIn("zh-Hans", R.string.nav_settings)
        val traditional = stringIn("zh-Hant", R.string.nav_settings)

        // Falling back to values/ is what a folder no request can name looks like from here.
        assertNotEquals("values-b+zh+Hans is not being reached", "settings", simplified)
        assertNotEquals("values-b+zh+Hant is not being reached", "settings", traditional)
        assertNotEquals("both scripts resolved to the same file", simplified, traditional)
    }

    private fun stringIn(tag: String, resId: Int): String {
        runBlocking { settings.setLanguage(tag) }
        return AppLocale.wrap(app).getString(resId)
    }

    @Test
    fun `the list is ordered by the name each language gives itself`() {
        val names = AppLocale.pickerTags(app).map { AppLocale.displayName(it) }

        assertEquals(AppLocale.supportedTags(app).size, names.size)
        assertEquals(names.sortedWith(Collator.getInstance()), names)
    }

    @Test
    fun `a stored language reaches the resources and the formatters`() {
        runBlocking { settings.setLanguage("it") }

        val wrapped = AppLocale.wrap(app)

        assertEquals(
            "it",
            ConfigurationCompat.getLocales(wrapped.resources.configuration)[0]?.language,
        )
        assertEquals("it", Locale.getDefault().language)
        assertNotEquals(
            "a context that still answers in English has not been rebuilt",
            app.getString(R.string.settings_language),
            wrapped.getString(R.string.settings_language),
        )
    }

    /**
     * `wrap` runs in `attachBaseContext`, which has no suspension point and comes before the
     * process may touch the database — so on a cold start reading the row there would open Room,
     * and run any pending migration, on the main thread before the first frame. It reads a
     * `SharedPreferences` mirror instead, and the pair only stays honest if the write keeps them
     * in step. Both halves are asserted here: that the mirror follows the row, and that an install
     * which predates the mirror still gets its language.
     */
    @Test
    fun `the language is mirrored beside the row that wrap cannot afford to read`() {
        runBlocking { settings.setLanguage("ja") }

        assertEquals("ja", SettingsStore.cachedLanguage(app))
        assertEquals("ja", ConfigurationCompat.getLocales(AppLocale.wrap(app).resources.configuration)[0]?.language)
    }

    @Test
    fun `an install with no mirror yet reads the row once and seeds it`() {
        // What an upgrade looks like: the row is set, nothing has ever written the mirror.
        runBlocking { settings.setLanguage("it") }
        app.getSharedPreferences("ylih-settings-cache", Context.MODE_PRIVATE).edit { clear() }
        assertNull("the mirror starts empty", SettingsStore.cachedLanguage(app))

        val wrapped = AppLocale.wrap(app)

        assertEquals("it", ConfigurationCompat.getLocales(wrapped.resources.configuration)[0]?.language)
        assertEquals("and no later launch has to pay for it", "it", SettingsStore.cachedLanguage(app))
    }

    @Test
    fun `choosing the system language hands the default locale back`() {
        runBlocking { settings.setLanguage("it") }
        AppLocale.wrap(app)

        runBlocking { settings.setLanguage(AppLocale.SYSTEM) }
        val wrapped = AppLocale.wrap(app)

        assertSame("nothing to override, so nothing to rebuild", app, wrapped)
        assertEquals("en", Locale.getDefault().language)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `from android 13 the platform owns the language and the app keeps out`() {
        runBlocking { settings.setLanguage("it") }

        assertFalse(AppLocale.NEEDS_IN_APP_PICKER)
        assertSame(app, AppLocale.wrap(app))
        assertEquals(beforeTest, Locale.getDefault())
    }
}
