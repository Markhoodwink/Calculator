package com.example.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UnitConverterTest {

    private val engine = UnitConverterEngine()

    @Test
    fun testKmToMiles() {
        val result = engine.convert(10.0, "km", "mi", "length")
        assertEquals(6.213712, result, 0.0001)
    }

    @Test
    fun testM3ToLiters() {
        val result = engine.convert(1.0, "m3", "liter", "volume")
        assertEquals(1000.0, result, 0.0001)
    }

    @Test
    fun testKmhToMs() {
        val result = engine.convert(108.0, "kmh", "ms", "speed")
        assertEquals(30.0, result, 0.0001)
    }

    @Test
    fun testCelsiusToFahrenheitNegative() {
        val result = engine.convert(-40.0, "celsius", "fahrenheit", "temperature")
        assertEquals(-40.0, result, 0.0001)
    }

    @Test
    fun testCelsiusToKelvinZero() {
        val result = engine.convert(0.0, "celsius", "kelvin", "temperature")
        assertEquals(273.15, result, 0.0001)
    }

    @Test
    fun testMiBToBytes() {
        val result = engine.convert(1.0, "mib", "byte", "data")
        assertEquals(1048576.0, result, 0.0001)
    }

    @Test
    fun testSotkaToM2() {
        val result = engine.convert(1.0, "sotka", "m2", "area")
        assertEquals(100.0, result, 0.0001)
    }

    @Test
    fun testConvertAllCategories() {
        assertEquals(10, engine.categories.size)
        engine.categories.forEach { cat ->
            val all = engine.convertAll(1.0, cat.units.first().id, cat.id)
            assertEquals(cat.units.size, all.size)
            all.forEach { item ->
                assertNotNull(item.formattedValue)
            }
        }
    }
}
