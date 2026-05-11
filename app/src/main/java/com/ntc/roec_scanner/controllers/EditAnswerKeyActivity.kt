package com.ntc.roec_scanner.controllers

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.ntc.roec_scanner.R
import com.ntc.roec_scanner.database.AnswerKeyEntity
import com.ntc.roec_scanner.database.AnswerKeyImporter
import com.ntc.roec_scanner.database.AppDatabase
import com.ntc.roec_scanner.grading.ExamConfigurations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EditAnswerKeyActivity : AppCompatActivity() {

    private lateinit var spExamType: Spinner
    private lateinit var spSetNumber: Spinner
    private lateinit var gridContainer: LinearLayout
    private lateinit var btnSave: MaterialButton
    private lateinit var btnExportCsv: MaterialButton
    private lateinit var btnImport: MaterialButton

    private val activeAnswers = mutableMapOf<Int, MutableMap<Int, MutableSet<String>>>()
    private var allDbKeys: List<AnswerKeyEntity> = emptyList()

    // 1. Re-implemented the Import File Picker
    private val pickExcelFile = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val db = AppDatabase.getDatabase(this@EditAnswerKeyActivity)
                val result = AnswerKeyImporter.importFromUri(this@EditAnswerKeyActivity, it, db.answerKeyDao())

                if (result.success) {
                    AlertDialog.Builder(this@EditAnswerKeyActivity)
                        .setTitle("Import Successful")
                        .setMessage("${result.rowsImported} exam types imported\n${result.entriesInserted} answer keys stored")
                        .setPositiveButton("OK") { _, _ ->
                            loadAvailableExams() // Refresh Spinners and UI!
                        }
                        .show()
                } else {
                    AlertDialog.Builder(this@EditAnswerKeyActivity)
                        .setTitle("Import Failed")
                        .setMessage(result.error)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_answer_key)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        spExamType = findViewById(R.id.spExamType)
        spSetNumber = findViewById(R.id.spSetNumber)
        gridContainer = findViewById(R.id.gridContainer)
        btnSave = findViewById(R.id.btnSave)
        btnExportCsv = findViewById(R.id.btnExportCsv)
        btnImport = findViewById(R.id.btnImport)

        setupSpinners()
        loadAvailableExams()

        btnImport.setOnClickListener {
            pickExcelFile.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        }

        btnSave.setOnClickListener { saveToDatabase() }

        btnExportCsv.setOnClickListener {
            saveToDatabase {
                exportAllAnswerKeysToCSV()
            }
        }
    }

    private fun setupSpinners() {
        spExamType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateSetsForSelectedExam()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spSetNumber.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadAnswerKeys()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // 2. Dynamically queries DB and populates Exam Types
    private fun loadAvailableExams() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@EditAnswerKeyActivity).answerKeyDao()
            allDbKeys = db.getAllAnswerKeys()

            if (allDbKeys.isEmpty()) {
                val emptyAdapter = ArrayAdapter(this@EditAnswerKeyActivity, android.R.layout.simple_spinner_dropdown_item, listOf("No Exams Found"))
                spExamType.adapter = emptyAdapter
                spSetNumber.adapter = ArrayAdapter(this@EditAnswerKeyActivity, android.R.layout.simple_spinner_dropdown_item, listOf("-"))
                gridContainer.removeAllViews()
                btnSave.isEnabled = false
                btnExportCsv.isEnabled = false
            } else {
                btnSave.isEnabled = true
                btnExportCsv.isEnabled = true

                val distinctExams = allDbKeys.map { it.examCode }.distinct().sorted()
                spExamType.adapter = ArrayAdapter(this@EditAnswerKeyActivity, android.R.layout.simple_spinner_dropdown_item, distinctExams)
                // updateSetsForSelectedExam() automatically fires due to the selection listener
            }
        }
    }

    // 3. Dynamically populates Set Numbers based on the chosen Exam Type
    private fun updateSetsForSelectedExam() {
        val selectedExam = spExamType.selectedItem?.toString() ?: return
        if (selectedExam == "No Exams Found") return

        val distinctSets = allDbKeys.filter { it.examCode == selectedExam }
            .map { it.setNumber.toString() }
            .distinct()
            .sorted()

        spSetNumber.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, distinctSets)
        // loadAnswerKeys() automatically fires due to the set selection listener
    }

    private fun loadAnswerKeys() {
        val selectedExam = spExamType.selectedItem?.toString() ?: return
        if (selectedExam == "No Exams Found") return

        val selectedSet = spSetNumber.selectedItem?.toString()?.toIntOrNull() ?: 1

        // 4. Exclude Element 99 from the UI build process
        val elements = ExamConfigurations.getTestNumbersForTestType(selectedExam).filter { it != 99 }

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@EditAnswerKeyActivity).answerKeyDao()
            activeAnswers.clear()

            elements.forEach { elementNum ->
                val record = db.getAnswerKey(selectedExam, elementNum, selectedSet)
                val elementMap = mutableMapOf<Int, MutableSet<String>>()

                for (q in 1..25) { elementMap[q] = mutableSetOf() }

                if (record != null && record.answerString.isNotEmpty()) {
                    val parsedGroups = Regex("\\[(.*?)\\]|([A-Z])")
                        .findAll(record.answerString)
                        .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                        .toList()

                    parsedGroups.forEachIndexed { index, answers ->
                        val qNum = index + 1
                        if (qNum <= 25) {
                            val selected = answers.map { it.toString() }
                                .filter { it in "ABCD" }
                                .toMutableSet()
                            elementMap[qNum] = selected
                        }
                    }
                }
                activeAnswers[elementNum] = elementMap
            }

            buildGridUI(elements)
        }
    }

    private fun createCellBorder(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setStroke(2, "#E0E0E0".toColorInt())
            setColor(Color.WHITE)
        }
    }

    private fun createCustomCheckboxDrawable(): android.graphics.drawable.StateListDrawable {
        val dpToPx = { dp: Int -> (dp * resources.displayMetrics.density).toInt() }
        val size = dpToPx(24)

        val states = android.graphics.drawable.StateListDrawable()

        val checkedShape = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Color.BLACK)
            setSize(size, size)
            cornerRadius = 4f
        }

        val uncheckedShape = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(dpToPx(2), "#DDDDDD".toColorInt())
            setSize(size, size)
            cornerRadius = 4f
        }

        states.addState(intArrayOf(android.R.attr.state_checked), checkedShape)
        states.addState(intArrayOf(-android.R.attr.state_checked), uncheckedShape)
        return states
    }

    private fun buildGridUI(elements: List<Int>) {
        gridContainer.removeAllViews()

        elements.forEach { elementNum ->
            val elementMap = activeAnswers[elementNum] ?: return@forEach

            val elementHeader = TextView(this).apply {
                text = "Element $elementNum \u25BC"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(32, 32, 32, 32)
                setTextColor(Color.BLACK)
                setBackgroundColor("#E0E0E0".toColorInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 16, 0, 0)
                }
            }
            gridContainer.addView(elementHeader)

            val rowsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 32)
            }

            var isExpanded = true
            elementHeader.setOnClickListener {
                isExpanded = !isExpanded
                rowsContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
                elementHeader.text = "Element $elementNum ${if (isExpanded) "\u25BC" else "\u25B2"}"
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 5f
            }

            headerRow.addView(TextView(this).apply {
                text = "Q#"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 24)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.DKGRAY)
                background = createCellBorder()
            })

            val choicesHeaderWrapper = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 4f)
                weightSum = 4f
                background = createCellBorder()
            }

            listOf("A", "B", "C", "D").forEach { hText ->
                choicesHeaderWrapper.addView(TextView(this).apply {
                    text = hText
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    gravity = Gravity.CENTER
                    setPadding(0, 24, 0, 24)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.DKGRAY)
                })
            }
            headerRow.addView(choicesHeaderWrapper)
            rowsContainer.addView(headerRow)

            for (q in 1..25) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 5f
                }

                row.addView(TextView(this).apply {
                    text = q.toString()
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    gravity = Gravity.CENTER
                    setPadding(0, 16, 0, 16)
                    setTextColor(Color.BLACK)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = createCellBorder()
                })

                val choicesContentWrapper = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 4f)
                    weightSum = 4f
                    background = createCellBorder()
                }

                listOf("A", "B", "C", "D").forEach { opt ->
                    val cbContainer = LinearLayout(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                        gravity = Gravity.CENTER
                        setPadding(0, 16, 0, 16)
                    }

                    val cb = CheckBox(this).apply {
                        buttonDrawable = createCustomCheckboxDrawable()
                        isChecked = elementMap[q]?.contains(opt) == true

                        setOnCheckedChangeListener { _, checked ->
                            if (checked) {
                                elementMap[q]?.add(opt)
                            } else {
                                elementMap[q]?.remove(opt)
                            }
                        }
                    }
                    cbContainer.addView(cb)
                    choicesContentWrapper.addView(cbContainer)
                }
                row.addView(choicesContentWrapper)
                rowsContainer.addView(row)
            }
            gridContainer.addView(rowsContainer)
        }
    }

    private fun saveToDatabase(onSuccess: (() -> Unit)? = null) {
        val selectedExam = spExamType.selectedItem?.toString() ?: return
        val selectedSet = spSetNumber.selectedItem?.toString()?.toIntOrNull() ?: 1
        val rootView = findViewById<View>(android.R.id.content)

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@EditAnswerKeyActivity).answerKeyDao()

            for ((elementNum, qMap) in activeAnswers) {
                for (q in 1..25) {
                    val answers = qMap[q]?.filter { it in "ABCD" } ?: emptyList()
                    if (answers.isEmpty()) {
                        Snackbar.make(
                            rootView,
                            "Element $elementNum, Q$q is blank! All 25 questions require an answer.",
                            Snackbar.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                }
            }

            activeAnswers.forEach { (elementNum, qMap) ->
                val sb = java.lang.StringBuilder()

                for (q in 1..25) {
                    val answers = qMap[q]?.filter { it in "ABCD" }?.sorted() ?: emptyList()
                    if (answers.size == 1) {
                        sb.append(answers.first())
                    } else {
                        sb.append("[${answers.joinToString("")}]")
                    }
                }

                val entity = AnswerKeyEntity(
                    examCode = selectedExam,
                    testNumber = elementNum,
                    setNumber = selectedSet,
                    answerString = sb.toString()
                )
                db.insertAnswerKey(entity)
            }

            // Sync allDbKeys so next operations export fresh data
            allDbKeys = db.getAllAnswerKeys()

            if (onSuccess == null) {
                Snackbar.make(rootView, "Answer keys updated successfully", 3000).show()
            } else {
                onSuccess.invoke()
            }
        }
    }

    private fun exportAllAnswerKeysToCSV() {
        val rootView = findViewById<View>(android.R.id.content)

        lifecycleScope.launch {
            if (allDbKeys.isEmpty()) {
                Snackbar.make(rootView, "No answer keys found in database to export.", Snackbar.LENGTH_LONG).show()
                return@launch
            }

            val formatter = SimpleDateFormat("MMM-dd-yyyy hh:mm a", Locale.US)
            val fileName = "Answer Key ${formatter.format(Date())}.csv"
            val relativePath = Environment.DIRECTORY_DOCUMENTS + "/ROEC_Answer_Key"
            val resolver = contentResolver
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
            if (uri != null) {
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        val writer = outputStream.bufferedWriter()

                        val groupedKeys = allDbKeys.groupBy { Pair(it.examCode, it.setNumber) }

                        groupedKeys.forEach { (pair, keys) ->
                            val examCode = pair.first
                            val setNum = pair.second

                            // Get standard elements AND 99 if applicable to maintain CSV format
                            val requiredTestNums = ExamConfigurations.getTestNumbersForTestType(examCode)

                            val rowData = mutableListOf<String>()
                            rowData.add(examCode)
                            rowData.add(setNum.toString())

                            requiredTestNums.forEach { tNum ->
                                val ansStr = keys.find { it.testNumber == tNum }?.answerString ?: ""
                                rowData.add(ansStr)
                            }

                            writer.write(rowData.joinToString(",") + "\n")
                        }
                        writer.flush()
                    }
                }
                Snackbar.make(rootView, "Successfully exported to Documents/ROEC_Answer_Key", Snackbar.LENGTH_LONG).show()
            } else {
                Snackbar.make(rootView, "Failed to create export file.", Snackbar.LENGTH_LONG).show()
            }
        }
    }
}