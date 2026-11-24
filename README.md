# Yair's Kav Extensions

Extensions for [Kav](https://kavapp.link), the transit app for Israel.
Written and published independently — not affiliated with, endorsed by, or supported by Kav.

[![Add to Kav](https://kavapp.link/img/add-to-kav.svg)](https://kavapp.link/add-source?github=yair-654/yairs-kav-extensions)

## Add to Kav

Tap the button above, or add the source by hand in Kav:
**Settings → Extensions → Sources → +** and enter `https://github.com/yair-654/yairs-kav-extensions`.

Extensions arrive switched off. Turn on the ones you want; each one can be turned
off or removed at any time from the same screen.

Requires Kav 5.1 or newer.

## Extensions

| Extension | What it does |
|---|---|
| **Weather** | The weather on your trip, from the Israel Meteorological Service (ims.gov.il): a one-line summary and any IMS warning under each search result, the forecast at each place you get on, off or walk on the trip's page and map, and the weather now at the top of a stop's departures. You choose which conditions matter, in what order, and your units. Only the nearest forecast place is sent to IMS, never your location or route. |
| **Heroes** | For IDF soldiers: Kav's Pay buttons can open Zuzu instead, your choger or Tzav 8 opens full screen at full brightness for train security, and optional stay-awake reminders sound every few minutes of a trip. Everything is kept on your phone — no location, no data sent anywhere. |
| **Almost Shabbat** | For trips arriving on the afternoon before Shabbat or a holiday: a line under each search result saying how close you'll arrive to candle lighting (or sunset, your choice), a card on the trip's page with candle lighting, sunset and the parsha, and a notification if a delay brings your arrival closer. Times are for the locality nearest your destination, and only that locality is sent to Hebcal — never your exact destination. |
| **Rush** | In a hurry? Rush tries to find buses you can still catch if you jog or run to the stop. A Rush mode button in the results of a "leave now" search puts those routes first, with jogs in amber and runs in red and how much time you'd have to spare, and a note tells you when running would save you at least 10 minutes (you choose how much). During a trip it can also check before each change whether running gets you on an earlier connection, and offer to switch your trip. You set your own jogging and running speeds; everything is kept on your phone. |

## Credits

- None of this would've been possible without [Noam](https://github.com/ImNoammm). Thank you Noam for creating [Kav](https://github.com/ImNoammm/Kav), a superb transit app, and for opening it up to extensions.
- [Israel Meteorological Service (IMS)](https://ims.gov.il) — thanks to the IMS for its free public API, which powers the Weather extension's forecasts.
- [Hebcal.com](https://www.hebcal.com) — thank you to Hebcal for its free API, which gives Almost Shabbat its candle-lighting and sunset times and the parsha.
- [Central Bureau of Statistics](https://www.cbs.gov.il) — its list of localities in Israel lets Almost Shabbat find the locality nearest your destination, on your phone.

## Issues and feedback

Found a bug or have a suggestion? Please open an issue:
[github.com/yair-654/yairs-kav-extensions/issues](https://github.com/yair-654/yairs-kav-extensions/issues)

Problems with these extensions belong here — not to Kav or its developers.

## Disclaimer

These extensions are provided **as is**, without warranty of any kind, express or
implied, including but not limited to the warranties of merchantability, fitness for
a particular purpose, and non-infringement.

Forecasts, candle-lighting and sunset times, and other data shown by the extensions come
from third-party sources (the Israel Meteorological Service, Hebcal.com and others) and
may be incomplete, delayed, or inaccurate. **Do not rely on them for safety-critical decisions.**

Weather shows the forecast for the IMS place nearest each stop, which can be a few kilometres
away, and IMS warnings only for land areas. For official warnings, check ims.gov.il.

Almost Shabbat's times are calculated for the locality nearest your destination and are
approximate: customs differ between communities, and arrival times depend on live data
that can change. If in doubt, ask your local rabbi.

Rush's jogs and runs are estimates, and catching a bus with one is a gamble: buses can
come early, traffic lights can hold you up, and you may be slower than the speeds you set.
**Always run safely**: on the pavement, crossing only on green, with your phone in your
pocket, and watching out for people and obstacles in your way. No bus is worth getting hurt for.

Each extension runs its own Android code inside Kav with the permissions Kav has.
Installing and enabling these extensions is entirely at your own risk. The developer
assumes no responsibility or liability whatsoever — direct or indirect, actual or
consequential — for any damages, losses, or inconvenience arising from the use of,
or inability to use, these extensions, including any decisions made based on the
information they display.

## License

GPL-3.0. See [LICENSE](LICENSE).
