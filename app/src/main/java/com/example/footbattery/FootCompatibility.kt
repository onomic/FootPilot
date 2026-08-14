package com.example.footbattery

data class FootGattProfilePresence(
    val batteryService: Boolean,
    val batteryLevel: Boolean,
    val ossurService: Boolean,
    val aa01: Boolean,
    val aa02: Boolean
)

fun isCompatibleFootProfile(profile: FootGattProfilePresence): Boolean =
    profile.batteryService && profile.batteryLevel && profile.ossurService &&
        profile.aa01 && profile.aa02

class IncompatibleFootException(message: String) : BleSessionException(message)
