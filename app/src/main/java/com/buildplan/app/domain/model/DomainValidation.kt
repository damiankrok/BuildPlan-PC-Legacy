package com.buildplan.app.domain.model

/**
 * Validation shared by the domain entities.
 *
 * Invariants are checked at construction time and signalled with
 * [IllegalArgumentException]. No Result/Error framework is introduced: an object
 * that violates its invariants is a programming error, not a recoverable
 * outcome, and inventing an error type now would be a decision made ahead of any
 * caller that needs it.
 */

/** Requires a user-facing name that is not blank once whitespace is ignored. */
internal fun requireDomainName(value: String, field: String): String {
    require(value.isNotBlank()) { "$field must not be blank" }
    return value
}

/** Requires optional free text to be absent rather than present-but-empty. */
internal fun requireOptionalText(value: String?, field: String): String? {
    if (value != null) {
        require(value.isNotBlank()) { "$field must be null rather than blank" }
    }
    return value
}

/** Requires an explicit ordering position that is not negative. */
internal fun requireOrder(order: Int, field: String): Int {
    require(order >= 0) { "$field must not be negative, was $order" }
    return order
}

/**
 * Rejects duplicate identifiers inside a collection owned by one parent.
 * Two children sharing an id makes the parent's contents ambiguous.
 */
internal fun <T> requireUniqueIds(ids: List<T>, entity: String) {
    val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    require(duplicates.isEmpty()) {
        "Duplicate $entity ids in one collection: ${duplicates.joinToString()}"
    }
}
