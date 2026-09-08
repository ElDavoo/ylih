package it.eldavo.ylih

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * [runCatching] for a suspending block, which is not the same thing.
 *
 * `runCatching` catches `Throwable`, and a coroutine reports its own cancellation by throwing
 * `CancellationException` — so the plain form swallows it, hands it to the failure branch as
 * though the work had gone wrong, and lets the caller carry on inside a coroutine that's supposed
 * to be dead.
 *
 * The test is [ensureActive], not `is CancellationException` — not pedantry: **Room signals some
 * failures by cancelling**. `withTransaction` on a closed database can't get a thread, so it
 * cancels the continuation with the real cause attached — a genuine error in the shape of a
 * cancellation. Rethrowing on type alone drops those silently, which `ReceiversTest` catches by
 * closing the database and checking what was logged. Asking the job instead separates the two
 * cases exactly: if this coroutine is still active, the cancellation came from inside the work and
 * is a failure to report; if not, the scope is going away and the throw is how it says so.
 */
suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    runCatching(block).onFailure { currentCoroutineContext().ensureActive() }
