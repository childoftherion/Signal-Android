/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.recording

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.signal.core.util.SafeForegroundService
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.events.WebRtcViewModel
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.RecipientId
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages call recording operations integrated into Signal's call system.
 */
class RecordingManager(private val context: Context) : AudioCaptureService.RecordingStateListener {

    companion object {
        private val TAG = Log.tag(RecordingManager::class.java)

        // Call states that should trigger recording
        private val RECORDING_STATES = setOf(
            WebRtcViewModel.State.CALL_CONNECTED,
            WebRtcViewModel.State.CALL_INCOMING,
            WebRtcViewModel.State.CALL_OUTGOING,
            WebRtcViewModel.State.CALL_RINGING
        )

        // Call states that should stop recording
        private val STOP_RECORDING_STATES = setOf(
            WebRtcViewModel.State.CALL_DISCONNECTED,
            WebRtcViewModel.State.CALL_DISCONNECTED_GLARE,
            WebRtcViewModel.State.CALL_BUSY,
            WebRtcViewModel.State.CALL_ACCEPTED_ELSEWHERE,
            WebRtcViewModel.State.CALL_DECLINED_ELSEWHERE,
            WebRtcViewModel.State.CALL_ONGOING_ELSEWHERE,
            WebRtcViewModel.State.NETWORK_FAILURE,
            WebRtcViewModel.State.RECIPIENT_UNAVAILABLE,
            WebRtcViewModel.State.NO_SUCH_USER,
            WebRtcViewModel.State.UNTRUSTED_IDENTITY,
            WebRtcViewModel.State.IDLE,
            WebRtcViewModel.State.CALL_NEEDS_PERMISSION
        )

        @Volatile
        private var instance: RecordingManager? = null

        @JvmStatic
        fun getInstance(): RecordingManager? = instance

        @JvmStatic
        fun init(context: Context) {
            if (instance == null) {
                synchronized(RecordingManager::class.java) {
                    if (instance == null) {
                        instance = RecordingManager(context)
                    }
                }
            }
        }

        fun isRecordingEnabled(recipientId: RecipientId? = null): Boolean {
            return if (recipientId != null) {
                SignalStore.recording.isRecordingEnabledForContact(recipientId)
            } else {
                SignalStore.recording.isRecordCallsEnabled
            }
        }
    }

    // Current state
    private val stateLock = ReentrantLock()
    @Volatile
    private var currentState: RecordingState = RecordingState.Idle

    // Recording tracking
    private var currentRecipientId: RecipientId? = null
    private var currentRecipientName: String? = null
    private var currentIsVideoCall: Boolean = false
    private var recordingStartTime: Long = 0L

    // Handler for main thread operations
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        Log.i(TAG, "init: context=$context")
        
        // Register for EventBus callbacks
        EventBus.getDefault().register(this)
        
        // Register as listener on AudioCaptureService (singleton pattern)
        AudioCaptureService.getInstance()?.setRecordingStateListener(this)
            ?: run {
                Log.w(TAG, "AudioCaptureService not initialized yet")
            }
        
