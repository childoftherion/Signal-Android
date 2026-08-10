/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.recording

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.signal.core.ui.compose.ComposeFragment
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Rows.TextAndLabel
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Intent
import android.net.Uri

class RecordingLibraryFragment : ComposeFragment() {

    private val viewModel: RecordingLibraryViewModel by viewModels(
        factoryProducer = { RecordingLibraryViewModel.Factory(requireContext()) }
    )

    @Composable
    override fun FragmentContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        
        RecordingLibraryScreen(
            state = state,
            onBackClick = { requireActivity().onBackPressedDispatcher.onBackPressed() },
            onDeleteClick = { viewModel.deleteRecording(it) },
            onExportClick = { viewModel.exportRecording(requireContext(), it) },
            onPlayClick = { recording ->
                val context = requireContext()
                val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", recording.file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "audio/wav")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Play recording"))
            }
        )
    }
}

@Composable
private fun RecordingLibraryScreen(
    state: RecordingLibraryState,
    onBackClick: () -> Unit,
    onDeleteClick: (RecordingStorage.RecordingFile) -> Unit,
    onExportClick: (RecordingStorage.RecordingFile) -> Unit,
    onPlayClick: (RecordingStorage.RecordingFile) -> Unit
) {
    Scaffolds.Settings(
        title = stringResource(R.string.preferences__record_calls_library),
        onNavigationClick = onBackClick,
        navigationIcon = SignalIcons.ArrowStart.imageVector
    ) { paddingValues ->
        if (state.recordings.isEmpty()) {
            Text(
                text = "No recordings yet",
                modifier = Modifier.padding(paddingValues).padding(Rows.defaultPadding())
            )
        } else {
            LazyColumn(modifier = Modifier.padding(paddingValues)) {
                items(state.recordings) { recording ->
                    RecordingRow(
                        recording = recording,
                        onDeleteClick = { onDeleteClick(recording) },
                        onExportClick = { onExportClick(recording) },
                        onPlayClick = { onPlayClick(recording) }
                    )
                    Dividers.Default()
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    recording: RecordingStorage.RecordingFile,
    onDeleteClick: () -> Unit,
    onExportClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val date = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US).format(Date(recording.startTime))
    val duration = RecordingStorage.formatDuration(recording.durationMs)
    val size = RecordingStorage.formatFileSize(recording.file.length())

    Rows.TextRow(
        text = {
            TextAndLabel(
                text = recording.recipientName,
                label = "$date • $duration ($size)"
            )
            IconButton(onClick = onExportClick) {
                Icon(
                    imageVector = SignalIcons.Save.imageVector,
                    contentDescription = "Save to device"
                )
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = SignalIcons.Trash.imageVector,
                    contentDescription = stringResource(R.string.delete)
                )
            }
        },
        onClick = onPlayClick
    )
}
