package com.example.it_scann.controllers

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.it_scann.R
import com.example.it_scann.database.AppDatabase
import com.example.it_scann.database.ElementScoreEntity
import com.example.it_scann.database.ExamWithElements
import com.example.it_scann.grading.ExamConfigurations
import com.example.it_scann.modules.exportBatchToCSV
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- Constants & Maps ---
val REGIONS_DISPLAY = arrayOf("[ Please select a region ]", "BARMM", "CAR", "Central Office", "REGION I", "REGION II", "REGION III", "REGION IV-A", "REGION IV-B", "REGION IX", "REGION NCR", "NIR", "REGION V", "REGION VI", "REGION VII", "REGION VIII", "REGION X", "REGION XI", "REGION XII", "REGION XIII")
val REGIONS_CODE = arrayOf("", "BARMM", "CAR", "CO", "I", "II", "III", "IV-A", "IV-B", "IX", "NCR", "NIR", "V", "VI", "VII", "VIII", "X", "XI", "XII", "XIII")
val EXAM_TYPES = arrayOf("TYPEA-080910", "TYPEA-080910COD", "TYPEB-02", "TYPEB-050607", "TYPEC-020304", "TYPEC-0304", "TYPED-02", "FCRO-04", "FCRO-01020304", "FCRO-0304", "MORSE-CODE", "RROC-01", "FCRO-010203", "FCRO-0102")
val SETS = arrayOf("1", "2", "3", "4", "5")

// --- Data Models for Adapter ---
sealed class ResultListItem {
    data class Header(val examType: String, val elements: List<Int>) : ResultListItem()
    data class Row(val exam: ExamWithElements, val elements: List<Int>) : ResultListItem()
}

class ResultsActivity : AppCompatActivity() {

    private lateinit var etExamDate: EditText
    private lateinit var spRegion: AutoCompleteTextView
    private lateinit var etPlace: EditText
    private lateinit var rvResults: RecyclerView
    private lateinit var tvNoData: TextView
    private lateinit var adapter: ResultsAdapter

    private var isFabExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        etExamDate = findViewById(R.id.etExamDate)
        spRegion = findViewById(R.id.spRegion)
        etPlace = findViewById(R.id.etPlace)
        rvResults = findViewById(R.id.rvResults)
        tvNoData = findViewById(R.id.tvNoData)

        setupRegionDropdown()
        setupDatePicker()
        setupFabs()

        rvResults.layoutManager = LinearLayoutManager(this)
        adapter = ResultsAdapter(mutableListOf())
        rvResults.adapter = adapter

