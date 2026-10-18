# Sky Peaks 🏔️☀️🌙

A PeakFinder-style app that shows the **mountain skyline with peak names** and, on top of it, the **Sun, the Moon and all the planets** (Mercury, Venus, Mars, Jupiter, Saturn, Uranus, Neptune). The **terrain is downloaded once for offline use**. Portrait only.

## Tabs
- **Sky** – live view in the direction you point the phone.
  - **Panorama** (default): sky colour follows the Sun, mountains drawn in layers (far ridges lighter), peak labels with height and distance, azimuth strip (N/NE/E…), altitude lines.
  - **📷 AR ON**: the camera picture with the skyline outline, peak names and the Sun/Moon/planets laid over it.
  - **🧭**: ON = follow the phone's orientation, OFF = drag with a finger. Pinch to zoom in panorama mode.
  - Yellow line = Sun's path today (hour numbers on it), grey line = Moon's path. Bodies hidden behind a mountain are marked.
  - **Time**: slider and −1d / −1h / Now / +1h / +1d buttons to see where everything will be at any other time (e.g. sunrise behind that peak).
- **Map** – offline relief map made from the downloaded terrain (hill-shaded, coloured by height) with peaks, your position, view direction and the Sun/Moon directions. Pan and pinch-zoom.
- **Bodies** – azimuth, altitude, rise/set for every body (Urdu names too), Moon phase and illumination. If terrain is downloaded, rise/set **with the mountains in the way** is shown as well.
- **Offline** – choose 25 / 50 / 100 km, press **Download** (terrain tiles + named peaks), see how much storage is used, delete everything. You can also download for another place (type latitude, longitude) before a trip.

## How it works / limits
- Sun, Moon and planets are calculated on the phone (no internet): Sun ~0.01°, Moon ~0.3°, planets ~0.1–0.3° — fine for pointing the phone.
- Terrain: open "Terrarium" elevation tiles (AWS Open Data; SRTM and other public sources), zoom 10 (≈ 125 m per pixel at mid-latitudes). The skyline is computed with earth curvature and refraction, up to 140 km. Very near, narrow ridges are smoothed out by that resolution.
- Peak names/heights: © OpenStreetMap contributors (ODbL), fetched once through the Overpass API. If that step fails the skyline still works and summits are labelled with height only.
- Compass accuracy depends on the phone's magnetometer; move the phone in a figure of 8 to calibrate. Magnetic declination is corrected using the GPS position.
- Needs Location permission (and Camera only for AR). Android 7.0 (API 24) or newer.

Brought to you By: Black Falcon 🦅 (shown in the lower right corner of every screen)

## Build
Push to GitHub -> Actions -> "Build APK" -> download `SkyPeaks-APK`.
Local: `gradle assembleDebug` (JDK 17, Gradle 8.7+).
