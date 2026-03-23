package com.example.it_scann.grading

import android.util.Log
import com.example.it_scann.database.AnswerKeyDao
import com.example.it_scann.modules.DetectedAnswer
import kotlin.collections.iterator

suspend fun compareWithAnswerKey(
    detected: List<DetectedAnswer>,
    dao: AnswerKeyDao,
    examCode: String,
    setNumber: Int
): Map<Int, Int> {

    if (detected.isEmpty()) {
        Log.d("AnswerCompare", "No detected answers found.")
        return emptyMap()
    }

    val grouped = detected.groupBy { it.testNumber }
    val results = mutableMapOf<Int, Int>()

    Log.d("AnswerCompare", "ExamCode: $examCode | SetNumber: $setNumber")
    Log.d("AnswerCompare", "Detected total answers: ${detected.size}")

    for ((testNumber, answers) in grouped) {
        val keyEntity = dao.getAnswerKey(examCode, testNumber, setNumber)

        if (keyEntity == null) {
            Log.w("AnswerCompare", "No answer key found for ExamCode=$examCode, TestNumber=$testNumber, SetNumber=$setNumber")
            continue
        }

        val keyString = keyEntity.answerString
        var score = 0

        Log.d("AnswerCompare", "----- TEST NUMBER: $testNumber -----")
        Log.d("AnswerCompare", "Detected answers count: ${answers.size}")

        for (d in answers.sortedBy { it.questionNumber }) {
            val qIndex = d.questionNumber - 1
            val detectedChar = when (d.detected) {
                1 -> 'A'
                2 -> 'B'
                3 -> 'C'
                4 -> 'D'
                else -> null
            }

            if (detectedChar == null) {
                Log.w(
                    "AnswerCompare",
                    "Test $testNumber | Q${d.questionNumber}: Invalid detected value=${d.detected}"
                )
                continue
            }

            if (qIndex < 0 || qIndex >= keyString.length) {
                Log.w(
                    "AnswerCompare",
                    "Test $testNumber | Q${d.questionNumber}: Out of range for key length=${keyString.length}"
                )
                continue
            }

            val correctChar = keyString[qIndex]
            val isCorrect = detectedChar == correctChar

            if (isCorrect) score++

            Log.d(
                "AnswerCompare",
                "Test $testNumber | Q${d.questionNumber} -> Detected=$detectedChar | ${if (isCorrect) "CORRECT" else "WRONG"}"
            )
        }

        results[testNumber] = score
        Log.d("AnswerCompare", "FINAL SCORE for Test $testNumber = $score / ${answers.size}")
    }

    Log.d("AnswerCompare", "=== END COMPARISON ===")

    return results
}