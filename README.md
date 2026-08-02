# IMAX Watcher

Android app that watches Cineplex for **IMAX 70mm** seats and pings you the moment a good
one opens up.

Screening sold out? Pick the film, pick the cinemas, and it checks in the background.
When a seat frees up that isn't in the front rows, you get a notification with a link
straight to that screening's checkout.

## Install

Grab `ImaxWatcher.apk` from [Releases](../../releases), open it on your phone, and allow
installing from unknown sources.

## Use

1. Allow notifications, then tap **Disable battery optimisation**.
2. **Film** → pick one. Only films actually screening in 70mm are listed.
3. **Cinemas** → all 8 by default.
4. **Check once now**, then **Start watching**.

Tap any screening to see a **live seat map** — matching seats in gold, front rows and
accessibility seats outlined, taken seats dark.

Leave the ongoing notification alone; dismissing it stops the app.

## Notes

- IMAX 70mm only, at the 8 Cineplex venues in Canada that have a 70mm projector:
  Calgary, Edmonton, Langley, Richmond, Halifax, Mississauga, Vaughan, Montréal.
- Ignores wheelchair and companion seats, which always look free but aren't.
- Also alerts the first time a film goes on sale, so you can arm it months ahead.
- How it works: [docs/TECHNICAL.md](docs/TECHNICAL.md)
