# Multimedia / Remote Files Design QA

## Current iteration gate

- Current signed HAP SHA-256: `02db003137a6989afcd446142600250ab8d05282a676c5c48d7e58b0d88dc94c`.
- Emulator acceptance evidence: `/Users/pankang/mycode/MyTools/app/build/acceptance/device-acceptance-20260815T091018Z.json`.
- The complete `app/scripts/test-*.sh` suite passes.
- ArkTS type checking and signed HAP packaging pass without warnings.
- Emulator overwrite installation, cold start, authentication restoration, gallery browsing, source selection, `big_media` loading, image switching, video loading, video autoplay, control visibility, and vertical switching pass.
- The current signed HAP is installed on the HarmonyOS emulator. No physical device is currently enumerated, so this iteration does not claim physical-device acceptance.

## Comparison target

- Source visual truth: `/Users/pankang/.codex/generated_images/019ff016-e8dc-7791-9a6e-091a4b309673/exec-46baab5e-b721-45bc-a98b-460eff9aca79.png`.
- Final emulator implementation: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/mytools-media-polish-home-ready.jpeg`.
- Current full-view comparison: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/media-polish-qa/full-comparison.png`.
- Current focused header comparison: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/media-polish-qa/header-grid-comparison.png`.
- Source selector open state: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/mytools-source-selector-big.jpeg`.
- Selected `big_media` gallery state: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/mytools-big-media-page.jpeg`.
- Full-view source and implementation comparison: `/Users/pankang/mycode/MyTools/app/build/acceptance/design-qa-immersive/gallery-source-final.jpg`.
- Focused image-viewer before and after comparison: `/Users/pankang/mycode/MyTools/app/build/acceptance/design-qa-immersive/viewer-before-after.jpg`.
- Focused video-loading before and after comparison: `/Users/pankang/mycode/MyTools/app/build/acceptance/design-qa-immersive/video-loading-before-after.jpg`.
- Source pixels: `852 x 1846`.
- Implementation pixels: `1320 x 2856`, captured from the DevEco HarmonyOS emulator with the system status bar and gesture area retained.
- Normalization: the source was aspect-fit to `1320 x 2856`; all implementation comparisons use the same `1320 x 2856` device capture and are joined side by side without stretching.
- State: authenticated MyTools account, `远程文件` source, light gallery theme, live remote media. The current focused comparison covers the closed source trigger; the selected source visual does not define an open picker, so the open wheel and `big_media` gallery are recorded as supplemental interaction evidence rather than a false visual match.

## Findings

- No actionable P0, P1, or P2 issue remains in the verified emulator state.
- The historical `Multimedia` label is now presented as `远程文件`. The backend `LARGE_MEDIA` directory is discoverable beside it as `大文件`, with a selected-state explanation `MyTools big_media · 大图和视频`.
- `远程文件` and `大文件` deliberately remain separate selectable libraries. This preserves each backend directory's total, pagination, directory options, tags, mutation ownership, and thumbnail cache while reusing the same gallery and viewer design.
- [P3] The generated source uses a compact three-column grid, while the implementation uses the explicitly requested larger two-column thumbnails. This is an intentional product difference and improves recognition and tapping on the target device.
- [P3] The generated source has a shorter toolbar, while the implementation retains the requested source, media type, directory, and tag selectors. Their visual tokens, spacing, and hierarchy remain consistent with the selected design language.
- The immersive viewer now removes the filename from the primary top hierarchy, uses a compact sequence counter, moves image metadata into a small bottom surface, and automatically hides all chrome after 2.6 seconds.
- Video preparation now keeps the real cached thumbnail visible and adds a compact loading state instead of presenting a blank black surface.
- Image and video use one visual-only sequence, so vertical browsing no longer unexpectedly enters an audio player.
- The first remote page now renders 24 items and thumbnail downloads publish in four-item batches. Existing valid cache entries survive refresh, eliminating most blank-frame flashes and reducing ArkUI state churn during fast scrolling.
- Vertical immersive navigation now follows the finger with an adjacent-media preview, progressive scale and opacity, and a short spring-back transition instead of jumping only after release.
- Hash-only remote filenames are replaced by readable `远程图片` or `远程视频` labels; native favorite symbols replace text glyphs, and the bottom metadata surface is narrower and limited to the three most useful tags.

## Required fidelity surfaces

- Typography: HarmonyOS Sans, title weight, date hierarchy, selector labels, tag chips, sequence counter, and viewer controls are optically consistent and readable. Long remote filenames truncate safely.
- Spacing and layout: the gallery preserves the selected title/search/filter hierarchy, large two-column rhythm, rounded remote thumbnails, fixed navigation, and 48 vp action targets. Viewer chrome is compact and no longer competes with content.
- Colors and tokens: the gallery keeps the selected light neutral and blue token system. The viewer uses black system bars, black canvas, and restrained translucent surfaces for a continuous immersive frame.
- Image quality and assets: all gallery and viewer imagery is live remote content. Images retain aspect ratio; video preparation reuses the real remote thumbnail; no placeholder art, handcrafted SVG, or synthetic media was introduced.
- Copy and content: the source names use user-facing `远程文件` and `大文件`; the picker description explains the otherwise technical `big_media` scope. Filter labels, loading copy, image actions, and errors remain concise and standalone. The removed swipe hint does not return.
- Icons: navigation and media actions use native HarmonyOS system symbols or established shared controls.
- States and interactions: loading, prepared, playing, hidden chrome, revealed chrome, image zoom, vertical switching, retry, empty, filtering, and long-press action states remain reachable.
- Accessibility: interactive actions remain native focusable controls with semantic labels and practical touch targets. The status bar remains present and changes to a dark immersive treatment only while the visual viewer is open.

## Final-build interaction evidence

- Remote-file main state: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/mytools-media-source.jpeg`.
- Source wheel with `大文件` selected: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/mytools-source-selector-big.jpeg`.
- Live `big_media` result: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/mytools-big-media-page.jpeg`, showing the server's large-media video items after selection.
- Main gallery: `/Users/pankang/mycode/MyTools/app/build/acceptance/emulator-current/immersive-audit-final/gallery-final.jpeg`.
- Image chrome: `/Users/pankang/mycode/MyTools/app/build/acceptance/emulator-current/immersive-audit-final/image-viewer-final-chrome.jpeg`.
- Image auto-hidden chrome: `/Users/pankang/mycode/MyTools/app/build/acceptance/emulator-current/immersive-audit-final/image-viewer-final-immersive.jpeg`.
- Image tap-to-reveal: `/Users/pankang/mycode/MyTools/app/build/acceptance/emulator-current/immersive-audit-final/image-viewer-final-tap.jpeg`.
- Image vertical switching: `/Users/pankang/mycode/MyTools/app/build/acceptance/emulator-current/immersive-audit-final/image-viewer-final-next.jpeg` shows the next remote image and `2 / 40` sequence state one second after the swipe.
- Video thumbnail-backed preparation: `/Users/pankang/mycode/MyTools/app/build/acceptance/emulator-current/immersive-audit-final/video-viewer-final-loading.jpeg`.
- Video immersive playback: `/Users/pankang/mycode/MyTools/app/build/acceptance/emulator-current/immersive-audit-final/video-viewer-final-playing.jpeg`.
- Video tap-to-reveal: `/Users/pankang/mycode/MyTools/app/build/acceptance/emulator-current/immersive-audit-final/video-viewer-final-tap.jpeg`.
- Video vertical switching: `/Users/pankang/mycode/MyTools/app/build/acceptance/emulator-current/immersive-audit-final/video-viewer-final-next.jpeg` shows the preloaded next preview and `2 / 40`; `/Users/pankang/mycode/MyTools/app/build/acceptance/emulator-current/immersive-audit-final/video-viewer-final-next-playing.jpeg` verifies automatic playback and hidden controls.
- Permanent delete, rename, or move operations were not executed against user media.
- Current image viewer chrome and readable metadata: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/mytools-media-polish-viewer-chrome2.jpeg`.
- Current vertical image transition result: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/mytools-media-polish-viewer-next.jpeg`, showing the next remote image and `2 / 24` state.
- Current `big_media` video preparation and playback: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/mytools-media-polish-video-loading.jpeg` and `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/mytools-media-polish-video-late.jpeg`.
- Current next-video autoplay: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/mytools-media-polish-video-next-late.jpeg`.
- Current return-to-gallery restoration: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/mytools-media-polish-return.jpeg` retains the remote thumbnails after leaving full screen.

## Regression coverage

- The complete shell policy suite passes, including media grouping, thumbnail cancellation and restoration, preload radius, playback recovery, image gestures, subtitles, media response normalization, session restoration, and UI design policies.
- `app/scripts/test-ui-design-policy.sh` now enforces thumbnail-backed video preparation, immersive video controls, auto-hidden chrome, the visual-only media sequence, and 48 vp image transport actions.
- Signed `assembleHap --no-daemon` passes with zero ArkTS warning lines.
- The final HAP passes emulator signature, installation, cold-start, and process-observation checks.

## Implementation checklist

- [x] Preserve the selected gallery visual language and requested large two-column thumbnails.
- [x] Keep source selection beside search and media type, directory, and tag selectors in the filter row.
- [x] Present the MyTools `MULTIMEDIA` directory as `远程文件` instead of the historical English name.
- [x] Expose `LARGE_MEDIA` / `big_media` as the adjacent `大文件` source without merging incompatible pagination streams.
- [x] Explain each MyTools local source in the source wheel and verify live `big_media` results.
- [x] Retain live multi-tag overlays and real remote thumbnails.
- [x] Use the cached video thumbnail as the preparation frame.
- [x] Auto-hide viewer chrome and allow one-tap restoration for both images and videos.
- [x] Keep the system status bar visible while matching it to the black immersive canvas.
- [x] Reduce image controls to previous, reset scale, and next; retain pinch and double-tap zoom.
- [x] Limit vertical immersive navigation to the mixed image/video sequence.
- [x] Retain current plus/minus five-item preload behavior and partial video warming.
- [x] Verify image switching, video autoplay, next-video autoplay, and thumbnail restoration.
- [x] Batch thumbnail loading and preserve valid cached entries across refreshes.
- [x] Give vertical immersive switching live drag feedback with adjacent-media preview.
- [x] Replace hash-only metadata and text favorite glyphs with readable labels and native symbols.
- [x] Pass automated tests, warning-free signed build, emulator install, interaction checks, and screenshot comparison.

## Comparison history

- Earlier P1 functionality finding: the app normalizer accepted only `MULTIMEDIA`, so the backend's `LARGE_MEDIA` / `big_media` directory was silently absent from the source selector.
- Fix: accept and order both local media directory types, attach the directory type to the source model, map them to `远程文件` and `大文件`, and add selected-source explanatory copy in the existing wheel picker.
- Post-fix evidence: `mytools-source-selector-big.jpeg` shows both libraries and the `big_media` explanation; `mytools-big-media-page.jpeg` shows the live large-media results after selection.
- Earlier P1 interaction finding: the image surface's click handler was effectively swallowed by the pan recognizer, so tapping the image did not reliably hide or restore chrome.
- Fix: moved single and double taps into the exclusive image gesture group, added one-tap chrome control, and retained pinch and pan recognition.
- Post-fix evidence: `image-viewer-final-immersive.jpeg` and `image-viewer-final-tap.jpeg` verify hidden and restored chrome.
- Earlier P1 loading finding: video preparation displayed a black screen for several seconds with duplicate connection and transfer text.
- Fix: pass the cached remote thumbnail through `Video.previewUri`, use one compact preparation indicator, and remove the redundant transfer-status footer.
- Post-fix evidence: `video-loading-before-after.jpg`, `video-viewer-final-loading.jpeg`, and `video-viewer-final-playing.jpeg`.
- Earlier P2 immersion finding: the viewer retained a white status bar, full filename title, and a large five-button image control strip.
- Fix: darken system bars while the viewer is open, replace the title with a compact sequence counter, reduce image transport to three controls, and auto-hide chrome after 2.6 seconds.
- Post-fix evidence: `viewer-before-after.jpg` and `image-viewer-final-immersive.jpeg`.
- Earlier P2 flow finding: the shared sequence could move from an image or video into an audio item during vertical browsing.
- Fix: use a mixed image/video-only visual sequence for navigation and adjacent preload selection while retaining the separate audio player flow.
- Post-fix evidence: `image-viewer-final-next.jpeg`, `video-viewer-final-next.jpeg`, and `video-viewer-final-next-playing.jpeg`.
- Earlier P1 performance finding: thumbnail completion replaced the entire gallery state once per file and reset discarded still-valid cached entries, producing flicker and avoidable relayouts during refresh and fast scrolling.
- Fix: cap each remote page at 24 visual items, retain cache entries that still belong to the result set, download four thumbnails concurrently, and publish one combined state update per batch.
- Post-fix evidence: `mytools-media-polish-home-ready.jpeg` and `mytools-media-polish-return.jpeg`; the latter verifies that thumbnails remain visible after returning from full screen.
- Earlier P2 interaction finding: vertical navigation switched only after gesture completion, leaving no spatial feedback while dragging.
- Fix: translate and scale the active surface with the finger, reveal the cached adjacent item behind it, then complete or spring back with a short easing transition.
- Post-fix evidence: `mytools-media-polish-viewer-next.jpeg` and `mytools-media-polish-video-next-late.jpeg` verify the resulting image and autoplaying video states.
- Earlier P2 polish finding: opaque hash filenames, full-width sequence layout, and text star glyphs made the immersive controls feel technical and visually uneven.
- Fix: introduce media-type fallback names, a compact fixed-width sequence counter, native star symbols, and a restrained metadata panel with up to three tags.
- Post-fix evidence: `mytools-media-polish-viewer-chrome2.jpeg`.

final result: passed

# Media Date Directory Selection QA (2026-08-28)

## Scope

- Physical resource hierarchy: `/opt/extend/resource/yuyutian/media/202608/20260825`.
- User-facing hierarchy: `media/202608/20260825`.
- The verified date directory contains `176` gallery items.

## Result

- Directory and tag metadata is reused while media type, search text, and adult-content filtering remain unchanged.
- Selecting a date directory immediately clears cards from the previous scope and switches to the selected directory count; the directory response then confirms the authoritative total.
- Emulator UI dumps confirm that selection never presents the previous `34198` whole-library total and settles on `media/202608/20260825` with `176 个`.
- All `114` app shell regression tests, ArkTS type checking, signed HAP packaging, overwrite installation, and cold start pass.

final result: passed

# Goal Completion Audit Update (2026-08-28)

## Scope

- Rebuilt the current signed HAP from `master` after the media hierarchy deployment.
- Re-ran all 114 APP policy scripts and installed the resulting HAP on the connected HarmonyOS emulator.
- Cold-started authenticated Reading, Tools, DSH, Multimedia, Drive, and Profile surfaces.

## Runtime evidence

- Reading and shared navigation: `app/build/goal-audit/goal-audit-current.jpeg`.
- Multimedia day grouping with authoritative image count: `app/build/goal-audit/goal-audit-media-count.jpeg`.
- Immersive viewer entry after tapping the first live day thumbnail: `app/build/goal-audit/goal-audit-viewer.jpeg`.

## Finding and fix

- The unfiltered multimedia page grouped the current items under `20260825` but displayed the whole-library total
  `34198`, so the number beside the day was not the actual group quantity.
- The group header now uses the backend directory aggregate and only uses the catalog page total when that exact
  directory is selected. The rebuilt emulator page displays `20260825 · 176 个`, matching the ready image count for
  `/opt/extend/resource/yuyutian/media/202608/20260825`.
- The first live thumbnail still opens the immersive viewer after the count correction.

## Verification boundary

- ArkTS type checking and signed HAP packaging pass.
- The connected emulator installation and cold-start pass.
- The USB physical target `9CN0224A11031537` remains `Offline`; this update makes no new physical-device claim.

final result: passed

# Runtime Closure Audit (2026-08-27)

## Scope

- Sources: `app/docs/ui-redesign-mockup.html`, `app/docs/UI_REDESIGN_SPEC.md`, and the user's reader-footer regression screenshot.
- Runtime: the current signed HAP rebuilt from `master`, overwrite-installed and cold-started on the HarmonyOS emulator.
- Regression focus: shared controls, profile actions, source-search cache and return position, multimedia counts/filtering/viewer entry/directory hierarchy, and reader end-of-book completion.

## Runtime evidence

- Profile identity and settings rows: `app/build/design-qa-control-system/profile-current.jpeg`.
- Live gallery with actual total `34022`: `app/build/design-qa-control-system/media-runtime-current.jpeg`.
- Live `r18-否` filter with `296` matching items and retained thumbnails: `app/build/design-qa-control-system/media-tag-filter-current.jpeg`.
- Directory root with actual aggregate count: `app/build/design-qa-control-system/media-root-current.jpeg`.
- Reader end state: `app/build/design-qa-control-system/reader-final-progress.jpeg`.
- Reader footer before/after comparison: `app/build/design-qa-control-system/reader-final-progress-comparison.png`.
- Reader completion after an immediate process stop and cold restart: `app/build/design-qa-control-system/reader-progress-persist-after-restart.jpeg`.

## Findings and fixes

- The profile page renders avatar-left identity details, straight divider rows, no oval selection background, and working service, media, reading, and DSH destinations.
- A repeated `美母` source search returned from database cache in under two seconds with `缓存 1772 · 已查 0/0`; opening a result and returning restored the same scrolled result position.
- Multimedia gallery totals are sourced from the backend rather than the current render batch. Tag filtering returns the actual total, keeps more than one thumbnail, and image cards open the immersive viewer outside their explicit tag/action controls.
- The directory selector traverses `media -> yyyyMM -> yyyyMMdd` and displays live aggregate counts at every level. The physical storage contract remains `/opt/extend/resource/<username>/media/yyyyMM/yyyyMMdd/...`.
- Reader progress previously reached the final visible page while a height estimate rewrote the whole-book value to `53%`. Continuous reading now uses the native scroll boundary as authoritative; the final chapter/final page writes `100%`, while non-final positions remain capped at `99%`.
- Reader snapshots previously normalized percentage fields as ratios and silently clamped every value above `1%` to `1%` after restart. Snapshot restoration now preserves the product-wide `0-100` percentage contract, and first arrival at `100%` is flushed synchronously instead of waiting for the normal debounce window.

## Verification

- All 114 APP policy scripts pass.
- ArkTS type checking and signed HAP packaging pass.
- Focused reader/search/media backend tests pass under Java 21: 26 root-service tests and 15 Media Library tests.
- Emulator overwrite installation, cold start, authenticated session restoration, source search, source-result return restoration, profile actions, media filtering, media viewer entry, directory traversal, and reader `100% · 1/1页` completion pass.
- The reader was stopped immediately after reaching `100%`; a cold restart restored the same shelf item at `100%`, proving end-of-book persistence rather than only an in-memory footer update.
- The USB physical target is currently reported as `Offline`; no new physical-device claim is made by this audit.

final result: passed

# Shared Control System and Media Hierarchy QA (2026-08-27)

## Scope

- Visual source: `app/docs/ui-redesign-mockup.html`, `app/docs/UI_REDESIGN_SPEC.md`, and the approved reading-page screenshot.
- Control scope: primary, secondary, danger, search, filter, segment, input, and selected-state treatments across main pages, deep pages, sheets, and confirmation panels.
- Runtime scope: authenticated reading page and multimedia image page on the HarmonyOS emulator at `1320 x 2856`.
- Layout constraint: no main-page structure, navigation order, feature behavior, or content hierarchy was changed.

## Evidence

- Reading implementation: `app/build/design-qa-control-system/reading.jpeg`.
- Reading source/implementation comparison: `app/build/design-qa-control-system/reading-comparison.jpg`.
- Loaded multimedia gallery: `app/build/design-qa-control-system/media-gallery.jpeg`.
- Multimedia directory root: `app/build/design-qa-control-system/media-root.jpeg`.
- Multimedia month level: `app/build/design-qa-control-system/media-months.jpeg`.
- Multimedia day level: `app/build/design-qa-control-system/media-days.jpeg`.

## Findings and fixes

- Shared action controls now cover primary, secondary, and danger operations instead of repeating local fill, gradient, border, font, and enabled-state definitions.
- Shared search fields now cover the multimedia toolbar, multimedia selector sheets, remote media selector, drive search, book-source management, and shelf search.
- Deep flows now use the same control language as the main pages, including DSH permission/recovery, logout risk, cloud-data deletion, source credentials, source deletion, book details, shelf removal, reader prompts, annotations, login, audio, and video detail.
- Unselected DSH conversations no longer use a filled pill; the selected conversation uses the approved soft emerald state with an outline.
- The multimedia directory selector now represents the actual logical hierarchy as `media -> yyyyMM -> yyyyMMdd`, with live aggregate counts at each level.
- The multimedia root, month, and day states were opened in the emulator and verified as interactive, readable, non-overlapping, and correctly counted.
- The reading-page comparison shows that the approved header, search position, shelf list structure, and bottom navigation remain unchanged while the common control system is applied.

## Verification

- ArkTS type checking and signed HAP packaging pass.
- The signed HAP installs and cold-starts on the HarmonyOS emulator.
- All 114 APP policy scripts pass after updating the policies to assert shared semantic controls instead of obsolete raw button markup.
- The connected target list currently contains only the emulator, so this control batch is not claimed as newly verified on a physical device.

final result: passed

# Profile Pill Selection Removal QA (2026-08-27)

## Scope

- User annotation: the selected oval background remained on profile list rows.
- Implementation capture: `app/build/mytools-profile-no-pill.jpeg` at `1320 x 2856` from the HarmonyOS emulator.

## Result

- Replaced the shared profile action `Button` container with a clickable `Row`.
- Preserved the full-width 68vp target, action handler, accessibility label, divider, title, subtitle, and disclosure indicator.
- Eliminated the system button node that produced the persistent oval selection/hover background.

final result: passed

# Profile Action Audit QA (2026-08-27)

## Scope

- User request: remove invalid or redundant profile actions and repair retained unavailable actions.
- Implementation captures: `app/build/mytools-profile-audited.jpeg` and `app/build/mytools-cache-entry.jpeg` from the HarmonyOS emulator.

## Result

- Merged duplicate Service and Connection Status actions into one live connection-test row.
- Removed Reading History because it only duplicated the Reading Data destination.
- Removed Media Policy because it duplicated the Remote Data Sources destination.
- Consolidated Temporary Cache, Full-text Index, and Cache Management into one accurate Cache Management entry.
- Removed the failing manual Reading Sync row; automatic synchronization and cloud-data management remain available through their existing flows.
- Verified the consolidated Cache Management entry opens the live cache usage and cleanup page.

final result: passed

# Profile Login Devices Removal QA (2026-08-27)

## Scope

- User request: remove the Login Devices feature from the profile page.
- Implementation capture: `app/build/mytools-profile-no-devices.jpeg` at `1320 x 2856` from the HarmonyOS emulator.
- Constraint: retain the current-account logout flow.

## Result

- Removed the Login Devices title, refresh/status UI, device rows, and revoke-other-devices action.
- Removed the profile-side session list state, request lifecycle, refresh, and revoke handlers.
- Removed the obsolete Login Devices recommendation from the cloud-data deletion warning.
- The profile page now ends directly with the existing Logout action without residual spacing or device-session text.

final result: passed

# Profile Identity and Press-State QA (2026-08-27)

## Scope

- User annotation: replace the authenticated profile action card with identity information and remove oval press feedback from setting rows.
- Implementation capture: `app/build/mytools-profile-identity.jpeg` at `1320 x 2856` from the HarmonyOS emulator.
- Constraint: preserve existing setting actions and page structure.

## Result

- The authenticated profile card now places the existing avatar source on the left and nickname, username, email state, and role on the right.
- Removed the redundant profile-card connection-test button; connection testing remains available in the existing Service and Connection Status rows.
- Disabled the default button state effect and set zero row radius in the shared profile action row, preventing an oval gray press background.
- Long identity values use single-line ellipsis and the avatar retains the existing image/initial fallback behavior.

final result: passed

# Bottom Navigation Height QA (2026-08-27)

## Scope

- User request: reduce the visible height of the bottom function bar.
- Implementation capture: `app/build/mytools-nav-height.jpeg` at `1320 x 2856` from the HarmonyOS emulator.
- Constraint: preserve navigation behavior, labels, icon size, and accessible tab targets.

## Result

- Reduced the visible tab bar height from `68vp` to `60vp`.
- Reduced the selected icon background from `54×40vp` to `50×36vp` and tightened internal spacing.
- Kept each tab's `64vp` target height so touch accessibility remains unchanged.
- Updated the persistent DSH content height offset to match the new bar height.

final result: passed

# Bottom Navigation Icon Replacement QA (2026-08-27)

## Scope

- Visual source: the bottom navigation icons in `app/docs/ui-redesign-mockup.html` and section 4.5 of `app/docs/UI_REDESIGN_SPEC.md`.
- Implementation capture: `app/build/mytools-nav-icons.jpeg` at `1320 x 2856` from the HarmonyOS emulator.
- Constraint: replace the Reading, Tools, Media, and Drive symbols only; preserve navigation structure, selection treatment, labels, and behavior.

## Result

- Reading now uses the linear system book symbol.
- Tools now uses the linear wrench-and-screwdriver symbol.
- Media now uses the linear play-circle symbol.
- Drive now uses the linear external-drive symbol.
- DSH intentionally retains its existing AI-edit symbol.
- All five icons retain the documented `28vp` size and existing selected/unselected color treatment.

final result: passed

# Reading Search Left Spacing QA (2026-08-27)

## Scope

- User annotation: the blank space at the left side of the book-source search field was too large.
- Implementation capture: `app/build/mytools-search-spacing.jpeg` at `1320 x 2856` from the HarmonyOS emulator.
- Constraint: spacing adjustment only; search behavior, page structure, and overall layout remain unchanged.

## Result

- Reduced the search-mode button width from `84vp` to `68vp` and the field's left padding from `8vp` to `4vp`.
- Preserved the `42vp` control height and the full search field height, so the dropdown remains comfortably interactive.
- Emulator comparison confirms the mode label now sits closer to the field edge while the placeholder and search action retain clear separation.

final result: passed

# Full App Control Redesign QA (2026-08-27)

## Scope

- Source: `app/docs/ui-redesign-mockup.png`, `app/docs/ui-redesign-mockup.html`, and `app/docs/UI_REDESIGN_SPEC.md`.
- Runtime: signed HarmonyOS HAP installed on the `1320 x 2856` emulator.
- Evidence: `app/build/mytools-final-reading.jpeg`, `app/build/mytools-final-tools.jpeg`, `app/build/mytools-final-media.jpeg`, `app/build/mytools-final-drive.jpeg`, and `app/build/mytools-final-profile.jpeg`.
- Preserved: feature behavior, network/data flow, component order, page structure, and overall layout.

## Results

- The entire app now uses the emerald accent, pale green-gray background, glass/white surfaces, ink text, muted secondary text, soft green selection state, outlined pills, rounded fields, gradient primary actions, and semantic danger surfaces.
- All legacy blue tokens and historical hard-coded danger colors have been removed from the app UI source.
- Header selectors, chips, directory/tag filters, data-source selectors, reader settings, book-source controls, tool controls, media controls, drive controls, login fields, profile actions, sheets, and confirmation panels share the same control language.
- Primary actions use compact gradient pills; selected controls use soft green surfaces rather than large solid color blocks; unselected controls use white or transparent surfaces with subtle borders.
- The reading, tools, media, drive, and profile screenshots show consistent spacing, radii, type hierarchy, input surfaces, action hierarchy, and bottom-navigation treatment.
- DSH remote WebView content remains owned by the remote DSH application; the app-owned DSH host surface and bottom navigation use the shared MyTools styling.
- All `app/scripts/test-*.sh` checks pass. ArkTS type checking, compilation, signed HAP packaging, emulator installation, and cold launch pass.
- No P0, P1, or P2 visual issue remains in the app-owned interface scope.

final result: passed

# Shared Selection Controls Phase 2 QA (2026-08-27)

- Visual source: `app/docs/ui-redesign-mockup.png` and `app/docs/UI_REDESIGN_SPEC.md`.
- Emulator evidence: `app/build/mytools-controls-phase2-tools.jpeg` at `1320 x 2856`.
- Digest algorithms, feedback categories, remote source selectors, media directory/tag filters, book-source filters, comic options, and reader setting selectors now share the outlined pill and soft-green selected treatment.
- Selected controls use `accentSoft` with primary text and border; unselected controls use a white surface, muted text, and divider border instead of blue or gray filled blocks.
- Existing actions, state transitions, control order, and surrounding layouts are unchanged.
- Tool-page hero, search, list hierarchy, and bottom navigation remain visually coherent after the shared-control replacement.
- No P0, P1, or P2 visual issue remains in this phase.

final result: passed

# Reading Search Controls Annotation QA (2026-08-27)

- Reference: `codex-clipboard-2fbb2ef9-7e23-47a1-9dea-3c8e5f915d21.png`.
- Implementation: `app/build/mytools-search-controls.jpeg`, captured at `1320 x 2856` from the HarmonyOS emulator.
- The mode selector at the left of the source search field has no background while closed; its pale green pill appears only while the mode menu is open.
- The search action at the right has no background while the query is empty; it becomes a filled green circular action only when a query is present.
- Search, cancellation, mode switching, component positions, and surrounding layout are unchanged.
- No P0, P1, or P2 issue remains in the annotated control scope.

final result: passed

---

# Reading Experience Polish Round 2 QA (2026-08-21)

## Comparison target

- Source visual truth: `/Users/pankang/.codex/generated_images/01a005a6-cb9a-7322-8c7e-a77c719a7245/exec-f7ca44cb-9779-44f3-9fc9-1bdd00b5eba5.png`.
- Final emulator reading surface: `/Users/pankang/mycode/MyTools/app/build/design-qa-reading-experience/vm-reader-round2.jpeg`.
- Final emulator reading settings: `/Users/pankang/mycode/MyTools/app/build/design-qa-reading-experience/vm-reader-settings-round2.jpeg`.
- Final emulator catalog jump: `/Users/pankang/mycode/MyTools/app/build/design-qa-reading-experience/vm-reader-catalog-jump-rebuild.jpeg`.
- Side-by-side comparison: `/Users/pankang/mycode/MyTools/app/build/design-qa-reading-experience/reader-round2-comparison.jpg`.

## Findings and fixes

- Plain-text paragraphs now use a consistent two-character first-line indent, while scene-break markers are centered and visually quieter.
- Chapter metadata uses a compact `current/total` format; chapter titles remain larger and bold without an extra decorative rule competing with the text.
- Reading settings add compact, comfortable, and large-text typography presets while retaining fine-grained size, line-height, paragraph-spacing, margin, brightness, theme, and system-font controls.
- Text-selection offsets now match the rendered indented text instead of drifting by the inserted indentation.
- Catalog chapter selection rebuilds the scroll container before anchoring the selected chapter, preventing a reused pixel offset from placing the reader in the middle of the target chapter.
- The footer remains a single, compact line containing chapter, whole-book progress, and current/total page information.

## Interaction verification

- Emulator: remote book trial reading opened and rendered long-form content continuously with stable paragraph rhythm and the compact footer.
- Emulator: center tap opened reader controls; the font action opened the settings sheet and displayed all three typography presets without overlap.
- Emulator overwrite installation passed with the signed HAP.
- Reader cross-chapter, pagination, progress-sync, ebook UI policy checks, ArkTS type check, signed packaging, and whitespace validation passed.
- A physical device was not connected during this round, so installation was verified on the configured HarmonyOS emulator only.

final result: passed

---

# Reading Experience Polish QA (2026-08-21)

## Comparison target

- Visual target: `/Users/pankang/.codex/generated_images/01a005a6-cb9a-7322-8c7e-a77c719a7245/exec-f7ca44cb-9779-44f3-9fc9-1bdd00b5eba5.png`.
- Physical-device implementation: `/Users/pankang/mycode/MyTools/app/build/design-qa-reading-experience/reader-jump-final2.jpeg`.
- Side-by-side comparison: `/Users/pankang/mycode/MyTools/app/build/design-qa-reading-experience/reader-comparison-final.jpg`.
- Compact controls verification: `/Users/pankang/mycode/MyTools/app/build/design-qa-reading-experience/reader-controls-final.jpeg`.

## Findings and fixes

- Plain text chapters now render as independent paragraphs instead of one oversized text node. This prevents long native text nodes from truncating content and gives the selected line height and paragraph spacing a predictable visual rhythm.
- Chapter boundaries now use a clear hierarchy: section position, bold chapter title, restrained accent rule, then body content. The implementation continues to respect the user's selected system theme font.
- Empty prefetched source chapters no longer create large placeholder gaps in the continuous reading stream.
- Hidden controls retain a single 22 vp status line. The status is compacted to chapter name, whole-book progress, and current/total page estimate without wrapping.
- Visible reader controls use a shorter top bar, progress region, slider row, and action row so more text remains readable while controls are open.
- Catalog selection now anchors the selected chapter at the top of the reading surface after layout settles, closes the catalog and reader controls, and preserves continuous scrolling into adjacent chapters.

## Interaction verification

- Physical device: selected chapter 5 from the side catalog and verified that the chapter metadata, full title, accent rule, and first paragraph start at the top of the reader.
- Physical device: continuous vertical swipes move by the actual gesture distance; paragraph spacing remains stable and the one-line status updates without opening controls.
- Physical device: center tap opens the compact controls; catalog, night mode, speech, font settings, and more actions remain reachable.
- Emulator and physical device overwrite installation succeeded.
- Reader cross-chapter, pagination, progress-sync, UI policy checks, ArkTS type check, and signed HAP packaging passed.

final result: passed

---

# Reading Search and Reader Polish QA (2026-08-21)

## Comparison target

- User issue reference: `/Users/pankang/Pictures/Screenshot_2026-08-21T141435.png`.
- Emulator implementation: `/Users/pankang/mycode/MyTools/app/build/design-qa-reading/search-mode-final.jpeg`.
- Side-by-side comparison: `/Users/pankang/mycode/MyTools/app/build/design-qa-reading/search-mode-comparison.jpg`.
- Physical-device reader prompt: `/Users/pankang/mycode/MyTools/app/build/design-qa-reading/reader-shelf-prompt.jpeg`.
- Physical-device local reader: `/Users/pankang/mycode/MyTools/app/build/design-qa-reading/local-reader-final.jpeg`.

## Findings and fixes

- The search mode is now a compact, labelled control inside the unified search field. Its open menu uses a narrow three-row hierarchy with descriptions and a native selected checkmark instead of three full-width pills.
- The search action is now a native magnifying-glass icon in a 42 vp rounded-square target. The loading state replaces it with a similarly sized stop action, preventing layout movement.
- Chapter headings use a larger bold type scale in continuous and paged reader layouts.
- Trial reading from a network source displays a focused add-to-shelf confirmation on exit, while the secondary action still permits leaving without changing the shelf.
- Asynchronous source verification preserves an existing, richer introduction instead of replacing it with empty, placeholder, or shorter metadata.
- Remote books with real progress sort by progress update time descending; unread entries remain after the viewed group.
- Local TXT chapters are divided into bounded render blocks so a single oversized native Text component cannot truncate the remaining book content.

## Interaction verification

- Emulator: mode control opens, all three options are visible and non-overlapping, search action remains aligned, and the page stays top-aligned.
- Physical device: fuzzy search returned live results, result selection opened a verified catalog, and the introduction remained visible after asynchronous catalog validation.
- Physical device: trial reading loaded chapter content and the enlarged chapter title, then system Back displayed the add-to-shelf confirmation; choosing `暂不加入` returned to the existing result list.
- Physical device: remote progress prefetch reordered viewed books above unread books.
- ArkTS type check, signed HAP packaging, overwrite installation, cold start, and policy checks pass.

final result: passed

---

# Book Source Management Design QA

## Comparison target

- Source visual truth: `/Users/pankang/.codex/generated_images/01a005a6-cb9a-7322-8c7e-a77c719a7245/exec-cec53524-0809-491d-b4c5-344b2ad014e7.png`.
- Final emulator implementation: `/Users/pankang/mycode/MyTools/app/build/design-qa-book-source/book-source-tools.jpeg`.
- Source pixels: `853 x 1844`.
- Implementation pixels and viewport: `1320 x 2856`, captured from the DevEco HarmonyOS emulator with the system status bar and gesture area retained.
- Density normalization: the source was scaled to `1320 x 2856` with Lanczos filtering; the implementation remained at native capture size. The two normalized views were joined side by side without stretching the implementation.
- State: authenticated ebook page, one enabled live source, source action row expanded, add-tools bottom sheet open, light theme.

## Evidence

- Full-view comparison: `/Users/pankang/mycode/MyTools/app/build/design-qa-book-source/book-source-full-comparison.png`.
- Focused header/list comparison: `/Users/pankang/mycode/MyTools/app/build/design-qa-book-source/book-source-header-comparison.png`.
- Focused add-tools comparison: `/Users/pankang/mycode/MyTools/app/build/design-qa-book-source/book-source-sheet-comparison.png`.
- Closed management page: `/Users/pankang/mycode/MyTools/app/build/design-qa-book-source/book-source-main.jpeg`.
- Expanded source actions: `/Users/pankang/mycode/MyTools/app/build/design-qa-book-source/book-source-expanded.jpeg`.
- Filter interaction result: `/Users/pankang/mycode/MyTools/app/build/design-qa-book-source/book-source-filter.jpeg`.

## Findings

- No actionable P0, P1, or P2 mismatch remains in the verified emulator state.
- Typography uses HarmonyOS Sans with the same title, subtitle, source-name, metadata, and action hierarchy as the source visual. Long search copy truncates safely within the live device width.
- Spacing and layout preserve the fixed header, search/filter row, grouped source surface, compact expanded actions, and bottom-sheet hierarchy. Content remains top-aligned when the source list is short.
- Colors and visual tokens use the existing MyTools neutral background, white surfaces, primary blue, subtle lavender controls, dividers, and semantic red delete action.
- Icons use native HarmonyOS system symbols. The generated mock's decorative filter and source-type icons are represented by the closest available native symbols rather than handcrafted assets.
- Copy and content retain the live product actions: source discovery, JSON import, health checking, secure credentials, enable/disable, and delete. The source mock contains a second sample row, while the implementation truthfully displays the one source currently present in app state.
- The tools sheet is slightly taller than the source mock because the native device text and controls use larger accessible touch targets; all actions and the URL field remain visible without scrolling.

## Interaction verification

- The top-right add button opens the tools sheet.
- Tapping the dimmed backdrop closes the sheet.
- The source chevron expands and collapses the action row without triggering another action.
- The compact filter advances from `全部` to `已启用` and updates the list.
- Enable/disable was not toggled during visual QA to avoid mutating the live source configuration; its existing handler remains wired and is covered by the policy/build checks.
- Import, health check, discovery, credentials, and delete destructive/network effects were not executed during visual QA; their existing handlers remain wired and are asserted by source policy tests.

## Comparison history

- First normalized comparison found no actionable P0/P1/P2 issue, so no visual fix iteration was required after the emulator capture.
- The implementation intentionally uses live source count and native HarmonyOS symbols instead of the mock's fabricated second source and non-system illustrative icons.

## Implementation checklist

- [x] Keep page header, search, filter, and list fixed to a top-down hierarchy.
- [x] Move secondary creation and maintenance operations into the top-right add sheet.
- [x] Use a compact source row with icon, metadata, state, native switch, and disclosure action.
- [x] Reveal discovery, credentials, and delete only for the selected source.
- [x] Preserve the existing source business handlers and empty/filter states.
- [x] Pass ArkTS type checking, policy tests, signed HAP packaging, emulator installation, and visual comparison.

final result: passed
# UI Redesign Control Pass QA (2026-08-27)

## Scope

- Visual source: `app/docs/ui-redesign-mockup.png` and `app/docs/UI_REDESIGN_SPEC.md`.
- Implementation capture: `app/build/mytools-ui-redesign.jpeg` at `1320 x 2856` from the HarmonyOS emulator.
- State: authenticated reading page with source shelf content, light theme.
- Constraint: control styling only; existing page structure, content positions, navigation order, and behavior remain unchanged.

## Comparison

- Color tokens match the emerald accent, pale green background, white/glass surface, green soft-selection fill, dark ink text, and muted secondary text in the source.
- Header mode selector matches the pill container and soft selected state without a solid blue block.
- Shelf chips use white outlined pills and preserve the existing wrapping and position.
- Search and action surfaces use large rounded corners, subtle borders, and soft shadow without changing their component tree.
- Bottom navigation retains its original placement while matching the 28 vp icon scale, green selected icon block, selection dot, and translucent bar treatment.
- Existing search mode control and live shelf data intentionally remain as-is structurally because the redesign scope excludes feature and layout changes.

## Result

No P0, P1, or P2 visual issues remain within the control-only scope. A future pass may apply the same shared tokens to secondary sheets and deep feature pages.

final result: passed
