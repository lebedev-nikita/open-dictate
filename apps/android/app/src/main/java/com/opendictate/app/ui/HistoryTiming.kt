package com.opendictate.app.ui

import java.text.DecimalFormatSymbols
import java.util.Locale

internal fun formatHistorySeconds(milliseconds: Long, locale: Locale): String {
    require(milliseconds >= 0)
    val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    if (milliseconds < 100) return "<0${separator}1"
    val tenths = milliseconds / 100 + if (milliseconds % 100 >= 50) 1 else 0
    return "${tenths / 10}$separator${tenths % 10}"
}
