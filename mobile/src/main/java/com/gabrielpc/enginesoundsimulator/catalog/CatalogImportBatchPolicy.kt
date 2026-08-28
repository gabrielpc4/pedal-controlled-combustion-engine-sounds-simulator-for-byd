package com.gabrielpc.enginesoundsimulator.catalog

/**
 * Runs a per-pack-atomic import batch with one discovery/selector closure.
 *
 * A later invalid pack does not roll back earlier packs that were already atomically committed.
 * The closure therefore always runs exactly once for a non-empty batch, including failure, so
 * those successful commits are immediately discoverable. The original import failure remains the
 * primary exception if discovery also fails.
 */
internal object CatalogImportBatchPolicy {
    fun <T, R> importDistinctAndClose(
        sources: List<T>,
        importOne: (T) -> Unit,
        closeBatch: () -> R,
    ): R {
        val distinctSources = sources.distinct()
        require(distinctSources.isNotEmpty()) { "An import batch must contain at least one source" }

        var importFailure: Throwable? = null
        try {
            distinctSources.forEach(importOne)
        } catch (failure: Throwable) {
            importFailure = failure
        }

        val result = try {
            closeBatch()
        } catch (closeFailure: Throwable) {
            importFailure?.let { primary ->
                primary.addSuppressed(closeFailure)
                throw primary
            }
            throw closeFailure
        }
        importFailure?.let { throw it }
        return result
    }
}
