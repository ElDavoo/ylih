package it.eldavo.ylih

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.core.os.ConfigurationCompat
import it.eldavo.ylih.data.SettingsStore
import kotlinx.coroutines.runBlocking
import org.xmlpull.v1.XmlPullParser
import java.text.Collator
import java.util.Locale

/**
 * The app's own language setting, which only exists below Android 13.
 *
 * From API 33 the platform owns this: the locale config generated from the values-* folders puts
 * the app under Settings > System > Languages, and the choice made there is remembered per app.
 * Below that nothing does — the system language is the only language an app gets — so ylih stores
 * a tag itself and applies it to every context it creates.
 */
object AppLocale {

    /** The stored value that means "whatever the system is set to". */
    const val SYSTEM = ""

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** True where the platform has no per-app language of its own — the only place we offer one. */
    val NEEDS_IN_APP_PICKER: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    /**
     * Every language the app has resources for, as BCP 47 tags.
     *
     * Read out of the locale config AGP generates from the values-* folders, which is the same
     * list the system reads on Android 13+: the in-app picker and the system one therefore cannot
     * drift apart, and a new translation folder needs no further wiring here either. The resource
     * name is AGP's, so a rename on its side fails the compile rather than silently emptying the
     * picker.
     */
    fun supportedTags(context: Context): List<String> {
        val parser = context.resources.getXml(R.xml._generated_res_locale_config)
        return try {
            buildList {
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                        parser.getAttributeValue(ANDROID_NS, "name")?.let(::add)
                    }
                }
            }
        } finally {
            parser.close()
        }
    }

    /** [supportedTags] in the order a picker is read in: by the name each language gives itself. */
    fun pickerTags(context: Context): List<String> =
        supportedTags(context).sortedWith(compareBy(Collator.getInstance()) { displayName(it) })

    /**
     * How a language names itself. A picker in the current language would be unreadable to the
     * person looking for the one they can actually read.
     */
    fun displayName(tag: String): String =
        Locale.forLanguageTag(tag).let { it.getDisplayName(it) }

    /**
     * Applies the stored language to a base context on its way into [Context.attachBaseContext].
     *
     * The configuration has to be settled before a single resource is resolved, so there is no
     * suspension point to wait at and the read blocks. DataStore holds the value in memory once
     * it has been read, so that is one small file read per process.
     */
    fun wrap(base: Context): Context {
        if (!NEEDS_IN_APP_PICKER) return base
        return apply(base, runBlocking { SettingsStore(base).languageNow() })
    }

    /** Visible for the tests and for [wrap]; [tag] is a BCP 47 tag or [SYSTEM]. */
    fun apply(base: Context, tag: String): Context {
        val locale = if (tag == SYSTEM) systemLocale() else Locale.forLanguageTag(tag)
        // ui/Format.kt puts every date and duration through Locale.getDefault(), which a
        // configuration override does not reach. Set here rather than at the call sites so a
        // formatter added later cannot forget.
        Locale.setDefault(locale)
        if (tag == SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        // setLocale, not setLocales: it is the form that exists at the API 23 floor, and from
        // API 24 it stores a one-element locale list anyway.
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** The system's own language, which the app context no longer reports once [apply] has run. */
    private fun systemLocale(): Locale =
        ConfigurationCompat.getLocales(Resources.getSystem().configuration)[0]
            ?: Locale.getDefault()
}
