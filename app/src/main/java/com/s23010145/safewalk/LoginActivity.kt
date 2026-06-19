package com.s23010145.safewalk

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var tilUsername: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: MaterialButton
    private lateinit var btnGoogleLogin: MaterialButton
    private lateinit var btnFacebookLogin: MaterialButton
    private lateinit var tvSignUp: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        db   = FirebaseFirestore.getInstance()

        // Already logged in → skip straight to Home
        if (auth.currentUser != null) {
            navigateToHome()
            return
        }

        initViews()
        setClickListeners()
    }

    private fun initViews() {
        tilUsername      = findViewById(R.id.tilUsername)
        tilPassword      = findViewById(R.id.tilPassword)
        etUsername       = findViewById(R.id.etUsername)
        etPassword       = findViewById(R.id.etPassword)
        btnLogin         = findViewById(R.id.btnLogin)
        btnGoogleLogin   = findViewById(R.id.btnGoogleLogin)
        btnFacebookLogin = findViewById(R.id.btnFacebookLogin)
        tvSignUp         = findViewById(R.id.tvSignUp)
    }

    private fun setClickListeners() {

        btnLogin.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()
            if (validateInputs(username, password)) {
                lookupEmailAndLogin(username, password)
            }
        }

        btnGoogleLogin.setOnClickListener {
            Toast.makeText(this, "Google Sign-In coming soon!", Toast.LENGTH_SHORT).show()
        }

        btnFacebookLogin.setOnClickListener {
            Toast.makeText(this, "Facebook Sign-In coming soon!", Toast.LENGTH_SHORT).show()
        }

        tvSignUp.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }


    // Validation
    private fun validateInputs(username: String, password: String): Boolean {
        var isValid = true

        if (username.isEmpty()) {
            tilUsername.error = "Username is required"
            isValid = false
        } else {
            tilUsername.error = null
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

        return isValid
    }


    //Firestore: find the email linked to this username
    private fun lookupEmailAndLogin(username: String, password: String) {
        btnLogin.isEnabled = false
        btnLogin.text = getString(R.string.logging_account)


        db.collection("users")
            .whereEqualTo("username", username)
            .limit(1)
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    btnLogin.isEnabled = true
                    tilUsername.error = "No account found for this username"
                } else {
                    val email = documents.documents[0].getString("email") ?: ""
                    if (email.isEmpty()) {
                        btnLogin.isEnabled = true
                        showError("Account data is missing. Please re-register.")
                    } else {
                        signInWithEmail(email, password)
                    }
                }
            }
            .addOnFailureListener { e ->
                btnLogin.isEnabled = true
                showError("Connection error: ${e.message}")
            }
    }

    //Firebase Auth sign-in
    private fun signInWithEmail(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                btnLogin.isEnabled = true

                if (task.isSuccessful) {
                    navigateToHome()
                } else {
                    showError(task.exception?.message ?: "Login failed. Please try again.")
                }
            }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun navigateToHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}