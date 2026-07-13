package itr.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import itr.core.render.Units
import itr.core.settings.AppSettings
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {
    private fun file() = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
        "settings-${System.nanoTime()}.preferences_pb")

    @Test fun `defaults, then a change persists to disk (reopened via a fresh DataStore)`() = runTest {
        val f = file()
        val job1 = kotlinx.coroutines.SupervisorJob()
        val scope1 = kotlinx.coroutines.CoroutineScope(job1 + kotlinx.coroutines.Dispatchers.IO)
        val ds1 = PreferenceDataStoreFactory.create(scope = scope1) { f }
        val repo = SettingsRepository(ds1)
        assertEquals(AppSettings.DEFAULT, repo.get())
        repo.setUnits(Units.IMPERIAL); repo.setSnap(false); repo.setDiagnosticLog(true)
        job1.cancelAndJoin()
        val job2 = kotlinx.coroutines.SupervisorJob()
        val scope2 = kotlinx.coroutines.CoroutineScope(job2 + kotlinx.coroutines.Dispatchers.IO)
        val ds2 = PreferenceDataStoreFactory.create(scope = scope2) { f }
        assertEquals(AppSettings(Units.IMPERIAL, snapByDefault = false, diagnosticLog = true), SettingsRepository(ds2).get())
        job2.cancelAndJoin()
    }
}
