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
import com.runanywhere.startup_hackathon20.domain.model.UserSettings
import com.runanywhere.startup_hackathon20.domain.service.AssistantController
import com.runanywhere.startup_hackathon20.domain.service.AssistantControllerImpl
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

    init {
        loadAvailableModels()
        loadUserSettings()
        loadChatSessions()
        observeTtsState()
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
    
    fun downloadModel(modelId: String) {
        viewModelScope.launch {
            try {
                android.util.Log.d("ChatViewModel", "Starting download for model: $modelId")
                _statusMessage.value = "Downloading model..."
                lastDownloadProgress = 0f
                
                RunAnywhere.downloadModel(modelId).collect { progress ->
                    // Only update if progress increased (prevents flicker)
                    if (progress >= lastDownloadProgress) {
                        lastDownloadProgress = progress
                        _downloadProgress.value = progress
                        _statusMessage.value = "Downloading: ${(progress * 100).toInt()}%"
                        android.util.Log.d("ChatViewModel", "Download progress: ${(progress * 100).toInt()}%")
                    }
                }
                _downloadProgress.value = null
                lastDownloadProgress = 0f
                _statusMessage.value = "Download complete! Please load the model."
                // Refresh models list to show downloaded status
                loadAvailableModels()
                android.util.Log.d("ChatViewModel", "Download complete!")
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Download failed: ${e.message}", e)
                _statusMessage.value = "Download failed: ${e.message}"
                _downloadProgress.value = null
                lastDownloadProgress = 0f
            }
        }
    }

    fun loadModel(modelId: String) {
        viewModelScope.launch {
            try {
                _statusMessage.value = "Loading model..."
                val success = RunAnywhere.loadModel(modelId)
                if (success) {
                    _currentModelId.value = modelId
                    _statusMessage.value = "Model loaded! Ready to chat."
                    
                    // Update AssistantController model state
                    (assistantController as? AssistantControllerImpl)?.setModelLoaded(true)
                } else {
                    _statusMessage.value = "Failed to load model"
                }
            } catch (e: Exception) {
                _statusMessage.value = "Error loading model: ${e.message}"
            }
        }
    }

    /**
     * Sends a message using the AssistantController for two-speed routing.
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
                // Check for instant actions first (PS3 - <80ms)
                val classifier = MyApplication.instance.intentClassifier
                val classification = classifier.classify(text)
                
                android.util.Log.d("ChatViewModel", "Input: $text, Type: ${classification.type}, Params: ${classification.extractedParams}")
                
                if (classification.type.isInstantAction()) {
                    // FAST PATH: Execute instantly without LLM
                    android.util.Log.d("ChatViewModel", "FAST PATH: Executing instant action")
                    val executor = MyApplication.instance.instantActionExecutor
                    val response = executor.execute(classification)
                    android.util.Log.d("ChatViewModel", "Action result: ${response.text}")
                    val actionMessage = ChatMessage(text = response.text, isUser = false)
                    _messages.value += actionMessage
                    saveMessage(actionMessage)
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
     */
    private fun getSmartMaxTokens(query: String): Int {
        val lowerQuery = query.lowercase()
        
        return when {
            // Detailed explanations need more tokens
            lowerQuery.contains("explain") || 
            lowerQuery.contains("describe") ||
            lowerQuery.contains("how does") ||
            lowerQuery.contains("why does") ||
            lowerQuery.contains("tell me about") ||
            lowerQuery.contains("what are the") ||
            lowerQuery.contains("list") ||
            lowerQuery.contains("compare") -> 400
            
            // Short factual questions
            lowerQuery.startsWith("what is") ||
            lowerQuery.startsWith("who is") ||
            lowerQuery.startsWith("when") ||
            lowerQuery.startsWith("where") ||
            lowerQuery.contains("define") ||
            query.split(" ").size <= 5 -> 150
            
            // Default balanced
            else -> 250
        }
    }
    
    /**
     * Direct LLM generation with smart token limits.
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
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val currentMessages = _messages.value.toMutableList()
            currentMessages.add(ChatMessage(text = "Thinking...", isUser = false, isStreaming = true))
            _messages.value = currentMessages
        }
        
        try {
            // Smart generation options based on query type
            val smartOptions = com.runanywhere.sdk.models.RunAnywhereGenerationOptions(
                maxTokens = smartTokens,
                temperature = 0.7f,
                topP = 0.9f,
                enableRealTimeTracking = false,
                stopSequences = listOf("\n\n\n", "User:", "Q:"),
                streamingEnabled = true,
                preferredExecutionTarget = null,
                structuredOutput = null,
                systemPrompt = null,
                topK = 40,
                repetitionPenalty = 1.1f,
                frequencyPenalty = null,
                presencePenalty = null,
                seed = null,
                contextLength = 1024
            )
            
            // Generate with smart options
            RunAnywhere.generateStream(promptWithContext, smartOptions).collect { token ->
                assistantResponse += token
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.d("ChatViewModel", "Generation took ${elapsed}ms, tokens: $smartTokens, response: ${assistantResponse.take(100)}...")
            
            if (assistantResponse.isNotBlank()) {
                showFinalMessage(assistantResponse.trim())
            } else {
                assistantResponse = "I'm not sure how to answer that."
                showFinalMessage(assistantResponse)
            }
            
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "LLM error", e)
            assistantResponse = "Sorry, I couldn't generate a response. Please try again."
            showFinalMessage(assistantResponse)
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
     */
    private fun saveMessage(message: ChatMessage) {
        viewModelScope.launch {
            try {
                // Save to session
                currentSessionId?.let { sessionId ->
                    sessionRepository.addMessageToSession(sessionId, message)
                    loadChatSessions() // Refresh sessions list
                }
                // Also save to legacy repository
                messageRepository?.saveMessage(message)
            } catch (e: Exception) {
                // Silently fail - message is still in memory
            }
        }
    }
    
    /**
     * Speaks the response using TTS.
     * Requirement 3.1: WHEN the LLM completes generating a response,
     * THE Translexa app SHALL convert the response text to speech
     */
    private fun speakResponse(text: String) {
        if (text.isBlank()) return
        
        viewModelScope.launch {
            ttsService?.speak(text)
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
