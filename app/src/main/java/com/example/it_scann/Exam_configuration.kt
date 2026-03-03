package com.example.it_scann

import android.util.Log


// ====================== EXAM TYPE CONFIGURATIONS ======================

object ExamConfigurations {

    // Maps full exam code → list of element names
    private val examRegistry: Map<String, List<String>> = mapOf(
        "TYPEA-080910"     to listOf("Elem 8", "Elem 9", "Elem 10"),
        "TYPEA-080910COD"  to listOf("Elem 8", "Elem 9", "Elem 10", "Code"),
        "TYPEB-02"         to listOf("Elem 2"),
        "TYPEB-050607"     to listOf("Elem 5", "Elem 6", "Elem 7"),
        "TYPEC-020304"     to listOf("Elem 2", "Elem 3", "Elem 4"),
        "TYPEC-0304"       to listOf("Elem 3", "Elem 4"),
        "TYPED-02"         to listOf("Elem 2"),
        "FCRO-04"          to listOf("Elem 4"),
        "FCRO-01020304"    to listOf("Elem 1", "Elem 2", "Elem 3", "Elem 4"),
        "FCRO-0304"        to listOf("Elem 3", "Elem 4"),
        "MORSE-CODE"       to listOf("Code"),
        "RROC-01"          to listOf("Elem 1"),
        "FCRO-010203"      to listOf("Elem 1", "Elem 2", "Elem 3"),
        "FCRO-0102"        to listOf("Elem 1", "Elem 2")
    )

    // X positions for up to 4 columns on the answer sheet
    private val columnStartX = listOf(0.055, 0.30, 0.536, 0.776)

    // Shared layout constants
    private const val COL_WIDTH  = 0.20
    private const val COL_STARTY = 0.0675
    private const val COL_HEIGHT = 0.925

    fun getColumnsForTestType(testType: String?): List<Column> {
        val elements = examRegistry[testType?.uppercase()]
            ?: run {
                Log.w("OMR", "Unknown test type '$testType', using default")
                listOf("Elem 2", "Elem 3", "Elem 4")   // fallback
            }

        return elements.mapIndexed { i, name ->
            Column(
                name   = name,
                startx = columnStartX[i],
                width  = COL_WIDTH,
                starty = COL_STARTY,
                height = COL_HEIGHT
            )
        }
    }

    fun getQuestionsForTestType(testType: String?): Int = 25  // uniform across all

    fun getTestNumbersForTestType(testType: String?): List<Int> {
        val elements = examRegistry[testType?.uppercase()] ?: return listOf(2, 3, 4)
        return elements.mapNotNull { name ->
            when (name) {
                "Elem 1"  -> 1
                "Elem 2"  -> 2
                "Elem 3"  -> 3
                "Elem 4"  -> 4
                "Elem 5"  -> 5
                "Elem 6"  -> 6
                "Elem 7"  -> 7
                "Elem 8"  -> 8
                "Elem 9"  -> 9
                "Elem 10" -> 10
                "Code"    -> 99  // assign a sentinel for Morse code
                else      -> null
            }
        }
    }
}
