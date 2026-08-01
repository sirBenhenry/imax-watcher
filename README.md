# Odyssey Watch

Android app that watches Cineplex for cinema seats and pings you the moment a good one opens up.

Screening sold out? Pick the film, pick the cinemas, and it checks in the background every
15 minutes. When a seat frees up that isn't in the front rows, you get a notification with
a link straight to that screening's checkout.

## Install

Grab `OdysseyWatch.apk` from [Releases](../../releases), open it on your phone, and allow
installing from unknown sources.

## Use

1. Allow notifications, then tap **Disable battery optimisation**.
2. **Choose film** → **Choose cinemas**.
3. Set format, row and time filters.
4. **Check once now** to confirm it works, then **Start watching**.

Leave the ongoing notification alone — dismissing it stops the app.

## Notes

- Canada only — it uses Cineplex's own API.
- Ignores wheelchair and companion seats, which always look free but aren't.
- Also alerts the first time a film goes on sale, so you can arm it months ahead.
- How it works: [docs/TECHNICAL.md](docs/TECHNICAL.md)
