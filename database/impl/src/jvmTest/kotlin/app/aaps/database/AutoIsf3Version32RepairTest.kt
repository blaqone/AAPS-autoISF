package app.aaps.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.aaps.database.di.AUTOISF3_ACTIONS
import app.aaps.database.di.repairAutoIsf3Version32
import app.aaps.database.entities.TABLE_TOTAL_DAILY_DOSES
import app.aaps.database.entities.TABLE_USER_ENTRY
import app.aaps.database.entities.UserEntry
import com.google.common.truth.Truth.assertThat
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * AutoISF fork: a database that AutoISF 3.x (3.4.2.6+aisf3.2.1) left at version 32 must open in this build.
 *
 * AutoISF 3.x used 32 for its table `autoIsfValues`, AAPS 4 for the column `carbInsulin`. Without the repair
 * Room skipped 31 to 32, the column was missing and the app crashed on every start.
 */
class AutoIsf3Version32RepairTest {

    private val directory = createTempDirectory("aaps-autoisf3-repair-test")
    private val connection: SQLiteConnection = BundledSQLiteDriver().open("$directory/aaps.db")

    @AfterTest
    fun tearDown() {
        connection.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `an AutoISF 3 database gets the column and loses the old table`() {
        createTables(withCarbInsulin = false)
        connection.execSQL("CREATE TABLE `autoIsfValues` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL)")
        insertTdd()

        repairAutoIsf3Version32(connection)

        assertThat(columns(TABLE_TOTAL_DAILY_DOSES)).contains("carbInsulin")
        assertThat(tableExists("autoIsfValues")).isFalse()
        // cached data, recalculated later, like the 31 to 32 step of AAPS 4
        assertThat(count(TABLE_TOTAL_DAILY_DOSES)).isEqualTo(0)
    }

    @Test
    fun `the user entries of AutoISF 3 become UNKNOWN, others stay`() {
        createTables(withCarbInsulin = false)
        (AUTOISF3_ACTIONS + "BOLUS").forEach { insertUserEntry(it) }

        repairAutoIsf3Version32(connection)

        assertThat(actions()).containsExactly("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", "BOLUS")
    }

    @Test
    fun `a database of AAPS 4 is not changed`() {
        createTables(withCarbInsulin = true)
        insertTdd()
        insertUserEntry("BOLUS")

        repairAutoIsf3Version32(connection)

        // the TDD rows are kept: nothing was missing
        assertThat(count(TABLE_TOTAL_DAILY_DOSES)).isEqualTo(1)
        assertThat(actions()).containsExactly("BOLUS")
    }

    @Test
    fun `running it twice does no harm`() {
        createTables(withCarbInsulin = false)
        repairAutoIsf3Version32(connection)
        insertTdd()

        repairAutoIsf3Version32(connection)

        assertThat(count(TABLE_TOTAL_DAILY_DOSES)).isEqualTo(1)
    }

    @Test
    fun `an unknown action is read as UNKNOWN instead of crashing`() {
        assertThat(Converters().toAction("AUTOISF_ENABLED")).isEqualTo(UserEntry.Action.UNKNOWN)
        assertThat(Converters().toAction("BOLUS")).isEqualTo(UserEntry.Action.BOLUS)
        assertThat(Converters().toAction(null)).isNull()
    }

    private fun createTables(withCarbInsulin: Boolean) {
        val carbInsulin = if (withCarbInsulin) ", `carbInsulin` REAL NOT NULL DEFAULT 0" else ""
        connection.execSQL(
            "CREATE TABLE `$TABLE_TOTAL_DAILY_DOSES` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`basalAmount` REAL NOT NULL, `bolusAmount` REAL NOT NULL, `totalAmount` REAL NOT NULL, `carbs` REAL NOT NULL$carbInsulin)"
        )
        connection.execSQL(
            "CREATE TABLE `$TABLE_USER_ENTRY` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`utcOffset` INTEGER NOT NULL, `action` TEXT NOT NULL, `source` TEXT NOT NULL, `note` TEXT NOT NULL, `values` TEXT NOT NULL)"
        )
    }

    private fun insertTdd() =
        connection.execSQL("INSERT INTO `$TABLE_TOTAL_DAILY_DOSES` (timestamp, basalAmount, bolusAmount, totalAmount, carbs) VALUES (1700000000000, 10, 20, 30, 150)")

    private fun insertUserEntry(action: String) =
        connection.execSQL("INSERT INTO `$TABLE_USER_ENTRY` (timestamp, utcOffset, action, source, note, `values`) VALUES (1700000000000, 0, '$action', 'Aaps', '', '[]')")

    private fun columns(table: String): List<String> =
        connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
            buildList { while (statement.step()) add(statement.getText(1)) }
        }

    private fun tableExists(table: String): Boolean =
        connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$table'").use { it.step() }

    private fun count(table: String): Long =
        connection.prepare("SELECT COUNT(*) FROM `$table`").use { statement -> if (statement.step()) statement.getLong(0) else 0L }

    private fun actions(): List<String> =
        connection.prepare("SELECT `action` FROM `$TABLE_USER_ENTRY` ORDER BY id").use { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }
}
