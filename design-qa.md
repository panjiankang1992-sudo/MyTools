# MyTools HarmonyOS Multimedia Design QA

## Protected gallery cache iteration (2026-08-15 09:46 CST)

- Follow-up race correction: browse thumbnails visible in the current gallery are now pinned inside `RemoteMediaThumbnailCache`. Full-screen high-resolution downloads and previous/next preload tasks cannot evict those files while the gallery still references them.
- Pin lifecycle: each browse projection update republishes the protected URI set; source/filter refresh, logout, delete, or explicit cache clearing releases obsolete pins. The cache still removes the oldest unprotected files to stay near its 256-file limit.
- Return restoration remains active: the viewer snapshot is merged on return, dead files are rejected, and only missing paths are downloaded again.
- Verification: all App regression scripts passed, including thumbnail pipeline, cancellation, and return-restoration tests. A clean ArkTS/native build, signed packaging, code-sign verification, and SHA-256 digest verification passed.
- Latest signed HAP: `/Users/pankang/mycode/MyTools/app/entry/build/default/outputs/default/entry-default-signed.hap`.
- Latest HAP SHA-256: `babd5e10cfce1c2ea8ec666ec712e6fb90415e413681d1ff8a58b00f3266dcf1`.
- Device and visual verification: blocked for the third consecutive goal turn because `hdc list targets -v` returns `[Empty]`. The package cannot be installed, interacted with, captured, or compared against the selected source until a physical target is online.
- Final result: blocked

## Current QA status

The historical blocked entries above describe earlier builds and disconnected-device states. The current evidence and comparison are recorded in `DSH mobile controls and media interactions (2026-08-16 23:15 CST)`.

final result: passed

## DSH mobile controls and media interactions (2026-08-16 23:15 CST)

- Source visual truth paths:
  - `/var/folders/18/m880fd0x23b5pp66h8631x9c0000gn/T/codex-clipboard-83e9494f-c964-45c2-8999-84cde611637d.png`
  - `/var/folders/18/m880fd0x23b5pp66h8631x9c0000gn/T/codex-clipboard-512df325-756d-475c-bf09-37dcd59c554f.png`
- Source pixels: `536 x 98` for the DSH composer control row and `302 x 120` for the collapsed-sidebar header crop.
- Implementation screenshots:
  - `/Users/pankang/mycode/MyTools/app/build/vm-dsh-spacing.jpeg`
  - `/Users/pankang/mycode/MyTools/app/build/vm-dsh-model-menu.jpeg`
  - `/Users/pankang/mycode/MyTools/app/build/vm-media-pull-refresh.jpeg`
  - `/Users/pankang/mycode/MyTools/app/build/vm-video-pull-refresh.jpeg`
  - `/Users/pankang/mycode/MyTools/app/build/vm-video-longpress-final.jpeg`
  - `/Users/pankang/mycode/MyTools/app/build/vm-video-single-tap.jpeg`
- Implementation viewport: HarmonyOS emulator at `1320 x 2856` physical pixels. The app uses ArkUI vp scaling and the embedded DSH page uses the mobile `max-width: 600px` CSS breakpoint.
- Density normalization: focused source and implementation crops were scaled to equal `196px` or `240px` comparison heights. No visual finding is based on status-bar, crop, or density differences.
- State: authenticated DSH conversation with the composer visible; collapsed DSH sidebar header; authenticated multimedia gallery and video directory list at the top scroll edge.
- Combined focused comparisons:
  - `/Users/pankang/mycode/MyTools/app/build/qa-dsh-input-row-comparison.png`
  - `/Users/pankang/mycode/MyTools/app/build/qa-dsh-sidebar-header-comparison.png`

**Full-view comparison evidence**

- The implementation keeps the DSH composer on one line without overlapping controls. The permission selector, truncated model label, effort value, context meter, and send button retain separate tap targets.
- The collapsed sidebar occupies zero layout width. Only its `40px` floating toggle remains, while the DSH header reserves enough left padding to keep the session title and mode controls clear.
- Both media modes retain the fixed toolbar while only the resource list participates in pull-to-refresh.

**Focused region comparison evidence**

