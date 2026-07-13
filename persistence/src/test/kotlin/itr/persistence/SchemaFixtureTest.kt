package itr.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchemaFixtureTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ItrDatabase::class.java)

    @Test fun `exported v1 schema is present and creates a database`() {
        // reads persistence/schemas via the test-assets srcDir configured in build.gradle.kts.
        // Proves the exported v1 JSON exists and is parseable. NOTE: this does NOT by itself
        // catch an entity change (KSP would overwrite 1.json without a version bump) — the
        // committed schema diff in code review is the real drift guard. Data-preserving
        // runMigrationsAndValidate tests arrive with v2.
        helper.createDatabase(TEST_DB, 1).close()
    }

    companion object { private const val TEST_DB = "schema-fixture.db" }
}
