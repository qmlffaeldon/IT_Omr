package com.example.it_scann

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AnswerKeyDao {

    @Upsert
    suspend fun upsertAll(list: List<AnswerKeyEntity>)

    @Query("SELECT * FROM answer_keys WHERE testNumber = :test")
    suspend fun getAnswersForTest(test: Int): List<AnswerKeyEntity>

    @Query("DELETE FROM answer_keys WHERE testNumber = :test")
    suspend fun deleteTest(test: Int)
}
