package com.user.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotesPersistenceTest {
    private lateinit var db: ChatDatabase
    private lateinit var dao: NoteDao

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChatDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ChatDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.noteDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndReadBack() = runTest {
        val note = Note(title = "Test", body = "Hello world", tags = """["kotlin"]""")
        val id = dao.insert(note)
        val retrieved = dao.getById(id)
        assertEquals("Test", retrieved?.title)
        assertEquals("Hello world", retrieved?.body)
    }

    @Test
    fun getAllReturnsInsertedNotes() = runTest {
        dao.insert(Note(title = "A", body = "body a"))
        dao.insert(Note(title = "B", body = "body b"))
        val all = dao.getAll().first()
        assertEquals(2, all.size)
    }

    @Test
    fun deleteRemovesNote() = runTest {
        val id = dao.insert(Note(title = "Temp", body = "delete me"))
        val note = dao.getById(id)!!
        dao.delete(note)
        assertNull(dao.getById(id))
    }

    @Test
    fun tagsStoredAndRetrievedCorrectly() = runTest {
        val tagsJson = """["android","kotlin","notes"]"""
        val id = dao.insert(Note(title = "Tagged", body = "has tags", tags = tagsJson))
        val retrieved = dao.getById(id)
        assertEquals(tagsJson, retrieved?.tags)
    }

    @Test
    fun searchByTagReturnsMatchingNotes() = runTest {
        dao.insert(Note(title = "Note A", body = "", tags = """["android"]"""))
        dao.insert(Note(title = "Note B", body = "", tags = """["kotlin"]"""))
        dao.insert(Note(title = "Note C", body = "", tags = """["android","kotlin"]"""))
        dao.insert(Note(title = "Note D", body = "", tags = """["androidx"]"""))
        val results = dao.searchByTag("android").first()
        assertEquals(2, results.size)
        assertTrue(results.all { it.tags.contains("\"android\"") })
    }

    @Test
    fun migrate9To10CreatesNotesTable() {
        migrationHelper.createDatabase(TEST_DB, 9).apply {
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DB,
            10,
            true,
            ChatDatabase.MIGRATION_9_10
        ).apply {
            execSQL(
                "INSERT INTO notes (title, body, createdAt, tags) VALUES " +
                    "('Migrated', 'Body', 123, '[\"migration\"]')"
            )
            query("SELECT title, tags FROM notes").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Migrated", cursor.getString(0))
                assertEquals("""["migration"]""", cursor.getString(1))
            }
            close()
        }
    }

    companion object {
        private const val TEST_DB = "notes-migration-test"
    }
}
