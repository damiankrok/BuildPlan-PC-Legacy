package com.buildplan.app.domain.money

/**
 * Currencies the domain can express amounts in.
 *
 * PLN is the only currency the product targets today. EUR exists so that the
 * multi-currency contract is real rather than theoretical: [Money] arithmetic
 * must refuse to mix currencies, and that rule needs a second currency to be
 * expressible and testable. Adding a currency here must not require changes to
 * [Money] itself.
 *
 * @property minorUnitDigits how many decimal digits the currency's minor unit
 *   has (2 for PLN: 1 zloty = 100 grosze).
 */
enum class CurrencyCode(val minorUnitDigits: Int) {
    PLN(minorUnitDigits = 2),
    EUR(minorUnitDigits = 2),
    ;

    init {
        require(minorUnitDigits >= 0) { "minorUnitDigits must not be negative" }
    }
}
