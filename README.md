# Odyssey Watch

An Android app that watches for a decent seat opening up for **The Odyssey in IMAX 70mm
at Cineplex Cinemas Vaughan**, and notifies you the moment one does with a link straight
into that screening's booking page.

Built for a fixed window: **24 Jul – 4 Aug 2026**.

## What it actually watches

| Setting | Value |
| --- | --- |
| Theatre | Cineplex Cinemas Vaughan (`theatreId 7408`), 3555 Highway 7 West |
| Movie | The Odyssey |
| Format | IMAX 70mm only (`experienceTypes` contains `70mm`) |
| Screenings | 11:00 and 15:00 daily (19:00 / 23:00 togglable in-app) |
| Rows | C and further back — rows A and B are ignored |
| Seat types | `Standard` only |
| Alert threshold | 1 seat |

### Why wheelchair and companion seats are excluded

The IMAX house at Vaughan has 8 accessibility seats in row E — `EW1–EW4` (Wheelchair) and
`EC1/EC4/EC21/EC24` (Companion). These report `Available` in **almost every screening**,
including ones that are otherwise sold out, because they're held back for accessibility
booking. Without a `type == "Standard"` filter the app would fire an alert on essentially
every poll, forever. This is the single most important filter in the whole thing.

### Row geometry

The auditorium is 10 lettered rows, **A at the front** through **J at the back** (plus two
zero-seat spacer rows). Seat IDs are `1_<physicalRow>_<physicalColumn>` where the physical
row number runs *backwards* from the label — row A is physical 12, row J is physical 1.
The app keys off the row `label` from the layout payload, never off the seat ID, so this
inversion doesn't matter.

## The Cineplex API

All of this is the same undocumented API that cineplex.com's own web front-end calls.
No login is involved and only public showtime/seat data is read. The subscription key is
the public one shipped in their JavaScript bundle.

```
Header: Ocp-Apim-Subscription-Key: dcdac5601d864addbc2675a2e96cb1f8
```

**Showtimes** — cheap, and already includes `seatsRemaining`:

```
GET https://apis.cineplex.com/prod/cpx/theatrical/api/v1/showtimes
      ?language=en&locationId=7408&date=2026-07-26
```

Returns `dates[] → movies[] → experiences[] → sessions[]`. Each session carries
`vistaSessionId`, `showStartDateTime` (local theatre time), `seatsRemaining`, `isSoldOut`,
`auditorium`, and `deeplinkUrl`.

Note: the response is sometimes a bare object and sometimes a single-element array;
`experienceTypes` is sometimes a string and sometimes an array. The parser handles both.

**Seat layout** — static per session, so it's cached on disk (~60 KB, the largest payload):

```
GET https://apis.cineplex.com/prod/ticketing/api/v1/theatre/7408/showtime/{id}/seat-layout
```

**Seat availability** — the live bit, ~7 KB:

```
GET https://apis.cineplex.com/prod/ticketing/api/v1/theatre/7408/showtime/{id}/seat-availability
```

Returns `seatAvailabilities: { "<seatId>": "Available" | "Occupied" | "Broken" }`.

**Booking deeplink** — 302s to the movie page with the ticketing flow already opened on the
right session:

```
https://apis.cineplex.com/prod/cpx/theatrical/deeplink?s={sessionId}&a=0000000001&l=7408&m=the-odyssey&ss=False
```

## Polling strategy

Two-stage, to stay light on Cineplex's servers and on your battery:

1. One cheap showtimes call per date (12 calls) gives `seatsRemaining` for every session.
2. The expensive seat-map pair is only fetched for sessions whose seat count **moved**
   since the last poll.
3. Every 4th cycle does a **full sweep** regardless, so a swap that leaves the count
   unchanged (someone frees E12 while someone takes A3) can't hide indefinitely.

Steady state is ~12 requests per cycle instead of ~60.

Alerts are de-duplicated per session: you're notified about a seat once, and only again if
it goes away and comes back.

## Running it

The watcher is a **foreground service** with a persistent silent notification, not
WorkManager — periodic work gets batched and deferred under Doze, which is the wrong
tradeoff when a seat may only exist for a few minutes. It targets SDK 34 deliberately:
apps targeting Android 15+ get a 6-hour/day cap on `dataSync` foreground services.

### Install

```
adb install -r OdysseyWatch.apk
```

Or copy `OdysseyWatch.apk` to the phone and open it (allow "install unknown apps").

### After installing

1. Open the app, allow notifications.
2. Tap **Disable battery optimisation** and confirm — without this Android will eventually
   throttle the polling.
3. Tap **Check once now** to confirm it works; the last-scan panel fills in.
4. Tap **Start watching**.

Leave the persistent "Watching IMAX 70mm seats" notification alone — dismissing it stops
the service.

## Building from source

Requires JDK 17+ and an Android SDK with platform 34. `local.properties` must point at the
SDK.

```
gradle assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`, signed with the debug key so it
can be sideloaded directly.
