package mihon.data.extension.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** KMK: How each repository's last package-list check went, shown on its row in Extensions › Repositories */
object ExtensionStoreStatus {

    data class Entry(
        /** When its packages were last read, in ms */
        val lastSuccess: Long?,
        /** Why the last check failed, or null when it worked */
        val lastError: String?,
        val packageCount: Int?,
    )

    private val _entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val entries: StateFlow<Map<String, Entry>> = _entries.asStateFlow()

    fun record(indexUrl: String, result: Result<List<*>>) {
        val now = System.currentTimeMillis()
        _entries.update { map ->
            val old = map[indexUrl]
            map + (
                indexUrl to result.fold(
                    onSuccess = { Entry(lastSuccess = now, lastError = null, packageCount = it.size) },
                    onFailure = {
                        Entry(
                            lastSuccess = old?.lastSuccess,
                            lastError = it.message?.take(160) ?: it.javaClass.simpleName,
                            packageCount = old?.packageCount,
                        )
                    },
                )
                )
        }
    }
}
