package dk.jarvisbot.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.max

/**
 * Full-screen display of the image bundled in assets/display/ (the first .png/.jpg/.jpeg/.webp file found).
 * Aspect ratio is preserved (letterboxed on black). Tap anywhere or press Back to return.
 * Listening is not affected: it runs in the foreground service, independent of what is on screen.
 */
class ImageActivity : Activity() {
    companion object {
        const val ASSET_DIR = "display"
        private val EXT = listOf(".png", ".jpg", ".jpeg", ".webp")
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private var shownTs = 0L
    private var samplesAtStart = 0L
    private var imageName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val info = JSONObject()
        val bmp = try { loadImage(info) } catch (t: Throwable) { info.put("error", t.toString()); null }
        if (bmp != null) image.setImageBitmap(bmp)
        else root.addView(TextView(this).apply {
            text = "No image found in assets/$ASSET_DIR/\n${info.optString("error")}"
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Small, unobtrusive line showing that listening continues while the image is displayed.
        status = TextView(this).apply {
            textSize = 11f; setTextColor(Color.argb(200, 255, 255, 255)); setBackgroundColor(Color.argb(90, 0, 0, 0))
            setPadding(12, 6, 12, 6)
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        root.addView(TextView(this).apply {
            text = "Tap anywhere to return"; textSize = 12f; setTextColor(Color.argb(170, 255, 255, 255))
            setPadding(12, 6, 12, 18)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.setOnClickListener { finish() }
        setContentView(root)

        shownTs = System.currentTimeMillis()
        samplesAtStart = MicStatus.samples
        JarvisApp.instance.store.log(
            "IMAGE_SHOWN", if (bmp != null) imageName else "no image",
            info.put("micTestRunning", MicStatus.running).put("micState", MicStatus.state)
                .put("wakeWordRunning", ListenerService.instance?.pipeline != null),
            interactionId = MicStatus.sessionId.ifEmpty { null }
        )
    }

    private fun loadImage(info: JSONObject): Bitmap? {
        val files = assets.list(ASSET_DIR)?.filter { n -> EXT.any { n.lowercase().endsWith(it) } }?.sorted().orEmpty()
        val name = files.firstOrNull() ?: run { info.put("error", "no .png/.jpg/.webp file in assets/$ASSET_DIR"); return null }
        imageName = name
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open("$ASSET_DIR/$name").use { BitmapFactory.decodeStream(it, null, bounds) }
        // Downsample very large images so they never exhaust memory (keeps at least screen resolution).
        val screen = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= screen) sample *= 2
        val bmp = assets.open("$ASSET_DIR/$name").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        info.put("file", "assets/$ASSET_DIR/$name").put("width", bounds.outWidth).put("height", bounds.outHeight)
            .put("inSampleSize", sample).put("otherImagesIgnored", files.drop(1).toString())
        return bmp
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val s = MicStatus
            val now = System.currentTimeMillis()
            status.text = when {
                s.running && now - s.lastSampleTs < 1000 ->
                    "🎙 listening · peak %.0f dBFS%s".format(s.peakDb, if (s.recordingNow) " · ● saving" else "")
                s.running -> "🎙 listening · ${s.state.lowercase()}"
                ListenerService.instance?.pipeline != null -> "🎙 wake-word listening active"
                else -> "microphone not listening"
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        handler.post(ticker)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        val shownMs = System.currentTimeMillis() - shownTs
        JarvisApp.instance.store.log(
            "IMAGE_CLOSED", "shown for ${shownMs / 1000} s",
            JSONObject().put("shownMs", shownMs).put("micTestRunning", MicStatus.running)
                .put("audioSamplesReceivedWhileShown", MicStatus.samples - samplesAtStart).put("micState", MicStatus.state),
            interactionId = MicStatus.sessionId.ifEmpty { null }
        )
        super.onDestroy()
    }
}
