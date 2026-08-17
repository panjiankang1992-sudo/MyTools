#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

fail() {
  echo "UI design policy failed: $1" >&2
  exit 1
}

tabs_block="$(sed -n '/private readonly tabs:/,/^  ];/p' "$SOURCE")"
expected_tabs=$'电子书\n工具\nCopilot\n多媒体\n网盘'
actual_tabs="$(printf '%s\n' "$tabs_block" | sed -n "s/.*{ title: '\([^']*\)'.*/\1/p")"
[[ "$actual_tabs" == "$expected_tabs" ]] || fail "bottom tabs must be 电子书/工具/Copilot/多媒体/网盘"

page_header="$(sed -n '/private PageHeader(title: string)/,/^  }/p' "$SOURCE")"
printf '%s\n' "$page_header" | rg -q -F '.fontSize(21).fontWeight(FontWeight.Bold)' ||
  fail "main page titles must keep the approved compact 21 vp size"
printf '%s\n' "$page_header" | rg -q -F 'Image(this.ProfileAvatarSource()).width(36).height(36)' ||
  fail "main page avatar must keep the approved compact 36 vp visual size"
printf '%s\n' "$page_header" | rg -q -F 'Text(this.ProfileAvatarLabel()).width(36).height(36)' ||
  fail "fallback account avatar must match the compact visual size"

book_selector="$(sed -n '/private BookModeSelector()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$book_selector" | rg -q -F "ForEach(['书源', '远程', '本地']" ||
  fail "ebook modes must remain 书源/远程/本地"
printf '%s\n' "$book_selector" | rg -q -F 'this.bookModeIndex = index;' ||
  fail "ebook mode selector must update the active mode"
printf '%s\n' "$book_selector" | rg -q -F 'SymbolGlyph($r(index === 0 ?' ||
  fail "ebook modes must use native symbols"
printf '%s\n' "$book_selector" | rg -q -F 'Button() {' ||
  fail "ebook mode selector must use focusable buttons"
printf '%s\n' "$book_selector" | rg -q -F '.accessibilityDescription(this.bookModeIndex === index ?' ||
  fail "ebook mode selector must expose selected state"
printf '%s\n' "$book_selector" | rg -q -F 'if (index === 1 && this.authenticated) this.LoadRemoteBooks();' ||
  fail "remote ebook mode must load the remote library"

books_page="$(sed -n '/private BooksPage()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$books_page" | rg -q -F "this.BookActionCard('连接远程书库'" ||
  fail "remote ebook mode needs a connection action"
printf '%s\n' "$books_page" | rg -q -F "this.BookActionCard('从本地添加'" ||
  fail "local ebook mode needs an explicit picker action"
printf '%s\n' "$books_page" | rg -q -F 'this.PickLocalBooks();' ||
  fail "local ebook picker action is not wired"
printf '%s\n' "$books_page" | rg -q -F 'this.RemoteBookSourceSelector()' ||
  fail "remote ebook mode must use its own source selector"

remote_book_selector="$(sed -n '/private RemoteBookSourceSelector()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$remote_book_selector" | rg -q -F 'this.CurrentRemoteBookSourceLabel()' ||
  fail "remote ebook selector must expose the current source"
printf '%s\n' "$remote_book_selector" | rg -q -F 'this.OpenRemoteBookSourceSelector()' ||
  fail "remote ebook selector must open the source wheel"
rg -q -F 'this.remoteBookSources.map((source: RemoteMediaSource)' "$SOURCE" ||
  fail "remote ebook selector must use its isolated source list"
rg -q -F 'private RemoteBookSourceSelectorSheet()' "$SOURCE" ||
  fail "remote ebook selector wheel is missing"
rg -q -F "source.localDirectoryType === 'EBOOK'" "$SOURCE" ||
  fail "MyTools EBOOK directory must be available to the remote ebook page"

book_source_summary="$(sed -n '/private BookSourceSummaryRow()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$book_source_summary" | rg -q -F "Button(this.bookSourceManagementVisible ? '收起' : '管理 ›')" ||
  fail "book source mode must expose its management panel"
book_source_management="$(sed -n '/private BookSourceManagementPanel()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$book_source_management" | rg -q -F "Button('导入书源')" ||
  fail "book source management needs a real import action"
printf '%s\n' "$book_source_management" | rg -q -F 'this.ImportBookSources()' ||
  fail "book source import action is not wired"

book_detail="$(sed -n '/private BookDetailPage()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$book_detail" | rg -q -F "Button(this.DetailBookInShelf() ? '书架中' : '试读')" ||
  fail "book details need the fixed preview action"
