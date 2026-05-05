package com.ntc.roec_scanner.grading

import android.util.Log
import com.ntc.roec_scanner.database.AnswerKeyDao
import com.ntc.roec_scanner.modules.DetectedAnswer

// Helper function to extract array of valid answers from bracket syntax
fun parseAnswerKey(answerKeyStr: String): List<String> {
    val regex = Regex("\\[(.*?)]|([A-Z])")
    return regex.findAll(answerKeyStr).map { match ->
        match.groupValues[1].ifEmpty { match.groupValues[2] }
    }.toList()
}

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

        // Parse the raw string into a logical List<String>
        val parsedKey = parseAnswerKey(keyEntity.answerString)
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

            // Compare against the parsed array's size, not raw string length
            if (qIndex < 0 || qIndex >= parsedKey.size) {
                Log.w(
                    "AnswerCompare",
                    "Test $testNumber | Q${d.questionNumber}: Out of range for parsed key length=${parsedKey.size}"
                )
                continue
            }

            // A group can be a single letter ("A") or multiple letters ("AB")
            val validAnswersForQuestion = parsedKey[qIndex]
            val isCorrect = validAnswersForQuestion.contains(detectedChar)

            if (isCorrect) score++

            Log.d(
                "AnswerCompare",
                "Test $testNumber | Q${d.questionNumber} -> Detected=$detectedChar | Expected=${validAnswersForQuestion} | ${if (isCorrect) "CORRECT" else "WRONG"}"
            )
        }

        results[testNumber] = score
        Log.d("AnswerCompare", "FINAL SCORE for Test $testNumber = $score / ${answers.size}")
    }

    Log.d("AnswerCompare", "=== END COMPARISON ===")

    return results
}