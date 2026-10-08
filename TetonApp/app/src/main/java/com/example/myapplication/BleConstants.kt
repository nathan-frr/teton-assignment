package com.example.myapplication

import java.util.UUID

object BleConstants {

    val SERVICE_UUID: UUID =
        UUID.fromString("a5f1063d-0998-43be-9946-3eaacaf74ab1")

    val PUBLIC_KEY_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("75a65872-af14-4fde-9ab7-b68e3df74d30")

    val PROVISIONING_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("f62b8918-e9ef-4481-94b6-4e5c17f0a700")

    val CONFIGURATOR_PUBLIC_KEY_CHARACTERISTIC_UUID =
        UUID.fromString("c0ad84ed-5c63-496e-ab9b-5eb2a5315bd2")
}