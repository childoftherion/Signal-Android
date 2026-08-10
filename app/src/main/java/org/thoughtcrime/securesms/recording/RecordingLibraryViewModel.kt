/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.recording

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.signal.core.util.concurrent.SignalDispatchers
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.recording.RecordingStorage.RecordingFile
import java.io.File

class RecordingLibraryViewModel(private val recordingStorage: RecordingStorage) : ViewModel() {

    private val _state = MutableStateFlow(RecordingLibraryState())
    val state: StateFlow<RecordingLibraryState> = _state

    private val viewModelScope = CoroutineScope(SignalDispatchers.Default)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val recordings = recordingStorage.getRecordings()
            _state.update { it.copy(recordings = recordings, isLoading = false) }
        }
    }

    fun deleteRecording(recording: RecordingFile) {
        viewModelScope.launch {
            recordingStorage.deleteRecording(recording)
            refresh()
        }
    }

    fun exportRecording(context: Context, recording: RecordingFile) {
        viewModelScope.launch {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val destFile = File(downloadsDir, recording.file.name)
                recording.file.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // Notify user (maybe via state or Toast if we had a way)
            } catch (e: Exception) {
                Log.w("RecordingLibrary", "Export failed", e)
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return modelClass.cast(RecordingLibraryViewModel(RecordingStorage(context))) as T
        }
    }
}

data class RecordingLibraryState(
    val recordings: List<RecordingFile> = emptyList(),
    val isLoading: Boolean = true
)
