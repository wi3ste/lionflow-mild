package no.lionflow.app.plan

import org.junit.Assert.*
import org.junit.Test

class EvTripPlannerTest {
    private val road = RouteData(doubleArrayOf(42.0, 42.1, 42.2), doubleArrayOf(12.0, 12.1, 12.2),
        doubleArrayOf(0.0, 80.0, 180.0), doubleArrayOf(0.0, 50.0, 110.0), doubleArrayOf(0.0, 95.0, 100.0))
    private val model = Consumption.Model(1.0, 0)

    @Test fun electricPlannerSkipsChargingWhenBatteryCanFinish() {
        val result = TripPlanner.plan(road, doubleArrayOf(0.0, 15.0, 35.0), emptyList(),
            Options(90.0, 15, 80, 65.0, 120.0), emptyList(), emptySet(), model)
        assertTrue(result.ok)
        assertTrue(result.stops.isEmpty())
        assertEquals(90.0 - 35.0 / 65.0 * 100, result.arrivePct, 1e-8)
    }

    @Test fun electricPlannerRetainsChargersAndUsesEvPricing() {
        val charger = PlanCharger("id", "Charge", "Operator", 42.1, 12.1,
            150.0, 4, 1, 80.0, 0.0, 0.50, "EUR")
        val result = TripPlanner.plan(road, doubleArrayOf(0.0, 15.0, 35.0), listOf(charger),
            Options(45.0, 15, 80, 65.0, 120.0), emptyList(), emptySet(), model)
        assertTrue(result.ok)
        assertEquals("id", result.stops.single().charger.id)
        assertTrue(result.stops.single().kwh > 0)
        assertTrue(result.arrivePct >= 15)
        assertEquals("EUR", result.currency)
        assertTrue(result.cost!! > 0)
    }
}
