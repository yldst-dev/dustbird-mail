package app.dustbird.mail.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "probe")
data class ProbeEntity(@PrimaryKey val id: Long, val value: String)

@Dao
interface ProbeDao {
    @Insert
    suspend fun insert(entity: ProbeEntity)

    @Query("SELECT COUNT(*) FROM probe")
    suspend fun count(): Int
}

@Database(entities = [ProbeEntity::class], version = 1, exportSchema = true)
abstract class ProbeDatabase : RoomDatabase() {
    abstract fun probeDao(): ProbeDao
}
