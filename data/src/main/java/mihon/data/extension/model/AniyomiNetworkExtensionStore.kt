package mihon.data.extension.model

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * KMK: Aniyomi's protobuf index (index.pb). It is the same as Mihon's except that an extension's field 8 is
 * "isTorrent" and its sources are at field 9 (as Kototoro reads it). Read with Mihon's layout, its sources came out
 * empty, or the whole index failed when a torrent extension was in it.
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class AniyomiNetworkExtensionStore(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(4) val contact: NetworkExtensionStore.Contact = NetworkExtensionStore.Contact(""),
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {
    @Serializable
    data class ExtensionList(@ProtoNumber(1) val extensions: List<Extension> = emptyList()) {
        fun toMihon() = NetworkExtensionStore.ExtensionList(extensions.map { it.toMihon() })
    }

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String = "",
        @ProtoNumber(2) val packageName: String = "",
        @ProtoNumber(3) val resources: NetworkExtensionStore.Resources = NetworkExtensionStore.Resources("", ""),
        @ProtoNumber(4) val extensionLib: String = "",
        @ProtoNumber(5) val versionCode: Long = 0,
        @ProtoNumber(6) val versionName: String = "",
        @ProtoNumber(7) val contentWarning: NetworkExtensionStore.ContentWarning =
            NetworkExtensionStore.ContentWarning.UNSPECIFIED,
        @ProtoNumber(8) val isTorrent: Boolean = false,
        @ProtoNumber(9) val sources: List<NetworkExtensionStore.Source> = emptyList(),
    ) {
        fun toMihon() = NetworkExtensionStore.Extension(
            name = name,
            packageName = packageName,
            resources = resources,
            extensionLib = extensionLib,
            versionCode = versionCode,
            versionName = versionName,
            contentWarning = contentWarning,
            sources = sources,
        )
    }

    fun toMihon() = NetworkExtensionStore(
        name = name,
        badgeLabel = badgeLabel,
        signingKey = signingKey,
        contact = contact,
        extensionList = extensionList?.toMihon(),
        extensionListUrl = extensionListUrl,
    )
}
