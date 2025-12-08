package com.runanywhere.startup_hackathon20.domain.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.runanywhere.sdk.models.ModelInfo
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.listAvailableModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Model loading state for UI updates.
 */
sealed class ModelLoadingState {
    object Idle : ModelLoadingState()
    object Checking : ModelLoadingState()
    data class Downloading(val modelName: String, val progress: Float) : ModelLoadingState()
    data class Loading(val modelName: String) : ModelLoadingState()
    data class Ready(val modelName: String, val modelId: String) : ModelLoadingState()
    data class Error(val message: String) : ModelLoadingState()
}

/**
 * Adaptive Model Manager
 * 
 * Intelligently manages model selection and loading based on device resources.
 * 
 * Features:
 * - Auto-selects appropriate model based on device RAM and battery
 * - Pre-loads small model on app startup for instant responses
 * - Loads heavy models only on user demand
 * - Monitors resources and warns before potential crashes
 * - Remembers user's preferred model
 */
class AdaptiveModelManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AdaptiveModelManager"
        private const val PREFS_NAME = "adaptive_model_prefs"
        private const val KEY_PREFERRED_MODEL = "preferred_model"
        private const val KEY_LAST_LOADED_MODEL = "last_loaded_model"
        private const val KEY_AUTO_LOAD_ENABLED = "auto_load_enabled"
        
        // Model URL mapping (matches MyApplication.kt registrations)
        val MODEL_URLS = mapOf(
            "SmolLM2-360M" to "https://huggingface.co/prithivMLmods/SmolLM2-360M-GGUF/resolve/main/SmolLM2-360M.Q8_0.gguf",
            "Qwen-0.5B" to "https://huggingface.co/Triangle104/Qwen2.5-0.5B-Instruct-Q6_K-GGUF/resolve/main/qwen2.5-0.5b-instruct-q6_k.gguf",
            "Llama-1B-Q4" to "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            "Llama-1B-Q6" to "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q6_K_L.gguf",
            "Qwen-1.5B" to "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q6_k.gguf",
            "Qwen-3B" to "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            "Llama-3B" to "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            "Phi-3-Mini" to "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
            "Mistral-7B" to "https://huggingface.co/MaziyarPanahi/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3.Q4_K_M.gguf"
        )
        
        // Model display names mapping
        val MODEL_DISPLAY_NAMES = mapOf(
            "SmolLM2-360M" to "SmolLM2 360M",
            "Qwen-0.5B" to "Qwen 2.5 0.5B",
            "Llama-1B-Q4" to "Llama 3.2 1B Q4",
            "Llama-1B-Q6" to "Llama 3.2 1B Q6",
            "Qwen-1.5B" to "Qwen 2.5 1.5B",
            "Qwen-3B" to "Qwen 2.5 3B",
            "Llama-3B" to "Llama 3.2 3B",
            "Phi-3-Mini" to "Phi-3 Mini 3.8B",
            "Mistral-7B" to "Mistral 7B"
        )
    }
    
    private val resourceManager = DeviceResourceManager(context)
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    // State flows for UI observation
    private val _loadingState = MutableStateFlow<ModelLoadingState>(ModelLoadingState.Idle)
    val loadingState: StateFlow<ModelLoadingState> = _loadingState.asStateFlow()
    
    private val _currentModelId = MutableStateFlow<String?>(null)
    val currentModelId: StateFlow<String?> = _currentModelId.asStateFlow()
    
    private val _currentModelKey = MutableStateFlow<String?>(null)
    val currentModelKey: StateFlow<String?> = _currentModelKey.asStateFlow()
    
    private val _resourceSnapshot = MutableStateFlow<ResourceSnapshot?>(null)
    val resourceSnapshot: StateFlow<ResourceSnapshot?> = _resourceSnapshot.asStateFlow()
    
    /**
     * Initializes the manager and optionally auto-loads appropriate model.
     * Call this on app startup.
     */
    suspend fun initialize(autoLoad: Boolean = true) {
        Log.i(TAG, "Initializing AdaptiveModelManager...")
        
        // Update resource snapshot
        updateResourceSnapshot()
        resourceManager.logResourceState()
        
        if (autoLoad && isAutoLoadEnabled()) {
            autoLoadAppropriateModel()
        }
    }
    
    /**
     * Updates the current resource snapshot.
     */
    fun updateResourceSnapshot() {
        _resourceSnapshot.value = resourceManager.getResourceSnapshot()
    }
    
    /**
     * Auto-loads the most appropriate model based on device resources.
     * Prefers previously loaded model if still appropriate.
     */
    suspend fun autoLoadAppropriateModel() {
        withContext(Dispatchers.IO) {
            try {
                _loadingState.value = ModelLoadingState.Checking
                
                // Get available models
                val availableModels = listAvailableModels()
                Log.d(TAG, "Available models: ${availableModels.size}")
                
                // Determine best model to load
                val modelKey = selectBestModel(availableModels)
                Log.i(TAG, "Selected model for auto-load: $modelKey")
                
                // Find the model in available models
                val displayName = MODEL_DISPLAY_NAMES[modelKey] ?: modelKey
                val modelInfo = availableModels.find { it.name == displayName }
                
                if (modelInfo != null) {
                    if (modelInfo.isDownloaded) {
                        // Model already downloaded, just load it
                        loadModel(modelInfo.id, modelKey)
                    } else {
                        // Need to download first
                        downloadAndLoadModel(modelInfo.id, modelKey)
                    }
                } else {
                    Log.w(TAG, "Model $modelKey not found in available models")
                    _loadingState.value = ModelLoadingState.Error("Model not found: $displayName")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in auto-load: ${e.message}", e)
                _loadingState.value = ModelLoadingState.Error("Auto-load failed: ${e.message}")
            }
        }
    }
    
    /**
     * Selects the best model based on:
     * 1. User's preferred model (if set and resources allow)
     * 2. Last successfully loaded model
     * 3. Default model for device tier
     */
    private fun selectBestModel(availableModels: List<ModelInfo>): String {
        val snapshot = resourceManager.getResourceSnapshot()
        
        // Check user's preferred model first
        val preferred = getPreferredModel()
        if (preferred != null) {
            val (canLoad, reason) = resourceManager.canLoadModel(preferred)
            if (canLoad) {
                Log.d(TAG, "Using preferred model: $preferred")
                return preferred
            } else {
                Log.w(TAG, "Preferred model $preferred cannot load: $reason")
            }
        }
        
        // Check last loaded model
        val lastLoaded = getLastLoadedModel()
        if (lastLoaded != null) {
            val (canLoad, _) = resourceManager.canLoadModel(lastLoaded)
            if (canLoad) {
                // Check if it's downloaded
                val displayName = MODEL_DISPLAY_NAMES[lastLoaded]
                val isDownloaded = availableModels.any { it.name == displayName && it.isDownloaded }
                if (isDownloaded) {
                    Log.d(TAG, "Using last loaded model: $lastLoaded")
                    return lastLoaded
                }
            }
        }
        
        // Use default for device tier
        val default = resourceManager.getDefaultStartupModel()
        Log.d(TAG, "Using default model for ${snapshot.deviceTier}: $default")
        return default
    }
    
    // 🔧 FIX: Track last displayed progress to prevent UI glitching
    private var lastDisplayedDownloadProgress = -1

    /**
     * Downloads and loads a model with progress tracking.
     * 🔧 FIXED: Throttled progress updates to prevent UI glitching
     */
    suspend fun downloadAndLoadModel(modelId: String, modelKey: String) {
        withContext(Dispatchers.IO) {
            try {
                val displayName = MODEL_DISPLAY_NAMES[modelKey] ?: modelKey

                // Check resources before downloading
                val (canLoad, reason) = resourceManager.canLoadModel(modelKey)
                if (!canLoad) {
                    _loadingState.value = ModelLoadingState.Error(reason)
                    return@withContext
                }

                Log.i(TAG, "Downloading model: $displayName")
                _loadingState.value = ModelLoadingState.Downloading(displayName, 0f)
                lastDisplayedDownloadProgress = 0

                // Download with throttled progress tracking
                RunAnywhere.downloadModel(modelId).collect { progress ->
                    // 🔧 FIX: Only update every 2% to prevent glitching
                    val currentPercent = (progress * 100).toInt()
                    if (currentPercent - lastDisplayedDownloadProgress >= 2 || currentPercent >= 99) {
                        lastDisplayedDownloadProgress = currentPercent
                        _loadingState.value = ModelLoadingState.Downloading(displayName, progress)
                        Log.d(TAG, "Download progress: $currentPercent%")
                    }
                }

                lastDisplayedDownloadProgress = -1

                // Now load the model
                loadModel(modelId, modelKey)

            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                _loadingState.value = ModelLoadingState.Error("Download failed: ${e.message}")
                lastDisplayedDownloadProgress = -1
            }
        }
    }
    
    /**
     * Loads an already downloaded model.
     */
    suspend fun loadModel(modelId: String, modelKey: String) {
        withContext(Dispatchers.IO) {
            try {
                val displayName = MODEL_DISPLAY_NAMES[modelKey] ?: modelKey
                
                // Check resources before loading
                val (canLoad, reason) = resourceManager.canLoadModel(modelKey)
                if (!canLoad) {
                    _loadingState.value = ModelLoadingState.Error(reason)
                    return@withContext
                }
                
                // Log warning if applicable
                if (reason.startsWith("Warning")) {
                    Log.w(TAG, reason)
                }
                
                Log.i(TAG, "Loading model: $displayName")
                _loadingState.value = ModelLoadingState.Loading(displayName)
                
                val success = RunAnywhere.loadModel(modelId)
                
                if (success) {
                    _currentModelId.value = modelId
                    _currentModelKey.value = modelKey
                    _loadingState.value = ModelLoadingState.Ready(displayName, modelId)
                    
                    // Remember this model
                    saveLastLoadedModel(modelKey)
                    
                    Log.i(TAG, "Model loaded successfully: $displayName")
                } else {
                    _loadingState.value = ModelLoadingState.Error("Failed to load model")
                    Log.e(TAG, "Model load returned false")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Load failed: ${e.message}", e)
                _loadingState.value = ModelLoadingState.Error("Load failed: ${e.message}")
            }
        }
    }
    
    /**
     * Checks if a heavier model can be loaded and provides recommendation.
     */
    fun canUpgradeModel(): Pair<Boolean, String?> {
        val currentKey = _currentModelKey.value ?: return Pair(false, null)
        val currentReq = DeviceResourceManager.MODEL_REQUIREMENTS[currentKey]
            ?: return Pair(false, null)
        
        val snapshot = resourceManager.getResourceSnapshot()
        
        // Find models with higher quality that can run
        val betterModels = DeviceResourceManager.MODEL_REQUIREMENTS.entries
            .filter { it.value.qualityTier > currentReq.qualityTier }
            .filter { resourceManager.canLoadModel(it.key).first }
            .sortedBy { it.value.qualityTier }
        
        return if (betterModels.isNotEmpty()) {
            Pair(true, betterModels.first().key)
        } else {
            Pair(false, null)
        }
    }
    
    /**
     * Gets model recommendation message for UI.
     */
    fun getModelRecommendation(): String {
        val snapshot = resourceManager.getResourceSnapshot()
        val recommended = resourceManager.getRecommendedModel()
        val displayName = MODEL_DISPLAY_NAMES[recommended] ?: recommended
        
        return when (snapshot.deviceTier) {
            DeviceTier.LOW -> "Your device has limited RAM. Using $displayName for best stability."
            DeviceTier.MEDIUM -> "Recommended: $displayName for balanced performance."
            DeviceTier.HIGH -> "Your device can handle larger models! $displayName selected."
            DeviceTier.ULTRA -> "Powerful device detected! $displayName provides best quality."
        }
    }
    
    /**
     * Gets safe models list for current device.
     */
    fun getSafeModels(): List<Pair<String, ModelRequirements>> {
        return DeviceResourceManager.MODEL_REQUIREMENTS.entries
            .filter { resourceManager.canLoadModel(it.key).first }
            .map { Pair(it.key, it.value) }
            .sortedBy { it.second.qualityTier }
    }
    
    /**
     * Gets all models with their load safety status.
     */
    fun getAllModelsWithStatus(): List<Triple<String, ModelRequirements, Boolean>> {
        return DeviceResourceManager.MODEL_REQUIREMENTS.entries
            .map { Triple(it.key, it.value, resourceManager.canLoadModel(it.key).first) }
            .sortedBy { it.second.qualityTier }
    }
    
    // ===== Preferences =====
    
    fun setPreferredModel(modelKey: String?) {
        prefs.edit().putString(KEY_PREFERRED_MODEL, modelKey).apply()
    }
    
    fun getPreferredModel(): String? {
        return prefs.getString(KEY_PREFERRED_MODEL, null)
    }
    
    private fun saveLastLoadedModel(modelKey: String) {
        prefs.edit().putString(KEY_LAST_LOADED_MODEL, modelKey).apply()
    }
    
    private fun getLastLoadedModel(): String? {
        return prefs.getString(KEY_LAST_LOADED_MODEL, null)
    }
    
    fun setAutoLoadEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_LOAD_ENABLED, enabled).apply()
    }
    
    fun isAutoLoadEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_LOAD_ENABLED, true) // Default true
    }
    
    /**
     * Checks if any model is currently loaded.
     */
    fun isModelLoaded(): Boolean {
        return _currentModelId.value != null
    }
    
    /**
     * Gets current device tier.
     */
    fun getDeviceTier(): DeviceTier {
        return _resourceSnapshot.value?.deviceTier ?: resourceManager.getResourceSnapshot().deviceTier
    }
}
