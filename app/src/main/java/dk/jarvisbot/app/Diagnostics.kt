package dk.jarvisbot.app

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Resources
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone

/** Device / power / battery / recognizer facts. Anything not obtainable is reported as "unavailable". */
object Diagnostics {
    const val UNAVAILABLE = "unavailable"

    /** Called once per process start. Detects probable unclean terminations of the previous process. */
    fun startupChecks(app: JarvisApp) {
        val prefs = app.prefs
        val store = app.store
        val now = System.currentTimeMillis()
        if (prefs.svcRunningMarker) {
            val last = prefs.lastHeartbeatTs
            store.log(
                "SUSPECTED_UNCLEAN_TERMINATION",
                "Previous process ended while the listening service was running (no clean stop recorded)",
                JSONObject().put("lastHeartbeatTs", last).put("lastHeartbeatIso", DiagStore.iso(last))
                    .put("msSinceLastHeartbeat", if (last > 0) now - last else JSONObject.NULL)
                    .put("lastHeartbeatInfo", prefs.lastHeartbeatInfo)
                    .put("serviceStartTs", prefs.svcStartTs)
            )
            prefs.svcRunningMarker = false
        }
        logExitReasons(app)
    }

    fun logExitReasons(app: JarvisApp) {
        if (Build.VERSION.SDK_INT < 30) {
            app.store.log("PROCESS_EXIT_REASONS", "unavailable: requires Android 11+")
            return
        }
        try {
            val am = app.getSystemService(ActivityManager::class.java)
            val list = am.getHistoricalProcessExitReasons(app.packageName, 0, 20)
            var newest = app.prefs.lastExitReasonTs
            for (info in list.sortedBy { it.timestamp }) {
                if (info.timestamp <= app.prefs.lastExitReasonTs) continue
                app.store.log("PROCESS_EXIT_REASON", reasonName(info.reason) + ": " + (info.description ?: ""), exitInfoJson(info))
                newest = maxOf(newest, info.timestamp)
            }
            app.prefs.lastExitReasonTs = newest
        } catch (e: Exception) {
            app.store.log("PROCESS_EXIT_REASONS_ERROR", e.toString())
        }
    }

