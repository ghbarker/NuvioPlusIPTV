# IPTV performance review evidence — 2026-11-24

This bundle supports the focused IPTV performance PRs. It contains reviewed screenshots, sanitized measurement summaries and source identities. It contains no application/user-data backup, account or provider configuration, raw HTTP/logcat, UI hierarchy XML, APK, or private workstation/device path.

The recorded combined source was based on `1823b9c`, with the existing separate Sports C187 work included. The twelve extracted PRs are independently based on `b02e887` (Nuvio 1.2.0-beta.4). Their fresh isolated receipts are appended under `validation/` as serialized checks complete. Current dev `b14285b` independently repaired the base's missing import; extracted checks use exact merges with that current dev. The earlier combined build's results must not be relabeled as isolated Beta4 branch results.

## Recorded validation

The final combined build passed **586 JVM/native-SQLite tests across 96 classes**, plus APK verification. A separate Windows Go 1.27.3 suite passed **39 top-level local protocol/ownership tests**. The synthetic TV emulator flows covered Guide coverage, Classic/Grid focus return, tabs/query changes, playback and metadata revisit. See [validation summary](validation-summary.json) and [61-file source identities](source-hashes.json).

These checks do not establish physical-device performance, authenticated provider behavior, or whole-app heap/PSS/FPS improvements.

## Component measurements

The actual cached-file repository fixture used 25,000 live entries plus 100,000 skipped VOD entries. Changed-reload median wall times were 2914.5 → 1114.2 ms (missing cache),3068.8 → 1298.0 ms (same-count change) and2385.7 → 900.2 ms (one playlist changed). Unchanged reuse was 196.5 → 191.7 ms. Full ordered channel/source/binary-cache results matched. The candidate includes the separate M3U and no-EPG-index improvements; these are combined-component figures.

For 1,000 guide identities, the legacy 21-hour read constructed 42,000 models in 59.34 ms. The compact query returned 2,000 checked now/next models in 28.88 ms. Full 65-hour reads still returned 130,000 models. This compares intentionally different requested working sets; **95.2% fewer constructed models is not a measured heap reduction**.

The token-only Sports overlay preserved every complete ordered model and canonical fixture identity. Native-path medians changed around 1–2% versus the earlier baseline, so this bundle does not claim a material additional native speedup. The pre-existing C187 acceleration is separate. The retained helper removes per-token strings without retaining per-programme text.

See [measurement summaries](measurements.json) for exact medians, CPU scope, fixture sizes and limitations. All figures are from a synthetic API36 x86_64 emulator component harness; one excluded warmup and three steady operations per case. Setup, parity serialization and reporting were outside measured operations.

## Screenshot interpretation

The [evidence index](evidence-index.json) contains only public filenames, SHA256 identities, capture context, visible findings and limits. The source PNGs were checked byte-for-byte, visually reviewed, and copied unchanged without textual/EXIF PNG metadata.

The resolver pair shows the same synthetic title: [provider fallback before the correction](screenshots/resolver-before-provider1001.png), then [full addon details after a pressure/revisit flow](screenshots/resolver-after-full1001-revisit.png), followed by [Back to the selected card](screenshots/resolver-after-revisit-back.png). “Before” means before the resolver fix in a combined build that already had Home changes. It is not a before-Home baseline. Private circuit state was not logged; separate public-entry red/green regressions establish the resolver defect.

Home evidence shows [Classic returning to row 09/card 35](screenshots/home-classic-after-card35-back.png) and [Grid returning to card 17](screenshots/home-grid-after-card17-back.png). These are **after-only** captures. A literal Beta4/Home-only matched comparison is now recorded below, with stable query revision during the accepted route. Direct row → details → Back and SeeAll → details → Back are different routes; the latter is not claimed here. Existing Grid label clipping is outside the fix.

Guide evidence shows [the future Sentinel in global Search](screenshots/guide-search-five-hour-sentinel.png), [the populated near-now preview](screenshots/guide-near-now-preview.png), and [the same Sentinel in the full schedule](screenshots/guide-full-schedule-five-hour-sentinel.png). The limited preview's absence of the Sentinel is expected. Its14-row cap comes from source/operational evidence; the screenshot does not show every row fitting on screen. Search remains a next-six-hours search; no nine-hour Search claim is made. Public Cinemeta posters in the search screenshot are incidental.

## Literal Beta4 baseline-before capture

The baseline APK was built from `58104b7ad1026` (b02 plus the build-only NuvioDialog import). Upstream later added that same import in `b14285b29f35`; only import ordering differs. The baseline APK and its completed verification are identified in [the extracted checkpoint](validation/beta4-extracted-checkpoints-v3.json).

The literal route selected [Row09/Card35](screenshots/home-before-card35-selected.png), opened [full addon9035 details](screenshots/home-before-card35-full-details.png), then one Back [focused Movies and rewound the row](screenshots/home-before-back-loses-focus.png). The immediate and settled return frames were identical. See [the sanitized baseline result](home-baseline-before.json).

This supplies actual baseline-before evidence. The new matched comparison below uses the Home-only build; the earlier combined after frames retain their original labels. These are functional navigation observations, not memory/performance measurements.

## Matched Home-only before/after comparison

The repeated baseline kept repository revision1 throughout the direct Card35 → full addon9035 → Back route, but reduced the published row prefix from35 to30 and [returned to Movies/Card01](screenshots/home-before-back-loses-focus.png). Its settled return PNG is byte-identical to the original before capture above.

The Home-only current-dev merge `c0307222c433` kept repository/spec revision1 and focus epoch1 throughout the same route. It opened [the same full9035 details](screenshots/home-only-card35-full-details.png), then [restored Row09/Card35](screenshots/home-only-back-keeps-card35.png). The prefix remained35; disposal saved zero-based card index34 and Back restored it. The selected starting frame and settled return frame have identical PNG bytes. The sidebar remained reachable.

See [the sanitized paired receipt](home-matched-before-after.json) for exact source/APK identities and the recorded field observations. The unchanged Home-only APK's separate [27-test/6-class and APK/four-ABI receipt](validation/06-home-working-set-complete-extracted-verification-b482f84d63d29a0a.json) is build evidence, not a timing result.

An earlier candidate return went to Row01 and was held. Its first transition was not field-sampled, so its exact cause is not established; later state plus source supports startup query invalidation as an explanation. It is excluded from this unchanged-query comparison, and no source patch was made between the observations. Read-only debugger sampling briefly suspended execution; no memory, timing, FPS, physical-device, or provider-account improvement is claimed.
