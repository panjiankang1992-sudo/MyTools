# Reader Shelf Isolation And Resume QA — 2026-08-20

## Scope

- Source visual truth: `/var/folders/18/m880fd0x23b5pp66h8631x9c0000gn/T/codex-clipboard-4e2aa97d-6ddf-4100-b7cb-36a85714e76d.jpg`.
- Final local shelf: `/Users/pankang/mycode/MyTools/app/build/design-audit/reader-shelf-20260820/local-shelf.jpeg`.
- Final remote shelf: `/Users/pankang/mycode/MyTools/app/build/design-audit/reader-shelf-20260820/remote-shelf-final.jpeg`.
- Final restored reader: `/Users/pankang/mycode/MyTools/app/build/design-audit/reader-shelf-20260820/reader-final.jpeg`.
- Runtime: HarmonyOS virtual device `127.0.0.1:5555`, viewport `1320 x 2856`.

## Visible Findings

- P0: none.
- P1: none.
- P2: none after the final iteration.
- The duplicated reader status header and all persistent previous/next page actions are absent. The body starts with one chapter heading and ends with one compact status line.
- The remote page contains one `远程书库` heading with an adjacent `上传书籍` action; the earlier duplicate root heading was removed.
- Source, remote, and local modes expose independent content. The local mode contains one `添加书籍` action and no source-management or duplicate import card.
- Missing covers use the existing bundled indigo book asset. Title, author or format metadata, chapter count, tags, and progress remain readable without overlap.
- Remote list rows show restored progress before a detail page is opened.

## Interaction Verification

- A vertical swipe advanced exactly from section 1 to section 2 and did not open reader controls. A short continuation cooldown prevents one swipe from skipping several short chapters.
- A stationary middle tap opened the controls. The controls contain catalog, theme, speech, typography, more, progress, and seek actions without previous/next buttons.
- Tapping above the reading-settings sheet closed it and persisted the selected settings.
- TTS was started while the reader was at section 2; the implementation slices continuous text from the current chapter fraction rather than restarting the chapter.
- After closing and reinstalling the app, the remote shelf showed non-zero progress and the same remote book reopened at section `2/40`, `3%`.
- The local add action opened the system document picker in multi-select mode and displayed `已选 (0)`; the picker policy allows up to 50 supported ebook files.

## Backend And Build Verification

- `mvn test`: passed, including new duplicate-name multi-upload and unsupported-extension ebook import coverage.
- Reader, source, local-picker, remote-book, upload, and UI policy suites: passed.
- ArkTS type check and signed HAP build: passed without ArkTS warnings.
- Latest signed HAP overwrite installation and cold launch on `127.0.0.1:5555`: passed.
- Backend import endpoints support authenticated multipart local uploads, collision-safe filenames, asynchronous full source downloads, task status, resource-disk guarding, and post-import indexing.

final result: passed

---

# Ebook Reader Design QA

## Scope

- Reference: `/Users/pankang/.codex/generated_images/01a005a6-cb9a-7322-8c7e-a77c719a7245/exec-f7ca44cb-9779-44f3-9fc9-1bdd00b5eba5.png`
- Implementation capture: `/Users/pankang/mycode/MyTools/app/build/ebook-reading-redesign/reader-controls-final-loaded.jpeg`
- Combined comparison: `/Users/pankang/mycode/MyTools/app/build/ebook-reading-redesign/design-qa-comparison.png`
- Ebook page baseline: `/Users/pankang/mycode/MyTools/app/build/ebook-reading-redesign/ebook-page-before-header-redesign.jpeg`
- Ebook page redesign: `/Users/pankang/mycode/MyTools/app/build/ebook-reading-redesign/ebook-page-header-redesign-final.jpeg`
- Ebook page comparison: `/Users/pankang/mycode/MyTools/app/build/ebook-reading-redesign/ebook-page-header-redesign-comparison.png`
- Shelf grid baseline: `/Users/pankang/mycode/MyTools/app/build/ebook-reading-redesign/ebook-shelf-grid-before.jpeg`
- Shelf list redesign: `/Users/pankang/mycode/MyTools/app/build/ebook-reading-redesign/ebook-shelf-list-final.jpeg`
- Shelf list comparison: `/Users/pankang/mycode/MyTools/app/build/ebook-reading-redesign/ebook-shelf-list-comparison.png`
- Runtime: HarmonyOS virtual device `127.0.0.1:5555`
- State: paper theme, local TXT book opened, reading controls visible

The reference and implementation were normalized to a 390 x 844 viewport and inspected together. The comparison includes the complete header, reading surface, progress area, and bottom control bar.

## Visible Findings

