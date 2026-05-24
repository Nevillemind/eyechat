package com.bits.telechat

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.texne.g1.basis.client.G1ServiceClient
import io.texne.g1.basis.client.G1ServiceCommon
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.net.Socket
import java.net.InetSocketAddress
import com.bits.telechat.cpp.Cpp
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        // Broadcast actions from Hub
        const val ACTION_QUICKNOTE_RELEASE = "com.bits.g1hub.QUICKNOTE_RELEASE"
        const val ACTION_AUDIO_CHUNK = "com.bits.g1hub.AUDIO_CHUNK"
        const val ACTION_AUDIO_STREAM_END = "com.bits.g1hub.AUDIO_STREAM_END"
        const val ACTION_GESTURE_TAP = "com.bits.g1hub.GESTURE_TAP"

        // Broadcast actions TO Hub
        const val ACTION_REQUEST_AUDIO = "com.bits.g1hub.REQUEST_AUDIO"
        const val ACTION_ACK_AUDIO = "com.bits.g1hub.ACK_AUDIO"

        const val EXTRA_GLASSES_ID = "glasses_id"
        const val EXTRA_AUDIO_PAYLOAD = "audio_payload"
        const val EXTRA_NOTE_INDEX = "note_index"
        const val EXTRA_NOTE_DATA = "note_data"
        const val EXTRA_GESTURE_TYPE = "gesture_type"
    }

    // ── G1 Glasses ──
    private var glassesService: G1ServiceClient? = null
    private var glassesId: String? = null

    // ── QuickNote Audio Pipeline ──
    private var isReceivingAudio = false
    private val audioPayloadBuffer = ByteArrayOutputStream()
    private var currentNoteIndex = 1
    private var lastNotesData: ByteArray? = null
    private var streamTimeoutJob: Job? = null

    private fun resetStreamTimeout() {
        streamTimeoutJob?.cancel()
        streamTimeoutJob = lifecycleScope.launch {
            delay(30000)
            if (isReceivingAudio) {
                isReceivingAudio = false
                withContext(Dispatchers.Main) { appendChat("⚠️ Stream timeout after 30s idle — processing ${audioPayloadBuffer.size()} bytes") }
                lifecycleScope.launch(Dispatchers.IO) { processAudioAndTranscribe() }
            }
        }
    }

    // ── Relay Server ──
    private val relayUrl = "http://100.82.28.24:5555"
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    private val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(0, 1, java.util.concurrent.TimeUnit.MILLISECONDS))
        .protocols(listOf(Protocol.HTTP_1_1))
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()
    private val gson = Gson()
    private var pollJob: Job? = null
    private var lastMessageId = ""
    private var lastSentText = ""

    // ── UI ──
    private lateinit var statusText: TextView
    private lateinit var relayStatus: TextView
    private lateinit var chatDisplay: TextView
    private lateinit var glassesIndicator: TextView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var connectButton: Button
    private lateinit var stopButton: Button
    private lateinit var rawTestButton: Button

    // ── Audio Receiver ──
    private val audioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_GESTURE_TAP -> {
                    val gestureType = intent.getIntExtra(EXTRA_GESTURE_TYPE, 0)
                    Log.d("EyeChat", "Gesture tap: type=$gestureType")
                    if (gestureType == 0x01) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            glassesId?.let { glassesService?.stopDisplaying(it) }
                        }
                        appendChat("👓 Display cleared (tap)")
                    }
                }
                ACTION_QUICKNOTE_RELEASE -> {
                    // Auto-clear glasses when starting new recording
                    lifecycleScope.launch(Dispatchers.IO) {
                        glassesId?.let { glassesService?.stopDisplaying(it) }
                    }
                    val noteIndex = intent.getIntExtra(EXTRA_NOTE_INDEX, 1)
                    val noteData = intent.getByteArrayExtra(EXTRA_NOTE_DATA)
                    val hubGlassesId = intent.getStringExtra(EXTRA_GLASSES_ID)  // Hub's own device key
                    Log.d("EyeChat_AUDIO", "[Q1] QUICKNOTE_RELEASE received: noteIndex=$noteIndex hubGlassesId=$hubGlassesId noteData=${noteData?.size} bytes")
                    Log.d("EyeChat_AUDIO", "[Q1a] Local glassesId field=$glassesId")
                    handleQuickNoteRelease(noteIndex, noteData, hubGlassesId)
                }
                ACTION_AUDIO_CHUNK -> {
                    val payload = intent.getByteArrayExtra(EXTRA_AUDIO_PAYLOAD)
                    val chunkGlassesId = intent.getStringExtra(EXTRA_GLASSES_ID)
                    if (payload != null && isReceivingAudio) {
                        val hexHeader = payload.take(20).joinToString(" ") { "%02X".format(it) }
                        Log.d("EyeChat_AUDIO", "[C1] AUDIO_CHUNK: ${payload.size} bytes gid=$chunkGlassesId hex=${hexHeader}")
                        synchronized(audioPayloadBuffer) {
                            audioPayloadBuffer.write(payload)
                            Log.d("EyeChat_AUDIO", "[C2] Buffer now ${audioPayloadBuffer.size()} bytes total")
                        }
                        resetStreamTimeout()
                    } else if (payload != null && !isReceivingAudio) {
                        Log.d("EyeChat_AUDIO", "[C1w] AUDIO_CHUNK received but !isReceivingAudio — ignoring ${payload.size} bytes")
                    } else {
                        Log.d("EyeChat_AUDIO", "[C1w] AUDIO_CHUNK with null payload")
                    }
                }
                ACTION_AUDIO_STREAM_END -> {
                    Log.d("EyeChat_AUDIO", "[E1] Audio stream end received")
                    streamTimeoutJob?.cancel()
                    // Acknowledge stream end to keep Hub state machine healthy
                    val ackIntent = Intent(ACTION_ACK_AUDIO)
                    ackIntent.setPackage("io.texne.g1.hub")
                    val streamEndGlassesId = intent.getStringExtra(EXTRA_GLASSES_ID) ?: glassesId
                    ackIntent.putExtra(EXTRA_GLASSES_ID, streamEndGlassesId)
                    Log.d("EyeChat_AUDIO", "[E2] Sending ACK_AUDIO glassesId=$streamEndGlassesId")
                    sendBroadcast(ackIntent)
                    if (isReceivingAudio) {
                        isReceivingAudio = false
                        val size = synchronized(audioPayloadBuffer) { audioPayloadBuffer.size() }
                        Log.d("EyeChat_AUDIO", "[E3] Stream ended. Total bytes in buffer=$size. Launching transcribe.")
                        appendChat("Audio received: $size bytes raw payload")
                        lifecycleScope.launch(Dispatchers.IO) { processAudioAndTranscribe() }
                    } else {
                        Log.d("EyeChat_AUDIO", "[E3w] Stream end but !isReceivingAudio — already timed out or processed?")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        relayStatus = findViewById(R.id.relayStatus)
        chatDisplay = findViewById(R.id.chatDisplay)
        glassesIndicator = findViewById(R.id.glassesIndicator)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        connectButton = findViewById(R.id.connectButton)
        stopButton = findViewById(R.id.stopButton)
        rawTestButton = findViewById(R.id.rawTestButton)

        requestPermissions()

        // Register for audio broadcasts from Hub
        val filter = IntentFilter().apply {
            addAction(ACTION_QUICKNOTE_RELEASE)
            addAction(ACTION_AUDIO_CHUNK)
            addAction(ACTION_AUDIO_STREAM_END)
            addAction(ACTION_GESTURE_TAP)
        }
        ContextCompat.registerReceiver(this, audioReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        connectButton.setOnClickListener { connectGlasses() }
        sendButton.setOnClickListener { sendTextMessage() }
        stopButton.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // Try multiple methods to clear display
                    glassesId?.let { gid ->
                        // Method 1: Official stopDisplaying
                        glassesService?.stopDisplaying(gid)
                        // Method 2: Send empty text page to overwrite display
                        try {
                            glassesService?.displayTextPage(gid, listOf("", "", "", "", ""))
                        } catch (_: Exception) {}
                    }
                    withContext(Dispatchers.Main) { 
                        glassesIndicator.text = "Display cleared"
                        appendChat("🧹 Display cleared (STOP)")
                    }
                    // Retry in case glasses are in QuickNote dark period
                    for (retrySec in listOf(5, 15, 35)) {
                        delay(retrySec * 1000L)
                        if (glassesId != null && glassesService != null) {
                            glassesService?.stopDisplaying(glassesId!!)
                            try {
                                glassesService?.displayTextPage(glassesId!!, listOf("", "", "", "", ""))
                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { appendChat("Error clearing: ${e.message}") }
                }
            }
        }
        rawTestButton.setOnClickListener { testRawSocket() }

        checkRelayConnection()

        // Mic button
        val micButton = findViewById<Button>(R.id.micButton)
        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    appendChat("🎤 Hold RIGHT temple to record voice note")
                    appendChat("Release to send → glasses record locally → transfer → transcribe")
                    true
                }
                else -> false
            }
        }
    }

    // ══════════════════════════════════════
    // GLASSES CONNECTION
    // ══════════════════════════════════════

    private fun connectGlasses() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { appendChat("Connecting to glasses via Hub...") }
                glassesService = G1ServiceClient.open(this@MainActivity)
                if (glassesService == null) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Status: Hub not found"
                        appendChat("❌ Hub service not found")
                    }
                    return@launch
                }
                // Wait for service to discover glasses
                var glasses: List<io.texne.g1.basis.client.G1ServiceCommon.Glasses> = emptyList()
                for (i in 1..10) {
                    val st = glassesService!!.state.value
                    if (st != null && st.glasses.isNotEmpty()) {
                        glasses = st.glasses
                        break
                    }
                    delay(500)
                }
                if (glasses.isNotEmpty()) {
                    glassesId = glasses.first().id
                    withContext(Dispatchers.Main) {
                        statusText.text = "Status: Connected"
                        statusText.setTextColor(0xFF00E676.toInt())
                        glassesIndicator.text = "Glasses: ${glasses.first().name}"
                        appendChat("✅ Connected — Glasses: ${glasses.first().name}")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Status: Hub found, no glasses"
                        appendChat("⚠️ Hub found but no glasses paired")
                    }
                }
            } catch (e: Exception) {
                Log.e("EyeChat", "Connect failed", e)
                withContext(Dispatchers.Main) {
                    statusText.text = "Status: Hub not found"
                    appendChat("❌ Connection failed: ${e.message}")
                }
            }
        }
    }

    private fun sendTextMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        messageInput.text.clear()
        appendChat("You: $text")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = gson.toJson(mapOf("message" to text, "from" to "george"))
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$relayUrl/api/send").header("Connection", "close").post(body).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) { appendChat("Relay send failed: ${response.code}") }
                    }
                }
            } catch (e: Exception) {
                Log.e("EyeChat", "POST send failed", e)
                withContext(Dispatchers.Main) { appendChat("Relay error: ${e.javaClass.simpleName} — ${e.message}") }
            }
        }
    }

    // ══════════════════════════════════════
    // QUICKNOTE AUDIO PIPELINE
    // ══════════════════════════════════════

    private fun handleQuickNoteRelease(noteIndex: Int, noteData: ByteArray?, hubGlassesId: String?) {
        appendChat("🎤 QuickNote detected (note #$noteIndex)")
        currentNoteIndex = noteIndex
        synchronized(audioPayloadBuffer) { audioPayloadBuffer.reset() }
        isReceivingAudio = true

        // V17 MATCH: use local glassesId (not hubGlassesId)
        val intent = Intent(ACTION_REQUEST_AUDIO)
        intent.putExtra(EXTRA_GLASSES_ID, glassesId)
        intent.putExtra(EXTRA_NOTE_INDEX, noteIndex)
        intent.setPackage("io.texne.g1.hub")
        sendBroadcast(intent)

        resetStreamTimeout()
    }

    /**
     * Full audio pipeline:
     * 1. Collect raw payloads from audioPayloadBuffer (all BLE chunks concatenated)
     * 2. Pass entire concatenated payload to C++ LC3 decoder
     * 3. C++ decoder internally slices at 20-byte LC3 frame boundaries
     * 4. Wrap PCM in WAV header
     * 5. Send to relay for Whisper transcription
     */
    private suspend fun processAudioAndTranscribe() {
        val rawPayloads: ByteArray
        synchronized(audioPayloadBuffer) {
            rawPayloads = audioPayloadBuffer.toByteArray()
            audioPayloadBuffer.reset()
        }

        if (rawPayloads.isEmpty()) {
            withContext(Dispatchers.Main) { appendChat("⚠️ No audio data to process") }
            return
        }

        withContext(Dispatchers.Main) {
            appendChat("Audio received: ${rawPayloads.size} bytes raw payload")
            glassesIndicator.text = "Decoding ${rawPayloads.size} bytes..."
        }

        // Fix LC3 frame alignment: Each BLE chunk is 190 bytes (200 - 10 header)
        // which is 9 x 20-byte frames + 10 leftover bytes. Simply concatenating creates
        // misaligned frame boundaries. We must extract complete 20-byte frames per chunk.
        //
        // Approach: collect chunks as they arrive (track boundaries), then extract aligned frames.
        // Simpler approach: just realign the whole buffer by discarding trailing bytes per-chunk-equivalent.
        // Since each chunk contributes 190 bytes and 190 mod 20 = 10, every ~190 bytes has 10 garbage bytes
        // at the boundary. The fix: pass the raw buffer to decoder BUT the decoder needs aligned input.
        //
        // Actually the simplest correct fix: the concatenated buffer from 190-byte chunks IS byte-continuous
        // LC3 data — there are NO gaps. The 10-byte headers are already stripped. The issue is that
        // 190 mod 20 = 10 means frame boundaries don't align on chunk boundaries, but that's fine —
        // LC3 frames CAN span chunk boundaries. The decoder reads 20 bytes at a time from the
        // concatenated buffer, and as long as the data is continuous LC3 bytes, it works.
        //
        // So the REAL question: is the audioPayload actually continuous LC3 data? Let's log and verify.
        Log.d("EyeChat_AUDIO", "[ALIGN] Raw payload size: ${rawPayloads.size} bytes, " +
            "total possible frames: ${rawPayloads.size / 20}, remainder: ${rawPayloads.size % 20}")

        val totalFrames = rawPayloads.size / 20
        if (totalFrames == 0) {
            withContext(Dispatchers.Main) {
                appendChat("⚠️ Audio too short — ${rawPayloads.size} bytes")
                glassesIndicator.text = "Audio too short"
            }
            return
        }

        val pcm: ByteArray = Cpp.decodeLC3(rawPayloads)

        withContext(Dispatchers.Main) {
            appendChat("Decoded: $totalFrames LC3 frames → ${pcm.size} bytes PCM (${pcm.size / 32000}s audio)")
            glassesIndicator.text = "Transcribing..."
        }

        if (pcm.isEmpty()) {
            withContext(Dispatchers.Main) { appendChat("⚠️ LC3 decode produced no PCM data") }
            return
        }

        val wav = createWav(pcm, 16000, 1, 16)

        // Save WAV for debugging
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val debugFile = java.io.File(downloadsDir, "eyechat_debug_${System.currentTimeMillis()}.wav")
            debugFile.writeBytes(wav)
            withContext(Dispatchers.Main) { appendChat("Debug WAV saved: ${debugFile.name} (${wav.size} bytes)") }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { appendChat("⚠️ Couldn't save debug WAV: ${e.message}") }
        }

        // Send to relay for Whisper transcription (with retry for Tailscale DERP)
        var transcribed = false
        for (attempt in 1..3) {
            try {
                val requestBody = wav.toRequestBody("audio/wav".toMediaType())
                val request = Request.Builder()
                    .url("$relayUrl/api/transcribe")
                    .header("Connection", "close")
                    .post(requestBody)
                    .build()

                if (attempt > 1) {
                    withContext(Dispatchers.Main) { appendChat("Retrying relay (attempt $attempt/3)...") }
                    delay(5000)
                }

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val result = gson.fromJson(responseBody, TranscriptionResult::class.java)
                        var transcript = result.text?.trim() ?: ""

                        if (transcript.isEmpty()) {
                            transcript = transcribeLocal(wav) ?: ""
                        }

                        if (transcript.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                appendChat("You (voice): $transcript")
                                displayOnGlasses("${transcript.take(120)} — sent, awaiting Doug…")
                            }
                            sendMessageToRelay(transcript)
                            transcribed = true
                        } else {
                            withContext(Dispatchers.Main) { appendChat("Voice: no speech detected after all attempts") }
                            transcribed = true
                        }
                    } else {
                        withContext(Dispatchers.Main) { appendChat("Relay error: ${response.code} — trying local Whisper...") }
                        val localResult = transcribeLocal(wav)
                        if (localResult != null && localResult.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                appendChat("You (voice) [local]: $localResult")
                                displayOnGlasses(localResult.take(120))
                            }
                            sendMessageToRelay(localResult)
                        } else {
                            withContext(Dispatchers.Main) { appendChat("Voice: no speech detected [local]") }
                        }
                        transcribed = true
                    }
                }
            } catch (e: Exception) {
                Log.e("EyeChat", "POST transcribe attempt $attempt failed", e)
                withContext(Dispatchers.Main) { appendChat("Relay error (attempt $attempt/3): ${e.javaClass.simpleName} — ${e.message}") }
                if (attempt == 3) {
                    val localResult = transcribeLocal(wav)
                    if (localResult != null && localResult.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            appendChat("You (voice) [local]: $localResult")
                            displayOnGlasses(localResult.take(120))
                        }
                        sendMessageToRelay(localResult)
                    } else {
                        withContext(Dispatchers.Main) { appendChat("Voice: no speech detected [local]") }
                    }
                }
            }
            if (transcribed) break
        }

        withContext(Dispatchers.Main) { glassesIndicator.text = "Ready" }
    }

    // ══════════════════════════════════════
    // WAV FILE CREATION
    // ══════════════════════════════════════

    private fun createWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val headerSize = 44
        val fileSize = headerSize + dataSize

        val wav = java.io.ByteArrayOutputStream()
        // RIFF header
        wav.write("RIFF".toByteArray())
        wav.write(intToLE(fileSize, 4))
        wav.write("WAVE".toByteArray())
        // fmt subchunk
        wav.write("fmt ".toByteArray())
        wav.write(intToLE(16, 4)) // PCM
        wav.write(shortToLE(1)) // audio format PCM
        wav.write(shortToLE(channels.toShort()))
        wav.write(intToLE(sampleRate, 4))
        wav.write(intToLE(byteRate, 4))
        wav.write(shortToLE(blockAlign.toShort()))
        wav.write(shortToLE(bitsPerSample.toShort()))
        // data subchunk
        wav.write("data".toByteArray())
        wav.write(intToLE(dataSize, 4))
        wav.write(pcmData)
        return wav.toByteArray()
    }

    private fun shortToLE(s: Short): ByteArray {
        return byteArrayOf((s.toInt() and 0xFF).toByte(), (s.toInt() shr 8 and 0xFF).toByte())
    }

    private fun intToLE(v: Int, bytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until bytes) {
            out.write((v shr (8 * i)) and 0xFF)
        }
        return out.toByteArray()
    }

    // ══════════════════════════════════════
    // RELAY COMMUNICATION
    // ══════════════════════════════════════

    private fun sendMessageToRelay(text: String) {
        // Dedup: don't re-send the same message
        if (text.trim() == lastSentText.trim()) {
            Log.d("EyeChat", "Skipping duplicate relay send: ${text.take(50)}")
            return
        }
        lastSentText = text.trim()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = gson.toJson(mapOf("message" to text, "from" to "george"))
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$relayUrl/api/send").header("Connection", "close").post(body).build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        // Advance lastMessageId past our own message so the poll
                        // doesn't pick it up as stale/cached from a previous recording.
                        val respBody = response.body?.string() ?: ""
                        if (respBody.isNotEmpty()) {
                            try {
                                val sent = gson.fromJson(respBody, RelayMessage::class.java)
                                if (sent.id.isNotEmpty()) {
                                    lastMessageId = sent.id
                                }
                            } catch (_: Exception) {}
                        }
                    } else {
                        withContext(Dispatchers.Main) { appendChat("Relay send failed: ${response.code}") }
                    }
                }
            } catch (e: Exception) {
                Log.e("EyeChat", "POST send failed", e)
                withContext(Dispatchers.Main) { appendChat("Relay error: ${e.javaClass.simpleName} — ${e.message}") }
            }
        }
    }

    private fun checkRelayConnection() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$relayUrl/api/health").build()
                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            relayStatus.text = "Relay: Connected"
                            relayStatus.setTextColor(0xFF00E676.toInt())
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            relayStatus.text = "Relay: Error ${resp.code}"
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { relayStatus.text = "Relay: ${e.message}" }
                delay(10000)
                checkRelayConnection()
            }
            startRelayPolling()
        }
    }

    private fun startRelayPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val request = Request.Builder()
                        .url("$relayUrl/api/poll?after=$lastMessageId")
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            if (body.isNotEmpty() && body != "[]") {
                                val messages = gson.fromJson(body, Array<RelayMessage>::class.java)
                                for (msg in messages) {
                                    if (msg.from == "doug" && msg.id != lastMessageId) {
                                        lastMessageId = msg.id
                                        withContext(Dispatchers.Main) {
                                            appendChat("Doug: ${msg.text}")
                                            displayOnGlasses(msg.text)
                                        }
                                        // Retry after glasses wake from QuickNote dark period
                                        lifecycleScope.launch(Dispatchers.Default) {
                                            for (retrySec in listOf(15, 35, 55)) {
                                                delay(retrySec * 1000L)
                                                if (glassesId != null && glassesService != null) {
                                                    displayOnGlasses(msg.text)
                                                    Log.d("EyeChat", "Display retry after dark period ($retrySec s)")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
                delay(2000)
            }
        }
    }

    // ══════════════════════════════════════
    // GLASSES DISPLAY
    // ══════════════════════════════════════

    private suspend fun displayOnGlasses(text: String) {
        val id = glassesId
        if (id == null || glassesService == null) {
            glassesIndicator.text = "No glasses for display"
            return
        }
        try {
            val words = text.split(" ")
            val lines = mutableListOf<String>()
            var currentLine = ""
            for (word in words) {
                if ((currentLine + " " + word).trim().length > 40) {
                    lines.add(currentLine.trim())
                    currentLine = word
                } else {
                    currentLine = (currentLine + " " + word).trim()
                }
            }
            if (currentLine.isNotEmpty()) lines.add(currentLine.trim())
            val pages = lines.chunked(5)

            for ((index, pageLines) in pages.withIndex()) {
                val padded = pageLines.toMutableList()
                while (padded.size < 5) padded.add("")
                val page = G1ServiceCommon.FormattedPage(
                    lines = padded.map { G1ServiceCommon.FormattedLine(text = it, justify = G1ServiceCommon.JustifyLine.LEFT) },
                    justify = G1ServiceCommon.JustifyPage.CENTER
                )
                val timeout = if (index < pages.size - 1) 8000L else Long.MAX_VALUE
                val timed = G1ServiceCommon.TimedFormattedPage(page, timeout)
                glassesService!!.displayTimedFormattedPage(id, timed)
                glassesIndicator.text = "Page ${index + 1}/${pages.size}" +
                    if (index < pages.size - 1) " (8s)" else " (persistent)"
                if (index < pages.size - 1) delay(8200)
            }
            glassesIndicator.text = "Displaying — ${pages.size} page(s)"
        } catch (e: Exception) {
            glassesIndicator.text = "Display error: ${e.message}"
        }
    }

    // ══════════════════════════════════════
    // LOCAL WHISPER FALLBACK
    // ══════════════════════════════════════

    private suspend fun transcribeLocal(wavData: ByteArray): String? {
        return withContext(Dispatchers.IO) {
            try {
                val tmpFile = java.io.File.createTempFile("eyechat_", ".wav", cacheDir)
                tmpFile.writeBytes(wavData)
                val pb = ProcessBuilder(
                    "/opt/homebrew/bin/whisper",
                    tmpFile.absolutePath,
                    "--model", "base",
                    "--output_format", "txt",
                    "--output_dir", cacheDir.absolutePath,
                    "--language", "en"
                )
                pb.redirectErrorStream(true)
                val process = pb.start()
                process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                tmpFile.delete()

                val txtFile = java.io.File(cacheDir, tmpFile.nameWithoutExtension + ".txt")
                if (txtFile.exists()) {
                    val text = txtFile.readText().trim()
                    txtFile.delete()
                    if (text.isNotEmpty()) {
                        withContext(Dispatchers.Main) { appendChat("Local Whisper: $text") }
                        return@withContext text
                    }
                }
                null
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendChat("Local Whisper failed: ${e.message}") }
                null
            }
        }
    }

    // ══════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════

    private fun appendChat(line: String) {
        chatDisplay.append("$line\n")
        findViewById<ScrollView>(R.id.chatScroll).fullScroll(View.FOCUS_DOWN)
    }

    private fun requestPermissions() {
        val perms = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    // ── Raw Socket Diagnostic ──
    private fun testRawSocket() {
        appendChat("RAW: Testing TCP to 100.82.28.24:5555...")
        Thread {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress("100.82.28.24", 5555), 5000)
                val req = "POST /api/health HTTP/1.1\r\nHost: 100.82.28.24\r\nContent-Length: 5\r\n\r\nhello"
                socket.getOutputStream().write(req.toByteArray())
                socket.getOutputStream().flush()
                val response = socket.getInputStream().bufferedReader().readLine()
                Log.d("RAW", "POST write OK, response=$response")
                runOnUiThread { appendChat("RAW SOCKET: OK — $response") }
                socket.close()
            } catch (e: Exception) {
                Log.e("RAW", "socket fail", e)
                runOnUiThread { appendChat("RAW SOCKET FAIL: ${e.javaClass.simpleName} — ${e.message}") }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        unregisterReceiver(audioReceiver)
        glassesService?.close()
    }

    data class RelayMessage(val id: String, val from: String, val text: String)
    data class TranscriptionResult(val text: String?, val error: String?)
}
