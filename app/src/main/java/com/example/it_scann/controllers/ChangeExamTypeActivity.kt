package com.example.it_scann.controllers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.it_scann.R
import com.example.it_scann.database.AppDatabase
import com.example.it_scann.database.ExamResultsEntity
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.launch

class ChangeExamTypeActivity : AppCompatActivity() {

    private lateinit var rvChangeExam: RecyclerView
    private lateinit var btnChange: ExtendedFloatingActionButton
    private lateinit var btnSaveReturn: ExtendedFloatingActionButton
    private lateinit var adapter: ChangeExamAdapter

    private var allExams = mutableListOf<ExamResultsEntity>()
    private val selectedIds = mutableSetOf<Long>()
    private val examsToWipe = mutableSetOf<Long>() // Track which ones need element wipes on save

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_change_exam)

        rvChangeExam = findViewById(R.id.rvChangeExam)
        btnChange = findViewById(R.id.btnChange)
        btnSaveReturn = findViewById(R.id.btnSaveReturn)

        rvChangeExam.layoutManager = LinearLayoutManager(this)
        adapter = ChangeExamAdapter(mutableListOf(), selectedIds) {
            btnChange.isEnabled = selectedIds.isNotEmpty()
        }
        rvChangeExam.adapter = adapter

        btnChange.setOnClickListener { showExamTypeSelectionDialog() }

        btnSaveReturn.setOnClickListener { saveAndReturn() }

        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            if (allExams.isEmpty()) {
                val examsWithElements = AppDatabase.getDatabase(this@ChangeExamTypeActivity).answerKeyDao().getAllExamsWithElements()

                // EXTRACTS THE EXAM AND SORTS IT BY SEAT NUMBER BEFORE DISPLAYING
                allExams = examsWithElements.map { it.exam }.sortedBy { it.seatNumber }.toMutableList()
            }
            refreshGrouping()
        }
    }

    private fun refreshGrouping() {
        val grouped = allExams.groupBy { it.examCode }
        val listItems = mutableListOf<Any>()

        for ((examCode, group) in grouped) {
            listItems.add(examCode) // Header
            listItems.addAll(group) // Rows
        }
        adapter.updateData(listItems)
        btnChange.isEnabled = selectedIds.isNotEmpty()
    }

    private fun showExamTypeSelectionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Select New Exam Type")
            .setItems(EXAM_TYPES) { _, which ->
                val chosenType = EXAM_TYPES[which]
                showConfirmationDialog(chosenType)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConfirmationDialog(chosenType: String) {
        val selectedExams = allExams.filter { selectedIds.contains(it.id) }
        val seatList = selectedExams.joinToString(", ") { it.seatNumber.toString() }

        AlertDialog.Builder(this)
            .setTitle("Confirm Change")
            .setMessage("Are you sure you want to change the following seat numbers' exam type to $chosenType? \n\nSeats: $seatList")
            .setPositiveButton("Yes") { _, _ ->
                // Update in memory and flag for element wipe
                for (exam in selectedExams) {
                    exam.examCode = chosenType
                    examsToWipe.add(exam.id)
                }
                selectedIds.clear()
                refreshGrouping()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveAndReturn() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@ChangeExamTypeActivity)

            for (exam in allExams) {
                db.answerKeyDao().updateExamResult(exam)
                // If the type was changed, wipe the old element scores from DB
                if (examsToWipe.contains(exam.id)) {
                    db.answerKeyDao().deleteElementsForExam(exam.id)
                }
            }
            Toast.makeText(this@ChangeExamTypeActivity, "Changes saved successfully", Toast.LENGTH_SHORT).show()
            finish() // Goes back to ResultsActivity
        }
    }
}

class ChangeExamAdapter(
    private var data: MutableList<Any>,
    private val selectedIds: MutableSet<Long>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val typeHeader = 0
    private val typeRow = 1

    fun updateData(newData: List<Any>) {
        data.clear()
        data.addAll(newData)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = if (data[position] is String) typeHeader else typeRow

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == typeHeader) {
            HeaderViewHolder(inflater.inflate(R.layout.item_change_header, parent, false))
        } else {
            RowViewHolder(inflater.inflate(R.layout.item_change_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = data[position]
        if (holder is HeaderViewHolder && item is String) {
            holder.bind(item)
        } else if (holder is RowViewHolder && item is ExamResultsEntity) {
            holder.bind(item, selectedIds, onSelectionChanged)
        }
    }

    override fun getItemCount() = data.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvHeaderTitle: TextView = view.findViewById(R.id.tvHeaderTitle)
        fun bind(examType: String) {
            tvHeaderTitle.text = getFriendlyExamName(examType)
        }
    }

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvSeat: TextView = view.findViewById(R.id.tvSeat)
        private val spSet: Spinner = view.findViewById(R.id.spSet)
        private val tvType: TextView = view.findViewById(R.id.tvType)
        private val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)

        fun bind(exam: ExamResultsEntity, selectedIds: MutableSet<Long>, onSelectionChanged: () -> Unit) {
            tvSeat.text = exam.seatNumber.toString()
            tvType.text = exam.examCode

            val setAdapter = ArrayAdapter(itemView.context, R.layout.item_spinner_selected, SETS)
            setAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
            spSet.adapter = setAdapter
            spSet.setSelection(SETS.indexOf(exam.setNumber.toString()).coerceAtLeast(0))

            spSet.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    exam.setNumber = SETS[pos].toInt()
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }

            // Remove listener before setting checked state to avoid unwanted triggers during scroll recycling
            cbSelect.setOnCheckedChangeListener(null)
            cbSelect.isChecked = selectedIds.contains(exam.id)

            cbSelect.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedIds.add(exam.id) else selectedIds.remove(exam.id)
                onSelectionChanged()
            }
        }
    }
}