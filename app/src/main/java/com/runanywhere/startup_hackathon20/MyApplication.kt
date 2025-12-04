package com.runanywhere.startup_hackathon20

import android.app.Application
import android.util.Log
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.data.models.SDKEnvironment
import com.runanywhere.sdk.public.extensions.addModelFromURL
import com.runanywhere.sdk.llm.llamacpp.LlamaCppServiceProvider
import com.runanywhere.startup_hackathon20.data.repository.InMemoryMessageRepository
import com.runanywhere.startup_hackathon20.data.repository.MessageRepository
import com.runanywhere.startup_hackathon20.data.repository.MessageRepositoryImpl
import com.runanywhere.startup_hackathon20.data.repository.ReminderRepository
import com.runanywhere.startup_hackathon20.data.repository.ReminderRepositoryImpl
import com.runanywhere.startup_hackathon20.data.repository.TaskRepository
import com.runanywhere.startup_hackathon20.data.repository.TaskRepositoryImpl
import com.runanywhere.startup_hackathon20.domain.service.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Application class for Translexa.
 * Initializes SDK and provides dependency injection for all services.
 */
class MyApplication : Application() {
    
    companion object {
        private const val TAG = "MyApplication"
        
        // Singleton instance for accessing dependencies
        lateinit var instance: MyApplication
            private set
    }
    
    // Repositories
    lateinit var messageRepository: MessageRepository
        private set
    lateinit var reminderRepository: ReminderRepository
        private set
    lateinit var taskRepository: TaskRepository
        private set
    
    // Services
    lateinit var intentClassifier: IntentClassifier
        private set
    lateinit var contextManager: ContextManager
        private set
    lateinit var responseParser: LLMResponseParser
        private set
    lateinit var notificationHelper: NotificationHelper
        private set
    
    // These require context and are initialized lazily
    val instantActionExecutor: InstantActionExecutor by lazy {
        InstantActionExecutorImpl(this, reminderRepository)
    }
    
    val llmActionExecutor: LLMActionExecutor by lazy {
        LLMActionExecutorImpl(reminderRepository, taskRepository)
    }
    
    val ttsService: TTSService by lazy {
        TTSServiceImpl(this)
    }
    
    val voiceInputHandler: VoiceInputHandler by lazy {
        VoiceInputHandlerImpl(this)
    }
    
    val assistantController: AssistantController by lazy {
        AssistantControllerImpl(
            intentClassifier = intentClassifier,
            instantActionExecutor = instantActionExecutor,
            responseParser = responseParser,
            contextManager = contextManager,
            llmActionExecutor = llmActionExecutor
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Initialize dependencies
        initializeDependencies()
        
        // Initialize SDK asynchronously
        GlobalScope.launch(Dispatchers.IO) {
            initializeSDK()
        }
    }
    
    /**
     * Initializes all dependencies (repositories, services, etc.)
     */
    private fun initializeDependencies() {
        Log.d(TAG, "Initializing dependencies...")
        
        // Initialize repositories
        messageRepository = MessageRepositoryImpl(this)
        reminderRepository = ReminderRepositoryImpl(this)
        taskRepository = TaskRepositoryImpl(this)
        
        // Initialize services (non-context dependent)
        intentClassifier = IntentClassifierImpl()
        contextManager = ContextManagerImpl()
        responseParser = LLMResponseParserImpl()
        notificationHelper = NotificationHelper(this)
        
        Log.d(TAG, "Dependencies initialized successfully")
    }

    private suspend fun initializeSDK() {
        try {
            // Step 1: Initialize SDK
            RunAnywhere.initialize(
                context = this@MyApplication,
                apiKey = "dev",  // Any string works in dev mode
                environment = SDKEnvironment.DEVELOPMENT
            )

            LlamaCppServiceProvider.register()

            // Step 3: Register Models
            registerModels()

            // Step 4: Scan for previously downloaded models
            RunAnywhere.scanForDownloadedModels()

            Log.i(TAG, "SDK initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "SDK initialization failed: ${e.message}")
        }
    }

    private suspend fun registerModels() {
        // ===== SMALL MODELS (Fast, Low RAM) =====
        
        // Qwen 2.5 0.5B - Basic, fast (374 MB, 2GB RAM)
        addModelFromURL(
            url = "https://huggingface.co/Triangle104/Qwen2.5-0.5B-Instruct-Q6_K-GGUF/resolve/main/qwen2.5-0.5b-instruct-q6_k.gguf",
            name = "⭐⭐⭐ Qwen 2.5 0.5B",
            type = "LLM"
        )
        
        // SmolLM2 360M - Ultra fast (119 MB, 1GB RAM)
        addModelFromURL(
            url = "https://huggingface.co/prithivMLmods/SmolLM2-360M-GGUF/resolve/main/SmolLM2-360M.Q8_0.gguf",
            name = "⭐⭐ SmolLM2 360M",
            type = "LLM"
        )
        
        // ===== MEDIUM MODELS (Good Balance - RECOMMENDED) =====
        
        // Llama 3.2 1B Q4 - Fast + Good quality (600 MB, 2GB RAM) - BEST FOR SPEED
        addModelFromURL(
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            name = "⭐⭐⭐⭐ Llama 3.2 1B Q4",
            type = "LLM"
        )
        
        // Llama 3.2 1B Q6 - Better quality (815 MB, 3GB RAM)
        addModelFromURL(
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q6_K_L.gguf",
            name = "⭐⭐⭐⭐ Llama 3.2 1B Q6",
            type = "LLM"
        )
        
        // Qwen 2.5 1.5B - High quality (1.2 GB, 4GB RAM)
        addModelFromURL(
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q6_k.gguf",
            name = "⭐⭐⭐⭐⭐ Qwen 2.5 1.5B",
            type = "LLM"
        )
        
        // ===== LARGE MODELS (Best Quality, 8GB RAM) =====
        
        // Qwen 2.5 3B - Excellent quality (2.1 GB, 6GB RAM)
        addModelFromURL(
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            name = "⭐⭐⭐⭐⭐⭐ Qwen 2.5 3B",
            type = "LLM"
        )
        
        // Llama 3.2 3B - Top quality (2.0 GB, 6GB RAM)
        addModelFromURL(
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            name = "⭐⭐⭐⭐⭐⭐ Llama 3.2 3B",
            type = "LLM"
        )
        
        // Phi-3 Mini 3.8B - Microsoft's best small model (2.3 GB, 6GB RAM)
        addModelFromURL(
            url = "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
            name = "⭐⭐⭐⭐⭐⭐⭐ Phi-3 Mini 3.8B",
            type = "LLM"
        )
        
        // ===== ULTRA POWERFUL MODEL (8GB+ RAM Required) =====
        
        // Mistral 7B v0.3 - Excellent quality (4.4 GB, 8GB RAM)
        addModelFromURL(
            url = "https://huggingface.co/MaziyarPanahi/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3.Q4_K_M.gguf",
            name = "⭐⭐⭐⭐⭐⭐⭐⭐ Mistral 7B",
            type = "LLM"
        )
    }
}
