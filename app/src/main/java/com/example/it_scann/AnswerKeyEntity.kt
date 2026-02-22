package com.example.it_scann

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "answer_keys",
    primaryKeys = ["testNumber", "questionNumber"]
)
data class AnswerKeyEntity(
    val testNumber: Int,
    val questionNumber: Int,
    val answer: Int
)


@Entity(
    tableName = "exam_results",
    indices = [Index(value = ["testType", "setNumber", "seatNumber"], unique = true)]
)
data class ExamResultsEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val testType: String,
    val setNumber: Int,
    val seatNumber: Int,

    val totalScore: Int,
    val dateTaken: Long = System.currentTimeMillis()
)


@Entity(
    tableName = "element_scores",
    primaryKeys = ["examResultId", "elementNumber"],
    foreignKeys = [
        ForeignKey(
            entity = ExamResultsEntity::class,
            parentColumns = ["id"],
            childColumns = ["examResultId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("examResultId")]
)
data class ElementScoreEntity(
    val examResultId: Long,
    val elementNumber: Int,
    val score: Int,
    val maxScore: Int = 25
)

data class ExamWithElements(
    @Embedded val exam: ExamResultsEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "examResultId"
    )
    val elements: List<ElementScoreEntity>
)