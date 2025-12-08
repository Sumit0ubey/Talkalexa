package com.runanywhere.startup_hackathon20.ui

import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.runanywhere.startup_hackathon20.ChatViewModel
import com.runanywhere.startup_hackathon20.MessagesAdapter
import com.runanywhere.startup_hackathon20.ModelPopupAdapter
import com.runanywhere.startup_hackathon20.ModelsAdapter
import com.runanywhere.startup_hackathon20.MyApplication
import com.runanywhere.startup_hackathon20.R
import com.runanywhere.startup_hackathon20.ViewModelFactory
import com.runanywhere.startup_hackathon20.domain.model.AttachmentType
import com.runanywhere.startup_hackathon20.domain.model.MessageAttachment
import com.runanywhere.startup_hackathon20.domain.service.DocumentProcessor
import com.runanywhere.startup_hackathon20.domain.service.DocumentType
import com.runanywhere.startup_hackathon20.domain.service.ProcessedDocument
import com.runanywhere.sdk.models.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatFragment : Fragment() {

    private val chatViewModel: ChatViewModel by activityViewModels { ViewModelFactory() }
    
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var modelSelectorRecyclerView: RecyclerView
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var btnNewChat: MaterialButton
    private lateinit var btnModel: MaterialButton
    private lateinit var statusMessage: TextView
    private lateinit var statusIndicator: View
    private lateinit var btnModelIcon: ImageView
    private lateinit var modelPopupCard: CardView
    private lateinit var modelPopupList: RecyclerView

    // Attachment views
    private lateinit var btnAttachment: ImageView
    private lateinit var attachmentPreviewCard: CardView
    private lateinit var attachmentThumbnail: ImageView
    private lateinit var attachmentFileName: TextView
    private lateinit var attachmentFileSize: TextView
    private lateinit var btnRemoveAttachment: ImageView

    private val messagesAdapter = MessagesAdapter()
    private val modelsAdapter by lazy {
        ModelsAdapter(
            onDownload = { modelId -> chatViewModel.downloadModel(modelId) },
            onLoad = { modelId -> chatViewModel.loadModel(modelId) }
        )
    }

    private lateinit var modelPopupAdapter: ModelPopupAdapter
    private var showModelSelector = false
    private var showModelPopup = false
    private var loadedModels: List<ModelInfo> = emptyList()
    private var selectedModelId: String? = null

    // Attachment state
    private var currentAttachment: ProcessedDocument? = null
    private var currentAttachmentUri: Uri? = null
    private val documentProcessor by lazy { DocumentProcessor(requireContext()) }

    // File picker launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processSelectedFile(it) }
    }

    // Multi-type file picker
    private val multiFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { processSelectedFile(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupRecyclerViews()
        setupClickListeners()
        setupAttachmentHandling()
        observeViewModel()
    }

    private fun initViews(view: View) {
        messagesRecyclerView = view.findViewById(R.id.messagesRecyclerView)
        modelSelectorRecyclerView = view.findViewById(R.id.modelSelectorRecyclerView)
        inputEditText = view.findViewById(R.id.inputEditText)
        sendButton = view.findViewById(R.id.sendButton)
        btnNewChat = view.findViewById(R.id.btnNewChat)
        btnModel = view.findViewById(R.id.btnModel)
        statusMessage = view.findViewById(R.id.statusMessage)
        statusIndicator = view.findViewById(R.id.statusIndicator)
        btnModelIcon = view.findViewById(R.id.btnModelIcon)
        modelPopupCard = view.findViewById(R.id.modelPopupCard)
        modelPopupList = view.findViewById(R.id.modelPopupList)

        // Attachment views
        btnAttachment = view.findViewById(R.id.btnAttachment)
        attachmentPreviewCard = view.findViewById(R.id.attachmentPreviewCard)
        attachmentThumbnail = view.findViewById(R.id.attachmentThumbnail)
        attachmentFileName = view.findViewById(R.id.attachmentFileName)
        attachmentFileSize = view.findViewById(R.id.attachmentFileSize)
        btnRemoveAttachment = view.findViewById(R.id.btnRemoveAttachment)
    }

    /**
     * Sets up attachment button and preview handling.
     */
    private fun setupAttachmentHandling() {
        btnAttachment.setOnClickListener {
            showFilePicker()
        }

        btnRemoveAttachment.setOnClickListener {
            clearAttachment()
        }
    }

    /**
     * Shows file picker for images and documents.
     */
    private fun showFilePicker() {
        try {
            // Use OpenDocument for better file type support
            multiFilePickerLauncher.launch(
                arrayOf(
                    "image/*",
                    "application/pdf",
                    "text/plain",
                    "text/markdown",
                    "text/csv",
                    "application/json"
                )
            )
        } catch (e: Exception) {
            // Fallback to simple picker
            try {
                filePickerLauncher.launch("*/*")
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), "Cannot open file picker", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    /**
     * Processes a selected file.
     * Uses ML Kit for ACTUAL image analysis!
     */
    private fun processSelectedFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                // Show loading
                attachmentPreviewCard.visibility = View.VISIBLE
                attachmentFileName.text = "Analyzing..."
                attachmentFileSize.text = "Analyzing image..."
                attachmentThumbnail.setImageResource(R.drawable.ic_document)

                // Get file name first
                val fileName = getFileName(uri)
                val isImage = isImageFile(fileName)

                // Process document - use ML Kit analysis for images!
                val processed = withContext(Dispatchers.IO) {
                    if (isImage) {
                        // 🔥 Use ML Kit for REAL image analysis!
                        documentProcessor.processImageWithAnalysis(uri, fileName)
                    } else {
                        documentProcessor.processDocument(uri)
                    }
                }

                if (processed.error != null) {
                    Toast.makeText(requireContext(), processed.error, Toast.LENGTH_SHORT).show()
                    clearAttachment()
                    return@launch
                }

                // Store attachment
                currentAttachment = processed
                currentAttachmentUri = uri

                // Update preview
                attachmentFileName.text = processed.fileName
                attachmentFileSize.text = formatFileSize(processed.fileSizeKB)

                // Set thumbnail
                if (processed.thumbnailBitmap != null) {
                    attachmentThumbnail.setImageBitmap(processed.thumbnailBitmap)
                } else {
                    val iconRes = when (processed.type) {
                        DocumentType.PDF -> R.drawable.ic_document
                        DocumentType.TEXT -> R.drawable.ic_document
                        else -> R.drawable.ic_document
                    }
                    attachmentThumbnail.setImageResource(iconRes)
                }

                // Show success with analysis info
                val analysisInfo = if (isImage) "Image analyzed" else "Attached"
                Toast.makeText(
                    requireContext(),
                    "$analysisInfo ${processed.fileName} (${processed.processingTimeMs}ms)",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Failed to process file: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                clearAttachment()
            }
        }
    }

    /**
     * Gets file name from URI.
     */
    private fun getFileName(uri: Uri): String {
        var name = "Unknown"
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: "Unknown"
            }
        }
        return name
    }

    /**
     * Checks if file is an image based on extension.
     */
    private fun isImageFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    }

    /**
     * Clears the current attachment.
     */
    private fun clearAttachment() {
        currentAttachment = null
        currentAttachmentUri = null
        attachmentPreviewCard.visibility = View.GONE
    }

    /**
     * Formats file size for display.
     */
    private fun formatFileSize(sizeKB: Long): String {
        return when {
            sizeKB < 1024 -> "${sizeKB} KB"
            else -> String.format("%.1f MB", sizeKB / 1024.0)
        }
    }

    /**
     * Creates MessageAttachment from ProcessedDocument.
     */
    private fun createMessageAttachment(processed: ProcessedDocument, uri: Uri): MessageAttachment {
        val type = when (processed.type) {
            DocumentType.IMAGE -> AttachmentType.IMAGE
            DocumentType.PDF -> AttachmentType.PDF
            DocumentType.TEXT -> AttachmentType.TEXT_FILE
            else -> AttachmentType.DOCUMENT
        }

        return MessageAttachment(
            type = type,
            fileName = processed.fileName,
            fileSizeKB = processed.fileSizeKB,
            extractedText = processed.extractedText,
            pageCount = processed.pageCount,
            uriString = uri.toString(),
            thumbnail = processed.thumbnailBitmap
        )
    }

    private fun setupRecyclerViews() {
        // Setup messages RecyclerView with anti-glitch settings
        val layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true  // Start from bottom
        }
        messagesRecyclerView.layoutManager = layoutManager
        messagesRecyclerView.adapter = messagesAdapter

        // 🔧 FIX: Disable item animations to prevent glitching
        messagesRecyclerView.itemAnimator = null

        // 🔧 FIX: Set item view cache size for smoother scrolling
        messagesRecyclerView.setItemViewCacheSize(20)

        // 🔧 FIX: Enable drawing cache
        messagesRecyclerView.setHasFixedSize(false)

        // 🔧 FIX: Disable nested scrolling
        messagesRecyclerView.isNestedScrollingEnabled = false

        modelSelectorRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        modelSelectorRecyclerView.adapter = modelsAdapter
        
        // Setup model popup list
        modelPopupAdapter = ModelPopupAdapter { model ->
            selectedModelId = model.id
            chatViewModel.loadModel(model.id)
            updateModelIcon(model.id)
            hideModelPopup()
        }
        modelPopupList.layoutManager = LinearLayoutManager(requireContext())
        modelPopupList.adapter = modelPopupAdapter
    }

    private fun setupClickListeners() {
        sendButton.setOnClickListener {
            val text = inputEditText.text.toString().trim()
            val hasAttachment = currentAttachment != null

            if (text.isNotEmpty() || hasAttachment) {
                // Send message with optional attachment
                if (hasAttachment && currentAttachmentUri != null) {
                    val attachment =
                        createMessageAttachment(currentAttachment!!, currentAttachmentUri!!)
                    val messageText = if (text.isNotEmpty()) {
                        text
                    } else {
                        "Sent: ${currentAttachment!!.fileName}"
                    }
                    chatViewModel.sendMessageWithAttachment(messageText, attachment)
                    clearAttachment()
                } else {
                    chatViewModel.sendMessage(text)
                }
                inputEditText.text.clear()
            }
        }

        btnNewChat.setOnClickListener {
            chatViewModel.startNewChat()
            clearAttachment()
            Toast.makeText(requireContext(), "New chat started", Toast.LENGTH_SHORT).show()
        }

        btnModel.setOnClickListener {
            showModelSelector = !showModelSelector
            modelSelectorRecyclerView.visibility =
                if (showModelSelector) View.VISIBLE else View.GONE
        }

        inputEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                // Enable send if there's text OR an attachment
                sendButton.isEnabled = !s.isNullOrBlank() || currentAttachment != null
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        
        // Model icon click - show dropdown
        btnModelIcon.setOnClickListener {
            toggleModelPopup()
        }
    }
    
    private fun updateModelIcon(modelId: String?) {
        val iconRes = when {
            modelId == null -> R.drawable.ic_model_default
            modelId.contains("phi", ignoreCase = true) -> R.drawable.ic_model_phi
            modelId.contains("llama", ignoreCase = true) && modelId.contains("tiny", ignoreCase = true) -> R.drawable.ic_model_tinyllama
            modelId.contains("llama", ignoreCase = true) -> R.drawable.ic_model_llama
            modelId.contains("gemma", ignoreCase = true) -> R.drawable.ic_model_gemma
            modelId.contains("qwen", ignoreCase = true) -> R.drawable.ic_model_qwen
            modelId.contains("mistral", ignoreCase = true) -> R.drawable.ic_model_mistral
            modelId.contains("deepseek", ignoreCase = true) -> R.drawable.ic_model_deepseek
            modelId.contains("stablelm", ignoreCase = true) -> R.drawable.ic_model_stablelm
            else -> R.drawable.ic_model_default
        }
        btnModelIcon.setImageResource(iconRes)
    }
    
    private fun toggleModelPopup() {
        showModelPopup = !showModelPopup
        modelPopupCard.visibility = if (showModelPopup) View.VISIBLE else View.GONE
    }
    
    private fun hideModelPopup() {
        showModelPopup = false
        modelPopupCard.visibility = View.GONE
    }
    
    private fun setupModelPopup(models: List<ModelInfo>) {
        // Filter only downloaded models
        loadedModels = models.filter { it.isDownloaded }
        
        if (loadedModels.isEmpty()) {
            return
        }
        
        // Update popup adapter
        modelPopupAdapter.submitList(loadedModels, selectedModelId)
        
        // Set current model
        val currentModelIdValue = chatViewModel.currentModelId.value
        if (currentModelIdValue != null) {
            selectedModelId = currentModelIdValue
        } else if (loadedModels.isNotEmpty()) {
            selectedModelId = loadedModels[0].id
        }
    }
    
    private fun getDisplayName(modelId: String): String {
        return when {
            modelId.contains("phi", ignoreCase = true) -> "Phi-3 Mini"
            modelId.contains("llama", ignoreCase = true) -> "Llama 3.2"
            modelId.contains("gemma", ignoreCase = true) -> "Gemma 2B"
            modelId.contains("qwen", ignoreCase = true) -> "Qwen 2.5"
            modelId.contains("mistral", ignoreCase = true) -> "Mistral 7B"
            modelId.contains("deepseek", ignoreCase = true) -> "DeepSeek"
            modelId.contains("tinyllama", ignoreCase = true) -> "TinyLlama"
            modelId.contains("stablelm", ignoreCase = true) -> "StableLM"
            else -> modelId.substringAfterLast("/").substringBefore(".").take(15)
        }
    }
    
    private fun getModelIcon(modelId: String): Int {
        return when {
            modelId.contains("phi", ignoreCase = true) -> R.drawable.ic_model_phi
            modelId.contains("llama", ignoreCase = true) -> R.drawable.ic_model_llama
            modelId.contains("gemma", ignoreCase = true) -> R.drawable.ic_model_gemma
            modelId.contains("qwen", ignoreCase = true) -> R.drawable.ic_model_qwen
            modelId.contains("mistral", ignoreCase = true) -> R.drawable.ic_model_mistral
            modelId.contains("deepseek", ignoreCase = true) -> R.drawable.ic_model_deepseek
            modelId.contains("tinyllama", ignoreCase = true) -> R.drawable.ic_model_tinyllama
            modelId.contains("stablelm", ignoreCase = true) -> R.drawable.ic_model_stablelm
            else -> R.drawable.ic_model_default
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.messages.collectLatest { messages ->
                messagesAdapter.submitList(messages)
                if (messages.isNotEmpty()) {
                    // 🔧 FIX: Smooth scroll with delay to prevent glitching
                    messagesRecyclerView.post {
                        messagesRecyclerView.smoothScrollToPosition(messages.size - 1)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.statusMessage.collectLatest { message ->
                statusMessage.text = message
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.availableModels.collectLatest { models ->
                modelsAdapter.submitList(models)
                setupModelPopup(models)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.currentModelId.collectLatest { modelId ->
                modelsAdapter.setCurrentModel(modelId)
                selectedModelId = modelId
                modelPopupAdapter.setCurrentModel(modelId)
                updateModelIcon(modelId)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.isLoading.collectLatest { isLoading ->
                sendButton.isEnabled = !isLoading && inputEditText.text.isNotBlank()
                updateStatusIndicator(isLoading)
            }
        }
    }

    private fun updateStatusIndicator(isLoading: Boolean) {
        val color = if (isLoading) {
            ContextCompat.getColor(requireContext(), R.color.accentOrange)
        } else {
            ContextCompat.getColor(requireContext(), R.color.accentGreen)
        }
        (statusIndicator.background as? GradientDrawable)?.setColor(color)
    }
}
