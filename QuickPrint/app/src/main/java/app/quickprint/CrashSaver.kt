package app.quickprint

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the details of a crash so the app can show them next time (and you can send them to get it fixed). */
class CrashSaver private constructor(
    private val ctx: Context,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val head = "Quick Print ${versionName()} · Android ${android.os.Build.VERSION.RELEASE} · " +
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()) + " · thread ${t.name}\n"
            ctx.getSharedPreferences("crash", Context.MODE_PRIVATE).edit()
                .putString("text", head + sw.toString().take(6000)).commit()
        } catch (ignored: Throwable) {
        }
        previous?.uncaughtException(t, e)
    }

    private fun versionName(): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    companion object {
        fun install(ctx: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current !is CrashSaver) Thread.setDefaultUncaughtExceptionHandler(CrashSaver(ctx.applicationContext, current))
        }

        fun lastCrash(ctx: Context): String? =
            ctx.getSharedPreferences("crash", Context.MODE_PRIVATE).getString("text", null)

        fun clear(ctx: Context) {
            ctx.getSharedPreferences("crash", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