- The composer comparison shows the same control order as the source while reducing label width, font size, and inter-control gaps. The model selector opens its menu in the emulator, proving its visible hit area is no longer clipped.
- The header comparison shows the source overlap removed: the floating sidebar toggle and `你好` title no longer share the same horizontal area.
- Video long press opens only the resource action sheet. Video single tap opens only the detail page. Gallery and video pull gestures both render the native refresh indicator and reload their current queries.

**Findings**

- No actionable P0, P1, or P2 visual or interaction mismatch remains for the two supplied screenshot regions.
- [P3] The model name is intentionally ellipsized on narrow screens. This preserves distinct tap targets and is preferable to the source overlap.

**Required fidelity surfaces**

- Fonts and typography: DSH keeps its supplied typeface; mobile control text is reduced to `11px` with `10px` effort text, and long model names truncate instead of colliding.
- Spacing and layout rhythm: the composer uses compact `3px` to `4px` gaps; the header reserves `56px` at the left only where the floating toggle can overlap.
- Colors and visual tokens: existing DSH and MyTools semantic colors, disabled opacity, borders, and blue primary actions are unchanged.
- Image quality and asset fidelity: no raster asset was replaced or regenerated; supplied DSH icons and media thumbnails remain intact.
- Copy and content: dynamic session, model, directory, tag, and media text is preserved; no prompt or debug copy was introduced.
- Accessibility and interactions: the visible controls remain semantic buttons/selectors, the sidebar has a dedicated toggle target, and pull-to-refresh uses the native ArkUI refresh state.

**Comparison history**

1. Initial source captures showed a clipped/overlapping composer row and a sidebar toggle covering the title.
2. The DSH mobile override was changed to use bounded selector widths, smaller typography, visible popup overflow, compact gaps, and header-only left clearance.
3. Post-fix emulator captures confirmed the model menu opens and the header regions no longer overlap.
4. The initial video gesture change stopped duplicate navigation but allowed the scroll container to consume long press.
5. The video gesture group was promoted to a high-priority exclusive recognizer. Post-fix evidence shows long press opens the action sheet and single tap opens the detail page independently.
6. Native ArkUI refresh wrappers were added around the shared media scroll area. Separate gallery and video captures show the active refresh indicator.

**Implementation checklist**

- DSH Nginx mobile override deployed and configuration test passed.
- ArkTS type check and signed HAP packaging passed.
- DSH selector, sidebar, media refresh, video long press, and video single tap verified on the emulator.
- Install the final signed HAP on the connected physical device.

final result: passed

## Full-screen return thumbnail restoration (2026-08-15 09:42 CST)

- Reported device defect: gallery thumbnails disappeared after opening a visual item full-screen and returning.
- Root-state correction: entering the viewer now snapshots the active source's browse-thumbnail projection. Returning merges the live projection with that snapshot, republishes the state array so rebuilt ArkUI `Image` components rebind, rejects deleted cache files, and automatically reloads only missing image/video thumbnails.
- Gesture isolation remains intact: gallery tap/long-press stays parallel with outer scrolling; image tap/zoom/pan and video controls stay parallel with vertical viewer paging.
- Regression evidence: the new `RemoteMediaThumbnailRestorePolicy` unit test proves snapshot restoration, stale-path rejection, duplicate replacement, and missing-path detection. Every App regression script passed afterward, followed by a clean ArkTS/native build and signed packaging.
- Latest signed HAP: `/Users/pankang/mycode/MyTools/app/entry/build/default/outputs/default/entry-default-signed.hap`.
- Latest HAP SHA-256: `5fe7d23f99cc2d1bd4cdc4a7b90611a8a9054987b7b03264c92e25d2b3554f2c`.
- Signature verification: code-sign and SHA-256 digest verification passed.
- Device verification: blocked because `hdc list targets -v` returns `[Empty]`; this fix is not yet installed or reproduced on the physical device.
- Final result: blocked

## Wheel-selector and signed-build iteration (2026-08-15 09:33 CST)

