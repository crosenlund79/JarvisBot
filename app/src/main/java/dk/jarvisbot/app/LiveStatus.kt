package dk.jarvisbot.app

/** Process-wide live values for the UI (written by service/pipeline, polled by the activity). */
object LiveStatus {
    @Volatile var serviceRunning = false
    @Volatile var serviceStartTs = 0L
    @Volatile var foreground = false
    @Volatile var wakeEnabled = false
    @Volatile var state = "STOPPED"
    @Volatile var micOpen = false
    @Volatile var micInfo = "-"
    @Volatile var micSessionId = -1
    @Volatile var micSilencedByOs: Boolean? = null
    @Volatile var digitalSilence = false
    @Volatile var otherRecordingClients = 0
    /** Incremented whenever the number of other recording clients increases (used by recognition binding check). */
    @Volatile var otherClientStarts = 0L
    @Volatile var levelDb = -120.0
    @Volatile var peakDb = -120.0
    @Volatile var vadProb = -1f
    @Volatile var wakeScore = -1f
    @Volatile var wakeScoreMaxRecent = 0f
    @Volatile var chunks = 0L
    @Volatile var procAvgMs = 0.0
    @Volatile var procMaxMs = 0.0
    @Volatile var wakeEngineStatus = "not loaded"
    @Volatile var vadEngineStatus = "not loaded"
    @Volatile var lastActivation = "-"
    @Volatile var lastCommand = "-"
    @Volatile var lastAudioChunkTs = 0L
    @Volatile var manualRecordingSince = 0L
    @Volatile var rawCaptureUntil = 0L
    @Volatile var micErrors = 0
    @Volatile var micOpenFailures = 0
    @Volatile var lastHeartbeat = 0L
    @Volatile var recognitionStatus = "idle"
    @Volatile var lastError = "-"
}