- P0: none.
- P1: none.
- P2: none.
- P3: the system CJK serif typeface is less calligraphic than the generated reference. The implementation intentionally uses the platform font stack to preserve legibility and avoid bundling an unlicensed display font.
- Ebook page comparison: the redundant full-width mode selector is gone; the fixed header now matches the multimedia page hierarchy, while search, source status, and continue-reading surfaces use consistent compact radii and spacing.
- Shelf comparison: the cover wall no longer truncates nearly all metadata; each row now keeps a stable cover column and exposes title, author, chapter, tags, and progress without overlap.
- The real device keeps the system status bar while the reference omits it. This is an expected runtime difference.
- The floating annotation action appears only after text selection, matching the selected-text state in the reference without obscuring ordinary reading.

## Iterations

1. Replaced the previous dark control blocks with a paper-colored control surface and compact icon-first actions.
2. Removed the duplicated chapter heading from the body when it matches the current chapter title.
3. Reduced toolbar density, added previous/next symbols, and aligned progress information with the reference hierarchy.
4. Added functional night mode, typography settings, catalog, more menu, TTS entry, and progress seeking.
5. Added a selected-text annotation shortcut using the HarmonyOS system symbol library.
6. Added on-demand server progress restoration before opening remote books, so the first rendered chapter can resume from another device.
7. Added a compact URL-to-book-source card inside source management while retaining JSON import and health checking.
8. Reworked each managed source into a compact two-row card so long source names cannot be squeezed into vertical text, and consolidated the three decorative loading placeholders into one truthful operation state.
9. Moved source, remote, and local modes into the fixed ebook header, then tightened search, source status, continue-reading, and shelf components into a consistent three-column mobile layout.
10. Replaced the shelf cover grid with a vertical information list that prioritizes cover, title, author, chapter, tags, and progress; remote metadata tags now persist in the local reader snapshot.

## Interaction Verification

- Night mode switches the reading surface and controls together.
- The more menu opens and exposes search, bookmark, and annotation actions.
- Typography settings expose font, size, line spacing, paragraph spacing, margins, theme, and page-turn controls.
- Dragging the progress slider moves from the first chapter to the second chapter and updates chapter/progress labels.
- The TTS entry remains connected to the existing reader speech workflow.
- Reader progress continues to use the existing server synchronization flow.
- Remote directory books query their stable hashed progress before content rendering and apply the server revision only when the active account and open operation still match.
- The URL analysis control stays at the top of the source-management content, uses a full-width input/action row, and reports the asynchronous DSH task state without vertically centering sparse content.
- Virtual-device end-to-end verification generated and enabled one source from `https://tw.hjwzw.com/index.html`; searching the generated source returned `三國演義`.
- Virtual-device layout verification confirmed the source name occupies a single horizontal line, all three actions have separate equal-width targets, and an initial source search renders exactly one loading row without a duplicate status label.
- Virtual-device long press on a real image card opened the six-action sheet and kept the gallery visible, confirming that the fullscreen tap path was not fired.

## Build Verification

- `scripts/test-ebook-ui-policy.sh`: passed.
- `scripts/test-reader-pagination-policy.sh`: passed.
- `scripts/test-reader-progress-sync-policy.sh`: passed.
- `scripts/test-book-source-discovery-policy.sh`: passed.
- ArkTS HAP build: passed.
- Latest signed HAP installation on the virtual device: passed; layout dump confirmed the URL source card, action button, JSON import, and health check are visible without overlap.
- Ebook header redesign installation on the virtual device: passed; layout dump confirmed all three header modes remain clickable and remote and local mode content switches correctly.
- Shelf list installation on the virtual device: passed; long-title and missing-cover rows remained readable, row taps opened book details, and snapshot tests confirmed remote tags are deduplicated and bounded before persistence.
- Production backend and Ubuntu DSH skill deployment: passed; source discovery, JSON import, and real source search completed through the installed virtual-device app.

final result: passed

# Reading Shelf Compact List QA — 2026-08-20

## Scope

- Source visual truth: `/Users/pankang/.codex/generated_images/01a005a6-cb9a-7322-8c7e-a77c719a7245/exec-7616c77f-c5d0-4b67-9e7d-8377342486bc.png`
- Implementation capture: `/Users/pankang/mycode/MyTools/app/build/design-audit/ebook-ui-20260820/10-emulator-compact-final-latest.jpeg`
- Combined comparison: `/Users/pankang/mycode/MyTools/app/build/design-audit/ebook-ui-20260820/11-reference-vs-latest.png`
- Runtime: HarmonyOS virtual device `127.0.0.1:5555`
- State: reading home, source mode, one real cover and two books without covers

The source and implementation were normalized to the same `1844` pixel height and inspected in one side-by-side comparison input.

## Visible Findings

