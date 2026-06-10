package com.s23010145.safewalk

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class RegisterActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var tilUsername: TextInputLayout
    private lateinit var tilEmail: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var tilConfirmPassword: TextInputLayout
    private lateinit var etUsername: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etConfirmPassword: TextInputEditText
    private lateinit var btnSignUp: MaterialButton
    private lateinit var tvLogin: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        auth = FirebaseAuth.getInstance()
        db   = FirebaseFirestore.getInstance()

        initViews()
        setClickListeners()
    }

    private fun initViews() {
        tilUsername        = findViewById(R.id.tilUsername)
        tilEmail           = findViewById(R.id.tilEmail)
        tilPassword        = findViewById(R.id.tilPassword)
        tilConfirmPassword = findViewById(R.id.tilConfirmPassword)
        etUsername         = findViewById(R.id.etUsername)
        etEmail            = findViewById(R.id.etEmail)
        etPassword         = findViewById(R.id.etPassword)
        etConfirmPassword  = findViewById(R.id.etConfirmPassword)
        btnSignUp          = findViewById(R.id.btnSignUp)
        tvLogin            = findViewById(R.id.tvLogin)
    }

    private fun setClickListeners() {

        btnSignUp.setOnClickListener {
            val username        = etUsername.text.toString().trim()
            val email           = etEmail.text.toString().trim()
            val password        = etPassword.text.toString().trim()
            val confirmPassword = etConfirmPassword.text.toString().trim()

            if (validateInputs(username, email, password, confirmPassword)) {
                checkUsernameAvailability(username, email, password)
            }
        }

        tvLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    // Validation
    private fun validateInputs(
        username: String,
        email: String,
        password: String,
        confirmPassword: String
    ): Boolean {
        var isValid = true

        if (username.isEmpty()) {
            tilUsername.error = "Username is required"
            isValid = false
        } else if (username.length < 3) {
            tilUsername.error = "Username must be at least 3 characters"
            isValid = false
        } else if (!username.matches(Regex("^[a-zA-Z0-9_]+$"))) {
            tilUsername.error = "Only letters, numbers and underscores allowed"
            isValid = false
        } else {
            tilUsername.error = null
        }

        if (email.isEmpty()) {
            tilEmail.error = "Email is required"
            isValid = false
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.error = "Enter a valid email address"
            isValid = false
        } else {
            tilEmail.error = null
        }

        if (password.isEmpty()) {
            tilPassword.error = "Password is required"
            isValid = false
        } else if (password.length < 6) {
            tilPassword.error = "Password must be at least 6 characters"
            isValid = false
        } else {
            tilPassword.error = null
        }

        if (confirmPassword.isEmpty()) {
            tilConfirmPassword.error = "Please confirm your password"
            isValid = false
        } else if (password != confirmPassword) {
            tilConfirmPassword.error = "Passwords do not match"
            isValid = false
        } else {
            tilConfirmPassword.error = null
        }

        return isValid
    }

    //Check if username is already taken
    private fun checkUsernameAvailability(username: String, email: String, password: String) {
        btnSignUp.isEnabled = false
        btnSignUp.text = getString(R.string.creating_account)

        db.collection("users")
            .whereEqualTo("username", username)
            .limit(1)
            .get()
            .addOnSuccessListener { documents ->
                if (!documents.isEmpty) {
                    btnSignUp.isEnabled = true
                    tilUsername.error = "Username already taken. Try another."
                } else {
                    createAuthAccount(username, email, password)
                }
            }
            .addOnFailureListener { e ->
                btnSignUp.isEnabled = true
                showError("Connection error: ${e.message}")
            }
    }

    //Create Firebase Auth account
    private fun createAuthAccount(username: String, email: String, password: String) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val uid = auth.currentUser?.uid ?: ""
                    saveUserToFirestore(uid, username, email)
                } else {
                    btnSignUp.isEnabled = true
                    showError(task.exception?.message ?: "Registration failed. Please try again.")
                }
            }
    }

    //Save username + email to Firestore
    private fun saveUserToFirestore(uid: String, username: String, email: String) {
        val user = hashMapOf(
            "uid"       to uid,
            "username"  to username,
            "email"     to email,
            "createdAt" to Timestamp.now()
        )

        db.collection("users").document(uid)
            .set(user)
            .addOnSuccessListener {
                btnSignUp.isEnabled = true
                Toast.makeText(this, "Account created successfully!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .addOnFailureListener {
                // Auth was created but Firestore failed — roll back the auth account
                auth.currentUser?.delete()
                btnSignUp.isEnabled = true
                showError("Failed to save profile. Please try again.")
            }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}