package com.example.it_scann

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AnswerKeyEntity::class], version = 2)
abstract class AppDatabase : RoomDatabase() {

    abstract fun answerKeyDao(): AnswerKeyDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "answer_key_db"
                ).build().also { INSTANCE = it }
            }
    }
}
