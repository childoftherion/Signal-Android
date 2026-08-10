/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.recording

import android.content.Context
import android.os.Environment
import org.signal.core.util.Hex
import org.signal.core.util.bytes
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "RecordingStorage"

/**
 * Manages storage of call recordings, including encryption at rest.
 */
class RecordingStorage(private val context: Context) {

    companion object {
        private const val ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val AES_KEY_SIZE = 256

        private const val RECORDINGS_DIR = "call_recordings"
        private const val MAX_RECORDINGS = 100

        const val DEFAULT_SAMPLE_RATE = 48000
        const val DEFAULT_CHANNELS = 2 // Stereo
        const val DEFAULT_BITS_PER_SAMPLE = 16

        @JvmStatic
        fun formatDuration(durationMs: Long): String {
            val totalSeconds = durationMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            
            return if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }

        @JvmStatic
        fun formatFileSize(size: Long): String {
            return size.bytes.toUnitString()
        }

        private fun sanitizeFilename(name: String): String {
            return name
                .replace(Regex("[^a-zA-Z0-9_]"), "_")
                .take(50)
        }
    }

    private val recordingsDir: File by lazy {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), RECORDINGS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    fun createRecordingFile(
        recipientName: String,
        isVideoCall: Boolean,
        startTime: Long
    ): RecordingFile {
        val sanitizedName = sanitizeFilename(recipientName)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startTime))
        val type = if (isVideoCall) "video" else "audio"
        val filename = "${type}_${sanitizedName}_${timestamp}.wav"
        val file = File(recordingsDir, filename)
        
        return RecordingFile(
            file = file,
            recipientName = sanitizedName,
            isVideoCall = isVideoCall,
            startTime = startTime,
            durationMs = 0L
        )
    }

    fun finalizeRecording(recording: RecordingFile, durationMs: Long) {
        try {
            val file = recording.file
            if (file.exists()) {
                val dataSize = file.length() - 44
                writeWavHeader(file, dataSize)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to finalize WAV header", e)
        }

        val updatedRecording = recording.copy(durationMs = durationMs)
        saveRecordingMetadata(updatedRecording)
        maybeCleanupOldRecordings()
    }

    /**
     * Writes or updates the WAV (RIFF) header of a file.
     * 
     * @param file The file to write to.
     * @param dataSize Total bytes of raw PCM data (excluding header).
     */
    fun writeWavHeader(file: File, dataSize: Long) {
        val sampleRate = DEFAULT_SAMPLE_RATE.toLong()
        val channels = DEFAULT_CHANNELS
        val bitsPerSample = DEFAULT_BITS_PER_SAMPLE
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalLength = dataSize + 36

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.writeBytes("RIFF")
            raf.writeInt(Integer.reverseBytes(totalLength.toInt())) // ChunkSize
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeInt(Integer.reverseBytes(16)) // Subchunk1Size
            raf.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // AudioFormat (PCM = 1)
            raf.writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt()) // NumChannels
            raf.writeInt(Integer.reverseBytes(sampleRate.toInt())) // SampleRate
            raf.writeInt(Integer.reverseBytes(byteRate.toInt())) // ByteRate
            raf.writeShort(java.lang.Short.reverseBytes((channels * bitsPerSample / 8).toShort()).toInt()) // BlockAlign
            raf.writeShort(java.lang.Short.reverseBytes(bitsPerSample.toShort()).toInt()) // BitsPerSample
            raf.writeBytes("data")
            raf.writeInt(Integer.reverseBytes(dataSize.toInt())) // Subchunk2Size
        }
    }

    fun getRecordings(): List<RecordingFile> = loadAllRecordingMetadata()

    fun deleteRecording(recording: RecordingFile) {
        recording.file.delete()
        deleteRecordingMetadata(recording)
    }

    fun getTotalSizeBytes(): Long {
        return recordingsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun cleanupOldRecordings() {
        val maxAge = SignalStore.recording.getMaximumRecordingAge()
        val cutoffTime = System.currentTimeMillis() - maxAge
        
        val files = recordingsDir.listFiles()?.filter { 
            it.lastModified() < cutoffTime 
        }?.sortedBy { it.lastModified() } ?: emptyList()
        
        files.forEach { file ->
            val recording = RecordingFile(file, "", false, 0, 0)
            deleteRecording(recording)
        }
        
        SignalStore.recording.lastCleanupTimestamp = System.currentTimeMillis()
    }

    private fun maybeCleanupOldRecordings() {
        val files = recordingsDir.listFiles()?.toList() ?: emptyList()
        if (files.size > MAX_RECORDINGS) {
            val toDelete = files.sortedBy { it.lastModified() }.take(files.size - MAX_RECORDINGS)
            toDelete.forEach { file ->
                val recording = RecordingFile(file, "", false, 0, 0)
                deleteRecording(recording)
            }
        }
    }

    fun encryptRecording(inputFile: File, encryptRecordings: Boolean = false): File? {
        if (!encryptRecordings) {
            return inputFile
        }

        val outputFile = File(inputFile.parentFile, "${inputFile.name}.enc")
        
        return try {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(AES_KEY_SIZE)
            val secretKey: SecretKey = keyGen.generateKey()
            
            val cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            
            FileOutputStream(outputFile).use { fos ->
                fos.write(iv)
                CipherOutputStream(fos, cipher).use { cos ->
                    inputFile.inputStream().use { it.copyTo(cos) }
                }
            }
            
            saveEncryptionKey(outputFile.name, secretKey.encoded)
            inputFile.delete()
            outputFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to encrypt recording", e)
            outputFile.delete()
            inputFile
        }
    }

    fun decryptRecording(encFile: File): File? {
        if (!encFile.exists() || !encFile.name.endsWith(".enc")) {
            return null
        }

        val outputFile = File(encFile.parentFile, encFile.name.removeSuffix(".enc"))
        
        return try {
            FileInputStream(encFile).use { fis ->
                val iv = ByteArray(GCM_IV_LENGTH)
                if (fis.read(iv) != GCM_IV_LENGTH) {
                    Log.w(TAG, "Failed to read IV from encrypted file")
                    return null
                }
                
                val secretKeyBytes = loadEncryptionKey(encFile.name)
                if (secretKeyBytes == null) {
                    Log.w(TAG, "No encryption key found for file")
                    return null
                }

                val secretKeySpec = SecretKeySpec(secretKeyBytes, "AES")
                val cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, spec)
                
                FileOutputStream(outputFile).use { fos ->
                    CipherInputStream(fis, cipher).use { cis ->
                        cis.copyTo(fos)
                    }
                }
            }
            outputFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt recording", e)
            outputFile.delete()
            null
        }
    }

    private fun saveEncryptionKey(filename: String, key: ByteArray) {
        getMetadataPrefs().edit()
            .putString("${filename}_key", Hex.toStringCondensed(key))
            .apply()
    }

    private fun loadEncryptionKey(filename: String): ByteArray? {
        val hexKey = getMetadataPrefs().getString("${filename}_key", null) ?: return null
        return try {
            Hex.fromStringCondensed(hexKey)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to decode encryption key", e)
            null
        }
    }

    private fun getMetadataPrefs() = context.getSharedPreferences("recording_metadata", Context.MODE_PRIVATE)
    
    private fun saveRecordingMetadata(recording: RecordingFile) {
        val prefs = getMetadataPrefs()
        val editor = prefs.edit()
        val key = recording.file.name
        
        editor.putString("${key}_recipient", recording.recipientName)
        editor.putBoolean("${key}_videocall", recording.isVideoCall)
        editor.putLong("${key}_starttime", recording.startTime)
        editor.putLong("${key}_duration", recording.durationMs)
        
        editor.apply()
    }
    
    private fun loadAllRecordingMetadata(): List<RecordingFile> {
        val prefs = getMetadataPrefs()
        val result = mutableListOf<RecordingFile>()
        
        prefs.all.forEach { (key, _) ->
            if (key.endsWith("_recipient")) {
                val filename = key.removeSuffix("_recipient")
                val recipient = prefs.getString("${filename}_recipient", "") ?: ""
                val isVideo = prefs.getBoolean("${filename}_videocall", false)
                val startTime = prefs.getLong("${filename}_starttime", 0)
                val duration = prefs.getLong("${filename}_duration", 0)
                
                val nameWithExtension = if (filename.endsWith(".wav")) filename else "${filename}.wav"
                val file = File(recordingsDir, nameWithExtension)
                
                if (file.exists()) {
                    result.add(RecordingFile(
                        file = file,
                        recipientName = recipient,
                        isVideoCall = isVideo,
                        startTime = startTime,
                        durationMs = duration
                    ))
                }
            }
        }
        
        return result.sortedByDescending { it.startTime }
    }
    
    private fun deleteRecordingMetadata(recording: RecordingFile) {
        val key = recording.file.name
        getMetadataPrefs().edit()
            .remove("${key}_recipient")
            .remove("${key}_videocall")
            .remove("${key}_starttime")
            .remove("${key}_duration")
            .remove("${key}_key")
            .apply()
    }

    data class RecordingFile(
        val file: File,
        val recipientName: String,
        val isVideoCall: Boolean,
        val startTime: Long,
        var durationMs: Long
    )
}
