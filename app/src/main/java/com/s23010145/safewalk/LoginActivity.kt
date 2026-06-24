package com.s23010145.safewalk

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.facebook.AccessToken
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.Timestamp
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var facebookCallbackManager: CallbackManager
    private lateinit var tilUsername: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: MaterialButton
    private lateinit var btnGoogleLogin: MaterialButton
    private lateinit var btnFacebookLogin: MaterialButton
    private lateinit var tvSignUp: TextView

    // ── Google Sign-In result launcher
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            firebaseAuthWithGoogle(account.idToken!!, account.displayName ?: "User", account.email ?: "")
        } catch (e: ApiException) {
            Toast.makeText(this, "Google sign-in failed: ${e.statusCode}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        db   = FirebaseFirestore.getInstance()

        setupGoogleSignIn()
        setupFacebookLogin()

        if (auth.currentUser != null) {
            navigateToHome()
            return
        }

        initViews()
        setClickListeners()
    }

    // Init
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

    // GOOGLE SIGN-IN setup
    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun firebaseAuthWithGoogle(idToken: String, name: String, email: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    ensureUserProfileExists(
                        uid      = user?.uid ?: "",
                        username = name,
                        email    = email
                    )
                } else {
                    Toast.makeText(this, "Authentication failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }


    // FACEBOOK LOGIN setup
    private fun setupFacebookLogin() {
        facebookCallbackManager = CallbackManager.Factory.create()

        LoginManager.getInstance().registerCallback(
            facebookCallbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    firebaseAuthWithFacebook(result.accessToken)
                }

                override fun onCancel() {
                    Toast.makeText(this@LoginActivity, "Facebook login cancelled.", Toast.LENGTH_SHORT).show()
                }

                override fun onError(error: FacebookException) {
                    Toast.makeText(this@LoginActivity, "Facebook login failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun firebaseAuthWithFacebook(token: AccessToken) {
        val credential = FacebookAuthProvider.getCredential(token.token)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    ensureUserProfileExists(
                        uid      = user?.uid ?: "",
                        username = user?.displayName ?: "User",
                        email    = user?.email ?: ""
                    )
                } else {
                    Toast.makeText(this, "Authentication failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    // Shared: create a Firestore profile on first social login only
    private fun ensureUserProfileExists(uid: String, username: String, email: String) {
        if (uid.isEmpty()) {
            navigateToHome()
            return
        }

        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    // Returning user — profile already in Firestore
                    navigateToHome()
                } else {
                    // First-time social login — create the profile document
                    val safeUsername = username.ifEmpty { "User" }
                    val newUser = hashMapOf(
                        "uid"       to uid,
                        "username"  to safeUsername,
                        "email"     to email,
                        "createdAt" to Timestamp.now()
                    )
                    db.collection("users").document(uid)
                        .set(newUser)
                        .addOnSuccessListener { navigateToHome() }
                        .addOnFailureListener {
                            Toast.makeText(this, "Could not save profile: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Profile check failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    // Click listeners
    private fun setClickListeners() {

        btnLogin.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()
            if (validateInputs(username, password)) {
                lookupEmailAndLogin(username, password)
            }
        }

        btnGoogleLogin.setOnClickListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }

        btnFacebookLogin.setOnClickListener {
            LoginManager.getInstance().logInWithReadPermissions(
                this,
                listOf("email", "public_profile")
            )
        }

        tvSignUp.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }


    // Forward the Facebook SDK's activity result to its callback manager
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        facebookCallbackManager.onActivityResult(requestCode, resultCode, data)
    }


    // Validation
    private fun validateInputs(username: String, password: String): Boolean {
        var isValid = true
        if (username.isEmpty()) {
            tilUsername.error = "Username is required"
            isValid = false
        } else tilUsername.error = null

        if (password.isEmpty()) {
            tilPassword.error = "Password is required"
            isValid = false
        } else if (password.length < 6) {
            tilPassword.error = "Password must be at least 6 characters"
            isValid = false
        } else tilPassword.error = null

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
                        Toast.makeText(this, "Account data is missing. Please re-register.", Toast.LENGTH_LONG).show()
                    } else {
                        signInWithEmail(email, password)
                    }
                }
            }
            .addOnFailureListener { e ->
                btnLogin.isEnabled = true
                Toast.makeText(this, "Connection error: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun signInWithEmail(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                btnLogin.isEnabled = true
                if (task.isSuccessful) {
                    navigateToHome()
                } else {
                    Toast.makeText(this, task.exception?.message ?: "Login failed.", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun navigateToHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}