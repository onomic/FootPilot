package com.example.footbattery

import java.util.UUID

/** Standard Bluetooth SIG and confirmed Össur proprietary UUIDs. */
object Uuids {
    val SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val OSSUR_SERVICE: UUID = UUID.fromString("1610aa00-0111-0899-2503-732905714219")
    val AA01: UUID = UUID.fromString("1610aa01-0111-0899-2503-732905714219")
    val AA02: UUID = UUID.fromString("1610aa02-0111-0899-2503-732905714219")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
