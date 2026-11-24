# LionFlow privacy policy

_Last updated: 24 November 2026_

LionFlow is an unofficial, open-source app for controlling your own Peugeot. It has no servers of its own, no accounts of its own, no ads and no analytics. The developer receives none of your data.

## What the app handles, and where it goes

| Data | Why | Sent to |
|---|---|---|
| Your Peugeot sign-in (OAuth tokens), remote-control code state | To read your car's status and send commands | Stellantis/Peugeot (`api.groupe-psa.com`, `idpcvs.peugeot.com`, `mwa.mpsa.com`, `otp.mpsa.com`). Stored encrypted on your phone with Android Keystore. |
| Car status, trips, maintenance | Shown in the app, widget and Android Auto | Fetched from Stellantis; kept on your phone only |
| Your location (only while the app or Android Auto map is open) | Map position, weather, nearby chargers | Approximate coordinates go to MET Norway (weather), OpenStreetMap Overpass or NOBIL (chargers) and OpenFreeMap (map tiles for the area you view) |
| Car photo, service history, imported trips, charging log, home and charger places | Your own notes | Stay on your phone |
| Address of a charging spot / your home or charger address | Show where you charged; find coordinates for an address you type | OpenStreetMap Nominatim (`nominatim.openstreetmap.org`) receives the coordinates or the address for the lookup |
| Trip planner (only when you plan a trip) | Address suggestions, the driving route and the fast chargers along it | The text you type and your approximate position go to Photon (`photon.komoot.io`) for address search; the start and destination go to the OSRM route service (`routing.openstreetmap.de`, or `router.project-osrm.org` as a fallback) and to FOSSGIS' Valhalla route service (`valhalla1.openstreetmap.de`) for routes with car ferries and other alternatives; the route line goes to OpenStreetMap Overpass to find chargers (or, for routes in the Nordic countries when a NOBIL key is available, boxes around the route go to NOBIL, which also tells how many charge points are free right now). Points along the route and the departure time go to Open-Meteo (`api.open-meteo.com`) for heights and the weather forecast. When the route has a car ferry, the two quays and the time you get there go to Entur (`api.entur.io`, Norway) or Transitous (`api.transitous.org`, elsewhere) for the next departure. Saved and recent addresses stay on your phone. |
| Fuel map and planner | Station prices, refuelling stops, consumption and costs | Ministero delle Imprese e del Made in Italy (`www.mimit.gov.it`) receives requests for the same two public Italian files for every user. Address search uses Photon. The trip endpoints, vias and coordinates needed to verify station access go to OSRM. LionFlow calculates fuel and costs locally from trip history and vehicle settings. |
| Recorded routes (only if you turn on Route tracking) | Show your drives on a map | Stay on your phone. Recorded with the phone's GPS while it's connected to your car over Bluetooth. |
| Update checks (GitHub version only) | Finding new versions | GitHub (`api.github.com`) |
| Active-device count (GitHub version only, at most once a day) | Counting how many devices that installed LionFlow from GitHub are in use: the app downloads a tiny empty file (`ping.txt`) from the GitHub release, and GitHub's own download counter for that file is shown as a badge in the README. LionFlow sends no ID, no account or car details and nothing about your phone, and the developer receives no data, only the counter. Copies installed from Google Play don't do this. | GitHub (`github.com`) |

No data is sold or shared for advertising. Nothing is backed up to the cloud by the app (Android backup is turned off).

## Deleting your data

LionFlow has no servers, so there is nothing stored with the developer to delete. Everything LionFlow keeps is on your phone, and you delete it yourself:

1. **Delete everything:** open LionFlow → Settings → **Sign out**, or uninstall LionFlow (Android settings → Apps → LionFlow → Uninstall). This removes all data LionFlow stored: sign-in tokens, remote-control setup, car status, trips, charging log, places and prices, recorded routes, service history, car photo and settings.
2. **Delete parts of it:**
   - Recorded routes: Settings → Route tracking → **Delete all recorded routes**, or open a trip and tap **Delete route**.
   - A charging session: open it in Charging and tap **Delete session**.

Nothing is kept after this, and there is no retention period. Your MyPeugeot account and the data Peugeot/Stellantis holds about your car are managed by Peugeot; to delete those, contact Peugeot or use the MyPeugeot app.

## Contact

Open an issue at https://github.com/thocubeat/lionflow/issues.
