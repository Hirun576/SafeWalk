package com.s23010145.safewalk

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import de.hdodenhof.circleimageview.CircleImageView

class HomeActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    // Top bar
    private lateinit var btnDrawer: ImageButton

    // Home content
    private lateinit var tvUsername: TextView
    private lateinit var cardStartWalk: CardView
    private lateinit var cardEmergencySOS: CardView
    private lateinit var cardContacts: CardView
    private lateinit var cardHistory: CardView

    // Drawer
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerHeader: LinearLayout
    private lateinit var drawerProfile: LinearLayout
    private lateinit var drawerSettings: LinearLayout
    private lateinit var drawerLogout: LinearLayout
    private lateinit var imgProfilePic: CircleImageView
    private lateinit var tvDrawerUsername: TextView
    private lateinit var tvDrawerEmail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        auth = FirebaseAuth.getInstance()
        db   = FirebaseFirestore.getInstance()

        // Safety check — if somehow not logged in, send back to Login
        if (auth.currentUser == null) {
            goToLogin()
            return
        }

        initViews()
        setClickListeners()
        setupBackHandler()
        loadUserData()
    }

    // ------------------------------------------------------------------
    // Init
    // ------------------------------------------------------------------
    private fun initViews() {
        drawerLayout     = findViewById(R.id.drawerLayout)
        btnDrawer        = findViewById(R.id.btnDrawer)
        tvUsername       = findViewById(R.id.tvUsername)
        cardStartWalk    = findViewById(R.id.cardStartWalk)
        cardEmergencySOS = findViewById(R.id.cardEmergencySOS)
        cardContacts     = findViewById(R.id.cardContacts)
        cardHistory      = findViewById(R.id.cardHistory)

        // Drawer views
        drawerHeader     = findViewById(R.id.drawerHeader)
        drawerProfile    = findViewById(R.id.drawerProfile)
        drawerSettings   = findViewById(R.id.drawerSettings)
        drawerLogout     = findViewById(R.id.drawerLogout)
        imgProfilePic    = findViewById(R.id.imgProfilePic)
        tvDrawerUsername = findViewById(R.id.tvDrawerUsername)
        tvDrawerEmail    = findViewById(R.id.tvDrawerEmail)
    }

    // ------------------------------------------------------------------
    // Click listeners
    // ------------------------------------------------------------------
    private fun setClickListeners() {

        // Hamburger opens drawer — use GravityCompat.START (not Gravity.START)
        btnDrawer.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Main action cards
        cardStartWalk.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }

        cardEmergencySOS.setOnClickListener {
            startActivity(Intent(this, EmergencyActivity::class.java))
        }

        cardContacts.setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }

        cardHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Drawer — Profile header tap
        drawerHeader.setOnClickListener {
            drawerLayout.closeDrawers()
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        // Drawer — Profile item
        drawerProfile.setOnClickListener {
            drawerLayout.closeDrawers()
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        // Drawer — Settings
        drawerSettings.setOnClickListener {
            drawerLayout.closeDrawers()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Drawer — Log Out
        drawerLogout.setOnClickListener {
            drawerLayout.closeDrawers()
            confirmLogout()
        }
    }

    // ------------------------------------------------------------------
    // Back press — close drawer if open, otherwise default behaviour
    // Uses OnBackPressedDispatcher (replaces deprecated onBackPressed)
    // ------------------------------------------------------------------
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    // Re-enable default back behaviour (e.g. exit app)
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // Load username + email from Firestore
    // ------------------------------------------------------------------
    private fun loadUserData() {
        val uid = auth.currentUser?.uid ?: return

        db.collection("users").document(uid)
            .get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val username = document.getString("username") ?: "User"
                    val email    = document.getString("email") ?: ""

                    tvUsername.text       = username
                    tvDrawerUsername.text = username
                    tvDrawerEmail.text    = email
                }
            }
            .addOnFailureListener {
                tvUsername.text = "User"
            }
    }

    // ------------------------------------------------------------------
    // Logout confirmation
    // ------------------------------------------------------------------
    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Log Out")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Log Out") { _, _ ->
                auth.signOut()
                goToLogin()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun goToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}