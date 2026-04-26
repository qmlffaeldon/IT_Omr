package com.example.it_scann.controllers

import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.opencv.android.OpenCVLoader
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.example.it_scann.database.AnswerKeyImporter
import com.example.it_scann.database.AppDatabase
import com.example.it_scann.R
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        installSplashScreen()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.home_activity)

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
    }

    private val pickExcelFile = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val db = AppDatabase.Companion.getDatabase(this@MainActivity)
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