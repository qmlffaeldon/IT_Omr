package com.example.it_scann.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "answer_keys",
    primaryKeys = ["examCode", "testNumber", "setNumber"]  // examCode added
)
data class AnswerKeyEntity(
    val examCode: String,       // e.g. "TYPEC-020304"
    val testNumber: Int,
    val setNumber: Int,
    val answerString: String
)

@Entity(
    tableName = "exam_results",
    indices = [Index(value = ["examCode", "setNumber", "seatNumber"], unique = true)]
)
data class ExamResultsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var examCode: String,
    var setNumber: Int,
    var seatNumber: Int,
    val totalScore: Int,
    val isAbsent: Boolean = false,
    val dateTaken: Long = System.currentTimeMillis(),
    var examDate: String? = "",
    var region: String? = "",
    var placeOfExam: String? = "",
    var completeRow: String = "No"
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