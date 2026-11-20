# Yair's Kav extensions

Extensions for [Kav](https://kavapp.link), the open-source transit app. Made and published by me, not by Kav.

[![Add to Kav](https://kavapp.link/img/add-to-kav.svg)](https://kavapp.link/add-source?github=yair-654/yairs-kav-extensions)

To add this source by hand: in Kav open Settings, Extensions, Sources, the plus, and enter `yair-654/yairs-kav-extensions`. Its
extensions come in off: switch on the ones you want.

## What is here

| Extension | What it does |
|---|---|
| Route Weather | On a trip's page in Kav, a card showing the forecast from the Israel Meteorological Service (ims.gov.il) for every city the route passes through, at the hour you pass it. A screen of its own with a place picker for a one-off forecast. |

More extensions will be added to this source over time; each one lands in its own folder under `extensions/` and is published in the same bundle. Kav checks this source's latest release about every six hours; when the bundle's SHA-256 changes it is replaced automatically, each extension keeping its switch and settings. A rider that installed from a bundle file must remove the old source and re-add the new build; the repo source is the one that updates.

Problems with these extensions go to this repository's issues, not to Kav.

## For developers

### Layout of the source

- Each extension is a folder under `extensions/`, named exactly as its manifest's `name`. That name is the extension's identity with this source and never changes.
- Inside an extension's folder: `manifest.json`, the Kotlin or Java under `src/` (against Kav's small `kav.kavapp.api` API), and optionally its own files: an icon, pictures, data.
- `src/`, `build/` and dot-files never go into the bundle.

### Adding a new extension

1. Copy an existing extension's folder or the kit's `starter-kotlin` and rename it; set a new unique `name` in `manifest.json`.
2. Set the rest of its manifest: `displayName`, `version` (`"1.0.0"`), `release: 1`, `minKav: 45` (Kav 5), `api: 2`, `description`, `native`, `contributions`, `pages`. Anything registered in code must also be declared in the manifest — what the manifest doesn't declare never shows.
3. Build and check it the way Kav does:

```
kavx check extensions/<new-extension>   # or, before the kit is published, the by-hand steps in the guide
```

4. Add a row for it to the "What is here" table above, then commit and push. It ships with the next release.

### Updating an extension

Change its code, bump `version` and raise the integer `release` in **every changed** extension's manifest, and commit. Kav does not compare `version` or `release` — a changed bundle is the update — but riders see the version, so keep them moving.

### Releasing

One build of the whole source, all extensions together in one bundle, carried by the repository's latest GitHub Release as exactly one `.kavb` asset:

```
git tag v3
git push origin v3
```

`.github/workflows/release.yml` then builds the bundle and attaches it to a release for the tag. Kav reads only the latest release's bundle, which must carry exactly one `.kavb` asset (drafts and pre-releases are never seen; a second `.kavb` breaks the source). Version the tags v1, v2, v3…, always forward, never re-pointing an old tag at a new build, so old sources stay consistent.

Until the Kav extension kit (github.com/ImNoammm/kav-extension-kit) is published, the workflow fails; build the bundle by hand following "Kotlin and Java" and "The bundle format" in the guide and attach the `.kavb` to the release:

```
bash ~/Downloads/Kav/<extension-folder>/build.sh      # writes <name>.kavb
git tag v3 && git push origin v3                     # create the release
gh release upload v3 <name>.kavb                     # attach the bundle
```

### Shared-fate

The bundle is checked as a whole: a bad manifest or build in one extension refuses the whole bundle, and none of the extensions install. Run `kavx check` on each extension before tagging.

### Manual test install

```
adb push <name>.kavb /sdcard/Download/
```

Then in Kav: Settings › Extensions › Sources › Add a bundle file, pick it, switch the extension on. A file source never updates: remove the old one before adding a new build.

## License

GPL-3.0. See [LICENSE](LICENSE).
