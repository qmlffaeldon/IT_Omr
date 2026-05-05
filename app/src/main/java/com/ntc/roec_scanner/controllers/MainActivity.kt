@file:Suppress("DEPRECATION")

package com.ntc.roec_scanner.controllers

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.ntc.roec_scanner.R
import com.ntc.roec_scanner.database.AnswerKeyImporter
import com.ntc.roec_scanner.database.AppDatabase
import com.ntc.roec_scanner.modules.GoogleDriveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.net.URL
import androidx.core.graphics.drawable.toDrawable

class MainActivity : AppCompatActivity() {

    private lateinit var driveManager: GoogleDriveManager
    private lateinit var loginButton: MaterialButton

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    updateUIWithAccount(account)
                    Toast.makeText(this, "Signed in successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiException) {
                Log.e("GoogleDrive", "Sign-in failed", e)
                Toast.makeText(this, "Sign-in failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        installSplashScreen()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_home_activity)

        Log.d("MainActivity", "OpenCV init: ${OpenCVLoader.initDebug()}")

        findViewById<Button>(R.id.btn_scan).setOnClickListener {
            Log.d("MainActivity", "Scan button clicked")
            startActivity(Intent(this, CameraScanActivity::class.java))
        }

        findViewById<Button>(R.id.btn_answers).setOnClickListener {
            Log.d("MainActivity", "answerkey button clicked")
            //startActivity(Intent(this, Answer_key::class.java))
            pickExcelFile.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        }

        findViewById<Button>(R.id.btn_results).setOnClickListener {
            Log.d("MainActivity", "Exam Results clicked")
            startActivity(Intent(this, ResultsActivity::class.java))
        }

        driveManager = GoogleDriveManager(this)

        loginButton = findViewById(R.id.btn_google_login)

        // Check if already signed in on app launch
        val existingAccount = driveManager.getSignedInAccount()
        if (existingAccount != null) {
            updateUIWithAccount(existingAccount)
        }

        loginButton.setOnClickListener {
            val currentAccount = driveManager.getSignedInAccount()

            if (currentAccount != null) {
                // THE USER IS LOGGED IN -> LOG THEM OUT
                driveManager.signOut {
                    updateUIForLoggedOut()
                    Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
                }
            } else {
                // THE USER IS LOGGED OUT -> LOG THEM IN
                val signInIntent = driveManager.getSignInClient().signInIntent
                signInLauncher.launch(signInIntent)
            }
        }
    }

    private fun updateUIWithAccount(account: GoogleSignInAccount) {
        // 1. Set the user's full name
        loginButton.text = account.displayName ?: "Connected to Google"

        // 2. Fetch the profile picture in the background and set it as the button icon
        val photoUrl = account.photoUrl
        if (photoUrl != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val stream = URL(photoUrl.toString()).openStream()
                    val bitmap = BitmapFactory.decodeStream(stream)
                    val drawable = bitmap.toDrawable(resources)

                    withContext(Dispatchers.Main) {
                        loginButton.icon = drawable
                        loginButton.iconSize = 72 // Adjust size as needed
                        loginButton.iconPadding = 16
                    }
                } catch (e: Exception) {
                    Log.e("GoogleDrive", "Failed to load profile picture", e)
                }
            }
        } else {
            loginButton.setIconResource(R.drawable.ic_check) // Fallback icon if no profile pic
        }
    }

    private fun updateUIForLoggedOut() {
        // Reset the button to its default state
        loginButton.text = "Sign in with Google"
        loginButton.icon = null
    }

    private val pickExcelFile = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val db = AppDatabase.getDatabase(this@MainActivity)
                val result =
                    AnswerKeyImporter.importFromUri(this@MainActivity, it, db.answerKeyDao())

                if (result.success) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Import Successful")
                        .setMessage("${result.rowsImported} exam types imported\n${result.entriesInserted} answer keys stored")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Import Failed")
                        .setMessage(result.error)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }
}