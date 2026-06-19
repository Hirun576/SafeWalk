package com.s23010145.safewalk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EmergencyActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_TRIGGER = "auto_trigger"
    }

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var btnBack: ImageButton
    private lateinit var tvStatusMessage: TextView
    private lateinit var tvLocationStatus: TextView
    private lateinit var btnSOS: MaterialButton
    private lateinit var cardAlertSent: CardView
    private lateinit var tvAlertDetails: TextView
    private lateinit var tvContactCount: TextView
    private lateinit var cardEmergencyContacts: CardView
    private lateinit var cardEmergencyRecording: CardView

    private var currentLat: Double  = 0.0
    private var currentLng: Double  = 0.0
    private var locationText        = "Location unavailable"
    private var alertAlreadySent    = false

    // Contacts loaded from Firestore — used for both sending and saving
    private var loadedContacts: List<Map<String, String>> = emptyList()

    // Location permission launcher
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) fetchCurrentLocation()
        else tvLocationStatus.text = "Location permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emergency)

        auth = FirebaseAuth.getInstance()
        db   = FirebaseFirestore.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        initViews()
        setClickListeners()
        setupBackHandler()
        checkLocationPermission()
        loadContacts()

        // Auto-triggered from SensorActivity fall detection
        if (intent.getBooleanExtra(EXTRA_AUTO_TRIGGER, false)) {
            tvStatusMessage.text = "⚠️ Fall detected! Sending emergency alert…"
            // Small delay so UI renders first
            btnSOS.postDelayed({ sendSOSAlert() }, 800)
        }
    }

    // Init
    private fun initViews() {
        btnBack                = findViewById(R.id.btnBack)
        tvStatusMessage        = findViewById(R.id.tvStatusMessage)
        tvLocationStatus       = findViewById(R.id.tvLocationStatus)
        btnSOS                 = findViewById(R.id.btnSOS)
        cardAlertSent          = findViewById(R.id.cardAlertSent)
        tvAlertDetails         = findViewById(R.id.tvAlertDetails)
        tvContactCount         = findViewById(R.id.tvContactCount)
        cardEmergencyContacts  = findViewById(R.id.cardEmergencyContacts)
        cardEmergencyRecording = findViewById(R.id.cardEmergencyRecording)
    }

    // Click listeners
    private fun setClickListeners() {
        btnBack.setOnClickListener { finish() }

        btnSOS.setOnClickListener {
            if (!alertAlreadySent) sendSOSAlert()
            else Toast.makeText(this, "Alert already sent.", Toast.LENGTH_SHORT).show()
        }

        cardEmergencyContacts.setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }

        cardEmergencyRecording.setOnClickListener {
            startActivity(Intent(this, RecordingActivity::class.java))
        }
    }

    // Location
    private fun checkLocationPermission() {
        val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            fetchCurrentLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun fetchCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                currentLat   = location.latitude
                currentLng   = location.longitude
                locationText = "Lat: %.5f, Lng: %.5f".format(currentLat, currentLng)
                tvLocationStatus.text = "📍 $locationText"
            } else {
                tvLocationStatus.text = "Unable to get location"
            }
        }
    }

    // Load contacts from Firestore
    private fun loadContacts() {
        val uid = auth.currentUser?.uid ?: return

        db.collection("users").document(uid)
            .collection("emergency_contacts")
            .get()
            .addOnSuccessListener { docs ->
                loadedContacts = docs.map { doc ->
                    mapOf(
                        "name"  to (doc.getString("name")  ?: ""),
                        "phone" to (doc.getString("phone") ?: "")
                    )
                }
                val count = loadedContacts.size
                tvContactCount.text = if (count == 0)
                    "No contacts added yet"
                else
                    "$count contact${if (count > 1) "s" else ""} ready to alert"
            }
    }


    // SEND SOS ALERT
    private fun sendSOSAlert() {
        if (loadedContacts.isEmpty()) {
            Toast.makeText(
                this,
                "No emergency contacts found. Please add contacts first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        alertAlreadySent = true
        btnSOS.isEnabled = false
        btnSOS.text = "Sending…"

        val timestamp  = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val mapsLink   = "https://maps.google.com/?q=$currentLat,$currentLng"
        val message    = "EMERGENCY ALERT!\n" +
                "I need help immediately.\n" +
                "My location: $mapsLink\n" +
                "Time: $timestamp\n" +
                "— Sent via SafeWalk"

        // Save incident to Firestore first (non-blocking)
        val contactNames = loadedContacts.map { it["name"] ?: "" }
        saveIncident(timestamp, contactNames)

        // Open SMS app for first contact — chain the rest after
        openSmsForContacts(loadedContacts, message, timestamp, contactNames)
    }

    // Open system SMS app pre-filled for each contact, one at a time.
    private fun openSmsForContacts(
        contacts: List<Map<String, String>>,
        message: String,
        timestamp: String,
        allNames: List<String>
    ) {
        if (contacts.isEmpty()) {
            // All contacts processed — update UI
            showAlertSentUI(allNames, timestamp)
            return
        }

        val contact = contacts[0]
        val phone   = contact["phone"] ?: ""
        val name    = contact["name"]  ?: "Contact"
        val rest    = contacts.drop(1)

        if (phone.isNotEmpty()) {
            // smsto: URI opens the default SMS app pre-filled — no SMS permission required
            val smsUri = Uri.parse("smsto:$phone")
            val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                putExtra("sms_body", message)
            }

            try {
                startActivity(intent)
                Toast.makeText(this, "Opening SMS for $name — tap Send", Toast.LENGTH_LONG).show()

                // After returning to this activity, open next contact after a short delay
                btnSOS.postDelayed({
                    openSmsForContacts(rest, message, timestamp, allNames)
                }, 2000)

            } catch (e: Exception) {
                Toast.makeText(this, "Could not open SMS app: ${e.message}", Toast.LENGTH_SHORT).show()
                // Skip this contact and continue
                openSmsForContacts(rest, message, timestamp, allNames)
            }
        } else {
            // No phone — skip
            openSmsForContacts(rest, message, timestamp, allNames)
        }
    }

    // Save incident to Firestore (HistoryActivity reads from here)
    private fun saveIncident(timestamp: String, alertedContacts: List<String>) {
        val uid = auth.currentUser?.uid ?: return

        val incident = hashMapOf(
            "type"            to "SOS Alert",
            "timestamp"       to timestamp,
            "location"        to locationText,
            "latitude"        to currentLat,
            "longitude"       to currentLng,
            "alertedContacts" to alertedContacts.joinToString(", "),
            "createdAt"       to Timestamp.now()
        )

        db.collection("users").document(uid)
            .collection("incidents")
            .add(incident)
            .addOnFailureListener { e ->
                Log.e("EmergencyActivity", "Failed to save incident: ${e.message}")
            }
    }


    // Update UI after alerts opened
    private fun showAlertSentUI(contactNames: List<String>, timestamp: String) {
        btnSOS.text      = "✓ SENT"
        btnSOS.isEnabled = false
        cardAlertSent.visibility = View.VISIBLE

        tvAlertDetails.text = buildString {
            append("Alert opened at $timestamp\n")
            append("📍 Location: $locationText\n\n")
            append("SMS opened for:\n")
            contactNames.forEach { append("• $it\n") }
        }

        tvStatusMessage.text = "SMS app opened for ${contactNames.size} contact(s). Tap Send in each."
    }

    // Back handler
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }
}