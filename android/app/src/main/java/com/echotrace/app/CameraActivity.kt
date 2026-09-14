package com.echotrace.app

import androidx.activity.ComponentActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File

/** Opens the device camera straight from the widget circle. Capture -> preview
 *  with an optional caption -> explicit send. Every failure path is visible:
 *  nothing in this flow may ever again look like "the window just closed". */
class CameraActivity : ComponentActivity() {
    private var pendingFile: File? = null
    private var sent = false
    private val ui = Handler(Looper.getMainLooper())

    companion object { const val REQ_CAPTURE = 4101 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_camera)
            findViewById<Button>(R.id.closeBtn).setOnClickListener { finish() }
            // Recover a capture orphaned by process death (file + path survive).
            val restored = savedInstanceState?.getString("pendingPath")
                ?: with(Prefs) { pendingCapturePath }
            if (restored != null) {
                val f = File(restored)
                if (f.exists() && f.length() > 0) {
                    pendingFile = f
                    showPreview(f)
                    return
                }
                with(Prefs) { pendingCapturePath = null }
            }
            startCapture()
        } catch (t: Throwable) {
            showFatal(getString(R.string.camera_open_error, t.javaClass.simpleName))
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("pendingPath", pendingFile?.absolutePath)
    }

    private fun startCapture() {
        try {
            val dir = File(cacheDir, "captures").apply { mkdirs() }
            val f = File(dir, "cap_${System.currentTimeMillis()}.jpg")
            pendingFile = f
            with(Prefs) { pendingCapturePath = f.absolutePath }
            val uri = FileProvider.getUriForFile(this, "com.echotrace.app.fileprovider", f)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // FileProvider needs an explicit grant per camera package; several OEM
            // cameras ignore the intent flag and would otherwise fail to save.
            val targets = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            for (r in targets) {
                grantUriPermission(
                    r.activityInfo.packageName, uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            startActivityForResult(intent, REQ_CAPTURE)
        } catch (t: Throwable) {
            showFatal(getString(R.string.camera_open_error, t.javaClass.simpleName))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        try {
            val f = pendingFile
            if (resultCode == RESULT_OK && f != null && f.exists() && f.length() > 0) {
                showPreview(f)
            } else if (resultCode == RESULT_CANCELED) {
                f?.delete()
                with(Prefs) { pendingCapturePath = null }
                toast(getString(R.string.capture_canceled))
                finish()
            } else {
                // The camera said OK but wrote nothing (grant failure on some OEMs).
                f?.delete()
                with(Prefs) { pendingCapturePath = null }
                showFatal(getString(R.string.photo_read_error, "empty capture file"))
            }
        } catch (t: Throwable) {
            showFatal(getString(R.string.photo_read_error, t.javaClass.simpleName))
        }
    }

    private fun showPreview(f: File) {
        pendingFile = f
        val bmp = try { Imaging.decodeSampledRotated(f, 1280) } catch (t: Throwable) { null }
        if (bmp == null) {
            showFatal(getString(R.string.photo_read_error, "decode failed"))
            return
        }
        findViewById<ImageView>(R.id.previewImage).setImageBitmap(bmp)
        findViewById<View>(R.id.errorCard).visibility = View.GONE
        val preview = findViewById<View>(R.id.previewUi)
        preview.visibility = View.VISIBLE
        preview.alpha = 0f
        preview.animate().alpha(1f).setDuration(220).start()
        findViewById<Button>(R.id.sendBtn).setOnClickListener { sendPhoto(f) }
        findViewById<Button>(R.id.cancelBtn).setOnClickListener {
            f.delete()
            with(Prefs) { pendingCapturePath = null }
            toast(getString(R.string.capture_canceled))
            finish()
        }
    }

    private fun sendPhoto(f: File) {
        if (sent) return
        sent = true
        val caption = findViewById<EditText>(R.id.captionInput).text.toString().trim()
        try {
            val data = Data.Builder()
                .putString("path", f.absolutePath)
                .putString("caption", caption)
                .build()
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(this).enqueue(req)
            with(Prefs) { pendingCapturePath = null }
            showSentCard()
        } catch (t: Throwable) {
            sent = false
            toast(getString(R.string.send_error, t.javaClass.simpleName))
        }
    }

    private fun showSentCard() {
        findViewById<View>(R.id.previewUi).visibility = View.GONE
        findViewById<View>(R.id.errorCard).visibility = View.GONE
        val card = findViewById<View>(R.id.sentCard)
        card.visibility = View.VISIBLE
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

    /** A failure state the user can actually see - never a silent close. */
    private fun showFatal(message: String) {
        try {
            findViewById<View>(R.id.previewUi)?.visibility = View.GONE
            findViewById<View>(R.id.sentCard)?.visibility = View.GONE
            val err = findViewById<View>(R.id.errorCard)
            err.visibility = View.VISIBLE
            findViewById<TextView>(R.id.errorText).text = message
        } catch (t: Throwable) {
            toast(message)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
