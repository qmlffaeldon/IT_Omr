package com.example.it_scann

import android.util.Log


// ====================== EXAM TYPE CONFIGURATIONS ======================

object ExamConfigurations {

    private val RadioAmateurD = listOf(
        Column("Elem 1", 0.055, 0.1925, 0.0675, 0.925)
    )

    private val RadioAmateurC = listOf(
        Column("Elem 2", 0.055, 0.1925, 0.0675, 0.925),
        Column("Elem 3", 0.30, 0.20, 0.0675, 0.925),
        Column("Elem 4", 0.536, 0.20, 0.0675, 0.925)
    )

    private val RadioAmateurB = listOf(
        Column("Elem 5", 0.055, 0.1925, 0.0675, 0.925),
        Column("Elem 6", 0.30, 0.20, 0.0675, 0.925),
        Column("Elem 7", 0.536, 0.20, 0.0675, 0.925)
    )

    private val RadioAmateurA = listOf(
        Column("Elem 8", 0.055, 0.1925, 0.0675, 0.925),
        Column("Elem 9", 0.30, 0.20, 0.0675, 0.925),
        Column("Elem 10", 0.536, 0.20, 0.0675, 0.925)
    )

    // Default configuration (your current hardcoded one)
    private val DefaultConfig = listOf(
        Column("Elem 2", 0.055, 0.1925, 0.0675, 0.925),
        Column("Elem 3", 0.30, 0.20, 0.0675, 0.925),
        Column("Elem 4a", 0.536, 0.20, 0.0675, 0.925),
        Column("Elem 4b", 0.776, 0.20, 0.0675, 0.925)
    )

    /*
     * Get column configuration based on test type from QR code
     */
    fun getColumnsForTestType(testType: String?): List<Column> {
        return when (testType?.uppercase()) {
            "A" -> RadioAmateurA
            "B" -> RadioAmateurB
            "C" -> RadioAmateurC
            "D" -> RadioAmateurD
            else -> {
                Log.w("OMR", "Unknown test type '$testType', using default configuration")
                DefaultConfig
            }
        }
    }

    /*
     * Get number of questions based on test type
     * Adjust these values based on your actual exam requirements
     */
    fun getQuestionsForTestType(testType: String?): Int {
        return when (testType?.uppercase()) {
            "A" -> 25
            "B" -> 25
            "C" -> 25
            "D" -> 25
            else -> 25
        }
    }
    fun getTestNumbersForTestType(testType: String?): List<Int> {
        return when (testType?.uppercase()) {
            "A" -> listOf(8, 9, 10)       // Elem 8, 9, 10
            "B" -> listOf(5, 6, 7)        // Elem 5, 6, 7
            "C" -> listOf(2, 3, 4)        // Elem 2, 3, 4
            "D" -> listOf(1)              // Elem 1
            else -> listOf(2, 3, 4, 5)    // Default
        }
    }
}
