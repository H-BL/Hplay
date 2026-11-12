package com.hpplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

/** The car-model picker must round-trip through its storage key and default to OTHER. */
class CarModelTest {
    @Test
    fun everyModelRoundTripsThroughItsKey() {
        for (model in CarModel.entries) {
            assertEquals(model, CarModel.fromStorageKey(model.storageKey))
        }
    }

    @Test
    fun unknownAndMissingKeysFallBackToOther() {
        assertEquals(CarModel.OTHER, CarModel.fromStorageKey(null))
        assertEquals(CarModel.OTHER, CarModel.fromStorageKey(""))
        assertEquals(CarModel.OTHER, CarModel.fromStorageKey("spm8666p2_64_car"))
    }

    @Test
    fun theDefaultIsOther() {
        assertEquals(CarModel.OTHER, CarModel.entries.first())
    }
}
