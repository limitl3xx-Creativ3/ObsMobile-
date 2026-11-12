package dev.obsmobile.capture

import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Helper that asks the user for screen-capture permission and hands the
 * resulting MediaProjection to [ScreenCaptureService].
 */
class ProjectionPermissionActivity : AppCompatActivity() {

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data: Intent? = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ScreenCaptureService.start(this, result.resultCode, data)
            }
            finish()
        }

    override fun onResume() {
        super.onResume()
        val mgr = getSystemService(MediaProjectionManager::class.java)
        launcher.launch(mgr.createScreenCaptureIntent())
    }

    companion object {
        fun request(activity: AppCompatActivity) {
            activity.startActivity(
                android.content.Intent(activity, ProjectionPermissionActivity::class.java)
            )
        }
    }
}
