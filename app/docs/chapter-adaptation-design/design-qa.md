# Chapter adaptation — selected option 2

Date: 2026-09-13. Scope: native HarmonyOS implementation, not a web prototype.

## Current result

Functional deployment result: passed for the bounded chapter acceptance. Pixel-level visual gate was not re-run in this deployment iteration.

Reader v6 protocol fix is deployed and creation is enabled. Emulator V5 INITIAL, V6 OPTIMIZE and V7 REGENERATE all completed, with exactly three successful provider calls each and no retries. Database/API re-reads confirm three distinct persisted results, correct lineage and unchanged original text hash. Actual V5 and V7 reading pages, V5/V6/V7 details and the combined history were opened. Captures `v6-final-history.jpeg`, `v6-completed-detail.jpeg`, `v6-reading.jpeg`, `v6-optimized-detail.jpeg`, `v6-regenerated-detail.jpeg`, `v6-regenerated-reading.jpeg` are the current evidence. Failed V4 remains historical. Reader tests: 303 passed. Shared deployment lock and emulator handoff were coordinated with the other deployment session; no global release-pointer switch or schema migration occurred. The historical blocked findings below are superseded only for this verified functional scope, not a claim of new pixel-exact visual approval.

## Latest deployment iteration

- Final installed HAP: `fd99958ea2ce4e51c2ac052889c1e3006e0ac9cbf9172738909aa67c15cfe6d7`; install evidence `app/build/acceptance/device-acceptance-20260913T080641Z.json`.
- [P0 fixed] Template API now returns seven real choices. Captures `dropdown.jpeg`, `form-ready.jpeg`, `form-filled.jpeg` show successful loading, selection and valid intent submission.
- [P1 fixed] Latest history row retained old V3/status/date while intent had updated to V4. VersionRow now uses ArkUI reference binding. New `list.jpeg` and `detail.jpeg` correctly show V4, failed status, current date and 加料小说.
- [P2 fixed] Keyboard OFFSET hid the footer. Scoped RESIZE keeps the live enabled submit button above the keyboard; mode is restored on exit. `keyboard.jpeg` is before, `keyboard-fixed.jpeg` is after. The short text in the latter was not submitted.
- Source board, successful template form and fixed keyboard capture were opened together in one comparison input. Same native viewport/density as below; keyboard-open behavior is an additional implementation state absent from the mock. Typography, green/white tokens and form hierarchy are consistent; long actual template description adds expected height. Full-resolution bounds confirm submit is visible at y=1505..1673 above the keyboard after the fix. No pixel-exact fidelity is claimed.
- [P0 open] Actual generation fails after context sealing with READER_049. After the user approved the read-only diagnostic, Reader stored-context and wire-context checks passed. The cause is confirmed: AdaptationStoryRepository still hard-coded v1-only workflow/constraint guards and validation metadata. Local v1/v2 fixes and integration regressions pass, but the fix is not deployed and no new model run occurred. Do not label complete merely because local tests or screenshots pass.
- The prior continuous navigation script assumes latest version has readable output; it correctly did not reach reading for failed V4. Historical V3 reading evidence below remains from the prior run, not a new successful v2 result.

The sections below describe earlier iterations. Older blocked template and keyboard findings are superseded by this latest iteration; the generation blocker remains authoritative. See `docs/runbooks/2026-09-13-adaptation-v2-production.md`.

## Visual source and evidence

- Selected source: `/Users/pankang/mycode/MyTools/app/docs/chapter-adaptation-design/selected-option2.png` (displayed option 2, “版本脉络”).
- Implementation evidence directory: `/Users/pankang/mycode/MyTools/app/build/acceptance/chapter-adaptation-option2/`.
- Captures: `catalog.jpeg`, `list.jpeg`, `detail.jpeg`, `reading.jpeg`, `form.jpeg`.
- Install evidence: `/Users/pankang/mycode/MyTools/app/build/acceptance/device-acceptance-20260913T072816Z.json`.
- HAP SHA-256: `e15aa58534fdf0c085e482ce1e49a11e1641e19fe6ceb3445341f27b4d696c7b`.
- Runtime: existing Pura 90 emulator, HarmonyOS 6.1.1, `127.0.0.1:15555`; physical-device verification stopped at the user's request.
- Viewport: native device capture 1320 × 2856 pixels, configured density 560 dpi (3.5 pixels/vp). The generated reference is a five-screen board, each intended as 390 × 844 logical pixels. OS status and navigation areas are retained in captures but excluded from layout judgments. This is not a claim of pixel-exact normalized comparison.
- State: authenticated existing shelf; chapter `1重生大反派`; actual historical V3/V2/V1 created before style templates were introduced. Mock book names, mock timestamps and template tags in the design were not substituted for real data.

