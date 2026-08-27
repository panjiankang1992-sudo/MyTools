#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
CONTROLS="$APP_DIR/entry/src/main/ets/components/AppControls.ets"

fail() {
  echo "UI design policy failed: $1" >&2
  exit 1
}

tabs_block="$(sed -n '/private readonly tabs:/,/^  ];/p' "$SOURCE")"
expected_tabs=$'阅读\n工具\nDSH\n多媒体\n网盘'
actual_tabs="$(printf '%s\n' "$tabs_block" | sed -n "s/.*{ title: '\([^']*\)'.*/\1/p")"
[[ "$actual_tabs" == "$expected_tabs" ]] || fail "bottom tabs must be 阅读/工具/DSH/多媒体/网盘"

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
printf '%s\n' "$book_selector" | rg -q -F 'AppSegmentItem({ label: item' ||
  fail "ebook mode selector must use the shared segment control"
rg -q -F 'Button(this.label)' "$CONTROLS" ||
  fail "shared segment control must remain a focusable button"
printf '%s\n' "$page_header" | rg -q -F 'this.BookModeSelector()' ||
  fail "ebook modes must stay in the fixed page header"
rg -q -F ".accessibilityDescription(this.selected ? '已选择' : '未选择')" "$CONTROLS" ||
  fail "shared segment control must expose selected state"
printf '%s\n' "$book_selector" | rg -q -F 'if (index === 1 && this.authenticated) this.LoadRemoteBooks();' ||
  fail "remote ebook mode must load the remote library"

books_page="$(sed -n '/private BooksPage()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$books_page" | rg -q -F "this.BookActionCard('连接远程书库'" ||
  fail "remote ebook mode needs a connection action"
printf '%s\n' "$books_page" | rg -q -F 'this.BookShelfHeader()' ||
  fail "each ebook shelf needs its contextual header action"
if printf '%s\n' "$books_page" | rg -q -F 'this.BookModeSelector()'; then
  fail "ebook modes must not consume a second content row"
fi
if printf '%s\n' "$books_page" | rg -q -F '.columnsTemplate('; then
  fail "ebook shelf must use a vertical information list"
fi
book_shelf_section="$(sed -n '/private BookShelfSection()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$book_shelf_section" | rg -q -F 'this.BookShelfCard(book)' ||
  fail "ebook shelf list must render a row for each book"

rg -q -F "source.localDirectoryType === 'EBOOK'" "$SOURCE" ||
  fail "MyTools EBOOK directory must be available to the remote ebook page"

book_shelf_header="$(sed -n '/private BookShelfHeader()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$book_shelf_header" | rg -q -F 'this.BookShelfHeaderAction()' ||
  fail "ebook shelf header must expose a mode-specific action"
rg -q -F 'private RunBookShelfHeaderAction(): void' "$SOURCE" ||
  fail "ebook shelf header action is not wired"
rg -q -F 'private BookSourceManagementPage()' "$SOURCE" ||
  fail "book source management must use a standalone page"
if printf '%s\n' "$books_page" | rg -q -F 'this.BookSourceSummaryRow()'; then
  fail "ebook home must not render the book source summary card"
fi
if printf '%s\n' "$books_page" | rg -q -F 'this.ContinueReadingCard('; then
  fail "ebook home must not render the duplicate recent-reading card"
fi
[[ "$(printf '%s\n' "$books_page" | rg -c -F 'this.BookShelfSearchField()')" -eq 1 ]] ||
  fail "local shelf must render exactly one search field"
book_source_management="$(sed -n '/private BookSourceManagementPanel()/,/^  }/p' "$SOURCE")"
book_source_tools="$(sed -n '/private BookSourceManageToolsSheet()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$book_source_tools" | rg -q -F "Text('导入 JSON')" ||
  fail "book source tools sheet needs a real import action"
printf '%s\n' "$book_source_tools" | rg -q -F 'this.ImportBookSources()' ||
  fail "book source tools import action is not wired"
printf '%s\n' "$book_source_tools" | rg -q -F 'this.DiscoverBookSource()' ||
  fail "book source tools website discovery is not wired"
printf '%s\n' "$book_source_management" | rg -q -F 'this.FilteredManagedBookSources()' ||
  fail "book source management list must preserve filtering"
book_source_row="$(sed -n '/private BookSourceManageRow(source: BookSource)/,/^  }/p' "$SOURCE")"
printf '%s\n' "$book_source_row" | rg -q -F '.maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })' ||
  fail "book source names must stay horizontal and truncate safely"
[[ "$(printf '%s\n' "$book_source_row" | rg -c -F '.layoutWeight(1).height(44)')" -ge 2 ]] ||
  fail "book source actions must use a dedicated balanced action row"
