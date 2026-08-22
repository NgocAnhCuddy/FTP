package com.myfile.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.myfile.ui.databinding.FragmentClientBinding
import com.myfile.ui.model.FtpConnectionProfile
import com.myfile.ui.FileBrowserActivity
import com.myfile.ui.FtpConnectionActivity
import com.myfile.ui.adapters.ConnectionAdapter
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.SecurePrefs

class ClientFragment : Fragment() {

    private var _binding: FragmentClientBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: SecurePrefs
    private lateinit var adapter: ConnectionAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentClientBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SecurePrefs.getInstance(requireContext())

        adapter = ConnectionAdapter(
            onClick = { conn -> openFileBrowser(conn) },
            onDelete = { conn ->
                val updated = prefs.loadConnections().filterNot { it.id == conn.id }
                prefs.saveConnections(updated)
                refreshList()
            }
        )
        binding.rvConnections.layoutManager = LinearLayoutManager(requireContext())
        binding.rvConnections.adapter = adapter

        binding.btnNewConnection.setOnClickListener {
            val intent = Intent(requireContext(), FtpConnectionActivity::class.java)
            startActivity(intent)
            activity?.let { ActivityTransitions.forward(it) }
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val list = prefs.loadConnections()
        adapter.submit(list)
        binding.rvConnections.scheduleLayoutAnimation()
        binding.tvEmptyConnections.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openFileBrowser(conn: FtpConnectionProfile) {
        val intent = Intent(requireContext(), FileBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_CONNECTION_ID, conn.id)
        }
        startActivity(intent)
        activity?.let { ActivityTransitions.forward(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
