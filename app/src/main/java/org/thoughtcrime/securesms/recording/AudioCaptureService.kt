/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.recording

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.signal.core.util.SafeForegroundService
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.recording.RecordingStorage.RecordingFile
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that captures audio from active calls.
 *
 * This service uses Android's AudioRecord API to capture audio data during a call.
 * It prioritizes stability and attempts to use the best available audio source.
 */
class AudioCaptureService : SafeForegroundService() {

    override val tag: String = Log.tag(AudioCaptureService::class.java)
    override val notificationId: Int = NOTIFICATION_ID

    companion object {
        private val TAG = Log.tag(AudioCaptureService::class.java)

        // Custom intent extras
        const val EXTRA_ACTION = "extra_action"
        const val EXTRA_RECIPIENT_ID = "extra_recipient_id"
        const val EXTRA_RECIPIENT_NAME = "extra_recipient_name"
        const val EXTRA_IS_VIDEO_CALL = "extra_is_video_call"

        // Custom actions passed via extra
        const val ACTION_EXTRA_START = "ACTION_START_RECORDING"
        const val ACTION_EXTRA_STOP = "ACTION_STOP_RECORDING"
        const val ACTION_EXTRA_PAUSE = "ACTION_PAUSE_RECORDING"
        const val ACTION_EXTRA_RESUME = "ACTION_RESUME_RECORDING"

        // Audio configuration - Must match RecordingStorage constants
        private const val SAMPLE_RATE = RecordingStorage.DEFAULT_SAMPLE_RATE
        private val CHANNEL_CONFIG = if (RecordingStorage.DEFAULT_CHANNELS == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 4

        // Notification
        const val NOTIFICATION_ID = 20001

        // Max recording duration (6 hours to respect Android's foreground service limit)
        private const val MAX_RECORDING_DURATION_MS = 6 * 60 * 60 * 1000L

        private var instance: AudioCaptureService? = null

        fun getInstance(): AudioCaptureService? = instance
    }

    // State management
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val recordingStartTime = AtomicLong(0L)

    // Audio resources
    private var audioRecord: AudioRecord? = null
    private var currentAudioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private var recordThread: Thread? = null
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Recording state
    private var currentRecording: RecordingFile? = null
    private var fileOutputStream: BufferedOutputStream? = null
    private var recordingStateListener: RecordingStateListener? = null

    // Storage
    private lateinit var recordingStorage: RecordingStorage

    /**
     * Listener interface for recording state changes.
     */
    interface RecordingStateListener {
        fun onRecordingStarted(recording: RecordingFile)
        fun onRecordingStopped(recording: RecordingFile?, durationMs: Long)
        fun onRecordingError(error: String)
    }

    fun setRecordingStateListener(listener: RecordingStateListener?) {
        recordingStateListener = listener
    }

    override fun onCreate() {
        Log.d(TAG, "[onCreate]")
        super.onCreate()
        instance = this
        
        RecordingManager.getInstance()?.let {
            setRecordingStateListener(it)
        }

        recordingStorage = RecordingStorage(this)
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    override fun getForegroundNotification(intent: Intent): Notification {
        val isRecording = isRunning.get()
        val recipientName = currentRecording?.recipientName ?: "Signal Call"
        val channelId = org.thoughtcrime.securesms.notifications.NotificationChannels.getInstance().CALLS
        
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(if (isRecording) getString(R.string.call_recording_notification_title, recipientName) else getString(R.string.app_name))
            .setContentText(if (isRecording) getString(R.string.call_recording_notification_text) else "")
            .setSmallIcon(R.drawable.ic_call_secure_white_24dp)
            .setOngoing(isRecording)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun serviceType(intent: Intent): Int {
        return if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
    }

    override fun onServiceStartCommandReceived(intent: Intent) {
        Log.d(TAG, "[onServiceStartCommandReceived] intent=$intent")

        val extraAction = intent.getStringExtra(EXTRA_ACTION)
        when (extraAction) {
            ACTION_EXTRA_START -> {
                val recipientId = intent.getStringExtra(EXTRA_RECIPIENT_ID) ?: return
                val recipientName = intent.getStringExtra(EXTRA_RECIPIENT_NAME) ?: "Unknown"
                val isVideoCall = intent.getBooleanExtra(EXTRA_IS_VIDEO_CALL, false)

                startRecording(recipientId, recipientName, isVideoCall)
            }
            ACTION_EXTRA_PAUSE -> {
                pauseRecording()
            }
            ACTION_EXTRA_RESUME -> {
                resumeRecording()
            }
            else -> {
                Log.w(TAG, "No valid extra action in start command")
            }
        }
    }

    override fun onServiceStopCommandReceived(intent: Intent) {
        Log.d(TAG, "[onServiceStopCommandReceived]")
        stopRecording()
    }

    /**
     * Starts recording a call.
     */
    private fun startRecording(recipientId: String, recipientName: String, isVideoCall: Boolean) {
        if (isRunning.get()) {
            Log.w(TAG, "Already recording, stopping previous recording first")
            stopRecording()
        }

        val startTime = System.currentTimeMillis()
        currentRecording = recordingStorage.createRecordingFile(
            recipientName = recipientName,
            isVideoCall = isVideoCall,
            startTime = startTime
        )

        if (!startAudioCapture()) {
            Log.e(TAG, "Failed to start audio capture")
            recordingStateListener?.onRecordingError("Failed to initialize audio recording")
            return
        }

        isRunning.set(true)
        recordingStartTime.set(startTime)

        acquireWakeLock()
        updateNotification()

        Log.i(TAG, "Recording started for: $recipientName (isVideo=$isVideoCall)")
        recordingStateListener?.onRecordingStarted(currentRecording!!)
    }

    /**
     * Starts the actual audio capture.
     */
    private fun startAudioCapture(): Boolean {
        // Try these sources in order of preference
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )

        for (source in sources) {
            if (tryStartAudioCapture(source)) {
                currentAudioSource = source
                Log.i(TAG, "Successfully started audio capture with source: $source")
                return true
            }
        }

        Log.e(TAG, "Failed to start audio capture with any source")
        return false
    }

    private fun tryStartAudioCapture(source: Int): Boolean {
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.w(TAG, "Invalid AudioRecord parameters for source $source")
                return false
            }
            
            val bufferSize = minBufferSize * BUFFER_SIZE_MULTIPLIER

            val recorder = AudioRecord(
                source,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                ENCODING,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord failed to initialize for source $source, state: ${recorder.state}")
                recorder.release()
                return false
            }

            recorder.startRecording()
            
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "AudioRecord failed to start recording for source $source")
                recorder.stop()
                recorder.release()
                return false
            }

