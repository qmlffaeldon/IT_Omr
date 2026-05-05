package com.ntc.roec_scanner.modules

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.ntc.roec_scanner.R
import com.ntc.roec_scanner.database.ExamWithElements
import com.ntc.roec_scanner.grading.ExamConfigurations
import java.text.SimpleDateFormat
import java.util.Locale

fun exportBatchToCSV(context: Context, exams: List<ExamWithElements>) {
    if (exams.isEmpty()) {
        Log.e("ExportCSV", "No exams to export.")
        return
    }

    val firstExam = exams.first().exam
    val rawDate = firstExam.examDate ?: ""
    val regionAbbr = firstExam.region ?: ""
    val rawPlace = if (firstExam.placeOfExam.isNullOrEmpty()) "Unknown Place" else firstExam.placeOfExam

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

    val fullRegion = when (regionAbbr.uppercase()) {
        "BARMM", "CAR", "NIR" -> regionAbbr
        "NCR" -> "Region NCR"
        "CO" -> "Central Office"
        "" -> "Unknown Region"
        else -> if (regionAbbr.contains("REGION", true)) regionAbbr else "Region $regionAbbr"
    }

    val fileName = "Exam Results ($formattedFileDate) - $fullRegion, $rawPlace.csv"
    val relativePath = Environment.DIRECTORY_DOCUMENTS + "/ROEC_ExamResults"
    val resolver = context.contentResolver
    val collectionUri = MediaStore.Files.getContentUri("external")

    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
    val selectionArgs = arrayOf(fileName, "$relativePath%")

    try {
        resolver.query(collectionUri, arrayOf(MediaStore.MediaColumns._ID), selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val existingId = cursor.getLong(idColumn)
                val existingUri = ContentUris.withAppendedId(collectionUri, existingId)
                resolver.delete(existingUri, null, null)
            }
        }
    } catch (e: Exception) {
        Log.e("ExportCSV", "Error checking for duplicate file", e)
    }

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
                val exam = examWithElements.exam
                val expectedElements = ExamConfigurations.getTestNumbersForTestType(exam.examCode)
                val elementMap = examWithElements.elements.associateBy { it.elementNumber }

                val elemScores = if (exam.isAbsent) {
                    val firstExpected = expectedElements.filter { it != 99 }.minOrNull()
                    (1..10).joinToString(",") { elemNumber ->
                        when (elemNumber) {
                            firstExpected -> "A"
                            in expectedElements -> ""
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

                val codeScore = if (exam.isAbsent) "" else if (99 in expectedElements) elementMap[99]?.score?.toString() ?: "0" else ""
                val seatNumber = exam.seatNumber
                val setNumber = exam.setNumber
                val region = exam.region ?: ""
                val place = exam.placeOfExam?.replace(",", "") ?: ""
                val date = exam.examDate ?: ""
                val examType = exam.examCode
                val completeRow = exam.completeRow

                writer.write("$seatNumber,$setNumber,$region,$place,$date,$examType,$elemScores,$codeScore,$completeRow\n")
            }
            writer.flush()
        }
    }

    uri?.let { fileUri ->
        val driveManager = GoogleDriveManager(context)
        val account = driveManager.getSignedInAccount()

        if (account != null) {
            // 1. Build a custom loading dialog
            val layout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(64, 48, 64, 48)
                gravity = android.view.Gravity.CENTER
            }

            val progressBar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false
                max = 100
                progress = 0
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val statusText = android.widget.TextView(context).apply {
                text = context.getString(R.string.loading_connecting_to_google_drive)
                textSize = 14f
                setPadding(0, 24, 0, 0)
                gravity = android.view.Gravity.CENTER
            }

            layout.addView(progressBar)
            layout.addView(statusText)

            val loadingDialog = AlertDialog.Builder(context)
                .setTitle("Syncing to PC")
                .setView(layout)
                .setCancelable(false) // Don't let them tap away while syncing
                .create()

            loadingDialog.show()

            // 2. Start the background sync
            Thread {
                val driveService = driveManager.getDriveService(account)

                driveManager.processAndUploadScannedResult(context, driveService, fileUri) { statusMessage, percent ->
                    // 3. Update the UI on the Main Thread
                    (context as? android.app.Activity)?.runOnUiThread {
                        if (percent == -1) {
                            // Error state
                            loadingDialog.dismiss()
                            AlertDialog.Builder(context)
                                .setTitle("Sync Failed")
                                .setMessage(statusMessage)
                                .setPositiveButton("OK", null)
                                .show()
                        } else {
                            // Progress state
                            statusText.text = statusMessage
                            progressBar.progress = percent

                            if (percent == 100) {
                                // Done!
                                loadingDialog.dismiss()
                                android.widget.Toast.makeText(context, "Successfully Uploaded Results to PC!", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }.start()

        } else {
            // User isn't logged in, just show normal local export success
            AlertDialog.Builder(context)
                .setTitle("Local Export Only")
                .setMessage("Exported to Documents/ROEC_ExamResults.\n\nSign in with Google to enable automatic PC syncing.")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}