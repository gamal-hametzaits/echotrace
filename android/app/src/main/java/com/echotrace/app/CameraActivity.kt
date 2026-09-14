package com.echotrace.app

import androidx.activity.ComponentActivity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File

/** Opens the device camera straight from the widget circle. Capture -> automatic upload,
 *  then a brief "on its way" card so the tap feels answered before the widget updates. */
class CameraActivity : ComponentActivity() {
    private var pending: Uri? = null
    private var pendingFile: File? = null
    private val ui = Handler(Looper.getMainLooper())

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingFile
        if (ok && f != null && f.exists() && f.length() > 0) {
            val data = Data.Builder().putString("path", f.absolutePath).build()
            WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<UploadWorker>().setInputData(data).build())
            showSentCard()
        } else {
            f?.delete()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val f = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        pendingFile = f
        val uri = FileProvider.getUriForFile(this, "com.echotrace.app.fileprovider", f)
        pending = uri
        takePicture.launch(uri)
    }

    private fun showSentCard() {
        setContentView(R.layout.activity_camera)
        val card = findViewById<android.view.View>(R.id.sentCard)
        val check = findViewById<TextView>(R.id.sentCheck)
        card.alpha = 0f
        card.scaleX = 0.7f; card.scaleY = 0.7f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(280).setInterpolator(OvershootInterpolator(1.4f)).start()
        check.rotation = -30f
        check.animate().rotation(0f).setDuration(350).setStartDelay(120)
            .setInterpolator(OvershootInterpolator(2f)).start()
        ui.postDelayed({
            card.animate().alpha(0f).setDuration(220).withEndAction { finish() }.start()
        }, 950)
    }
}
