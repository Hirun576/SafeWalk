package com.s23010145.safewalk

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import de.hdodenhof.circleimageview.CircleImageView

class ProfileActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var btnBack: ImageButton
    private lateinit var imgAvatar: CircleImageView
    private lateinit var tvProfileUsername: TextView
    private lateinit var tvProfileEmail: TextView
    private lateinit var tvUsername: TextView
    private lateinit var tvEmail: TextView
    private lateinit var tvPhone: TextView
    private lateinit var rowUsername: LinearLayout
    private lateinit var rowEmail: LinearLayout
    private lateinit var rowPhone: LinearLayout
    private lateinit var rowChangePassword: LinearLayout
    private lateinit var rowLogout: LinearLayout

    // Cached profile data
    private var currentUsername = ""
    private var currentEmail    = ""
    private var currentPhone    = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        auth = FirebaseAuth.getInstance()
        db   = FirebaseFirestore.getInstance()

        initViews()
        loadProfile()
        setClickListeners()
        setupBackHandler()
    }


    // Init
    private fun initViews() {
        btnBack            = findViewById(R.id.btnBack)
        imgAvatar          = findViewById(R.id.imgAvatar)
        tvProfileUsername  = findViewById(R.id.tvProfileUsername)
        tvProfileEmail     = findViewById(R.id.tvProfileEmail)
        tvUsername         = findViewById(R.id.tvUsername)
        tvEmail            = findViewById(R.id.tvEmail)
        tvPhone            = findViewById(R.id.tvPhone)
        rowUsername        = findViewById(R.id.rowUsername)
        rowEmail           = findViewById(R.id.rowEmail)
        rowPhone           = findViewById(R.id.rowPhone)
        rowChangePassword  = findViewById(R.id.rowChangePassword)
        rowLogout          = findViewById(R.id.rowLogout)
    }


    // Load profile from Firestore
    private fun loadProfile() {
        val uid = auth.currentUser?.uid ?: return

        db.collection("users").document(uid)
            .get()
            .addOnSuccessListener { doc ->
                if (doc != null && doc.exists()) {
                    currentUsername = doc.getString("username") ?: ""
                    currentEmail    = doc.getString("email")    ?: auth.currentUser?.email ?: ""
                    currentPhone    = doc.getString("phone")    ?: ""

                    tvProfileUsername.text = currentUsername
                    tvProfileEmail.text    = currentEmail
                    tvUsername.text        = currentUsername.ifEmpty { "Not set" }
                    tvEmail.text           = currentEmail.ifEmpty { "Not set" }
                    tvPhone.text           = currentPhone.ifEmpty { "Not set" }
                }
            }
            .addOnFailureListener {
                // Fall back to Firebase Auth email
                currentEmail       = auth.currentUser?.email ?: ""
                tvProfileEmail.text = currentEmail
                tvEmail.text        = currentEmail
            }
    }


    // Click listeners
    private fun setClickListeners() {

        btnBack.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }

        rowUsername.setOnClickListener { showEditUsernameDialog() }
        rowEmail.setOnClickListener    { showEditEmailDialog()    }
        rowPhone.setOnClickListener    { showEditPhoneDialog()    }
        rowChangePassword.setOnClickListener { showChangePasswordDialog() }
        rowLogout.setOnClickListener   { confirmLogout()          }
    }


    // Edit Username
    private fun showEditUsernameDialog() {
        val view   = LayoutInflater.from(this).inflate(R.layout.dialog_edit_field, null)
        val til    = view.findViewById<TextInputLayout>(R.id.tilField)
        val et     = view.findViewById<TextInputEditText>(R.id.etField)
        til.hint   = "New Username"
        et.setText(currentUsername)
        et.inputType = InputType.TYPE_TEXT_VARIATION_PERSON_NAME

        AlertDialog.Builder(this)
            .setTitle("Change Username")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val newUsername = et.text.toString().trim()
                when {
                    newUsername.isEmpty()  -> til.error = "Username cannot be empty"
                    newUsername.length < 3 -> til.error = "At least 3 characters"
                    !newUsername.matches(Regex("^[a-zA-Z0-9_]+$")) ->
                        til.error = "Only letters, numbers and underscores"
                    else -> updateUsername(newUsername)
                }
            }
            .setNegativeButton("Cancel", null)
            .create().also {
                it.window?.setBackgroundDrawableResource(android.R.color.transparent)
                it.show()
            }
    }

    private fun updateUsername(newUsername: String) {
        val uid = auth.currentUser?.uid ?: return

        // Check uniqueness first
        db.collection("users")
            .whereEqualTo("username", newUsername)
            .limit(1)
            .get()
            .addOnSuccessListener { docs ->
                if (!docs.isEmpty && docs.documents[0].id != uid) {
                    Toast.makeText(this, "Username already taken.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                db.collection("users").document(uid)
                    .update("username", newUsername)
                    .addOnSuccessListener {
                        currentUsername = newUsername
                        tvProfileUsername.text = newUsername
                        tvUsername.text        = newUsername
                        Toast.makeText(this, "Username updated.", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
    }


    // Edit Email
    // Firebase requires re-authentication before changing email.
    private fun showEditEmailDialog() {
        val view  = LayoutInflater.from(this).inflate(R.layout.dialog_edit_email, null)
        val tilNew  = view.findViewById<TextInputLayout>(R.id.tilNewEmail)
        val tilPass = view.findViewById<TextInputLayout>(R.id.tilCurrentPassword)
        val etNew   = view.findViewById<TextInputEditText>(R.id.etNewEmail)
        val etPass  = view.findViewById<TextInputEditText>(R.id.etCurrentPassword)
        etNew.setText(currentEmail)

        AlertDialog.Builder(this)
            .setTitle("Change Email")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val newEmail  = etNew.text.toString().trim()
                val password  = etPass.text.toString().trim()

                if (newEmail.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) {
                    tilNew.error = "Enter a valid email"
                    return@setPositiveButton
                }
                if (password.isEmpty()) {
                    tilPass.error = "Current password required"
                    return@setPositiveButton
                }

                reAuthAndUpdateEmail(newEmail, password)
            }
            .setNegativeButton("Cancel", null)
            .create().also {
                it.window?.setBackgroundDrawableResource(android.R.color.transparent)
                it.show()
            }
    }

    private fun reAuthAndUpdateEmail(newEmail: String, password: String) {
        val user = auth.currentUser ?: return
        val credential = EmailAuthProvider.getCredential(user.email ?: "", password)

        user.reauthenticate(credential)
            .addOnSuccessListener {
                // Update Firebase Auth email
                user.updateEmail(newEmail)
                    .addOnSuccessListener {
                        // Update Firestore
                        db.collection("users").document(user.uid)
                            .update("email", newEmail)
                            .addOnSuccessListener {
                                currentEmail           = newEmail
                                tvProfileEmail.text    = newEmail
                                tvEmail.text           = newEmail
                                Toast.makeText(this, "Email updated.", Toast.LENGTH_SHORT).show()
                            }
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Email update failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Incorrect password.", Toast.LENGTH_SHORT).show()
            }
    }


    // Edit Phone
    private fun showEditPhoneDialog() {
        val view  = LayoutInflater.from(this).inflate(R.layout.dialog_edit_field, null)
        val til   = view.findViewById<TextInputLayout>(R.id.tilField)
        val et    = view.findViewById<TextInputEditText>(R.id.etField)
        til.hint  = "Phone Number"
        et.setText(currentPhone)
        et.inputType = InputType.TYPE_CLASS_PHONE

        AlertDialog.Builder(this)
            .setTitle("Update Phone")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val newPhone = et.text.toString().trim()
                updatePhone(newPhone)
            }
            .setNegativeButton("Cancel", null)
            .create().also {
                it.window?.setBackgroundDrawableResource(android.R.color.transparent)
                it.show()
            }
    }

    private fun updatePhone(phone: String) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid)
            .update("phone", phone)
            .addOnSuccessListener {
                currentPhone = phone
                tvPhone.text = phone.ifEmpty { "Not set" }
                Toast.makeText(this, "Phone updated.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }


    // Change Password
    // Firebase requires re-authentication before changing password.
    private fun showChangePasswordDialog() {
        val view      = LayoutInflater.from(this).inflate(R.layout.dialog_change_password, null)
        val tilCurrent= view.findViewById<TextInputLayout>(R.id.tilCurrentPassword)
        val tilNew    = view.findViewById<TextInputLayout>(R.id.tilNewPassword)
        val tilConfirm= view.findViewById<TextInputLayout>(R.id.tilConfirmPassword)
        val etCurrent = view.findViewById<TextInputEditText>(R.id.etCurrentPassword)
        val etNew     = view.findViewById<TextInputEditText>(R.id.etNewPassword)
        val etConfirm = view.findViewById<TextInputEditText>(R.id.etConfirmPassword)

        AlertDialog.Builder(this)
            .setTitle("Change Password")
            .setView(view)
            .setPositiveButton("Update") { _, _ ->
                val current = etCurrent.text.toString()
                val newPass = etNew.text.toString()
                val confirm = etConfirm.text.toString()

                when {
                    current.isEmpty() -> tilCurrent.error = "Enter current password"
                    newPass.length < 6 -> tilNew.error = "At least 6 characters"
                    newPass != confirm  -> tilConfirm.error = "Passwords do not match"
                    else -> reAuthAndUpdatePassword(current, newPass)
                }
            }
            .setNegativeButton("Cancel", null)
            .create().also {
                it.window?.setBackgroundDrawableResource(android.R.color.transparent)
                it.show()
            }
    }

    private fun reAuthAndUpdatePassword(currentPassword: String, newPassword: String) {
        val user = auth.currentUser ?: return
        val credential = EmailAuthProvider.getCredential(user.email ?: "", currentPassword)

        user.reauthenticate(credential)
            .addOnSuccessListener {
                user.updatePassword(newPassword)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Password updated successfully.", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Password update failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Incorrect current password.", Toast.LENGTH_SHORT).show()
            }
    }


    // Log out
    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Log Out")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Log Out") { _, _ ->
                auth.signOut()
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    // Back handler
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                startActivity(Intent(this@ProfileActivity, HomeActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        })
    }
}