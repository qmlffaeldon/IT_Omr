package com.example.it_scann

// Compare all detected answers grouped by testNumber, return Map<testNumber, score>
suspend fun compareWithAnswerKey(
    detected: List<DetectedAnswer>,
    dao: AnswerKeyDao,
    setNumber: Int
): Map<Int, Int> {

    if (detected.isEmpty()) return emptyMap()

    val grouped = detected.groupBy { it.testNumber }
    val results = mutableMapOf<Int, Int>()

    for ((testNumber, answers) in grouped) {
        val keyEntity = dao.getAnswerKey(testNumber, setNumber) ?: continue
        val keyString = keyEntity.answerString

        var score = 0
        for (d in answers) {
            val qIndex = d.questionNumber - 1
            val detectedChar = when (d.detected) {
                1 -> 'A'; 2 -> 'B'; 3 -> 'C'; 4 -> 'D'; else -> null
            }
            if (detectedChar != null && qIndex >= 0 && qIndex < keyString.length) {
                if (detectedChar == keyString[qIndex]) score++
            }
        }
        results[testNumber] = score
    }

    return results
}