- P0: none.
- P1: none.
- P2: none.
- P3: the runtime keeps the HarmonyOS status bar and has fewer sample books than the generated reference; neither difference changes the requested layout.
- The continue-reading feature card is absent. Search is followed directly by the shelf heading and compact book list.
- Each book row uses a `50 x 74 vp` cover inside a `94 vp` row, approximately sixty percent of the previous card height.
- Missing covers consistently render the bundled indigo default cover; real covers continue to render unchanged.
- Title, author, current chapter, source or format, progress, and row navigation remain readable without overlap.
- The main navigation label is `阅读`, and the selected and unselected tab states remain visually distinct.

## Interaction And Build Verification

- Policy tests for ebook UI, app UI design, compact shelf layout, reader pagination, and progress synchronization: passed.
- ArkTS type check and signed HAP build: passed.
- Latest signed HAP installation and launch on HarmonyOS virtual device `127.0.0.1:5555`: passed.
- Source inspection confirms the unused continue-reading builder and its helper methods were removed; a policy test prevents reintroduction.
- Emulator screenshot confirms both no-cover samples use the same default asset and the shelf starts immediately below its heading.

final result: passed

---

# Ebook Continuous Reader QA — 2026-08-20

## Scope

- Source visual truth: `/var/folders/18/m880fd0x23b5pp66h8631x9c0000gn/T/codex-clipboard-79a7f6ab-cac1-46fb-a860-1d8e18a492cc.png`
- Implementation screenshot: `/Users/pankang/mycode/MyTools/app/build/ebook-continuous-reader/catalog-drawer-final.jpeg`
- Reader-body screenshot: `/Users/pankang/mycode/MyTools/app/build/ebook-continuous-reader/reader-body-final.jpeg`
- Combined comparison: `/Users/pankang/mycode/MyTools/app/build/ebook-continuous-reader/catalog-comparison.png`
- Runtime: HarmonyOS virtual device `127.0.0.1:5555`
- State: paper theme, local TXT book, catalog visible with the second chapter selected
- Source pixels: `590 x 848`; implementation pixels: `1320 x 2856`; implementation CSS/runtime viewport: `1320 x 2856` physical pixels at the emulator density
- Density normalization: both artifacts were scaled to `848` pixels high for the combined comparison; the implementation keeps its narrower phone aspect ratio and system bars.

## Full-view Comparison Evidence

- The reference establishes the catalog typography, selected-tab treatment, chapter-selection color, and spacious reading theme.
- The implementation intentionally changes the reference bottom sheet into the requested full-height left drawer. It preserves the paper surface, blue selected state, rounded tabs, readable chapter hierarchy, and dimmed reading context.
- The drawer begins at the physical left edge, occupies `86%` of the viewport, keeps a visible scrim/close target on the right, and does not cover the device home indicator with controls.

## Focused Comparison Evidence

- The catalog header and tabs were inspected in the combined comparison because they carry the primary visual hierarchy.
- The first two chapter rows were inspected in the emulator capture. They start immediately below the tabs, use a stable single-line layout, and distinguish the active chapter in blue.
- No image assets are present in this state; image-quality fidelity is therefore not applicable.

## Required Fidelity Surfaces

- Fonts and typography: the platform CJK font remains consistent with the existing app; title, tabs, and chapter rows have distinct weights and readable line heights.
- Spacing and layout: full-height drawer alignment, top padding, tab spacing, chapter-row rhythm, right scrim, and rounded right corners are stable with no overlap or clipping.
- Colors and tokens: the implementation uses the existing primary blue, paper/surface white, primary text, and translucent scrim tokens.
- Image quality: no visible raster or decorative assets are required for the catalog state.
- Copy and content: `目录`, `书签`, `关闭`, and real chapter names are concise and match the reading flow.

## Findings

- P0: none.
- P1: none.
- P2: none after iteration.
- P3: the implementation is intentionally narrower than the reference modal because the requested interaction is a side drawer and the emulator has a taller aspect ratio.

## Comparison History

1. Initial virtual-device capture placed the chapter list near the vertical center of the remaining drawer space. This was a P2 hierarchy issue for sparse catalogs.
2. The catalog scroll container was top-aligned and its content column explicitly changed to start alignment.
3. The revised capture shows chapter rows directly below the mode tabs, with the drawer anchored at `x = 0`; the earlier P2 issue is no longer present.

## Interaction Verification

- Tapping the catalog action opens the full-height left drawer; tapping the right scrim closes it.
- Selecting the second chapter closes the drawer and updates the fixed chapter label and body chapter heading.
- In vertical-scroll mode, an upward content swipe at the first chapter boundary automatically advances from chapter `1/2` to `2/2`.
- A downward content swipe at the second chapter boundary automatically returns from chapter `2/2` to `1/2`.
- Chapter transitions continue to schedule the existing server progress-save flow.

## Build Verification

- ArkTS type check: passed.
- Signed debug HAP build: passed.
- Signed HAP installation on HarmonyOS virtual device `127.0.0.1:5555`: passed.
- Layout dump and emulator screenshot inspection: passed.

