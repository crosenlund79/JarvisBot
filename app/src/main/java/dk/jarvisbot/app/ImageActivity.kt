package dk.jarvisbot.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
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
import java.io.File
import kotlin.math.max

/**
 * Full-screen display of the image chosen with "Choose Image" (copied into app storage, so it survives restarts and
 * deletion of the original). Falls back to the bundled placeholder in assets/display/. Aspect ratio is preserved
 * (letterboxed on black), camera rotation (EXIF) is respected. Tap anywhere or press Back to return.
 * Listening is not affected: it runs in the foreground service, independent of what is on screen.
 */
class ImageActivity : Activity() {
    companion object {
        const val ASSET_DIR = "display"
        private val EXT = listOf(".png", ".jpg", ".jpeg", ".webp")

        fun chosenFile(act: Activity) = File(File(act.filesDir, MainActivity.IMAGE_DIR), MainActivity.IMAGE_FILE)

        /** True if Android can decode [f] as an image. */
        fun canDecode(f: File): Boolean {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            return o.outWidth > 0 && o.outHeight > 0
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private var shownTs = 0L
    private var samplesAtStart = 0L

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
            text = "No image could be shown.\n${info.optString("error")}\n\nUse “Choose Image” on the main screen."
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        status = TextView(this).apply {
            textSize = 11f; setTextColor(Color.argb(200, 255, 255, 255)); setBackgroundColor(Color.argb(90, 0, 0, 0))
            setPadding(12, 6, 12, 6)
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        hint = TextView(this).apply {
            text = "Tap anywhere to return"; textSize = 12f; setTextColor(Color.argb(170, 255, 255, 255)); setPadding(12, 6, 12, 18)
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.setOnClickListener { finish() }
        setContentView(root)
        // Only the image remains after a few seconds.
        handler.postDelayed({ status.visibility = View.GONE; hint.visibility = View.GONE; handler.removeCallbacks(ticker) }, 4000)

        shownTs = System.currentTimeMillis()
        samplesAtStart = MicStatus.samples
        JarvisApp.instance.store.log(
            "IMAGE_SHOWN", info.optString("source", "no image"),
            info.put("micTestRunning", MicStatus.running).put("micState", MicStatus.state)
                .put("wakeWordRunning", ListenerService.instance?.pipeline != null),
            interactionId = MicStatus.sessionId.ifEmpty { null }
        )
    }

    private fun loadImage(info: JSONObject): Bitmap? {
        val screen = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val chosen = chosenFile(this)
        if (chosen.exists()) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(chosen.path, bounds)
                val sample = sampleSize(bounds, screen)
                val bmp = BitmapFactory.decodeFile(chosen.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: throw IllegalStateException("decode failed")
                val rotation = runCatching {
                    when (ExifInterface(chosen.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                }.getOrDefault(0f)
                info.put("source", "chosen: " + JarvisApp.instance.prefs.displayImageName).put("width", bounds.outWidth)
                    .put("height", bounds.outHeight).put("inSampleSize", sample).put("rotation", rotation)
                return if (rotation == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
            } catch (t: Throwable) {
                info.put("chosenImageError", t.toString())
                JarvisApp.instance.store.log("IMAGE_LOAD_FAILED", "chosen image could not be shown, using placeholder: $t")
            }
        }
        // Fallback: bundled placeholder.
        val files = assets.list(ASSET_DIR)?.filter { n -> EXT.any { n.lowercase().endsWith(it) } }?.sorted().orEmpty()
        val name = files.firstOrNull() ?: run { info.put("error", "no image chosen and no placeholder"); return null }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open("$ASSET_DIR/$name").use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = sampleSize(bounds, screen)
        info.put("source", "placeholder assets/$ASSET_DIR/$name").put("width", bounds.outWidth).put("height", bounds.outHeight)
        return assets.open("$ASSET_DIR/$name").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    /** Downsample very large images so they never exhaust memory (keeps at least screen resolution). */
    private fun sampleSize(b: BitmapFactory.Options, screen: Int): Int {
        var s = 1
        while (max(b.outWidth, b.outHeight) / (s * 2) >= screen) s *= 2
        return s
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
        if (status.visibility == View.VISIBLE) handler.post(ticker)
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
        handler.removeCallbacksAndMessages(null)
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
