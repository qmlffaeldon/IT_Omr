package com.example.it_scann

// Compare all detected answers grouped by testNumber, return Map<testNumber, score>
suspend fun compareWithAnswerKey(
    detected: List<DetectedAnswer>,
    dao: AnswerKeyDao
): Map<Int, Int> {

    if (detected.isEmpty()) return emptyMap()

    val grouped = detected.groupBy { it.testNumber }
    val results = mutableMapOf<Int, Int>()

    for ((testNumber, answers) in grouped) {
        val answerKey = dao.getAnswersForTest(testNumber)
            .associateBy { it.questionNumber }

        var score = 0
        answers.forEach { d ->
            val correct = answerKey[d.questionNumber]?.answer ?: return@forEach
            if (d.detected == correct) {
                score++
            }
        }
        results[testNumber] = score
    }

    return results
}