            audioRecord = recorder

            // Start the recording thread
            isPaused.set(false)
            recordThread = Thread(this::recordAudioLoop).apply {
                name = "call-recording-thread"
                isDaemon = true
                start()
            }

            return true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start audio capture for source $source", e)
            audioRecord?.release()
            audioRecord = null
            return false
        }
    }

    /**
     * Main recording loop that reads audio data and writes to file.
     */
    private fun recordAudioLoop() {
        val audioRecord = this.audioRecord ?: return
        val currentRecording = this.currentRecording ?: return

        try {
            fileOutputStream = BufferedOutputStream(FileOutputStream(currentRecording.file))
            
            // Write placeholder for WAV header (44 bytes)
            fileOutputStream?.write(ByteArray(44))

            val buffer = ShortArray(audioRecord.bufferSizeInFrames)
            var totalShortsRead = 0L
            var maxSample = 0

            while (isRunning.get()) {
                // Check if recording has timed out
                if (System.currentTimeMillis() - recordingStartTime.get() > MAX_RECORDING_DURATION_MS) {
                    Log.w(TAG, "Recording duration limit reached")
                    break
                }

                // Check if paused
                if (isPaused.get()) {
                    Thread.sleep(100)
                    continue
                }

                val read = audioRecord.read(buffer, 0, buffer.size)

                if (read > 0) {
                    val byteData = ByteArray(read * 2)
                    for (i in 0 until read) {
                        val shortVal = buffer[i].toInt()
                        
                        // Track max sample to see if it's all zeros
                        val absVal = Math.abs(shortVal)
                        if (absVal > maxSample) maxSample = absVal
                        
                        byteData[i * 2] = (shortVal and 0xFF).toByte()
                        byteData[i * 2 + 1] = ((shortVal shr 8) and 0xFF).toByte()
                    }

                    fileOutputStream?.write(byteData)
                    totalShortsRead += read
                    
                    // Log progress every 5 seconds roughly
                    if (totalShortsRead % (SAMPLE_RATE * 5) < buffer.size) {
                        Log.d(TAG, "Recording... read $totalShortsRead shorts, max amplitude: $maxSample")
                    }
                } else if (read < 0) {
                    Log.e(TAG, "Error reading audio data: $read")
                    break
                }
            }
            Log.i(TAG, "Recording loop finished. Total shorts read: $totalShortsRead, peak amplitude: $maxSample")
        } catch (e: Exception) {
            Log.e(TAG, "Error in recording loop", e)
            recordingStateListener?.onRecordingError("Recording error: ${e.message}")
        } finally {
            try {
                fileOutputStream?.flush()
                fileOutputStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing file output stream", e)
            }
            fileOutputStream = null
        }
    }

    /**
     * Stops the current recording.
     */
    fun stopRecording() {
        if (!isRunning.get()) {
            return
        }

        Log.i(TAG, "Stopping recording")
        isRunning.set(false)

        // Stop audio capture
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio record", e)
        }

        // Wait for recording thread to finish
        recordThread?.join(2000)
        recordThread = null

        // Calculate duration
        val durationMs = if (recordingStartTime.get() > 0) {
            System.currentTimeMillis() - recordingStartTime.get()
        } else {
            0L
        }

        // Finalize recording
        val recording = currentRecording
        recording?.let {
            recordingStorage.finalizeRecording(it, durationMs)
            recordingStateListener?.onRecordingStopped(it, durationMs)
        }

        releaseWakeLock()
    }

    /**
     * Pauses the current recording.
     */
    private fun pauseRecording() {
        if (isPaused.get()) return

        Log.i(TAG, "Pausing recording")
        isPaused.set(true)
    }

    /**
     * Resumes a paused recording.
     */
    private fun resumeRecording() {
        if (!isPaused.get()) return

        Log.i(TAG, "Resuming recording")
        isPaused.set(false)
    }

    /**
     * Acquires a wake lock to prevent CPU sleep during recording.
     */
    private fun acquireWakeLock() {
        powerManager?.let { pm ->
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "call_recording:wake_lock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    /**
     * Releases the wake lock.
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
            wakeLock = null
        }
    }

    /**
     * Updates the foreground notification.
     */
    private fun updateNotification() {
        val notification = getForegroundNotification(Intent())
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(
                notificationId,
                notification
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "[onDestroy]")
        super.onDestroy()
        instance = null

        if (isRunning.get()) {
            Log.w(TAG, "Service destroyed while recording - stopping recording")
            stopRecording()
        }

        releaseWakeLock()
    }
}
