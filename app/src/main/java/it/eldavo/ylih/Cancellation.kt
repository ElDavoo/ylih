package it.eldavo.ylih

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * [runCatching] for a suspending block, which is not the same thing at all.
 *
 * `runCatching` catches `Throwable`, and a coroutine reports its own cancellation by throwing
 * `CancellationException` — so the plain form swallows the cancellation, hands it to the failure
 * branch as though the work had gone wrong, and lets the caller carry on inside a coroutine that
 * is supposed to be dead.
 *
 * The test is [ensureActive] rather than `is CancellationException`, and the difference is not
 * pedantry: **Room signals some failures by cancelling**. `withTransaction` on a closed database
 * cannot get a thread, so it cancels the continuation with the real cause attached — a genuine
 * error arriving in the shape of a cancellation. Rethrowing on the type alone drops those
 * silently, which `ReceiversTest` catches by closing the database and asking what was logged.
 * Asking the job instead separates the two cases exactly: if this coroutine is still active, the
 * cancellation came from inside the work and is a failure to report; if it is not, the scope is
 * going away and the throw is how it says so.
 */
suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    runCatching(block).onFailure { currentCoroutineContext().ensureActive() }
