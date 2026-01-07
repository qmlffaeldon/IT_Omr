package com.example.it_scann

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.opencv.android.OpenCVLoader
import android.content.Intent

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        installSplashScreen()
        setContentView(R.layout.home_activity)

        Log.d("MainActivity", "OpenCV init: ${OpenCVLoader.initDebug()}")

        findViewById<Button>(R.id.btn_scan).setOnClickListener {
            Log.d("MainActivity", "Scan button clicked")
            startActivity(Intent(this, camera_scan::class.java))
        }

        findViewById<Button>(R.id.btn_results).setOnClickListener {
            Log.d("MainActivity", "Scan button clicked")
            startActivity(Intent(this, Answer_key::class.java))
        }

    }
}
