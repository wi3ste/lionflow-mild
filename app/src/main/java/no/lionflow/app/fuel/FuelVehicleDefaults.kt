package no.lionflow.app.fuel

import no.lionflow.app.api.Vehicle
import no.lionflow.app.model.VehicleStatus

internal fun defaultTankCapacity(vehicle: Vehicle?, grade: FuelGrade): Double? =
    vehicle?.specifiedPetrolTankLiters?.takeIf { grade == FuelGrade.PETROL }

internal data class StartingFuel(val value: Double, val fromGauge: Boolean)

internal fun defaultStartingFuel(
    vehicle: Vehicle?, status: VehicleStatus?, grade: FuelGrade, tank: Double?, percent: Boolean, rate: Double,
): StartingFuel? {
    if (status == null) return null
    val knownGrade = vehicle?.let { if (it.specifiedPetrolTankLiters != null) FuelGrade.PETROL else FuelGrade.from(it.motorization) }
    if (knownGrade != null && knownGrade != grade) return null
    val liquid = grade == FuelGrade.PETROL || grade == FuelGrade.DIESEL
    if (knownGrade == null && !liquid) return null
    val measured = status.fuelPct?.takeIf { it in 0.0..100.0 }
    if (measured != null) {
        val value = if (percent) measured else tank?.let { it * (measured / 100) } ?: return null
        return StartingFuel(value, fromGauge = true)
    }
    val range = status.fuelRangeKm?.takeIf { liquid && it in 1.0..1600.0 } ?: return null
    val amount = (range * rate / 100).let { if (tank != null) it.coerceAtMost(tank) else it }
    val value = if (percent) tank?.let { amount / it * 100 } ?: return null else amount
    return StartingFuel(value, fromGauge = false)
}
