package nl.livedub

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Pipeline: playback capture (16 kHz mono) -> Vosk (English STT) -> ML Kit (EN->NL) -> Android TTS (Dutch).
 * Everything runs on-device; models are downloaded once by MainActivity / ModelManager.
 */
class DubService : Service() {

    companion object {
        const val ACTION_START = "nl.livedub.START"
        const val ACTION_STOP = "nl.livedub.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        private const val CHANNEL = "livedub"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 16_000

        /** Force a flush when a partial sentence reaches this many words (YouTube speech rarely pauses). */
        private const val MAX_WORDS = 14

        /** If this many Dutch sentences are still queued, drop the backlog and catch up. */
        private const val MAX_BACKLOG = 4

        @Volatile var running = false
        @Volatile var lastStatus = ""

        // Set by MainActivity while it is visible; always invoked on the main thread.
        var onStatus: ((String) -> Unit)? = null
        var onLine: ((en: String, nl: String) -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var translator: Translator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var worker: Thread? = null

    private val active = ConcurrentHashMap.newKeySet<String>()
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false

    // TTS uses a usage that is NOT in the capture filter, so the app never hears (and re-translates) itself.
    private val speechAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        // Must be in the foreground (type mediaProjection) BEFORE getMediaProjection().
        startForeground(NOTIF_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        running = true
        begin(code, data)
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)
        projection = proj

        initTts()

        val tr = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.DUTCH)
                .build()
        )
        translator = tr
        setStatus("Loading translation model…")
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { if (running) startWorker() }
            .addOnFailureListener {
                setStatus("Translation model unavailable: ${it.message}")
                stopSelf()
            }
    }

    // ---------------------------------------------------------------- capture + recognition

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by MainActivity before the service starts
    private fun buildRecord(proj: MediaProjection): AudioRecord {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 2))
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
    }

    private fun startWorker() {
        val proj = projection ?: return
        worker = Thread({
            var model: Model? = null
            var recognizer: Recognizer? = null
            var audio: AudioRecord? = null
            try {
                setStatus("Loading speech model…")
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val m = Model(ModelManager.modelDir(this).absolutePath)
                model = m
                val rec = Recognizer(m, SAMPLE_RATE.toFloat())
                recognizer = rec
                val record = buildRecord(proj)
                audio = record
                record.startRecording()
                setStatus("Listening… play an English video")

                val buf = ByteArray(4096) // ~128 ms
                while (running && !Thread.currentThread().isInterrupted) {
                    val n = record.read(buf, 0, buf.size)
                    if (n < 0) {
                        setStatus("Audio capture error ($n)")
                        break
                    }
                    if (n == 0) continue

                    if (rec.acceptWaveForm(buf, n)) {
                        // Vosk detected a pause -> sentence is complete
                        emit(JSONObject(rec.result).optString("text"))
                    } else {
                        val partial = JSONObject(rec.partialResult).optString("partial")
                        if (wordCount(partial) >= MAX_WORDS) {
                            // Long run-on speech: cut here instead of waiting for a pause
                            val text = JSONObject(rec.finalResult).optString("text")
                            rec.reset()
                            emit(text)
                        }
                    }
                }
            } catch (t: Throwable) {
                setStatus("Error: ${t.message}")
            } finally {
                runCatching { audio?.stop() }
                audio?.release()
                recognizer?.close()
                model?.close()
                main.post { stopSelf() }
            }
        }, "livedub-worker")
        worker?.start()
    }

    private fun wordCount(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    // ---------------------------------------------------------------- translate + speak

    /** Called from the worker thread. ML Kit delivers its callbacks on the main thread. */
    private fun emit(text: String) {
        val t = text.trim()
        if (wordCount(t) < 2) return // skip noise like a lone "the"
        val tr = translator ?: return
        tr.translate(t)
            .addOnSuccessListener { nl ->
                if (running) {
                    onLine?.invoke(t, nl)
                    speak(nl)
                }
            }
            .addOnFailureListener { setStatus("Translation failed: ${it.message}") }
    }

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                setStatus("Text-to-speech failed to start")
                return@TextToSpeech
            }
            val r = engine.setLanguage(Locale.forLanguageTag("nl-NL"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                setStatus("No Dutch voice installed – add one in Settings → Text-to-speech")
                return@TextToSpeech
            }
            engine.setAudioAttributes(speechAttrs)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finished(utteranceId)

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) = finished(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            })
            ttsReady = true
        }
    }

    private fun speak(text: String) {
        val engine = tts ?: return
        if (!ttsReady) return

        val id = "u${System.nanoTime()}"
        val flush = active.size >= MAX_BACKLOG
        if (flush) active.clear()
        // Dutch usually runs longer than English: speak faster while a backlog builds up
        engine.setSpeechRate(minOf(1.7f, 1.0f + 0.15f * active.size))
        acquireFocus()
        active.add(id)
        engine.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun finished(id: String?) {
        if (id != null) active.remove(id)
        if (active.isEmpty()) main.post { if (active.isEmpty()) releaseFocus() }
    }

    // ---------------------------------------------------------------- audio focus (ducks the English)

    private fun acquireFocus() {
        if (focusHeld) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttrs)
            .setOnAudioFocusChangeListener { _ -> }
            .build()
        focusRequest = req
        focusHeld = getSystemService(AudioManager::class.java)
            .requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseFocus() {
        if (!focusHeld) return
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusHeld = false
    }

    // ---------------------------------------------------------------- status + notification

    private fun setStatus(text: String) {
        lastStatus = text
        main.post {
            onStatus?.invoke(text)
            if (running) {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
            }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "LiveDub", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, DubService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LiveDub")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        tts?.stop()
        tts?.shutdown()
        tts = null
        releaseFocus()
        translator?.close()
        projection?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        setStatus("Stopped")
        super.onDestroy()
    }
}
