package itr.core.render

import java.util.Locale
import kotlin.math.roundToInt

enum class Units {
    METRIC {
        override fun length(m: Double) = String.format(Locale.US, "%.2f m", check(m))
        override fun area(m2: Double) = String.format(Locale.US, "%.2f m²", check(m2))
    },
    IMPERIAL {
        override fun length(m: Double): String {
            val inches = (check(m) / 0.0254).roundToInt()   // round total inches ONCE, then split
            return "${inches / 12} ft ${inches % 12} in"
        }
        override fun area(m2: Double) = String.format(Locale.US, "%.2f ft²", check(m2) / 0.09290304)
    };
    abstract fun length(m: Double): String
    abstract fun area(m2: Double): String
    protected fun check(v: Double): Double { require(v.isFinite() && v >= 0.0) { "measurement must be finite and >= 0: $v" }; return v }
}