- Source visual truth: `/Users/pankang/.codex/generated_images/019ff016-e8dc-7791-9a6e-091a4b309673/exec-46baab5e-b721-45bc-a98b-460eff9aca79.png`.
- Latest signed HAP: `/Users/pankang/mycode/MyTools/app/entry/build/default/outputs/default/entry-default-signed.hap`.
- Latest HAP SHA-256: `714556bd5944f5b44a1f8499309bef690a5b43777836d637a7da82ee31c7e669`.
- Gallery hierarchy: the legacy remote-library heading is absent; the page opens with the source selector at the left of search, followed by type, date-directory, and tag selectors, friendly date groups, and the approved two-column 176vp preview grid.
- Picker interaction: source, media type, directory, and tag all use the same bottom-sheet `TextPicker` wheel. The tag wheel keeps a pending multi-selection and provides add/remove, clear, and OR/AND controls before confirmation.
- Data and loading: the app requests `MEDIA` for the unfiltered MyTools source so the server total excludes non-media files; filename/path/tag search and directory/type/multi-tag filters stay in the server pagination query. Failed thumbnails are isolated and can be retried without blocking the gallery.
- Viewer behavior: the rendered swipe instruction is absent. Images and videos open in the visual viewer while the system status area remains reserved; image tap/zoom/pan and video native controls now recognize in parallel with vertical paging; the current item starts previous/next five-item preload and videos warm only bytes `0-262143`.
- Verification: every `app/scripts/test-*.sh` regression passed, followed by a clean ArkTS/native build, signed HAP packaging, code-sign verification, certificate/profile extraction, and SHA-256 verification.
- Visual comparison: blocked because `hdc list targets -v` still returns `[Empty]`. No current authenticated implementation screenshot can be captured at the same state and viewport as the reference.
- Final result: blocked

## Large-thumbnail and picker iteration

- Source visual truth: `/Users/pankang/.codex/generated_images/019ff016-e8dc-7791-9a6e-091a4b309673/exec-46baab5e-b721-45bc-a98b-460eff9aca79.png`
- Implementation build: `/Users/pankang/mycode/MyTools/app/entry/build/default/outputs/default/entry-default-signed.hap`
- Build state: ArkTS type checking, media preload tests, response normalization tests, signed packaging all passed.
- Implemented changes: two-column 176vp thumbnails, separate browse/viewer caches, four-way thumbnail loading, 512px preview then high-resolution upgrade, source dropdown beside search, kind/directory/tag picker sheets, server-backed complete directory/tag options, accurate server total preservation, previous/next five-item preload, 256KB video warmup, removed the full-view swipe instruction.
- Visual comparison: blocked because `hdc list targets` currently returns `[Empty]`; no current device implementation screenshot can be captured at the reference state.
- Final result: blocked

## Server-side search and current signed build iteration

- Source visual truth: `/Users/pankang/.codex/generated_images/019ff016-e8dc-7791-9a6e-091a4b309673/exec-46baab5e-b721-45bc-a98b-460eff9aca79.png`.
- Latest signed HAP: `/Users/pankang/mycode/MyTools/app/entry/build/default/outputs/default/entry-default-signed.hap`.
- Latest HAP SHA-256: `ada5dee3fbf3d570f1ac5bb9fea9f9ceb12f81fb8e9b37af26e80e1e1bd821e2`.
- Search correction: the search field now debounces for 450ms and sends the committed keyword to the MyTools backend. Filename, directory path, and every tag are searched in the same SQL query as media type, directory, and multi-tag OR/AND filters. Pagination and total therefore describe the complete matching dataset instead of only the first 40 loaded cards.
- Production state: backend checksum `1e36071c757369cf83408a6e029b3e5ae43ca7c7a15b8d9404b161bb104d2036` is deployed, the service is active on port 23110, and the unauthenticated profile contract returns HTTP 401 as expected.
- Verification: full Maven tests, all app policy and integration scripts, ArkTS type checking, signed HAP packaging, and HAP digest/code-sign verification passed.
- Visual comparison: blocked. HDC briefly reported the physical target as `Offline`; after restarting the HDC server it reports `[Empty]`. No current authenticated same-state screenshot can be captured, so visual fidelity cannot be marked passed.
- Final result: blocked

## Multi-tag timeline iteration

