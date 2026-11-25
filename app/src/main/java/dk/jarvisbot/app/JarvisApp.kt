package dk.jarvisbot.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Process
import org.json.JSONObject

class JarvisApp : Application() {
    lateinit var store: DiagStore
        private set
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        store = DiagStore(this)
        installCrashHandler()
        store.log(
            "PROCESS_START", "pid=${Process.myPid()}",
            JSONObject().put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
                .put("git", BuildConfig.GIT_SHA).put("jarvisEnabledPref", prefs.enabled)
                .put("diagnosticMode", prefs.diagnosticMode)
        )
        Diagnostics.startupChecks(this)
        runCatching { MicTest.recoverOrphans(this) }.onFailure { store.log("MICTEST_RECOVERY_ERROR", it.toString()) }
        Transcription.start()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                store.log(
                    "CRASH", "${e.javaClass.name}: ${e.message}",
                    JSONObject().put("thread", t.name).put("stack", e.stackTraceToString().take(8000)),
                    sync = true
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            store.log("MEMORY_TRIM", "level=$level")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        store.log("MEMORY_LOW", "onLowMemory")
    }

    companion object {
        lateinit var instance: JarvisApp
            private set
    }
}