printf '%s\n' "$book_detail" | rg -q -F "Button(this.DetailBookInShelf() ? '继续阅读' : '加入书架')" ||
  fail "book details need the fixed primary shelf action"
printf '%s\n' "$book_detail" | rg -q -F ".accessibilityText('目录')" ||
  fail "book detail catalog entry must be a semantic button"
printf '%s\n' "$book_detail" | rg -q -F '.accessibilityText(`书源：${candidate.sourceName}`)' ||
  fail "book source switching must expose the candidate name"

reader_page="$(sed -n '/private ReaderPage()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$reader_page" | rg -q -F '.parallelGesture(TapGesture({ count: 1 }).onAction(() => {' ||
  fail "reader middle tap must work in parallel with child content gestures"
printf '%s\n' "$reader_page" | rg -q -F 'this.readerControlsVisible = !this.readerControlsVisible;' ||
  fail "reader middle tap must toggle controls"
printf '%s\n' "$reader_page" | rg -q -F 'if (this.readerControlsVisible) this.ReaderControls()' ||
  fail "reader operation overlay state is missing"
printf '%s\n' "$reader_page" | rg -q -F 'if (this.readerSettingsVisible) this.ReaderSettingsPanel()' ||
  fail "reader settings state is missing"

reader_controls="$(sed -n '/private ReaderControls()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$reader_controls" | rg -q -F "Button('搜索')" ||
  fail "reader search must exist only in the opened-book controls"
printf '%s\n' "$reader_controls" | rg -q -F "Button('更多')" ||
  fail "reader controls need a real more action"
printf '%s\n' "$reader_controls" | rg -q -F "this.ReaderToolButton('设置'" ||
  fail "reader controls must open the settings state"
printf '%s\n' "$reader_controls" | rg -q -F "this.readerSearchVisible ? '关闭本书搜索'" ||
  fail "reader search must expose its open state"
printf '%s\n' "$reader_controls" | rg -q -F "this.readerMoreVisible ? '关闭更多操作'" ||
  fail "reader more action must expose its open state"

reader_settings="$(sed -n '/private ReaderSettingsPanel()/,/^  }/p' "$SOURCE")"
for setting in 字体 字号 行距 段距 页边距 亮度 主题; do
  printf '%s\n' "$reader_settings" | rg -q -F "'$setting'" || fail "reader settings must include $setting"
done
printf '%s\n' "$reader_settings" | rg -q -F 'this.ComicSettingsFields()' ||
  fail "comic reader must use its own settings fields"

reader_annotation="$(sed -n '/private ReaderAnnotationPanel()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$reader_annotation" | rg -q -F "Button('保存当前位置批注').width('100%')" ||
  fail "reader annotation needs a full-width save action"
printf '%s\n' "$reader_annotation" | rg -q -F '.height(48)' ||
  fail "reader annotation save action must meet the 48 vp touch target"

reader_catalog="$(sed -n '/private ReaderCatalogPanel()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$reader_catalog" | rg -q -F "Button('上一页').layoutWeight(1)" ||
  fail "PDF catalog needs previous-page navigation"
printf '%s\n' "$reader_catalog" | rg -q -F "Button('下一页').layoutWeight(1)" ||
  fail "PDF catalog needs next-page navigation"
[[ "$(printf '%s\n' "$reader_catalog" | rg -c -F '.height(48).accessibilityDescription(')" -ge 2 ]] ||
  fail "PDF catalog navigation must use semantic 48 vp targets"

media_page="$(sed -n '/private MediaPage()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$media_page" | rg -q -F "this.MediaCatalogFilterButton('directory')" ||
  fail "media directory filter must share the search row"
printf '%s\n' "$media_page" | rg -q -F "this.MediaCatalogFilterButton('tag')" ||
  fail "media tag filter must share the search row"
printf '%s\n' "$media_page" | rg -q -F "placeholder: '搜索媒体'" ||
  fail "media page needs fuzzy search"
if printf '%s\n' "$media_page" | rg -q -F 'CurrentMediaSourceLabel'; then
  fail "media page must not expose Alist or WebDAV source selection"
