package com.example.it_scann

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface AnswerKeyDao {

    @Transaction
    @Query("SELECT * FROM exam_results")
    suspend fun getAllExamsWithElements(): List<ExamWithElements>

    @Upsert
    suspend fun upsertAll(list: List<AnswerKeyEntity>)

    @Upsert
    suspend fun upsertElementScores(list: List<ElementScoreEntity>)

    @Query("SELECT * FROM exam_results")
    suspend fun getAllExamResults(): List<ExamResultsEntity>

    @Query("SELECT * FROM element_scores WHERE examResultId = :id")
    suspend fun getElementScores(id: Long): List<ElementScoreEntity>

    // Need this to get the generated ID back
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExamResult(result: ExamResultsEntity): Long

    @Query("SELECT * FROM answer_keys WHERE testNumber = :test")
    suspend fun getAnswersForTest(test: Int): List<AnswerKeyEntity>

    @Query("DELETE FROM answer_keys WHERE testNumber = :test")
    suspend fun deleteTest(test: Int)

}