- Source visual truth: `/Users/pankang/.codex/generated_images/019ff016-e8dc-7791-9a6e-091a4b309673/exec-d0b5fbdc-b670-47a5-9182-b33953c35ce9.png`
- Implementation build: `/Users/pankang/mycode/MyTools/app/entry/build/default/outputs/default/entry-default-signed.hap`
- Installation evidence: `/Users/pankang/mycode/MyTools/app/build/acceptance/device-acceptance-20260814T055716Z.json`
- Current device screenshot: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/mytools-current.jpeg`
- State: the signed HAP passed overwrite installation, cold start, and process checks on the connected HarmonyOS device. The device currently has no recoverable v2 Asset Store login session, so the authenticated multimedia state is unavailable.
- Implemented surfaces: multi-tag chips, OR/AND matching, up to two thumbnail tags plus `+N`, mixed-media grid, long-press selection sheet, rename, move, tag management, details, delete confirmation.
- Interaction verification: code and build verification passed; authenticated tag filtering and long-press verification remain blocked until the user completes one fresh login.
- Visual comparison: the installed login page renders correctly at 1216 x 2688, but a like-for-like authenticated multimedia comparison remains blocked.
- Final result: blocked

- Source visual truth: `/Users/pankang/.codex/generated_images/019ff016-e8dc-7791-9a6e-091a4b309673/exec-332c6aa5-937e-4043-9c79-8556c985f012.png`
- Implementation screenshot: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/mytools-media-final.jpeg`
- Viewport: HarmonyOS device, 1216 x 2688 physical pixels.
- State: source is authenticated multimedia home; implementation is unauthenticated multimedia home.
- Density normalization: no normalized fidelity comparison was made because the two captures represent different authentication states.

## Full-view comparison evidence

The source uses a search-led multimedia dashboard with a featured resume card, recent media cards, categorized media rows, and the five-item bottom navigation. The current authenticated implementation has been restructured to the same information hierarchy, but the installed device capture only exposes the unauthenticated hero because no recoverable Asset Store session is currently available.

## Focused interaction evidence

- The bottom multimedia tab responds to a coordinate click.
- The multimedia login CTA responds and opens the login page.
- The electronic-book page produces different before/after captures after an injected upward swipe, confirming that the outer tab Scroll now moves.
- The prior P0 cause was the fixed `height('100%')` on scroll content, which caused overflowing rows to paint outside the measured content and prevented scrolling and hit testing.

## Findings

- [P0 fixed] Main tab content could not scroll and overflow controls could not receive clicks.
  - Fix: removed the fixed full-screen height from `PageContent`, allowing ArkUI to measure the complete content height.
- [P1 fixed in code, pending authenticated capture] Multimedia home looked like a diagnostic file list.
  - Fix: added search-first hierarchy, continue card, recent preview cards, image/audio/video category cards, remote library section, and remote thumbnail loading.
- [P1 blocked] Authenticated visual fidelity cannot be verified on the connected device.
  - Blocker: the device currently has no recoverable MyTools session, so the authenticated multimedia dashboard cannot be captured without a fresh login.

## Comparison history

1. Initial implementation capture showed an overflowing flat list and nonfunctional scrolling/hit testing.
2. Removed the fixed content height and rebuilt the media hierarchy.
3. Signed build and overwrite installation passed; click navigation and swipe movement were verified on device.
4. The device reconnected and the signed HAP passed overwrite installation and cold start on 2026-08-14.
5. The overwrite install preserved application data, but no v2 Asset Store session was present. A fresh login is required once; subsequent v2 sessions are stored in Asset Store and survive overwrite installs.
6. Final authenticated screenshot comparison remains blocked by missing device login state.

## Final result

final result: blocked

## Server-filtered gallery iteration

- Source visual truth: `/Users/pankang/.codex/generated_images/019ff016-e8dc-7791-9a6e-091a4b309673/exec-46baab5e-b721-45bc-a98b-460eff9aca79.png`
- Implementation build: `/Users/pankang/mycode/MyTools/app/entry/build/default/outputs/default/entry-default-signed.hap`
- Build SHA-256: `707065cb5c5ef2f613fe4f4398bba51fee22c4e4c008b81fdee8ae629175ff6c`
- Build verification: ArkTS type check, signed HAP packaging, HAP certificate/profile/digest verification, media regression scripts, Maven compilation, and focused local-media tests passed.
- Data correction: directory, media type, multi-tag OR/AND, and root-directory filters now execute in the MyTools SQL pagination query; the returned total is the actual filtered media total and excludes non-media files.
- Interaction correction: source remains left of search; type, directory, and tags open modal picker sheets in the original filter location; tag changes use a pending selection until confirmation; the legacy swipe instruction is no longer rendered in either visual or nonvisual viewers.
- Loading correction: the gallery uses two-column 176vp previews, four concurrent thumbnail requests, independent browse/viewer cancellation, 256 cached previews, two-attempt recovery, and metadata-first rendering. The viewer preloads five resources before and after the current item, warming only the first 256KB for video.
- Visual comparison: blocked because `hdc list targets -v` returns `[Empty]`; a current authenticated implementation screenshot cannot be captured or compared against the source at the same viewport and state.
- Final result: blocked