network_loading="$(sed -n '/private NetworkLoadingRows(label: string)/,/^  }/p' "$SOURCE")"
printf '%s\n' "$network_loading" | rg -q -F 'Text(label)' ||
  fail "network loading state must display the current operation"
if printf '%s\n' "$network_loading" | rg -q -F 'ForEach([0, 1, 2]'; then
  fail "network loading state must not render three duplicate placeholders"
fi
printf '%s\n' "$books_page" | rg -q -F "!(this.bookModeIndex === 0 && this.sourceSearchLoading)" ||
  fail "book source loading must not duplicate the operation status below the spinner"

book_detail="$(sed -n '/private BookDetailPage()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$book_detail" | rg -q -F "AppSecondaryPillButton({ label: this.DetailBookInShelf() ? '书架中' : '试读'" ||
  fail "book details need the fixed preview action"
printf '%s\n' "$book_detail" | rg -q -F "AppPrimaryPillButton({ label: this.DetailBookInShelf() ? '继续阅读' : '加入书架'" ||
  fail "book details need the fixed primary shelf action"
printf '%s\n' "$book_detail" | rg -q -F ".accessibilityText('目录')" ||
  fail "book detail catalog entry must be a semantic button"
printf '%s\n' "$book_detail" | rg -q -F '.accessibilityText(`书源：${candidate.sourceName}`)' ||
  fail "book source switching must expose the candidate name"

reader_page="$(sed -n '/private ReaderPage()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$reader_page" | rg -q -F '.parallelGesture(TapGesture({ count: 1, distanceThreshold: 6 }).onAction(() => {' ||
  fail "reader tap must cancel after a scroll-sized movement"
printf '%s\n' "$reader_page" | rg -q -F 'this.readerControlsVisible = !this.readerControlsVisible;' ||
  fail "reader middle tap must toggle controls"
printf '%s\n' "$reader_page" | rg -q -F 'if (this.readerControlsVisible) this.ReaderControls()' ||
  fail "reader operation overlay state is missing"
rg -q -F 'this.ReaderSettingsPanel()' "$SOURCE" ||
  fail "reader settings state is missing"
rg -q -F "backgroundColor('#52000000')" "$SOURCE" ||
  fail "reader settings need an outside-click backdrop"

reader_controls="$(sed -n '/private ReaderControls()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$reader_controls" | rg -q -F "this.ReaderMoreButton('搜索正文'" ||
  fail "reader search must exist only in the opened-book controls"
printf '%s\n' "$reader_controls" | rg -q -F ".accessibilityText('更多')" ||
  fail "reader controls need a real more action"
printf '%s\n' "$reader_controls" | rg -q -F 'this.ReaderFontQuickAction()' ||
  fail "reader controls must open the settings state"
printf '%s\n' "$reader_controls" | rg -q -F 'this.readerSearchVisible = true;' ||
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
printf '%s\n' "$reader_annotation" | rg -q -F "AppPrimaryPillButton({ label: '保存当前位置批注'" ||
  fail "reader annotation needs a full-width save action"
rg -q -F '.height(AppTheme.controlHeight)' "$CONTROLS" ||
  fail "shared action buttons must meet the standard touch target"

reader_catalog="$(sed -n '/private ReaderCatalogPanel()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$reader_catalog" | rg -q -F "Button('上一页').layoutWeight(1)" ||
  fail "PDF catalog needs previous-page navigation"
printf '%s\n' "$reader_catalog" | rg -q -F "Button('下一页').layoutWeight(1)" ||
  fail "PDF catalog needs next-page navigation"
[[ "$(printf '%s\n' "$reader_catalog" | rg -c -F '.height(48).accessibilityDescription(')" -ge 2 ]] ||
  fail "PDF catalog navigation must use semantic 48 vp targets"

media_page="$(sed -n '/private MediaPage()/,/^  }/p' "$SOURCE")"
media_toolbar="$(sed -n '/private MediaCatalogToolbar()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$media_toolbar" | rg -q -F "this.MediaCatalogFilterButton('directory')" ||
  fail "media directory filter must share the search row"
printf '%s\n' "$media_toolbar" | rg -q -F "this.MediaCatalogFilterButton('tag')" ||
  fail "media tag filter must share the search row"
printf '%s\n' "$media_toolbar" | rg -q -F "placeholder: '搜索媒体'" ||
  fail "media page needs fuzzy search"
if printf '%s\n' "$media_page" | rg -q -F 'CurrentMediaSourceLabel'; then
  fail "media page must not expose Alist or WebDAV source selection"