    fun exitReasonsJson(ctx: Context): JSONArray {
        val arr = JSONArray()
        if (Build.VERSION.SDK_INT < 30) return arr.put(UNAVAILABLE + " (requires Android 11+)")
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 50).forEach { arr.put(exitInfoJson(it)) }
        }.onFailure { arr.put("error: $it") }
        return arr
    }

    private fun exitInfoJson(info: ApplicationExitInfo): JSONObject = JSONObject()
        .put("timestamp", info.timestamp).put("iso", DiagStore.iso(info.timestamp))
        .put("reason", reasonName(info.reason)).put("reasonCode", info.reason)
        .put("status", info.status).put("importance", info.importance)
        .put("description", info.description ?: JSONObject.NULL)
        .put("pssKb", info.pss).put("rssKb", info.rss).put("pid", info.pid).put("process", info.processName)

    fun reasonName(r: Int): String = when (r) {
        1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "CRASH"; 5 -> "CRASH_NATIVE"; 6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"; 8 -> "PERMISSION_CHANGE"; 9 -> "EXCESSIVE_RESOURCE_USAGE"; 10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"; 12 -> "DEPENDENCY_DIED"; 13 -> "OTHER"; 14 -> "FREEZER"; 15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"; else -> "UNKNOWN($r)"
    }

    fun battery(ctx: Context): JSONObject {
        val o = JSONObject()
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i == null) return o.put("battery", UNAVAILABLE)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        o.put("pct", if (level >= 0 && scale > 0) level * 100 / scale else UNAVAILABLE)
        o.put("status", when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"; BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"; BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> UNAVAILABLE
        })
        o.put("plugged", when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            0 -> "unplugged"; BatteryManager.BATTERY_PLUGGED_AC -> "ac"; BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"; else -> UNAVAILABLE
        })
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        o.put("temperatureC", if (temp != Int.MIN_VALUE) temp / 10.0 else UNAVAILABLE)
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val cur = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
        o.put("currentNowUa", if (cur != Int.MIN_VALUE && cur != 0) cur else UNAVAILABLE)
        val cc = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Int.MIN_VALUE
        o.put("chargeCounterUah", if (cc != Int.MIN_VALUE && cc != 0) cc else UNAVAILABLE)
        return o
    }

    fun batteryPct(ctx: Context): Int {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    fun power(ctx: Context): JSONObject {
        val o = JSONObject()
        val pm = ctx.getSystemService(PowerManager::class.java)
        o.put("screenInteractive", pm.isInteractive)
        o.put("powerSaveMode", pm.isPowerSaveMode)
        o.put("deviceIdleMode", pm.isDeviceIdleMode)
        o.put("ignoringBatteryOptimizations", pm.isIgnoringBatteryOptimizations(ctx.packageName))
        val km = ctx.getSystemService(KeyguardManager::class.java)
        o.put("keyguardLocked", km.isKeyguardLocked)
        if (Build.VERSION.SDK_INT >= 28) {
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            o.put("appStandbyBucket", when (val b = usm.appStandbyBucket) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"; UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"; UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
                45 -> "RESTRICTED"; else -> "OTHER($b)"
            })
            o.put("backgroundRestricted", ctx.getSystemService(ActivityManager::class.java).isBackgroundRestricted)
        } else {
            o.put("appStandbyBucket", UNAVAILABLE); o.put("backgroundRestricted", UNAVAILABLE)
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        o.put("audioMode", audioModeName(am.mode))
        o.put("ringerMode", am.ringerMode)
        o.put("uptimeMs", SystemClock.elapsedRealtime())
        return o
    }

    fun audioModeName(m: Int) = when (m) {
        AudioManager.MODE_NORMAL -> "NORMAL"; AudioManager.MODE_RINGTONE -> "RINGTONE"; AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"; 4 -> "CALL_SCREENING"; 5 -> "CALL_REDIRECT"
        6 -> "COMMUNICATION_REDIRECT"; else -> "MODE_$m"
    }

    fun permissions(ctx: Context): JSONObject {
        val o = JSONObject()
        fun st(p: String) = if (ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
        o.put("RECORD_AUDIO", st(Manifest.permission.RECORD_AUDIO))
        o.put("POST_NOTIFICATIONS", if (Build.VERSION.SDK_INT >= 33) st(Manifest.permission.POST_NOTIFICATIONS) else "not required (<Android 13)")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        o.put("notificationsEnabled", nm.areNotificationsEnabled())
        return o
    }

    fun hasMicPermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun recognizerInfo(ctx: Context): JSONObject {
        val o = JSONObject()
        o.put("recognitionAvailable", runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrElse { "error: $it" })
        o.put("onDeviceRecognitionAvailable",
            if (Build.VERSION.SDK_INT >= 31) runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrElse { "error: $it" }
            else "$UNAVAILABLE (requires Android 12+)")
        o.put("recordedAudioInputSupported", Build.VERSION.SDK_INT >= 33)
        o.put("defaultRecognitionService", runCatching { Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service") }.getOrNull() ?: UNAVAILABLE)
        o.put("onDeviceRecognitionServiceConfig", onDeviceServiceConfig() ?: UNAVAILABLE)
        val arr = JSONArray()
        runCatching {
            ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).forEach {
                arr.put(it.serviceInfo.packageName + "/" + it.serviceInfo.name)
            }
        }
        o.put("installedRecognitionServices", arr)
        return o
    }

    fun onDeviceServiceConfig(): String? = runCatching {
        val id = Resources.getSystem().getIdentifier("config_defaultOnDeviceSpeechRecognitionService", "string", "android")
        if (id != 0) Resources.getSystem().getString(id).ifBlank { null } else null
    }.getOrNull()

    private var modelInfoCache: JSONArray? = null

    fun modelsInfo(ctx: Context): JSONArray {
        modelInfoCache?.let { return it }
        val arr = JSONArray()
        for (name in listOf("melspectrogram.onnx", "embedding_model.onnx", "hey_jarvis_v0.1.onnx", "silero_vad.onnx")) {
            runCatching {
                val bytes = ctx.assets.open("models/$name").use { it.readBytes() }
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                arr.put(JSONObject().put("name", name).put("bytes", bytes.size).put("sha256", sha))
            }.onFailure { arr.put(JSONObject().put("name", name).put("error", it.toString())) }
        }
        modelInfoCache = arr
        return arr
    }

    fun deviceJson(ctx: Context): JSONObject {
        val o = JSONObject()
        o.put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND).put("model", Build.MODEL)
        o.put("device", Build.DEVICE).put("product", Build.PRODUCT).put("hardware", Build.HARDWARE)
        o.put("androidRelease", Build.VERSION.RELEASE).put("sdkInt", Build.VERSION.SDK_INT)
        o.put("securityPatch", if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else UNAVAILABLE)
        o.put("fingerprint", Build.FINGERPRINT).put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        o.put("locale", Locale.getDefault().toLanguageTag()).put("timezone", TimeZone.getDefault().id)
        o.put("app", JSONObject().put("applicationId", ctx.packageName).put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE).put("gitSha", BuildConfig.GIT_SHA)
            .put("buildTime", BuildConfig.BUILD_TIME).put("buildType", BuildConfig.BUILD_TYPE)
            .put("targetSdk", ctx.applicationInfo.targetSdkVersion))
        o.put("engines", JSONObject()
            .put("wakeWord", "openWakeWord 0.5.1 pretrained hey_jarvis_v0.1 (ONNX), Kotlin port of streaming pipeline, offline")
            .put("vad", "Silero VAD v4 (ONNX, as bundled with openWakeWord 0.5.1), offline")
            .put("onnxRuntime", "onnxruntime-android ${BuildConfig.ORT_VERSION}")
            .put("speechRecognition", "android.speech.SpeechRecognizer (system service; engine depends on device)")
            .put("models", modelsInfo(ctx)))
        o.put("recognizer", recognizerInfo(ctx))
        o.put("permissions", permissions(ctx))
        o.put("power", power(ctx))
        o.put("battery", battery(ctx))
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        o.put("memory", JSONObject().put("totalMb", mi.totalMem / 1048576).put("availMb", mi.availMem / 1048576).put("lowMemory", mi.lowMemory))
        return o
    }
}
