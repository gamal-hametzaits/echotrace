package com.echotrace.app

import androidx.activity.ComponentActivity
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File

/** Opens the device camera straight from the widget circle. Capture -> automatic upload. */
class CameraActivity : ComponentActivity() {
    private var pending: Uri? = null
    private var pendingFile: File? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingFile
        if (ok && f != null && f.exists() && f.length() > 0) {
            val data = Data.Builder().putString("path", f.absolutePath).build()
            WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<UploadWorker>().setInputData(data).build())
        } else {
            f?.delete()
        }
        finish()
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
}
