package com.ntc.roec_scanner.controllers

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.ntc.roec_scanner.R
import com.ntc.roec_scanner.database.AppDatabase
import com.ntc.roec_scanner.database.ElementScoreEntity
import com.ntc.roec_scanner.database.ExamWithElements
import com.ntc.roec_scanner.grading.ExamConfigurations
import com.ntc.roec_scanner.modules.exportBatchToCSV
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val REGIONS_DISPLAY = arrayOf(
    "[ Please select a region ]",
    "BARMM",
    "CAR",
    "Central Office",
    "REGION I",
    "REGION II",
    "REGION III",
    "REGION IV-A",
    "REGION IV-B",
    "REGION IX",
    "REGION NCR",
    "NIR",
    "REGION V",
    "REGION VI",
    "REGION VII",
    "REGION VIII",
    "REGION X",
    "REGION XI",
    "REGION XII",
    "REGION XIII"
)
val REGIONS_CODE = arrayOf(
    "",
    "BARMM",
    "CAR",
    "CO",
    "I",
    "II",
    "III",
    "IV-A",
    "IV-B",
    "IX",
    "NCR",
    "NIR",
    "V",
    "VI",
    "VII",
    "VIII",
    "X",
    "XI",
    "XII",
    "XIII"
)
val EXAM_TYPES = arrayOf(
    "TYPEA-080910",
    "TYPEA-080910COD",
    "TYPEB-02",
    "TYPEB-050607",
    "TYPEC-020304",
    "TYPEC-0304",
    "TYPED-02",
    "FCRO-04",
    "FCRO-01020304",
    "FCRO-0304",
    "MORSE-CODE",
    "RROC-01",
    "FCRO-010203",
    "FCRO-0102"
)
val SETS = arrayOf("1", "2", "3", "4", "5")

fun getFriendlyExamName(examCode: String): String = when (examCode) {
    "TYPEA-080910" -> "Amateur Class A (Elements 8, 9, & 10)"
    "TYPEA-080910COD" -> "Amateur Class A (Elements 8, 9, & 10 with Code)"
    "TYPEB-02" -> "Amateur Class B (Element 2)"
    "TYPEB-050607" -> "Amateur Class B (Elements 5, 6, & 7)"
    "TYPEC-020304" -> "Amateur Class C (Elements 2, 3, & 4)"
    "TYPEC-0304" -> "Amateur Class C (Elements 3 & 4)"
    "TYPED-02" -> "Amateur Class D (Element 2)"
    "FCRO-04" -> "First Class Radio Op (Element 4)"
    "FCRO-01020304" -> "First Class Radio Op (Elements 1, 2, 3, & 4)"
    "FCRO-0304" -> "First Class Radio Op (Elements 3 & 4)"
    "MORSE-CODE" -> "Amateur Class A (Morse Code Only)"
    "RROC-01" -> "Restricted Radio Op (RROC) (Element 1)"
    "FCRO-010203" -> "Second Class Radiotelephone Op (Element 1, 2, & 3)"
    "FCRO-0102" -> "Third Class Radiotelephone Op (Elements 1 & 2)"
    else -> "Exam Type: $examCode"
}

