package com.runanywhere.startup_hackathon20.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runanywhere.startup_hackathon20.ChatViewModel
import com.runanywhere.startup_hackathon20.R
import com.runanywhere.startup_hackathon20.ViewModelFactory
import com.runanywhere.startup_hackathon20.domain.model.ChatSession
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private val chatViewModel: ChatViewModel by activityViewModels { ViewModelFactory() }
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var btnClearAll: MaterialButton
    
    private val adapter = ChatSessionAdapter(
        onSessionClick = { session ->
            chatViewModel.loadSession(session.id)
            // Navigate to chat tab
            (activity as? MainActivity)?.navigateToChat()
        },
        onDeleteClick = { session ->
            showDeleteConfirmation(session)
        }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView = view.findViewById(R.id.chatSessionsRecyclerView)
        emptyState = view.findViewById(R.id.emptyState)
        btnClearAll = view.findViewById(R.id.btnClearAll)
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        
        btnClearAll.setOnClickListener {
            showClearAllConfirmation()
        }
        
        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.chatSessions.collectLatest { sessions ->
                adapter.submitList(sessions)
                
                if (sessions.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    emptyState.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyState.visibility = View.GONE
                }
            }
        }
    }

    private fun showDeleteConfirmation(session: ChatSession) {
        MaterialAlertDialogBuilder(requireContext(), R.style.AlertDialogTheme)
            .setTitle("Delete Chat")
            .setMessage("Are you sure you want to delete this chat?")
            .setPositiveButton("Delete") { dialog, _ ->
                chatViewModel.deleteSession(session.id)
                Toast.makeText(requireContext(), "Chat deleted", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showClearAllConfirmation() {
        MaterialAlertDialogBuilder(requireContext(), R.style.AlertDialogTheme)
            .setTitle("Clear All History")
            .setMessage("Are you sure you want to delete all chat history? This cannot be undone.")
            .setPositiveButton("Clear All") { dialog, _ ->
                chatViewModel.clearAllSessions()
                Toast.makeText(requireContext(), "All history cleared", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
