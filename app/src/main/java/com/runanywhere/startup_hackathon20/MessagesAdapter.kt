package com.runanywhere.startup_hackathon20

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.runanywhere.startup_hackathon20.domain.model.AttachmentType
import com.runanywhere.startup_hackathon20.domain.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for displaying chat messages with streaming support.
 * Uses direct list management for real-time streaming updates.
 * 🔧 FIXED: Added stable IDs and optimized updates to prevent UI glitching
 */
class MessagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_ASSISTANT = 1
    }

    private val messages = mutableListOf<ChatMessage>()

    init {
        // 🔧 FIX: Enable stable IDs to prevent view recycling issues
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        // 🔧 FIX: Use timestamp as stable ID
        return messages.getOrNull(position)?.timestamp ?: position.toLong()
    }

    override fun getItemCount(): Int = messages.size

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_USER -> {
                val view = inflater.inflate(R.layout.item_message_user, parent, false)
                UserMessageViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_message_assistant, parent, false)
                AssistantMessageViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder) {
            is UserMessageViewHolder -> holder.bind(message)
            is AssistantMessageViewHolder -> holder.bind(message)
        }
    }
    
    // 🔧 FIX: Payload binding to update only text without full rebind
    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }

        val message = messages[position]
        // 🔧 FIX: Only update text content, not entire view
        when (holder) {
            is AssistantMessageViewHolder -> holder.updateText(message)
        }
    }
    
    /**
     * Submit new list - for normal updates
     * 🔧 FIXED: Optimized to prevent unnecessary layout recalculations
     */
    fun submitList(newMessages: List<ChatMessage>) {
        val oldSize = messages.size
        val newSize = newMessages.size

        // 🔧 FIX: Only update if there are actual changes
        if (oldSize == newSize && newSize > 0) {
            // Check if last message text changed (streaming update)
            val oldLast = messages.lastOrNull()
            val newLast = newMessages.lastOrNull()

            if (oldLast?.text != newLast?.text || oldLast?.isStreaming != newLast?.isStreaming) {
                messages.clear()
                messages.addAll(newMessages)
                // 🔧 FIX: Use payload to update only text, not entire view
                notifyItemChanged(newSize - 1, "text_update")
            }
            return
        }

        messages.clear()
        messages.addAll(newMessages)

        if (newSize > oldSize) {
            // New message added
            if (newSize - oldSize == 1) {
                notifyItemInserted(newSize - 1)
            } else {
                notifyItemRangeInserted(oldSize, newSize - oldSize)
            }
        } else if (newSize < oldSize) {
            // Messages removed (clear chat)
            notifyDataSetChanged()
        } else {
            // Same size but different content
            notifyDataSetChanged()
        }
    }
    
    /**
     * Update streaming message directly - for real-time streaming
     */
    fun updateStreamingMessage(text: String) {
        if (messages.isNotEmpty() && !messages.last().isUser) {
            messages[messages.lastIndex] = messages.last().copy(text = text, isStreaming = true)
            notifyItemChanged(messages.lastIndex, "streaming")
        }
    }
    
    /**
     * Finalize streaming message
     */
    fun finalizeStreaming(text: String) {
        if (messages.isNotEmpty() && !messages.last().isUser) {
            messages[messages.lastIndex] = messages.last().copy(text = text, isStreaming = false)
            notifyItemChanged(messages.lastIndex)
        }
    }

    class UserMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val messageTime: TextView = itemView.findViewById(R.id.messageTime)

        // Attachment views
        private val attachmentContainer: LinearLayout? =
            itemView.findViewById(R.id.attachmentContainer)
        private val attachmentImageCard: CardView? = itemView.findViewById(R.id.attachmentImageCard)
        private val attachmentImage: ImageView? = itemView.findViewById(R.id.attachmentImage)
        private val attachmentDocContainer: LinearLayout? =
            itemView.findViewById(R.id.attachmentDocContainer)
        private val attachmentDocIcon: ImageView? = itemView.findViewById(R.id.attachmentDocIcon)
        private val attachmentDocName: TextView? = itemView.findViewById(R.id.attachmentDocName)
        private val attachmentDocSize: TextView? = itemView.findViewById(R.id.attachmentDocSize)

        fun bind(message: ChatMessage) {
            messageText.text = message.text
            messageTime.text = formatTime(message.timestamp)

            // Handle attachment display
            if (message.hasAttachment() && attachmentContainer != null) {
                attachmentContainer.visibility = View.VISIBLE
                val attachment = message.attachment!!

                when (attachment.type) {
                    AttachmentType.IMAGE -> {
                        // Show image thumbnail
                        attachmentImageCard?.visibility = View.VISIBLE
                        attachmentDocContainer?.visibility = View.GONE

                        if (attachment.thumbnail != null) {
                            attachmentImage?.setImageBitmap(attachment.thumbnail)
                        } else {
                            attachmentImage?.setImageResource(R.drawable.ic_document)
                        }
                    }

                    AttachmentType.PDF, AttachmentType.TEXT_FILE, AttachmentType.DOCUMENT -> {
                        // Show document info
                        attachmentImageCard?.visibility = View.GONE
                        attachmentDocContainer?.visibility = View.VISIBLE

                        attachmentDocName?.text = attachment.fileName
                        attachmentDocSize?.text = formatFileSize(attachment.fileSizeKB)

                        // Set appropriate icon
                        val iconRes = when (attachment.type) {
                            AttachmentType.PDF -> R.drawable.ic_document
                            AttachmentType.TEXT_FILE -> R.drawable.ic_document
                            else -> R.drawable.ic_document
                        }
                        attachmentDocIcon?.setImageResource(iconRes)
                    }
                }
            } else {
                attachmentContainer?.visibility = View.GONE
            }

            itemView.setOnLongClickListener {
                copyToClipboard(itemView.context, message.text)
                true
            }
        }

        private fun formatFileSize(sizeKB: Long): String {
            return when {
                sizeKB < 1024 -> "${sizeKB} KB"
                else -> String.format("%.1f MB", sizeKB / 1024.0)
            }
        }
    }

    class AssistantMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val messageTime: TextView = itemView.findViewById(R.id.messageTime)
        private val copyButton: ImageButton = itemView.findViewById(R.id.copyButton)

        // 🔧 FIX: Removed typing animation variables - caused glitching on some devices
        @Suppress("unused")
        private var typingAnimator: android.animation.ValueAnimator? = null
        private var currentMessage: ChatMessage? = null

        /**
         * 🔧 FIX: Update only text without rebinding entire view
         * Prevents layout recalculation and glitching
         */
        fun updateText(message: ChatMessage) {
            currentMessage = message
            // Cancel any ongoing animation
            typingAnimator?.cancel()

            if (message.isStreaming) {
                messageText.text = message.text
            } else {
                messageText.text = parseMarkdown(message.text)
            }
        }

        fun bind(message: ChatMessage) {
            currentMessage = message
            // Cancel any ongoing animation
            typingAnimator?.cancel()
            
            if (message.isStreaming) {
                // Show "Thinking..." or streaming text
                messageText.text = message.text
                messageTime.visibility = View.GONE
                copyButton.visibility = View.GONE
            } else {
                // 🔧 FIX: Show formatted text directly without animation
                // Typing animation was causing glitching on some devices
                val fullText = message.text
                val formattedText = parseMarkdown(fullText)
                messageText.text = formattedText

                messageTime.visibility = View.VISIBLE
                copyButton.visibility = View.VISIBLE
                messageTime.text = formatTime(message.timestamp)
            }

            copyButton.setOnClickListener {
                copyToClipboard(itemView.context, message.text.replace("▌", ""))
            }

            itemView.setOnLongClickListener {
                copyToClipboard(itemView.context, message.text.replace("▌", ""))
                true
            }
        }
        
        /**
         * Parse markdown to SpannableString
         * Supports: **bold**, *italic*, `code`, ***bold italic***
         */
        private fun parseMarkdown(text: String): CharSequence {
            val spannable = SpannableStringBuilder()
            var currentIndex = 0
            val cleanText = text
            
            // Process text character by character
            var i = 0
            while (i < cleanText.length) {
                when {
                    // Bold + Italic: ***text***
                    cleanText.startsWith("***", i) -> {
                        val endIndex = cleanText.indexOf("***", i + 3)
                        if (endIndex != -1) {
                            val content = cleanText.substring(i + 3, endIndex)
                            val start = spannable.length
                            spannable.append(content)
                            spannable.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            i = endIndex + 3
                        } else {
                            spannable.append(cleanText[i])
                            i++
                        }
                    }
                    // Bold: **text**
                    cleanText.startsWith("**", i) -> {
                        val endIndex = cleanText.indexOf("**", i + 2)
                        if (endIndex != -1) {
                            val content = cleanText.substring(i + 2, endIndex)
                            val start = spannable.length
                            spannable.append(content)
                            spannable.setSpan(StyleSpan(Typeface.BOLD), start, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            i = endIndex + 2
                        } else {
                            spannable.append(cleanText[i])
                            i++
                        }
                    }
                    // Italic: *text*
                    cleanText[i] == '*' && (i == 0 || cleanText[i-1] != '*') && i + 1 < cleanText.length && cleanText[i+1] != '*' -> {
                        val endIndex = cleanText.indexOf('*', i + 1)
                        if (endIndex != -1 && endIndex > i + 1) {
                            val content = cleanText.substring(i + 1, endIndex)
                            val start = spannable.length
                            spannable.append(content)
                            spannable.setSpan(StyleSpan(Typeface.ITALIC), start, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            i = endIndex + 1
                        } else {
                            spannable.append(cleanText[i])
                            i++
                        }
                    }
                    // Code: `text`
                    cleanText[i] == '`' -> {
                        val endIndex = cleanText.indexOf('`', i + 1)
                        if (endIndex != -1) {
                            val content = cleanText.substring(i + 1, endIndex)
                            val start = spannable.length
                            spannable.append(content)
                            spannable.setSpan(ForegroundColorSpan(0xFFDC143C.toInt()), start, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            spannable.setSpan(android.text.style.BackgroundColorSpan(0xFF2A2A2A.toInt()), start, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            i = endIndex + 1
                        } else {
                            spannable.append(cleanText[i])
                            i++
                        }
                    }
                    else -> {
                        spannable.append(cleanText[i])
                        i++
                    }
                }
            }
            
            return spannable
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Message", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}
