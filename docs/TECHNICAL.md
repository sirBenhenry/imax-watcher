# Odyssey Watch

An Android app that watches Cineplex for seats that are actually worth booking, and
notifies you the moment one appears with a link straight into that screening's checkout.

Built originally to get a non-front-row IMAX 70mm seat for *The Odyssey*; now it works for
**any film, at any Cineplex cinema in Canada, at any point in the bookable future**.

## What it does

- Pick **any film** from Cineplex's catalogue (~250 titles, running roughly 18 months
  ahead — *Dune: Part 3* and 2027 releases are already selectable).
- Pick **any set of cinemas**, browsable and searchable, grouped by province. One tap
  selects all 8 IMAX 70mm venues.
- Filter by **format** (IMAX 70mm, IMAX, UltraAVX, D-BOX, …), **row**, **time of day**,
  and **how far ahead** to look.
- Alerts on a matching seat opening up, **and** on a film going on sale for the first
  time at a cinema you're watching.
- Runs indefinitely — no hardcoded end date.

## Cinemas with IMAX 70mm

Found by sweeping all ~150 Cineplex theatres for a `70mm` experience:

| Theatre | City | ID |
| --- | --- | --- |
| Scotiabank Theatre Chinook | Calgary, AB | 3401 |
| Scotiabank Theatre Edmonton | Edmonton, AB | 3403 |
| Cineplex Cinemas Langley | Langley, BC | 1405 |
| SilverCity Riverport | Richmond, BC | 1409 |
| Scotiabank Theatre Halifax | Halifax, NS | 5130 |
| Cineplex Mississauga Square One | Mississauga, ON | 7420 |
| Cineplex Cinemas Vaughan | Vaughan, ON | 7408 |
| Cinéma Banque Scotia Montréal | Montréal, QC | 9406 |

Three further houses run standard (non-IMAX) 70mm: International Village Vancouver,
Queensway and Varsity in Toronto. Canada's ninth IMAX 70mm screen, Toronto's Cinesphere,
is not a Cineplex venue and so isn't reachable through this API.

### Why Canada only

There are 41 true IMAX 70mm screens worldwide: 25 in the US, 9 in Canada, 3 in the UK and
one each in Australia, Belgium, Czechia and France. Only the Canadian ones are Cineplex.
The rest sit behind AMC, Regal, Cinemark, ODEON and various science museums — a different
ticketing backend each, and AMC already answers a plain request with `403`. Supporting
them is not an extension of this work but a separate reverse-engineering project per
chain, so this app is deliberately scoped to the one API that is fully solved.

## Seat filtering

The auditorium at Vaughan is 10 lettered rows, **A at the front** through **J at the
back**. Seat IDs are `1_<physicalRow>_<physicalColumn>` where the physical row number runs
*backwards* from the label (row A is physical 12). The app keys off the row `label`, never
the ID, so that inversion is irrelevant.

**Wheelchair and companion seats are always excluded.** At Vaughan the eight accessibility
seats in row E (`EW1–EW4`, `EC1/EC4/EC21/EC24`) report `Available` in almost every
screening — including sold-out ones — because they're held back for accessibility booking.
Without a `type == "Standard"` filter the app would fire an alert on every single poll.
This is the single most important filter in the whole thing.

## The Cineplex API

The same undocumented API cineplex.com's own front-end calls. No login; only public
catalogue, showtime and seat data is read. The subscription key is the public one shipped
in their JavaScript bundle.

```
Header: Ocp-Apim-Subscription-Key: dcdac5601d864addbc2675a2e96cb1f8
Base:   https://apis.cineplex.com/prod/cpx/theatrical/api
Tickets:https://apis.cineplex.com/prod/ticketing/api
```

| Purpose | Endpoint |
| --- | --- |
| All theatres | `GET /v1/theatres?language=en&skip=0&take=1000` |
| **Films actually bookable** | `GET /v1/movies/bookable?language=en[&locationId={t}]` |
| Full catalogue (coming soon) | `GET /v1/movies?language=en&take=500&showtimeStatus=0` |
| **Bookable dates for a film at a cinema** | `GET /v1/dates/bookable?language=en&locationId={t}&filmId={f}` |
| Showtimes | `GET /v1/showtimes?language=en&locationId={t}&date=YYYY-MM-DD` |
| Seat layout | `GET {tickets}/v1/theatre/{t}/showtime/{s}/seat-layout` |
| Seat availability | `GET {tickets}/v1/theatre/{t}/showtime/{s}/seat-availability` |
| Booking deeplink | `…/deeplink?s={session}&a=0000000001&l={t}&m={slug}&ss=False` |

