# Peugeot 208 Hybrid 100 e-DCS6

The personal branch starts from upstream `562064ab2aa605cb7ae7b7a5200f37a6a333de07`.

- **48 V drivetrain.** Settings → Profile has a per-car Auto, On or Off choice. Auto accepts explicit mild-hybrid motorization or Peugeot Hybrid metadata that names the 208 Hybrid 100 e-DCS6. The phone, widget, Android Auto and Wear use the existing fuel display path. The 48 V record supplies neither plug-in charging data nor an estimate of electric distance. The car-report trip fallback still records odometer movement.
- **Fuel and CSV.** The PSA B2C trip schema defines totals in liters and kWh, and averages in cL/100 km and Wh/100 km. Recognized explicit units override those defaults. A positive fuel volume without explicit units needs agreement with the reported rate and distance. Trip summaries weight usable rates by distance and keep estimates separate. CSV exports leave withheld volumes empty and mark usable volumes in `fuel_volume_verified`. Old exports still import. The check runs when the data is read and preserves the stored values.
- **Bluetooth.** The paired-device picker has a bounded scrolling list. Device identity still uses the address, including where display names match. The Compose test from PR #7 travels with the feature branch.
- **Trips.** The week-by-week UTC loader follows server next links, includes zero-distance trips and keeps one server row per ID. Home and Trips use the same seven local calendar days, and Trips loads list rows as they scroll into view. The existing car-report fallback keeps its time window. CSV rows are matched by ID or by an unambiguous match on both minute timestamps and rounded distance before fuel data is added to a reported trip.

## Historical branches

PR #6's alternative loader is superseded. This branch adapts offset and fractional timestamp tests, stable fallback IDs and the page-cursor regression test from the archived trip work. The current CSV tests cover round-trip and old-format exports, and the status tests cover the mild-hybrid fuel display path.

## Vehicle checks

On the 208, verify the automatic metadata and manual override, fuel level and range when fresh or missing, units actually returned for real trips, weekly completeness and deduplication against the official app, and the fuel displays in Android Auto and on a paired Wear watch. Check the debug sign-in redirect while the official app is also installed. Verify the Bluetooth list and same-name selection on the Galaxy S25.
