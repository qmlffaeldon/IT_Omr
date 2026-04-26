package com.example.it_scann.controllers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.it_scann.R
import com.example.it_scann.database.AppDatabase
import com.example.it_scann.database.ExamWithElements
import com.example.it_scann.modules.exportBatchToCSV
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class ResultsActivity : AppCompatActivity() {

    private lateinit var etExamDate: EditText
    private lateinit var etRegion: EditText
    private lateinit var etPlace: EditText
    private lateinit var rvResults: RecyclerView
    private lateinit var adapter: ResultsAdapter

    private var isFabExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        etExamDate = findViewById(R.id.etExamDate)
        etRegion = findViewById(R.id.etRegion)
        etPlace = findViewById(R.id.etPlace)
        rvResults = findViewById(R.id.rvResults)

        rvResults.layoutManager = LinearLayoutManager(this)
        adapter = ResultsAdapter(emptyList())
        rvResults.adapter = adapter

        setupFabs()
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@ResultsActivity)
            val exams = db.answerKeyDao().getAllExamsWithElements()

            if (exams.isNotEmpty()) {
                val first = exams.first().exam
                etExamDate.setText(first.examDate)
                etRegion.setText(first.region)
                etPlace.setText(first.placeOfExam)
            }
            adapter.updateData(exams)
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
            val region = etRegion.text.toString()
            val place = etPlace.text.toString()

            lifecycleScope.launch {
                AppDatabase.getDatabase(this@ResultsActivity).answerKeyDao()
                    .updateAllMetadata(date, region, place)
                Toast.makeText(this@ResultsActivity, "Saved changes to database", Toast.LENGTH_SHORT).show()
                loadData()
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
                etRegion.text.clear()
                etPlace.text.clear()
                loadData()
                Toast.makeText(this@ResultsActivity, "All data deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class ResultsAdapter(private var data: List<ExamWithElements>) : RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSeat: TextView = view.findViewById(R.id.tvSeat)
        val tvSet: TextView = view.findViewById(R.id.tvSet)
        val tvType: TextView = view.findViewById(R.id.tvType)
        val tvScore: TextView = view.findViewById(R.id.tvScore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_result_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = data[position].exam
        holder.tvSeat.text = item.seatNumber.toString()
        holder.tvSet.text = item.setNumber.toString()
        holder.tvType.text = item.examCode
        holder.tvScore.text = if (item.isAbsent) "Absent" else item.totalScore.toString()
    }

    override fun getItemCount() = data.size

    fun updateData(newData: List<ExamWithElements>) {
        data = newData
        notifyDataSetChanged()
    }
}