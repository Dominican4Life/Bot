package tachiyomi.data

import androidx.paging.PagingSource
import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.Query
import kotlinx.coroutines.flow.Flow

interface DatabaseHandler {

    suspend fun <T> await(inTransaction: Boolean = false, block: suspend Database.() -> T): T

    suspend fun <T : Any> awaitList(
        inTransaction: Boolean = false,
        block: suspend Database.() -> Query<T>,
    ): List<T>

    // SY -->
    suspend fun <T : Any> awaitListExecutable(
        inTransaction: Boolean = false,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): List<T>
    // SY <--

    suspend fun <T : Any> awaitOne(
        inTransaction: Boolean = false,
        block: suspend Database.() -> Query<T>,
    ): T

    suspend fun <T : Any> awaitOneExecutable(
        inTransaction: Boolean = false,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): T

    suspend fun <T : Any> awaitOneOrNull(
        inTransaction: Boolean = false,
        block: suspend Database.() -> Query<T>,
    ): T?

    suspend fun <T : Any> awaitOneOrNullExecutable(
        inTransaction: Boolean = false,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): T?

    fun <T : Any> subscribeToList(block: Database.() -> Query<T>): Flow<List<T>>

    // KMK -->
    /**
     * Like [subscribeToList], but a burst of table notifications runs the query once, [timeoutMillis]
     * after the last one. The first run is not delayed. For the library flows only (r96): during a
     * library update or while browsing, every write to mangas/chapters/history re-ran the library query.
     */
    fun <T : Any> subscribeToListDebounced(timeoutMillis: Long, block: Database.() -> Query<T>): Flow<List<T>>
    // KMK <--

    fun <T : Any> subscribeToOne(block: Database.() -> Query<T>): Flow<T>

    fun <T : Any> subscribeToOneOrNull(block: Database.() -> Query<T>): Flow<T?>

    fun <T : Any> subscribeToPagingSource(
        countQuery: Database.() -> Query<Long>,
        queryProvider: Database.(Long, Long) -> Query<T>,
    ): PagingSource<Long, T>
}
