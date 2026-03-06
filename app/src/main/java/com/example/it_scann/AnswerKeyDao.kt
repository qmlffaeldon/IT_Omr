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
    suspend fun upsertAll(list: List<AnswerKeyEntity>)          // ← restored

    @Upsert
    suspend fun upsertElementScores(list: List<ElementScoreEntity>)

    @Query("SELECT * FROM exam_results")
    suspend fun getAllExamResults(): List<ExamResultsEntity>

    @Query("SELECT * FROM element_scores WHERE examResultId = :id")
    suspend fun getElementScores(id: Long): List<ElementScoreEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExamResult(result: ExamResultsEntity): Long

    @Query("SELECT * FROM answer_keys WHERE examCode = :examCode AND testNumber = :test AND setNumber = :set")
    suspend fun getAnswerKey(examCode: String, test: Int, set: Int): AnswerKeyEntity?

    @Query("DELETE FROM answer_keys WHERE examCode = :examCode AND testNumber = :test AND setNumber = :set")
    suspend fun deleteTest(examCode: String, test: Int, set: Int)

    @Query("SELECT * FROM answer_keys WHERE examCode = :examCode AND setNumber = :set")
    suspend fun getAnswerKeysForExam(examCode: String, set: Int): List<AnswerKeyEntity>  // ← renamed
}
