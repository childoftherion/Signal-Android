/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

import org.thoughtcrime.securesms.recipients.RecipientId
import kotlin.time.Duration.Companion.days

/**
 * Settings related to call recording.
 */
class RecordingValues internal constructor(store: KeyValueStore) : SignalStoreValues(store) {

  companion object {
    private const val RECORD_CALLS_ENABLED = "recording.record_calls_enabled"
    private const val RECORD_CALLS_AUDIO_QUALITY = "recording.record_calls_audio_quality"
    private const val RECORD_LAST_CLEANUP_TIMESTAMP = "recording.record_last_cleanup_timestamp"
    private const val CONTACT_RECORDING_ENABLED_PREFIX = "recording.contact_enabled."
  }

  public override fun onFirstEverAppLaunch() = Unit

  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  var isRecordCallsEnabled: Boolean by booleanValue(RECORD_CALLS_ENABLED, false)

  var recordCallsAudioQualityKbps: Int by integerValue(RECORD_CALLS_AUDIO_QUALITY, 128)

  var lastCleanupTimestamp: Long by longValue(RECORD_LAST_CLEANUP_TIMESTAMP, 0)

  /**
   * Returns whether recording is enabled for a specific contact.
   * If not explicitly set for the contact, it falls back to the global setting.
   */
  fun isRecordingEnabledForContact(recipientId: RecipientId): Boolean {
    val key = CONTACT_RECORDING_ENABLED_PREFIX + recipientId.serialize()
    return if (store.containsKey(key)) {
      store.getBoolean(key, false)
    } else {
      isRecordCallsEnabled
    }
  }

  /**
   * Returns whether recording is explicitly enabled or disabled for a specific contact.
   * Returns null if no explicit setting exists for the contact.
   */
  fun getExplicitRecordingEnabledForContact(recipientId: RecipientId): Boolean? {
    val key = CONTACT_RECORDING_ENABLED_PREFIX + recipientId.serialize()
    return if (store.containsKey(key)) {
      store.getBoolean(key, false)
    } else {
      null
    }
  }

  /**
   * Explicitly enables or disables recording for a specific contact.
   */
  fun setRecordingEnabledForContact(recipientId: RecipientId, enabled: Boolean?) {
    val key = CONTACT_RECORDING_ENABLED_PREFIX + recipientId.serialize()
    if (enabled == null) {
      store.beginWrite().remove(key).apply()
    } else {
      store.beginWrite().putBoolean(key, enabled).apply()
    }
  }

  /**
   * Returns the maximum age of recording files before they should be cleaned up (7 days).
   */
  fun getMaximumRecordingAge(): Long = 7.days.inWholeMilliseconds
}
