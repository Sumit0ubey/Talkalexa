package com.runanywhere.startup_hackathon20.domain.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Represents the current state of TTS playback.
 */
sealed class TTSState {
    /** TTS is idle and ready to speak */
    object Idle : TTSState()
    
    /** TTS is currently speaking */
    object Speaking : TTSState()
    
    /** TTS has completed speaking the current utterance */
    object Completed : TTSState()
    
    /** TTS encountered an error */
    data class Error(val message: String) : TTSState()
}

/**
 * Interface for Text-to-Speech service.
 * Wraps Android TTS for speaking assistant responses.
 * 
 * Requirements: 3.1, 3.2, 3.3, 3.4
 */
interface TTSService {
    /**
     * Speaks the given text aloud.
     * Returns a Flow that emits state changes during playback.
     * 
     * @param text The text to speak
     * @return Flow of TTSState updates
     */
    fun speak(text: String): Flow<TTSState>
    
    /**
     * Stops any ongoing speech immediately.
     * Requirement 3.3: WHEN the user taps a stop button during TTS playback,
     * THE Translexa app SHALL immediately stop audio output
     */
    fun stop()
    
    /**
     * Checks if TTS auto-speak is enabled.
     * 
     * @return true if TTS is enabled
     */
    fun isEnabled(): Boolean
    
    /**
     * Enables or disables TTS auto-speak.
     * Requirement 3.4: WHEN TTS is enabled in settings,
     * THE Translexa app SHALL automatically speak all assistant responses
     * 
     * @param enabled true to enable, false to disable
     */
    fun setEnabled(enabled: Boolean)
    
    /**
     * Gets the current TTS state as a StateFlow.
     * 
     * @return StateFlow of current TTSState
     */
    fun getStateFlow(): StateFlow<TTSState>
    
    /**
     * Checks if TTS is currently speaking.
     * 
     * @return true if currently speaking
     */
    fun isSpeaking(): Boolean
    
    /**
     * Releases TTS resources. Call when done using the service.
     */
    fun shutdown()
}


/**
 * Voice options with different accents and genders
 */
data class VoiceOption(
    val id: String,
    val name: String,
    val locale: Locale,
    val gender: String, // "Male" or "Female"
    val country: String
)

/**
 * Available voice options
 */
object VoiceOptions {
    val VOICES = listOf(
        VoiceOption("en_us_male", "🇺🇸 English (US) - Male", Locale.US, "Male", "United States"),
        VoiceOption("en_us_female", "🇺🇸 English (US) - Female", Locale.US, "Female", "United States"),
        VoiceOption("en_gb_male", "🇬🇧 English (UK) - Male", Locale.UK, "Male", "United Kingdom"),
        VoiceOption("en_gb_female", "🇬🇧 English (UK) - Female", Locale.UK, "Female", "United Kingdom"),
        VoiceOption("en_au_male", "🇦🇺 English (AU) - Male", Locale("en", "AU"), "Male", "Australia"),
        VoiceOption("en_au_female", "🇦🇺 English (AU) - Female", Locale("en", "AU"), "Female", "Australia"),
        VoiceOption("en_in_male", "🇮🇳 English (IN) - Male", Locale("en", "IN"), "Male", "India"),
        VoiceOption("en_in_female", "🇮🇳 English (IN) - Female", Locale("en", "IN"), "Female", "India"),
        VoiceOption("es_es_male", "🇪🇸 Spanish (ES) - Male", Locale("es", "ES"), "Male", "Spain"),
        VoiceOption("es_es_female", "🇪🇸 Spanish (ES) - Female", Locale("es", "ES"), "Female", "Spain"),
        VoiceOption("fr_fr_male", "🇫🇷 French (FR) - Male", Locale.FRANCE, "Male", "France"),
        VoiceOption("fr_fr_female", "🇫🇷 French (FR) - Female", Locale.FRANCE, "Female", "France"),
        VoiceOption("de_de_male", "🇩🇪 German (DE) - Male", Locale.GERMANY, "Male", "Germany"),
        VoiceOption("de_de_female", "🇩🇪 German (DE) - Female", Locale.GERMANY, "Female", "Germany"),
        VoiceOption("it_it_male", "🇮🇹 Italian (IT) - Male", Locale.ITALY, "Male", "Italy"),
        VoiceOption("it_it_female", "🇮🇹 Italian (IT) - Female", Locale.ITALY, "Female", "Italy"),
        VoiceOption("ja_jp_male", "🇯🇵 Japanese (JP) - Male", Locale.JAPAN, "Male", "Japan"),
        VoiceOption("ja_jp_female", "🇯🇵 Japanese (JP) - Female", Locale.JAPAN, "Female", "Japan"),
        VoiceOption("ko_kr_male", "🇰🇷 Korean (KR) - Male", Locale.KOREA, "Male", "South Korea"),
        VoiceOption("ko_kr_female", "🇰🇷 Korean (KR) - Female", Locale.KOREA, "Female", "South Korea"),
        VoiceOption("zh_cn_male", "🇨🇳 Chinese (CN) - Male", Locale.CHINA, "Male", "China"),
        VoiceOption("zh_cn_female", "🇨🇳 Chinese (CN) - Female", Locale.CHINA, "Female", "China")
    )
    
