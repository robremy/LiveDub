package nl.livedub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var linesView: TextView
    private lateinit var button: Button
    private val recentLines = ArrayDeque<String>()

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, DubService::class.java)
                    .setAction(DubService.ACTION_START)
                    .putExtra(DubService.EXTRA_CODE, result.resultCode)
                    .putExtra(DubService.EXTRA_DATA, data)
                startForegroundService(intent)
            } else {
                setStatus("Screen-capture permission was declined")
                refreshButton()
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                prepareThenCapture()
            } else {
                setStatus("The microphone permission is required to capture audio playback")
                refreshButton()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(24)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = "LiveDub"
            textSize = 26f
        })
        column.addView(TextView(this).apply {
            text = "Press Start, then play an English YouTube video. You hear a Dutch voice " +
                "(about 2–4 s behind). Everything runs on this phone after the one-time model downloads."
            setPadding(0, dp(8), 0, dp(16))
        })
        button = Button(this).apply {
            setOnClickListener { onButton() }
        }
        column.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        statusView = TextView(this).apply {
            setPadding(0, dp(16), 0, dp(8))
            gravity = Gravity.START
        }
        column.addView(statusView)
        linesView = TextView(this).apply { setTextIsSelectable(true) }
        column.addView(linesView)

        setContentView(ScrollView(this).apply { addView(column) })
        refreshButton()
    }

    override fun onStart() {
        super.onStart()
        DubService.onStatus = { text ->
            setStatus(text)
            refreshButton()
        }
        DubService.onLine = { en, nl -> appendLine(en, nl) }
        if (DubService.lastStatus.isNotEmpty()) setStatus(DubService.lastStatus)
        refreshButton()
    }

    override fun onStop() {
        DubService.onStatus = null
        DubService.onLine = null
        super.onStop()
    }

    private fun onButton() {
        if (DubService.running) {
            stopService(Intent(this, DubService::class.java))
            return
        }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) prepareThenCapture() else permissionLauncher.launch(needed.toTypedArray())
    }

    /** Downloads the two offline models if needed, then asks for screen-capture (audio) permission. */
    private fun prepareThenCapture() {
        button.isEnabled = false
        button.text = "Preparing…"
        Thread {
            try {
                if (!ModelManager.isReady(this)) {
                    ModelManager.download(this) { pct ->
                        runOnUiThread { setStatus("Downloading English speech model… $pct%") }
                    }
                }
                runOnUiThread { setStatus("Checking Dutch translation model…") }
                val translator = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.ENGLISH)
                        .setTargetLanguage(TranslateLanguage.DUTCH)
                        .build()
                )
                try {
                    Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
                } finally {
                    translator.close()
                }
                runOnUiThread {
                    val mpm = getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("Setup failed: ${e.message}")
                    refreshButton()
                }
            }
        }.start()
    }

    private fun refreshButton() {
        button.isEnabled = true
        button.text = if (DubService.running) "Stop dubbing" else "Start dubbing"
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    private fun appendLine(en: String, nl: String) {
        recentLines.addLast("EN: $en\nNL: $nl")
        while (recentLines.size > 6) recentLines.removeFirst()
        linesView.text = recentLines.joinToString("\n\n")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
