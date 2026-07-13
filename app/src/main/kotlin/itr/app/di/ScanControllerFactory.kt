package itr.app.di

import android.content.Context
import com.google.ar.core.Session
import dagger.hilt.android.qualifiers.ApplicationContext
import itr.corearcore.ArCoreSession
import itr.corearcore.SessionLifecycle
import itr.corearcore.UnrotatedFullImageTransform
import itr.persistence.ScanRepository
import itr.scan.DetectorFactory
import itr.scan.ScanController
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanControllerFactory @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repository: ScanRepository,
) {
    fun create(session: Session, lifecycle: SessionLifecycle): ScanController {
        val detector = DetectorFactory.create(ctx)
        val arSession = ArCoreSession(ctx, session, lifecycle, UnrotatedFullImageTransform)
        return ScanController(arSession, detector, repository)
    }
}
