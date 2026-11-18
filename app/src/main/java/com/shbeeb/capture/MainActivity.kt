package com.shbeeb.capture

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var projectionManager: MediaProjectionManager

    private var pendingAction: String = RecordingService.ACTION_START

    private val capturePermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                if (pendingAction == RecordingService.ACTION_SCREENSHOT) {
                    startService(
                        Intent(this, RecordingService::class.java).apply {
                            action = RecordingService.ACTION_SCREENSHOT
                            putExtra(RecordingService.EXTRA_RESULT_CODE, result.resultCode)
                            putExtra(RecordingService.EXTRA_RESULT_DATA, result.data)
                        }
                    )
                } else {
                    val audioMode = findViewById<RadioGroup>(R.id.audioGroup).checkedRadioButtonId
                    val mode = when (audioMode) {
                        R.id.systemRadio -> RecordingService.AUDIO_SYSTEM
                        R.id.bothRadio -> RecordingService.AUDIO_BOTH
                        else -> RecordingService.AUDIO_MIC
                    }
                    startService(
                        Intent(this, RecordingService::class.java).apply {
                            action = RecordingService.ACTION_START
                            putExtra(RecordingService.EXTRA_RESULT_CODE, result.resultCode)
                            putExtra(RecordingService.EXTRA_RESULT_DATA, result.data)
                            putExtra(RecordingService.EXTRA_AUDIO_MODE, mode)
                        }
                    )
                    startButton.isEnabled = false
                    stopButton.isEnabled = true
                    statusText.text = "يتم التسجيل..."
                }
            } else {
                statusText.text = "تم إلغاء إذن تسجيل الشاشة"
            }
        }

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestScreenCapture(RecordingService.ACTION_START) else
                statusText.text = "يلزم إذن الميكروفون للتسجيل بالصوت"
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(RecordingService.EXTRA_STATUS) ?: return
            statusText.text = status
            if (status.startsWith("تم حفظ") || status.startsWith("تم إيقاف")) {
                startButton.isEnabled = true
                stopButton.isEnabled = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.screenButton).setOnClickListener {
            requestScreenCapture(RecordingService.ACTION_START)
        }
        findViewById<Button>(R.id.screenshotButton).setOnClickListener {
            requestScreenCapture(RecordingService.ACTION_SCREENSHOT)
        }
        startButton.setOnClickListener {
            val needsMic = findViewById<RadioGroup>(R.id.audioGroup).checkedRadioButtonId ==
                R.id.micRadio ||
                findViewById<RadioGroup>(R.id.audioGroup).checkedRadioButtonId == R.id.bothRadio
            if (needsMic &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                requestScreenCapture(RecordingService.ACTION_START)
            }
        }
        stopButton.setOnClickListener {
            sendBroadcast(Intent(RecordingService.ACTION_STOP).setPackage(packageName))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(statusReceiver, IntentFilter(RecordingService.ACTION_STATUS),
            RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    private fun requestScreenCapture(action: String) {
        pendingAction = action
        capturePermission.launch(projectionManager.createScreenCaptureIntent())
    }
}
