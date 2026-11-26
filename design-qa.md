# MyTools UI Design QA

- Source visual truth: `/Users/pankang/mycode/MyTools/app/docs/ui-redesign-mockup.png`
- Implementation screenshot: `/Users/pankang/mycode/MyTools/app/build/qa/mytools-ui-nav.jpeg`
- Shared input verification: `/Users/pankang/mycode/MyTools/app/build/qa/ui-tools-input-active.jpeg`
- Source pixels: 2560 x 12800; reading-screen crop normalized to 720 x 1440.
- Implementation pixels: 1320 x 2856; normalized to 720 x 1558 for comparison.
- Device viewport: 1320 x 2856 physical pixels, portrait HarmonyOS emulator.
- State: authenticated Reading / Book Source shelf, first main tab selected.
- Density normalization: both comparison images were resized to 720 physical pixels wide; device chrome was excluded from design judgments.

## Full-view comparison evidence

The source reading-phone crop and emulator screenshot were opened together in one comparison input. The implementation preserves the existing application layout while matching the emerald accent, pale green-gray background, glass header segment, outlined chips, thin list dividers, large linear navigation icons, selected navigation icon surface, and amber reading progress.

## Focused region comparison evidence

The full comparison renders the header selector, search field, chips, shelf rows, and bottom navigation at readable size, so a second crop was not necessary. These are the exact components changed in this iteration.

## Required fidelity surfaces

- Fonts and typography: HarmonyOS Sans hierarchy, weights, truncation, and Chinese/Latin fallback are consistent with the source. Dynamic long book titles truncate without shifting the progress column.
- Spacing and layout rhythm: header, search field, section heading, list rhythm, and 60vp bottom-bar height are stable. The search field no longer has the excessive left blank region reported earlier.
- Colors and visual tokens: `#F3F6F4`, `#0DA678`, `#34C08F`, `#DDF4EA`, `#E5EAE6`, and amber progress are visibly aligned with the source tokens.
- Image quality and asset fidelity: the app uses real cached book covers and HarmonyOS system symbols. No placeholder glyph was introduced by this iteration.
- Copy and content: dynamic shelf content differs intentionally from mock data; control labels and information hierarchy remain equivalent.

## Findings

No actionable P0, P1, or P2 mismatch remains in the compared Reading screen. The navigation surface is now horizontally inset, rounded to 26vp, translucent, elevated, and retains the 28vp symbols, selected soft surface, selected label, and top dot from the source.

## Open questions

- The production search control intentionally keeps the fuzzy/exact mode selector requested by the user, while the mock shows only a search hint. The production behavior takes precedence.
- Production shelf tag chips display real file/source tags instead of the mock's illustrative category names.

## Comparison history

1. Initial comparison found the bottom navigation outer surface still full-width. Header segmented controls, chips, input density, colors, icons, and list rows had no other actionable P0/P1/P2 mismatch.
2. The existing native `Tabs` bar was narrowed with `barWidth`, made transparent, and its five existing tab items were styled as one 26vp glass pill. No content node or tab behavior was added or removed.
3. The revised Reading screenshot was compared with the normalized source crop in one comparison input. The earlier P2 is closed.
4. DSH and Multimedia tabs were clicked through device UI input. The DSH WebView/modal remained visible and interactive, and Multimedia rendered with the correct selected tab and unchanged content width.
5. The shared glass input was installed on the emulator, focused, and edited with `JSON`; text entry, caret, filtering state, and keyboard interaction remained functional.

## Implementation checklist

1. Keep `AppSegmentItem`, `AppFilterChip`, `AppPrimaryPillButton`, and `AppGlassTextInput` as the source of truth for subsequent page replacements.
2. Repeat focused comparisons when the remaining Tools, Profile, and subpage control batches are replaced.
3. Repeat the same navigation verification on the physical device when it reconnects.

## Follow-up polish

- Increase the subtle top-left emerald diffusion only if it remains legible across light displays.
- Compare the profile grouping cards against the dedicated profile mock after the navigation container is fixed.

final result: passed


## 2026-11-03 雅迪爱车页与桌面解锁入口

基于用户真机雅迪智行爱车截图进行原生适配，已安装最终正式入口包到真机并验证桌面长按解锁入口。车辆比例、页头颜色和操作区渐变经同图比较调整；字体、间距、颜色、资产和内容五项检查通过。

详细记录与组合截图：[爱车设计 QA](docs/verification/2026-11-03-yadea-home/design-qa.md)、[参考与真机同图比较](docs/verification/2026-11-03-yadea-home/comparison-final.png)。无剩余 P0/P1/P2；当前解锁入口仅显示未鉴权说明，未验证实车控车。

final result: passed


## 2026-11-03 桌面“解锁爱车”按钮

将被裁切、模糊的24px快捷图标替换为墨绿圆底与白色开锁图形的192px PNG，名称为“解锁爱车”。按真机桌面放大行为补偿图形安全区，重新安装并以同图比较复验，点击进入生产爱车页通过。

[详细QA](docs/verification/2026-11-03-yadea-shortcut-icon/design-qa.md) · [真机菜单](docs/verification/2026-11-03-yadea-shortcut-icon/menu-after-device.png)。

final result: passed
