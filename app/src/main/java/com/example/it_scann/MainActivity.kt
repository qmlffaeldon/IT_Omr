package com.example.it_scann

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
import android.os.Environment
import android.provider.MediaStore
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            startActivity(Intent(this, CameraScan::class.java))
        }

        findViewById<Button>(R.id.btn_answers).setOnClickListener {
            Log.d("MainActivity", "Scan button clicked")
            startActivity(Intent(this, Answer_key::class.java))
        }

        findViewById<Button>(R.id.btn_results).setOnClickListener {
            Log.d("MainActivity", "Exam Results clicked")
            lifecycleScope.launch {
                val db = AppDatabase.getDatabase(this@MainActivity)
                val exams = db.answerKeyDao().getAllExamsWithElements()
                exportBatchToCSV(this@MainActivity, exams)
            }
        }
    }

    fun exportBatchToCSV(context: Context, exams: List<ExamWithElements>)  {

        val sdfFile = SimpleDateFormat("yyyy-MM-dd_HH", Locale.getDefault())
        val fileName = "ROEC_${sdfFile.format(Date())}.csv"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOCUMENTS + "/ROEC_ExamResults"
            )
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Files.getContentUri("external"),
            contentValues
        )

        uri?.let { fileUri ->
            resolver.openOutputStream(fileUri)?.use { outputStream ->
                val writer = outputStream.bufferedWriter()

                // HEADER (Always fixed structure)
                writer.write(
                    "SeatNumber,TestType,SetNumber," +
                            "E1,E2,E3,E4,E5,E6,E7,E8,E9,E10," +
                            "TotalScore,DateTaken\n"
                )

                exams.forEach { examWithElements ->

                    val exam = examWithElements.exam

                    // Get expected elements based on TestType
                    val expectedElements =
                        ExamConfigurations.getTestNumbersForTestType(exam.testType)

                    // Map detected element scores by elementNumber
                    val elementMap =
                        examWithElements.elements.associateBy { it.elementNumber }

                    // Always output E1–E10 to prevent shifting
                    val elementScores = (1..10).joinToString(",") { elementNumber ->
                        if (elementNumber in expectedElements) {
                            elementMap[elementNumber]?.score?.toString() ?: "0"
                        } else {
                            ""
                        }
                    }

                    val sdfDisplay = SimpleDateFormat("yyyy-MM-dd_HH:mm", Locale.getDefault())
                    val formattedDate = sdfDisplay.format(Date(exam.dateTaken))

                    writer.write(
                        "${exam.seatNumber}," +
                                "${exam.testType}," +
                                "${exam.setNumber}," +
                                "$elementScores," +
                                "${exam.totalScore}," +
                                "$formattedDate\n"
                    )
                }

                writer.flush()
            }
        }
        Log.d("ExportCSV", "Exported to Documents/ROEC_ExamResults")
        AlertDialog.Builder(this@MainActivity)
            .setTitle("Results Exported to a CSV File")
            .setMessage("Exported to Documents/ROEC_ExamResults")
            .setPositiveButton("OK", null)
            .show()

    }
}