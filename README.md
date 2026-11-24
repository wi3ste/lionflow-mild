# LionFlow

![Downloads](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/thocubeat/lionflow/stats/downloads.json) ![Active users (last 24h)](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/thocubeat/lionflow/stats/devices.json)

**[⬇ Download the latest version (APK)](https://github.com/thocubeat/lionflow/releases/latest/download/LionFlow.apk)** · [All releases](https://github.com/thocubeat/lionflow/releases)

## What is LionFlow?

LionFlow is a free, open-source Android app for your own connected Peugeot, Citroën, DS, Opel or Vauxhall. It's an unofficial alternative to the MyPeugeot, MyCitroën, MyDS, myOpel and MyVauxhall apps. It signs in with your normal account for your car's brand and talks to the same Stellantis servers, so it shows the same car data and sends the same remote commands. The difference is how it's built and what it does with that data.

**Subscriptions (decided by the carmaker, not by LionFlow)**
Status, battery, range, trips and service data come with your brand account. Charging control and pre-heating on electric and plug-in hybrid cars need the brand's e-Remote Control service (included in the Connect ONE pack on many new cars, otherwise Connect PLUS or a separate subscription). Lock, unlock, lights and horn need the Remote Control service (Connect PLUS). What is included, and for how long, depends on model, country and order date. Activate it in the brand's own app first; LionFlow can't do that for you. The app shows this for your brand before sign-in and next to the functions that need it.

**How it differs from MyPeugeot**

- **Quick and to the point:** opens straight to battery, range and charging, with no menus or offers in the way. It wakes the car in the background when the app opens, so commands get through faster, and shows each step while a command is on its way ("Waking the car…", "Car received it").
- **Real charging power in kW:** the car only reports "km of range per hour". LionFlow works out the actual kW from the energy going into the battery.
- **Charging log:** every session with time, % from–to, kWh, duration, average and peak kW, and where. Splits home from away, and estimates cost from your home price and up to 5 known chargers.
- **Charge limit:** switch the car between its daily limit and a full charge for a trip, or let LionFlow stop charging at any % you choose, even on chargers that ignore the car's limit.
- **Pre-heating schedules:** set "ready at 07:00, Monday to Friday" in a few taps.
- **Range that makes sense:** when Peugeot's server reports 0 km by mistake, LionFlow estimates from your own driving instead.
- **Clear errors:** when something fails you get the actual reason and code, not a generic "try again later".
- **More on screen:** a Wear OS watch app, a home-screen widget, a free 2D/3D charger map, local weather and forecast, and layouts for foldables and tablets.
- **Your language:** English, French, German, Spanish or Norwegian (Settings → Appearance → Language), or follow the phone.
- **Several cars:** switch between the cars on your MyPeugeot account, including petrol and diesel ones (fuel level and range).
- **Private and secure:** no ads, no analytics, no tracking. Sign-in tokens are encrypted on the phone, and remote actions can require your fingerprint. All the code is public, so anyone can check what it does.

### First sign-in (2 minutes)

1. **Have the brand's own app working first** (MyPeugeot, MyCitroën, MyDS, myOpel or MyVauxhall). Your car must already be added there, and you need its 4-digit security code.
2. **Install:** open the APK link above on your phone → tap the file → allow *Install unknown apps* for your browser when asked.
3. **Sign in:** open LionFlow → pick your **car brand** and country → **Sign in** → log in on the brand's own page with the same email and password as its app.
4. **Back to the app:** if Android asks which app should open the link, choose **LionFlow** (not MyPeugeot). The app opens and loads your car.
5. **Remote control (optional):** tap **Send SMS code** → enter the SMS code and your MyPeugeot security code → **Activate**. Do it once; you can skip it and do it later under Settings › Security.
6. **Allow location and notifications** when asked — for the map, local weather and car alerts.

> Didn't come back to the app after signing in? Open LionFlow again and repeat step 3. Still stuck? Settings › Security › *Command & error log* shows what went wrong.

## Updates

LionFlow updates itself. When a new version is ready, a popup shows what's new; tap **Install now**. Only versions signed with LionFlow's key are installed. You can turn the check off under Settings › App updates.

## How car data is updated

- **When you open the app** (or switch back to it), LionFlow fetches fresh status, unless it already did so less than 5 minutes ago.
- **While the app is open**, it updates by itself every 5 minutes.
- **The refresh button** (on Home and on the widget) updates right away, at any time.
- **In the background**, Android lets the app check about every 30 minutes. This keeps the widget and the notifications up to date.

Automatic updates can be turned off under Settings › Driving data. The refresh button always works.

## Charging log

The Charging tab logs every charging session: when, from what % to what %, energy (kWh), duration, average and peak kW, AC or DC, and where.

Under **Places & prices**, set your **Home** (sessions within 50 m count as home) and up to **5 known chargers** (work, cabin, a free charger) with a price per kWh. You can also set prices for other AC and DC chargers. The log shows home vs away kWh and estimated costs for the last 30 days. Everything stays on the phone.

## Wear OS watch

LionFlow also has a watch app for Wear OS (Wear OS 3 or newer). It shows battery % and range, kilometres driven today and yesterday, and where the car is parked. You can lock or unlock, flash the lights and start pre-heating, and every action asks you to confirm first.

The watch gets everything through LionFlow on your phone, so your Peugeot sign-in stays on the phone. Keep the phone app updated and signed in.

**Install on the watch:**

1. Download `Watch-LionFlow-1.1.x.apk` from the [latest release](https://github.com/thocubeat/lionflow/releases/latest) to your computer, and install [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) (adb).
2. On the watch: **Settings → System → About** → tap **Build number** 7 times. Then in **Developer options**, turn on **ADB debugging** and **Wireless debugging**.
3. Under Wireless debugging, tap **Pair new device** and run these on the computer, using the address and code the watch shows:
   ```
   adb pair <IP:port> <code>
   adb connect <IP:port>
   adb install Watch-LionFlow-1.1.x.apk
   ```
4. Open LionFlow on the watch. Your phone needs to be nearby, with LionFlow installed.

The watch app doesn't update itself. Install a new version the same way when you want it.

## Features

- **Sign-in** on Peugeot's own page, "Remember me", and app lock with your security code or fingerprint
- **Status:** battery, range, charging, doors, odometer, position and next service
- **Remote control:** lock/unlock, lights, horn, pre-heating, and start/stop charging, confirmed with fingerprint or security code
- **Map:** free vector map (MapLibre + OpenFreeMap) in 2D/3D that you can drag, zoom and rotate, showing you, the car and charging stations — free from OpenStreetMap (no key), or from NOBIL if you add a free API key
- **EV trip planner** (fully electric cars): type where you are going, and LionFlow draws the route and picks fast-charging stops from your start charge and the lowest charge you accept. Battery use is tuned with your own longest trips. Change how full to charge or how long to stay at each stop, swap chargers, and save addresses. Prices are the operators' usual drop-in prices from a small built-in table (Norway and Germany so far), not live prices
- **Fuel trip planner** (combustion and hybrid cars): plan fuel stops in Italy with official MIMIT prices, road-verified detours, fuel reserve and tank limits. Compare economy, balanced and fast plans, browse nearby prices and favorites, log refuels and compare saved trips with recorded drives. [Fuel Trip Planner guide](docs/FUEL_TRIP_PLANNER.md) · [Fuel Management review](docs/FUEL_MANAGEMENT_REVIEW.md)
- **Weather** now and the forecast where you are (MET Norway)
- **Trips** with CSV export and import, plus your own service history
- **Home-screen widget:** battery %, charging, range, pre-heating and outside temperature
- **Notifications:** charging complete or stopped, pre-heating finished, car left unlocked, low battery and service due
- Light, dark or system theme, km or miles, °C or °F
- Adapts to foldables and tablets with a side menu and a two-column home screen

## Security

- Tokens, the remote-control state and your security code are encrypted with an Android Keystore key (AES-256-GCM).
- Your password is only typed on Peugeot's own page. The app never sees it.
- There is no backend and no analytics. The app talks directly to Peugeot, OpenFreeMap, OpenStreetMap (Overpass), Photon and OSRM (trip planner), MIMIT (Italian fuel stations), MET Norway, GitHub (updates) and — only if you choose it — NOBIL.


## Disclaimer

LionFlow is an independent app, not made, endorsed or supported by Stellantis, Peugeot, Citroën, DS Automobiles, Opel or Vauxhall. All brand and app names are trademarks of their owners and are only used to say which cars the app works with. Remote commands act on a real car, so make sure it's safe to lock, unlock, heat or charge it.

## Support LionFlow

LionFlow is free, has no ads and never will. If it makes your car a bit nicer to live with, you may donate what you want, so that I'm able to work with this in my spare time:

[![Support on Ko-fi](https://img.shields.io/badge/Support-Ko--fi-FF5E5B?logo=ko-fi&logoColor=white)](https://ko-fi.com/thocubeat)

Thank you! ☕

## License

GPL-3.0 — see [`LICENSE`](LICENSE). Third-party code, licences and data sources are listed in [`NOTICE.md`](NOTICE.md).

## Credits

The Stellantis sign-in, API and MQTT flow are ported from
[homeassistant-stellantis-vehicles](https://github.com/andreadegiovine/homeassistant-stellantis-vehicles) (MIT), and the
one-time-code generator from [psa_car_controller](https://github.com/flobz/psa_car_controller) (GPL-3.0) by Florian Bezannier.
Thanks to both projects — LionFlow wouldn't exist without them. Details in `NOTICE.md`.