fi
media_mode_switch="$(sed -n '/private MediaModeSwitch()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$media_mode_switch" | rg -q -F "Button('图片')" || fail "media header needs gallery mode"
printf '%s\n' "$media_mode_switch" | rg -q -F "Button('视频')" || fail "media header needs video mode"
rg -q -F 'private MediaCatalogVideos()' "$SOURCE" || fail "video directory aggregation view is missing"
rg -q -F 'directory.topItems' "$SOURCE" || fail "video directories must show top three items"
rg -q -F 'private MediaVideoDetailPage()' "$SOURCE" || fail "multimedia video detail page is missing"
rg -q -F 'this.mediaVideoDetail.storyboard' "$SOURCE" || fail "video detail must show storyboard frames"
rg -q -F "Button(this.mediaVideoDescriptionExpanded ? '收起文字' : '展开文字')" "$SOURCE" ||
  fail "video description must support expand and collapse"
if printf '%s\n' "$media_page" | rg -q "DocumentViewPicker|PhotoViewPicker|AudioViewPicker|PickLocal|HeroPanel\('本地媒体|BookActionCard\('本地媒体|Button\('本地媒体"; then
  fail "media page must not expose a local media picker or entry"
fi
rg -q -F 'READ_MEDIA' "$APP_DIR/entry/src/main/module.json5" &&
  fail "app must not request local media library permissions"

hero_panel="$(sed -n '/private HeroPanel(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$hero_panel" | rg -q -F 'Button(action)' || fail "hero action must be a focusable button"
printf '%s\n' "$hero_panel" | rg -q -F '.height(48)' || fail "hero action must meet the 48 vp touch target"

tool_entry="$(sed -n '/private ToolEntryRow(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$tool_entry" | rg -q -F 'Button() {' || fail "tool entries must be focusable buttons"
printf '%s\n' "$tool_entry" | rg -q -F '.height(62)' || fail "tool entries must meet the 48 vp touch target"
printf '%s\n' "$tool_entry" | rg -q -F '.accessibilityText(`工具：${title}`)' ||
  fail "tool entries must expose their title"

recent_tool="$(sed -n '/private RecentToolCard(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$recent_tool" | rg -q -F 'Button() {' || fail "recent tool cards must be focusable buttons"
printf '%s\n' "$recent_tool" | rg -q -F '.accessibilityText(`最近使用：${this.RecentToolTitle(id)}`)' ||
  fail "recent tool cards must expose their destination"

tool_file_row="$(sed -n '/private ToolFileRow(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$tool_file_row" | rg -q -F 'Button() {' ||
  fail "remote file and directory content targets must be focusable buttons"
printf '%s\n' "$tool_file_row" | rg -q -F '.height(58)' ||
  fail "remote file and directory content targets must meet the 48 vp touch target"
printf '%s\n' "$tool_file_row" | rg -q -F '.accessibilityText(`${item.kind ===' ||
  fail "remote file targets must expose file or directory semantics"

tool_file_operation="$(sed -n '/private ToolFileOperationPanel()/,/^  }/p' "$SOURCE")"
for operation in 重命名 移动 删除; do
  printf '%s\n' "$tool_file_operation" | rg -q "Button\('$operation'\).*height\(48\)" ||
    fail "remote file $operation action must meet the 48 vp touch target"
done
printf '%s\n' "$tool_file_operation" | rg -q -F "Button(this.toolSelectedDirectory ? '确认删除目录' : '确认删除文件').layoutWeight(1)" ||
  fail "remote deletion confirmation must name the target kind"

media_selector="$(sed -n '/private MediaSourceSelector()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$media_selector" | rg -q -F 'Button(source.name.length > 0 ?' ||
  fail "remote source selector must use focusable buttons"
printf '%s\n' "$media_selector" | rg -q -F '.accessibilityDescription(this.selectedMediaSourceId === source.id ?' ||
  fail "remote source selector must expose selected state"

thumbnail_block="$(sed -n '/ForEach(this.mediaThumbnailItems/,/}, (item: RemoteMediaItem) => item.path)/p' "$SOURCE")"
printf '%s\n' "$thumbnail_block" | rg -q -F 'Button() {' ||
  fail "remote image thumbnails must be focusable buttons"
printf '%s\n' "$thumbnail_block" | rg -q -F '.accessibilityDescription(item.path === this.currentMediaPath ?' ||
  fail "remote image thumbnails must expose current state"

media_viewer="$(sed -n '/private RemoteMediaViewerPage()/,/^  }/p' "$SOURCE")"
visual_media_viewer="$(sed -n '/private RemoteVisualMediaViewerPage()/,/^  }/p' "$SOURCE")"
if printf '%s\n' "$visual_media_viewer" | rg -q -F '上下滑动'; then
  fail "full-screen visual viewer must not render swipe instructions"
fi
for control in "Button('上一个').width(88).height(48)" "Button('下一个').width(88).height(48)"; do
  printf '%s\n' "$visual_media_viewer" | rg -q -F "$control" ||
    fail "remote visual media transport control must use a 48 vp button: $control"