## Comparison history and findings

1. [P2, fixed] Content initially vertically centered inside the scroll viewport. Applied `Alignment.TopStart`. Latest list/detail captures show content starting below the header.
2. [P1, fixed] Native Button's default capsule geometry distorted version cards. Specified `ButtonType.Normal`. The latest `list.jpeg` shows the latest-version card and flat historical rows instead of giant pills.
3. [P0, fixed] Footer retained its initial disabled state through value-based Builder arguments. Footer now reads live page state directly. The final continuous emulator run clicked “阅读改编内容”, reached the actual text, returned twice, and opened the new-adaptation form. Added an executable footer-state regression test.
4. [P0, open] Style options are unavailable. `form.jpeg` and `current-layout.json` show `ADAPTATION_UNAVAILABLE`, an empty selector and disabled submit. The same-state successful form/dropdown and the new-create/optimize/regenerate flow have not passed. Do not replace this evidence with a fabricated option or claim a successful generation.
5. [P2, open] Successful-form visual comparison remains unavailable; the error state adds height above the form. Keyboard-open layout, template selection and all enabled-submit states still need validation after backend readiness.

Source and implementation images were opened together for qualitative full-view comparison. The full-resolution layout dump was also inspected for button bounds, text and enabled state, identifying the footer regression. A cropped 1:1 successful-form comparison cannot be completed in the blocked state; no pixel-level pass is claimed.

## Required fidelity surfaces

- Typography: uses existing native HarmonyOS typography, 24 vp context heading, 13–15 vp UI copy and 17 vp / 28 vp reading text. Actual long chapter/book names wrap without overlapping actions. Reference reading text is slightly larger; this remains P3 polish.
- Spacing/layout: 24 vp horizontal padding, 18 vp card radius, latest-version emphasis and chronological history. Existing reader catalog drawer is retained rather than creating a second full-screen directory. Each chapter has its own 44 vp right-side action; chapter title remains original reading.
- Colors/tokens: AppTheme pale green background, white surfaces, emerald primary and soft green version badges. Primary CTA uses the existing solid primary token; the mock's subtle gradient is not treated as a functional gate.
- Assets/icons: no new raster assets are required by this direction. Native system symbols are used, not custom image approximations. System device chrome remains platform-owned.
- Copy/data: real history intent and timestamps are preserved; missing historical style snapshots are not invented. Detail labels explain versions that predate templates. All current extra consent entry points were removed; login and ownership remain intact.

## Functional verification

- Signed build, install, cold start and process observation: passed on the emulator.
- Existing chapter right-side entry → that chapter's version list → V3 detail → adapted text → detail → list → new form: passed continuously with real backend history.
- Version list keeps latest version and appends history pages without replacing previous rows: executable tests passed.
- Unknown-submit recovery retains the same key without trapping Back navigation: executable tests passed.
- App policy/normalization 22 tests, navigation/footer 8 tests, session/pending-journal 22 tests: passed.
- Reader version repository 26 tests, consent capability repository 11 tests and context/execution regression classes: passed in local H2/Java 21. Gateway adaptation contract 21 tests: passed. These are local tests, not deployment evidence.
- New generation, optimization generation, regeneration generation and resulting persistent version creation: not executed in this UI run.

## Deployment and rollback notes

- Pending Reader V40 permits null disclosure for explicitly marked DIRECT requests while preserving EXPLICIT historical records and foreign-key relationships.
- Reader creation, execution claims, send permission and Gateway/APP feature normalization were adjusted coherently; no consent acceptance is fabricated. Model-deployment shutdown, account ownership and workload authentication remain enforced.
- Template support (V39 and its matching execution release) and V40 must be released together with compatible Gateway/APP behavior before successful form/generation acceptance.
- Do not roll an old Reader executable back while DIRECT tasks are active: disable new creation and drain/finalize active DIRECT work first. Keep migration data and historical versions; do not drop or rewrite history.
- The final corrected HAP is installed on the emulator. The physical device was not updated to this final HAP after the user chose emulator-only verification.

## Next acceptance gate

Deploy compatible backend/template support, then verify template selection, keyboard state, initial creation, optimize and regenerate once, confirming that every result is retained. Capture the successful form and re-evaluate this blocked gate before marking the feature complete.
