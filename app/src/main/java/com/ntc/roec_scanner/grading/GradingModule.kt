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

fun calculateExamRemarks(
    examCode: String,
    elementScores: Map<Int, Int>,
    completeRow: String
): String {
    // Human-readable helper functions
    fun getScore(elementNumber: Int): Int = elementScores[elementNumber] ?: 0
    fun getRate(elementNumber: Int): Double = getScore(elementNumber) / 25.0

    return when (examCode) {
        "FCRO-0102" -> {
            val overallRate = (getScore(1) + getScore(2)) / 50.0
            if (overallRate >= 0.7 && getRate(1) >= 0.5 && getRate(2) >= 0.5) "PASSED"
            else if (overallRate >= 0.7) "FAILED*" else "FAILED"
        }
        "FCRO-01020304" -> {
            val overallRate = (getScore(1) + getScore(2) + getScore(3) + getScore(4)) / 100.0
            if (overallRate >= 0.7 && getRate(1) >= 0.5 && getRate(2) >= 0.5 && getRate(3) >= 0.5 && getRate(4) >= 0.5) "PASSED"
            else if ((getScore(1) + getScore(2) + getScore(3)) / 75.0 >= 0.7 && getRate(1) >= 0.5 && getRate(2) >= 0.5 && getRate(3) >= 0.5) "DG '2PHN'"
            else if ((getScore(1) + getScore(2)) / 50.0 >= 0.7 && getRate(1) >= 0.5 && getRate(2) >= 0.5) "DG '3PHN'"
            else if (overallRate >= 0.7) "FAILED*" else "FAILED"
        }
        "FCRO-010203" -> {
            val overallRate = (getScore(1) + getScore(2) + getScore(3)) / 75.0
            if (overallRate >= 0.7 && getRate(1) >= 0.5 && getRate(2) >= 0.5 && getRate(3) >= 0.5) "PASSED"
            else if ((getScore(1) + getScore(2)) / 50.0 >= 0.7 && getRate(1) >= 0.5 && getRate(2) >= 0.5) "DG '3PHN'"
            else if (overallRate >= 0.7) "FAILED*" else "FAILED"
        }
        "FCRO-0304" -> {
            val overallRate = (getScore(3) + getScore(4)) / 50.0
            if (overallRate >= 0.7 && getRate(3) >= 0.5 && getRate(4) >= 0.5) "PASSED"
            else if (overallRate >= 0.7) "FAILED*" else "FAILED"
        }
        "FCRO-04" -> {
            if (getRate(4) >= 0.7) "PASSED" else "FAILED"
        }
        "RROC-01" -> {
            if (getRate(1) >= 0.7) "PASSED" else "FAILED"
        }
        "TYPEA-080910" -> {
            val overallRate = (getScore(8) + getScore(9) + getScore(10)) / 75.0
            if (overallRate >= 0.7 && getRate(8) >= 0.5 && getRate(9) >= 0.5 && getRate(10) >= 0.5) "PASSED"
            else if (overallRate >= 0.7) "FAILED*" else "FAILED"
        }
        "TYPEB-050607" -> {
            val overallRate = (getScore(5) + getScore(6) + getScore(7)) / 75.0
            if (overallRate >= 0.7 && getRate(5) >= 0.5 && getRate(6) >= 0.5 && getRate(7) >= 0.5) "PASSED"
            else if (overallRate >= 0.7) "FAILED*" else "FAILED"
        }
        "TYPEB-02" -> {
            if (getRate(2) >= 0.7) "PASSED" else "FAILED"
        }
        "TYPEC-020304" -> {
            val overallRate = (getScore(2) + getScore(3) + getScore(4)) / 75.0
            if (overallRate >= 0.7 && getRate(2) >= 0.5 && getRate(3) >= 0.5 && getRate(4) >= 0.5) "PASSED"
            else if (overallRate >= 0.7 && getRate(2) >= 0.7 && (getRate(3) < 0.5 || getRate(4) < 0.5)) "DG 'D'*"
            else if (overallRate < 0.7 && getRate(2) >= 0.7) "DG 'D'"
            else if (overallRate >= 0.7) "FAILED*" else "FAILED"
        }
        "TYPEC-0304" -> {
            val overallRate = (getScore(3) + getScore(4)) / 50.0
            if (overallRate >= 0.7 && getRate(3) >= 0.5 && getRate(4) >= 0.5) "PASSED"
            else if (overallRate >= 0.7) "FAILED*" else "FAILED"
        }
        "TYPED-02" -> {
            if (getRate(2) >= 0.7) "PASSED" else "FAILED"
        }
        "TYPEA-080910COD" -> {
            val theoryRate = (getScore(8) + getScore(9) + getScore(10)) / 75.0
            val theoryRemarks = if (theoryRate >= 0.7 && getRate(8) >= 0.5 && getRate(9) >= 0.5 && getRate(10) >= 0.5) "THEORY: PASSED"
            else if (theoryRate >= 0.7) "THEORY: FAILED*" else "THEORY: FAILED"

            val codeRate = getRate(99)
            val codeRemarks = if (codeRate >= 0.7 && completeRow == "Yes") "CODE: PASSED"
            else if (codeRate >= 0.7 && completeRow == "No") "CODE: FAILED**"
            else "CODE: FAILED"

            "$theoryRemarks\n$codeRemarks"
        }
        "MORSE-CODE" -> {
            val codeRate = getRate(99)
            if (codeRate >= 0.7 && completeRow == "Yes") "PASSED"
            else if (codeRate >= 0.7 && completeRow == "No") "FAILED**"
            else "FAILED"
        }
        else -> "UNKNOWN EXAM TYPE"
    }
}