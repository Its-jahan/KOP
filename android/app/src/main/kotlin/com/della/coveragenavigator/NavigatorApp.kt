// Application: MapLibre initialisation (OSM raster tiles through our OkHttp client with a
// real User-Agent), persistence and the single Engine instance.
package com.della.coveragenavigator

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.Locale

class NavigatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        MapLibre.getInstance(this)
        HttpRequestUtil.setOkHttpClient(Net.client)
        store = Store(this)
        engine = Engine(this, store)
    }

    companion object {
        lateinit var instance: NavigatorApp
            private set
        lateinit var store: Store
            private set
        lateinit var engine: Engine
            private set
    }
}

/** UI language preference (default Persian) applied through a wrapped configuration. */
object Localized {
    private const val PREFS = "settings"
    private const val KEY_LANG = "lang"

    fun language(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, "fa") ?: "fa"

    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANG, lang).apply()
    }

    fun wrap(base: Context): Context {
        val locale = Locale(language(base))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
