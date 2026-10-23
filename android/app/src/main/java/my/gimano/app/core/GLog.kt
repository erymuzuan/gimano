package my.gimano.app.core

import android.util.Log
import my.gimano.app.BuildConfig

/**
 * The only logger the app uses. Release builds log nothing (privacy rule: no coordinates or
 * request bodies in logs); debug builds log normally.
 */
object GLog {
    fun d(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.d(tag, msg) }
    fun i(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.i(tag, msg) }
    fun w(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.w(tag, msg) }
}