// Global helper for customized Snackbars
fun showCustomSnackbar(view: View, message: String) {
    val snackbar = Snackbar.make(view, message, 3000)
    snackbar.setAction("Close") {
        snackbar.dismiss()
    }
    snackbar.show()
}

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

    private val changeExamLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val rootView = findViewById<View>(android.R.id.content)
            showCustomSnackbar(rootView, "Changes saved successfully")
        }
    }

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
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun setupRegionDropdown() {
        val regionAdapter =
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, REGIONS_DISPLAY)
        spRegion.setAdapter(regionAdapter)
    }

    private fun setupDatePicker() {
        etExamDate.setOnClickListener {
            val datePicker =
                MaterialDatePicker.Builder.datePicker().setTitleText("Select Exam Date").build()
            datePicker.addOnPositiveButtonClickListener { selection ->
                val sdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
                etExamDate.setText(sdf.format(Date(selection)))
            }
            datePicker.show(supportFragmentManager, "DATE_PICKER")
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            val exams = AppDatabase.getDatabase(this@ResultsActivity).answerKeyDao()
                .getAllExamsWithElements()

            if (exams.isEmpty()) {
                tvNoData.visibility = View.VISIBLE
                rvResults.visibility = View.GONE
                spRegion.setText(REGIONS_DISPLAY[0], false)
                return@launch
            }

            tvNoData.visibility = View.GONE
            rvResults.visibility = View.VISIBLE

            val first = exams.first().exam
            etExamDate.setText(first.examDate)
            etPlace.setText(first.placeOfExam)

            val regionIndex = REGIONS_CODE.indexOf(first.region ?: "")
            if (regionIndex != -1) spRegion.setText(REGIONS_DISPLAY[regionIndex], false)
            else spRegion.setText(REGIONS_DISPLAY[0], false)

            val sortedExams = exams.sortedBy { it.exam.seatNumber }
            val grouped = sortedExams.groupBy { it.exam.examCode }

            val listItems = mutableListOf<ResultListItem>()

            for ((examCode, group) in grouped) {
                val baseElements = ExamConfigurations.getTestNumbersForTestType(examCode)

                val expectedElements =
                    if (examCode == "TYPEA-080910COD" || examCode == "MORSE-CODE") {
                        baseElements + listOf(98)
                    } else {
                        baseElements
                    }

                listItems.add(ResultListItem.Header(examCode, expectedElements))
                for (exam in group) {
                    listItems.add(ResultListItem.Row(exam, expectedElements))
                }
            }
            adapter.updateData(listItems)
        }
    }

    private suspend fun saveAllDataToDb() {
        val date = etExamDate.text.toString()
        val regionDisplay = spRegion.text.toString()
        val regionIndex = REGIONS_DISPLAY.indexOf(regionDisplay)
        val regionCode = if (regionIndex > 0) REGIONS_CODE[regionIndex] else ""
        val place = etPlace.text.toString()

        val db = AppDatabase.getDatabase(this@ResultsActivity)

        for (item in adapter.getItems()) {
            if (item is ResultListItem.Row) {
                item.exam.exam.examDate = date
                item.exam.exam.region = regionCode
                item.exam.exam.placeOfExam = place

                if (item.exam.exam.examCode != "TYPEA-080910COD" && item.exam.exam.examCode != "MORSE-CODE") {
                    item.exam.exam.completeRow = ""
                } else if (item.exam.exam.completeRow.isEmpty()) {
                    item.exam.exam.completeRow = "No"
                }

                db.answerKeyDao().updateExamResult(item.exam.exam)

                for (element in item.exam.elements) {
                    db.answerKeyDao().upsertElementScore(element)
                }
            }
        }
    }

    private fun setupFabs() {
        val fabMain = findViewById<FloatingActionButton>(R.id.fabMain)
        val fabMenuContainer = findViewById<LinearLayout>(R.id.fabMenuContainer)
        val fabSave = findViewById<ExtendedFloatingActionButton>(R.id.fabSave)
        val fabChangeTypes = findViewById<ExtendedFloatingActionButton>(R.id.fabChangeTypes)
        val fabExport = findViewById<ExtendedFloatingActionButton>(R.id.fabExport)
        val fabDelete = findViewById<ExtendedFloatingActionButton>(R.id.fabDelete)
        val rootView = findViewById<ViewGroup>(android.R.id.content)

        // Helper function to cleanly auto-collapse the menu
        fun closeFabMenu() {
            if (isFabExpanded) {
                fabMenuContainer.animate().translationY(150f).alpha(0f).setDuration(250)
                    .withEndAction {
                        fabMenuContainer.visibility = View.GONE
                    }.start()
                isFabExpanded = false
            }
        }

        // Keyboard Detection: Hide FAB when typing
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.rootView.height
            val keypadHeight = screenHeight - rect.bottom

            if (keypadHeight > screenHeight * 0.15) {
                fabMain.visibility = View.GONE
                if (isFabExpanded) {
                    fabMenuContainer.visibility = View.GONE
                    isFabExpanded = false
                }
            } else {
                fabMain.visibility = View.VISIBLE
            }
        }

        fabMain.setOnClickListener {
            if (!isFabExpanded) {
                fabMenuContainer.visibility = View.VISIBLE
                fabMenuContainer.translationY = 150f
                fabMenuContainer.alpha = 0f
                fabMenuContainer.animate().translationY(0f).alpha(1f).setDuration(250).start()
                isFabExpanded = true
            } else {
                closeFabMenu()
            }
        }

        fabChangeTypes.setOnClickListener {
            closeFabMenu() // Auto-close
            // Use the launcher instead of startActivity
            changeExamLauncher.launch(Intent(this, ChangeExamTypeActivity::class.java))
        }

        fabSave.setOnClickListener {
            closeFabMenu() // Auto-close
            lifecycleScope.launch {
                saveAllDataToDb()
                showCustomSnackbar(rootView, "Saved all changes")
                loadData()
            }
        }

        fabExport.setOnClickListener {
            closeFabMenu() // Auto-close
            lifecycleScope.launch {
                saveAllDataToDb()
                val exams = AppDatabase.getDatabase(this@ResultsActivity).answerKeyDao()
                    .getAllExamsWithElements()
                exportBatchToCSV(this@ResultsActivity, exams)
            }
        }

        fabDelete.setOnClickListener {
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete All Records")
                .setMessage("Are you sure you want to permanently delete all scanned results?")
                .setPositiveButton("Yes", null)
                .setNegativeButton("Cancel", null)
                .create()

            dialog.setOnShowListener {
                val btnYes = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                btnYes.setBackgroundColor(Color.parseColor("#2F9248"))
                btnYes.setTextColor(Color.WHITE)
                btnYes.setOnClickListener {
                    closeFabMenu() // Auto-close on confirm
                    lifecycleScope.launch {
                        AppDatabase.getDatabase(this@ResultsActivity).answerKeyDao()
                            .deleteAllResults()
                        etExamDate.text.clear()
                        etPlace.text.clear()
                        spRegion.setText(REGIONS_DISPLAY[0], false)
                        loadData()
                        showCustomSnackbar(rootView, "All data deleted")
                        dialog.dismiss()
                    }
                }

                val btnCancel = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                btnCancel.setBackgroundColor(Color.TRANSPARENT)
                btnCancel.setTextColor(Color.RED)
                btnCancel.setOnClickListener {
                    closeFabMenu() // Auto-close on cancel
                    dialog.dismiss()
                }
            }
            dialog.show()
        }
    }
}

