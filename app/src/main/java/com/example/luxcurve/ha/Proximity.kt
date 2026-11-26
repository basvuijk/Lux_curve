package com.example.luxcurve.ha

object Proximity {
    /**
     * Is something near the sensor? Radar presence sensors report 0 when nobody is there and
     * anything else when somebody is. Ordinary proximity sensors often only report 0 (near) and
     * the maximum range (far); "far" is never "near".
     */
    fun isNear(value: Float, maxRange: Float, radar: Boolean, thresholdCm: Float): Boolean =
        if (radar) value != 0f else value < maxRange && value <= thresholdCm
}
