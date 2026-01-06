package com.example.it_scann.ui.theme

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.it_scann.QuestionAdapter
import com.example.it_scann.R

class Answer_key : AppCompatActivity() {

    private lateinit var adapter: QuestionAdapter

    private val totalQuestions = 25
    private val totalTests = 4

    // 🔹 TEMP storage: testIndex -> answers[]
    private val answersPerTest = mutableMapOf<Int, IntArray>()

    private var currentTestIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_answer_key)

        // 🔹 Initialize empty answers for each test
        for (i in 0 until totalTests) {
            answersPerTest[i] = IntArray(totalQuestions) { -1 }
        }

        // 🔹 RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.questionRecycler)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = QuestionAdapter(totalQuestions)
        recyclerView.adapter = adapter

        // Load default test (Elem 1)
        adapter.setAnswers(answersPerTest[0]!!)

        // 🔹 Spinner
        val spinner = findViewById<Spinner>(R.id.testNumberSpinner)

        ArrayAdapter.createFromResource(
            this,
            R.array.element_tests,
            android.R.layout.simple_spinner_item
        ).also { spinnerAdapter ->
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = spinnerAdapter
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {

            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                // 🔹 Save current test answers
                answersPerTest[currentTestIndex] = adapter.getAnswers()

                // 🔹 Switch test
                currentTestIndex = position

                // 🔹 Load new test answers
                adapter.setAnswers(answersPerTest[currentTestIndex]!!)

                Log.d("AnswerKey", "Switched to Test ${currentTestIndex + 1}")
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // 🔹 Submit button (TEMP → DB later)
        findViewById<Button>(R.id.submitButton).setOnClickListener {

            // Save current test before submit
            answersPerTest[currentTestIndex] = adapter.getAnswers()

            Log.d("AnswerKey", "Submitted answers:")
            answersPerTest.forEach { (test, answers) ->
                Log.d("AnswerKey", "Test ${test + 1}: ${answers.contentToString()}")
            }

            // 👉 NEXT STEP: Save to database here
        }
    }
}
