package com.peter.minimal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Foreground service using Vosk for fully offline, continuous speech
 * transcription. There is no separate "wake word engine" here — Vosk
 * transcribes everything it hears, and this service checks whether the
 * wake word (default: "peter") appears in each transcribed phrase.
 *
 * IMPORTANT / UNVERIFIED: the Vosk Android API shape used below (Model,
 * Recognizer, SpeechService, RecognitionListener with onResult/onPartialResult/
 * onError/onTimeout) is written from my best knowledge of vosk-android's
 * typical sample-app pattern, but I have NOT verified it against a live
 * build — no network access here to check current docs/source. If this
 * fails to compile, check https://github.com/alphacep/vosk-api Android
 * examples and adjust the API calls below — the surrounding
 * service/notification/broadcast structure should still be sound.
 *
 * Real behavioral difference from a dedicated wake-word engine: Vosk is
 * always fully transcribing, not just detecting a keyword. This uses more
 * CPU/battery than Porcupine would have.
 *
 * NOT YET VERIFIED: whether this survives your Oppo (ColorOS) phone's
 * background/battery restrictions once the screen locks. Test on the
 * real device.
 */
class ListeningService : Service(), TextToSpeech.OnInitListener {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    // Change this if you want a different wake word. Matching is a simple
    // substring check on Vosk's lowercase transcription — not fuzzy, so
    // pick something Vosk is likely to transcribe accurately.
    private val wakeWord = "peter"

    companion object {
        const val CHANNEL_ID = "peter_listening_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.peter.minimal.action.STOP"

        const val ACTION_HEARD_TEXT = "com.peter.minimal.HEARD_TEXT"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_REPLY = "extra_reply"

        const val ACTION_ERROR = "com.peter.minimal.SERVICE_ERROR"
        const val EXTRA_ERROR = "extra_error"

        private const val MODEL_ASSET_FOLDER = "model"
        private const val SAMPLE_RATE = 16000.0f
    }

    override fun onCreate() {
        super.onCreate()
        textToSpeech = TextToSpeech(this, this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Loading speech model..."))
        isRunning = true
        loadModelAndStartListening()
        return START_STICKY
    }

    // ---- Model loading ----

    private fun loadModelAndStartListening() {
        // Vosk's Model class expects a real filesystem path, not an asset
        // path directly — assets must be copied out to app storage first.
        backgroundExecutor.execute {
            try {
                val modelDir = copyModelFromAssetsIfNeeded()
                model = Model(modelDir.absolutePath)
                handler.post {
                    startRecognition()
                }
            } catch (e: IOException) {
                handler.post {
                    reportError(
                        "Could not prepare speech model. Did you add the " +
                            "model folder to app/src/main/assets/model/ ? (${e.message})"
                    )
                }
            } catch (e: Exception) {
                handler.post {
                    reportError("Could not load speech model: ${e.message}")
                }
            }
        }
    }

    /**
     * Copies app/src/main/assets/model/** to internal app storage, once.
     * Vosk needs a real directory path on disk, not an APK asset path.
     */
    private fun copyModelFromAssetsIfNeeded(): File {
        val targetDir = File(filesDir, MODEL_ASSET_FOLDER)
        val markerFile = File(filesDir, "model_copy_complete.marker")

        if (targetDir.exists() && markerFile.exists()) {
            return targetDir
        }

        // Fresh copy — clear any partial previous attempt.
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        copyAssetFolderRecursive(MODEL_ASSET_FOLDER, targetDir)

        markerFile.createNewFile()
        return targetDir
    }

    private fun copyAssetFolderRecursive(assetPath: String, targetDir: File) {
        val assetManager = applicationContext.assets
        val children = assetManager.list(assetPath)
            ?: throw IOException("No 'model' folder found in assets — see README_WAKE_WORD_MODEL.txt")

        if (children.isEmpty()) {
            // Leaf file, not a directory — shouldn't normally hit this branch
            // since we only recurse into confirmed directories below.
            return
        }

        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val subChildren = assetManager.list(childAssetPath)

            if (subChildren.isNullOrEmpty()) {
                // It's a file.
                val outFile = File(targetDir, child)
                assetManager.open(childAssetPath).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                // It's a directory — recurse.
                val childDir = File(targetDir, child)
                childDir.mkdirs()
                copyAssetFolderRecursive(childAssetPath, childDir)
            }
        }
    }