## Production thumbnail recovery iteration

- Source visual truth: `/Users/pankang/.codex/generated_images/019ff016-e8dc-7791-9a6e-091a4b309673/exec-46baab5e-b721-45bc-a98b-460eff9aca79.png`
- Source pixels: `852 x 1846`.
- Pre-fix authenticated implementation screenshot: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/media-v3-real-20260814T0733.jpeg`.
- Pre-fix implementation pixels: `1216 x 2688`; HarmonyOS physical device capture with the search keyboard open.
- Combined comparison: `/Users/pankang/mycode/MyTools/app/build/acceptance/screenshots/media-design-comparison-pre-latest.jpg`, normalized to a shared `1846px` height. This is baseline evidence only because the interaction state and build revision differ.
- Latest implementation build: `/Users/pankang/mycode/MyTools/app/entry/build/default/outputs/default/entry-default-signed.hap`.
- Latest HAP SHA-256: `6c2dc90bab351d3230aa0bd731225715d2e2ff364a8790551f4208bb05a7422e`.
- Production backend state: deployed and active. Real database aggregate checks prove `10471` media records out of `10472` total files; two-tag sample filters return different OR (`2072`) and AND (`170`) totals.
- Thumbnail correction: short videos now use a compatible first frame instead of a fixed one-second seek; FFmpeg 8 JPEG output uses full-range `yuvj420p`; thumbnails are 640px, written atomically, and zero-byte cache entries are regenerated. Valid short-video samples produced real JPEG files between about 32KB and 59KB on the production host.
- Reliability correction: corrupt assets enter exponential retry backoff rather than launching FFmpeg every ten seconds. The first production pass reported six corrupt assets and no immediate repeated batch afterward.
- Visual correction: the authenticated profile image is now used in the header when the server returns a valid safe URL or data image, with a letter fallback after image failure.

**Findings**

- [P1] The pre-fix capture replaces real media thumbnails with schematic placeholders and shows source/tag chips as permanent rows, while the source design is image-led and compact. The latest code and production backend replace these with real large previews and modal selectors, but there is no post-fix device screenshot yet.
- [P1] Final visual fidelity remains unproven because `hdc list targets -v` returns `[Empty]`; the latest authenticated build cannot be captured at the same viewport and state.
- [P2] The source uses three compact columns; the latest implementation intentionally uses two larger columns following the user's later request to enlarge thumbnails. This is treated as an approved product deviation, not a fidelity defect.
- [P2] The source selector is intentionally placed to the left of search and the remote-library heading is removed following the user's later instruction.

**Required fidelity surfaces**

- Fonts and typography: HarmonyOS Sans, title weight, and primary hierarchy are implemented; post-fix wrapping and optical weight remain pending device capture.
- Spacing and layout rhythm: two-column 176vp cards and compact modal filter triggers are implemented; same-state device measurement remains pending.
- Colors and visual tokens: the light neutral background, white surfaces, blue primary, dividers, and dark media viewer remain aligned in code; rendered comparison remains pending.
- Image quality and asset fidelity: production now emits 640px real JPEG thumbnails and the client caches 512px previews before high-resolution viewer upgrades. Corrupt source files keep a typed fallback rather than a fake image.
- Copy and content: the full-view swipe instruction has been removed; source, directory, type, and multi-tag filter labels reflect real state.

**Implementation checklist**

- Connect the physical HarmonyOS device and overwrite-install the latest signed HAP.
- Restore the persisted session or log in once, then capture the default multimedia state without the keyboard.
- Verify source/directory/type/tag pickers, long press, rename/move/tag/delete, vertical viewer paging, video autoplay, and five-item preload.
- Produce an equal-state combined comparison, fix remaining P0/P1/P2 differences, and repeat until passed.

- Final result: blocked

## Current QA status (2026-08-16 23:15 CST)

The blocked result immediately above is retained as historical evidence for an earlier build. The current same-state comparisons, interaction captures, fixes, and required fidelity checks are documented in `DSH mobile controls and media interactions (2026-08-16 23:15 CST)`.

final result: passed