### Which films to offer

`/v1/movies` returns Cineplex's entire catalogue — ~250 titles, most with no showtimes
anywhere, which made the picker look full of films that aren't playing. `/v1/movies/bookable`
is the honest list: 86 nationally, and narrower per cinema (51 at Vaughan, 64 as a union
across the eight IMAX 70mm venues). The picker unions the per-cinema lists for whatever
you've selected.

The `hasShowtimes` flag on the raw catalogue is **not** a usable substitute: *Dune: Part 3*
reports `hasShowtimes: false` while genuinely being bookable at two cinemas.

Films that aren't on sale anywhere yet sit behind a **+ Coming soon** toggle, so a watch can
still be armed months ahead for the on-sale alert.

### Quirks

All handled in `CineplexApi.kt`:

- `/v1/showtimes` returns a bare object *or* a single-element array.
- `experienceTypes` is sometimes a string, sometimes an array of strings.
- Responses are **intermittently gzipped even when not requested**, so the client sniffs
  the `1f 8b` magic bytes rather than trusting `Content-Encoding`.
- There is no film-wide showtimes query; `locationId` is mandatory, so each cinema costs
  a request.

## Polling strategy

`dates/bookable` is the pivot the whole loop is built on — one request per (cinema, film)
says exactly which dates exist, instead of blindly sweeping a year of calendar.

1. One `bookableDates` call per cinema. Empty means not on sale yet: cheap, and it doubles
   as the trigger for the on-sale alert.
2. Showtimes fetched only for those dates, and only inside the configured window.
3. Seat maps fetched only when a session's `seatsRemaining` actually moved, with a full
   sweep every 4th cycle so a same-count swap (one person frees E12 as another takes A3)
   can't hide indefinitely.
4. (cinema, date) pairs are budgeted at 60 per cycle and rotated, so a wide-open window
   degrades into slower coverage rather than hundreds of requests every 15 minutes.

Seat layouts are cached on disk permanently — they never change for a given session and
are by far the largest payload.

### Caching the matching-seat result

Skipping the seat fetch when `seatsRemaining` hasn't moved requires somewhere to keep the
previous answer. Two rules matter:

- **"Never looked" and "looked, nothing matched" must stay distinct.** Collapsing them
  makes the list confidently report *"90 free, none matching"* for a screening whose seat
  map is full of matches — a session that rotated out of the per-cycle budget simply has
  nothing cached, which is not the same as having no matches.
- **The seat count is persisted only after a successful fetch.** Storing it first meant a
  transient network error left the count updated but the seats unknown, so every later
  quick pass saw "unchanged" and skipped the retry, poisoning that screening until the
  next full sweep.

A change to the row filter invalidates the whole cache, since "matching" then means
something different.

Because only `PAIR_BUDGET` pairs are visited per cycle, results from previous cycles are
carried forward into the screenings list (dropping anything outside the window or at a
deselected cinema). Otherwise the list would shed most of its entries every cycle and
repopulate them several cycles later.

Alerts de-duplicate per session: you're told about a seat once, and again only if it goes
away and comes back.

## Running it

A **foreground service** with a persistent silent notification, not WorkManager — periodic
work gets batched and deferred under Doze, the wrong tradeoff when a seat may exist for
only minutes. Targets SDK 34 deliberately: apps targeting Android 15+ get a 6-hour/day cap
on `dataSync` foreground services.

### Install

```
adb install -r OdysseyWatch.apk
```

Or copy the APK to the phone and open it (allow "install unknown apps").

### First run

1. Allow notifications.
2. **Disable battery optimisation** — required, or Android throttles the polling.
3. **Choose film**, then **Choose cinemas** (the *IMAX 70mm* button selects all 8).
4. Set format, row, time range and how far ahead to look.
5. **Check once now** to confirm, then **Start watching**.

Don't dismiss the ongoing notification — that stops the service. On Samsung, Xiaomi,
OnePlus, Oppo and Huawei you must also exempt the app from the manufacturer's own
background killer, which is separate from Android's.

## Building

Requires JDK 17+ and an Android SDK with platform 34; `local.properties` must point at it.

```
gradle assembleRelease
```

Output `app/build/outputs/apk/release/app-release.apk`, signed with the debug key for
direct sideloading.
