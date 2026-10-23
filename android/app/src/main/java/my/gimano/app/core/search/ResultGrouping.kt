package my.gimano.app.core.search

import my.gimano.app.data.model.PlaceSummary

/** "Terdekat" / "Paling sesuai" / "Buka sekarang" (canvas CariHasil). */
enum class ResultSort { NEAREST, RELEVANT, OPEN_NOW }

sealed interface ResultRow {
    data class Single(val place: PlaceSummary) : ResultRow
    /** "FamilyMart · 12 berdekatan": a chain collapsed into one row, branches nearest first. */
    data class Chain(val key: String, val name: String, val branches: List<PlaceSummary>) : ResultRow {
        val nearest get() = branches.first()
    }
}

object ResultGrouping {
    /** At least this many branches of the same chain are collapsed into one group. */
    const val MIN_CHAIN = 3

    fun chainKey(p: PlaceSummary): String? =
        (p.chainGroup ?: p.brand)?.trim()?.lowercase()?.replace(Regex("[^a-z0-9]+"), "")?.takeIf { it.isNotEmpty() }

    /**
     * Applies the sort/filter, then collapses chains with [MIN_CHAIN]+ branches into one row placed where the
     * chain's best (first) branch would have been. Branches inside a group are always nearest first.
     */
    fun rows(results: List<PlaceSummary>, sort: ResultSort): List<ResultRow> {
        val ordered = when (sort) {
            ResultSort.RELEVANT -> results
            ResultSort.NEAREST -> results.sortedBy { it.distanceM ?: Double.MAX_VALUE }
            ResultSort.OPEN_NOW -> results.filter { it.openNow == true }.sortedBy { it.distanceM ?: Double.MAX_VALUE }
        }
        val byChain = ordered.groupBy { chainKey(it) }
        val emitted = HashSet<String>()
        return ordered.mapNotNull { p ->
            val k = chainKey(p)
            val group = k?.let { byChain[it] }
            when {
                k == null || group == null || group.size < MIN_CHAIN -> ResultRow.Single(p)
                !emitted.add(k) -> null
                else -> ResultRow.Chain(k, p.brand ?: p.name, group.sortedBy { it.distanceM ?: Double.MAX_VALUE })
            }
        }
    }

    /** Whether "Buka sekarang" can say anything: at least one result knows its opening state. */
    fun knowsOpening(results: List<PlaceSummary>) = results.any { it.openNow != null }
}
