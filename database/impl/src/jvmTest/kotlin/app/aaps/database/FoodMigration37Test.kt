package app.aaps.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.aaps.database.di.databaseMigrations
import app.aaps.database.entities.TABLE_FOODS
import com.google.common.truth.Truth.assertThat
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The foods table is built again by the 36 to 37 migration: the values change from INTEGER to REAL,
 * and four local columns are added. Every food must come through with the same values.
 *
 * Run on the JVM with the bundled SQLite, like `InsulinConfigMigration33Test`: the SQL is in commonMain
 * and the engine is the same on every platform. The table is created as `36.json` declares it.
 */
class FoodMigration37Test {

    private val directory = createTempDirectory("aaps-food-migration-test")
    private val connection: SQLiteConnection = BundledSQLiteDriver().open("$directory/aaps.db")

    @AfterTest
    fun tearDown() {
        connection.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a food keeps its values, its Nightscout id and its links`() {
        createV36Table()
        insertFood(id = 1, name = "Apple", carbs = "14", fat = "0", protein = "1", energy = "218", nightscoutId = "'ns-1'", referenceId = "NULL")
        insertFood(id = 2, name = "Apple", carbs = "12", fat = "NULL", protein = "NULL", energy = "NULL", nightscoutId = "NULL", referenceId = "1")

        migrate36to37()

        assertThat(rowCount()).isEqualTo(2)
        connection.prepare("SELECT name, carbs, fat, protein, energy, portion, unit, gi, nightscoutId, referenceId FROM `$TABLE_FOODS` ORDER BY id").use { s ->
            check(s.step())
            assertThat(s.getText(0)).isEqualTo("Apple")
            assertThat(s.getDouble(1)).isEqualTo(14.0)
            assertThat(s.getDouble(2)).isEqualTo(0.0)
            assertThat(s.getDouble(3)).isEqualTo(1.0)
            assertThat(s.getDouble(4)).isEqualTo(218.0)
            assertThat(s.getDouble(5)).isEqualTo(100.0)
            assertThat(s.getText(6)).isEqualTo("g")
            assertThat(s.getLong(7)).isEqualTo(2)
            assertThat(s.getText(8)).isEqualTo("ns-1")
            assertThat(s.isNull(9)).isTrue()

            check(s.step())
            assertThat(s.getDouble(1)).isEqualTo(12.0)
            // An empty value stays empty: no fat is not 0 g of fat.
            assertThat(s.isNull(2)).isTrue()
            assertThat(s.isNull(3)).isTrue()
            assertThat(s.isNull(4)).isTrue()
            assertThat(s.getLong(9)).isEqualTo(1)
        }
    }

    @Test
    fun `the values are stored as REAL, so a decimal is kept`() {
        createV36Table()
        insertFood(id = 1, name = "Bread", carbs = "48", fat = "3", protein = "9", energy = "1100", nightscoutId = "NULL", referenceId = "NULL")

        migrate36to37()
        connection.execSQL("UPDATE `$TABLE_FOODS` SET carbs = 14.8, fat = 0.4 WHERE id = 1")

        connection.prepare("SELECT carbs, fat, typeof(carbs), typeof(protein) FROM `$TABLE_FOODS`").use { s ->
            check(s.step())
            assertThat(s.getDouble(0)).isEqualTo(14.8)
            assertThat(s.getDouble(1)).isEqualTo(0.4)
            // CAST(... AS REAL) in the copy: an old whole number is a real number now, not an integer.
            assertThat(s.getText(2)).isEqualTo("real")
            assertThat(s.getText(3)).isEqualTo("real")
        }
    }

    @Test
    fun `the new columns are empty on old foods`() {
        createV36Table()
        insertFood(id = 1, name = "Apple", carbs = "14", fat = "0", protein = "0", energy = "0", nightscoutId = "NULL", referenceId = "NULL")

        migrate36to37()

        connection.prepare("SELECT barcode, image, note, fiber FROM `$TABLE_FOODS`").use { s ->
            check(s.step())
            assertThat(s.isNull(0)).isTrue()
            assertThat(s.isNull(1)).isTrue()
            assertThat(s.isNull(2)).isTrue()
            assertThat(s.isNull(3)).isTrue()
        }
    }

    @Test
    fun `the indexes are there after the rebuild`() {
        createV36Table()

        migrate36to37()

        val indexes = mutableListOf<String>()
        connection.prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$TABLE_FOODS'").use { s ->
            while (s.step()) indexes.add(s.getText(0))
        }
        assertThat(indexes).containsAtLeast("index_foods_nightscoutId", "index_foods_referenceId", "index_foods_barcode")
    }

    private fun migrate36to37() =
        databaseMigrations.single { it.startVersion == 36 && it.endVersion == 37 }.migrate(connection)

    private fun rowCount(): Long =
        connection.prepare("SELECT COUNT(*) FROM `$TABLE_FOODS`").use { s -> if (s.step()) s.getLong(0) else 0L }

    /** Values are written as SQL text, so a test can pass `NULL`. */
    private fun insertFood(id: Long, name: String, carbs: String, fat: String, protein: String, energy: String, nightscoutId: String, referenceId: String) =
        connection.execSQL(
            "INSERT INTO `$TABLE_FOODS` (id, version, dateCreated, isValid, referenceId, name, category, subCategory, portion, carbs, fat, protein, " +
                "energy, unit, gi, nightscoutId) " +
                "VALUES ($id, 0, 0, 1, $referenceId, '$name', 'Fruit', NULL, 100.0, $carbs, $fat, $protein, $energy, 'g', 2, $nightscoutId)"
        )

    /** The table as `36.json` declares it. */
    private fun createV36Table() {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `$TABLE_FOODS` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `version` INTEGER NOT NULL, " +
                "`dateCreated` INTEGER NOT NULL, `isValid` INTEGER NOT NULL, `referenceId` INTEGER, `name` TEXT NOT NULL, `category` TEXT, " +
                "`subCategory` TEXT, `portion` REAL NOT NULL, `carbs` INTEGER NOT NULL, `fat` INTEGER, `protein` INTEGER, `energy` INTEGER, " +
                "`unit` TEXT NOT NULL, `gi` INTEGER, `nightscoutSystemId` TEXT, `nightscoutId` TEXT, `pumpType` TEXT, `pumpSerial` TEXT, " +
                "`temporaryId` INTEGER, `pumpId` INTEGER, `startId` INTEGER, `endId` INTEGER, " +
                "FOREIGN KEY(`referenceId`) REFERENCES `foods`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )"
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_foods_nightscoutId` ON `$TABLE_FOODS` (`nightscoutId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_foods_referenceId` ON `$TABLE_FOODS` (`referenceId`)")
    }
}
