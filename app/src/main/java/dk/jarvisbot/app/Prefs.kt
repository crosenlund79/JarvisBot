package dk.jarvisbot.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** All tunables in one place. Values are read live by the audio pipeline. */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun f(k: String, d: Float) = sp.getFloat(k, d)
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun l(k: String, d: Long) = sp.getLong(k, d)

    // --- user facing ---
    var enabled: Boolean get() = b("enabled", false); set(v) = sp.edit().putBoolean("enabled", v).apply()
    var diagnosticMode: Boolean get() = b("diagnosticMode", false); set(v) = sp.edit().putBoolean("diagnosticMode", v).apply()
    var language: String get() = s("language", "da-DK"); set(v) = sp.edit().putString("language", v).apply()
    var threshold: Float get() = f("threshold", 0.5f); set(v) = sp.edit().putFloat("threshold", v).apply()
    var candidateThreshold: Float get() = f("candidateThreshold", 0.15f); set(v) = sp.edit().putFloat("candidateThreshold", v).apply()
    var patience: Int get() = i("patience", 1); set(v) = sp.edit().putInt("patience", v).apply()
    var vadThreshold: Float get() = f("vadThreshold", 0.5f); set(v) = sp.edit().putFloat("vadThreshold", v).apply()
    var preRollMs: Int get() = i("preRollMs", 1500); set(v) = sp.edit().putInt("preRollMs", v).apply()
    var cmdIgnoreMs: Int get() = i("cmdIgnoreMs", 300); set(v) = sp.edit().putInt("cmdIgnoreMs", v).apply()
    var endSilenceMs: Int get() = i("endSilenceMs", 1300); set(v) = sp.edit().putInt("endSilenceMs", v).apply()
    var maxCommandMs: Int get() = i("maxCommandMs", 10000); set(v) = sp.edit().putInt("maxCommandMs", v).apply()
    var noSpeechTimeoutMs: Int get() = i("noSpeechTimeoutMs", 5000); set(v) = sp.edit().putInt("noSpeechTimeoutMs", v).apply()
    var refractoryMs: Int get() = i("refractoryMs", 1500); set(v) = sp.edit().putInt("refractoryMs", v).apply()
    var segPreRollMs: Int get() = i("segPreRollMs", 500); set(v) = sp.edit().putInt("segPreRollMs", v).apply()
    var segHangoverMs: Int get() = i("segHangoverMs", 800); set(v) = sp.edit().putInt("segHangoverMs", v).apply()
    var segMaxMs: Int get() = i("segMaxMs", 20000); set(v) = sp.edit().putInt("segMaxMs", v).apply()
    var wakeClipMs: Int get() = i("wakeClipMs", 3000); set(v) = sp.edit().putInt("wakeClipMs", v).apply()
    var missedClipMs: Int get() = i("missedClipMs", 15000); set(v) = sp.edit().putInt("missedClipMs", v).apply()
    var storageLimitMb: Int get() = i("storageLimitMb", 500); set(v) = sp.edit().putInt("storageLimitMb", v).apply()
    var levelLogSec: Int get() = i("levelLogSec", 10); set(v) = sp.edit().putInt("levelLogSec", v).apply()
    var heartbeatLogSec: Int get() = i("heartbeatLogSec", 60); set(v) = sp.edit().putInt("heartbeatLogSec", v).apply()
    var gain: Float get() = f("gain", 1.0f); set(v) = sp.edit().putFloat("gain", v).apply()
    var audioSource: String get() = s("audioSource", "VOICE_RECOGNITION"); set(v) = sp.edit().putString("audioSource", v).apply()
    var beepEnabled: Boolean get() = b("beepEnabled", true); set(v) = sp.edit().putBoolean("beepEnabled", v).apply()
    var beepStream: String get() = s("beepStream", "MUSIC"); set(v) = sp.edit().putString("beepStream", v).apply()
    var wakeLock: Boolean get() = b("wakeLock", true); set(v) = sp.edit().putBoolean("wakeLock", v).apply()
    var autoRecognize: Boolean get() = b("autoRecognize", true); set(v) = sp.edit().putBoolean("autoRecognize", v).apply()
    /** ON_DEVICE (default, offline) or DEFAULT_SERVICE (explicit opt-in; may send audio to the recognizer vendor). */
    var recognizerMode: String get() = s("recognizerMode", "ON_DEVICE"); set(v) = sp.edit().putString("recognizerMode", v).apply()
    var recognitionTimeoutMs: Int get() = i("recognitionTimeoutMs", 30000); set(v) = sp.edit().putInt("recognitionTimeoutMs", v).apply()

    // --- internal bookkeeping (service lifecycle / heartbeat) ---
    var svcRunningMarker: Boolean get() = b("svcRunningMarker", false); set(v) = sp.edit().putBoolean("svcRunningMarker", v).commit().let { }
    var svcStartTs: Long get() = l("svcStartTs", 0); set(v) = sp.edit().putLong("svcStartTs", v).apply()
    var lastHeartbeatTs: Long get() = l("lastHeartbeatTs", 0); set(v) = sp.edit().putLong("lastHeartbeatTs", v).commit().let { }
    var lastHeartbeatInfo: String get() = s("lastHeartbeatInfo", ""); set(v) = sp.edit().putString("lastHeartbeatInfo", v).apply()
    var lastExitReasonTs: Long get() = l("lastExitReasonTs", 0); set(v) = sp.edit().putLong("lastExitReasonTs", v).apply()
    var enableTs: Long get() = l("enableTs", 0); set(v) = sp.edit().putLong("enableTs", v).apply()
    var batteryAtEnable: Int get() = i("batteryAtEnable", -1); set(v) = sp.edit().putInt("batteryAtEnable", v).apply()
    /** Microphone test sensitivity 0..100 (higher = quieter sounds are saved). */
    var micSensitivity: Int get() = i("micSensitivity", MicTest.DEFAULT_SENSITIVITY); set(v) = sp.edit().putInt("micSensitivity", v.coerceIn(0, 100)).apply()
    /** True while a microphone test should be running (survives process death so interruptions can be reported). */
    var micTestActive: Boolean get() = b("micTestActive", false); set(v) = sp.edit().putBoolean("micTestActive", v).commit().let { }
    var lastManualRecordingId: String get() = s("lastManualRecordingId", ""); set(v) = sp.edit().putString("lastManualRecordingId", v).apply()

    fun toJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all.toSortedMap()) o.put(k, v)
        return o
    }

    companion object {
        val LANGUAGES = listOf("da-DK", "en-US", "en-GB")
        val AUDIO_SOURCES = listOf("VOICE_RECOGNITION", "MIC", "UNPROCESSED", "VOICE_COMMUNICATION", "CAMCORDER")
        val BEEP_STREAMS = listOf("MUSIC", "NOTIFICATION", "ALARM", "SYSTEM")
        val RECOGNIZER_MODES = listOf("ON_DEVICE", "DEFAULT_SERVICE")
    }
}
