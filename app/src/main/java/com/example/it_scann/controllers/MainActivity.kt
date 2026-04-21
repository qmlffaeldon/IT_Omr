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
import android.content.ContentUris

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
        if (exams.isEmpty()) {
            Log.e("ExportCSV", "No exams to export.")
            return
        }

        // Extract batch details from the first exam
        val firstExam = exams.first().exam
        val rawDate = firstExam.examDate ?: ""
        val regionAbbr = firstExam.region ?: ""
        val rawPlace = if (firstExam.placeOfExam.isNullOrEmpty()) "Unknown Place" else firstExam.placeOfExam

        // Convert Date from "MM/dd/yyyy" to "dd MMMM yyyy"
        var formattedFileDate = "Unknown Date"

        try {
            if (rawDate.isNotEmpty()) {
                val parsedDate = SimpleDateFormat("MM/dd/yyyy", Locale.US).parse(rawDate)
                if (parsedDate != null) {
                    formattedFileDate = SimpleDateFormat("dd MMMM yyyy", Locale.US).format(parsedDate)
                }
            }
        } catch (e: Exception) {
            Log.e("ExportCSV", "Date parsing failed, falling back to current date.", e)
        }

        // Expand Region abbreviation back to Full Version
        val fullRegion = when (regionAbbr.uppercase()) {
            "BARMM", "CAR", "NIR" -> regionAbbr
            "NCR" -> "Region NCR"
            "CO" -> "Central Office"
            "" -> "Unknown Region"
            else -> if (regionAbbr.contains("REGION", true)) regionAbbr else "Region $regionAbbr"
        }

        // Construct the requested filename
        val fileName = "Exam Results ($formattedFileDate) - $fullRegion, $rawPlace.csv"
        val relativePath = Environment.DIRECTORY_DOCUMENTS + "/ROEC_ExamResults"

        val resolver = context.contentResolver
        val collectionUri = MediaStore.Files.getContentUri("external")

        // --- NEW: Check for existing file and delete it ---
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(fileName, "$relativePath%")

        try {
            resolver.query(collectionUri, arrayOf(MediaStore.MediaColumns._ID), selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val existingId = cursor.getLong(idColumn)
                    val existingUri = ContentUris.withAppendedId(collectionUri, existingId)
                    resolver.delete(existingUri, null, null)
                    Log.d("ExportCSV", "Deleted old duplicate file to overwrite.")
                }
            }
        } catch (e: Exception) {
            Log.e("ExportCSV", "Error checking for duplicate file", e)
        }
        // --------------------------------------------------

        // Insert the brand new file
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }

        val uri = resolver.insert(collectionUri, contentValues)

        uri?.let { fileUri ->
            resolver.openOutputStream(fileUri)?.use { outputStream ->
                val writer = outputStream.bufferedWriter()

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

                    // Extract and populate row data from database entity
                    val seatNumber = exam.seatNumber
                    val setNumber = exam.setNumber ?: ""
                    val region = exam.region ?: ""
                    val place = if (exam.placeOfExam.isNullOrEmpty()) "" else exam.placeOfExam.replace(",", "")
                    val date = exam.examDate ?: ""
                    val examType = exam.examCode
                    val completeRow = ""

                    // Write the full formatted row
                    writer.write("$seatNumber,$setNumber,$region,$place,$date,$examType,$elemScores,$codeScore,$completeRow\n")
                }

                writer.flush()
            }
        }

        Log.d("ExportCSV", "Exported batch: $fileName")

        // Cast context if necessary depending on where this function is housed
        // e.g., AlertDialog.Builder(context as Activity)
        AlertDialog.Builder(context)
            .setTitle("Results Exported")
            .setMessage("Exported to Documents/ROEC_ExamResults")
            .setPositiveButton("OK", null)
            .show()
    }
}