    // ---- Recognition ----

    private fun startRecognition() {
        val currentModel = model
        if (currentModel == null) {
            reportError("Speech model not loaded")
            return
        }

        try {
            recognizer = Recognizer(currentModel, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    // Partial (in-progress) transcription — not acted on,
                    // but could be surfaced in the UI later if useful.
                }

                override fun onResult(hypothesis: String?) {
                    val text = extractText(hypothesis)
                    if (!text.isNullOrBlank()) {
                        onTranscribedPhrase(text)
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    val text = extractText(hypothesis)
                    if (!text.isNullOrBlank()) {
                        onTranscribedPhrase(text)
                    }
                }

                override fun onError(exception: Exception?) {
                    reportError("Recognition error: ${exception?.message}")
                    restartAfterDelay()
                }

                override fun onTimeout() {
                    restartAfterDelay()
                }
            })
            updateNotification("Listening for \"$wakeWord\"...")
        } catch (e: Exception) {
            reportError("Could not start recognizer: ${e.message}")
        }
    }

    private fun extractText(hypothesisJson: String?): String? {
        if (hypothesisJson.isNullOrBlank()) return null
        return try {
            // Vosk returns JSON like {"text": "hello peter"}
            JSONObject(hypothesisJson).optString("text", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun onTranscribedPhrase(text: String) {
        val lower = text.lowercase(Locale.getDefault())
        if (lower.contains(wakeWord)) {
            handleRecognizedText(text)
        }
        // Phrases without the wake word are discarded — Vosk keeps
        // listening continuously regardless.
    }

    private fun restartAfterDelay() {
        if (!isRunning) return
        handler.postDelayed({
            if (isRunning) {
                startRecognition()
            }
        }, 500)
    }

    // ---- Result handling (Gemini, same as before) ----

    private fun handleRecognizedText(text: String) {
        updateNotification("Heard: \"$text\" — thinking...")

        val broadcastHeard = Intent(ACTION_HEARD_TEXT).apply {
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_REPLY, "")
            setPackage(packageName)
        }
        sendBroadcast(broadcastHeard)

        val settings = SettingsStore(applicationContext)
        val geminiKey = settings.getGeminiApiKey()

        if (geminiKey.isNullOrBlank()) {
            val reply = offlineReply(text)
            deliverReply(text, reply)
            return
        }

        backgroundExecutor.execute {
            val reply = try {
                GeminiClient(geminiKey).generateReply(text)
            } catch (e: Exception) {
                "[Gemini unavailable: ${e.message}] ${offlineReply(text)}"
            }
            handler.post {
                deliverReply(text, reply)
            }
        }
    }

    private fun deliverReply(heardText: String, reply: String) {
        updateNotification("Heard: \"$heardText\"")
        speak(reply)

        val broadcast = Intent(ACTION_HEARD_TEXT).apply {
            putExtra(EXTRA_TEXT, heardText)
            putExtra(EXTRA_REPLY, reply)
            setPackage(packageName)
        }
        sendBroadcast(broadcast)
    }

    private fun offlineReply(text: String): String = when {
        text.contains("hello", ignoreCase = true) -> "Hi there!"
        text.contains("your name", ignoreCase = true) -> "I'm Peter."
        text.contains("thank", ignoreCase = true) -> "You're welcome."
        else -> "You said: $text"
    }

    private fun speak(text: String) {
        if (ttsReady) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            textToSpeech?.language = Locale.getDefault()
        }
    }

    private fun reportError(message: String) {
        updateNotification("Error: $message")
        val broadcast = Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR, message)
            setPackage(packageName)
        }
        sendBroadcast(broadcast)
    }

    private fun stopEverything() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            // best-effort cleanup
        }
        speechService = null
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Peter Listening",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, ListeningService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Peter")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEverything()
        backgroundExecutor.shutdownNow()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
