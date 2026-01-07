package com.example.it_scann

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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.appcompat.app.AlertDialog
import android.widget.Toast


class Answer_key : AppCompatActivity() {

    private lateinit var adapter: QuestionAdapter

    private val totalQuestions = 25
    private val totalTests = 4

    // 🔹 TEMP storage: testIndex -> answers[]
    private val answersPerTest = mutableMapOf<Int, IntArray>()

    private var currentTestIndex = 0
    private fun showSuccessDialog() {
        AlertDialog.Builder(this)
            .setTitle("Saved Successfully")
            .setMessage("Answer keys have been saved.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }


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
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                // 🔹 Save current test to TEMP
                answersPerTest[currentTestIndex] = adapter.getAnswers()

                currentTestIndex = position

                val db = AppDatabase.getDatabase(this@Answer_key)

                lifecycleScope.launch {

                    val saved = db.answerKeyDao()
                        .getAnswersForTest(currentTestIndex)

                    if (saved.isNotEmpty()) {
                        // 🔹 Load from DB
                        val arr = IntArray(totalQuestions) { -1 }
                        saved.forEach {
                            arr[it.questionNumber - 1] = it.answer
                        }
                        adapter.setAnswers(arr)
                        answersPerTest[currentTestIndex] = arr
                    } else {
                        // 🔹 Load TEMP if DB empty
                        adapter.setAnswers(answersPerTest[currentTestIndex]!!)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }


        // 🔹 Submit button (TEMP → DB later)
        findViewById<Button>(R.id.submitButton).setOnClickListener {

            // 🔹 Save current test before submitting
            answersPerTest[currentTestIndex] = adapter.getAnswers()

            val db = AppDatabase.getDatabase(this)

            lifecycleScope.launch {
                try {
                    val list = mutableListOf<AnswerKeyEntity>()

                    answersPerTest.forEach { (test, answers) ->
                        answers.forEachIndexed { index, ans ->
                            if (ans != -1) {
                                list.add(
                                    AnswerKeyEntity(
                                        testNumber = test,
                                        questionNumber = index + 1,
                                        answer = ans
                                    )
                                )
                            }
                        }
                    }

                    // 🔹 Validate: nothing to save
                    if (list.isEmpty()) {
                        showErrorDialog("No answers to save.")
                        return@launch
                    }

                    db.answerKeyDao().upsertAll(list)

                    // ✅ SUCCESS POPUP
                    showSuccessDialog()

                } catch (e: Exception) {
                    Log.e("AnswerKey", "Save failed", e)

                    // ❌ ERROR POPUP
                    showErrorDialog("Failed to save answers. Please try again.")
                }
            }
        }


    }
}