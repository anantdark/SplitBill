package com.anant.splitbill.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.anant.splitbill.data.model.EntryType

class Converters {
    @TypeConverter
    fun toEntryType(value: String): EntryType = EntryType.valueOf(value)

    @TypeConverter
    fun fromEntryType(value: EntryType): String = value.name
}

@Database(
    entities = [RoomEntity::class, MemberEntity::class, EntryEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun roomDao(): RoomDao
    abstract fun memberDao(): MemberDao
    abstract fun entryDao(): EntryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE entries ADD COLUMN deletedAtEpochMs INTEGER")
                db.execSQL("ALTER TABLE entries ADD COLUMN deletedByMemberId TEXT")
                db.execSQL("ALTER TABLE entries ADD COLUMN deletedByMemberName TEXT")
                db.execSQL("ALTER TABLE entries ADD COLUMN deletedByDeviceId TEXT")
                db.execSQL("ALTER TABLE entries ADD COLUMN deletedByDeviceName TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN loggedByMemberId TEXT")
                db.execSQL("ALTER TABLE entries ADD COLUMN loggedByMemberName TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "splitbill.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
