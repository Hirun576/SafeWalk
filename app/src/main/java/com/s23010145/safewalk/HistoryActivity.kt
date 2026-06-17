package com.s23010145.safewalk

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

data class Incident(
    val id: String = "",
    val type: String = "",
    val timestamp: String = "",
    val location: String = "",
    val alertedContacts: String = ""
)

class HistoryActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var btnBack: ImageButton
    private lateinit var btnClearAll: ImageButton
    private lateinit var recyclerHistory: RecyclerView
    private lateinit var layoutEmpty: LinearLayout

    private val incidents = mutableListOf<Incident>()
    private lateinit var adapter: IncidentsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        auth = FirebaseAuth.getInstance()
        db   = FirebaseFirestore.getInstance()

        initViews()
        setupRecycler()
        loadIncidents()
        setClickListeners()
        setupBackHandler()
    }

    // Init
    private fun initViews() {
        btnBack        = findViewById(R.id.btnBack)
        btnClearAll    = findViewById(R.id.btnClearAll)
        recyclerHistory= findViewById(R.id.recyclerHistory)
        layoutEmpty    = findViewById(R.id.layoutEmpty)
    }

    private fun setupRecycler() {
        adapter = IncidentsAdapter(incidents) { incident -> confirmDelete(incident) }
        recyclerHistory.layoutManager = LinearLayoutManager(this)
        recyclerHistory.adapter = adapter
    }

    // Load incidents from Firestore — newest first
    private fun loadIncidents() {
        val uid = auth.currentUser?.uid ?: return

        db.collection("users").document(uid)
            .collection("incidents")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Toast.makeText(this, "Failed to load history: ${error.message}", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }

                incidents.clear()
                snapshot?.documents?.forEach { doc ->
                    incidents.add(
                        Incident(
                            id              = doc.id,
                            type            = doc.getString("type")            ?: "SOS Alert",
                            timestamp       = doc.getString("timestamp")       ?: "",
                            location        = doc.getString("location")        ?: "Location unavailable",
                            alertedContacts = doc.getString("alertedContacts") ?: "None"
                        )
                    )
                }

                adapter.notifyDataSetChanged()
                layoutEmpty.visibility    = if (incidents.isEmpty()) View.VISIBLE else View.GONE
                recyclerHistory.visibility= if (incidents.isEmpty()) View.GONE    else View.VISIBLE
            }
    }

    // Click listeners
    private fun setClickListeners() {
        btnBack.setOnClickListener { finish() }

        btnClearAll.setOnClickListener {
            if (incidents.isEmpty()) {
                Toast.makeText(this, "No incidents to clear.", Toast.LENGTH_SHORT).show()
            } else {
                confirmClearAll()
            }
        }
    }

    // Delete single incident
    private fun confirmDelete(incident: Incident) {
        AlertDialog.Builder(this)
            .setTitle("Delete Incident")
            .setMessage("Remove this incident from history?")
            .setPositiveButton("Delete") { _, _ -> deleteIncident(incident) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteIncident(incident: Incident) {
        val uid = auth.currentUser?.uid ?: return

        db.collection("users").document(uid)
            .collection("incidents")
            .document(incident.id)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(this, "Incident removed.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to delete: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // Clear all incidents
    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear All History")
            .setMessage("This will permanently delete all ${incidents.size} incident(s). This cannot be undone.")
            .setPositiveButton("Clear All") { _, _ -> deleteAllIncidents() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteAllIncidents() {
        val uid = auth.currentUser?.uid ?: return
        val batch = db.batch()

        incidents.forEach { incident ->
            val ref = db.collection("users").document(uid)
                .collection("incidents")
                .document(incident.id)
            batch.delete(ref)
        }

        batch.commit()
            .addOnSuccessListener {
                Toast.makeText(this, "All incidents cleared.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to clear: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // Back handler
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }
}

// ======================================================================
// IncidentsAdapter
class IncidentsAdapter(
    private val incidents: List<Incident>,
    private val onDelete: (Incident) -> Unit
) : RecyclerView.Adapter<IncidentsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView    = view.findViewById(R.id.tvIncidentTitle)
        val tvDate: TextView     = view.findViewById(R.id.tvIncidentDate)
        val tvLocation: TextView = view.findViewById(R.id.tvIncidentLocation)
        val tvContacts: TextView = view.findViewById(R.id.tvIncidentContacts)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteIncident)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_incident, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val incident = incidents[position]
        holder.tvTitle.text    = incident.type
        holder.tvDate.text     = incident.timestamp
        holder.tvLocation.text = incident.location
        holder.tvContacts.text = "Alerted: ${incident.alertedContacts}"
        holder.btnDelete.setOnClickListener { onDelete(incident) }
    }

    override fun getItemCount() = incidents.size
}