# Multimedia / Remote Files Design QA

## Current iteration gate

- Current signed HAP SHA-256: `9b08c947dfe076924fb48a77721742ebafa4d39b313f5c906c68c1e7052d2a21`.
- Emulator acceptance evidence: `/Users/pankang/mycode/MyTools/app/build/acceptance/device-acceptance-20260815T091018Z.json`.
- The complete `app/scripts/test-*.sh` suite passes.
- ArkTS type checking and signed HAP packaging pass without warnings.
- Emulator overwrite installation, cold start, authentication restoration, gallery browsing, source selection, `big_media` loading, image switching, video loading, video autoplay, control visibility, and vertical switching pass.
- The signed HAP is installed on the connected physical device. Physical cold-start observation is not counted as passed because the device was locked when the final launch command ran.

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
