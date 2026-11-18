package com.shbeeb.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.shbeeb.capture.START"
        const val ACTION_STOP = "com.shbeeb.capture.STOP"
        const val ACTION_SCREENSHOT = "com.shbeeb.capture.SCREENSHOT"
        const val ACTION_STATUS = "com.shbeeb.capture.STATUS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_AUDIO_MODE = "audio_mode"
        const val EXTRA_STATUS = "status"
        const val AUDIO_MIC = 1
        const val AUDIO_SYSTEM = 2
        const val AUDIO_BOTH = 3
        private const val CHANNEL_ID = "shbeeb_capture"
        private const val NOTIFICATION_ID = 101
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP) stopRecording()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerReceiver(stopReceiver, IntentFilter(ACTION_STOP), RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }

        override fun onDestroy() {
            unregisterReceiver(stopReceiver)
            super.onDestroy()
        }
        if (intent?.action == ACTION_SCREENSHOT) {
            startForegroundNotification("Preparing screenshot")
            createProjection(intent)
            Handler(Looper.getMainLooper()).postDelayed({ takeScreenshot() }, 350)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) {
            val audioMode = intent.getIntExtra(EXTRA_AUDIO_MODE, AUDIO_MIC)
            startForegroundNotification("Recording screen")
            createProjection(intent)
            Handler(Looper.getMainLooper()).postDelayed({
                startRecording(audioMode)
            }, 350)
        }
        return START_NOT_STICKY
    }

    private fun createProjection(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(code, data!!)
    }

    private fun startRecording(audioMode: Int) {
        try {
            val metrics = getDisplayMetrics()
            val density = metrics.densityDpi
            val width = metrics.widthPixels
            val height = metrics.heightPixels

            val recorder = if (Build.VERSION.SDK_INT >= 31)
                MediaRecorder(this)
            else
                @Suppress("DEPRECATION") MediaRecorder()

            if (audioMode == AUDIO_MIC || audioMode == AUDIO_BOTH) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(8_000_000)
            if (audioMode == AUDIO_MIC || audioMode == AUDIO_BOTH) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }

            val file = createOutputFile("mp4")
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "Shbeeb-Capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            )

            if (audioMode == AUDIO_SYSTEM) {
                broadcastStatus("صوت النظام يحتاج دعمًا خاصًا بجهاز هذا الإصدار")
            } else if (audioMode == AUDIO_BOTH) {
                broadcastStatus("يتم التسجيل بالميكروفون")
            } else {
                broadcastStatus("يتم التسجيل بالميكروفون")
            }
        } catch (error: Exception) {
            broadcastStatus("تعذر بدء التسجيل: ${error.message}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
            virtualDisplay?.release()
            virtualDisplay = null
            mediaProjection?.stop()
            mediaProjection = null
            broadcastStatus("تم إيقاف التسجيل وحفظه في معرض الهاتف")
        } catch (error: Exception) {
            broadcastStatus("تعذر إيقاف التسجيل: ${error.message}")
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun takeScreenshot() {
        try {
            val metrics = getDisplayMetrics()
            val reader = ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                2
            )
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "Shbeeb-Capture-Screenshot",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
            Handler(Looper.getMainLooper()).postDelayed({
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * metrics.widthPixels
                    val bitmap = Bitmap.createBitmap(
                        metrics.widthPixels + rowPadding / pixelStride,
                        metrics.heightPixels,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    saveScreenshot(bitmap)
                    image.close()
                    bitmap.recycle()
                } else {
                    broadcastStatus("تعذر أخذ لقطة الشاشة")
                }
                reader.close()
                virtualDisplay?.release()
                virtualDisplay = null
                mediaProjection?.stop()
                mediaProjection = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }, 150)
        } catch (error: Exception) {
            broadcastStatus("تعذر أخذ لقطة الشاشة: ${error.message}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Shbeeb_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Shbeeb Capture")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            broadcastStatus("تم حفظ لقطة الشاشة")
        } else {
            broadcastStatus("تعذر حفظ لقطة الشاشة")
        }
    }

    private fun createOutputFile(extension: String): File {
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Shbeeb Capture")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "Shbeeb_${System.currentTimeMillis()}.$extension")
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds.let {
                metrics.widthPixels = it.width()
                metrics.heightPixels = it.height()
            }
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shbeeb Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(text: String) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shbeeb Capture")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_away)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, status))
    }
}
