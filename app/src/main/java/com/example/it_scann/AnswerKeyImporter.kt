package com.example.it_scann

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStream
import kotlin.math.floor

object AnswerKeyImporter {

    private val examCodeToTestNumbers: Map<String, List<Int>> = mapOf(
        "TYPEA-080910"     to listOf(8, 9, 10),
        "TYPEA-080910COD"  to listOf(8, 9, 10, 99),
        "TYPEB-02"         to listOf(2),
        "TYPEB-050607"     to listOf(5, 6, 7),
        "TYPEC-020304"     to listOf(2, 3, 4),
        "TYPEC-0304"       to listOf(3, 4),
        "TYPED-02"         to listOf(2),
        "FCRO-04"          to listOf(4),
        "FCRO-01020304"    to listOf(1, 2, 3, 4),
        "FCRO-0304"        to listOf(3, 4),
        "MORSE-CODE"       to listOf(99),
        "RROC-01"          to listOf(1),
        "FCRO-010203"      to listOf(1, 2, 3),
        "FCRO-0102"        to listOf(1, 2)
    )

    // Returns list of entities parsed from this row, or null if row should be skipped
    private fun parseRow(row: Row): Pair<List<AnswerKeyEntity>, Int>? {
        val examCode = getCellAsString(row, 0) ?: return null
        val setNumber = getCellAsString(row, 1)?.toIntOrNull() ?: return null
        val testNumbers = examCodeToTestNumbers[examCode.uppercase()] ?: return Pair(emptyList(), 1)

        val entities = mutableListOf<AnswerKeyEntity>()
        var errors = 0

        for ((partIndex, testNumber) in testNumbers.withIndex()) {
            val keyString = getCellAsString(row, 2 + partIndex)
            val isValid = !keyString.isNullOrEmpty()
                    && keyString.length == 25
                    && keyString.all { it.uppercaseChar() in "ABCD" }

            if (isValid) {
                entities.add(
                    AnswerKeyEntity(
                        examCode = examCode.uppercase(),  // ← added
                        testNumber = testNumber,
                        setNumber = setNumber,
                        answerString = keyString.uppercase()
                    )
                )
            } else if (!keyString.isNullOrEmpty()) {
                errors++
            }
        }
        return Pair(entities, errors)
    }

    // Reads any cell type as a String safely
    private fun getCellAsString(row: org.apache.poi.ss.usermodel.Row, index: Int): String? {
        val cell = row.getCell(index) ?: return null
        return when (cell.cellType) {
            org.apache.poi.ss.usermodel.CellType.STRING  -> cell.stringCellValue.trim()
            org.apache.poi.ss.usermodel.CellType.NUMERIC -> {
                // Avoid "1.0" for whole numbers
                val num = cell.numericCellValue
                if (num == floor(num)) num.toInt().toString() else num.toString()
            }
            org.apache.poi.ss.usermodel.CellType.BLANK   -> null
            else -> null
        }
    }

    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        dao: AnswerKeyDao
    ): ImportResult {
        val entities = mutableListOf<AnswerKeyEntity>()
        var rowsProcessed = 0
        var errors = 0

        try {
            val stream: InputStream = context.contentResolver.openInputStream(uri)
                ?: return ImportResult(0, 0, "Could not open file")

            val workbook = WorkbookFactory.create(stream)
            val sheet = workbook.getSheetAt(0)

            for (rowIndex in 1..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue
                val result = parseRow(row) ?: continue

                entities.addAll(result.first)
                errors += result.second
                rowsProcessed++
            }

            workbook.close()
            stream.close()

            dao.upsertAll(entities)
            return ImportResult(rowsProcessed, entities.size, null)

        } catch (e: Exception) {
            return ImportResult(rowsProcessed, 0, e.message)
        }
    }

    data class ImportResult(
        val rowsImported: Int,
        val entriesInserted: Int,
        val error: String?
    ) {
        val success get() = error == null
    }
}