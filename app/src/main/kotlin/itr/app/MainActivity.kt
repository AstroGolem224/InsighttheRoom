package itr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import dagger.hilt.android.AndroidEntryPoint
import itr.app.di.ScanControllerFactory
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var scanControllerFactory: ScanControllerFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ItrNav(scanControllerFactory)
            }
        }
    }
}