        // Start cleanup task
        scheduleCleanupTask()
    }

    /**
     * EventBus handler for WebRtcViewModel state changes.
     * Called on the main thread.
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onWebRtcViewModel(event: WebRtcViewModel) {
        Log.d(TAG, "onWebRtcViewModel: state=${event.state}")
        
        handleCallStateChange(event.state, event)
    }

    /**
     * Handles call state changes to control recording.
     */
    private fun handleCallStateChange(state: WebRtcViewModel.State, event: WebRtcViewModel) {
        stateLock.withLock {
            when {
                state in RECORDING_STATES -> {
                    handleRecordingStart(state, event)
                }
                state in STOP_RECORDING_STATES -> {
                    handleRecordingStop(state)
                }
                else -> {
                    Log.d(TAG, "No action for state: $state")
                }
            }
        }
    }

    /**
     * Handles starting a recording when a call enters a recording state.
     */
    private fun handleRecordingStart(state: WebRtcViewModel.State, event: WebRtcViewModel) {
        val recipient = event.recipient
        if (!isRecordingEnabled(recipient.id)) {
            Log.d(TAG, "Recording not enabled for this contact or globally")
            updateState(RecordingState.Idle)
            return
        }

        val currentState = currentState
        if (currentState is RecordingState.ActiveRecording) {
            Log.d(TAG, "Already actively recording, ignoring state change to $state")
            return
        }

        // Don't record incoming ringing - wait for connected
        if (state == WebRtcViewModel.State.CALL_INCOMING || state == WebRtcViewModel.State.CALL_OUTGOING || state == WebRtcViewModel.State.CALL_RINGING) {
            Log.d(TAG, "Call is ringing, will record when connected")
            updateState(RecordingState.Idle)
            return
        }

        // Start recording for CONNECTED state
        if (state == WebRtcViewModel.State.CALL_CONNECTED) {
            currentRecipientId = recipient.id
            currentRecipientName = recipient.getDisplayName(context)
            currentIsVideoCall = event.isRemoteVideoOffer || event.localParticipant.isVideoEnabled
            
            val startTime = System.currentTimeMillis()
            recordingStartTime = startTime

            Log.i(TAG, "Starting recording for call: $currentRecipientName (video=$currentIsVideoCall)")
            updateState(RecordingState.ActiveRecording(startTime))

            // Start the audio capture service with a slight delay to avoid initial mic contention with WebRTC
            mainHandler.postDelayed({
                if (getState() is RecordingState.ActiveRecording) {
                    Log.i(TAG, "Delayed start of AudioCaptureService")
                    startAudioCapture()
                } else {
                    Log.w(TAG, "Not starting AudioCaptureService, state changed to: $currentState")
                }
            }, 500)
        }
    }

    /**
     * Handles stopping a recording when a call enters a stopped/disconnected state.
     */
    private fun handleRecordingStop(state: WebRtcViewModel.State) {
        val currentState = currentState
        
        if (currentState is RecordingState.ActiveRecording) {
            val duration = System.currentTimeMillis() - currentState.startTimeMs
            Log.i(TAG, "Stopping recording due to state=$state, duration=${duration}ms")
            
            // Stop the audio capture service
            stopAudioCapture()
            updateState(RecordingState.Idle)
        } else if (currentState is RecordingState.Paused) {
            Log.d(TAG, "Call ended while paused")
            stopAudioCapture()
            updateState(RecordingState.Idle)
        }
    }

    /**
     * Starts the AudioCaptureService.
     */
    private fun startAudioCapture() {
        val recipientId = currentRecipientId ?: return
        val recipientName = currentRecipientName ?: "Unknown"
        
        val intentExtras = android.os.Bundle().apply {
            putString(AudioCaptureService.EXTRA_ACTION, AudioCaptureService.ACTION_EXTRA_START)
            putString(AudioCaptureService.EXTRA_RECIPIENT_ID, recipientId.serialize())
            putString(AudioCaptureService.EXTRA_RECIPIENT_NAME, recipientName)
            putBoolean(AudioCaptureService.EXTRA_IS_VIDEO_CALL, currentIsVideoCall)
        }
        
        SafeForegroundService.start(context, AudioCaptureService::class.java, intentExtras)
        
        Log.i(TAG, "AudioCaptureService started for: $recipientName")
    }

    /**
     * Stops the AudioCaptureService.
     */
    private fun stopAudioCapture() {
        val intentExtras = android.os.Bundle().apply {
            putString(AudioCaptureService.EXTRA_ACTION, AudioCaptureService.ACTION_EXTRA_STOP)
        }
        
        SafeForegroundService.update(context, AudioCaptureService::class.java, intentExtras)
        SafeForegroundService.stop(context, AudioCaptureService::class.java)
        
        Log.i(TAG, "AudioCaptureService stop signal sent")
    }

    /**
     * Pauses the current recording.
     */
    fun pauseRecording() {
        stateLock.withLock {
            if (currentState is RecordingState.ActiveRecording) {
                Log.i(TAG, "Pausing recording")
                updateState(RecordingState.Paused)
                
                val intentExtras = android.os.Bundle().apply {
                    putString(AudioCaptureService.EXTRA_ACTION, AudioCaptureService.ACTION_EXTRA_PAUSE)
                }
                SafeForegroundService.update(context, AudioCaptureService::class.java, intentExtras)
            }
        }
    }

    /**
     * Resumes a paused recording.
     */
    fun resumeRecording() {
        stateLock.withLock {
            if (currentState is RecordingState.Paused) {
                Log.i(TAG, "Resuming recording")
                updateState(RecordingState.ActiveRecording(recordingStartTime))
                
                val intentExtras = android.os.Bundle().apply {
                    putString(AudioCaptureService.EXTRA_ACTION, AudioCaptureService.ACTION_EXTRA_RESUME)
                }
                SafeForegroundService.update(context, AudioCaptureService::class.java, intentExtras)
            }
        }
    }

    /**
     * Gets the current recording state.
     */
    fun getState(): RecordingState {
        return stateLock.withLock { currentState }
    }

    /**
     * Checks if recording is currently active.
     */
    fun isCurrentlyRecording(): Boolean {
        return stateLock.withLock { currentState is RecordingState.ActiveRecording }
    }

    /**
     * Gets the current recording duration in milliseconds.
     */
    fun getRecordingDuration(): Long {
        return stateLock.withLock {
            val state = currentState
            if (state is RecordingState.ActiveRecording) {
                System.currentTimeMillis() - state.startTimeMs
            } else {
                0L
            }
        }
    }

    /**
     * Updates the internal state, notifying listeners if changed.
     */
    private fun updateState(newState: RecordingState) {
        if (currentState != newState) {
            Log.d(TAG, "Recording state changed: $currentState -> $newState")
            currentState = newState
        }
    }

    /**
     * Called when recording starts.
     */
    override fun onRecordingStarted(recording: RecordingStorage.RecordingFile) {
        Log.i(TAG, "Recording started: ${recording.file.name}")
        
        mainHandler.post {
            stateLock.withLock {
                updateState(RecordingState.ActiveRecording(recordingStartTime, recording))
            }
        }
    }

    /**
     * Called when recording stops.
     */
    override fun onRecordingStopped(recording: RecordingStorage.RecordingFile?, durationMs: Long) {
        Log.i(TAG, "Recording stopped: ${recording?.file?.name}, duration=${durationMs}ms")
        
        mainHandler.post {
            stateLock.withLock {
                updateState(RecordingState.Idle)
            }
        }
    }

    /**
     * Called when a recording error occurs.
     */
    override fun onRecordingError(error: String) {
        Log.e(TAG, "Recording error: $error")
        
        mainHandler.post {
            stateLock.withLock {
                updateState(RecordingState.Idle)
            }
        }
    }

    /**
     * Schedules periodic cleanup of old recordings.
     */
    private fun scheduleCleanupTask() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                RecordingStorage(context).cleanupOldRecordings()
                // Schedule next cleanup in 24 hours
                mainHandler.postDelayed(this, 24 * 60 * 60 * 1000)
            }
        }, 60 * 60 * 1000) // First cleanup after 1 hour
    }

    /**
     * Shuts down the recording manager.
     */
    fun shutdown() {
        Log.i(TAG, "shutdown")
        
        // Stop any active recording
        stateLock.withLock {
            if (currentState is RecordingState.ActiveRecording) {
                stopAudioCapture()
                updateState(RecordingState.Idle)
            }
        }
        
        // Unregister from EventBus
        EventBus.getDefault().unregister(this)
    }

    /**
     * Recording state representation.
     */
    sealed class RecordingState {
        object Idle : RecordingState() {
            override fun toString(): String = "Idle"
        }
        
        data class ActiveRecording(
            val startTimeMs: Long,
            val recordingFile: RecordingStorage.RecordingFile? = null,
            val durationMs: Long = 0L
        ) : RecordingState() {
            val durationSeconds: Long get() = durationMs / 1000
            val durationFormatted: String get() = RecordingStorage.formatDuration(durationMs)
            
            override fun toString(): String = "ActiveRecording(startTimeMs=$startTimeMs, duration=$durationFormatted)"
        }
        
        object Paused : RecordingState() {
            override fun toString(): String = "Paused"
        }
    }
}
