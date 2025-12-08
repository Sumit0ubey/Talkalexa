package com.runanywhere.startup_hackathon20

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.listAvailableModels
import com.runanywhere.sdk.models.ModelInfo
import com.runanywhere.startup_hackathon20.data.repository.ChatSessionRepository
import com.runanywhere.startup_hackathon20.data.repository.MessageRepository
import com.runanywhere.startup_hackathon20.domain.model.ChatMessage
import com.runanywhere.startup_hackathon20.domain.model.ChatSession
import com.runanywhere.startup_hackathon20.domain.model.MessageAttachment
import com.runanywhere.startup_hackathon20.domain.model.UserSettings
import com.runanywhere.startup_hackathon20.domain.service.AssistantController
import com.runanywhere.startup_hackathon20.domain.service.AssistantControllerImpl
import com.runanywhere.startup_hackathon20.domain.service.DeviceTier
import com.runanywhere.startup_hackathon20.domain.service.ModelLoadingState
import com.runanywhere.startup_hackathon20.domain.service.ResourceSnapshot
import com.runanywhere.startup_hackathon20.domain.service.TTSService
import com.runanywhere.startup_hackathon20.domain.service.TTSState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the chat interface.
 * Supports multiple chat sessions and user personalization.
 */
class ChatViewModel(
    private val assistantController: AssistantController? = null,
    private val messageRepository: MessageRepository? = null,
    private val ttsService: TTSService? = null
) : ViewModel() {

    // Session repository
    private val sessionRepository: ChatSessionRepository by lazy {
        ChatSessionRepository(MyApplication.instance)
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    private val _currentModelId = MutableStateFlow<String?>(null)
    val currentModelId: StateFlow<String?> = _currentModelId

    private val _statusMessage = MutableStateFlow<String>("Initializing...")
    val statusMessage: StateFlow<String> = _statusMessage
    
    private val _ttsState = MutableStateFlow<TTSState>(TTSState.Idle)
    val ttsState: StateFlow<TTSState> = _ttsState.asStateFlow()
    
    private val _isTtsEnabled = MutableStateFlow(true)
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()
    
    // Chat sessions
    private val _chatSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val chatSessions: StateFlow<List<ChatSession>> = _chatSessions.asStateFlow()
    
    private var currentSessionId: String? = null
    
    // User settings
    private val _userSettings = MutableStateFlow(UserSettings())
    val userSettings: StateFlow<UserSettings> = _userSettings.asStateFlow()
    
    // ===== Adaptive Model Management =====
    
    // Device resource info for UI display
    private val _resourceSnapshot = MutableStateFlow<ResourceSnapshot?>(null)
    val resourceSnapshot: StateFlow<ResourceSnapshot?> = _resourceSnapshot.asStateFlow()
    
    // Device tier for UI display
    private val _deviceTier = MutableStateFlow<DeviceTier?>(null)
    val deviceTier: StateFlow<DeviceTier?> = _deviceTier.asStateFlow()
    
    // Model loading state from AdaptiveModelManager
    private val _modelLoadingState = MutableStateFlow<ModelLoadingState>(ModelLoadingState.Idle)
    val modelLoadingState: StateFlow<ModelLoadingState> = _modelLoadingState.asStateFlow()
    
    // Model recommendation message
    private val _modelRecommendation = MutableStateFlow<String>("")
    val modelRecommendation: StateFlow<String> = _modelRecommendation.asStateFlow()

    init {
        loadAvailableModels()
        loadUserSettings()
        loadChatSessions()
        observeTtsState()
        observeAdaptiveModelManager()

        // Create a session on init if none exists
        ensureSessionExists()
    }

    /**
     * Ensures a session exists for saving messages.
     * Creates a new session if currentSessionId is null.
     */
    private fun ensureSessionExists() {
        if (currentSessionId == null) {
            val newSession = com.runanywhere.startup_hackathon20.domain.model.ChatSession()
            currentSessionId = newSession.id
            sessionRepository.setCurrentSessionId(newSession.id)
            android.util.Log.d("ChatViewModel", "Created new session: ${newSession.id}")
        }
    }
    
    // ===== Session Management =====
    
    fun startNewChat() {
        // Save current session first
        saveCurrentSession()
        
        // Create new session
        val newSession = ChatSession()
        currentSessionId = newSession.id
        _messages.value = emptyList()
        
        // DON'T save empty session - only save when first message is sent
        sessionRepository.setCurrentSessionId(newSession.id)
        loadChatSessions()
    }
    
    fun loadSession(sessionId: String) {
        saveCurrentSession()
        
        val session = sessionRepository.getSession(sessionId)
        if (session != null) {
            currentSessionId = session.id
            _messages.value = session.messages
            sessionRepository.setCurrentSessionId(sessionId)
        }
    }
    
    fun deleteSession(sessionId: String) {
        sessionRepository.deleteSession(sessionId)
        if (currentSessionId == sessionId) {
            startNewChat()
        }
        loadChatSessions()
    }
    
    fun clearAllSessions() {
        sessionRepository.clearAllSessions()
        startNewChat()
        loadChatSessions()
    }
    
    private fun saveCurrentSession() {
        val sessionId = currentSessionId ?: return
        val messages = _messages.value
        
        // Don't save empty sessions
        if (messages.isEmpty()) {
            // Delete empty session if it exists
            sessionRepository.deleteSession(sessionId)
            return
        }
        
        val existingSession = sessionRepository.getSession(sessionId)
        val title = existingSession?.title ?: messages.firstOrNull { it.isUser }?.text?.take(30) ?: "New Chat"
        
        val session = ChatSession(
            id = sessionId,
            title = title,
            messages = messages,
            createdAt = existingSession?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        sessionRepository.saveSession(session)
    }
    
    private fun loadChatSessions() {
        _chatSessions.value = sessionRepository.getAllSessions()
    }
    
    // ===== User Settings =====
    
    private fun loadUserSettings() {
        _userSettings.value = sessionRepository.getUserSettings()
        _isTtsEnabled.value = _userSettings.value.ttsEnabled
    }
    
    fun saveUserSettings(settings: UserSettings) {
        sessionRepository.saveUserSettings(settings)
        _userSettings.value = settings
        _isTtsEnabled.value = settings.ttsEnabled
    }

    
    /**
     * No longer restores messages on init - each app open starts fresh.
     * History is available in the History tab.
     */
    private fun restoreMessages() {
        // Don't restore - start fresh each time
        // User can access history from History tab
    }
    
    /**
     * Observes TTS state changes for UI updates.
     */
    private fun observeTtsState() {
        viewModelScope.launch {
            ttsService?.getStateFlow()?.collect { state ->
                _ttsState.value = state
            }
        }
    }

    /**
     * Observes AdaptiveModelManager state for automatic model management.
     */
    private fun observeAdaptiveModelManager() {
        viewModelScope.launch {
            try {
                val adaptiveManager = MyApplication.instance.adaptiveModelManager

                // Observe loading state
                launch {
                    adaptiveManager.loadingState.collect { state ->
                        _modelLoadingState.value = state

                        // Update status message based on state
                        _statusMessage.value = when (state) {
                            is ModelLoadingState.Idle -> "Ready"
                            is ModelLoadingState.Checking -> "Checking device resources..."
                            is ModelLoadingState.Downloading -> "Downloading ${state.modelName}: ${(state.progress * 100).toInt()}%"
                            is ModelLoadingState.Loading -> "Loading ${state.modelName}..."
                            is ModelLoadingState.Ready -> "✓ ${state.modelName} ready"
                            is ModelLoadingState.Error -> "Error: ${state.message}"
                        }

                        // Update download progress for UI
                        _downloadProgress.value = when (state) {
                            is ModelLoadingState.Downloading -> state.progress
                            else -> null
                        }
                    }
                }

                // Observe current model ID
                launch {
                    adaptiveManager.currentModelId.collect { modelId ->
                        if (modelId != null) {
                            _currentModelId.value = modelId
                            // Update AssistantController model state
                            (assistantController as? AssistantControllerImpl)?.setModelLoaded(true)
                        }
                    }
                }

                // Observe resource snapshot
                launch {
                    adaptiveManager.resourceSnapshot.collect { snapshot ->
                        _resourceSnapshot.value = snapshot
                        _deviceTier.value = snapshot?.deviceTier
                    }
                }

                // Get initial recommendation
                _modelRecommendation.value = adaptiveManager.getModelRecommendation()

            } catch (e: Exception) {
                android.util.Log.e(
                    "ChatViewModel",
                    "Error observing adaptive manager: ${e.message}"
                )
            }
        }
    }

    /**
     * Gets device resource info as a formatted string for UI display.
     */
    fun getResourceInfoText(): String {
        val snapshot = _resourceSnapshot.value ?: return "Loading device info..."

        return buildString {
            append("Device: ${snapshot.deviceTier} tier\n")
            append("RAM: ${snapshot.availableRamMB}MB / ${snapshot.totalRamMB}MB\n")
            append("Battery: ${snapshot.batteryPercent}%")
            if (snapshot.isCharging) append(" (Charging)")
            append("\n")
            append("Storage: ${snapshot.availableStorageMB / 1024}GB free")
        }
    }

    /**
     * Checks if a heavier model can be loaded for better quality.
     */
    fun checkModelUpgrade(): Pair<Boolean, String?> {
        return try {
            MyApplication.instance.adaptiveModelManager.canUpgradeModel()
        } catch (e: Exception) {
            Pair(false, null)
        }
    }

    private fun loadAvailableModels() {
        viewModelScope.launch {
            try {
                val models = listAvailableModels()
                _availableModels.value = models
                _statusMessage.value = "Ready - Please download and load a model"
            } catch (e: Exception) {
                _statusMessage.value = "Error loading models: ${e.message}"
            }
        }
    }

    // Track last progress to prevent flicker
    private var lastDownloadProgress = 0f

    // 🔧 FIX: Track last displayed percentage to prevent UI glitching
    private var lastDisplayedPercent = -1

    // Track if we've requested battery exemption (one-time)
    private var hasRequestedBatteryExemption = false

    fun downloadModel(modelId: String) {
        viewModelScope.launch {
            try {
                android.util.Log.d("ChatViewModel", "Starting download for model: $modelId")
                _statusMessage.value = "Downloading model..."
                lastDownloadProgress = 0f
                lastDisplayedPercent = -1

                RunAnywhere.downloadModel(modelId).collect { progress ->
                    // Only update if progress increased (prevents flicker)
                    if (progress >= lastDownloadProgress) {
                        lastDownloadProgress = progress
                        _downloadProgress.value = progress

                        // 🔧 FIX: Only update status text every 2% to prevent glitching
                        val currentPercent = (progress * 100).toInt()
                        if (currentPercent != lastDisplayedPercent &&
                            (currentPercent - lastDisplayedPercent >= 2 || currentPercent >= 99)
                        ) {
                            lastDisplayedPercent = currentPercent
                            // Use fixed-width format to prevent layout shifts
                            val percentText = String.format("%3d", currentPercent)
                            _statusMessage.value = "Downloading: $percentText%"
                            android.util.Log.d(
                                "ChatViewModel",
                                "Download progress: $currentPercent%"
                            )
                        }
                    }
                }
                _downloadProgress.value = null
                lastDownloadProgress = 0f
                lastDisplayedPercent = -1
                _statusMessage.value = "Download complete! Please load the model."
                // Refresh models list to show downloaded status
                loadAvailableModels()
                android.util.Log.d("ChatViewModel", "Download complete!")
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Download failed: ${e.message}", e)
                _statusMessage.value = "Download failed: ${e.message}"
                _downloadProgress.value = null
                lastDownloadProgress = 0f
                lastDisplayedPercent = -1
            }
        }
    }

    fun loadModel(modelId: String) {
        viewModelScope.launch {
            try {
                // Get model name for display
                val modelInfo = _availableModels.value.find { it.id == modelId }
                val modelName = modelInfo?.name ?: "Model"
                val modelKey = findModelKeyByName(modelName)

                // Log resource check (but don't block - user chose to load)
                val resourceManager = MyApplication.instance.deviceResourceManager
                if (modelKey != null) {
                    val (canLoad, reason) = resourceManager.canLoadModel(modelKey)
                    android.util.Log.d(
                        "ChatViewModel",
                        "Resource check for $modelKey: canLoad=$canLoad, reason=$reason"
                    )

                    // Only show warning, don't block loading
                    if (reason.startsWith("Warning")) {
                        android.util.Log.w("ChatViewModel", reason)
                    }
                }

                _statusMessage.value = "Loading $modelName..."

                // Retry logic - sometimes first load fails, second succeeds
                var success = false
                var lastError: Exception? = null

                for (attempt in 1..3) {
                    try {
                        android.util.Log.d("ChatViewModel", "Load attempt $attempt for $modelName")
                        success = RunAnywhere.loadModel(modelId)
                        if (success) {
                            android.util.Log.d(
                                "ChatViewModel",
                                "Load succeeded on attempt $attempt"
                            )
                            break
                        } else {
                            android.util.Log.w(
                                "ChatViewModel",
                                "Load returned false on attempt $attempt"
                            )
                            // Small delay before retry
                            if (attempt < 3) {
                                kotlinx.coroutines.delay(500)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(
                            "ChatViewModel",
                            "Load error on attempt $attempt: ${e.message}"
                        )
                        lastError = e
                        if (attempt < 3) {
                            kotlinx.coroutines.delay(500)
                        }
                    }
                }

                if (success) {
                    _currentModelId.value = modelId

                    // Check if it's a heavy model and warn user
                    val isHeavyModel = modelName.contains("3B") ||
                            modelName.contains("7B") ||
                            modelName.contains("Mistral") ||
                            modelName.contains("Phi-3")

                    if (isHeavyModel) {
                        _statusMessage.value =
                            "⚠️ $modelName loaded - SLOW responses expected (30-120s)"
                        android.util.Log.w(
                            "ChatViewModel",
                            "Heavy model loaded: $modelName - will be slow!"
                        )
                    } else {
                        _statusMessage.value = "✓ $modelName ready to chat!"
                    }

                    // Update AssistantController model state
                    (assistantController as? AssistantControllerImpl)?.setModelLoaded(true)

                    // Remember this model in adaptive manager
                    if (modelKey != null) {
                        MyApplication.instance.adaptiveModelManager.setPreferredModel(modelKey)
                    }
                } else {
                    val errorMsg = lastError?.message ?: "Failed to load model after 3 attempts"
                    _statusMessage.value = "Failed: $errorMsg"
                    android.util.Log.e("ChatViewModel", "Model load failed after all attempts")
                }
            } catch (e: Exception) {
                _statusMessage.value = "Error loading model: ${e.message}"
                android.util.Log.e("ChatViewModel", "Model load exception: ${e.message}", e)
            }
        }
    }

    /**
     * Finds the model key (e.g., "Qwen-0.5B") from display name.
     */
    private fun findModelKeyByName(displayName: String): String? {
        return when {
            displayName.contains("SmolLM2 360M") -> "SmolLM2-360M"
            displayName.contains("Qwen 2.5 0.5B") -> "Qwen-0.5B"
            displayName.contains("Llama 3.2 1B Q4") -> "Llama-1B-Q4"
            displayName.contains("Llama 3.2 1B Q6") -> "Llama-1B-Q6"
            displayName.contains("Qwen 2.5 1.5B") -> "Qwen-1.5B"
            displayName.contains("Qwen 2.5 3B") -> "Qwen-3B"
            displayName.contains("Llama 3.2 3B") -> "Llama-3B"
            displayName.contains("Phi-3 Mini") -> "Phi-3-Mini"
            displayName.contains("Mistral 7B") -> "Mistral-7B"
            else -> null
        }
    }

    /**
     * Sends a message using the AssistantController for two-speed routing.
     * Now uses AI-powered classification for better reminder detection.
     * Requirement 2.1: WHEN the user submits a text or voice query,
     * THE Translexa app SHALL send the query to the on-device LLM for processing
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // Add user message
        val userMessage = ChatMessage(text = text, isUser = true)
        _messages.value += userMessage
        saveMessage(userMessage)

        viewModelScope.launch {
            _isLoading.value = true

            try {
                val classifier = MyApplication.instance.intentClassifier

                // First do quick sync classification
                val quickResult = classifier.classify(text)
                android.util.Log.d(
                    "ChatViewModel",
                    "Quick classify: ${quickResult.type}, confidence: ${quickResult.confidence}"
                )

                // Determine if we need AI classification for reminders
                val needsAIClassification = classifier.mightBeReminder(text) &&
                        (quickResult.type != com.runanywhere.startup_hackathon20.domain.model.IntentType.CREATE_REMINDER ||
                                quickResult.confidence < 0.85f)

                // Use AI classification if:
                // 1. Might be a reminder and quick classification isn't confident
                // 2. Model is loaded (can use AI)
                val classification = if (needsAIClassification && _currentModelId.value != null) {
                    android.util.Log.d(
                        "ChatViewModel",
                        "Using AI classification for potential reminder"
                    )
                    classifier.classifyWithAI(text)
                } else {
                    quickResult
                }

                android.util.Log.d(
                    "ChatViewModel",
                    "Final classification: ${classification.type}, Params: ${classification.extractedParams}"
                )

                if (classification.type.isInstantAction()) {
                    // FAST PATH: Execute instantly without LLM
                    android.util.Log.d("ChatViewModel", "FAST PATH: Executing instant action")
                    val executor = MyApplication.instance.instantActionExecutor
                    val response = executor.execute(classification)
                    android.util.Log.d("ChatViewModel", "Action result: ${response.text}")
                    val actionMessage = ChatMessage(text = response.text, isUser = false)
                    _messages.value += actionMessage
                    saveMessage(actionMessage)

                    // Speak response if TTS enabled
                    if (_isTtsEnabled.value && response.shouldSpeak) {
                        speakResponse(response.text)
                    }
                } else {
                    // SLOW PATH: Use LLM for knowledge queries
                    android.util.Log.d("ChatViewModel", "SLOW PATH: Using LLM")
                    processWithDirectLLM(text)
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Error processing message", e)
                val errorMessage = ChatMessage(text = "Error: ${e.message}", isUser = false)
                _messages.value += errorMessage
                saveMessage(errorMessage)
            }

            _isLoading.value = false
        }
    }
    
    /**
     * Sends a message with an attachment (image, PDF, document).
     * The attachment's extracted text is included as context for the LLM.
     */
    fun sendMessageWithAttachment(text: String, attachment: MessageAttachment) {
        if (text.isBlank() && attachment.extractedText.isNullOrBlank()) return

        // Add user message with attachment
        val userMessage = ChatMessage(
            text = text,
            isUser = true,
            attachment = attachment
        )
        _messages.value += userMessage
        saveMessage(userMessage)

        viewModelScope.launch {
            _isLoading.value = true

            try {
                // Build context that includes the attachment content
                val contextWithAttachment = buildAttachmentContext(text, attachment)

                android.util.Log.d(
                    "ChatViewModel",
                    "Processing message with attachment: ${attachment.fileName}"
                )

                // Use LLM to process the query with attachment context
                processWithDirectLLMAndContext(contextWithAttachment, text)

            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Error processing attachment message", e)
                val errorMessage =
                    ChatMessage(text = "Error processing attachment: ${e.message}", isUser = false)
                _messages.value += errorMessage
                saveMessage(errorMessage)
            }

            _isLoading.value = false
        }
    }

    /**
     * Builds context that includes attachment content for LLM processing.
     * Handles BOTH document images (OCR) and scene images (ML Kit labels)
     */
    private fun buildAttachmentContext(userQuery: String, attachment: MessageAttachment): String {
        val isImage =
            attachment.type == com.runanywhere.startup_hackathon20.domain.model.AttachmentType.IMAGE

        return if (isImage) {
            val raw = attachment.extractedText?.trim() ?: ""

            // Check what type of content we have
            val hasFaces = raw.contains("person(s)") || raw.contains("Personal Photo")
            val hasText = raw.contains("TEXT CONTENT:") || raw.length > 100

            when {
                hasFaces -> {
                    // Personal photo - report faces detected
                    val faceMatch = Regex("(\\d+) person").find(raw)
                    val count = faceMatch?.groupValues?.get(1) ?: "multiple"
                    "I analyzed a photo and detected $count people in it.\n\nUser question: $userQuery\n\nBased on my analysis, this photo contains $count"
                }

                hasText -> {
                    // Document with text - extract and pass OCR content
                    val textStart = raw.indexOf("TEXT CONTENT:")
                    val ocrText = if (textStart >= 0) {
                        raw.substring(textStart + 13).trim().take(800)
                    } else {
                        raw.take(800)
                    }
                    "I read the following text from a document:\n\n$ocrText\n\nUser question: $userQuery\n\nBased on the document text above,"
                }

                else -> {
                    // Generic - just pass what we have
                    "Image analysis result: $raw\n\nUser question: $userQuery\n\nBased on this,"
                }
            }
        } else {
            // Non-image document (PDF, etc.)
            val docInfo = attachment.extractedText?.take(1500) ?: "No content"

            """File: ${attachment.fileName}
Content: $docInfo

Question: $userQuery

Answer:"""
        }
    }

    /**
     * Processes a query with pre-built context (used for attachments/images).
     * Uses PerformanceBooster for maximum speed during inference.
     * UPDATED: Higher token limits for image analysis, especially for heavy models!
     */
    private suspend fun processWithDirectLLMAndContext(context: String, originalQuery: String) {
        if (_currentModelId.value == null) {
            _statusMessage.value = "Please load a model first"
            val errorMsg = ChatMessage(
                text = "Please load an AI model first to process your file.",
                isUser = false
            )
            _messages.value += errorMsg
            saveMessage(errorMsg)
            return
        }

        var assistantResponse = ""
        val startTime = System.currentTimeMillis()
        val isHeavy = isHeavyModel()

        // Add "thinking" placeholder
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val currentMessages = _messages.value.toMutableList()
            currentMessages.add(
                ChatMessage(
                    text = if (isHeavy) "🔍 Analyzing image with AI... (this may take a moment)" else "Analyzing your file...",
                    isUser = false,
                    isStreaming = true
                )
            )
            _messages.value = currentMessages
        }

        // Get performance booster
        val performanceBooster = MyApplication.instance.performanceBooster

        try {
            // 🚀 START PERFORMANCE BOOST - maximize CPU priority
            performanceBooster.startBoost()
            android.util.Log.d("ChatViewModel", "🚀 Performance boost STARTED (attachment)")

            // 🖼️ IMAGE ANALYSIS - reasonable tokens
            val imageAnalysisTokens = 300  // Balanced for speed

            android.util.Log.d("ChatViewModel", "🖼️ Image: $imageAnalysisTokens tokens")

            val smartOptions = com.runanywhere.sdk.models.RunAnywhereGenerationOptions(
                maxTokens = imageAnalysisTokens,
                temperature = 0.7f,
                topP = 0.95f,  // Higher for more diverse output
                enableRealTimeTracking = false,
                stopSequences = emptyList(),  // NO stop sequences!
                streamingEnabled = true,
                preferredExecutionTarget = null,
                structuredOutput = null,
                systemPrompt = null,
                topK = 50,  // Higher for more output
                repetitionPenalty = 1.0f,  // No penalty - let it generate freely
                frequencyPenalty = null,
                presencePenalty = null,
                seed = null,
                contextLength = 2048
            )

            android.util.Log.d(
                "ChatViewModel",
                "📊 Options: maxTokens=${smartOptions.maxTokens}, temp=${smartOptions.temperature}, topP=${smartOptions.topP}")

            // Log the FULL context being sent for debugging
            android.util.Log.d("ChatViewModel", "📝 Image context length: ${context.length} chars")
            android.util.Log.d("ChatViewModel", "📝 FULL CONTEXT START ===")
            android.util.Log.d("ChatViewModel", context.take(500))
            android.util.Log.d("ChatViewModel", "📝 FULL CONTEXT END ===")

            var tokenCount = 0
            var totalChars = 0
            android.util.Log.d("ChatViewModel", "🔄 Starting generation stream...")

            try {
                RunAnywhere.generateStream(context, smartOptions).collect { token ->
                    assistantResponse += token
                    tokenCount++
                    totalChars += token.length

                    // Log every 10 tokens
                    if (tokenCount % 10 == 0) {
                        android.util.Log.d(
                            "ChatViewModel",
                            "📝 Token $tokenCount: total chars = $totalChars"
                        )
                    }

                    // Stream UI update for better UX
                    if (tokenCount % 5 == 0) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val currentMessages = _messages.value.toMutableList()
                            if (currentMessages.lastOrNull()?.isUser == false) {
                                currentMessages[currentMessages.lastIndex] = ChatMessage(
                                    text = assistantResponse.trim(),
                                    isUser = false,
                                    isStreaming = true
                                )
                                _messages.value = currentMessages
                            }
                        }
                    }
                }
                android.util.Log.d(
                    "ChatViewModel",
                    "✅ Stream completed: $tokenCount tokens, $totalChars chars"
                )
            } catch (streamError: Exception) {
                android.util.Log.e(
                    "ChatViewModel",
                    "❌ Stream error: ${streamError.message}",
                    streamError)
            }

            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.d(
                "ChatViewModel",
                "⚡ Image: ${elapsed}ms, ${assistantResponse.length} chars")

            if (assistantResponse.isNotBlank()) {
                showFinalMessage(assistantResponse.trim())
            } else {
                assistantResponse = "Could not analyze the image. Please try a different question."
                showFinalMessage(assistantResponse)
            }

        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "LLM error with attachment", e)
            assistantResponse =
                "Sorry, I couldn't analyze the file. Please try again or ask a different question."
            showFinalMessage(assistantResponse)
        } finally {
            // 🛑 STOP PERFORMANCE BOOST - release resources
            performanceBooster.stopBoost()
            android.util.Log.d("ChatViewModel", "🛑 Performance boost STOPPED (attachment)")
        }

        // Save final message
        val finalMessage = ChatMessage(text = assistantResponse.trim(), isUser = false)
        saveMessage(finalMessage)

        // Speak response if TTS enabled
        if (_isTtsEnabled.value && assistantResponse.isNotBlank()) {
            speakResponse(assistantResponse)
        }
    }
    
    /**
     * Processes query using AssistantController with streaming.
     * Routes instant actions to fast path, knowledge queries to LLM.
     */
    private suspend fun processWithAssistantController(text: String) {
        var assistantResponse = ""
        var lastMessage: ChatMessage? = null
        
        assistantController!!.processQueryStream(text).collect { response ->
            assistantResponse = response.text
            
            // Update assistant message in real-time
            val currentMessages = _messages.value.toMutableList()
            if (currentMessages.lastOrNull()?.isUser == false) {
                lastMessage = ChatMessage(text = assistantResponse, isUser = false)
                currentMessages[currentMessages.lastIndex] = lastMessage!!
            } else {
                lastMessage = ChatMessage(text = assistantResponse, isUser = false)
                currentMessages.add(lastMessage!!)
            }
            _messages.value = currentMessages
            
            // Speak final response if TTS enabled and shouldSpeak is true
            if (response.shouldSpeak && _isTtsEnabled.value) {
                speakResponse(response.text)
            }
        }
        
        // Save final message
        lastMessage?.let { saveMessage(it) }
    }

    
    /**
     * Builds optimized conversation context for faster LLM response.
     * Uses minimal context to reduce processing time.
     */
    private fun buildConversationContext(currentQuery: String, maxMessages: Int = 4): String {
        val recentMessages = _messages.value.takeLast(maxMessages)
        
        // For simple queries, skip context entirely for speed
        if (recentMessages.isEmpty() || currentQuery.split(" ").size <= 5) {
            return "Q: $currentQuery\nA:"
        }
        
        val contextBuilder = StringBuilder()
        
        // Minimal context format for speed
        for (msg in recentMessages) {
            val role = if (msg.isUser) "Q" else "A"
            // Shorter truncation for speed
            val text = if (msg.text.length > 100) msg.text.take(100) + "..." else msg.text
            contextBuilder.append("$role: $text\n")
        }
        
        contextBuilder.append("Q: $currentQuery\nA:")
        
        return contextBuilder.toString()
    }
    
    /**
     * Smart token selection based on query type
     * OPTIMIZED FOR SPEED - reasonable tokens for fast response
     */
    private fun getSmartMaxTokens(query: String): Int {
        val lowerQuery = query.lowercase()

        return when {
            // Detailed explanations
            lowerQuery.contains("explain") ||
                    lowerQuery.contains("describe") ||
                    lowerQuery.contains("how does") ||
                    lowerQuery.contains("why does") ||
                    lowerQuery.contains("tell me about") -> 250  // Good but fast

            // Short factual questions
            lowerQuery.startsWith("what is") ||
                    lowerQuery.startsWith("who is") ||
                    lowerQuery.startsWith("when") ||
                    lowerQuery.startsWith("where") -> 150  // Quick answer

            // Very short questions
            query.split(" ").size <= 3 -> 100  // Fast response

            // Default
            else -> 180  // Balanced
        }
    }

    /**
     * Checks if current model is "heavy" (3B+ parameters)
     * Heavy models are inherently slow on mobile
     */
    private fun isHeavyModel(): Boolean {
        val modelId = _currentModelId.value ?: return false
        val modelName = _availableModels.value.find { it.id == modelId }?.name ?: ""
        return modelName.contains("3B") ||
                modelName.contains("7B") ||
                modelName.contains("Mistral") ||
                modelName.contains("Phi-3")
    }

    /**
     * HEAVY MODELS (3B+) - MODERATE tokens
     * These are slow so we use fewer tokens BUT still complete answers
     * Use for REASONING tasks, not long generation
     */
    private fun getBalancedTokensForHeavyModel(query: String): Int {
        val wordCount = query.split(" ").size
        val lowerQuery = query.lowercase()

        return when {
            // Yes/No questions - Concise explanation
            lowerQuery.startsWith("is ") ||
                    lowerQuery.startsWith("are ") ||
                    lowerQuery.startsWith("can ") ||
                    lowerQuery.startsWith("do ") ||
                    lowerQuery.startsWith("does ") ||
                    lowerQuery.startsWith("will ") ||
                    lowerQuery.startsWith("has ") ||
                    lowerQuery.startsWith("have ") ||
                    lowerQuery.contains("yes or no") -> 120  // Concise but complete

            // Simple "who is" / "what is" - Key facts
            lowerQuery.startsWith("who is") ||
                    lowerQuery.startsWith("what is") && wordCount <= 6 -> 150  // Core info

            // When/Where questions - Brief context
            lowerQuery.startsWith("when") ||
                    lowerQuery.startsWith("where") -> 120  // Direct answer

            // Short questions (5 words or less)
            wordCount <= 5 -> 150  // Good response

            // Medium questions - Standard answer
            wordCount <= 10 -> 180  // Complete but not verbose

            // Complex questions - More detailed
            lowerQuery.contains("explain") ||
                    lowerQuery.contains("describe") ||
                    lowerQuery.contains("how") ||
                    lowerQuery.contains("why") -> 250  // Detailed but not too long

            // Default - Moderate response
            else -> 180  // Balanced
        }
    }

    /**
     * Creates BALANCED generation options for heavy models
     * Good quality + reasonable speed
     * FIXED: Removed aggressive stop sequences that were cutting responses short!
     */
    private fun createBalancedOptions(tokens: Int): com.runanywhere.sdk.models.RunAnywhereGenerationOptions {
        return com.runanywhere.sdk.models.RunAnywhereGenerationOptions(
            maxTokens = tokens,
            temperature = 0.6f,           // Slightly more creative
            topP = 0.9f,                  // Good diversity
            enableRealTimeTracking = false,
            stopSequences = listOf(       // ONLY stop on clear conversation markers
                "User:",
                "Q:",
                "Human:",
                "Question:",
                "###"                     // Common separator
            ),
            streamingEnabled = true,
            preferredExecutionTarget = null,
            structuredOutput = null,
            systemPrompt = null,
            topK = 50,                    // More diversity for quality
            repetitionPenalty = 1.1f,     // Slight penalty to avoid repetition
            frequencyPenalty = null,
            presencePenalty = null,
            seed = null,                  // Random for natural responses
            contextLength = 1024          // Good context for coherent answers
        )
    }

    /**
     * Shows warning for heavy model usage
     */
    private fun getModelSpeedWarning(): String? {
        if (!isHeavyModel()) return null

        val modelName =
            _availableModels.value.find { it.id == _currentModelId.value }?.name ?: "Current model"
        return "⚠️ $modelName is a large model. For faster responses, use Llama 1B or Qwen 0.5B."
    }
    
    /**
     * Direct LLM generation with smart token limits.
     * Uses PerformanceBooster for maximum speed during inference.
     */
    private suspend fun processWithDirectLLM(text: String) {
        if (_currentModelId.value == null) {
            _statusMessage.value = "Please load a model first"
            return
        }

        val promptWithContext = buildConversationContext(text, maxMessages = 2)
        val smartTokens = getSmartMaxTokens(text)
        android.util.Log.d("ChatViewModel", "Query: $text, SmartTokens: $smartTokens")

        var assistantResponse = ""
        val startTime = System.currentTimeMillis()

        // Add "thinking" placeholder immediately
        val thinkingMessage = "Thinking..."

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val currentMessages = _messages.value.toMutableList()
            currentMessages.add(
                ChatMessage(
                    text = thinkingMessage,
                    isUser = false,
                    isStreaming = true
                )
            )
            _messages.value = currentMessages
        }

        // Get performance booster
        val performanceBooster = MyApplication.instance.performanceBooster

        // Request battery optimization exemption on first LLM use (one-time dialog)
        if (!hasRequestedBatteryExemption && !performanceBooster.isBatteryOptimizationExempted()) {
            hasRequestedBatteryExemption = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                performanceBooster.requestBatteryOptimizationExemption()
            }
        }

        try {
            // 🚀 START PERFORMANCE BOOST - maximize CPU priority + foreground service
            performanceBooster.startBoost()
            android.util.Log.d("ChatViewModel", "🚀 Performance boost STARTED")

            val isHeavy = isHeavyModel()

            // Use TURBO MODE for heavy models (3B+)
            val smartOptions = if (isHeavy) {
                // 🔥 TURBO MODE - Aggressive optimization for 3B+ models
                val turboTokens = getBalancedTokensForHeavyModel(text)
                android.util.Log.d(
                    "ChatViewModel",
                    "🔥 TURBO MODE: $turboTokens tokens for 3B+ model"
                )
                createBalancedOptions(turboTokens)
            } else {
                // Normal mode for smaller models
                android.util.Log.d("ChatViewModel", "⚡ Normal mode: $smartTokens tokens")
                com.runanywhere.sdk.models.RunAnywhereGenerationOptions(
                    maxTokens = smartTokens,
                    temperature = 0.5f,
                    topP = 0.85f,
                    enableRealTimeTracking = false,
                    stopSequences = listOf("User:", "Q:", "Human:", "Question:"),  // REMOVED \n\n - was cutting responses short!
                    streamingEnabled = true,
                    preferredExecutionTarget = null,
                    structuredOutput = null,
                    systemPrompt = null,
                    topK = 40,
                    repetitionPenalty = 1.05f,
                    frequencyPenalty = null,
                    presencePenalty = null,
                    seed = null,
                    contextLength = 1024  // Increased for better coherence
                )
            }

            // For TURBO MODE, use minimal context prompt
            val optimizedPrompt = if (isHeavy) {
                // Ultra-minimal prompt for heavy models
                "Q: $text\nA:"
            } else {
                promptWithContext
            }

            android.util.Log.d("ChatViewModel", "📝 Prompt length: ${optimizedPrompt.length} chars")

            // Generate with smart options (CPU is now boosted)
            var tokenCount = 0

            RunAnywhere.generateStream(optimizedPrompt, smartOptions).collect { token ->
                assistantResponse += token
                tokenCount++

                // Update UI with streaming response for better UX
                if (tokenCount % 5 == 0) {  // Update every 5 tokens
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val currentMessages = _messages.value.toMutableList()
                        if (currentMessages.lastOrNull()?.isUser == false) {
                            currentMessages[currentMessages.lastIndex] = ChatMessage(
                                text = assistantResponse.trim(),
                                isUser = false,
                                isStreaming = true
                            )
                            _messages.value = currentMessages
                        }
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            val elapsedSec = elapsed / 1000.0

            android.util.Log.d(
                "ChatViewModel",
                "⚡ Generation complete: ${elapsed}ms (${String.format("%.1f", elapsedSec)}s), " +
                        "tokens: $tokenCount, heavy: $isHeavy"
            )

            if (assistantResponse.isNotBlank()) {
                // Show clean response - no timing clutter
                showFinalMessage(assistantResponse.trim())
            } else {
                assistantResponse = "I'm not sure how to answer that."
                showFinalMessage(assistantResponse)
            }

        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "LLM error", e)
            assistantResponse = "Sorry, I couldn't generate a response. Please try again."
            showFinalMessage(assistantResponse)
        } finally {
            // 🛑 STOP PERFORMANCE BOOST - release resources
            performanceBooster.stopBoost()
            android.util.Log.d("ChatViewModel", "🛑 Performance boost STOPPED")
        }
        
        // Save final message
        val finalMessage = ChatMessage(text = assistantResponse.trim(), isUser = false)
        saveMessage(finalMessage)
        
        // Speak response if TTS enabled
        android.util.Log.d(
            "ChatViewModel",
            "🔊 TTS Check: enabled=${_isTtsEnabled.value}, response='${assistantResponse.take(50)}', blank=${assistantResponse.isBlank()}"
        )
        if (_isTtsEnabled.value && assistantResponse.isNotBlank()) {
            android.util.Log.d("ChatViewModel", "🔊 Calling speakResponse!")
            speakResponse(assistantResponse)
        } else {
            android.util.Log.w(
                "ChatViewModel",
                "🔊 NOT speaking: ttsEnabled=${_isTtsEnabled.value}, hasText=${assistantResponse.isNotBlank()}")
        }
    }
    
    /**
     * Show final message without streaming indicator
     */
    private suspend fun showFinalMessage(text: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val finalMessages = _messages.value.toMutableList()
            if (finalMessages.lastOrNull()?.isUser == false) {
                finalMessages[finalMessages.lastIndex] =
                    ChatMessage(text = text, isUser = false, isStreaming = false)
            }
            _messages.value = finalMessages
        }
    }
    
    /**
     * Saves a message to current session.
     * Creates session if needed and saves immediately.
     */
    private fun saveMessage(message: ChatMessage) {
        viewModelScope.launch {
            try {
                // Ensure session exists
                ensureSessionExists()

                // Save to session
                currentSessionId?.let { sessionId ->
                    // For first message, we need to create the session in repository
                    val existingSession = sessionRepository.getSession(sessionId)
                    if (existingSession == null) {
                        // Create new session with this message
                        val title = if (message.isUser) message.text.take(30) else "New Chat"
                        val newSession = ChatSession(
                            id = sessionId,
                            title = title,
                            messages = listOf(message),
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        sessionRepository.saveSession(newSession)
                        android.util.Log.d(
                            "ChatViewModel",
                            "Created and saved new session: $sessionId"
                        )
                    } else {
                        // Add to existing session
                        sessionRepository.addMessageToSession(sessionId, message)
                    }
                    loadChatSessions() // Refresh sessions list
                }
                // Also save to legacy repository
                messageRepository?.saveMessage(message)
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Error saving message: ${e.message}")
            }
        }
    }
    
    /**
     * Speaks the response using TTS.
     * Requirement 3.1: WHEN the LLM completes generating a response,
     * THE Translexa app SHALL convert the response text to speech
     */
    private fun speakResponse(text: String) {
        android.util.Log.d("ChatViewModel", "🔊 speakResponse called, text length: ${text.length}")
        android.util.Log.d("ChatViewModel", "🔊 ttsService null? ${ttsService == null}")
        android.util.Log.d("ChatViewModel", "🔊 ttsEnabled? ${_isTtsEnabled.value}")

        if (text.isBlank()) {
            android.util.Log.w("ChatViewModel", "🔊 Text is blank, not speaking")
            return
        }

        if (ttsService == null) {
            android.util.Log.e("ChatViewModel", "🔊 ttsService is NULL!")
            return
        }

        viewModelScope.launch {
            android.util.Log.d("ChatViewModel", "🔊 Calling ttsService.speak()...")
            ttsService?.speak(text)
            android.util.Log.d("ChatViewModel", "🔊 ttsService.speak() called")
        }
    }
    
    /**
     * Stops TTS playback.
     * Requirement 3.3: WHEN the user taps a stop button during TTS playback,
     * THE Translexa app SHALL immediately stop audio output
     */
    fun stopSpeaking() {
        ttsService?.stop()
    }
    
    /**
     * Toggles TTS enabled state.
     * Requirement 3.4: WHEN TTS is enabled in settings,
     * THE Translexa app SHALL automatically speak all assistant responses
     */
    fun setTtsEnabled(enabled: Boolean) {
        _isTtsEnabled.value = enabled
        ttsService?.setEnabled(enabled)
        if (!enabled) {
            stopSpeaking()
        }
    }
    
    /**
     * Clears conversation history.
     * Requirement 8.3: WHEN the user requests to clear conversation history,
     * THE Translexa app SHALL delete all stored messages and reset the context
     */
    fun clearHistory() {
        viewModelScope.launch {
            try {
                messageRepository?.clearAll()
                assistantController?.clearContext()
                _messages.value = emptyList()
            } catch (e: Exception) {
                _statusMessage.value = "Failed to clear history: ${e.message}"
            }
        }
    }

    fun refreshModels() {
        loadAvailableModels()
    }
    
    override fun onCleared() {
        super.onCleared()
        ttsService?.shutdown()
    }
}