        loadData()
    }

    private fun setupRegionDropdown() {
        val regionAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, REGIONS_DISPLAY)
        spRegion.setAdapter(regionAdapter)
    }

    private fun setupDatePicker() {
        etExamDate.setOnClickListener {
            val datePicker = MaterialDatePicker.Builder.datePicker().setTitleText("Select Exam Date").build()
            datePicker.addOnPositiveButtonClickListener { selection ->
                val sdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
                etExamDate.setText(sdf.format(Date(selection)))
            }
            datePicker.show(supportFragmentManager, "DATE_PICKER")
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            val exams = AppDatabase.getDatabase(this@ResultsActivity).answerKeyDao().getAllExamsWithElements()

            if (exams.isEmpty()) {
                tvNoData.visibility = View.VISIBLE
                rvResults.visibility = View.GONE
                spRegion.setText(REGIONS_DISPLAY[0], false)
                return@launch
            }

            tvNoData.visibility = View.GONE
            rvResults.visibility = View.VISIBLE

            // Set Global Metadata
            val first = exams.first().exam
            etExamDate.setText(first.examDate)
            etPlace.setText(first.placeOfExam)

            val regionIndex = REGIONS_CODE.indexOf(first.region ?: "")
            if (regionIndex != -1) spRegion.setText(REGIONS_DISPLAY[regionIndex], false)
            else spRegion.setText(REGIONS_DISPLAY[0], false)

            // Group and Flatten Data
            val grouped = exams.groupBy { it.exam.examCode }
            val listItems = mutableListOf<ResultListItem>()

            for ((examCode, group) in grouped) {
                val expectedElements = ExamConfigurations.getTestNumbersForTestType(examCode)
                listItems.add(ResultListItem.Header(examCode, expectedElements))
                for (exam in group) {
                    listItems.add(ResultListItem.Row(exam, expectedElements))
                }
            }
            adapter.updateData(listItems)
        }
    }

    private fun setupFabs() {
        val fabMain = findViewById<FloatingActionButton>(R.id.fabMain)
        val fabSave = findViewById<ExtendedFloatingActionButton>(R.id.fabSave)
        val fabExport = findViewById<ExtendedFloatingActionButton>(R.id.fabExport)
        val fabDelete = findViewById<ExtendedFloatingActionButton>(R.id.fabDelete)

        fabMain.setOnClickListener {
            isFabExpanded = !isFabExpanded
            val visibility = if (isFabExpanded) View.VISIBLE else View.GONE
            fabSave.visibility = visibility
            fabExport.visibility = visibility
            fabDelete.visibility = visibility
        }

        fabSave.setOnClickListener {
            val date = etExamDate.text.toString()
            val regionDisplay = spRegion.text.toString()
            val regionIndex = REGIONS_DISPLAY.indexOf(regionDisplay)
            val regionCode = if (regionIndex > 0) REGIONS_CODE[regionIndex] else ""
            val place = etPlace.text.toString()

            lifecycleScope.launch {
                val db = AppDatabase.getDatabase(this@ResultsActivity)

                // Update global metadata in database
                db.answerKeyDao().updateAllMetadata(date, regionCode, place)

                // Save edited rows
                for (item in adapter.getItems()) {
                    if (item is ResultListItem.Row) {
                        item.exam.exam.examDate = date
                        item.exam.exam.region = regionCode
                        item.exam.exam.placeOfExam = place

                        db.answerKeyDao().updateExamResult(item.exam.exam)
                        for (element in item.exam.elements) {
                            db.answerKeyDao().upsertElementScore(element)
                        }
                    }
                }
                Toast.makeText(this@ResultsActivity, "Saved changes to database", Toast.LENGTH_SHORT).show()
            }
        }

        fabExport.setOnClickListener {
            lifecycleScope.launch {
                val exams = AppDatabase.getDatabase(this@ResultsActivity).answerKeyDao().getAllExamsWithElements()
                exportBatchToCSV(this@ResultsActivity, exams)
            }
        }

        fabDelete.setOnClickListener {
            lifecycleScope.launch {
                AppDatabase.getDatabase(this@ResultsActivity).answerKeyDao().deleteAllResults()
                etExamDate.text.clear()
                etPlace.text.clear()
                spRegion.setText(REGIONS_DISPLAY[0], false)
                loadData()
                Toast.makeText(this@ResultsActivity, "All data deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// --- Dynamic RecyclerView Adapter ---
class ResultsAdapter(private var data: MutableList<ResultListItem>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val TYPE_HEADER = 0
    private val TYPE_ROW = 1

    fun getItems() = data
    fun updateData(newData: List<ResultListItem>) {
        data.clear()
        data.addAll(newData)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = if (data[position] is ResultListItem.Header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_result_header, parent, false))
        } else {
            RowViewHolder(inflater.inflate(R.layout.item_result_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = data[position]
        if (holder is HeaderViewHolder && item is ResultListItem.Header) {
            holder.bind(item)
        } else if (holder is RowViewHolder && item is ResultListItem.Row) {
            holder.bind(item)
        }
    }

    override fun getItemCount() = data.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvHeaderTitle: TextView = view.findViewById(R.id.tvHeaderTitle)
        private val container: LinearLayout = view.findViewById(R.id.headerColumnsContainer)

        fun bind(item: ResultListItem.Header) {
            tvHeaderTitle.text = getFriendlyName(item.examType)

            // Clear dynamic columns (keep first 3: Seat, Set, Type)
            while (container.childCount > 3) container.removeViewAt(3)

            val weightPerDynamicColumn = 40f / item.elements.size

            for (element in item.elements) {
                val headerName = when (element) {
                    99 -> "Code"
                    98 -> "Complete" // Custom mapping for CompleteRow
                    else -> "Elem $element"
                }

                val tv = TextView(container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weightPerDynamicColumn)
                    text = headerName
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                }
                container.addView(tv)
            }
        }

        private fun getFriendlyName(examCode: String): String = when (examCode) {
            "TYPEA-080910COD" -> "Amateur Class A (Elements 8, 9, & 10 with Code)"
            "MORSE-CODE" -> "Amateur Class A (Morse Code Only)"
            "TYPEC-020304" -> "Amateur Class C (Elements 2, 3 & 4)"
            "TYPED-02" -> "Amateur Class D (Element 2)"
            "FCRO-01020304" -> "First Class RO (Elements 1, 2, 3, & 4)"
            else -> "Exam Type: $examCode"
        }
    }

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val container: LinearLayout = view.findViewById(R.id.rowContainer)
        private val tvSeat: TextView = view.findViewById(R.id.tvSeat)
        private val spSet: Spinner = view.findViewById(R.id.spSet)
        private val spType: Spinner = view.findViewById(R.id.spType)

        fun bind(item: ResultListItem.Row) {
            val exam = item.exam.exam
            val elementMap = item.exam.elements.associateBy { it.elementNumber }.toMutableMap()

            tvSeat.text = exam.seatNumber.toString()

            // Setup Spinners
            val setAdapter = ArrayAdapter(container.context, android.R.layout.simple_spinner_item, SETS)
            spSet.adapter = setAdapter
            spSet.setSelection(SETS.indexOf(exam.setNumber.toString()).coerceAtLeast(0))

            val typeAdapter = ArrayAdapter(container.context, android.R.layout.simple_spinner_item, EXAM_TYPES)
            spType.adapter = typeAdapter
            spType.setSelection(EXAM_TYPES.indexOf(exam.examCode).coerceAtLeast(0))

            // Spinner Listeners to update memory model
            spSet.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    exam.setNumber = SETS[pos].toInt()
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
            spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    exam.examCode = EXAM_TYPES[pos]
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }

            // Clear dynamic columns (keep first 3: Seat, Set, Type)
            while (container.childCount > 3) container.removeViewAt(3)

            val weightPerDynamicColumn = 40f / item.elements.size

            for (elementNum in item.elements) {
                if (elementNum == 98) { // CompleteRow Checkbox mapping
                    val cb = CheckBox(container.context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weightPerDynamicColumn)
                        gravity = Gravity.CENTER
                        isChecked = exam.completeRow
                        setOnCheckedChangeListener { _, isChecked -> exam.completeRow = isChecked }
                    }
                    container.addView(cb)
                } else {
                    val et = EditText(container.context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weightPerDynamicColumn)
                        inputType = InputType.TYPE_CLASS_NUMBER
                        gravity = Gravity.CENTER

                        val score = elementMap[elementNum]?.score?.toString() ?: ""
                        setText(score)

                        addTextChangedListener(object : TextWatcher {
                            override fun afterTextChanged(s: Editable?) {
                                val newScore = s?.toString()?.toIntOrNull() ?: 0
                                val existingEntity = elementMap[elementNum]
                                if (existingEntity != null) {
                                    val updatedEntity = existingEntity.copy(score = newScore)
                                    elementMap[elementNum] = updatedEntity

                                    // Update the master list so it saves correctly
                                    val mutableList = item.exam.elements as MutableList
                                    val index = mutableList.indexOfFirst { it.elementNumber == elementNum }
                                    if(index != -1) mutableList[index] = updatedEntity
                                } else {
                                    // Handle missing element gracefully
                                    val newEntity = ElementScoreEntity(examResultId = exam.id, elementNumber = elementNum, score = newScore)
                                    (item.exam.elements as MutableList).add(newEntity)
                                }
                            }
                            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                        })
                    }
                    container.addView(et)
                }
            }
        }
    }
}