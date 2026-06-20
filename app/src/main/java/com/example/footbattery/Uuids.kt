package com.example.footbattery

import java.util.UUID

/** Standard Bluetooth SIG UUIDs, referenced as Uuids.SERVICE etc. */
object Uuids {
    val SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