    fun getVoiceById(id: String): VoiceOption? = VOICES.find { it.id == id }
    fun getDefaultVoice(): VoiceOption = VOICES[0] // US Male
}

/**
 * Implementation of TTSService using Android TextToSpeech.
 * Provides text-to-speech functionality for assistant responses.
 * 
 * Requirements: 3.1, 3.2, 3.3, 3.4, 3.5
 */
class TTSServiceImpl(
    private val context: Context
) : TTSService {
    
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var ttsEnabled = true
    private var currentVoice: VoiceOption = VoiceOptions.getDefaultVoice()
    
    private val _stateFlow = MutableStateFlow<TTSState>(TTSState.Idle)
    
    init {
        initializeTTS()
    }
    
    /**
     * Sets the voice for TTS
     */
    fun setVoice(voiceId: String) {
        val voice = VoiceOptions.getVoiceById(voiceId) ?: return
        currentVoice = voice
        
        if (isInitialized) {
            tts?.language = voice.locale
            
            // Try to set specific voice with gender preference
            tts?.voice = tts?.voices?.find { v ->
                v.locale == voice.locale && 
                (voice.gender.lowercase() in v.name.lowercase())
            } ?: tts?.voices?.find { v ->
                v.locale == voice.locale
            }
        }
    }
    
    /**
     * Gets current voice
     */
    fun getCurrentVoice(): VoiceOption = currentVoice
    
    /**
     * Initializes the Android TextToSpeech engine.
     * Requirement 3.5: IF TTS initialization fails, THEN THE Translexa app
     * SHALL fall back to text-only mode and notify the user
     */
    private fun initializeTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                
                // Set language to default locale
                val result = tts?.setLanguage(Locale.getDefault())
                
                if (result == TextToSpeech.LANG_MISSING_DATA || 
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Fall back to US English
                    tts?.setLanguage(Locale.US)
                }
                
                // Set up utterance progress listener
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _stateFlow.value = TTSState.Speaking
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        _stateFlow.value = TTSState.Completed
                        // Reset to idle after a short delay
                        _stateFlow.value = TTSState.Idle
                    }
                    
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _stateFlow.value = TTSState.Error("Speech synthesis failed")
                    }
                    
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        val errorMessage = when (errorCode) {
                            TextToSpeech.ERROR_SYNTHESIS -> "Speech synthesis error"
                            TextToSpeech.ERROR_SERVICE -> "TTS service error"
                            TextToSpeech.ERROR_OUTPUT -> "Audio output error"
                            TextToSpeech.ERROR_NETWORK -> "Network error"
                            TextToSpeech.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            TextToSpeech.ERROR_INVALID_REQUEST -> "Invalid request"
                            TextToSpeech.ERROR_NOT_INSTALLED_YET -> "TTS not installed"
                            else -> "Unknown TTS error"
                        }
                        _stateFlow.value = TTSState.Error(errorMessage)
                    }
                })
                
                _stateFlow.value = TTSState.Idle
            } else {
                isInitialized = false
                _stateFlow.value = TTSState.Error("TTS initialization failed")
            }
        }
    }
    
    /**
     * Speaks the given text aloud.
     * Requirement 3.1: WHEN the LLM completes generating a response,
     * THE Translexa app SHALL convert the response text to speech
     * using the on-device TTS engine
     */
    override fun speak(text: String): Flow<TTSState> {
        if (!isInitialized) {
            _stateFlow.value = TTSState.Error("TTS not initialized")
            return _stateFlow.asStateFlow()
        }
        
        if (!ttsEnabled) {
            _stateFlow.value = TTSState.Idle
            return _stateFlow.asStateFlow()
        }
        
        if (text.isBlank()) {
            _stateFlow.value = TTSState.Idle
            return _stateFlow.asStateFlow()
        }
        
        // Generate unique utterance ID
        val utteranceId = UUID.randomUUID().toString()
        
        // Speak the text
        val result = tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )
        
        if (result == TextToSpeech.ERROR) {
            _stateFlow.value = TTSState.Error("Failed to start speech")
        }
        
        return _stateFlow.asStateFlow()
    }
    
    /**
     * Stops any ongoing speech immediately.
     * Requirement 3.3: WHEN the user taps a stop button during TTS playback,
     * THE Translexa app SHALL immediately stop audio output
     */
    override fun stop() {
        tts?.stop()
        _stateFlow.value = TTSState.Idle
    }
    
    override fun isEnabled(): Boolean {
        return ttsEnabled
    }
    
    /**
     * Enables or disables TTS auto-speak.
     * Requirement 3.4: WHEN TTS is enabled in settings,
     * THE Translexa app SHALL automatically speak all assistant responses
     */
    override fun setEnabled(enabled: Boolean) {
        ttsEnabled = enabled
        if (!enabled) {
            stop()
        }
    }
    
    override fun getStateFlow(): StateFlow<TTSState> {
        return _stateFlow.asStateFlow()
    }
    
    override fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }
    
    /**
     * Releases TTS resources.
     */
    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}

