package com.peter.minimal

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.peter.minimal.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsStore: SettingsStore
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var wakeWordListeningOn = false

    private val heardTextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ListeningService.ACTION_HEARD_TEXT -> {
                    val text = intent.getStringExtra(ListeningService.EXTRA_TEXT) ?: return
                    val reply = intent.getStringExtra(ListeningService.EXTRA_REPLY) ?: ""
                    binding.recognizedText.text = text
                    binding.replyText.text = reply
                    binding.statusText.text = "Heard you (wake word)"
                }
                ListeningService.ACTION_ERROR -> {
                    val error = intent.getStringExtra(ListeningService.EXTRA_ERROR) ?: "Unknown error"
                    binding.statusText.text = "Error: $error"
                }
            }
        }
    }

    private val requestMicPermission =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                requestNotificationPermissionIfNeededThenStart()
            } else {
                binding.statusText.text = "Microphone permission denied"
            }
        }

    private val requestNotificationPermission =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) {
            startWakeWordListening()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsStore = SettingsStore(applicationContext)
        settingsStore.getGeminiApiKey()?.let {
            binding.geminiKeyInput.setText(it)
        }

        textToSpeech = TextToSpeech(this, this)

        binding.micButton.setOnClickListener {
            checkPermissionAndListenOnce()
        }

        binding.saveGeminiKeyButton.setOnClickListener {
            val key = binding.geminiKeyInput.text.toString().trim()
            if (key.isNotEmpty()) {
                settingsStore.setGeminiApiKey(key)
                Toast.makeText(this, "Gemini key saved", Toast.LENGTH_SHORT).show()
            } else {
                // Allow clearing it by saving empty — some people may want
                // to remove the key to go back to offline-only mode.
                settingsStore.setGeminiApiKey("")
                Toast.makeText(this, "Gemini key cleared (offline mode)", Toast.LENGTH_SHORT).show()
            }
        }

        binding.backgroundToggleButton.setOnClickListener {
            if (wakeWordListeningOn) {
                stopWakeWordListening()
            } else {
                checkPermissionAndStartWakeWord()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ListeningService.ACTION_HEARD_TEXT)
            addAction(ListeningService.ACTION_ERROR)
        }
        ContextCompat.registerReceiver(
            this, heardTextReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(heardTextReceiver)
    }

    // ---- One-shot foreground listening (tap to talk) ----

    private fun checkPermissionAndListenOnce() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startListeningOnce()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListeningOnce() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.statusText.text = "Speech recognition not available on this device"
            return
        }

        binding.statusText.text = "Listening..."
        binding.recognizedText.text = ""

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    binding.statusText.text = "Processing..."
                }

                override fun onError(error: Int) {
                    binding.statusText.text = "Error: ${errorText(error)}"
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    val text = matches?.firstOrNull() ?: "(nothing recognized)"
                    binding.recognizedText.text = text
                    binding.statusText.text = "Heard you"
                    respondTo(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        speechRecognizer?.startListening(intent)
    }

    private fun respondTo(text: String) {
        val reply = when {
            text.contains("hello", ignoreCase = true) -> "Hi there!"
            text.contains("your name", ignoreCase = true) -> "I'm Peter."
            text.contains("thank", ignoreCase = true) -> "You're welcome."
            else -> "You said: $text"
        }
        binding.replyText.text = reply
        speak(reply)
    }

    private fun speak(text: String) {
        if (ttsReady) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.getDefault()
            ttsReady = true
        } else {
            Toast.makeText(this, "Text-to-speech unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No match"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout, try again"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "No permission"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
        else -> "Unknown ($error)"
    }

    // ---- Wake-word foreground-service listening ----

    private fun checkPermissionAndStartWakeWord() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        requestNotificationPermissionIfNeededThenStart()
    }

    private fun requestNotificationPermissionIfNeededThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotif = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotif) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startWakeWordListening()
    }

    private fun startWakeWordListening() {
        val serviceIntent = Intent(this, ListeningService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        wakeWordListeningOn = true
        binding.backgroundToggleButton.text = "Stop wake word listening"
        binding.statusText.text = "Wake word listening started — check notification"
    }

    private fun stopWakeWordListening() {
        val serviceIntent = Intent(this, ListeningService::class.java).apply {
            action = ListeningService.ACTION_STOP
        }
        startService(serviceIntent)
        wakeWordListeningOn = false
        binding.backgroundToggleButton.text = "Start wake word listening"
        binding.statusText.text = "Wake word listening stopped"
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