done
printf '%s\n' "$visual_media_viewer" | rg -q -F 'previewUri: this.currentMediaPreviewUri' ||
  fail "remote video viewer must retain its thumbnail while preparing playback"
printf '%s\n' "$visual_media_viewer" | rg -q -F '.controls(true)' ||
  fail "remote video controls must remain operable in immersive mode"
printf '%s\n' "$visual_media_viewer" | rg -q -F "Button(this.videoExpectedPlaying ? '暂停' : '播放')" ||
  fail "remote video viewer must expose an explicit play and pause control"
printf '%s\n' "$visual_media_viewer" | rg -q -F 'this.ToggleVideoFullscreen()' ||
  fail "remote video viewer must expose orientation and fullscreen control"
printf '%s\n' "$visual_media_viewer" | rg -q -F 'sys.symbol.star_fill' ||
  fail "immersive image favorite action must use the native system symbol"
printf '%s\n' "$visual_media_viewer" | rg -q -F 'this.CurrentMediaDisplayName()' ||
  fail "immersive image metadata must hide unreadable hash-only filenames"
rg -q -F 'private ScheduleMediaViewerChromeAutoHide(): void' "$SOURCE" ||
  fail "remote visual viewer must auto-hide chrome"
rg -q -F 'private VisualMediaViewerSequence(): RemoteMediaItem[]' "$SOURCE" ||
  fail "immersive browsing must keep image and video navigation in one visual sequence"

media_preview="$(sed -n '/private MediaPreviewCard(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$media_preview" | rg -q -F ".width(item.kind === 'audio' ? '100%' : '48.5%')" ||
  fail "visual media previews must use the approved large two-column layout"
[[ "$(printf '%s\n' "$media_preview" | rg -c -F ".height(176)")" -ge 2 ]] ||
  fail "visual media previews must remain 176 vp high"
printf '%s\n' "$media_preview" | rg -q -F "sys.symbol.music_note_circle_fill" ||
  fail "audio previews must use a system symbol instead of a text glyph"
rg -q -F '(item: MediaCatalogItem) => item.itemId' "$SOURCE" ||
  fail "new media catalog cards need stable opaque render keys"
for control in "Button('上一首').height(48)" "Button('下一首').height(48)"; do
  printf '%s\n' "$media_viewer" | rg -q -F "$control" ||
    fail "remote media transport control must use a 48 vp button: $control"
done
printf '%s\n' "$media_viewer" | rg -q -F ".accessibilityDescription('视频快退10秒')" ||
  fail "video rewind must expose its effect"
printf '%s\n' "$media_viewer" | rg -q -F ".accessibilityDescription('音频快进10秒')" ||
  fail "audio fast-forward must expose its effect"
printf '%s\n' "$media_viewer" | rg -q -F "this.audioQueueExpanded ? '收起远程音频播放队列'" ||
  fail "audio queue action must expose expanded state"

audio_queue_row="$(sed -n '/private AudioQueueRow(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$audio_queue_row" | rg -q -F 'Button() {' ||
  fail "audio queue rows must be focusable buttons"
printf '%s\n' "$audio_queue_row" | rg -q -F '.height(58)' ||
  fail "audio queue rows must meet the 48 vp touch target"
printf '%s\n' "$audio_queue_row" | rg -q -F '.accessibilityText(`播放远程音频：${item.name}`)' ||
  fail "audio queue rows must expose the remote track name"

if rg -q -F "Text('‹')" "$SOURCE"; then
  fail "back navigation must use the shared 48 vp button"
fi

back_button="$(sed -n '/private BackButton(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$back_button" | rg -q -F '.width(48)' || fail "back button width must be 48 vp"
printf '%s\n' "$back_button" | rg -q -F '.height(48)' || fail "back button height must be 48 vp"
printf '%s\n' "$back_button" | rg -q -F "sys.symbol.arrow_left" ||
  fail "back navigation must use the HarmonyOS system symbol"
printf '%s\n' "$back_button" | rg -q -F ".accessibilityText('返回')" || fail "back button needs an accessibility label"

page_header="$(sed -n '/private PageHeader(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$page_header" | rg -q -F 'Button() {' || fail "account avatar must be a focusable button"
printf '%s\n' "$page_header" | rg -q -F '.width(48).height(48)' || fail "account avatar must be 48 vp"
printf '%s\n' "$page_header" | rg -q -F ".accessibilityDescription(this.profileVisible ? '当前位于我的页面' : '进入我的页面')" ||
  fail "account avatar must independently open the profile page"

