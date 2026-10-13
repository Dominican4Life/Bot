package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * KMK: A source made in the Code editor: its details (name, id, on/off switch, when it was saved…) as JSON, its code,
 * and its settings.
 */
@Serializable
data class BackupJsSource(
    @ProtoNumber(1) val info: String,
    @ProtoNumber(2) val code: String,
    @ProtoNumber(3) val settings: List<BackupPreference> = emptyList(),
)
