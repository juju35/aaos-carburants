# Google Play

Everything the Play Console asks for, ready to paste or upload. The app is a templated POI app, so it is
distributed on **Android Automotive OS only** tracks (`automotive:qa` for internal testing, `automotive:production`).

| File | Console field |
| --- | --- |
| `listing/fr-FR/title.txt` | Store listing › App name (30 max) |
| `listing/fr-FR/short_description.txt` | Short description (80 max) |
| `listing/fr-FR/full_description.txt` | Full description (4,000 max) — keeps the source and "not a government app" statements the policy asks for |
| `whatsnew/fr-FR.txt` | Release notes of the next release (500 max), sent by the Release workflow |
| `graphics/icon-512.png` | App icon (512 × 512) |
| `graphics/feature-graphic.png` | Feature graphic (1024 × 500) |
| `graphics/automotive/*.png` | Advanced settings › Form factors › Android Automotive OS screenshots (2+ landscape 1024 × 768, 2+ portrait 800 × 1280) |
| `../PRIVACY.md` | App content › Privacy policy: `https://github.com/juju35/aaos-carburants/blob/main/PRIVACY.md` |

Automotive screenshots can't go through the Publishing API (no automotive `AppImageType`): upload them in the Console.

## App content answers

| Section | Answer |
| --- | --- |
| Privacy policy | URL above |
| Ads | No ads |
| App access | All functionality available without special access. Note for the reviewer: *"Prices cover France only. Outside France, set a mock location in France, e.g. Rennes 48.1105, -1.6795 (the app uses the platform location, mock locations work)."* |
| Content rating | Category "All other app types"; no to every question → PEGI 3 / everyone |
| Target audience | 18 and over (drivers); not designed for children |
| News app | No |
| Government app | No (the listing says it isn't affiliated with any government body and names the source) |
| Financial features, health | None |
| Data safety | See below |

### Data safety

- Collects or shares user data: **Yes**
- Data type: **Location › Approximate location** and **Precise location**
  - Collected: **Yes** (sent off the device in the search request); Shared: **No** (sent to the public prices API only to
    answer the search the user makes by opening the app)
  - Processed ephemerally: **Yes** — Required: **Yes** — Purpose: **App functionality**
- Every other data type: not collected
- Encrypted in transit: **Yes**
- Account creation: none — Data deletion request: not applicable, nothing is kept off the device

These are the developer's declarations: check them against `PRIVACY.md` before submitting.

## Release flow

1. Signing: create an upload key once and add it to the repository secrets (see the main README).
2. First upload by hand (the API can't create the first release): Console › Test and release › Advanced settings ›
   Form factors › **Add Android Automotive OS**, then upload `carburants-x.y.z.aab` from the GitHub release to the
   Android Automotive OS internal testing track.
3. Then, with the `PLAY_SERVICE_ACCOUNT_JSON` secret, each release is uploaded by the Release workflow to the
   track in the `PLAY_TRACK` variable (default `automotive:qa`), as a draft while the app has never been published
   (`PLAY_RELEASE_STATUS` variable, then `completed`).
4. Promote in the Console. Internal testing has no form-factor review; closed testing a non-blocking one;
   open testing and production a blocking one against the car app quality guidelines.

A personal developer account created after 13 November 2023 also needs a closed test with at least 12 testers opted
in for 14 days in a row before it can apply for production access.
