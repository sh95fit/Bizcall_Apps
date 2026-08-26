package com.bizcall.app.queue

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FailedUploadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: FailedUpload)

    @Delete
    suspend fun delete(item: FailedUpload)

    @Query("SELECT * FROM failed_uploads ORDER BY failedAt DESC")
    fun getAll(): Flow<List<FailedUpload>>

    @Query("SELECT * FROM failed_uploads ORDER BY failedAt DESC")
    suspend fun getAllOnce(): List<FailedUpload>

    @Query("DELETE FROM failed_uploads WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT COUNT(*) FROM failed_uploads")
    fun getCount(): Flow<Int>
}