final result: passed

---

# Ebook Home Simplification QA — 2026-08-20

## Scope

- Reference: `/var/folders/18/m880fd0x23b5pp66h8631x9c0000gn/T/codex-clipboard-1ec694dc-2c42-4b9d-8a56-9f7759cf4fb5.png`
- Homepage capture: `/Users/pankang/mycode/MyTools/app/build/ebook-home-redesign.jpeg`
- Source-management capture: `/Users/pankang/mycode/MyTools/app/build/book-source-management.jpeg`
- Combined comparison: `/Users/pankang/mycode/MyTools/app/build/ebook-home-redesign-comparison.png`
- Runtime: HarmonyOS virtual device `127.0.0.1:5555`

## Findings

- P0: none.
- P1: none.
- P2: none.
- The enabled-source summary card, recent-reading card, and second shelf search field are absent from the rendered homepage.
- The single search field is followed immediately by the shelf heading and vertical book list.
- The shelf heading keeps the book count and exposes a separate source-management action without crowding the title.
- Source management opens as a top-aligned standalone page with a fixed header and independently scrollable content.

## Interaction And Build Verification

- The source-management action opened the standalone page in the virtual device.
- Layout inspection confirmed the source-management title, enabled/total count, URL analysis, JSON import, health check, filters, source row, credentials, and delete actions.
- `scripts/test-ui-design-policy.sh`: passed.
- `scripts/test-ebook-ui-policy.sh`: passed.
- ArkTS type check and signed HAP build: passed.
- Signed HAP installation on the virtual device: passed.

final result: passed

---

# Reading Detail And Source List QA — 2026-08-20

## Scope

- Book-detail baseline: `/Users/pankang/mycode/MyTools/app/build/ebook-ui-20260819/yidai-detail-current.jpeg`
- Book-detail final capture: `/Users/pankang/mycode/MyTools/app/build/design-audit/reading-detail-source-20260820/04-book-detail-final.jpeg`
- Book-detail comparison: `/Users/pankang/mycode/MyTools/app/build/design-audit/reading-detail-source-20260820/06-detail-before-vs-final.png`
- Source-list baseline: `/Users/pankang/mycode/MyTools/app/build/design-audit/ebook-ui-20260820/05-redesigned-sources.jpeg`
- Source-list final capture: `/Users/pankang/mycode/MyTools/app/build/design-audit/reading-detail-source-20260820/05-source-list-final.jpeg`
- Source-list comparison: `/Users/pankang/mycode/MyTools/app/build/design-audit/reading-detail-source-20260820/07-source-before-vs-final.png`
- Runtime: HarmonyOS virtual device `127.0.0.1:5555`, viewport `1320 x 2856`.
- Installation target: HarmonyOS real device `9CN0224A11031537`.

## Visual Review

- Typography: detail and source titles establish a consistent hierarchy; metadata and secondary descriptions use compact single-line treatment.
- Spacing and layout: the detail cover is `92 x 138 vp`; source rows are `72 vp` high; redundant card nesting and excessive vertical gaps were removed.
- Color and surfaces: flat white surfaces, subtle dividers, tinted metadata pills, and the existing blue accent follow the Reading homepage language.
- Images: available covers keep aspect-fit presentation; missing covers continue to use the application default cover.
- Copy and controls: the detail header uses `书籍详情`; the destructive text that previously clipped into an ellipsis was replaced by a stable icon action.

## Findings

- P0: none.
- P1: none.
- P2: none after the final icon correction.
- P3: source counts and emoji in source names come from current backend data and do not cause layout overflow.
- The final source list shows materially more records per screen while preserving readable names, status, switches, and expansion controls.
- The final detail page removes the oversized hero treatment and keeps the fixed header, scrollable content, and fixed bottom actions visually distinct.

## Interaction Verification

- Opening a shelf row enters the book-detail page.
- The `书籍详情` header remains fixed after content scrolling.
- The bottom reading actions remain fixed after content scrolling.
- Source search and status filtering remain available above the list.
- Source switch and row expansion use separate hit targets.
- Expanding a source displays the credentials and delete actions without overlapping adjacent rows.
- No source state or user data was changed during visual verification.

## Build And Installation Verification

- `scripts/test-ebook-ui-policy.sh`: passed.
- `scripts/test-ui-design-policy.sh`: passed.
- `scripts/test-book-source-layout-policy.sh`: passed.
- `scripts/test-reader-pagination-policy.sh`: passed.
- `scripts/test-reader-progress-sync-policy.sh`: passed.
- ArkTS type check and signed HAP build: passed.
- Signed HAP installation on virtual device `127.0.0.1:5555`: passed.
- Signed HAP installation on real device `9CN0224A11031537`: passed.
- `git diff --check`: passed.

final result: passed
