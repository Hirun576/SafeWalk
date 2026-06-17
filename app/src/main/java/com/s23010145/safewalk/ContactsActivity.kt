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
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

data class EmergencyContact(
    val id: String = "",
    val name: String = "",
    val phone: String = "",
    val relation: String = ""
)

class ContactsActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var btnBack: ImageButton
    private lateinit var recyclerContacts: RecyclerView
    private lateinit var fabAddContact: FloatingActionButton
    private lateinit var layoutEmpty: LinearLayout

    private val contacts = mutableListOf<EmergencyContact>()
    private lateinit var adapter: ContactsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)

        auth = FirebaseAuth.getInstance()
        db   = FirebaseFirestore.getInstance()

        initViews()
        setupRecycler()
        loadContacts()
        setClickListeners()
        setupBackHandler()
    }

    // Init
    private fun initViews() {
        btnBack         = findViewById(R.id.btnBack)
        recyclerContacts= findViewById(R.id.recyclerContacts)
        fabAddContact   = findViewById(R.id.fabAddContact)
        layoutEmpty     = findViewById(R.id.layoutEmpty)
    }

    private fun setupRecycler() {
        adapter = ContactsAdapter(contacts) { contact -> confirmDelete(contact) }
        recyclerContacts.layoutManager = LinearLayoutManager(this)
        recyclerContacts.adapter = adapter
    }

    // Load contacts from Firestore
    private fun loadContacts() {
        val uid = auth.currentUser?.uid ?: return

        db.collection("users").document(uid)
            .collection("emergency_contacts")
            .orderBy("name")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Toast.makeText(this, "Failed to load contacts: ${error.message}", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }

                contacts.clear()
                snapshot?.documents?.forEach { doc ->
                    contacts.add(
                        EmergencyContact(
                            id       = doc.id,
                            name     = doc.getString("name")     ?: "",
                            phone    = doc.getString("phone")    ?: "",
                            relation = doc.getString("relation") ?: ""
                        )
                    )
                }

                adapter.notifyDataSetChanged()
                layoutEmpty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
            }
    }

    // Click listeners
    private fun setClickListeners() {
        btnBack.setOnClickListener { finish() }
        fabAddContact.setOnClickListener { showAddContactDialog() }
    }

    // Add contact dialog
    private fun showAddContactDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact, null)
        val tilName    = dialogView.findViewById<TextInputLayout>(R.id.tilName)
        val tilPhone   = dialogView.findViewById<TextInputLayout>(R.id.tilPhone)
        val etName     = dialogView.findViewById<TextInputEditText>(R.id.etName)
        val etPhone    = dialogView.findViewById<TextInputEditText>(R.id.etPhone)
        val etRelation = dialogView.findViewById<TextInputEditText>(R.id.etRelation)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name     = etName.text.toString().trim()
                val phone    = etPhone.text.toString().trim()
                val relation = etRelation.text.toString().trim()

                // Validate
                if (name.isEmpty()) {
                    tilName.error = "Name is required"
                    return@setPositiveButton
                }
                if (phone.isEmpty()) {
                    tilPhone.error = "Phone number is required"
                    return@setPositiveButton
                }

                saveContact(name, phone, relation)
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dialog.show()
            }
    }

    // Save contact to Firestore
    private fun saveContact(name: String, phone: String, relation: String) {
        val uid = auth.currentUser?.uid ?: return

        val contact = hashMapOf(
            "name"      to name,
            "phone"     to phone,
            "relation"  to relation,
            "createdAt" to Timestamp.now()
        )

        db.collection("users").document(uid)
            .collection("emergency_contacts")
            .add(contact)
            .addOnSuccessListener {
                Toast.makeText(this, "$name added as emergency contact.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to save contact: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // Delete contact
    private fun confirmDelete(contact: EmergencyContact) {
        AlertDialog.Builder(this)
            .setTitle("Remove Contact")
            .setMessage("Remove ${contact.name} from emergency contacts?")
            .setPositiveButton("Remove") { _, _ -> deleteContact(contact) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteContact(contact: EmergencyContact) {
        val uid = auth.currentUser?.uid ?: return

        db.collection("users").document(uid)
            .collection("emergency_contacts")
            .document(contact.id)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(this, "${contact.name} removed.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to remove: ${e.message}", Toast.LENGTH_SHORT).show()
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
// ContactsAdapter
class ContactsAdapter(
    private val contacts: List<EmergencyContact>,
    private val onDelete: (EmergencyContact) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView     = view.findViewById(R.id.tvContactName)
        val tvPhone: TextView    = view.findViewById(R.id.tvContactPhone)
        val tvRelation: TextView = view.findViewById(R.id.tvContactRelation)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteContact)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contacts[position]
        holder.tvName.text     = contact.name
        holder.tvPhone.text    = contact.phone
        holder.tvRelation.text = contact.relation.ifEmpty { "Contact" }
        holder.btnDelete.setOnClickListener { onDelete(contact) }
    }

    override fun getItemCount() = contacts.size
}