class ResultsAdapter(private var data: MutableList<ResultListItem>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val TYPE_HEADER = 0
    private val TYPE_ROW = 1

    fun getItems() = data
    fun updateData(newData: List<ResultListItem>) {
        data.clear()
        data.addAll(newData)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) =
        if (data[position] is ResultListItem.Header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_result_header, parent, false))
        } else {
            RowViewHolder(inflater.inflate(R.layout.item_result_row, parent, false), this)
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
            tvHeaderTitle.text = getFriendlyExamName(item.examType)

            while (container.childCount > 2) container.removeViewAt(2)

            val weightPerDynamicColumn = 70f / item.elements.size

            for (element in item.elements) {
                val headerName = when (element) {
                    99 -> "Code"
                    98 -> "CompleteRow"
                    else -> "Elem $element"
                }

                val tv = TextView(container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        weightPerDynamicColumn
                    )
                    text = headerName
                    gravity = Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                container.addView(tv)
            }
        }
    }

    class RowViewHolder(view: View, private val adapter: ResultsAdapter) :
        RecyclerView.ViewHolder(view) {
        private val container: LinearLayout = view.findViewById(R.id.rowContainer)
        private val tvSeat: TextView = view.findViewById(R.id.tvSeat)
        private val spSet: Spinner = view.findViewById(R.id.spSet)

        fun bind(item: ResultListItem.Row) {
            val exam = item.exam.exam
            val elementMap = item.exam.elements.associateBy { it.elementNumber }.toMutableMap()

            tvSeat.text = exam.seatNumber.toString()

            val setAdapter = ArrayAdapter(container.context, R.layout.item_spinner_selected, SETS)
            setAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
            spSet.adapter = setAdapter
            spSet.setSelection(SETS.indexOf(exam.setNumber.toString()).coerceAtLeast(0))

            spSet.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    exam.setNumber = SETS[pos].toInt()
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }

            tvSeat.setOnLongClickListener {
                val action = if (exam.isAbsent) "Present" else "Absent"
                androidx.appcompat.app.AlertDialog.Builder(itemView.context)
                    .setTitle("Mark as $action")
                    .setMessage("Are you sure you want to mark Seat ${exam.seatNumber} as $action?")
                    .setPositiveButton("Yes") { _, _ ->
                        exam.isAbsent = !exam.isAbsent
                        if (exam.isAbsent) {
                            exam.completeRow = ""
                        } else if (exam.examCode == "TYPEA-080910COD" || exam.examCode == "MORSE-CODE") {
                            exam.completeRow = "No"
                        }
                        adapter.notifyItemChanged(bindingAdapterPosition)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }

            while (container.childCount > 2) container.removeViewAt(2)

            val weightPerDynamicColumn = 70f / item.elements.size

            if (exam.isAbsent) {
                container.setBackgroundColor("#FFCDD2".toColorInt())

                val tvAbsent = TextView(container.context).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 70f)
                    text = "ABSENT"
                    gravity = Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    textSize = 16f
                    setTextColor("#D32F2F".toColorInt())
                }
                container.addView(tvAbsent)

            } else {
                container.setBackgroundColor(Color.TRANSPARENT)

                for (elementNum in item.elements) {
                    if (elementNum == 98) {
                        val wrapper = LinearLayout(container.context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                weightPerDynamicColumn
                            )
                            gravity = Gravity.CENTER
                        }

                        val cb = CheckBox(container.context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )

                            isChecked = exam.completeRow == "Yes"
                            setOnCheckedChangeListener { _, isChecked ->
                                exam.completeRow = if (isChecked) "Yes" else "No"
                            }
                        }
                        wrapper.addView(cb)
                        container.addView(wrapper)
                    } else {
                        val et = EditText(container.context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                weightPerDynamicColumn
                            )
                            inputType = InputType.TYPE_CLASS_NUMBER
                            gravity = Gravity.CENTER
                            textSize = 16f
                            setTextColor(Color.BLACK)

                            val score = elementMap[elementNum]?.score?.toString() ?: ""
                            setText(score)

                            addTextChangedListener(object : TextWatcher {
                                override fun afterTextChanged(s: Editable?) {
                                    val newScore = s?.toString()?.toIntOrNull() ?: 0

                                    if (newScore > 25) {
                                        setBackgroundColor("#FFCDD2".toColorInt())
                                        // Retrieve rootView contextually for the Snackbar
                                        val activity = context as? Activity
                                        activity?.findViewById<View>(android.R.id.content)?.let {
                                            showCustomSnackbar(it, "Maximum score is only up to 25")
                                        }
                                    } else {
                                        setBackgroundColor(Color.TRANSPARENT)
                                    }

                                    val existingEntity = elementMap[elementNum]
                                    if (existingEntity != null) {
                                        val updatedEntity = existingEntity.copy(score = newScore)
                                        elementMap[elementNum] = updatedEntity
                                        val mutableList = item.exam.elements as MutableList
                                        val index =
                                            mutableList.indexOfFirst { it.elementNumber == elementNum }
                                        if (index != -1) mutableList[index] = updatedEntity
                                    } else {
                                        val newEntity = ElementScoreEntity(
                                            examResultId = exam.id,
                                            elementNumber = elementNum,
                                            score = newScore
                                        )
                                        (item.exam.elements as MutableList).add(newEntity)
                                        elementMap[elementNum] = newEntity
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
}