fi
media_mode_switch="$(sed -n '/private MediaModeSwitch()/,/^  }/p' "$SOURCE")"
printf '%s\n' "$media_mode_switch" | rg -q -F "AppSegmentItem({ label: '图片'" || fail "media header needs gallery mode"
printf '%s\n' "$media_mode_switch" | rg -q -F "AppSegmentItem({ label: '视频'" || fail "media header needs video mode"
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
printf '%s\n' "$hero_panel" | rg -q -F 'AppPrimaryPillButton({ label: action' ||
  fail "hero action must use the shared primary button"

tool_entry="$(sed -n '/private ToolEntryRow(/,/^  }/p' "$SOURCE")"
printf '%s\n' "$tool_entry" | rg -q -F 'AppSettingsRow({' ||
  fail "tool entries must use the shared settings row"
rg -q -F 'export struct AppSettingsRow' "$CONTROLS" || fail "shared settings row is missing"
settings_row="$(sed -n '/export struct AppSettingsRow/,/^}/p' "$CONTROLS")"
printf '%s\n' "$settings_row" | rg -q -F 'Button({ type: ButtonType.Normal }) {' ||
  fail "shared settings rows must be focusable buttons"
printf '%s\n' "$settings_row" | rg -q -F '.constraintSize({ minHeight: 68 })' ||
  fail "shared settings rows must meet the minimum touch target"
printf '%s\n' "$settings_row" | rg -q -F '.borderRadius(0)' ||
  fail "unselected settings rows must not retain a pill background"
printf '%s\n' "$settings_row" | rg -q -F 'color: AppTheme.divider, radius: 0' ||
  fail "settings row dividers must stay square instead of curving into a pill"
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
  printf '%s\n' "$tool_file_operation" | rg -q "(Button|AppDangerPillButton).*'$operation'" ||
    fail "remote file $operation action must remain available"
done
printf '%s\n' "$tool_file_operation" | rg -q -F "AppDangerPillButton({ label: this.toolSelectedDirectory ? '确认删除目录' : '确认删除文件'" ||
  fail "remote deletion confirmation must name the target kind"
for shared_control in AppPrimaryPillButton AppSecondaryPillButton AppDangerPillButton; do
  rg -q -F "export struct $shared_control" "$CONTROLS" ||
    fail "shared control $shared_control is missing"
done

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
printf '%s\n' "$tab_bar" | rg -q -F '.height(60)' || fail "compact tab targets must remain at least 48 vp"
printf '%s\n' "$tab_bar" | rg -q -F '.accessibilityDescription(`主导航第${index + 1}项，共${this.tabs.length}项`)' ||
  fail "tab bar must expose navigation position"

rg -q -F 'private readonly tabScrollers: Scroller[]' "$SOURCE" || fail "tabs need independent scrollers"
rg -q -F 'Scroll(this.tabScrollers[index])' "$SOURCE" || fail "tab content must bind its own scroller"
standard_tab_page="$(sed -n '/private StandardTabPage(/,/private PageHeader(/p' "$SOURCE")"
printf '%s\n' "$standard_tab_page" | rg -q -F ".height('100%')" ||
  fail "short tab content must reserve the viewport height and remain top-aligned"
tabs_scroll="$(sed -n '/Scroll(this.tabScrollers\[index\])/,/.scrollBar(BarState.Off)/p' "$SOURCE")"
printf '%s\n' "$tabs_scroll" | rg -q -F ".width('100%')" ||
  fail "tab scroll must fill width so short states stay top-aligned"
printf '%s\n' "$tabs_scroll" | rg -q -F '.layoutWeight(1)' ||
  fail "tab scroll must fill the remaining height so short states stay top-aligned"
printf '%s\n' "$tabs_scroll" | rg -q -F '.align(Alignment.TopStart)' ||
  fail "tab scroll must align short states to the top"
printf '%s\n' "$tabs_scroll" | rg -q -F '.edgeEffect(EdgeEffect.None)' ||
  fail "tab scroll must reject picker gesture overscroll"
printf '%s\n' "$media_page" | rg -q -F '.enableScrollInteraction(this.mediaSelectorMode.length === 0 &&' ||
  fail "media page scroll must pause while a wheel selector owns the gesture"
printf '%s\n' "$media_page" | rg -q -F '!this.mediaCatalogDirectorySelectorVisible && !this.mediaCatalogTagSelectorVisible)' ||
  fail "media page scroll must pause while the catalog selector owns the gesture"
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
rg -q -F "AppSecondaryPillButton({ label: '继续同步'" "$SOURCE" ||
  fail "logout risk must offer synchronization"
rg -q -F "AppDangerPillButton({ label: '仍要退出'" "$SOURCE" ||
  fail "logout risk must require explicit confirmation"

for forbidden in "Text('新建')" "Text('上传')" "Text('测试连接')" "Text('关闭')" "Text('完成')"; do
  if rg -q -F "$forbidden" "$SOURCE"; then
    fail "$forbidden must not be used as a compact click target"
  fi
done

echo 'UI design policy tests passed'
