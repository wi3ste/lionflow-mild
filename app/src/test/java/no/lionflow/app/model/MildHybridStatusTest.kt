package no.lionflow.app.model

import no.lionflow.app.api.Vehicle
import no.lionflow.app.ui.Caps
import no.lionflow.app.ui.Tab
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MildHybridStatusTest {
    private val fuel = JSONObject("""{"type":"Fuel","level":59,"autonomy":360}""")
    private val electric = JSONObject("""{
        "type":"Electric","level":63,"autonomy":240,
        "extension":{"electric":{"charging":{"status":"InProgress","plugged":true},
        "battery":{"load":{"residual":480,"capacity":36000}}}}
    }""")

    private fun status(vararg energies: JSONObject) =
        JSONObject().put("energies", JSONArray(energies.toList()))

    @Test fun mildHybridUsesFuelAndDiscardsChargingData() {
        val st = ChargePower.record(VehicleStatus.parse(status(fuel, electric), null, mildHybrid = true))
        assertEquals("Fuel", st.energyType)
        assertTrue(st.isMildHybrid)
        assertEquals(59.0, st.batteryPct)
        assertEquals(360.0, st.rangeKm)
        assertFalse(st.plugged)
        assertFalse(Caps.tab(Tab.Charging, st))
        assertNull(st.chargingStatus)
        assertNull(st.residualKwh)
        assertNull(st.capacityKwh)
        assertFalse(st.rangeEstimated)
    }

    @Test fun missingFuelRangeIsNotEstimatedFromTheBattery() {
        val partialFuel = JSONObject("""{"type":"Fuel","level":59}""")
        val st = ChargePower.record(VehicleStatus.parse(status(partialFuel, electric), null, mildHybrid = true))
        assertNull(st.rangeKm)
        assertFalse(st.rangeEstimated)
        assertEquals("Fuel", VehicleStatus.parse(status(electric), null, mildHybrid = true).energyType)
    }

    @Test fun existingBatteryAndTankInterpretationsStillApply() {
        val bev = VehicleStatus.parse(status(electric), null)
        assertEquals("Electric", bev.energyType)
        assertEquals(240.0, bev.rangeKm)
        assertTrue(bev.isCharging)
        val phev = VehicleStatus.parse(status(fuel, electric), null)
        assertEquals("Hybrid", phev.energyType)
        assertEquals(63.0, phev.batteryPct)
        assertEquals(360.0, phev.fuelRangeKm)
        assertTrue(Caps.tab(Tab.Charging, phev))
        val missingElectric = JSONObject("""{"type":"Electric"}""")
        assertEquals("Hybrid", VehicleStatus.parse(status(fuel, missingElectric), null).energyType)
        val petrol = VehicleStatus.parse(status(fuel), null)
        assertEquals("Fuel", petrol.energyType)
        assertFalse(petrol.isMildHybrid)
        assertEquals(59.0, petrol.batteryPct)
    }

    @Test fun metadataMustBeExplicitForAutomaticMode() {
        val vehicle = Vehicle("vehicle", "synthetic", "Hybrid", "Peugeot", null, null)
        assertFalse(vehicle.reportsMildHybrid)
        assertFalse(vehicle.copy(motorization = "Electric").reportsMildHybrid)
        assertFalse(vehicle.copy(motorization = "Thermic").reportsMildHybrid)
        assertFalse(vehicle.copy(motorization = "PHEV").reportsMildHybrid)
        assertTrue(vehicle.copy(motorization = "MHEV").reportsMildHybrid)
        assertTrue(vehicle.copy(motorization = "48 V Mild hybrid").reportsMildHybrid)
        assertFalse(Vehicle.fromJson(vehicle.toJson()).reportsMildHybrid)
        assertTrue(Vehicle.fromJson(vehicle.toJson()).needsBranding)
    }

    @Test fun readsAndCachesTheSpecificPeugeotHybridVersion() {
        val payload = JSONObject("""{
            "id":"vehicle","vin":"synthetic","motorization":"Hybrid",
            "_embedded":{"extension":{"branding":{"brand":"Peugeot","label":"208 Hybrid 100 e-DCS6 Allure"}}}
        }""")
        val vehicle = Vehicle.fromApi(payload, "Other")
        assertTrue(vehicle.reportsMildHybrid)
        assertTrue(Vehicle.fromJson(vehicle.toJson()).reportsMildHybrid)
        assertFalse(vehicle.copy(modelLabel = "208 Hybrid").reportsMildHybrid)
        assertFalse(vehicle.copy(modelLabel = "308 Hybrid 180 e-EAT8").reportsMildHybrid)
        assertFalse(vehicle.copy(motorization = "PHEV").reportsMildHybrid)
        assertFalse(vehicle.copy(brand = "Other").reportsMildHybrid)
        assertTrue(Vehicle.fromApi(JSONObject("""{
            "id":"vehicle","vin":"synthetic","motorization":"Hybrid","brand":"Peugeot",
            "label":"208 Hybrid 100 eDCS6"
        }"""), "Other").reportsMildHybrid)
    }

    @Test fun aRefreshedVehicleWithoutBrandingKeepsManualFallback() {
        val vehicle = Vehicle.fromApi(JSONObject("""{
            "id":"vehicle","vin":"synthetic","motorization":"Hybrid","brand":"Peugeot"
        }"""), "Other")
        val cached = Vehicle.fromJson(vehicle.toJson())
        assertFalse(cached.reportsMildHybrid)
        assertFalse(cached.needsBranding)
    }
}
