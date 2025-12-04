package com.runanywhere.startup_hackathon20.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runanywhere.startup_hackathon20.R
import com.runanywhere.startup_hackathon20.domain.model.ChatSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatSessionAdapter(
    private val onSessionClick: (ChatSession) -> Unit,
    private val onDeleteClick: (ChatSession) -> Unit
) : ListAdapter<ChatSession, ChatSessionAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.sessionTitle)
        private val tvPreview: TextView = itemView.findViewById(R.id.sessionPreview)
        private val tvDate: TextView = itemView.findViewById(R.id.sessionDate)
        private val tvMessageCount: TextView = itemView.findViewById(R.id.sessionMessageCount)
        private val btnDelete: ImageView = itemView.findViewById(R.id.btnDelete)

        fun bind(session: ChatSession) {
            tvTitle.text = session.title
            tvPreview.text = session.preview
            tvDate.text = formatDate(session.updatedAt)
            
            // Display actual message count
            val count = session.messageCount
            tvMessageCount.text = when {
                count == 0 -> "No messages"
                count == 1 -> "1 message"
                else -> "$count messages"
            }

            itemView.setOnClickListener { onSessionClick(session) }
            btnDelete.setOnClickListener { onDeleteClick(session) }
        }

        private fun formatDate(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            
            return when {
                diff < 60_000 -> "Just now"
                diff < 3600_000 -> "${diff / 60_000} min ago"
                diff < 86400_000 -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
                diff < 604800_000 -> SimpleDateFormat("EEE, h:mm a", Locale.getDefault()).format(Date(timestamp))
                else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ChatSession>() {
        override fun areItemsTheSame(oldItem: ChatSession, newItem: ChatSession) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatSession, newItem: ChatSession) = oldItem == newItem
    }
}
