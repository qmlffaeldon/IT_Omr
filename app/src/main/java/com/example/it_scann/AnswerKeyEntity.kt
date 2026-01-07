package com.example.it_scann

import androidx.room.Entity

@Entity(
    tableName = "answer_keys",
    primaryKeys = ["testNumber", "questionNumber"]
)
data class AnswerKeyEntity(
    val testNumber: Int,
    val questionNumber: Int,
    val answer: Int
)
