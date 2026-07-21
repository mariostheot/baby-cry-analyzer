package com.babycry.analyzer.ui

/**
 * Accepts only digits and one decimal separator for parent-entered measurements.
 * The separator is kept as typed, so both Greek-style commas and dots are supported.
 */
fun sanitizeDecimalMeasurementInput(input: String, maxFractionDigits: Int = 2): String {
    val output = StringBuilder()
    var separatorSeen = false
    var fractionDigits = 0

    for (char in input) {
        when {
            char.isDigit() && (!separatorSeen || fractionDigits < maxFractionDigits) -> {
                output.append(char)
                if (separatorSeen) fractionDigits++
            }
            (char == ',' || char == '.') && !separatorSeen -> {
                output.append(char)
                separatorSeen = true
            }
            char == ',' || char == '.' -> break
        }
    }
    return output.toString()
}