/**
 * Mock implementation of TTSService for testing.
 * Does not require Android Context.
 */
class MockTTSService : TTSService {
    
    private var enabled = true
    private var speaking = false
    private val _stateFlow = MutableStateFlow<TTSState>(TTSState.Idle)
    
    override fun speak(text: String): Flow<TTSState> {
        if (!enabled || text.isBlank()) {
            _stateFlow.value = TTSState.Idle
            return _stateFlow.asStateFlow()
        }
        
        speaking = true
        _stateFlow.value = TTSState.Speaking
        
        // Simulate completion (in real tests, you'd control this)
        // For now, immediately complete
        speaking = false
        _stateFlow.value = TTSState.Completed
        _stateFlow.value = TTSState.Idle
        
        return _stateFlow.asStateFlow()
    }
    
    override fun stop() {
        speaking = false
        _stateFlow.value = TTSState.Idle
    }
    
    override fun isEnabled(): Boolean = enabled
    
    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            stop()
        }
    }
    
    override fun getStateFlow(): StateFlow<TTSState> = _stateFlow.asStateFlow()
    
    override fun isSpeaking(): Boolean = speaking
    
    override fun shutdown() {
        speaking = false
        _stateFlow.value = TTSState.Idle
    }
    
    // Test helper methods
    fun simulateSpeaking() {
        speaking = true
        _stateFlow.value = TTSState.Speaking
    }
    
    fun simulateCompleted() {
        speaking = false
        _stateFlow.value = TTSState.Completed
        _stateFlow.value = TTSState.Idle
    }
    
    fun simulateError(message: String) {
        speaking = false
        _stateFlow.value = TTSState.Error(message)
    }
}
