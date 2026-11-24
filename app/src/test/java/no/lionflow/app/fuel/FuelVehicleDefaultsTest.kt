package no.lionflow.app.fuel

import no.lionflow.app.api.Vehicle
import no.lionflow.app.model.VehicleStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelVehicleDefaultsTest {
    private val hybrid = Vehicle("id", "vin", "Hybrid", "Peugeot", null, null, "208 Hybrid 100")

    @Test fun publishedTankCapacityRequiresTheSameModelAndFuel() {
        assertEquals(44.0, defaultTankCapacity(hybrid, FuelGrade.PETROL))
        val fullLabel = hybrid.copy(modelLabel = "Peugeot 208 Allure Hybrid 100 ch e-DCS6")
        assertEquals(44.0, defaultTankCapacity(fullLabel, FuelGrade.PETROL))
        assertNull(defaultTankCapacity(hybrid.copy(modelLabel = "2008 Hybrid 100 e-DCS6"), FuelGrade.PETROL))
        assertNull(defaultTankCapacity(hybrid.copy(modelLabel = "208 Hybrid"), FuelGrade.PETROL))
        assertNull(defaultTankCapacity(hybrid.copy(modelLabel = "208 Hybrid 100 kW"), FuelGrade.PETROL))
        assertNull(defaultTankCapacity(hybrid.copy(modelLabel = "e-208 Hybrid 100", motorization = "Electric"), FuelGrade.PETROL))
        assertNull(defaultTankCapacity(hybrid.copy(motorization = "PHEV"), FuelGrade.PETROL))
        assertNull(defaultTankCapacity(hybrid, FuelGrade.CNG))
    }

    @Test fun latestGaugeLevelTakesPrecedenceEvenForAnEmptyTank() {
        val status = status("""{"type":"Fuel","level":0,"autonomy":140}""")
        val percent = defaultStartingFuel(hybrid, status, FuelGrade.PETROL, null, true, 6.0)!!
        assertEquals(0.0, percent.value, 0.0)
        assertTrue(percent.fromGauge)
        val amount = defaultStartingFuel(hybrid, status("""{"type":"Fuel","level":59}"""), FuelGrade.PETROL, 44.0, false, 6.0)!!
        assertEquals(25.96, amount.value, 0.001)
        val full = defaultStartingFuel(hybrid, status("""{"type":"Fuel","level":100}"""), FuelGrade.PETROL, 43.333, false, 6.0)!!
        assertEquals(43.333, full.value, 0.0)
        assertNull(defaultStartingFuel(hybrid, status, FuelGrade.DIESEL, 44.0, true, 6.0))
        assertNull(defaultStartingFuel(null, status, FuelGrade.CNG, 12.0, true, 6.0))
    }

    @Test fun estimatedLevelNeedsReportedRangeAndIsMarkedAsAnEstimate() {
        val status = status("""{"type":"Fuel","level":101,"autonomy":200}""")
        val estimate = defaultStartingFuel(hybrid, status, FuelGrade.PETROL, 40.0, true, 5.0)!!
        assertEquals(25.0, estimate.value, 0.001)
        assertFalse(estimate.fromGauge)
        assertNull(defaultStartingFuel(hybrid, status, FuelGrade.PETROL, null, true, 5.0))
        assertNull(defaultStartingFuel(hybrid, status("""{"type":"Electric","level":90}"""), FuelGrade.PETROL, 44.0, true, 5.0))
    }

    private fun status(energy: String) = VehicleStatus.parse(JSONObject("""{"energies":[$energy]}"""), null)
}
