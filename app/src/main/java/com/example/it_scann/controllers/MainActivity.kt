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
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.example.it_scann.database.AnswerKeyImporter
import com.example.it_scann.database.AppDatabase
import com.example.it_scann.grading.ExamConfigurations
import com.example.it_scann.database.ExamWithElements
import com.example.it_scann.R
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
            startActivity(Intent(this, CameraScanActivity::class.java))
        }

        findViewById<Button>(R.id.btn_answers).setOnClickListener {
            Log.d("MainActivity", "answerkey button clicked")
            //startActivity(Intent(this, Answer_key::class.java))
            pickExcelFile.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        }

        findViewById<Button>(R.id.btn_results).setOnClickListener {
            Log.d("MainActivity", "Exam Results clicked")
            lifecycleScope.launch {
                val db = AppDatabase.Companion.getDatabase(this@MainActivity)
                val exams = db.answerKeyDao().getAllExamsWithElements()
                exportBatchToCSV(this@MainActivity, exams)
            }
        }
    }


    private val pickExcelFile = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val db = AppDatabase.Companion.getDatabase(this@MainActivity)
                val result = AnswerKeyImporter.importFromUri(this@MainActivity, it, db.answerKeyDao())

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
    fun exportBatchToCSV(context: Context, exams: List<ExamWithElements>) {

        val sdfFile = SimpleDateFormat("yyyy-MM-dd_HH", Locale.getDefault())
        val fileName = "Results_${sdfFile.format(Date())}.csv"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOCUMENTS + "/ROEC_ExamResults"
            )
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)

        uri?.let { fileUri ->
            resolver.openOutputStream(fileUri)?.use { outputStream ->
                val writer = outputStream.bufferedWriter()

                // NEW Header matching the requested format
                writer.write("SeatNumber,SetNumber,Region,Place,Date,ExamType,E1,E2,E3,E4,E5,E6,E7,E8,E9,E10,Code,CompleteRow\n")

                exams.sortedBy { it.exam.seatNumber }.forEach { examWithElements ->
                    val exam             = examWithElements.exam
                    val expectedElements = ExamConfigurations.getTestNumbersForTestType(exam.examCode)
                    val elementMap       = examWithElements.elements.associateBy { it.elementNumber }

                    // E1–E10 columns
                    val elemScores = if (exam.isAbsent) {
                        val firstExpected = expectedElements.filter { it != 99 }.minOrNull()
                        (1..10).joinToString(",") { elemNumber ->
                            when {
                                elemNumber == firstExpected -> "A"
                                elemNumber in expectedElements -> ""
                                else -> ""
                            }
                        }
                    } else {
                        (1..10).joinToString(",") { elemNumber ->
                            if (elemNumber in expectedElements) {
                                elementMap[elemNumber]?.score?.toString() ?: "0"
                            } else ""
                        }
                    }

                    val codeScore = if (exam.isAbsent) {
                        ""
                    } else if (99 in expectedElements) {
                        elementMap[99]?.score?.toString() ?: "0"
                    } else ""

                    // Extract new data
                    val seatNumber = exam.seatNumber
                    val setNumber = exam.setNumber // safely handles if setNumber is null
                    val region = ""
                    val place = ""
                    val date = ""
                    val examType = exam.examCode
                    val completeRow = ""

                    // Write the full formatted row
                    writer.write("$seatNumber,$setNumber,$region,$place,$date,$examType,$elemScores,$codeScore,$completeRow\n")
                }

                writer.flush()
            }
        }

        Log.d("ExportCSV", "Simplified export done")
        AlertDialog.Builder(this@MainActivity) // Cast context if needed, or use original this@MainActivity
            .setTitle("Results Exported")
            .setMessage("Exported to Documents/ROEC_ExamResults")
            .setPositiveButton("OK", null)
            .show()
    }
}