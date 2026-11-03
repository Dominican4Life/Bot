package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * KMK (r115): one file of the app's own folder: its path relative to `filesDir` ("/" separated) and its bytes.
 * A restore only writes paths it allows (see `BackupFiles`), never outside those folders.
 */
@Serializable
class BackupFile(
    @ProtoNumber(1) val path: String,
    @ProtoNumber(2) val bytes: ByteArray = ByteArray(0),
)

/**
 * KMK (r115): a title's custom cover picture. Ids differ between devices, so it is matched by [source] + [url] on
 * restore; [anime] says which library it belongs to.
 */
@Serializable
class BackupCustomCover(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val url: String,
    @ProtoNumber(3) val anime: Boolean = false,
    @ProtoNumber(4) val bytes: ByteArray = ByteArray(0),
)
