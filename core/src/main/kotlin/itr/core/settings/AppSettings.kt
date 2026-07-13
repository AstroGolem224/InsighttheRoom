package itr.core.settings

import itr.core.render.Units

data class AppSettings(val units: Units, val snapByDefault: Boolean, val diagnosticLog: Boolean) {
    companion object { val DEFAULT = AppSettings(Units.METRIC, snapByDefault = true, diagnosticLog = false) }
}

/** Units from a stored name; total — an unknown/legacy value falls back to METRIC (never throws). */
fun unitsFromName(name: String?): Units = Units.entries.firstOrNull { it.name == name } ?: Units.METRIC
