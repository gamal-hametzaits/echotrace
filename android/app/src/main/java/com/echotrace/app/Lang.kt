package com.echotrace.app

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import java.util.Locale

/** Per-app language (he/en) and theme (dark/light), applied by wrapping the context's
 *  Configuration. Resources then resolve values/ (Hebrew) vs values-en/, and
 *  values/ (light colors) vs values-night/ (dark colors). No AppCompat needed. */
object Lang {
    fun config(base: Configuration, lang: String, dark: Boolean): Configuration {
        val loc = Locale(if (lang == "en") "en" else "iw")
        return Configuration(base).apply {
            setLocale(loc)
            setLayoutDirection(loc)
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
    }

    fun ctx(c: Context): Context = try {
        val prefs = c.getSharedPreferences("echotrace", Context.MODE_PRIVATE)
        c.createConfigurationContext(config(c.resources.configuration,
            prefs.getString("lang", "he") ?: "he", prefs.getBoolean("darkTheme", true)))
    } catch (e: Exception) { c }
}

open class BaseActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Lang.ctx(newBase))
}
