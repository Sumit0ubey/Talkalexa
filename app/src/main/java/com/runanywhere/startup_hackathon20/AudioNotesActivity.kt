package com.runanywhere.startup_hackathon20

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.runanywhere.sdk.public.RunAnywhere
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.*
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipInputStream

class AudioNotesActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AudioNotes"
        private const val MAX_DURATION_MS = 5 * 60 * 1000L
        private const val SAMPLE_RATE = 16000f
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }

    private lateinit var btnBack: ImageView
    private lateinit var spinnerModel: Spinner
    private lateinit var btnSelectFile: Button
    private lateinit var selectedFileInfo: LinearLayout
    private lateinit var tvFileName: TextView
    private lateinit var tvFileDuration: TextView
    private lateinit var processingSection: LinearLayout
    private lateinit var tvProcessingStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressDetail: TextView
    private lateinit var resultsSection: LinearLayout
    private lateinit var tvKeyPoints: TextView
    private lateinit var tvTerminologies: TextView
    private lateinit var btnDownloadNotes: Button
    private lateinit var btnAnalyze: Button

    private var selectedAudioUri: Uri? = null
    private var extractedText = ""
    private var keyPointsText = ""
    private var terminologiesText = ""
    private var model: Model? = null
    private var isModelReady = false
    private var isDownloading = false
    private var selectedModelId: String? = null
    private var isLoadingAIModel = false

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_audio_notes)
            initViews()
            setupListeners()
            loadModel()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error: $e", e)
            Toast.makeText(this, "Error loading Audio Notes: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    


    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        spinnerModel = findViewById(R.id.spinnerModel)
        btnSelectFile = findViewById(R.id.btnSelectFile)
        selectedFileInfo = findViewById(R.id.selectedFileInfo)
        tvFileName = findViewById(R.id.tvFileName)
        tvFileDuration = findViewById(R.id.tvFileDuration)
        processingSection = findViewById(R.id.processingSection)
        tvProcessingStatus = findViewById(R.id.tvProcessingStatus)
        progressBar = findViewById(R.id.progressBar)
        tvProgressDetail = findViewById(R.id.tvProgressDetail)
        resultsSection = findViewById(R.id.resultsSection)
        tvKeyPoints = findViewById(R.id.tvKeyPoints)
        tvTerminologies = findViewById(R.id.tvTerminologies)
        btnDownloadNotes = findViewById(R.id.btnDownloadNotes)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        
        setupModelSpinner()
    }
    
    private fun setupModelSpinner() {
        val modelNames = listOf(
            "Select AI Model",
            "Llama 3.2 1B",
            "Phi 3.5 Mini",
            "Qwen 2.5 0.5B",
            "SmolLM2 135M"
        )
        
        // Map display names to actual model IDs
        val modelIds = mapOf(
            "Llama 3.2 1B" to "Llama-3.2-1B-Instruct-Q4_K_M",
            "Phi 3.5 Mini" to "Phi-3.5-mini-instruct-Q4_K_M",
            "Qwen 2.5 0.5B" to "Qwen2.5-0.5B-Instruct-Q4_K_M",
            "SmolLM2 135M" to "SmolLM2-135M-Instruct-Q4_K_M"
        )
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerModel.adapter = adapter
        
        spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    val displayName = modelNames[position]
                    val actualModelId = modelIds[displayName]
                    if (actualModelId != null) {
                        loadAIModel(actualModelId, displayName)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun loadAIModel(modelId: String, displayName: String) {
        if (isLoadingAIModel) {
            Toast.makeText(this, "Already loading model...", Toast.LENGTH_SHORT).show()
            return
        }
        
        isLoadingAIModel = true
        selectedModelId = modelId
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AudioNotesActivity, "⏳ Loading $displayName...", Toast.LENGTH_SHORT).show()
                }
                
                Log.d(TAG, "Loading model: $modelId")
                
                // Actually load the model
                RunAnywhere.loadModel(modelId)
                
                // Wait a bit for model to initialize
                delay(1000)
                
                // Test if model loaded with a simple prompt
                val testPrompt = "Hi"
                var loaded = false
                var responseReceived = false
                
                val opts = com.runanywhere.sdk.models.RunAnywhereGenerationOptions(
                    maxTokens = 10, 
                    temperature = 0.7f, 
                    topP = 0.9f,
                    enableRealTimeTracking = false, 
                    stopSequences = emptyList(),
                    streamingEnabled = true, 
                    preferredExecutionTarget = null,
                    structuredOutput = null, 
                    systemPrompt = null, 
                    topK = 40,
                    repetitionPenalty = 1.1f, 
                    frequencyPenalty = null,
                    presencePenalty = null, 
                    seed = null, 
                    contextLength = 512
                )
                
                try {
                    withTimeoutOrNull(5000) {
                        RunAnywhere.generateStream(testPrompt, opts).collect { chunk ->
                            responseReceived = true
                            if (chunk.isNotEmpty()) {
                                loaded = true
                                Log.d(TAG, "Model test response: $chunk")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Model test failed: $e")
                }
                
                withContext(Dispatchers.Main) {
                    if (loaded) {
                        Toast.makeText(this@AudioNotesActivity, "✅ $displayName ready!", Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "Model $modelId loaded successfully")
                    } else if (responseReceived) {
                        Toast.makeText(this@AudioNotesActivity, "⚠️ $displayName loaded but slow", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@AudioNotesActivity, "❌ $displayName failed to respond", Toast.LENGTH_LONG).show()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Model load error: $e", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AudioNotesActivity, "❌ Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isLoadingAIModel = false
            }
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnSelectFile.setOnClickListener { picker.launch("audio/*") }
        btnAnalyze.setOnClickListener { analyze() }
        btnDownloadNotes.setOnClickListener { save() }
    }

    private fun loadModel() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dir = File(filesDir, "vosk-model")
            
            if (dir.exists() && File(dir, "am/final.mdl").exists()) {
                try {
                    model = Model(dir.absolutePath)
                    isModelReady = true
                    Log.d(TAG, "Model loaded")
                } catch (e: Exception) {
                    Log.e(TAG, "Load failed: $e")
                }
            } else {
                withContext(Dispatchers.Main) {
                    processingSection.visibility = View.VISIBLE
                    tvProcessingStatus.text = "First time setup"
                    tvProgressDetail.text = "Downloading speech model (50MB)..."
                    progressBar.isIndeterminate = true
                }
                downloadModel(dir)
            }
        }
    }

    private suspend fun downloadModel(dir: File) {
        if (isDownloading) return
        isDownloading = true
        
        withContext(Dispatchers.IO) {
            try {
                val zip = File(cacheDir, "model.zip")
                
                withContext(Dispatchers.Main) { tvProgressDetail.text = "Downloading..." }
                
                URL(MODEL_URL).openStream().use { input ->
                    FileOutputStream(zip).use { output ->
                        input.copyTo(output)
                    }
                }
                
                withContext(Dispatchers.Main) { tvProgressDetail.text = "Extracting..." }
                
                dir.mkdirs()
                ZipInputStream(zip.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val file = File(filesDir, entry.name.replaceFirst("vosk-model-small-en-us-0.15", "vosk-model"))
                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { fos -> zis.copyTo(fos) }
                        }
                        entry = zis.nextEntry
                    }
                }
                
                zip.delete()
                
                model = Model(dir.absolutePath)
                isModelReady = true
                
                withContext(Dispatchers.Main) {
                    processingSection.visibility = View.GONE
                    progressBar.isIndeterminate = false
                    Toast.makeText(this@AudioNotesActivity, "Ready! ✓", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: $e")
                withContext(Dispatchers.Main) {
                    processingSection.visibility = View.GONE
                    progressBar.isIndeterminate = false
                    Toast.makeText(this@AudioNotesActivity, "Download failed. Check internet.", Toast.LENGTH_LONG).show()
                }
            } finally {
                isDownloading = false
            }
        }
    }

    private fun handleFile(uri: Uri) {
        try {
            val name = getName(uri)
            val dur = getDur(uri)
            
            if (dur > MAX_DURATION_MS) {
                Toast.makeText(this, "Max 5 min!", Toast.LENGTH_SHORT).show()
                return
            }
            
            selectedAudioUri = uri
            tvFileName.text = name
            tvFileDuration.text = formatDur(dur)
            selectedFileInfo.visibility = View.VISIBLE
            btnAnalyze.isEnabled = true
            resultsSection.visibility = View.GONE
        } catch (e: Exception) {
            Toast.makeText(this, "Error: $e", Toast.LENGTH_SHORT).show()
        }
    }

    private fun analyze() {
        if (!isModelReady) {
            if (isDownloading) {
                Toast.makeText(this, "Downloading model... wait", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Starting download...", Toast.LENGTH_SHORT).show()
                loadModel()
            }
            return
        }
        
        selectedAudioUri?.let { uri ->
            lifecycleScope.launch {
                try {
                    show()
                    prog(5, "Decoding...")
                    
                    val text = transcribe(uri)
                    
                    if (text.isBlank()) {
                        err("No speech detected")
                        return@launch
                    }
                    
                    extractedText = text
                    Log.d(TAG, "Transcribed: ${text.take(100)}")
                    Log.d(TAG, "Starting AI analysis...")
                    
                    prog(60, "AI analyzing...")
                    aiAnalyze(text)
                    Log.d(TAG, "AI analysis completed")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error: $e", e)
                    err("Error: $e")
                }
            }
        }
    }

    private suspend fun transcribe(uri: Uri): String = withContext(Dispatchers.IO) {
        var rec: Recognizer? = null
        try {
            prog(10, "Extracting audio...")
            val pcm = decode(uri)
            Log.d(TAG, "PCM size: ${pcm.size} bytes")
            if (pcm.isEmpty()) {
                Log.e(TAG, "PCM decode failed - empty")
                return@withContext ""
            }
            
            prog(20, "Transcribing...")
            rec = Recognizer(model, SAMPLE_RATE)
            val result = StringBuilder()
            val chunk = 8000
            var off = 0
            
            while (off < pcm.size) {
                val end = minOf(off + chunk, pcm.size)
                val part = pcm.copyOfRange(off, end)
                
                if (rec.acceptWaveForm(part, part.size)) {
                    val partialResult = rec.result
                    Log.d(TAG, "Partial result: $partialResult")
                    parse(partialResult)?.let { 
                        result.append(it).append(" ")
                        Log.d(TAG, "Added text: $it")
                    }
                }
                
                off = end
                val p = 20 + (off * 35 / pcm.size)
                prog(p, "Transcribing ${off * 100 / pcm.size}%")
            }
            
            val finalResult = rec.finalResult
            Log.d(TAG, "Final result: $finalResult")
            parse(finalResult)?.let { 
                result.append(it)
                Log.d(TAG, "Final text: $it")
            }
            
            val transcription = result.toString().trim()
            Log.d(TAG, "Total transcription: $transcription")
            transcription
            
        } catch (e: Exception) {
            Log.e(TAG, "Transcribe error: $e", e)
            e.printStackTrace()
            ""
        } finally {
            rec?.close()
        }
    }

    private fun decode(uri: Uri): ByteArray {
        val ext = MediaExtractor()
        var dec: MediaCodec? = null
        
        try {
            ext.setDataSource(this, uri, null)
            
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ext.trackCount) {
                val f = ext.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    idx = i
                    fmt = f
                    break
                }
            }
            if (idx < 0 || fmt == null) return ByteArray(0)
            
            ext.selectTrack(idx)
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(fmt, null, null, 0)
            dec.start()
            
            val out = mutableListOf<Byte>()
            val info = MediaCodec.BufferInfo()
            var eos = false
            
            while (!eos) {
                val inIdx = dec.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    val buf = dec.getInputBuffer(inIdx)!!
                    val sz = ext.readSampleData(buf, 0)
                    if (sz < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    } else {
                        dec.queueInputBuffer(inIdx, 0, sz, ext.sampleTime, 0)
                        ext.advance()
                    }
                }
                
                val outIdx = dec.dequeueOutputBuffer(info, 10000)
                if (outIdx >= 0) {
                    val buf = dec.getOutputBuffer(outIdx)!!
                    val data = ByteArray(info.size)
                    buf.get(data)
                    out.addAll(data.toList())
                    dec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
            }
            
            return resample(out.toByteArray(), fmt)
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: $e", e)
            return ByteArray(0)
        } finally {
            dec?.stop()
            dec?.release()
            ext.release()
        }
    }

    private fun resample(data: ByteArray, fmt: MediaFormat): ByteArray {
        try {
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            
            val shorts = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = ShortArray(shorts.remaining())
            shorts.get(samples)
            
            val mono = if (ch > 1) ShortArray(samples.size / ch) { i ->
                var sum = 0
                for (c in 0 until ch) sum += samples[i * ch + c]
                (sum / ch).toShort()
            } else samples
            
            val ratio = SAMPLE_RATE / rate
            val out = ShortArray((mono.size * ratio).toInt()) { i ->
                mono[(i / ratio).toInt().coerceIn(0, mono.size - 1)]
            }
            
            val buf = ByteBuffer.allocate(out.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            out.forEach { buf.putShort(it) }
            return buf.array()
        } catch (e: Exception) {
            return data
        }
    }

    private fun parse(json: String) = try { 
        JSONObject(json).optString("text").takeIf { it.isNotBlank() } 
    } catch (e: Exception) { null }

    private suspend fun aiAnalyze(text: String) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "=== AI ANALYZE START ===")
            Log.d(TAG, "AI analyzing text length: ${text.length}")
            
            // Limit text to 800 chars for better context
            val limitedText = if (text.length > 800) {
                Log.d(TAG, "Text too long, truncating to 800 chars")
                text.take(800)
            } else text
            
            val prompt = """Audio transcript: "$limitedText"

Write a concise 2-3 sentence summary:"""
            
            Log.d(TAG, "Prompt length: ${prompt.length}")
            
            val resp = StringBuilder()
            
            Log.d(TAG, "Creating generation options...")
            val opts = com.runanywhere.sdk.models.RunAnywhereGenerationOptions(
                maxTokens = 600, temperature = 0.4f, topP = 0.9f,
                enableRealTimeTracking = false, stopSequences = emptyList(),
                streamingEnabled = true, preferredExecutionTarget = null,
                structuredOutput = null, systemPrompt = null, topK = 40,
                repetitionPenalty = 1.1f, frequencyPenalty = null,
                presencePenalty = null, seed = null, contextLength = 1024
            )
            
            Log.d(TAG, "Calling RunAnywhere.generateStream...")
            try {
                RunAnywhere.generateStream(prompt, opts).collect { 
                    resp.append(it)
                    Log.d(TAG, "Stream chunk received: ${it.take(50)}")
                    prog(60 + resp.length / 10, "Generating...")
                }
                Log.d(TAG, "Stream collection completed")
            } catch (e: Exception) {
                Log.e(TAG, "=== LLM STREAM ERROR ===")
                Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
                Log.e(TAG, "Error message: ${e.message}")
                Log.e(TAG, "Stack trace:", e)
                withContext(Dispatchers.Main) { err("Load AI model first from Home!") }
                return@withContext
            }
            
            val response = resp.toString()
            Log.d(TAG, "AI response length: ${response.length}")
            Log.d(TAG, "AI response: $response")
            
            if (response.isBlank()) {
                Log.e(TAG, "Empty AI response! Model might not be loaded properly")
                withContext(Dispatchers.Main) { 
                    err("AI returned empty response. Try:\n1. Load model from Home\n2. Use shorter audio") 
                }
                return@withContext
            }
            
            Log.d(TAG, "Parsing AI response...")
            parseResp(response, limitedText)
            
            Log.d(TAG, "Showing results...")
            withContext(Dispatchers.Main) { showRes() }
            Log.d(TAG, "=== AI ANALYZE END ===")
            
        } catch (e: Exception) {
            Log.e(TAG, "=== AI ANALYZE ERROR ===")
            Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Error message: ${e.message}")
            Log.e(TAG, "Stack trace:", e)
            e.printStackTrace()
            withContext(Dispatchers.Main) { err("AI failed: ${e.message}") }
        }
    }

    private fun parseResp(r: String, originalText: String) {
        Log.d(TAG, "Parsing response...")
        
        // Clean up the response - remove common AI prefixes
        var cleanResponse = r.trim()
            .removePrefix("Here is a summary")
            .removePrefix("Here's a summary")
            .removePrefix("Summary:")
            .removePrefix("summary:")
            .removePrefix(":")
            .removePrefix("of the audio lecture in")
            .removePrefix("in 3-4 clear sentences:")
            .trim()
        
        // Remove any leading colons or dashes
        cleanResponse = cleanResponse.trimStart(':', '-', ' ')
        
        Log.d(TAG, "Clean response: $cleanResponse")
        
        if (cleanResponse.isNotEmpty() && cleanResponse.length > 30) {
            // Split into sentences and clean
            val sentences = cleanResponse.split(Regex("[.!?]"))
                .map { it.trim() }
                .filter { it.length > 25 && !it.contains("summary", ignoreCase = true) }
                .distinct() // Remove duplicates
            
            Log.d(TAG, "Found ${sentences.size} unique sentences")
            
            if (sentences.isNotEmpty()) {
                // Take first 2-3 unique sentences
                keyPointsText = sentences.take(3)
                    .joinToString(". ") + "."
            } else {
                // Use cleaned response as-is
                keyPointsText = cleanResponse
            }
        } else {
            // Fallback: create summary from transcription
            Log.w(TAG, "AI response too short, using transcription")
            val words = originalText.split(Regex("\\s+"))
            keyPointsText = words.take(40).joinToString(" ") + "..."
        }
        
        // Extract technical terms - look for capitalized words and acronyms
        val techWords = originalText.split(Regex("\\s+"))
            .filter { word -> 
                val clean = word.replace(Regex("[^a-zA-Z]"), "")
                clean.length > 3 && (
                    clean.matches(Regex("[A-Z]{2,}")) || // Acronyms like LLM, AI
                    (clean[0].isUpperCase() && clean.drop(1).any { it.isLowerCase() }) // Proper nouns
                )
            }
            .map { it.replace(Regex("[^a-zA-Z]"), "") }
            .distinct()
            .take(6)
        
        terminologiesText = if (techWords.isNotEmpty()) {
            techWords.joinToString("\n") { "• $it" }
        } else {
            "• No technical terms detected"
        }
        
        Log.d(TAG, "Summary: $keyPointsText")
        Log.d(TAG, "Terms: $terminologiesText")
    }

    private fun show() {
        try {
            // Fade out results, slide in processing
            if (resultsSection.visibility == View.VISIBLE) {
                val fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out)
                resultsSection.startAnimation(fadeOut)
            }
            
            processingSection.visibility = View.VISIBLE
            val slideIn = AnimationUtils.loadAnimation(this, R.anim.slide_in_bottom)
            processingSection.startAnimation(slideIn)
            
            resultsSection.visibility = View.GONE
            btnAnalyze.isEnabled = false
            btnSelectFile.isEnabled = false
            progressBar.isIndeterminate = false
        } catch (e: Exception) {
            Log.e(TAG, "Animation error in show(): $e")
            // Fallback without animation
            processingSection.visibility = View.VISIBLE
            resultsSection.visibility = View.GONE
            btnAnalyze.isEnabled = false
            btnSelectFile.isEnabled = false
            progressBar.isIndeterminate = false
        }
    }

    private fun showRes() {
        try {
            // Fade out processing, scale in results
            val fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out)
            processingSection.startAnimation(fadeOut)
            processingSection.visibility = View.GONE
            
            resultsSection.visibility = View.VISIBLE
            val scaleIn = AnimationUtils.loadAnimation(this, R.anim.scale_in)
            resultsSection.startAnimation(scaleIn)
            
            btnAnalyze.isEnabled = true
            btnSelectFile.isEnabled = true
            tvKeyPoints.text = keyPointsText
            tvTerminologies.text = terminologiesText
        } catch (e: Exception) {
            Log.e(TAG, "Animation error in showRes(): $e")
            // Fallback without animation
            processingSection.visibility = View.GONE
            resultsSection.visibility = View.VISIBLE
            btnAnalyze.isEnabled = true
            btnSelectFile.isEnabled = true
            tvKeyPoints.text = keyPointsText
            tvTerminologies.text = terminologiesText
        }
    }

    private fun err(msg: String) {
        runOnUiThread {
            processingSection.visibility = View.GONE
            btnAnalyze.isEnabled = true
            btnSelectFile.isEnabled = true
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun prog(p: Int, t: String) {
        runOnUiThread {
            progressBar.progress = p
            tvProgressDetail.text = t
        }
    }

    private fun getName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && idx >= 0) return it.getString(idx)
        }
        return "audio"
    }

    private fun getDur(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } finally { r.release() }
    }

    private fun formatDur(ms: Long) = "${ms/60000}:${String.format("%02d",(ms/1000)%60)}"

    private fun save() {
        try {
            val name = tvFileName.text.toString().substringBeforeLast(".") + "_notes.txt"
            val content = "AUDIO NOTES\n\nKEY POINTS\n$keyPointsText\n\nTERMINOLOGIES\n$terminologiesText"
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name).writeText(content)
            Toast.makeText(this, "Saved: $name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        model?.close()
    }
}
