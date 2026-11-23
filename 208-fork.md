# Peugeot 208 Hybrid 100 e-DCS6

The personal branch starts from upstream `562064ab2aa605cb7ae7b7a5200f37a6a333de07`.

- **48 V drivetrain.** Settings → Profile has a per-car Auto, On or Off choice. Auto requires explicit mild-hybrid vehicle metadata. The phone, widget, Android Auto and Wear use the existing fuel display path. The 48 V record supplies neither plug-in charging data nor an estimate of electric distance. The car-report trip fallback still records odometer movement.
- **Fuel and CSV.** The PSA B2C trip schema defines totals in liters and kWh, and averages in cL/100 km and Wh/100 km. Recognized explicit units override those defaults. The three optional fuel columns preserve missing values, zero and the distinction between liters and l/100 km. Existing LionFlow exports still import with their original estimate flag.
- **Bluetooth.** The paired-device picker has a bounded scrolling list. Device identity still uses the address, including where display names match. The Compose test from PR #7 travels with the feature branch.
- **Trips.** Upstream's week-by-week loader and server next links serve phone, Android Auto and Wear. The existing car-report fallback keeps its time window. CSV rows are matched by ID or by an unambiguous match on both minute timestamps and rounded distance before fuel data is added to a reported trip.

## Historical branches

PR #6's alternative loader is superseded. The archived `fix/trip-loading` branch still has the offset and fractional timestamp tests, fallback IDs, and removal of the 50-trip display limit. These are independent follow-ups. `fix/trip-fuel-csv` keeps the original CSV round-trip and old-format tests. The new branch reuses the fuel columns and restores data through the current trip model. `fix/mild-hybrid` keeps the broader original implementation and tests. Sharing the upstream fuel display path replaces most of its per-screen capability changes.

## Vehicle checks

On the 208, verify the automatic metadata and manual override, fuel level and range when fresh or missing, units actually returned for real trips, weekly completeness and deduplication against the official app, and the fuel displays in Android Auto and on a paired Wear watch. Check the debug sign-in redirect while the official app is also installed. Verify the Bluetooth list and same-name selection on the Galaxy S25.
