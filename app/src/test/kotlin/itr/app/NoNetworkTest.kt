package itr.app

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoNetworkTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `the merged manifest requests EXACTLY the CAMERA permission (no INTERNET leak from any AAR)`() {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val perms = (info.requestedPermissions?.toSet() ?: emptySet())
        assertEquals(setOf(android.Manifest.permission.CAMERA), perms)
    }

    @Test fun `cloud backup is disabled`() {
        val ai = ctx.applicationInfo
        assertFalse(ai.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP != 0, "allowBackup must be false")
    }
}
