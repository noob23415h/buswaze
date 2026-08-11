# BusWaze

A navigation app for bus drivers in Israel.

**v0.1** — Map of Israel (OpenStreetMap) with live GPS location.

## How the APK gets built

This project builds automatically in the cloud with GitHub Actions:
every time the code is uploaded (pushed) to GitHub, the workflow in
`.github/workflows/build.yml` compiles it and produces `app-debug.apk`.

You can also build locally with Android Studio: open this folder,
let it sync, then **Build → Build APK(s)**.

## Roadmap

- v0.1: Map + GPS location (this version)
- v0.2: Bus-safe routing (avoids roads unsuitable for buses)
- v0.3: Bus line guidance from Israel MOT GTFS data
- v0.4: Depot & terminal maps
