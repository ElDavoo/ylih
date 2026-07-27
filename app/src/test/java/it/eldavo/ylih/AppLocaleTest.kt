package it.eldavo.ylih

import android.content.Context
import android.os.Build
import androidx.core.os.ConfigurationCompat
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.data.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