tab_bar="$(sed -n '/private TabBarItem(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$tab_bar" | rg -q -F '.height(64)' || fail "tab targets must remain at least 48 vp"
printf '%s\n' "$tab_bar" | rg -q -F '.accessibilityDescription(`主导航第${index + 1}项，共${this.tabs.length}项`)' ||
  fail "tab bar must expose navigation position"

rg -q -F 'private readonly tabScrollers: Scroller[]' "$SOURCE" || fail "tabs need independent scrollers"
rg -q -F 'Scroll(this.tabScrollers[index])' "$SOURCE" || fail "tab content must bind its own scroller"
page_content="$(sed -n '/private PageContent(/,/private PageHeader(/p' "$SOURCE")"
printf '%s\n' "$page_content" | rg -q -F ".constraintSize({ minHeight: '100%' })" ||
  fail "short tab content must reserve the viewport height and remain top-aligned"
tabs_scroll="$(sed -n '/Scroll(this.tabScrollers\[index\])/,/.scrollBar(BarState.Off)/p' "$SOURCE")"
printf '%s\n' "$tabs_scroll" | rg -q -F ".width('100%')" ||
  fail "tab scroll must fill width so short states stay top-aligned"
printf '%s\n' "$tabs_scroll" | rg -q -F ".height('100%')" ||
  fail "tab scroll must fill height so short states stay top-aligned"
printf '%s\n' "$tabs_scroll" | rg -q -F '.edgeEffect(EdgeEffect.None)' ||
  fail "tab scroll must reject picker gesture overscroll"
printf '%s\n' "$tabs_scroll" | rg -q -F '.enableScrollInteraction((index !== 3 || this.mediaSelectorMode.length === 0 &&' ||
  fail "media page scroll must pause while a wheel selector owns the gesture"
printf '%s\n' "$tabs_scroll" | rg -q -F '!this.mediaCatalogDirectorySelectorVisible && !this.mediaCatalogTagSelectorVisible)' ||
  fail "media page scroll must pause while the catalog selector owns the gesture"
printf '%s\n' "$tabs_scroll" | rg -q -F '(index !== 0 || !this.remoteBookSourceSelectorVisible))' ||
  fail "ebook page scroll must pause while the source wheel owns the gesture"
rg -q -F 'private CloseMediaSelector(): void' "$SOURCE" ||
  fail "media selector must use the shared close-and-reset path"
rg -q -F 'this.tabScrollers[3].scrollTo({ xOffset: 0, yOffset: 0, animation: false });' "$SOURCE" ||
  fail "closing a media picker must restore the media page position"
rg -q -F '.accessibilityText(`${tab.title}，${this.currentIndex === index ?' "$SOURCE" ||
  fail "tab bar needs selected-state accessibility text"
rg -q -F 'onPageHide(): void' "$SOURCE" || fail "background lifecycle must persist reader and media progress"
rg -q -F 'this.SaveCurrentProgress();' "$SOURCE" || fail "reader progress persistence is missing"
rg -q -F 'this.PersistMediaPlaybackProgress();' "$SOURCE" || fail "media progress persistence is missing"
rg -q -F '}, 30000);' "$SOURCE" || fail "reader progress needs a bounded periodic checkpoint"
rg -q -F 'Date.now() - this.lastMediaProgressPersistedAt >= 15000' "$SOURCE" ||
  fail "media progress needs a throttled periodic checkpoint"
rg -q -F 'this.pendingMediaSourceId = this.selectedMediaSourceId;' "$SOURCE" ||
  fail "session invalidation must preserve the selected remote source"
rg -q -F 'this.pendingMediaPath = this.mediaPath;' "$SOURCE" ||
  fail "session invalidation must preserve the remote directory"
rg -q -F 'else if (this.currentIndex === 2)' "$SOURCE" ||
  fail "login must resume the Copilot target tab"
rg -q -F '.onClick(() => this.RequestLogout())' "$SOURCE" ||
  fail "logout must evaluate unsynchronized reader data first"
rg -q -F "Button('继续同步')" "$SOURCE" || fail "logout risk must offer synchronization"
rg -q -F "Button('仍要退出')" "$SOURCE" || fail "logout risk must require explicit confirmation"

for forbidden in "Text('新建')" "Text('上传')" "Text('测试连接')" "Text('关闭')" "Text('完成')"; do
  if rg -q -F "$forbidden" "$SOURCE"; then
    fail "$forbidden must not be used as a compact click target"
  fi
done

echo 'UI design policy tests passed'
