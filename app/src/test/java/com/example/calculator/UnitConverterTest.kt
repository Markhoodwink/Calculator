package com.example.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UnitConverterTest {

    private val engine = UnitConverterEngine()

    @Test
    fun testCategoryOrderAndCount() {
        assertEquals(11, engine.categories.size)
        assertEquals("numeral_systems", engine.categories[0].id)
        assertEquals("Системы счисления", engine.categories[0].name)
        assertEquals("length", engine.categories[1].id)
        assertEquals("Длина", engine.categories[1].name)
    }

    @Test
    fun testIntegerBaseConversions() {
        assertEquals("101010", engine.convertBase("42", 10, 2))
        assertEquals("FF", engine.convertBase("255", 10, 16))
        assertEquals("377", engine.convertBase("FF", 16, 8))
        assertEquals("45", engine.convertBase("101101", 2, 10))
        assertEquals("86A", engine.convertBase("1234", 10, 12))
        assertEquals("10201", engine.convertBase("100", 10, 3))
    }

    @Test
    fun testLargeBigIntegerConversion() {
        val input = "18446744073709551615"
        assertEquals("FFFFFFFFFFFFFFFF", engine.convertBase(input, 10, 16))
        assertEquals("1111111111111111111111111111111111111111111111111111111111111111", engine.convertBase(input, 10, 2))
    }

    @Test
    fun testFractionalAndNegativeConversions() {
        assertEquals("1010.101", engine.convertBase("10.625", 10, 2))
        assertEquals("-FF", engine.convertBase("-255", 10, 16))
        assertEquals("0.5", engine.convertBase("0.8", 16, 10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidDigitGuard() {
        engine.convertBase("102", 2, 10)
    }

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
        assertEquals(11, engine.categories.size)
        engine.categories.forEach { cat ->
            val all = engine.convertAll(1.0, cat.units.first().id, cat.id)
            assertEquals(cat.units.size, all.size)
            all.forEach { item ->
                assertNotNull(item.formattedValue)
            }
        }
    }
}
