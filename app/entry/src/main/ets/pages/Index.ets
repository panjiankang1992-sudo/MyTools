import { AppTheme } from '../theme/AppTheme';
import { AppFilterChip, AppGlassTextInput, AppPrimaryPillButton, AppSegmentItem } from '../components/AppControls';
import { AuthSessionManager } from '../features/auth/AuthSessionManager';
import common from '@ohos.app.ability.common';
import window from '@ohos.window';
import preferences from '@ohos.data.preferences';
import { RemoteMediaApi } from '../features/media/RemoteMediaApi';
import { RemoteMediaFilterOptions, RemoteMediaItem, RemoteMediaSource, RemoteMediaTag, RemotePlaybackDescriptor } from
  '../features/media/RemoteMediaModels';
import { RemoteMediaPlayer } from '../features/media/RemoteMediaPlayer';
import { RemoteSubtitleCue, RemoteSubtitleParser } from '../features/media/RemoteSubtitleParser';
import { RemoteSubtitlePolicy } from '../features/media/RemoteSubtitlePolicy';
import { RemoteImageGesturePolicy } from '../features/media/RemoteImageGesturePolicy';
import { RemoteAudioQueuePolicy } from '../features/media/RemoteAudioQueuePolicy';
import { RemoteMediaPreloadPolicy } from '../features/media/RemoteMediaPreloadPolicy';
import { RemoteMediaDirectoryGroup, RemoteMediaGroupingPolicy } from
  '../features/media/RemoteMediaGroupingPolicy';
import { RemoteMediaPickerSelectionPolicy } from '../features/media/RemoteMediaPickerSelectionPolicy';
import { RemoteMediaLoadResult, RemoteMediaLoadResultPolicy } from
  '../features/media/RemoteMediaLoadResultPolicy';
import { media } from '@kit.MediaKit';
import picker from '@ohos.file.picker';
import fs from '@ohos.file.fs';
import {
  Book, BookSource, BookSourceAlternative, BookSourceExploreCategory, BookSourceSearchResult, BookSourceTombstone,
  ComicDirection, ComicFitMode, ComicPageMode,
  PageTurnMode, ReaderBlock, ReaderChapter, ReaderDocument, ReadingProgress, ReadingProgressTombstone, ReaderSettings,
  ReaderAnnotation, ReaderBookmark, ReaderFontFamily, ReaderMarkerTombstone, ReaderOrientation, ReadingStatistic,
  ReaderTheme,
  ShelfBookTombstone,
  SourceChapterContent,
  bookNameFromUri,
  defaultReaderSettings, resolveBookFormat
} from '../features/reader/ReaderModels';
import { BookSourceImporter } from '../features/reader/BookSourceImporter';
import { BookSourceSearchEngine } from '../features/reader/BookSourceSearchEngine';
import { BookSourceReaderEngine } from '../features/reader/BookSourceReaderEngine';
import { BookSourceCredentialStore } from '../features/reader/BookSourceCredentialStore';
import { LocalBookPicker } from '../features/reader/LocalBookPicker';
import { RemoteBookImportPolicy } from '../features/reader/RemoteBookImportPolicy';
import { RemoteBookDisplayMetadataPolicy } from '../features/reader/RemoteBookDisplayMetadataPolicy';
import { ReaderRepository, ReaderSnapshot } from '../features/reader/ReaderRepository';
import { ReaderProgressApi } from '../features/reader/ReaderProgressApi';
import { ReaderMarkerApi, ReaderMarkerSyncItem, ReaderMarkerSyncResult } from
  '../features/reader/ReaderMarkerApi';
import { ShelfSyncApi } from '../features/reader/ShelfSyncApi';
import { ShelfSyncItem, ShelfSyncResult } from '../features/reader/ShelfSyncModels';
import { BookSourceSyncApi, BookSourceSyncItem, BookSourceSyncResult } from
  '../features/reader/BookSourceSyncApi';
import { BookSourceDiscoveryApi, BookSourceDiscoveryTask } from
  '../features/reader/BookSourceDiscoveryApi';
import { BookSourceRuntimeSearchApi, BookSourceRuntimeSearchTask } from
  '../features/reader/BookSourceRuntimeSearchApi';
import { BookSourceRuntimeReaderApi } from '../features/reader/BookSourceRuntimeReaderApi';
import { BookSourceLocalCache } from '../features/reader/BookSourceLocalCache';
import { ReaderDataApi, ReaderDataSummary } from '../features/reader/ReaderDataApi';
import { IndexedTextSearchHit, ReaderContentLoader } from '../features/reader/ReaderContentLoader';
import { OpdsBookEntry, OpdsCatalog, OpdsNavigationEntry, OpdsProvider } from '../features/reader/OpdsProvider';
import { OpdsCredentialStore } from '../features/reader/OpdsCredentialStore';
import { DshApi } from '../features/dsh/DshApi';
import {
  DshConversationSnapshot as CopilotConversationSnapshot, DshMessage as CopilotMessage,
  DshRecoveryPlan as CopilotRecoveryPlan, DshRunStep as CopilotRunStep,
  DshTextAttachment as CopilotTextAttachment, DshTurnSnapshot as CopilotTurnSnapshot
} from '../features/dsh/DshModels';
import { DshConversationSnapshotPolicy as CopilotConversationSnapshotPolicy } from
  '../features/dsh/DshConversationSnapshotPolicy';
import { pdfService, pdfViewManager, PdfView } from '@kit.PDFKit';
import { SourceUrlPolicy } from '../shared/network/SourceUrlPolicy';
import { SafeRemoteCoverCache } from '../shared/network/SafeRemoteCoverCache';
import { FeedbackApi } from '../features/tools/FeedbackApi';
import { AppMarketApi, AppMarketDetail, AppMarketItem } from '../features/tools/AppMarketApi';
import { TextToolProcessor } from '../features/tools/TextToolProcessor';
import { DigestTool } from '../features/tools/DigestTool';
import { ArchiveCreateResult, ArchiveExtractedItem, ArchiveTool } from '../features/tools/ArchiveTool';
import { generateBarcode, scanBarcode, scanCore } from '@kit.ScanKit';
import { image } from '@kit.ImageKit';
import { util } from '@kit.ArkTS';
import { MediaPlaybackHistoryPolicy, MediaPlaybackProgress } from
  '../features/media/MediaPlaybackHistoryPolicy';
import { DownloadHistoryItem, DownloadHistoryPolicy } from '../features/media/DownloadHistoryPolicy';
import { RemoteMediaThumbnailCache } from '../features/media/RemoteMediaThumbnailCache';
import { RemoteMediaThumbnailRestorePolicy } from '../features/media/RemoteMediaThumbnailRestorePolicy';
import { ReaderPaginationPolicy } from '../features/reader/ReaderPaginationPolicy';
import { ReaderTextLocatorPolicy } from '../features/reader/ReaderTextLocatorPolicy';
import { ReaderSpeechPolicy } from '../features/reader/ReaderSpeechPolicy';
import { ReaderSpeechController } from '../features/reader/ReaderSpeechController';
import { ReaderViewport, ReaderViewportPolicy } from '../features/reader/ReaderViewportPolicy';
import { ReaderTextScalePolicy } from '../features/reader/ReaderTextScalePolicy';
import { EbookImportApi, EbookImportTask } from '../features/reader/EbookImportApi';
import systemShare from '@hms.collaboration.systemShare';
import uniformTypeDescriptor from '@ohos.data.uniformTypeDescriptor';
import { DownloadCancellationToken } from '../shared/network/DownloadCancellationToken';
import { MediaPlaybackRecoveryPolicy } from '../features/media/MediaPlaybackRecoveryPolicy';
import { MediaSeekPolicy } from '../features/media/MediaSeekPolicy';
import { LogoutRiskPolicy, LogoutRiskSnapshot } from '../features/profile/LogoutRiskPolicy';
import { ProfileApi } from '../features/profile/ProfileApi';
import { EndpointRouter } from '../features/connectivity/EndpointRouter';
import connection from '@ohos.net.connection';
import { webview } from '@kit.ArkWeb';
import { DriveApi } from '../features/drive/DriveApi';
import { DriveAccount, DriveBreadcrumb, DriveDirectory, DriveItem, DriveOpenDescriptor } from
  '../features/drive/DriveModels';
import { MediaCatalogApi } from '../features/media/MediaCatalogApi';
import {
  MediaCatalogFilters, MediaCatalogItem, MediaDirectoryFilter, MediaStoryboardFrame, MediaTagFilter, MediaVideoDetail,
  MediaVideoDirectory
} from '../features/media/MediaCatalogModels';

interface TabItem {
  title: string;
}

interface ReaderPagePosition {
  current: number;
  total: number;
}

interface MediaFavorite {
  owner: string;
  accountId: string;
  name: string;
  path: string;
  kind: string;
  contentType: string;
  size: number;
  lastModified: string;
  addedAt: number;
}

interface MediaThumbnailEntry {
  path: string;
  uri: string;
}

interface MediaThumbnailLoadResult {
  path: string;
  uri: string;
  failed: boolean;
}

interface RemoteBookCoverEntry {
  id: string;
  uri: string;
}

interface RemoteBookProgressEntry {
  sourceId: string;
  path: string;
  bookId: string;
  percentage: number;
  chapterTitle: string;
  updatedAt: number;
}

type CopilotConversation = CopilotConversationSnapshot;

interface CopilotPreviewPolicy {
  owner: string;
  enabled: boolean;
}

@Entry
@Component
struct Index {
  private readonly serviceUrl: string = 'https://mytools.yuyutian.top';
  private readonly copilotConversationSnapshotPolicy: CopilotConversationSnapshotPolicy =
    new CopilotConversationSnapshotPolicy();
  @State currentIndex: number = 0;
  @State profileVisible: boolean = false;
  @State activeServiceUrl: string = 'https://mytools.yuyutian.top';
  @State lanModeActive: boolean = false;
  private lanDiscoveryRevision: number = 0;
  @State bookSourceManagementVisible: boolean = false;
  @State showLogin: boolean = false;
  @State account: string = '';
  @State password: string = '';
  @State authStatus: string = '';
  @State connectionStatus: string = '';
  @State connectionHealthy: boolean = false;
  @State authInProgress: boolean = false;
  @State authenticated: boolean = false;
  @State profileName: string = '';
  @State profileUsername: string = '';
  @State profileRole: string = '';
  @State profileAvatar: string = '';
  @State profileAvatarPixelMap?: image.PixelMap = undefined;
  @State profileAvatarFailed: boolean = false;
  @State profileMode: string = 'home';
  @State profileCacheStatus: string = '';
  @State profileSettingsRevision: number = 0;
  @State profileCoverCacheBytes: number = 0;
  @State profileComicCacheBytes: number = 0;
  @State profileMediaCacheBytes: number = 0;
  @State toolMode: string = 'home';
  @State toolTextTitle: string = '文本处理';
  @State toolSearchText: string = '';
  @State recentToolIds: string[] = [];
  @State toolTextInput: string = '';
  @State toolTextOutput: string = '';
  @State digestInput: string = '';
  @State digestOutput: string = '';
  @State digestAlgorithm: string = 'SHA256';
  @State digestBusy: boolean = false;
  @State digestStatus: string = '';
  @State archiveBusy: boolean = false;
  @State archiveStatus: string = '';
  @State archiveExtractedItems: ArchiveExtractedItem[] = [];
  @State feedbackName: string = '';
  @State feedbackEmail: string = '';
  @State feedbackCategory: string = '功能建议';
  @State feedbackTitle: string = '';
  @State feedbackContent: string = '';
  @State feedbackStatus: string = '';
  @State feedbackSubmitting: boolean = false;
  @State feedbackReceiptId: string = '';
  @State marketItems: AppMarketItem[] = [];
  @State marketQuery: string = '';
  @State marketPage: number = 1;
  @State marketTotal: number = 0;
  @State marketLoading: boolean = false;
  @State marketStatus: string = '';
  @State marketDetail?: AppMarketDetail = undefined;
  @State qrInput: string = '';
  @State qrResult: string = '';
  @State qrStatus: string = '';
  @State qrBusy: boolean = false;
  @State qrPixelMap?: image.PixelMap = undefined;
  @State downloadHistory: DownloadHistoryItem[] = [];
  @State toolFiles: RemoteMediaItem[] = [];
  @State toolFilePath: string = '/';
  @State toolStatus: string = '';
  @State toolLoading: boolean = false;
  @State toolSelectedPath: string = '';
  @State toolSelectedName: string = '';
  @State toolSelectedDirectory: boolean = false;
  @State toolOperation: string = '';
  @State toolOperationInput: string = '';
  @State toolOperationBusy: boolean = false;
  @State toolUploadProgress: number = 0;
  @State mediaSources: RemoteMediaSource[] = [];
  @State mediaCatalogMode: string = 'gallery';
  @State mediaCatalogFilters: MediaCatalogFilters = { directories: [], tags: [] };
  @State mediaCatalogItems: MediaCatalogItem[] = [];
  @State mediaVideoDirectories: MediaVideoDirectory[] = [];
  @State mediaVideoDirectoryItems: MediaCatalogItem[] = [];
  @State mediaCatalogDirectoryId: string = '';
  @State mediaCatalogDirectoryParentId: string = '';
  @State mediaCatalogTag: string = '';
  @State mediaCatalogKeyword: string = '';
  @State mediaCatalogTotal: number = 0;
  @State mediaCatalogPage: number = 1;
  @State mediaCatalogLoading: boolean = false;
  @State mediaCatalogRefreshing: boolean = false;
  @State mediaCatalogStatus: string = '';
  @State hideAdultContent: boolean = false;
  @State mediaCatalogDirectorySelectorVisible: boolean = false;
  @State mediaCatalogTagSelectorVisible: boolean = false;
  @State mediaCatalogSelectorQuery: string = '';
  @State selectedVideoDirectoryId: string = '';
  @State selectedVideoDirectoryName: string = '';
  @State mediaVideoDirectoryListOffset: number = 0;
  @State mediaVideoDetail?: MediaVideoDetail = undefined;
  @State mediaVideoDetailVisible: boolean = false;
  @State mediaVideoDescriptionExpanded: boolean = false;
  @State mediaStoryboardUris: string[] = [];
  @State driveAccounts: DriveAccount[] = [];
  @State selectedDriveId: string = '';
  @State driveDirectory?: DriveDirectory = undefined;
  @State driveBreadcrumbs: DriveBreadcrumb[] = [];
  @State driveKeyword: string = '';
  @State driveSort: string = 'modified';
  @State driveDirection: string = 'desc';
  @State driveLoading: boolean = false;
  @State driveStatus: string = '';
  @State driveSelectorVisible: boolean = false;
  @State mediaItems: RemoteMediaItem[] = [];
  @State selectedMediaSourceId: string = '';
  @State mediaPath: string = '/';
  @State mediaStatus: string = '';
  @State mediaSearchText: string = '';
  @State mediaSearchQuery: string = '';
  @State mediaKindFilter: string = 'all';
  @State mediaDirectoryGroupFilter: string = 'all';
  @State mediaSelectedTags: string[] = [];
  @State mediaRequireAllTags: boolean = false;
  @State pendingMediaSelectedTags: string[] = [];
  @State pendingMediaRequireAllTags: boolean = false;
  @State mediaSelectorMode: string = '';
  @State mediaPickerSelectedIndex: number = 0;
  @State mediaPickerPendingValue: string = '';
  @State mediaTagSelectorQuery: string = '';
  @State mediaDirectorySelectorQuery: string = '';
  @State mediaDirectoryFilterOptions: string[] = [];
  @State mediaTagFilterOptions: string[] = [];
  @State mediaActionPath: string = '';
  @State mediaActionMode: string = '';
  @State mediaActionInput: string = '';
  @State mediaActionError: string = '';
  @State mediaActionBusy: boolean = false;
  @State mediaLoading: boolean = false;
  @State mediaDirectoryLoading: boolean = false;
  @State mediaLoadingMore: boolean = false;
  @State remoteBookLoading: boolean = false;
  @State opdsLoading: boolean = false;
  @State mediaFavoritesOnly: boolean = false;
  @State mediaFavorites: MediaFavorite[] = [];
  @State mediaViewerVisible: boolean = false;
  @State mediaViewerChromeVisible: boolean = true;
  @State mediaViewerDragOffsetY: number = 0;
  @State mediaViewerDragging: boolean = false;
  @State currentMediaName: string = '';
  @State currentMediaKind: string = '';
  @State currentMediaUrl: string = '';
  @State currentMediaPreviewUri: string = '';
  @State currentMediaPath: string = '';
  private currentMediaContentType: string = '';
  private currentMediaSize: number = 0;
  private currentMediaLastModified: string = '';
  @State imageScale: number = 1;
  @State imageOffsetX: number = 0;
  @State imageOffsetY: number = 0;
  @State currentMediaResumeMs: number = 0;
  @State playerState: media.AVPlayerState = 'idle';
  @State playerTimeMs: number = 0;
  @State playerDurationMs: number = 0;
  @State mediaPlaybackRate: number = 1;
  @State audioLoopEnabled: boolean = false;
  @State audioOutputDevice: string = '系统默认输出';
  @State audioSleepMinutes: number = 0;
  @State audioQueueExpanded: boolean = false;
  @State videoStatus: string = '';
  @State videoFullscreen: boolean = false;
  @State mediaSubtitleText: string = '';
  @State mediaSubtitleStatus: string = '';
  @State mediaSubtitleEnabled: boolean = true;
  @State mediaTransferSpeed: number = 0;
  @State mediaBufferState: string = '';
  @State mediaPlaybackError: string = '';
  @State mediaOpening: boolean = false;
  @State mediaImageDeleteConfirm: boolean = false;
  @State mediaImageDeleting: boolean = false;
  @State mediaImageSharing: boolean = false;
  @State mediaThumbnailItems: RemoteMediaItem[] = [];
  @State mediaThumbnailEntries: MediaThumbnailEntry[] = [];
  @State mediaBrowseThumbnailEntries: MediaThumbnailEntry[] = [];
  @State mediaBrowseThumbnailFailedPaths: string[] = [];
  private mediaPlaybackProgress: MediaPlaybackProgress[] = [];
  @State bookModeIndex: number = 0;
  @State books: Book[] = [];
  private shelfBookTombstones: ShelfBookTombstone[] = [];
  @State shelfSearchText: string = '';
  @State bookSources: BookSource[] = [];
  private bookSourceTombstones: BookSourceTombstone[] = [];
  @State sourceSearchText: string = '';
  @State sourceSearchMode: string = 'FUZZY';
  @State sourceSearchModeVisible: boolean = false;
  @State sourceSearchPageVisible: boolean = false;
  private sourceSearchTaskId: string = '';
  @State sourceManageText: string = '';
  @State sourceManageFilter: string = 'all';
  @State sourceManageExpandedUrl: string = '';
  @State sourceManageToolsVisible: boolean = false;
  @State sourceManageFilterVisible: boolean = false;
  @State sourceSearchResults: BookSourceSearchResult[] = [];
  @State sourceSearchVisibleCount: number = 20;
  @State sourceManageVisibleCount: number = 30;
  private readonly sourceSearchRenderBatchSize: number = 20;
  private readonly sourceManageRenderBatchSize: number = 30;
  @State sourceSearchLoading: boolean = false;
  @State sourceSearchActiveKeyword: string = '';
  @State sourceSearchPage: number = 0;
  @State sourceSearchHasMore: boolean = false;
  @State sourceExploreSourceUrl: string = '';
  @State sourceExploreCategories: BookSourceExploreCategory[] = [];
  @State sourceExploreActiveSourceUrl: string = '';
  @State sourceExploreActiveUrl: string = '';
  @State sourceExploreActiveTitle: string = '';
  @State sourceExplorePage: number = 0;
  @State sourceExploreHasMore: boolean = false;
  @State sourceHealthLoading: boolean = false;
  @State sourceDiscoveryUrl: string = '';
  @State sourceDiscoveryBusy: boolean = false;
  @State sourceDiscoveryStatus: string = '';
  @State sourceDeleteUrl: string = '';
  @State sourceDeleteName: string = '';
  @State sourceCredentialUrl: string = '';
  @State sourceCredentialName: string = '';
  @State sourceCredentialHeader: string = 'Authorization';
  @State sourceCredentialValue: string = '';
  @State sourceCredentialStatus: string = '';
  @State sourceCredentialBusy: boolean = false;
  @State bookStatus: string = '';
  @State ebookTransferBusy: boolean = false;
  @State ebookTransferProgress: number = 0;
  @State ebookTransferStatus: string = '';
  @State remoteBookSources: RemoteMediaSource[] = [];
  @State selectedRemoteBookSourceId: string = '';
  @State remoteBookSourceSelectorVisible: boolean = false;
  @State remoteBookSourcePickerIndex: number = 0;
  @State remoteBookItems: RemoteMediaItem[] = [];
  @State remoteBookCoverEntries: RemoteBookCoverEntry[] = [];
  @State remoteBookProgressEntries: RemoteBookProgressEntry[] = [];
  @State remoteBookPath: string = '/';
  @State remoteBookSearchText: string = '';
  private remoteBookSearchTimerId: number = -1;
  @State remoteBookPage: number = 1;
  @State remoteBookTotal: number = 0;
  @State remoteBookLoadedCount: number = 0;
  @State remoteBookHasMore: boolean = false;
  @State remoteBookLoadingMore: boolean = false;
  @State remoteLibraryMode: string = 'mytools';
  @State opdsUrl: string = '';
  @State opdsTitle: string = 'OPDS书库';
  @State opdsBooks: OpdsBookEntry[] = [];
  @State opdsNavigation: OpdsNavigationEntry[] = [];
  @State opdsCredentialVisible: boolean = false;
  @State opdsUsername: string = '';
  @State opdsPassword: string = '';
  @State opdsCredentialStatus: string = '';
  @State opdsCredentialBusy: boolean = false;
  @State readingProgress: ReadingProgress[] = [];
  private readingProgressTombstones: ReadingProgressTombstone[] = [];
  @State readerBookmarks: ReaderBookmark[] = [];
  @State readerAnnotations: ReaderAnnotation[] = [];
  private readerMarkerTombstones: ReaderMarkerTombstone[] = [];
  @State readingStatistics: ReadingStatistic[] = [];
  @State readerSyncStatus: string = '未登录 · 数据保存在本机';
  @State readerDataStatus: string = '';
  @State readerDataDeleting: boolean = false;
  @State readerDataDeleteConfirm: boolean = false;
  @State readerDataSummary?: ReaderDataSummary = undefined;
  @State readerAnnotationVisible: boolean = false;
  @State readerAnnotationInput: string = '';
  @State readerSettings: ReaderSettings = defaultReaderSettings();
  @State readerVisible: boolean = false;
  @State readerShelfPromptVisible: boolean = false;
  @State readerControlsVisible: boolean = false;
  @State readerSettingsVisible: boolean = false;
  @State readerSearchVisible: boolean = false;
  @State readerMoreVisible: boolean = false;
  @State readerCatalogVisible: boolean = false;
  @State readerCatalogMode: string = 'catalog';
  @State readerSearchText: string = '';
  @State readerSearchResults: IndexedTextSearchHit[] = [];
  @State readerSearchBusy: boolean = false;
  @State readerSearchStatus: string = '';
  private readerSearchRevision: number = 0;
  @State bookDetailVisible: boolean = false;
  @State detailBook?: Book = undefined;
  @State detailRemoveConfirmVisible: boolean = false;
  @State detailOperationBusy: boolean = false;
  @State detailOperationStatus: string = '';
  @State detailIntro: string = '';
  @State detailLastChapter: string = '';
  @State detailSourceName: string = '';
  @State detailSourceStatus: string = '待验证';
  @State detailSourceLatencyMs: number = 0;
  @State detailChapterCount: number = 0;
  @State detailSourceCandidates: BookSourceSearchResult[] = [];
  @State detailRemoteTags: string[] = [];
  @State detailRemoteSize: number = 0;
  @State detailRemoteModified: string = '';
  @State detailShowAllSources: boolean = false;
  @State detailShelfBookId: string = '';
  @State currentBook?: Book = undefined;
  @State readerContent: string = '';
  @State readerChapters: ReaderChapter[] = [];
  @State readerChapterIndex: number = 0;
  @State comicImageUris: string[] = [];
  @State sourceComicFailedIndexes: number[] = [];
  @State comicPageIndex: number = 0;
  @State pdfMode: boolean = false;
  @State pdfPageIndex: number = 0;
  @State pdfPageCount: number = 0;
  @State readerStatus: string = '';
  @State readerInfo: string = '';
  @State readerScrollValue: number = 0;
  @State readerScrollEpoch: number = 0;
  @State readerContinuousStartIndex: number = 0;
  @State readerContinuousEndIndex: number = 0;
  private readerScrollDirection: number = 0;
  private readerLastScrollAt: number = 0;
  private readerScrollChapterLoading: boolean = false;
  private readerScrollContinuationBlockedUntil: number = 0;
  @State readerTextPages: ReaderBlock[][] = [];
  @State readerTextPageIndex: number = 0;
  @State readerViewportWidth: number = 360;
  @State readerViewportHeight: number = 700;
  @StorageProp('systemFontScale') @Watch('OnSystemFontScaleChanged') systemFontScale: number = 1;
  @State readerSpeechActive: boolean = false;
  @State readerSpeechStatus: string = '';
  @State readerSelectedText: string = '';
  private readerSelectionBookId: string = '';
  private readerSelectionChapterTitle: string = '';
  @State copilotInitialized: boolean = false;
  @State copilotVersion: string = '';
  @State copilotGatewayModel: string = '';
  @State copilotSettingsVisible: boolean = false;
  @State copilotPersistPreview: boolean = true;
  @State copilotInput: string = '';
  @State copilotAttachment?: CopilotTextAttachment = undefined;
  @State copilotAttachmentStatus: string = '';
  @State copilotStatus: string = '';
  @State copilotBusy: boolean = false;
  @State copilotPollingTimedOut: boolean = false;
  @State copilotCancelling: boolean = false;
  @State copilotMessages: CopilotMessage[] = [];
  @State copilotConversations: CopilotConversation[] = [];
  @State activeCopilotConversationId: string = '';
  @State copilotDeleteConfirm: boolean = false;
  @State logoutConfirmVisible: boolean = false;
  @State copilotTurn?: CopilotTurnSnapshot = undefined;
  @State copilotRunSteps: CopilotRunStep[] = [];
  @State copilotRecoveryPlan?: CopilotRecoveryPlan = undefined;
  private readonly authManager: AuthSessionManager = new AuthSessionManager();
  private readonly audioPlayer: RemoteMediaPlayer = new RemoteMediaPlayer();
  private readonly readerSpeechPolicy: ReaderSpeechPolicy = new ReaderSpeechPolicy();
  private readonly readerSpeechController: ReaderSpeechController = new ReaderSpeechController();
  private readonly readerViewportPolicy: ReaderViewportPolicy = new ReaderViewportPolicy();
  private readonly readerTextScalePolicy: ReaderTextScalePolicy = new ReaderTextScalePolicy();
  private readerViewportTimer: number = -1;
  private readonly opdsProvider: OpdsProvider = new OpdsProvider();
  private readonly opdsCredentialStore: OpdsCredentialStore = new OpdsCredentialStore();
  private readerSessionStartedAt: number = 0;
  private readonly videoController: VideoController = new VideoController();
  private readonly subtitleParser: RemoteSubtitleParser = new RemoteSubtitleParser();
  private readonly subtitlePolicy: RemoteSubtitlePolicy = new RemoteSubtitlePolicy();
  private readonly imageGesturePolicy: RemoteImageGesturePolicy = new RemoteImageGesturePolicy();
  private readonly audioQueuePolicy: RemoteAudioQueuePolicy = new RemoteAudioQueuePolicy();
  private readonly mediaHistoryPolicy: MediaPlaybackHistoryPolicy = new MediaPlaybackHistoryPolicy();
  private readonly mediaRecoveryPolicy: MediaPlaybackRecoveryPolicy = new MediaPlaybackRecoveryPolicy();
  private readonly mediaSeekPolicy: MediaSeekPolicy = new MediaSeekPolicy();
  private readonly mediaPreloadPolicy: RemoteMediaPreloadPolicy = new RemoteMediaPreloadPolicy();
  private readonly mediaGroupingPolicy: RemoteMediaGroupingPolicy = new RemoteMediaGroupingPolicy();
  private readonly mediaPickerSelectionPolicy: RemoteMediaPickerSelectionPolicy =
    new RemoteMediaPickerSelectionPolicy();
  private readonly mediaLoadResultPolicy: RemoteMediaLoadResultPolicy = new RemoteMediaLoadResultPolicy();
  private readonly downloadHistoryPolicy: DownloadHistoryPolicy = new DownloadHistoryPolicy();
  private readonly logoutRiskPolicy: LogoutRiskPolicy = new LogoutRiskPolicy();
  private readonly mediaThumbnailCache: RemoteMediaThumbnailCache = new RemoteMediaThumbnailCache();
  private readonly mediaThumbnailRestorePolicy: RemoteMediaThumbnailRestorePolicy =
    new RemoteMediaThumbnailRestorePolicy();
  private readonly readerPaginationPolicy: ReaderPaginationPolicy = new ReaderPaginationPolicy();
  private mediaDirectoryItems: RemoteMediaItem[] = [];
  private mediaSubtitleCues: RemoteSubtitleCue[] = [];
  @State mediaSubtitleTracks: RemoteMediaItem[] = [];
  private mediaSubtitlePath: string = '';
  private mediaSubtitleRevision: number = 0;
  private imageGestureStartScale: number = 1;
  private imageGestureStartX: number = 0;
  private imageGestureStartY: number = 0;
  private queuedImageNavigationDirection: number = 0;
  private queuedAudioNavigationDirection: number = 0;
  private readonly bookSourceImporter: BookSourceImporter = new BookSourceImporter();
  private readonly bookSourceCredentialStore: BookSourceCredentialStore = new BookSourceCredentialStore();
  private readonly bookSourceSearchEngine: BookSourceSearchEngine =
    new BookSourceSearchEngine(this.bookSourceCredentialStore);
  private readonly remoteCoverCache: SafeRemoteCoverCache = new SafeRemoteCoverCache();
  private readonly bookSourceReaderEngine: BookSourceReaderEngine =
    new BookSourceReaderEngine(this.remoteCoverCache, this.bookSourceCredentialStore);
  private bookSourceOperationRevision: number = 0;
  private sourceChapterLoadRevision: number = 0;
  private readerOpenRevision: number = 0;
  private readerChapterPrefetchRevision: number = 0;
  private readonly readerChapterPrefetchCount: number = 20;
  private sourceComicRemoteUrls: string[] = [];
  private readonly localBookPicker: LocalBookPicker = new LocalBookPicker();
  private readonly remoteBookImportPolicy: RemoteBookImportPolicy = new RemoteBookImportPolicy();
  private readonly remoteBookDisplayMetadataPolicy: RemoteBookDisplayMetadataPolicy =
    new RemoteBookDisplayMetadataPolicy();
  private readonly readerRepository: ReaderRepository = new ReaderRepository();
  private readonly bookSourceLocalCache: BookSourceLocalCache = new BookSourceLocalCache();
  private readerContentLoader: ReaderContentLoader = new ReaderContentLoader();
  private readerOpeningLoader?: ReaderContentLoader = undefined;
  private activeComicPageCancellation?: DownloadCancellationToken = undefined;
  private activeSourceChapterCancellation?: DownloadCancellationToken = undefined;
  private activeReaderChapterPrefetchCancellation?: DownloadCancellationToken = undefined;
  private activeSourceCatalogCancellation?: DownloadCancellationToken = undefined;
  private activeOpdsCatalogCancellation?: DownloadCancellationToken = undefined;
  private activeBookSourceSearchCancellation?: DownloadCancellationToken = undefined;
  private activeBookSourceHealthCancellation?: DownloadCancellationToken = undefined;
  private activeMediaSourceCancellation?: DownloadCancellationToken = undefined;
  private activeDriveCancellation?: DownloadCancellationToken = undefined;
  private activeMediaCatalogCancellation?: DownloadCancellationToken = undefined;
  private activeMediaCatalogThumbnailCancellation?: DownloadCancellationToken = undefined;
  private mediaCatalogThumbnailRevision: number = 0;
  private activeRemoteBookSourceCancellation?: DownloadCancellationToken = undefined;
  private activeMediaDirectoryCancellation?: DownloadCancellationToken = undefined;
  private activeMediaLoadMoreCancellation?: DownloadCancellationToken = undefined;
  private activeRemoteBookDirectoryCancellation?: DownloadCancellationToken = undefined;
  private activeRemoteBookCoverCancellation?: DownloadCancellationToken = undefined;
  private activeToolDirectoryCancellation?: DownloadCancellationToken = undefined;
  private activeMediaOpenCancellation?: DownloadCancellationToken = undefined;
  private activeImageUpgradeCancellation?: DownloadCancellationToken = undefined;
  private activeBrowseThumbnailCancellation?: DownloadCancellationToken = undefined;
  private activeReaderTicketCancellation?: DownloadCancellationToken = undefined;
  private activeMediaMetricsCancellation?: DownloadCancellationToken = undefined;
  private activeMarketCancellation?: DownloadCancellationToken = undefined;
  private marketOperationRevision: number = 0;
  private activeProfileCancellation?: DownloadCancellationToken = undefined;
  private activeProgressPullCancellation?: DownloadCancellationToken = undefined;
  private activeMarkerPullCancellation?: DownloadCancellationToken = undefined;
  private activeShelfPullCancellation?: DownloadCancellationToken = undefined;
  private activeBookSourcePullCancellation?: DownloadCancellationToken = undefined;
  private activeReaderDataSummaryCancellation?: DownloadCancellationToken = undefined;
  private readerDataSummaryRevision: number = 0;
  private readerDataDeleteRevision: number = 0;
  private readonly sourceUrlPolicy: SourceUrlPolicy = new SourceUrlPolicy();
  private readonly textToolProcessor: TextToolProcessor = new TextToolProcessor();
  private readonly digestTool: DigestTool = new DigestTool();
  private readonly archiveTool: ArchiveTool = new ArchiveTool();
  private dshApi?: DshApi = undefined;
  private readonly dshPublicWebUrl: string = 'https://dsh-app.yuyutian.top';
  @State dshWebUrl: string = this.dshPublicWebUrl;
  private readonly dshWebController: webview.WebviewController = new webview.WebviewController();
  @State dshWebLoading: boolean = true;
  @State dshWebStatus: string = '正在打开 DSH';
  @State dshSidebarOverlayVisible: boolean = false;
  private localAccountScope: string = '';
  private readerStorageScope: string = 'device:v2:guest';
  private readonly pdfController: pdfViewManager.PdfController = new pdfViewManager.PdfController();
  private readerScroller: Scroller = new Scroller();
  private readonly mediaTagSelectorScroller: Scroller = new Scroller();
  private readonly mediaDirectorySelectorScroller: Scroller = new Scroller();
  private readonly mediaVideoDetailScroller: Scroller = new Scroller();
  private mediaTagSelectorOffset: number = 0;
  private mediaDirectorySelectorOffset: number = 0;
  private readonly tabScrollers: Scroller[] = [new Scroller(), new Scroller(), new Scroller(), new Scroller(),
    new Scroller()];
  private sourceSearchReturnOffset: number = 0;
  private sourceSearchScroller: Scroller = new Scroller();
  private sourceSearchReturnPending: boolean = false;
  private sourceSearchReturnRevision: number = 0;
  private sourceSearchReturnRestoreScheduled: boolean = false;
  private readerReturnToDetail: boolean = false;
  private detailReturnToReader: boolean = false;
  private settingsPreferences?: preferences.Preferences;
  private detailCatalog?: ReaderDocument;
  private detailCatalogBookId: string = '';
  private audioSleepTimerId: number = -1;
  private mediaMetricsTimerId: number = -1;
  private mediaSearchTimerId: number = -1;
  private readerPersistenceTimerId: number = -1;
  private readerProgressCommitTimerId: number = -1;
  private readerProgressSyncing: boolean = false;
  private readerProgressSyncError: string = '';
  private readerProgressPullPending: boolean = false;
  private pendingReaderProgress: ReadingProgress[] = [];
  private readerProgressQueueOwner: string = '';
  private readerProgressSyncGeneration: number = 0;
  private readerMarkerSyncing: boolean = false;
  private readerMarkerPullPending: boolean = false;
  private pendingReaderMarkers: ReaderMarkerSyncItem[] = [];
  private shelfSyncing: boolean = false;
  private shelfPullPending: boolean = false;
  private pendingShelfBooks: ShelfSyncItem[] = [];
  private bookSourceSyncing: boolean = false;
  private bookSourcePullPending: boolean = false;
  private pendingBookSources: BookSourceSyncItem[] = [];
  private lastMediaProgressPersistedAt: number = 0;
  private pendingMediaSourceId: string = '';
  private pendingMediaPath: string = '/';
  private currentMediaTicket: string = '';
  private lastMediaMetricBytes: number = 0;
  private lastMediaMetricAt: number = 0;
  private mediaMetricsPolling: boolean = false;
  private mediaOpenRevision: number = 0;
  private mediaOpeningPath: string = '';
  private mediaViewerChromeTimerId: number = -1;
  private copilotRefreshTimerId: number = -1;
  private copilotPollStartedAt: number = 0;
  private copilotOperationRevision: number = 0;
  private accountOperationRevision: number = 0;
  private networkChangeTimerId: number = -1;
  private networkConnection?: connection.NetConnection = undefined;
  private authenticationUiRevision: number = 0;
  private pageLifecycleRevision: number = 0;
  private toolDirectoryRevision: number = 0;
  private remoteBookDirectoryRevision: number = 0;
  private mediaDirectoryRevision: number = 0;
  private mediaLocalPage: number = 1;
  private mediaLocalTotal: number = 0;
  private mediaLocalHasMore: boolean = false;
  private mediaBrowseViewerSnapshotSourceId: string = '';
  private mediaBrowseViewerSnapshotEntries: MediaThumbnailEntry[] = [];
  private opdsNavigationRevision: number = 0;
  private opdsCredentialRevision: number = 0;
  private sourceManagementRevision: number = 0;
  private videoExpectedPlaying: boolean = false;
  private lastVideoProgressAt: number = 0;
  private lastDownloadProgressPublishedAt: number = 0;
  private activeDownloadCancellation?: DownloadCancellationToken = undefined;
  private activeSubtitleCancellation?: DownloadCancellationToken = undefined;
  private activeThumbnailCancellation?: DownloadCancellationToken = undefined;
  private activeImageShareCancellation?: DownloadCancellationToken = undefined;
  private activeMediaPreloadCancellation?: DownloadCancellationToken = undefined;
  private mediaPreloadRevision: number = 0;
  private readonly mediaPreloadTasks: Map<string, Promise<RemotePlaybackDescriptor | undefined>> =
    new Map<string, Promise<RemotePlaybackDescriptor | undefined>>();
  private readonly mediaPreloadDescriptors: Map<string, RemotePlaybackDescriptor> =
    new Map<string, RemotePlaybackDescriptor>();
  private activeDownloadOwner: string = '';
  private activeDownloadTaskId: string = '';

  private readonly tabs: TabItem[] = [
    { title: '阅读' },
    { title: '工具' },
    { title: 'DSH' },
    { title: '多媒体' },
    { title: '网盘' }
  ];

  /**
   * 恢复安全存储中的登录会话并初始化页面状态。
   */
  aboutToAppear(): void {
    this.authManager.onSessionInvalidated = () => this.HandleSessionInvalidated();
    this.authManager.onBusinessEndpointUnavailable = (baseUrl: string) => {
      if (baseUrl !== this.activeServiceUrl || !this.lanModeActive) return;
      this.lanDiscoveryRevision++;
      this.activeServiceUrl = this.serviceUrl;
      this.lanModeActive = false;
      this.ScheduleEndpointRediscovery();
    };
    this.audioPlayer.onStateChange = (state: media.AVPlayerState) => {
      this.playerState = state;
      if (state === 'completed') {
        this.ClearCurrentMediaProgress();
        if (!this.audioLoopEnabled) this.ChangeRemoteAudio(1);
      }
    };
    this.audioPlayer.onTimeUpdate = (timeMs: number) => {
      this.playerTimeMs = timeMs;
      this.UpdateMediaPlaybackProgress(timeMs, this.playerDurationMs);
    };
    this.audioPlayer.onDurationUpdate = (durationMs: number) => {
      this.playerDurationMs = durationMs;
    };
    this.audioPlayer.onOutputDeviceChange = (name: string) => {
      this.audioOutputDevice = name;
    };
    this.audioPlayer.onError = (message: string) => {
      this.UpdateMediaPlaybackProgress(this.playerTimeMs, this.playerDurationMs);
      this.PersistMediaPlaybackProgress();
      this.mediaPlaybackError = message;
      this.mediaBufferState = '播放失败';
    };
    this.audioPlayer.onPlayNext = () => this.ChangeRemoteAudio(1);
    this.audioPlayer.onPlayPrevious = () => this.ChangeRemoteAudio(-1);
    try {
      const hostContext = this.getUIContext().getHostContext();
      if (hostContext === undefined) {
        return;
      }
      const context = hostContext as common.UIAbilityContext;
      this.settingsPreferences = preferences.getPreferencesSync(context, { name: 'mytools_public_settings' });
      const storedAdultFilter = this.settingsPreferences.getSync('hide_adult_content', false);
      this.hideAdultContent = typeof storedAdultFilter === 'boolean' ? storedAdultFilter : false;
      const readerSnapshot = this.readerRepository.restore(context, 'device:v2:guest');
      this.ApplyReaderSnapshot(readerSnapshot);
      this.CacheShelfCovers(context);
      const storedOpdsUrl = this.settingsPreferences.getSync('opds_catalog_url', '');
      this.opdsUrl = typeof storedOpdsUrl === 'string' ? storedOpdsUrl : '';
      const storedMediaProgress = this.settingsPreferences.getSync('media_playback_progress', '');
      if (typeof storedMediaProgress === 'string' && storedMediaProgress.length > 0) {
        this.RestoreMediaPlaybackProgress(storedMediaProgress);
      }
      const storedMediaFavorites = this.settingsPreferences.getSync('media_favorites', '');
      if (typeof storedMediaFavorites === 'string' && storedMediaFavorites.length > 0) {
        this.RestoreMediaFavorites(storedMediaFavorites);
      }
      const storedDownloads = this.settingsPreferences.getSync('download_history', '');
      if (typeof storedDownloads === 'string' && storedDownloads.length > 0) {
        this.RestoreDownloadHistory(storedDownloads);
      }
      const storedRecentTools = this.settingsPreferences.getSync('recent_tool_ids', '');
      if (typeof storedRecentTools === 'string' && storedRecentTools.length > 0) {
        this.RestoreRecentToolIds(storedRecentTools);
      }
      const storedCopilotConversations = this.settingsPreferences.getSync('copilot_conversations', '');
      if (typeof storedCopilotConversations === 'string' && storedCopilotConversations.length > 0) {
        this.RestoreCopilotConversations(storedCopilotConversations);
      }
      this.StartNetworkMonitor();
      this.RestoreSession();
    } catch (error) {
      console.error('Failed to restore public settings');
    }
  }

  /**
   * 页面销毁时释放媒体播放器和 DSH 网络客户端。
   */
  aboutToDisappear(): void {
    this.pageLifecycleRevision++;
    this.authenticationUiRevision++;
    this.activeDownloadCancellation?.cancel();
    this.activeSubtitleCancellation?.cancel();
    this.activeBrowseThumbnailCancellation?.cancel();
    this.activeThumbnailCancellation?.cancel();
    this.activeImageShareCancellation?.cancel();
    this.readerOpeningLoader?.cancel();
    this.activeComicPageCancellation?.cancel();
    this.activeSourceChapterCancellation?.cancel();
    this.activeReaderChapterPrefetchCancellation?.cancel();
    this.activeSourceCatalogCancellation?.cancel();
    this.activeOpdsCatalogCancellation?.cancel();
    this.activeBookSourceSearchCancellation?.cancel();
    this.activeBookSourceHealthCancellation?.cancel();
    this.activeMediaSourceCancellation?.cancel();
    this.activeDriveCancellation?.cancel();
    this.activeMediaCatalogCancellation?.cancel();
    this.activeMediaCatalogThumbnailCancellation?.cancel();
    this.activeRemoteBookSourceCancellation?.cancel();
    this.activeMediaDirectoryCancellation?.cancel();
    this.activeMediaLoadMoreCancellation?.cancel();
    this.activeRemoteBookDirectoryCancellation?.cancel();
    this.activeRemoteBookCoverCancellation?.cancel();
    this.ClearRemoteBookSearchTimer();
    this.activeToolDirectoryCancellation?.cancel();
    this.activeMediaOpenCancellation?.cancel();
    this.activeImageUpgradeCancellation?.cancel();
    this.CancelMediaSearchTimer();
    this.ClearMediaPreloads();
    this.ClearMediaViewerChromeTimer();
    if (this.mediaViewerVisible) this.ApplyMediaViewerSystemBars(false);
    this.activeReaderTicketCancellation?.cancel();
    this.activeMarketCancellation?.cancel();
    this.activeProfileCancellation?.cancel();
    this.CancelReaderPulls();
    this.CancelReaderDataSummary();
    this.readerDataDeleteRevision++;
    this.InvalidateBookSourceOperation();
    this.ClearAudioSleepTimer();
    this.StopMediaMetrics();
    this.StopReaderPersistenceTimer();
    this.ClearScheduledReaderProgressSave();
    this.ClearReaderViewportTimer();
    this.readerSpeechController.stop();
    this.ApplyReaderOrientation('system');
    this.FinishReadingSession();
    this.PersistMediaPlaybackProgress();
    this.audioPlayer.release();
    this.copilotOperationRevision++;
    this.CancelCopilotRefresh();
    this.dshApi = undefined;
    this.archiveTool.cleanupExtraction();
    this.StopNetworkMonitor();
    this.ReleaseProfileAvatarPixelMap();
    this.qrPixelMap?.release();
  }

  /**
   * 应用进入后台时立即保存阅读和远程播放进度。
   */
  onPageHide(): void {
    if (this.readerVisible) {
      this.ClearScheduledReaderProgressSave();
      this.SaveCurrentProgress();
      this.FinishReadingSession();
      this.StopReaderSpeech();
    }
    this.StopReaderPersistenceTimer();
    if (this.mediaViewerVisible && this.currentMediaKind !== 'image') {
      this.UpdateMediaPlaybackProgress(this.playerTimeMs, this.playerDurationMs);
    }
    this.PersistMediaPlaybackProgress();
  }

  /**
   * 应用回到前台时恢复阅读时长统计和周期持久化。
   */
  onPageShow(): void {
    if (this.authenticated && !this.lanModeActive) this.DiscoverLanEndpoint();
    if (this.readerVisible && this.currentBook !== undefined) {
      if (this.readerSessionStartedAt <= 0) this.readerSessionStartedAt = Date.now();
      this.StartReaderPersistenceTimer();
    }
  }

  /**
   * 按视觉层级消费系统返回操作，避免直接退出时丢失阅读进度或媒体状态。
   */
  onBackPress(): boolean {
    if (this.readerVisible) {
      if (this.readerShelfPromptVisible) this.readerShelfPromptVisible = false;
      else if (this.readerAnnotationVisible) this.readerAnnotationVisible = false;
      else if (this.readerCatalogVisible) this.readerCatalogVisible = false;
      else if (this.readerSearchVisible) this.readerSearchVisible = false;
      else if (this.readerMoreVisible) this.readerMoreVisible = false;
      else if (this.readerSettingsVisible) this.readerSettingsVisible = false;
      else if (this.readerControlsVisible) this.readerControlsVisible = false;
      else this.CloseReader();
      return true;
    }
    if (this.bookDetailVisible) {
      if (this.detailRemoveConfirmVisible) this.detailRemoveConfirmVisible = false;
      else if (this.detailShowAllSources) this.detailShowAllSources = false;
      else this.CloseBookDetail();
      return true;
    }
    if (this.mediaViewerVisible) {
      if (this.videoFullscreen) this.videoFullscreen = false;
      else this.CloseRemoteMedia();
      return true;
    }
    if (this.mediaVideoDetailVisible) {
      this.CloseMediaVideoDetail();
      return true;
    }
    if (this.currentIndex === 3 && this.selectedVideoDirectoryId.length > 0) {
      this.LeaveMediaVideoDirectory();
      return true;
    }
    if (this.driveSelectorVisible) {
      this.driveSelectorVisible = false;
      return true;
    }
    if (this.currentIndex === 4 && this.driveBreadcrumbs.length > 0) {
      this.LeaveDriveDirectory();
      return true;
    }
    if (this.mediaCatalogDirectorySelectorVisible || this.mediaCatalogTagSelectorVisible) {
      this.CloseMediaCatalogSelector();
      return true;
    }
    if (this.showLogin) {
      if (this.authInProgress) return true;
      this.showLogin = false;
      this.password = '';
      return true;
    }
    if (this.profileVisible) {
      if (this.profileMode !== 'home') this.LeaveProfileSubpage();
      else this.profileVisible = false;
      return true;
    }
    if (this.mediaSelectorMode.length > 0) {
      this.CloseMediaSelector();
      return true;
    }
    if (this.remoteBookSourceSelectorVisible) {
      this.remoteBookSourceSelectorVisible = false;
      return true;
    }
    if (this.toolOperation.length > 0) {
      if (!this.toolOperationBusy) this.CloseToolOperation();
      return true;
    }
    if (this.currentIndex === 0) {
      if (this.sourceManageToolsVisible) this.sourceManageToolsVisible = false;
      else if (this.sourceManageFilterVisible) this.sourceManageFilterVisible = false;
      else if (this.sourceDeleteUrl.length > 0) this.CancelDeleteBookSource();
      else if (this.sourceCredentialUrl.length > 0) this.CloseBookSourceCredential();
      else if (this.sourceExploreSourceUrl.length > 0) this.CloseBookSourceExplore();
      else if (this.bookSourceManagementVisible) this.bookSourceManagementVisible = false;
      else if (this.sourceSearchPageVisible) this.LeaveBookSourceSearchResults();
      else if (this.bookModeIndex === 1 && this.remoteBookPath !== '/') {
        this.remoteBookPath = this.ParentBookPath();
        this.LoadRemoteBookDirectory();
      }
      else return false;
      return true;
    }
    if (this.currentIndex === 1 && this.toolMode !== 'home') {
      if (this.toolMode === 'market') this.LeaveMarketPage();
      else this.toolMode = 'home';
      return true;
    }
    if (this.currentIndex === 2) {
      try {
        if (this.dshWebController.accessBackward()) {
          this.dshWebController.backward();
          return true;
        }
      } catch (_) {
        // WebView尚未完成初始化时保留主页面默认返回行为。
      }
    }
    return false;
  }

  @Builder
  private PageBody(index: number) {
    if (index === 0) {
      this.BooksPage()
    } else if (index === 1) {
      this.ToolsPage()
    } else {
      this.DrivePage()
    }
  }

  @Builder
  private StandardTabPage(index: number) {
    Column({ space: 14 }) {
      // 一级页面标题和头像固定在顶部，内容区独立滚动。
      this.PageHeader(this.tabs[index].title)
      if (index === 3) {
        this.MediaPage()
      } else if (index === 0 && this.bookModeIndex === 0 && this.sourceSearchPageVisible) {
        // 搜索页的返回栏和搜索框固定，只让结果列表滚动。
        this.BookSourceSearchResultsPage()
      } else {
        Scroll(this.tabScrollers[index]) {
          Column({ space: 14 }) {
            this.PageBody(index)
          }.width('100%').padding({ bottom: 18 }).alignItems(HorizontalAlign.Start)
        }
        .width('100%')
        .layoutWeight(1)
        .align(Alignment.TopStart)
        .edgeEffect(EdgeEffect.None)
        .scrollBar(BarState.Off)
        .onReachEnd(() => {
          if (index === 0 && this.bookModeIndex === 0) {
            this.LoadMoreBookSourceSearchResults();
          } else if (index === 0 && this.bookModeIndex === 1) {
            this.LoadMoreRemoteBookDirectory();
          }
        })
      }
    }
    .width('100%')
    .height('100%')
    .padding({ left: 18, right: 18, top: 16 })
    .alignItems(HorizontalAlign.Start)
    .backgroundColor(AppTheme.background)
  }

  @Builder
  private PageHeader(title: string) {
    Row() {
      Row({ space: 5 }) {
        Text(title).fontSize(21).fontWeight(FontWeight.Bold)
          .fontFamily('HarmonyOS Sans').fontColor(AppTheme.textPrimary)
        if (this.lanModeActive) {
          SymbolGlyph($r('sys.symbol.wifi')).fontSize(14).fontColor([AppTheme.primary])
            .accessibilityText('局域网直连')
        }
      }.layoutWeight(1)
      if (this.currentIndex === 0) {
        this.BookModeSelector()
      }
      if (this.currentIndex === 3) {
        this.MediaModeSwitch()
      }
      Button() {
        if (this.profileAvatarPixelMap !== undefined && !this.profileAvatarFailed) {
          Image(this.profileAvatarPixelMap).width(36).height(36).objectFit(ImageFit.Cover)
            .borderRadius(18).border({ width: 1, color: AppTheme.divider })
        } else if (this.ProfileAvatarSource().length > 0 && !this.profileAvatarFailed) {
          Image(this.ProfileAvatarSource()).width(36).height(36).objectFit(ImageFit.Cover)
            .borderRadius(18).border({ width: 1, color: AppTheme.divider })
            .onError(() => this.profileAvatarFailed = true)
        } else {
          Text(this.ProfileAvatarLabel()).width(36).height(36).fontSize(13).fontWeight(FontWeight.Bold)
            .fontFamily('HarmonyOS Sans')
            .textAlign(TextAlign.Center)
            .fontColor(this.authenticated ? Color.White : AppTheme.textSecondary)
            .backgroundColor(this.authenticated ? AppTheme.primary : AppTheme.surfaceTint)
            .borderRadius(18)
            .border({ width: 1, color: this.authenticated ? AppTheme.primary : AppTheme.divider })
        }
      }.width(48).height(48).padding(0)
        .borderRadius(24)
        .backgroundColor(Color.Transparent)
        .accessibilityText(this.authenticated ? `账户：${this.profileName}` : '账户，未登录')
        .accessibilityDescription(this.profileVisible ? '当前位于我的页面' : '进入我的页面')
        .onClick(() => this.OpenProfilePage())
    }.width('100%')
  }

  @Builder
  private MediaModeSwitch() {
    Row() {
      AppSegmentItem({ label: '图片', selected: this.mediaCatalogMode === 'gallery',
        action: () => this.ChangeMediaCatalogMode('gallery') })
      AppSegmentItem({ label: '视频', selected: this.mediaCatalogMode === 'video',
        action: () => this.ChangeMediaCatalogMode('video') })
      AppSegmentItem({ label: '音频', selected: this.mediaCatalogMode === 'audio',
        action: () => this.ChangeMediaCatalogMode('audio') })
    }.height(40).padding(2).borderRadius(AppTheme.pillRadius).backgroundColor(AppTheme.glass)
      .border({ width: 1, color: AppTheme.glassBorder, radius: AppTheme.pillRadius })
      .backdropBlur(14).margin({ right: 4 })
  }

  @Builder
  private BackButton(color: ResourceColor, action: () => void) {
    Button() {
      SymbolGlyph($r('sys.symbol.arrow_left')).fontSize(24).fontColor([color])
    }
      .width(48)
      .height(48)
      .backgroundColor(Color.Transparent)
      .accessibilityText('返回')
      .onClick(action)
  }

  @Builder
  private CompactActionButton(label: string, action: () => void) {
    Button(label)
      .height(AppTheme.controlHeight)
      .padding({ left: 14, right: 14 })
      .fontSize(14)
      .fontColor(AppTheme.primary)
      .backgroundColor(AppTheme.accentSoft)
      .borderRadius(AppTheme.pillRadius)
      .onClick(action)
  }

  @Builder
  private BooksPage() {
    if (this.bookModeIndex === 0) {
      if (this.sourceSearchPageVisible) this.BookSourceSearchResultsPage()
      else {
        this.BookSourceSearchField()
        this.BookShelfSection()
      }
    } else if (this.bookModeIndex === 1) {
      AppGlassTextInput({ value: this.remoteBookSearchText, placeholder: '搜索远程书名、作者或标签',
        valueChanged: (value: string) => this.OnRemoteBookSearchChanged(value) })
      this.BookShelfHeader()
      if (!this.authenticated) {
        this.BookActionCard('连接远程书库', '登录后浏览MyTools后端管理的电子书', '登录', () => {
          this.OpenLogin();
        })
      } else {
        if (this.remoteBookPath !== '/') this.SectionTitle(this.remoteBookPath)
        if (this.remoteBookLoading && this.remoteBookItems.length === 0) {
          this.NetworkLoadingRows('正在读取远程书库')
        } else if (!this.remoteBookLoading && this.selectedRemoteBookSourceId.length > 0 &&
          this.remoteBookItems.length === 0 && this.remoteBookSearchText.trim().length === 0) {
          this.RetryAction('重新加载远程书库', () => this.LoadRemoteBookDirectory())
        }
        if (this.remoteBookPath !== '/') {
          this.RemoteBookRow({ name: '返回上级目录', path: this.ParentBookPath(), kind: 'directory',
            contentType: '', size: 0, lastModified: '' })
        }
        ForEach(this.FilteredRemoteBookItems(), (item: RemoteMediaItem) => {
          this.RemoteBookRow(item)
        }, (item: RemoteMediaItem) => item.path)
        if (this.remoteBookHasMore) {
          Button(this.remoteBookLoadingMore ? '正在加载更多…' : '继续加载远程图书')
            .width('100%').height(48).enabled(!this.remoteBookLoadingMore)
            .fontColor(AppTheme.primary).backgroundColor(AppTheme.surfaceTint)
            .onClick(() => this.LoadMoreRemoteBookDirectory())
        }
        if (this.remoteBookSearchText.trim().length > 0 && this.FilteredRemoteBookItems().length === 0) {
          this.PlaceholderRow('远程书库没有匹配图书', '可尝试书名、作者或标签中的其他关键词')
        }
      }
    } else {
      this.BookShelfSearchField()
      this.BookShelfSection()
    }
    if (this.bookStatus.length > 0 && !(this.bookModeIndex === 0 && this.sourceSearchLoading)) {
      Text(this.bookStatus).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
    }
  }

  @Builder
  private BookShelfSection() {
    this.BookShelfHeader()
    if (this.BookShelfFilterTags().length > 0) this.BookShelfTagFilterRow()
    if (this.ModeShelfBooks().length === 0) {
      this.EmptyShelfPanel()
    } else {
      if (this.FilteredShelfBooks().length === 0) {
        this.PlaceholderRow('书架中没有匹配图书', '可尝试书名或作者中的其他关键词')
      } else {
        Column({ space: 0 }) {
          ForEach(this.FilteredShelfBooks(), (book: Book) => {
            this.BookShelfCard(book)
          }, (book: Book) => book.id)
        }.width('100%').alignItems(HorizontalAlign.Start)
      }
    }
  }

  @Builder
  private BookShelfTagFilterRow() {
    Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Start }) {
      ForEach(this.BookShelfFilterTags(), (tag: string) => {
        AppFilterChip({ label: tag, selected: this.shelfSearchText === tag,
          action: () => this.shelfSearchText = this.shelfSearchText === tag ? '' : tag })
          .margin({ right: 6, bottom: 6 }).accessibilityText(`筛选书架标签：${tag}`)
      }, (tag: string) => tag)
    }.width('100%')
  }

  private BookShelfFilterTags(): string[] {
    const values: string[] = [];
    this.ModeShelfBooks().forEach((book: Book) => this.BookShelfTags(book).forEach((tag: string) => {
      if (!values.includes(tag)) values.push(tag);
    }));
    return values.sort((left: string, right: string) => {
      const leftAdult = left.startsWith('R18-');
      const rightAdult = right.startsWith('R18-');
      if (leftAdult === rightAdult) return left.localeCompare(right);
      return leftAdult ? -1 : 1;
    }).slice(0, 12);
  }

  @Builder
  private BookModeSelector() {
    Row() {
      ForEach(['书源', '远程', '本地'], (item: string, index: number) => {
        AppSegmentItem({ label: item, selected: this.bookModeIndex === index, action: () => {
          if (index !== 0 && this.sourceSearchLoading) this.CancelBookSourceSearch();
          this.bookModeIndex = index;
          this.bookSourceManagementVisible = false;
          if (index !== 0) this.sourceSearchPageVisible = false;
          this.bookStatus = '';
          if (index === 1 && this.authenticated) this.LoadRemoteBooks();
        } })
          .accessibilityText(`${item}添加图书方式`)
      })
    }
    .height(40)
    .padding(2)
    .borderRadius(AppTheme.pillRadius)
    .backgroundColor(AppTheme.glass)
    .border({ width: 1, color: AppTheme.glassBorder, radius: AppTheme.pillRadius })
    .backdropBlur(14)
    .margin({ right: 4 })
  }

  @Builder
  private BookActionCard(title: string, subtitle: string, action: string, onAction: () => void) {
    Row({ space: 16 }) {
      Column({ space: 6 }) {
        Text(title).width('100%').fontSize(18).fontFamily('HarmonyOS Sans')
          .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
        Text(subtitle).width('100%').fontSize(13).fontFamily('HarmonyOS Sans')
          .fontColor(AppTheme.textSecondary)
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)
      Button(action)
        .height(AppTheme.controlHeight).padding({ left: 18, right: 18 }).fontSize(14)
        .fontFamily('HarmonyOS Sans')
        .fontColor(Color.White)
        .backgroundColor(AppTheme.primary)
        .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
        .borderRadius(AppTheme.pillRadius)
        .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
        .onClick(onAction)
    }
    .width('100%')
    .padding(18)
    .borderRadius(AppTheme.cardRadius)
    .backgroundColor(AppTheme.surface)
    .border({ width: 1, color: AppTheme.divider, radius: AppTheme.cardRadius })
    .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
  }

  @Builder
  private EmptyShelfPanel() {
    Column({ space: 14 }) {
      Row({ space: 12 }) {
        Column() {
          SymbolGlyph($r('sys.symbol.book_open_fill')).fontSize(24).fontColor([AppTheme.primary])
        }.width(48).height(48).justifyContent(FlexAlign.Center)
          .borderRadius(16).backgroundColor(AppTheme.surfaceTint)
        Column({ space: 4 }) {
          Text(this.EmptyShelfTitle()).width('100%').fontSize(18).fontFamily('HarmonyOS Sans')
            .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
          Text(this.EmptyShelfDescription()).width('100%')
            .fontSize(12).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textSecondary)
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      }.width('100%')
      if (this.bookModeIndex === 0) {
        this.EmptyShelfAction('搜索并加入书源图书', 'sys.symbol.square_stack_3d_fill', () => {
          this.tabScrollers[0].scrollEdge(Edge.Top);
        })
      } else if (this.bookModeIndex === 2) {
        this.EmptyShelfAction('添加本地图书', 'sys.symbol.folder_fill', () => this.PickLocalBooks())
      }
    }.width('100%').padding(16).backgroundColor(AppTheme.surface).borderRadius(20)
      .border({ width: 1, color: AppTheme.divider, radius: 20 })
  }

  @Builder
  private EmptyShelfAction(label: string, symbol: string, action: () => void) {
    Button() {
      Row({ space: 12 }) {
        Column() {
          SymbolGlyph($r(symbol)).fontSize(19).fontColor([AppTheme.primary])
        }.width(36).height(36).justifyContent(FlexAlign.Center)
          .borderRadius(12).backgroundColor(AppTheme.surfaceTint)
        Text(label).layoutWeight(1).textAlign(TextAlign.Start)
          .fontSize(14).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textPrimary)
        SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(14).fontColor([AppTheme.textSecondary])
      }.width('100%')
    }.width('100%').height(54).padding({ left: 10, right: 12 })
      .backgroundColor(AppTheme.background).borderRadius(14)
      .accessibilityText(label).onClick(action)
  }

  @Builder
  private ToolsPage() {
    if (this.toolMode === 'text') {
      this.ToolTextPage()
    } else if (this.toolMode === 'digest') {
      this.ToolDigestPage()
    } else if (this.toolMode === 'archive') {
      this.ToolArchivePage()
    } else if (this.toolMode === 'files') {
      this.ToolFilesPage()
    } else if (this.toolMode === 'feedback') {
      this.ToolFeedbackPage()
    } else if (this.toolMode === 'market') {
      this.ToolMarketPage()
    } else if (this.toolMode === 'qrcode') {
      this.ToolQrCodePage()
    } else if (this.toolMode === 'downloads') {
      this.ToolDownloadsPage()
    } else {
      AppGlassTextInput({ value: this.toolSearchText, placeholder: '搜索工具',
        valueChanged: (value: string) => this.toolSearchText = value })
      this.HeroPanel('远程文件管理', '浏览MyTools、NAS与WebDAV文件，不扫描本地目录', '打开文件管理', () => {
        this.RecordRecentTool('files');
        if (!this.authenticated) {
          this.OpenLogin();
        } else {
          this.toolMode = 'files';
          this.LoadToolFiles();
        }
      })
      if (this.recentToolIds.length > 0) {
        Row() {
          Text('最近使用').layoutWeight(1).fontSize(22).fontWeight(FontWeight.Bold)
            .fontColor(AppTheme.textPrimary)
          Button('清除').width(48).height(48).fontSize(13).fontColor(AppTheme.textSecondary)
            .backgroundColor(Color.Transparent)
            .onClick(() => this.ClearRecentTools())
        }.width('100%')
        Row({ space: 10 }) {
          ForEach(this.recentToolIds.slice(0, 3), (id: string) => {
            this.RecentToolCard(id)
          }, (id: string) => id)
        }.width('100%')
      }
      this.SectionTitle('实用工具')
      if (this.ToolMatches('文本处理 JSON格式化 去重 排序 统计')) {
        this.ToolEntryRow('文本处理', 'JSON格式化、去重、排序和统计', () => {
          this.OpenRecentTool('text');
        })
      }
      if (this.ToolMatches('文件传输 上传 下载 WebDAV NAS 远程文件')) {
        this.ToolEntryRow('文件传输', '通过MyTools、NAS或WebDAV安全上传与下载', () => {
          this.OpenRecentTool('transfer');
        })
      }
      if (this.ToolMatches('开发工具 URL 编码 解码 时间戳 JSON')) {
        this.ToolEntryRow('开发工具', 'URL编解码、时间戳转换和JSON格式化', () => {
          this.OpenRecentTool('developer');
        })
      }
      if (this.ToolMatches('加密工具 摘要 SHA256 SHA512 哈希 校验')) {
        this.ToolEntryRow('加密工具', '使用系统密码框架计算本地文本摘要', () => {
          this.OpenRecentTool('digest');
        })
      }
      if (this.ToolMatches('压缩解压 ZIP 打包 归档 导出')) {
        this.ToolEntryRow('压缩解压', '安全创建ZIP或检查后逐项导出', () => {
          this.OpenRecentTool('archive');
        })
      }
      if (this.ToolMatches('远程电子书 书库 EPUB PDF 漫画')) {
        this.ToolEntryRow('远程电子书', '进入远程书库浏览', () => {
          this.RecordRecentTool('remoteBooks');
          this.ChangeMainTab(0);
          this.bookModeIndex = 1;
          if (this.authenticated) this.LoadRemoteBooks();
        })
      }
      if (this.ToolMatches('远程多媒体 图片 音频 视频')) {
        this.ToolEntryRow('远程多媒体', '进入远程媒体浏览与播放', () => {
          this.RecordRecentTool('remoteMedia');
          this.ChangeMainTab(3);
          if (this.authenticated && this.mediaSources.length === 0) this.LoadMediaSources();
        })
      }
      if (this.ToolMatches('意见反馈 建议 问题')) {
        this.ToolEntryRow('意见反馈', '提交功能建议或使用问题', () => {
          this.RecordRecentTool('feedback');
          this.feedbackName = this.profileUsername.length > 0 ? this.profileUsername : this.account;
          this.feedbackStatus = '';
          this.toolMode = 'feedback';
        })
      }
      if (this.ToolMatches('应用市场 扩展 MCP Skill App')) {
        this.ToolEntryRow('应用市场', '浏览MyTools提供的扩展目录', () => {
          this.RecordRecentTool('market');
          if (!this.authenticated) {
            this.OpenLogin();
          } else {
            this.toolMode = 'market';
            this.LoadMarketApps(true);
          }
        })
      }
      if (this.ToolMatches('二维码 QR 生成 扫描')) {
        this.ToolEntryRow('二维码', '离线生成二维码或调用系统扫码', () => {
          this.RecordRecentTool('qrcode');
          this.qrStatus = '';
          this.toolMode = 'qrcode';
        })
      }
      if (this.ToolMatches('下载管理 任务 历史 进度')) {
        this.ToolEntryRow('下载管理', `${this.CurrentDownloadHistory().length} 条最近任务`, () => {
          this.RecordRecentTool('downloads');
          this.toolMode = 'downloads';
        })
      }
      if (this.toolSearchText.trim().length > 0 && !this.ToolMatches('文本处理 JSON格式化 去重 排序 统计') &&
        !this.ToolMatches('远程电子书 书库 EPUB PDF 漫画') &&
        !this.ToolMatches('远程多媒体 图片 音频 视频') && !this.ToolMatches('意见反馈 建议 问题') &&
        !this.ToolMatches('应用市场 扩展 MCP Skill App') && !this.ToolMatches('二维码 QR 生成 扫描') &&
        !this.ToolMatches('下载管理 任务 历史 进度') &&
        !this.ToolMatches('文件传输 上传 下载 WebDAV NAS 远程文件') &&
        !this.ToolMatches('开发工具 URL 编码 解码 时间戳 JSON') &&
        !this.ToolMatches('加密工具 摘要 SHA256 SHA512 哈希 校验') &&
        !this.ToolMatches('压缩解压 ZIP 打包 归档 导出')) {
        this.PlaceholderRow('没有匹配的工具', '可尝试“文本”“传输”“开发”“摘要”“多媒体”或“市场”')
      }
    }
  }

  @Builder
  private ToolQrCodePage() {
    Row() {
      this.BackButton(AppTheme.textPrimary, () => this.toolMode = 'home')
      Text('二维码').layoutWeight(1).textAlign(TextAlign.Center).fontSize(20)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Blank().width(48)
    }.width('100%')
    TextArea({ text: this.qrInput, placeholder: '输入需要生成二维码的文本（最多2950字节）' })
      .width('100%').height(140).maxLength(2048)
      .onChange((value: string) => this.qrInput = value)
    Row({ space: 12 }) {
      AppPrimaryPillButton({ label: this.qrBusy ? '处理中…' : '生成二维码', available: !this.qrBusy,
        action: () => this.GenerateQrCode() }).layoutWeight(1)
      Button('系统扫码').layoutWeight(1).enabled(!this.qrBusy)
        .fontColor(AppTheme.primary).backgroundColor(AppTheme.surfaceTint).onClick(() => this.ScanQrCode())
    }.width('100%')
    if (this.qrPixelMap !== undefined) {
      Column({ space: 10 }) {
        Image(this.qrPixelMap).width(260).height(260).objectFit(ImageFit.Contain)
        Text('二维码仅在设备本地生成，输入内容不会发送到MyTools或第三方服务。')
          .fontSize(12).fontColor(AppTheme.textSecondary)
      }.width('100%').padding(18).borderRadius(18).backgroundColor(Color.White)
    }
    if (this.qrResult.length > 0) {
      Column({ space: 8 }) {
        Text('扫码结果').width('100%').fontSize(15).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
        Text(this.qrResult).width('100%').fontSize(14).fontColor(AppTheme.textPrimary)
          .copyOption(CopyOptions.LocalDevice)
        Text('结果不会自动打开或执行；长按文本可复制。').width('100%')
          .fontSize(12).fontColor(AppTheme.textSecondary)
      }.width('100%').padding(16).alignItems(HorizontalAlign.Start)
        .borderRadius(14).backgroundColor(AppTheme.surfaceTint)
    }
    if (this.qrStatus.length > 0) {
      Text(this.qrStatus).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
    }
  }

  @Builder
  private ToolDigestPage() {
    Row() {
      this.BackButton(AppTheme.textPrimary, () => this.toolMode = 'home')
      Text('加密工具').layoutWeight(1).textAlign(TextAlign.Center).fontSize(20)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Blank().width(48)
    }.width('100%')
    Text('摘要是单向校验值，不是可解密的密文。输入只在设备内存中处理，不会上传或持久化。')
      .width('100%').padding(14).fontSize(13).fontColor(AppTheme.textSecondary)
      .backgroundColor(AppTheme.surfaceTint).borderRadius(12)
    Row({ space: 10 }) {
      this.DigestAlgorithmButton('SHA-256', 'SHA256')
      this.DigestAlgorithmButton('SHA-512', 'SHA512')
    }.width('100%')
    TextArea({ text: this.digestInput, placeholder: '输入需要计算摘要的文本（最多20万字符）' })
      .width('100%').height(180).maxLength(200000).backgroundColor(AppTheme.surface)
      .border({ width: 1, color: AppTheme.divider, radius: 14 })
      .onChange((value: string) => {
        this.digestInput = value;
        this.digestOutput = '';
        this.digestStatus = '';
      })
    AppPrimaryPillButton({ label: this.digestBusy ? '计算中…' : '计算摘要', available: !this.digestBusy,
      action: () => this.CalculateDigest() })
    if (this.digestOutput.length > 0) {
      TextArea({ text: this.digestOutput }).width('100%').height(130)
        .backgroundColor(AppTheme.surfaceTint).copyOption(CopyOptions.LocalDevice)
    }
    if (this.digestStatus.length > 0) {
      Text(this.digestStatus).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
    }
  }

  @Builder
  private DigestAlgorithmButton(label: string, algorithm: string) {
    Button(label).layoutWeight(1).height(48)
      .fontColor(this.digestAlgorithm === algorithm ? AppTheme.primary : AppTheme.textSecondary)
      .backgroundColor(this.digestAlgorithm === algorithm ? AppTheme.accentSoft : AppTheme.surface)
      .border({ width: 1, color: this.digestAlgorithm === algorithm ? AppTheme.primary : AppTheme.divider,
        radius: AppTheme.pillRadius })
      .borderRadius(AppTheme.pillRadius)
      .onClick(() => {
        this.digestAlgorithm = algorithm;
        this.digestOutput = '';
      })
  }

  @Builder
  private ToolArchivePage() {
    Row() {
      this.BackButton(AppTheme.textPrimary, () => {
        this.archiveTool.cleanupExtraction();
        this.archiveExtractedItems = [];
        this.toolMode = 'home';
      })
      Text('压缩解压').layoutWeight(1).textAlign(TextAlign.Center).fontSize(20)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Blank().width(48)
    }.width('100%')
    Column({ space: 10 }) {
      Text('创建ZIP').width('100%').fontSize(20).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Text('最多选择20个普通文件；单文件不超过100 MB，总计不超过500 MB。文件会暂存在应用缓存中，保存完成或失败后立即清理。')
        .width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
      Text('不会扫描设备目录，不会修改或删除原文件。')
        .width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
      AppPrimaryPillButton({ label: this.archiveBusy ? '正在创建ZIP…' : '选择文件并创建ZIP',
        available: !this.archiveBusy, action: () => this.CreateToolArchive() })
    }.width('100%').padding(18).borderRadius(18).backgroundColor(AppTheme.surfaceTint)
    Column({ space: 10 }) {
      Text('安全解压').width('100%').fontSize(20).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Text('仅支持不含加密、符号链接、路径穿越、异常压缩比或不受支持算法的ZIP。解压结果暂存在应用缓存，需要逐个选择文件导出。')
        .width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
      Button(this.archiveBusy ? '处理中…' : '选择ZIP并检查').width('100%').height(48)
        .enabled(!this.archiveBusy).fontColor(AppTheme.primary).backgroundColor(AppTheme.surface)
        .onClick(() => this.ExtractToolArchive())
    }.width('100%').padding(18).borderRadius(18).backgroundColor(AppTheme.surfaceTint)
    if (this.archiveExtractedItems.length > 0) {
      Text(`解压结果 · ${this.archiveExtractedItems.length} 个文件${this.archiveExtractedItems.length > 200 ? ' · 仅展示前200项' : ''}`)
        .width('100%').fontSize(17).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      ForEach(this.archiveExtractedItems.slice(0, 200), (item: ArchiveExtractedItem) => {
        Row({ space: 10 }) {
          Column({ space: 4 }) {
            Text(item.relativePath).width('100%').maxLines(2).fontSize(14).fontColor(AppTheme.textPrimary)
            Text(this.FormatFileSize(item.size)).fontSize(12).fontColor(AppTheme.textSecondary)
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Button('导出').fontSize(12).fontColor(AppTheme.primary).backgroundColor(AppTheme.accentSoft)
            .borderRadius(AppTheme.pillRadius).onClick(() => this.ExportArchiveItem(item))
        }.width('100%').padding({ top: 10, bottom: 10 })
          .border({ width: { bottom: 1 }, color: AppTheme.divider })
      }, (item: ArchiveExtractedItem) => item.relativePath)
    }
    if (this.archiveStatus.length > 0) {
      Text(this.archiveStatus).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
    }
  }

  @Builder
  private ToolDownloadsPage() {
    Row() {
      this.BackButton(AppTheme.textPrimary, () => this.toolMode = 'home')
      Text('下载管理').layoutWeight(1).textAlign(TextAlign.Center).fontSize(20)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Button(this.CurrentDownloadHistory().length > 0 ? '清空' : '').width(48).height(48).fontSize(13)
        .fontColor(AppTheme.danger).backgroundColor(Color.Transparent)
        .enabled(this.CurrentDownloadHistory().length > 0)
        .onClick(() => this.ClearDownloadHistory())
    }.width('100%')
    Text('任务记录按当前MyTools服务账户隔离，只保存文件名、字节进度、状态和时间，不保存用户选择的目标URI、远程路径或账号凭据。AI发起下载时仍必须由你在系统窗口中选择保存位置。')
      .width('100%').padding(14).fontSize(12).fontColor(AppTheme.textSecondary)
      .borderRadius(12).backgroundColor(AppTheme.surfaceTint)
    if (this.CurrentDownloadHistory().length === 0) {
      this.PlaceholderRow('暂无下载记录', '可在远程文件中选择文件并下载')
    }
    ForEach(this.CurrentDownloadHistory(), (item: DownloadHistoryItem) => {
      Column({ space: 7 }) {
        Row() {
          Text(item.name).layoutWeight(1).maxLines(1).fontSize(15).fontColor(AppTheme.textPrimary)
          Text(this.DownloadStatusLabel(item.status)).fontSize(12)
            .fontColor(item.status === 'failed' ? AppTheme.danger : AppTheme.primary)
        }.width('100%')
        if (item.status === 'running') {
          Progress({ value: item.receivedBytes, total: Math.max(1, item.totalBytes), type: ProgressType.Linear })
            .width('100%').color(AppTheme.primary)
          if (item.owner === this.activeDownloadOwner && item.id === this.activeDownloadTaskId) {
            Button('取消下载').height(48).fontSize(13).fontColor(AppTheme.danger).backgroundColor(Color.Transparent)
              .onClick(() => this.CancelActiveDownload())
          }
        }
        Row() {
          Text(item.totalBytes > 0 ? `${this.FormatFileSize(item.receivedBytes)} / ${this.FormatFileSize(item.totalBytes)}` :
            this.FormatFileSize(item.receivedBytes)).fontSize(12).fontColor(AppTheme.textSecondary)
          Blank()
          Text(this.FormatRecordTime(item.updatedAt)).fontSize(12).fontColor(AppTheme.textSecondary)
        }.width('100%')
      }.width('100%').padding({ top: 14, bottom: 14 }).alignItems(HorizontalAlign.Start)
        .border({ width: { bottom: 1 }, color: AppTheme.divider })
    }, (item: DownloadHistoryItem) => item.id)
  }

  @Builder
  private ToolMarketPage() {
    Row() {
      this.BackButton(AppTheme.textPrimary, () => {
        this.LeaveMarketPage();
      })
      Text(this.marketDetail === undefined ? '应用市场' : '应用详情')
        .layoutWeight(1).textAlign(TextAlign.Center).fontSize(20)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Button(this.marketDetail === undefined ? '刷新' : '').width(48).height(48).fontSize(13)
        .fontColor(AppTheme.primary).backgroundColor(Color.Transparent).enabled(this.marketDetail === undefined)
        .onClick(() => {
          if (this.marketDetail === undefined) this.LoadMarketApps(true);
        })
    }.width('100%')
    if (this.marketDetail !== undefined) {
      this.ToolMarketDetail(this.marketDetail)
    } else {
      Row({ space: 10 }) {
        AppGlassTextInput({ value: this.marketQuery, placeholder: '按名称搜索应用',
          valueChanged: (value: string) => this.marketQuery = value,
          submitted: () => this.LoadMarketApps(true) }).layoutWeight(1)
        Button('搜索').height(AppTheme.controlHeight).padding({ left: 18, right: 18 })
          .fontColor(Color.White).backgroundColor(AppTheme.primary).borderRadius(AppTheme.pillRadius)
          .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
          .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
          .onClick(() => this.LoadMarketApps(true))
      }.width('100%')
      Text(`共 ${this.marketTotal} 项 · 第 ${this.marketPage} 页`)
        .width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
      if (this.marketLoading && this.marketItems.length === 0) {
        this.NetworkLoadingRows('正在读取应用市场')
      } else if (!this.marketLoading && this.marketItems.length === 0) {
        this.RetryAction(this.marketQuery.trim().length > 0 ? '没有匹配项，重新搜索' : '重新加载应用市场',
          () => this.LoadMarketApps(true))
      }
      ForEach(this.marketItems, (item: AppMarketItem) => {
        this.ToolMarketItemRow(item)
      }, (item: AppMarketItem) => item.id)
      if (this.marketItems.length < this.marketTotal) {
        Button(this.marketLoading ? '加载中…' : '加载更多').height(AppTheme.controlHeight)
          .fontColor(AppTheme.primary).backgroundColor(AppTheme.accentSoft).borderRadius(AppTheme.pillRadius)
          .enabled(!this.marketLoading)
          .onClick(() => this.LoadMarketApps(false))
      }
      if (this.marketStatus.length > 0) {
        Text(this.marketStatus).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
      }
    }
  }

  @Builder
  private ToolMarketItemRow(item: AppMarketItem) {
    Row({ space: 14 }) {
      Text(item.type.toUpperCase()).width(54).height(54).textAlign(TextAlign.Center)
        .fontSize(11).fontColor(AppTheme.primary).borderRadius(14).backgroundColor(AppTheme.surfaceTint)
      Column({ space: 5 }) {
        Text(item.name).width('100%').fontSize(16).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
        Text(`${item.type} · ${item.version} · ${item.author}`).width('100%').fontSize(12)
          .fontColor(AppTheme.textSecondary).maxLines(1)
        if (item.summary.length > 0) {
          Text(item.summary).width('100%').fontSize(13).fontColor(AppTheme.textSecondary).maxLines(2)
        }
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      Text('›').fontSize(24).fontColor(AppTheme.textSecondary)
    }.width('100%').padding({ top: 14, bottom: 14 })
      .border({ width: { bottom: 1 }, color: AppTheme.divider })
      .onClick(() => this.LoadMarketDetail(item.id))
  }

  @Builder
  private ToolMarketDetail(detail: AppMarketDetail) {
    Column({ space: 14 }) {
      Text(detail.type.toUpperCase()).width(72).height(72).textAlign(TextAlign.Center)
        .fontSize(13).fontColor(AppTheme.primary).borderRadius(18).backgroundColor(AppTheme.surfaceTint)
      Text(detail.name).fontSize(24).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Text(`版本 ${detail.version} · ${detail.author}`).fontSize(13).fontColor(AppTheme.textSecondary)
      Text(detail.content.length > 0 ? detail.content : '发布者未提供详细说明')
        .width('100%').fontSize(15).fontColor(AppTheme.textPrimary)
      if (detail.fileName.length > 0) {
        this.PlaceholderRow(detail.fileName, `${detail.fileType} · ${this.FormatFileSize(detail.fileSize)}`)
      }
      Text('安全提示：当前客户端仅展示目录与详情，不执行发布者提供的安装命令，也不会自动下载或运行文件。')
        .width('100%').padding(14).fontSize(13).fontColor(AppTheme.textSecondary)
        .borderRadius(12).backgroundColor(AppTheme.surfaceTint)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private ToolFeedbackPage() {
    Row() {
      this.BackButton(AppTheme.textPrimary, () => this.toolMode = 'home')
      Text('意见反馈').layoutWeight(1).textAlign(TextAlign.Center).fontSize(20)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Blank().width(48)
    }.width('100%')
    if (this.feedbackReceiptId.length > 0) {
      Column({ space: 12 }) {
        Text('反馈已提交').fontSize(22).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
        Text(`回执编号：${this.feedbackReceiptId}`).fontSize(14).fontColor(AppTheme.textSecondary)
        Text('感谢你的反馈，我们已安全保存本次内容。').fontSize(14).fontColor(AppTheme.textSecondary)
        Button('再提交一条').onClick(() => {
          this.feedbackReceiptId = '';
          this.feedbackTitle = '';
          this.feedbackContent = '';
          this.feedbackStatus = '';
        })
      }.width('100%').padding(24).borderRadius(18).backgroundColor(AppTheme.surfaceTint)
    } else {
      AppGlassTextInput({ value: this.feedbackName, placeholder: '称呼（必填）', limit: 50,
        valueChanged: (value: string) => this.feedbackName = value })
      AppGlassTextInput({ value: this.feedbackEmail, placeholder: '联系邮箱（必填）', limit: 100,
        inputType: InputType.Email, valueChanged: (value: string) => this.feedbackEmail = value })
      Text('反馈类型').width('100%').fontSize(14).fontColor(AppTheme.textSecondary)
      Row({ space: 8 }) {
        ForEach(['功能建议', '使用问题', '内容问题', '其他'], (category: string) => {
          Button(category).height(48).fontSize(13)
            .fontColor(this.feedbackCategory === category ? AppTheme.primary : AppTheme.textSecondary)
            .padding({ left: 12, right: 12 }).borderRadius(AppTheme.pillRadius)
            .backgroundColor(this.feedbackCategory === category ? AppTheme.accentSoft : AppTheme.surface)
            .border({ width: 1, color: this.feedbackCategory === category ? AppTheme.primary : AppTheme.divider,
              radius: AppTheme.pillRadius })
            .accessibilityDescription(this.feedbackCategory === category ? '已选择' : '未选择')
            .onClick(() => this.feedbackCategory = category)
        })
      }.width('100%')
      AppGlassTextInput({ value: this.feedbackTitle, placeholder: '标题（必填，最多200字）', limit: 200,
        valueChanged: (value: string) => this.feedbackTitle = value })
      TextArea({ text: this.feedbackContent, placeholder: '请描述问题、期望结果和复现步骤（必填，最多5000字）' })
        .width('100%').height(220).maxLength(5000).backgroundColor(AppTheme.glass)
        .border({ width: 1, color: AppTheme.divider, radius: AppTheme.inputRadius })
        .borderRadius(AppTheme.inputRadius)
        .onChange((value: string) => this.feedbackContent = value)
      Text(`${this.feedbackContent.length}/5000`).width('100%').textAlign(TextAlign.End)
        .fontSize(12).fontColor(AppTheme.textSecondary)
      if (this.feedbackStatus.length > 0) {
        Text(this.feedbackStatus).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
      }
      AppPrimaryPillButton({ label: this.feedbackSubmitting ? '提交中…' : '提交反馈',
        available: !this.feedbackSubmitting, action: () => this.SubmitFeedback() })
    }
  }

  @Builder
  private ToolTextPage() {
    Row() {
      this.BackButton(AppTheme.textPrimary, () => this.toolMode = 'home')
      Text(this.toolTextTitle).layoutWeight(1).textAlign(TextAlign.Center).fontSize(20)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Blank().width(48)
    }.width('100%')
    TextArea({ text: this.toolTextInput, placeholder: '输入需要处理的文本或JSON' })
      .width('100%').height(180).maxLength(200000).backgroundColor(AppTheme.surface)
      .border({ width: 1, color: AppTheme.divider, radius: 14 })
      .onChange((value: string) => this.toolTextInput = value)
    Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Start }) {
      this.ToolActionButton('JSON格式化', 'json')
      this.ToolActionButton('去重', 'unique')
      this.ToolActionButton('排序', 'sort')
      this.ToolActionButton('移除空行', 'compact')
      this.ToolActionButton('转大写', 'upper')
      this.ToolActionButton('转小写', 'lower')
      this.ToolActionButton('统计', 'stats')
      this.ToolActionButton('URL编码', 'urlEncode')
      this.ToolActionButton('URL解码', 'urlDecode')
      this.ToolActionButton('时间戳转时间', 'timestampToDate')
      this.ToolActionButton('时间转时间戳', 'dateToTimestamp')
    }.width('100%')
    TextArea({ text: this.toolTextOutput, placeholder: '处理结果' })
      .width('100%').height(200).backgroundColor(AppTheme.surfaceTint)
      .border({ width: 1, color: AppTheme.divider, radius: 14 })
      .copyOption(CopyOptions.LocalDevice)
  }

  private ToolMatches(searchableText: string): boolean {
    const keyword = this.toolSearchText.trim().toLowerCase();
    return keyword.length === 0 || searchableText.toLowerCase().includes(keyword);
  }

  private IsKnownToolId(id: string): boolean {
    return ['files', 'transfer', 'text', 'developer', 'digest', 'archive', 'remoteBooks', 'remoteMedia',
      'feedback', 'market', 'qrcode', 'downloads'].includes(id);
  }

  private RestoreRecentToolIds(raw: string): void {
    try {
      const parsed = JSON.parse(raw) as string[];
      this.recentToolIds = Array.isArray(parsed) ? parsed.filter((id: string) =>
        this.IsKnownToolId(id)).slice(0, 6) : [];
    } catch (_) {
      this.recentToolIds = [];
    }
  }

  private RecordRecentTool(id: string): void {
    if (!this.IsKnownToolId(id)) return;
    this.recentToolIds = [id].concat(this.recentToolIds.filter((value: string) => value !== id)).slice(0, 6);
    try {
      this.settingsPreferences?.putSync('recent_tool_ids', JSON.stringify(this.recentToolIds));
      this.settingsPreferences?.flush();
    } catch (_) {
    }
  }

  private ClearRecentTools(): void {
    this.recentToolIds = [];
    try {
      this.settingsPreferences?.putSync('recent_tool_ids', '[]');
      this.settingsPreferences?.flush();
    } catch (_) {
    }
  }

  private OpenRecentTool(id: string): void {
    if (!this.IsKnownToolId(id)) return;
    this.RecordRecentTool(id);
    if (id === 'text' || id === 'developer') {
      this.toolTextTitle = id === 'developer' ? '开发工具' : '文本处理';
      this.toolMode = 'text';
    } else if (id === 'files' || id === 'transfer') {
      if (!this.authenticated) this.OpenLogin();
      else {
        this.toolMode = 'files';
        this.LoadToolFiles();
      }
    } else if (id === 'digest') {
      this.digestStatus = '';
      this.toolMode = 'digest';
    } else if (id === 'archive') {
      this.archiveStatus = '';
      this.toolMode = 'archive';
    } else if (id === 'remoteBooks') {
      this.ChangeMainTab(0);
      this.bookModeIndex = 1;
      if (this.authenticated) this.LoadRemoteBooks();
    } else if (id === 'remoteMedia') {
      this.ChangeMainTab(3);
      if (this.authenticated && this.mediaSources.length === 0) this.LoadMediaSources();
    } else if (id === 'feedback') {
      this.feedbackName = this.profileUsername.length > 0 ? this.profileUsername : this.account;
      this.feedbackStatus = '';
      this.toolMode = 'feedback';
    } else if (id === 'market') {
      if (!this.authenticated) this.OpenLogin();
      else {
        this.toolMode = 'market';
        this.LoadMarketApps(true);
      }
    } else if (id === 'qrcode') {
      this.qrStatus = '';
      this.toolMode = 'qrcode';
    } else if (id === 'downloads') {
      this.toolMode = 'downloads';
    }
  }

  private RecentToolTitle(id: string): string {
    if (id === 'files') return '远程文件';
    if (id === 'transfer') return '文件传输';
    if (id === 'text') return '文本处理';
    if (id === 'developer') return '开发工具';
    if (id === 'digest') return '加密工具';
    if (id === 'archive') return '压缩解压';
    if (id === 'remoteBooks') return '远程电子书';
    if (id === 'remoteMedia') return '远程多媒体';
    if (id === 'feedback') return '意见反馈';
    if (id === 'market') return '应用市场';
    if (id === 'qrcode') return '二维码';
    return '下载管理';
  }

  private RecentToolSubtitle(id: string): string {
    if (id === 'files' || id === 'transfer') return '远程文件';
    if (id === 'text' || id === 'developer') return '本地处理';
    if (id === 'digest') return '本地摘要';
    if (id === 'archive') return 'ZIP工具';
    if (id === 'remoteBooks' || id === 'remoteMedia') return '远程内容';
    if (id === 'market') return '只读目录';
    if (id === 'qrcode') return '生成与扫描';
    if (id === 'downloads') return '任务记录';
    return '提交建议';
  }

  private RecentToolSymbol(id: string): string {
    if (id === 'remoteBooks') return '▣';
    if (id === 'remoteMedia') return '▶';
    if (id === 'qrcode') return '⌗';
    if (id === 'digest') return '#';
    if (id === 'archive') return '▤';
    if (id === 'developer') return '<>';
    return '▢';
  }

  @Builder
  private ToolActionButton(label: string, operation: string) {
    Button(label).height(AppTheme.controlHeight).fontSize(13).fontColor(AppTheme.textPrimary)
      .padding({ left: 12, right: 12 }).margin({ right: 8, bottom: 8 })
      .backgroundColor(AppTheme.surface)
      .border({ width: 1, color: AppTheme.outline, radius: AppTheme.pillRadius })
      .borderRadius(AppTheme.pillRadius)
      .onClick(() => this.ProcessToolText(operation))
  }

  @Builder
  private ToolFilesPage() {
    Row() {
      this.BackButton(AppTheme.textPrimary, () => this.toolMode = 'home')
      Text('远程文件').layoutWeight(1).textAlign(TextAlign.Center).fontSize(20)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Row({ space: 12 }) {
        this.CompactActionButton('新建', () => this.StartCreateToolDirectory())
        this.CompactActionButton('上传', () => this.UploadToolFile())
        this.CompactActionButton('刷新', () => this.LoadToolFiles())
      }
    }.width('100%')
    if (this.mediaSources.length > 0) this.ToolSourceSelector()
    Text(this.toolFilePath).width('100%').maxLines(1).fontSize(13).fontColor(AppTheme.textSecondary)
    if (this.toolStatus.length > 0) {
      Text(this.toolStatus).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
    }
    if (this.toolOperationBusy && this.toolUploadProgress > 0) {
      Progress({ value: this.toolUploadProgress * 100, total: 100, type: ProgressType.Linear })
        .width('100%').color(AppTheme.primary)
    }
    if (this.toolOperation.length > 0) this.ToolFileOperationPanel()
    if (this.toolLoading && this.toolFiles.length === 0) {
      this.NetworkLoadingRows('正在读取远程文件')
    } else if (!this.toolLoading && this.selectedMediaSourceId.length > 0 && this.toolFiles.length === 0) {
      this.RetryAction('重新加载远程文件', () => this.LoadToolFileDirectory())
    }
    if (this.toolFilePath !== '/') {
      this.ToolFileRow({ name: '返回上级目录', path: this.ParentToolFilePath(), kind: 'directory',
        contentType: '', size: 0, lastModified: '' })
    }
    ForEach(this.toolFiles, (item: RemoteMediaItem) => {
      this.ToolFileRow(item)
    }, (item: RemoteMediaItem) => item.path)
  }

  @Builder
  private ToolSourceSelector() {
    Row({ space: 8 }) {
      ForEach(this.mediaSources, (source: RemoteMediaSource) => {
        Button(source.name.length > 0 ? source.name : source.type).height(48).fontSize(13)
          .fontColor(this.selectedMediaSourceId === source.id ? AppTheme.primary : AppTheme.textSecondary)
          .padding({ left: 12, right: 12 }).borderRadius(AppTheme.pillRadius)
          .backgroundColor(this.selectedMediaSourceId === source.id ? AppTheme.accentSoft : AppTheme.surface)
          .border({ width: 1, color: this.selectedMediaSourceId === source.id ? AppTheme.primary : AppTheme.divider,
            radius: AppTheme.pillRadius })
          .accessibilityDescription(this.selectedMediaSourceId === source.id ? '当前远程数据源' : '切换远程数据源')
          .onClick(() => {
            if (this.selectedMediaSourceId !== source.id) {
              this.InvalidateRemoteDirectoryLoads();
              this.selectedMediaSourceId = source.id;
              this.toolFilePath = '/';
              this.LoadToolFileDirectory();
            }
          })
      }, (source: RemoteMediaSource) => source.id)
    }.width('100%')
  }

  @Builder
  private ToolFileRow(item: RemoteMediaItem) {
    Row({ space: 12 }) {
      Button() {
        Row({ space: 12 }) {
          Text(item.kind === 'directory' ? '▰' : '▤').width(38).height(38).textAlign(TextAlign.Center)
            .fontSize(18).fontColor(AppTheme.primary).backgroundColor(AppTheme.surfaceTint).borderRadius(10)
          Column({ space: 4 }) {
            Text(item.name).width('100%').maxLines(1).fontSize(15).fontColor(AppTheme.textPrimary)
            if (item.kind !== 'directory') {
              Text(`${this.FormatMediaSize(item.size)} · ${item.contentType.length > 0 ? item.contentType : '文件'}`)
                .fontSize(12).fontColor(AppTheme.textSecondary)
            }
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          if (item.kind === 'directory') Text('›').fontSize(22).fontColor(AppTheme.textSecondary)
        }.width('100%')
      }.layoutWeight(1).height(58).padding({ left: 0, right: 0 }).backgroundColor(Color.Transparent)
        .accessibilityText(`${item.kind === 'directory' ? '打开远程目录' : '远程文件'}：${item.name}`)
        .accessibilityDescription(item.kind === 'directory' ? '进入该远程目录' : '使用右侧管理按钮选择文件操作')
        .onClick(() => {
          if (item.kind === 'directory') {
            this.toolFilePath = item.path;
            this.LoadToolFileDirectory();
          }
        })
      if (item.name !== '返回上级目录') {
        Button('•••').width(48).height(48).fontSize(18).fontColor(AppTheme.primary)
          .backgroundColor(Color.Transparent).accessibilityText(`管理${item.name}`).onClick(() => {
          this.SelectToolFile(item);
        })
      }
    }.width('100%').height(70)
    .border({ width: { bottom: 1 }, color: AppTheme.divider })
  }

  @Builder
  private ToolFileOperationPanel() {
    Column({ space: 12 }) {
      Text(this.toolOperation === 'mkdir' ? '新建文件夹' : this.toolSelectedName)
        .width('100%').maxLines(1).fontSize(16)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Text(this.toolOperation === 'mkdir' ? this.toolFilePath : this.toolSelectedPath)
        .width('100%').maxLines(2).fontSize(12).fontColor(AppTheme.textSecondary)
      if (this.toolOperation === 'select') {
        if (this.toolOperationBusy && this.activeDownloadTaskId.length > 0) {
          Button('取消当前下载').width('100%').height(48).fontColor(AppTheme.danger).backgroundColor(Color.Transparent)
            .border({ width: 1, color: AppTheme.danger, radius: 12 }).onClick(() => this.CancelActiveDownload())
        }
        Row({ space: 8 }) {
          Button('重命名').layoutWeight(1).height(48).onClick(() => {
            this.toolOperation = 'rename';
            this.toolOperationInput = this.toolSelectedName;
          })
          Button('移动').layoutWeight(1).height(48).onClick(() => {
            this.toolOperation = 'move';
            this.toolOperationInput = this.toolFilePath;
          })
          if (!this.toolSelectedDirectory) {
            Button(this.toolOperationBusy ? '下载中' : '下载').layoutWeight(1).height(48)
              .enabled(!this.toolOperationBusy)
              .fontColor(Color.White).backgroundColor(AppTheme.primary).borderRadius(AppTheme.pillRadius)
              .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
              .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
              .onClick(() => this.DownloadToolFile())
          }
          Button('删除').layoutWeight(1).height(48).fontColor(AppTheme.danger).backgroundColor(AppTheme.dangerSoft).onClick(() => {
            this.toolOperation = 'delete';
          })
        }.width('100%')
      } else if (this.toolOperation === 'rename' || this.toolOperation === 'move' || this.toolOperation === 'mkdir') {
        Text(this.toolOperation === 'rename' ? '输入新名称' :
          (this.toolOperation === 'move' ? '输入目标目录绝对路径' : '输入文件夹名称'))
          .width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
        AppGlassTextInput({ value: this.toolOperationInput,
          valueChanged: (value: string) => this.toolOperationInput = value })
        Row({ space: 10 }) {
          Button('取消').layoutWeight(1).height(48).backgroundColor(AppTheme.surfaceTint)
            .fontColor(AppTheme.textPrimary).onClick(() => this.CloseToolOperation())
          Button(this.toolOperation === 'rename' ? '确认重命名' :
            (this.toolOperation === 'move' ? '确认移动' : '确认新建')).layoutWeight(1)
            .height(48).fontColor(Color.White).backgroundColor(AppTheme.primary)
            .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
            .borderRadius(AppTheme.pillRadius)
            .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
            .enabled(!this.toolOperationBusy).onClick(() => this.ConfirmToolOperation())
        }.width('100%')
      } else {
        Text(this.toolSelectedDirectory ?
          '将递归删除此目录及其中全部内容，此操作不可撤销。' : '将永久删除这个远程文件，此操作不可撤销。')
          .width('100%').fontSize(13).fontColor(AppTheme.danger)
        Row({ space: 10 }) {
          Button('取消').layoutWeight(1).height(48).backgroundColor(AppTheme.surfaceTint)
            .fontColor(AppTheme.textPrimary).onClick(() => this.CloseToolOperation())
          Button(this.toolSelectedDirectory ? '确认删除目录' : '确认删除文件').layoutWeight(1)
            .height(48)
            .fontColor(Color.White).backgroundColor(AppTheme.danger).enabled(!this.toolOperationBusy)
            .onClick(() => this.ConfirmToolOperation())
        }.width('100%')
      }
    }.width('100%').padding(16).borderRadius(16).backgroundColor(AppTheme.surfaceTint)
  }

  @Builder
  private ToolEntryRow(title: string, subtitle: string, action: () => void) {
    Button() {
      Row() {
        Column({ space: 4 }) {
          Text(title).fontSize(16).fontColor(AppTheme.textPrimary)
          Text(subtitle).fontSize(13).fontColor(AppTheme.textSecondary)
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        Text('›').fontSize(24).fontColor(AppTheme.primary)
      }.width('100%')
    }.width('100%').height(62).padding({ left: 0, right: 0 }).backgroundColor(Color.Transparent)
      .accessibilityText(`工具：${title}`).accessibilityDescription(subtitle).onClick(action)
  }

  @Builder
  private RecentToolCard(id: string) {
    Button() {
      Column({ space: 7 }) {
        Text(this.RecentToolSymbol(id)).fontSize(23).fontColor(AppTheme.primary)
        Text(this.RecentToolTitle(id)).width('100%').maxLines(1).textAlign(TextAlign.Center)
          .fontSize(14).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
        Text(this.RecentToolSubtitle(id)).width('100%').maxLines(2).textAlign(TextAlign.Center)
          .fontSize(11).fontColor(AppTheme.textSecondary)
      }.width('100%').height('100%').justifyContent(FlexAlign.Center)
    }.layoutWeight(1).height(126).padding({ left: 8, right: 8, top: 14, bottom: 10 })
      .backgroundColor(AppTheme.surfaceTint).borderRadius(16)
      .accessibilityText(`最近使用：${this.RecentToolTitle(id)}`)
      .accessibilityDescription(this.RecentToolSubtitle(id))
      .onClick(() => this.OpenRecentTool(id))
  }

  @Builder
  private ProfileInfoRow(label: string, value: string) {
    Row({ space: 16 }) {
      Text(label).fontSize(15).fontColor(AppTheme.textPrimary)
      Text(value).layoutWeight(1).maxLines(2).textAlign(TextAlign.End)
        .fontSize(13).fontColor(AppTheme.textSecondary)
    }.width('100%').padding({ top: 12, bottom: 12 })
    .border({ width: { bottom: 1 }, color: AppTheme.divider })
  }

  @Builder
  private CopilotPage() {
    if (!this.copilotInitialized) {
      this.HeroPanel('MyTools DSH', '连接远程Ubuntu上的DSH，会话由MyTools账户隔离', '连接 DSH', () => {
        this.InitializeCopilot();
      })
    } else {
      Row() {
        Text('DeepSeek DSH').layoutWeight(1).fontSize(13).fontColor(AppTheme.textSecondary)
        Text(this.copilotVersion).fontSize(12).fontColor(AppTheme.primary)
        Button('设置').width(64).height(48).fontSize(13).fontColor(AppTheme.primary)
          .backgroundColor(Color.Transparent).onClick(() => this.copilotSettingsVisible = !this.copilotSettingsVisible)
      }.width('100%')
      if (this.copilotSettingsVisible) this.CopilotSettingsPanel()
      Row() {
        Text('会话').layoutWeight(1).fontSize(18).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
        Button('删除').width(64).height(48).fontSize(13).fontColor(AppTheme.danger)
          .backgroundColor(Color.Transparent)
          .enabled(this.activeCopilotConversationId.length > 0 && !this.IsCopilotTurnActive() &&
            !this.copilotBusy && this.copilotRecoveryPlan === undefined)
          .onClick(() => this.copilotDeleteConfirm = true)
        Button('新会话').width(80).height(48).fontSize(13).fontColor(AppTheme.primary)
          .backgroundColor(AppTheme.surfaceTint)
          .enabled(!this.IsCopilotTurnActive() && !this.copilotBusy && this.copilotRecoveryPlan === undefined)
          .onClick(() => this.StartNewCopilotConversation(true))
      }.width('100%')
      if (this.copilotDeleteConfirm) {
        Column({ space: 10 }) {
          Text('确认删除当前会话？').width('100%').fontSize(16).fontWeight(FontWeight.Bold)
            .fontColor(AppTheme.textPrimary)
          Text('将从当前MyTools账户归档该DSH会话，归档后不再展示。')
            .width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
          Row({ space: 10 }) {
            Button('取消').layoutWeight(1).height(48).fontColor(AppTheme.textPrimary)
              .backgroundColor(AppTheme.surface).onClick(() => this.copilotDeleteConfirm = false)
            Button('确认删除').layoutWeight(1).height(48).fontColor(Color.White).backgroundColor(AppTheme.danger)
              .onClick(() => this.DeleteCurrentCopilotConversation())
          }.width('100%')
        }.width('100%').padding(16).borderRadius(16).backgroundColor(AppTheme.dangerSoft)
      }
      if (this.CurrentCopilotConversations().length > 0) {
        Scroll() {
          Row({ space: 8 }) {
            ForEach(this.CurrentCopilotConversations(), (conversation: CopilotConversation) => {
              Button(conversation.title)
                .height(44).constraintSize({ minWidth: 96, maxWidth: 180 }).fontSize(13)
                .fontColor(this.activeCopilotConversationId === conversation.id ? Color.White : AppTheme.textPrimary)
                .backgroundColor(this.activeCopilotConversationId === conversation.id ? AppTheme.primary :
                  AppTheme.surfaceTint)
                .enabled(!this.IsCopilotTurnActive() && !this.copilotBusy && this.copilotRecoveryPlan === undefined)
                .onClick(() => this.SwitchCopilotConversation(conversation.id))
            }, (conversation: CopilotConversation) => conversation.id)
          }
        }.width('100%').scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off)
      }
      if (this.copilotMessages.length === 0) {
        this.HeroPanel('你好，我是 DSH', '由Ubuntu远程运行，支持持久会话和文本附件', '介绍你的能力', () => {
          this.copilotInput = '请简要介绍你当前可用的能力。';
        })
        this.SectionTitle('试试这样问')
        this.CopilotSuggestion('帮我分析这段文本的重点', '可先选择一个文本附件')
        this.CopilotSuggestion('制定一个清晰的执行计划', '结果保存在远程会话中')
      } else {
        ForEach(this.copilotMessages, (message: CopilotMessage) => {
          this.CopilotMessageBubble(message)
        }, (message: CopilotMessage) => message.id)
      }
      if (this.copilotTurn?.permission !== undefined) this.CopilotPermissionCard()
      if (this.copilotRecoveryPlan !== undefined) this.CopilotRecoveryCard()
      if (this.copilotStatus.length > 0) {
        Text(this.copilotStatus).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
      }
      if (this.copilotRunSteps.length > 0) {
        Column({ space: 8 }) {
          Text('运行步骤').width('100%').fontSize(16).fontWeight(FontWeight.Bold)
            .fontColor(AppTheme.textPrimary)
          ForEach(this.copilotRunSteps, (step: CopilotRunStep) => {
            Row({ space: 10 }) {
              Text(this.CopilotRunStepSymbol(step.status)).width(28).height(28).textAlign(TextAlign.Center)
                .fontSize(13).fontColor(step.status === 'failed' ? AppTheme.danger : AppTheme.primary)
                .backgroundColor(step.status === 'failed' ? AppTheme.dangerSoft : AppTheme.surface).borderRadius(14)
              Text(step.label).layoutWeight(1).fontSize(13).fontColor(AppTheme.textPrimary)
            }.width('100%')
          }, (step: CopilotRunStep) => step.id)
        }.width('100%').padding(14).borderRadius(14).backgroundColor(AppTheme.surfaceTint)
      }
      if (this.copilotPollingTimedOut && this.IsCopilotTurnActive()) {
        Button('刷新当前轮次状态').width('100%').height(48)
          .enabled(!this.copilotBusy)
          .backgroundColor(AppTheme.surfaceTint).fontColor(AppTheme.primary)
          .onClick(() => this.RefreshCurrentCopilotTurn())
      }
      if (this.IsCopilotTurnActive()) {
        Button(this.copilotCancelling ? '正在提交取消…' : '取消当前轮次').width('100%').height(48)
          .enabled(!this.copilotCancelling)
          .backgroundColor(AppTheme.dangerSoft).fontColor(AppTheme.danger)
          .onClick(() => this.CancelCurrentCopilotTurn())
      }
      if (this.copilotAttachment !== undefined) {
        Row({ space: 10 }) {
          Column({ space: 3 }) {
            Text(this.copilotAttachment?.name ?? '').width('100%').maxLines(1).fontSize(14)
              .fontColor(AppTheme.textPrimary)
            Text(`${this.copilotAttachment?.mimeType ?? ''} · ${this.FormatFileSize(this.copilotAttachment?.size ?? 0)}`)
              .fontSize(12).fontColor(AppTheme.textSecondary)
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Button('移除').width(56).height(48).fontSize(13).fontColor(AppTheme.danger)
            .backgroundColor(Color.Transparent).enabled(!this.copilotBusy && !this.IsCopilotTurnActive())
            .onClick(() => {
              this.copilotAttachment = undefined;
              this.copilotAttachmentStatus = '';
            })
        }.width('100%').padding({ left: 12, right: 8, top: 6, bottom: 6 })
          .borderRadius(12).backgroundColor(AppTheme.surfaceTint)
      }
      if (this.copilotAttachmentStatus.length > 0) {
        Text(this.copilotAttachmentStatus).width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
      }
      Row({ space: 10 }) {
        Button('附件').width(64).height(50).fontSize(13).fontColor(AppTheme.primary)
          .backgroundColor(AppTheme.surfaceTint)
          .enabled(!this.copilotBusy && !this.IsCopilotTurnActive())
          .onClick(() => this.SelectCopilotTextAttachment())
        TextInput({ text: this.copilotInput, placeholder: '给 DSH 发消息' })
          .layoutWeight(1).height(50).backgroundColor(AppTheme.surface)
          .border({ width: 1, color: AppTheme.divider, radius: 16 })
          .onChange((value: string) => this.copilotInput = value)
          .onSubmit(() => this.SendCopilotMessage())
        Button(this.copilotBusy ? '处理中' : (this.IsCopilotTurnActive() ? '等待当前轮次' : '发送'))
          .height(50).enabled(!this.copilotBusy && !this.IsCopilotTurnActive()).backgroundColor(AppTheme.primary)
          .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
          .borderRadius(AppTheme.pillRadius)
          .onClick(() => this.SendCopilotMessage())
      }.width('100%')
    }
  }

  @Builder
  private DshTabPage() {
    // DSH已经具备完整网页导航，WebView直接占满主内容区，避免重复页头挤压操作空间。
    Web({ src: this.dshWebUrl, controller: this.dshWebController })
      .javaScriptAccess(true)
      .domStorageAccess(true)
      .databaseAccess(true)
      .zoomAccess(true)
      .fileAccess(false)
      .width('100%')
      .height('100%')
      .onTouch((event: TouchEvent) => {
        if (event.type !== TouchType.Down || event.touches.length === 0) return;
        const point = event.touches[0];
        // DSH主页面左上角仅保留侧栏按钮，记录打开动作后由原生遮罩接管点外关闭。
        if (point.x < 140 && point.y < 260) this.dshSidebarOverlayVisible = true;
      })
      .onPageBegin(() => {
        this.dshWebLoading = true;
        this.dshWebStatus = '正在打开 DSH';
      })
      .onPageEnd(() => {
        this.dshWebLoading = false;
        this.dshWebStatus = 'DSH 网页已连接';
        this.InstallDshDismissBehavior();
      })
      .onErrorReceive((event) => {
        this.dshWebLoading = false;
        this.dshWebStatus = `加载失败：${event.error.getErrorInfo()}`;
      })
  }

  /** 安全刷新DSH网页，并在控制器尚未就绪时向用户反馈。 */
  private RefreshDshWeb(): void {
    try {
      this.dshWebController.refresh();
    } catch (_) {
      this.dshWebLoading = false;
      this.dshWebStatus = 'DSH 网页刷新失败';
    }
  }

  /** 关闭DSH侧栏，并同步移除原生点外遮罩。 */
  private CloseDshSidebar(): void {
    this.dshSidebarOverlayVisible = false;
    const script = `(function(){
      function closeIn(targetWindow){
        try{
          var targetDocument=targetWindow.document;
          var probe=targetDocument.elementFromPoint(targetWindow.innerWidth*0.675,60);
          var probeButton=probe&&probe.closest?probe.closest('button,[role="button"]'):null;
          if(probeButton){probeButton.click();return true;}
          var buttons=Array.prototype.slice.call(targetDocument.querySelectorAll('button,[role="button"]'));
          var closeButton=buttons.filter(function(button){
            var rect=button.getBoundingClientRect();
            var label=((button.getAttribute('aria-label')||'')+' '+(button.getAttribute('title')||'')+' '+(button.textContent||'')).toLowerCase();
            return rect.width>0&&rect.height>0&&(/收起|关闭.*侧边栏|collapse|close.*sidebar/.test(label)||
              (rect.width<140&&rect.height<140&&rect.top<260&&rect.left>targetWindow.innerWidth*0.75));
          }).sort(function(left,right){return right.getBoundingClientRect().left-left.getBoundingClientRect().left;})[0];
          if(closeButton){closeButton.click();return true;}
          for(var index=0;index<targetWindow.frames.length;index++){
            if(closeIn(targetWindow.frames[index]))return true;
          }
          targetDocument.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true}));
        }catch(ignore){}
        return false;
      }
      return closeIn(window);
    })();`;
    this.dshWebController.runJavaScript(script).catch(() => {
      // 网页正处于切换状态时，遮罩仍需按用户操作立即关闭。
    });
  }

  /** 为DSH网页补充移动端点外部关闭侧栏和悬浮提示的统一行为。 */
  private InstallDshDismissBehavior(): void {
    const script = `(function(){
      if(window.__mytoolsDismissInstalled)return;
      window.__mytoolsDismissInstalled=true;
      var dismiss=function(event){
        var target=event.target;
        if(!(target instanceof Element))return;
        var topRightButtons=Array.prototype.slice.call(document.querySelectorAll('button,[role="button"]')).filter(function(button){
          var rect=button.getBoundingClientRect();
          return rect.width>0&&rect.height>0&&rect.width<140&&rect.height<140&&
            rect.top<260&&rect.left>window.innerWidth*0.75;
        }).sort(function(left,right){
          return right.getBoundingClientRect().left-left.getBoundingClientRect().left;
        });
        var drawerCloseButton=topRightButtons[0];
        if(drawerCloseButton&&event.clientX>window.innerWidth*0.75&&!drawerCloseButton.contains(target)){
          drawerCloseButton.click();
          return;
        }
        var trigger=target.closest('[aria-describedby],[data-tooltip-trigger],[data-radix-tooltip-trigger]');
        var floating=document.querySelectorAll('[role="tooltip"],[data-radix-popper-content-wrapper],[data-tooltip-content]');
        var outsideFloating=true;
        var visibleFloating=0;
        floating.forEach(function(node){
          var rect=node.getBoundingClientRect();
          if(rect.width>0&&rect.height>0){visibleFloating++;if(node.contains(target))outsideFloating=false;}
        });
        var sidebar=document.querySelector('aside,[data-sidebar],[data-slot="sidebar"]');
        var sidebarRect=sidebar?sidebar.getBoundingClientRect():null;
        var sidebarVisible=sidebar&&sidebarRect&&sidebar.getAttribute('aria-hidden')!=='true'&&
          sidebarRect.width>0&&sidebarRect.height>0&&sidebarRect.right>0&&sidebarRect.left<window.innerWidth;
        var outsideSidebar=sidebarVisible&&!sidebar.contains(target);
        if(outsideSidebar){
          var sidebarButtons=Array.prototype.slice.call(sidebar.querySelectorAll('button,[role="button"]'));
          var collapseButton=sidebarButtons.find(function(button){
            var label=((button.getAttribute('aria-label')||'')+' '+(button.getAttribute('title')||'')+' '+(button.textContent||'')).toLowerCase();
            return /收起|关闭.*侧边栏|collapse|close.*sidebar/.test(label);
          });
          if(!collapseButton){
            collapseButton=sidebarButtons.filter(function(button){
              var rect=button.getBoundingClientRect();
              return rect.width>0&&rect.height>0&&rect.top<sidebarRect.top+180&&
                rect.right>sidebarRect.right-180;
            }).sort(function(left,right){
              return right.getBoundingClientRect().right-left.getBoundingClientRect().right;
            })[0];
          }
          if(collapseButton){collapseButton.click();return;}
        }
        if((visibleFloating>0&&outsideFloating&&!trigger)||outsideSidebar){
          document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true}));
        }
      };
      document.addEventListener('pointerdown',dismiss,true);
      document.addEventListener('touchstart',dismiss,true);
      document.addEventListener('click',dismiss,true);
    })();`;
    this.dshWebController.runJavaScript(script).catch(() => {
      // 网页脚本尚未就绪时不影响DSH主体继续加载。
    });
  }

  @Builder
  private CopilotSettingsPanel() {
    Column({ space: 12 }) {
      Row() {
        Text('DSH 设置').layoutWeight(1).fontSize(17).fontWeight(FontWeight.Bold)
          .fontColor(AppTheme.textPrimary)
        Button('关闭').width(64).height(48).fontSize(13).fontColor(AppTheme.primary)
          .backgroundColor(Color.Transparent).onClick(() => this.copilotSettingsVisible = false)
      }.width('100%')
      this.ProfileInfoRow('连接方式', 'MyTools 服务端网关')
      this.ProfileInfoRow('当前模型', this.copilotGatewayModel.length > 0 ? this.copilotGatewayModel : '等待服务端配置')
      this.ProfileInfoRow('会话范围', '固定Ubuntu工作区，由MyTools账户隔离')
      this.ProfileInfoRow('工具授权', '使用DSH授权协议，不沿用旧SDK Host工具')
      Row({ space: 12 }) {
        Column({ space: 3 }) {
          Text('保存会话预览').width('100%').fontSize(15).fontColor(AppTheme.textPrimary)
          Text('仅在本机保存标题和最近20条展示消息；DSH服务器历史不受此开关影响')
            .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        Toggle({ type: ToggleType.Switch, isOn: this.copilotPersistPreview })
          .selectedColor(AppTheme.primary).onChange((value: boolean) => this.SetCopilotPreviewPersistence(value))
      }.width('100%').constraintSize({ minHeight: 64 })
      Text('模型由已登录的MyTools服务端管理。JWT、模型密钥、远程路径和设备文件URI不会显示在此处。')
        .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
    }.width('100%').padding(16).borderRadius(16).backgroundColor(AppTheme.surfaceTint)
  }

  @Builder
  private CopilotSuggestion(title: string, subtitle: string) {
    Row() {
      Column({ space: 4 }) {
        Text(title).fontSize(16).fontColor(AppTheme.textPrimary)
        Text(subtitle).fontSize(13).fontColor(AppTheme.textSecondary)
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      Text('›').fontSize(24).fontColor(AppTheme.primary)
    }.width('100%').padding({ top: 12, bottom: 12 })
    .onClick(() => this.copilotInput = title)
  }

  @Builder
  private CopilotMessageBubble(message: CopilotMessage) {
    Row() {
      if (message.role === 'assistant') Blank().width(0)
      Text(message.text)
        .constraintSize({ maxWidth: '84%' })
        .fontSize(15).fontColor(message.role === 'user' ? Color.White : AppTheme.textPrimary)
        .padding(14).borderRadius(16)
        .backgroundColor(message.role === 'user' ? AppTheme.primary : AppTheme.surfaceTint)
      if (message.role === 'user') Blank().layoutWeight(1)
    }.width('100%').justifyContent(message.role === 'user' ? FlexAlign.End : FlexAlign.Start)
  }

  private CopilotRunStepSymbol(status: string): string {
    if (status === 'failed') return '!';
    if (status === 'waiting') return '…';
    if (status === 'active') return '•';
    return '✓';
  }

  @Builder
  private CopilotPermissionCard() {
    Column({ space: 12 }) {
      Text('需要你的确认').width('100%').fontSize(19).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Text(`操作：${this.copilotTurn?.permission?.toolName ?? ''}`)
        .width('100%').fontSize(15).fontColor(AppTheme.textPrimary)
      Text(this.copilotTurn?.permission?.reason ?? '该操作会修改数据')
        .width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
      Text(`范围：${this.copilotTurn?.permission?.patterns.join('、') ?? ''}`)
        .width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
      Row({ space: 12 }) {
        Button('拒绝操作').layoutWeight(1).fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surfaceTint)
          .onClick(() => this.ReplyCopilotPermission(false))
        Button('仅本次允许').layoutWeight(1).backgroundColor(AppTheme.primary)
          .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
          .borderRadius(AppTheme.pillRadius)
          .onClick(() => this.ReplyCopilotPermission(true))
      }.width('100%')
    }.width('100%').padding(18).borderRadius(18).backgroundColor('#FFF3F0')
  }

  @Builder
  private CopilotRecoveryCard() {
    Column({ space: 12 }) {
      Text('发现未完成任务').width('100%').fontSize(19).fontWeight(FontWeight.Bold)
        .fontColor(AppTheme.textPrimary)
      Text(`状态：${this.copilotRecoveryPlan?.recoveryStatus ?? ''}`)
        .width('100%').fontSize(14).fontColor(AppTheme.textPrimary)
      Text(this.copilotRecoveryPlan?.reason ?? 'DSH会话可从服务器历史恢复')
        .width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
      Text(`建议：${this.copilotRecoveryPlan?.recommendedAction ?? ''}`)
        .width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
      Row({ space: 12 }) {
        Button('稍后处理').layoutWeight(1).fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surfaceTint)
          .onClick(() => this.copilotRecoveryPlan = undefined)
        Button('恢复执行').layoutWeight(1).backgroundColor(AppTheme.primary)
          .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
          .borderRadius(AppTheme.pillRadius)
          .enabled(this.copilotRecoveryPlan?.recommendedAction === 'continue' ||
            this.copilotRecoveryPlan?.recommendedAction === 'retry')
          .onClick(() => this.RecoverCopilotTurn())
      }.width('100%')
    }.width('100%').padding(18).borderRadius(18).backgroundColor('#FFF8E8')
  }

  @Builder
  private MediaPage() {
    if (!this.authenticated) {
      Scroll(this.tabScrollers[3]) {
        this.HeroPanel('登录后查看多媒体', '统一浏览 DownloadBot 与人工扫描入库的媒体', '登录', () => this.OpenLogin())
      }.width('100%').layoutWeight(1).align(Alignment.TopStart)
        .edgeEffect(EdgeEffect.None).scrollBar(BarState.Off)
    } else {
      this.MediaCatalogToolbar()
      Refresh({ refreshing: $$this.mediaCatalogRefreshing }) {
        Scroll(this.tabScrollers[3]) {
          Column({ space: 14 }) {
            if (this.mediaCatalogStatus.length > 0) {
              Row({ space: 8 }) {
                Text(this.mediaCatalogStatus).layoutWeight(1).fontSize(13).fontColor(AppTheme.textSecondary)
                Button('重试').height(38).fontSize(13).fontColor(AppTheme.primary)
                  .backgroundColor(AppTheme.surfaceTint).onClick(() => this.LoadMediaCatalog())
              }.width('100%').padding(10).borderRadius(12).backgroundColor(AppTheme.surface)
            }
            if (this.mediaCatalogLoading && this.mediaCatalogItems.length === 0 &&
              this.mediaVideoDirectories.length === 0) {
              this.NetworkLoadingRows(this.mediaCatalogMode === 'gallery' ? '正在读取图库' :
                (this.mediaCatalogMode === 'audio' ? '正在读取音频专辑' : '正在读取视频目录'))
            } else if (this.mediaCatalogMode === 'gallery') {
              this.MediaCatalogGallery()
            } else {
              this.MediaCatalogVideos()
            }
          }.width('100%').padding({ bottom: 18 }).alignItems(HorizontalAlign.Start)
        }
        .width('100%')
        .height('100%')
        .align(Alignment.TopStart)
        .edgeEffect(EdgeEffect.None)
        .scrollBar(BarState.Off)
        .enableScrollInteraction(this.mediaSelectorMode.length === 0 &&
          !this.mediaCatalogDirectorySelectorVisible && !this.mediaCatalogTagSelectorVisible)
        .onReachEnd(() => this.LoadMoreMediaCatalog())
      }
      .width('100%')
      .layoutWeight(1)
      .onRefreshing(() => this.RefreshMediaCatalog())
    }
  }

  @Builder
  private MediaCatalogToolbar() {
    Row({ space: 8 }) {
      this.MediaCatalogFilterButton('directory')
      this.MediaCatalogFilterButton('tag')
      Row({ space: 8 }) {
        SymbolGlyph($r('sys.symbol.magnifyingglass')).fontSize(18)
          .fontColor([this.mediaCatalogKeyword.length > 0 ? AppTheme.primary : AppTheme.textSecondary])
        TextInput({ text: this.mediaCatalogKeyword, placeholder: '搜索媒体' }).layoutWeight(1).height(46)
          .padding(0).maxLength(100).backgroundColor(Color.Transparent)
          .onChange((value: string) => this.mediaCatalogKeyword = value)
          .onSubmit(() => this.LoadMediaCatalog())
        if (this.mediaCatalogKeyword.length > 0) {
          Button() {
            SymbolGlyph($r('sys.symbol.xmark')).fontSize(15).fontColor([AppTheme.textSecondary])
          }.width(40).height(40).padding(0).borderRadius(20).backgroundColor(Color.Transparent)
            .accessibilityText('清除媒体搜索').onClick(() => {
              this.mediaCatalogKeyword = '';
              this.LoadMediaCatalog();
            })
        }
      }.layoutWeight(1).height(48).padding({ left: 12, right: 5 })
        .backgroundColor(this.mediaCatalogKeyword.length > 0 ? AppTheme.accentSoft : AppTheme.glass)
        .border({ width: 1, color: this.mediaCatalogKeyword.length > 0 ? AppTheme.primary : AppTheme.divider,
          radius: AppTheme.inputRadius })
        .borderRadius(AppTheme.inputRadius)
        .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
    }.width('100%')
  }

  @Builder
  private MediaCatalogFilterButton(mode: string) {
    Button() {
      Row({ space: 4 }) {
        Text(mode === 'directory' ? this.SelectedMediaCatalogDirectoryName() :
          (this.mediaCatalogTag.length > 0 ? this.mediaCatalogTag : '全部标签'))
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).fontSize(12)
        SymbolGlyph($r('sys.symbol.chevron_down')).fontSize(12)
          .fontColor([this.MediaCatalogFilterSelected(mode) ? AppTheme.primary : AppTheme.textSecondary])
      }
    }.width(94).height(48).padding({ left: 8, right: 8 }).fontColor(AppTheme.textPrimary)
      .backgroundColor(this.MediaCatalogFilterSelected(mode) ? AppTheme.accentSoft : AppTheme.surface)
      .border({ width: 1, color: this.MediaCatalogFilterSelected(mode) ? AppTheme.primary : AppTheme.divider,
        radius: 16 })
      .accessibilityText(mode === 'directory' ? this.SelectedMediaCatalogDirectoryName() :
        (this.mediaCatalogTag.length > 0 ? this.mediaCatalogTag : '全部标签'))
      .accessibilityDescription(mode === 'directory' ? '打开目录选择器' : '打开标签选择器')
      .onClick(() => {
        this.mediaCatalogSelectorQuery = '';
        if (mode === 'directory') {
          this.mediaCatalogDirectoryParentId = this.mediaCatalogFilters.directories.find(
            (value: MediaDirectoryFilter) => value.directoryId === this.mediaCatalogDirectoryId)?.parentDirectoryId ?? '';
        }
        this.mediaCatalogDirectorySelectorVisible = mode === 'directory' && !this.mediaCatalogDirectorySelectorVisible;
        this.mediaCatalogTagSelectorVisible = mode === 'tag' && !this.mediaCatalogTagSelectorVisible;
      })
  }

  @Builder
  private MediaCatalogSelectorSheet() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column().width('100%').height('100%').backgroundColor('#66000000')
        .onClick(() => this.CloseMediaCatalogSelector())
      Column({ space: 12 }) {
        Row() {
          Button('关闭').width(64).height(48).padding(0).fontSize(14).fontColor(AppTheme.textSecondary)
            .backgroundColor(Color.Transparent).onClick(() => this.CloseMediaCatalogSelector())
          Text(this.mediaCatalogDirectorySelectorVisible ? '选择目录' : '选择标签')
            .layoutWeight(1).textAlign(TextAlign.Center).fontSize(18).fontWeight(FontWeight.Bold)
            .fontColor(AppTheme.textPrimary)
          Button('清除').width(64).height(48).padding(0).fontSize(14)
            .fontColor(this.MediaCatalogSelectorHasValue() ? AppTheme.primary : AppTheme.textSecondary)
            .backgroundColor(Color.Transparent).enabled(this.MediaCatalogSelectorHasValue())
            .onClick(() => this.ClearMediaCatalogSelector())
        }.width('100%')
        Row({ space: 8 }) {
          SymbolGlyph($r('sys.symbol.magnifyingglass')).fontSize(18)
            .fontColor([this.mediaCatalogSelectorQuery.length > 0 ? AppTheme.primary : AppTheme.textSecondary])
          TextInput({ text: this.mediaCatalogSelectorQuery,
            placeholder: this.mediaCatalogDirectorySelectorVisible ? '搜索目录' : '搜索标签' })
            .layoutWeight(1).height(48).padding(0).fontSize(15).backgroundColor(Color.Transparent)
            .onChange((value: string) => this.mediaCatalogSelectorQuery = value)
          if (this.mediaCatalogSelectorQuery.length > 0) {
            Button() {
              SymbolGlyph($r('sys.symbol.xmark')).fontSize(14).fontColor([AppTheme.textSecondary])
            }.width(40).height(40).padding(0).backgroundColor(Color.Transparent)
              .accessibilityText('清除筛选项搜索').onClick(() => this.mediaCatalogSelectorQuery = '')
          }
        }.width('calc(100% - 36vp)').height(52).padding({ left: 14, right: 8 })
          .border({ width: 1, color: this.mediaCatalogSelectorQuery.length > 0 ? AppTheme.primary : AppTheme.divider,
            radius: 16 }).backgroundColor(AppTheme.surfaceTint)
        Text(this.MediaCatalogSelectorSummary()).width('calc(100% - 36vp)').fontSize(12)
          .fontColor(AppTheme.textSecondary)
        List({ space: 2 }) {
          if (this.mediaCatalogDirectorySelectorVisible && this.mediaCatalogDirectoryParentId.length > 0) {
            ListItem() {
              Button() {
                Row({ space: 8 }) {
                  SymbolGlyph($r('sys.symbol.chevron_left')).fontSize(16).fontColor([AppTheme.primary])
                  Text('返回月份').fontSize(15).fontColor(AppTheme.primary)
                }.width('100%')
              }.width('100%').height(48).padding({ left: 14, right: 14 }).backgroundColor(Color.Transparent)
                .onClick(() => {
                  this.mediaCatalogDirectoryParentId = '';
                  this.mediaCatalogSelectorQuery = '';
                })
            }
          }
          ListItem() {
            this.MediaCatalogSelectorRow('', this.mediaCatalogDirectorySelectorVisible ? '全部目录' : '全部标签', 0)
          }
          if (this.mediaCatalogDirectorySelectorVisible) {
            ForEach(this.FilteredMediaCatalogDirectories(), (directory: MediaDirectoryFilter) => {
              ListItem() {
                this.MediaCatalogSelectorRow(directory.directoryId, directory.name, directory.fileCount)
              }
            }, (directory: MediaDirectoryFilter) => directory.directoryId)
          } else {
            ForEach(this.FilteredMediaCatalogTags(), (tag: MediaTagFilter) => {
              ListItem() {
                this.MediaCatalogSelectorRow(tag.name, tag.name, tag.fileCount)
              }
            }, (tag: MediaTagFilter) => tag.name)
          }
        }.width('100%').layoutWeight(1).padding({ left: 12, right: 12 }).scrollBar(BarState.Auto)
      }.width('100%').height(570).padding({ top: 8, bottom: 18 })
        .borderRadius({ topLeft: 28, topRight: 28 }).backgroundColor(AppTheme.surface)
        .onClick(() => {})
    }.width('100%').height('100%')
  }

  @Builder
  private MediaCatalogSelectorRow(value: string, label: string, fileCount: number) {
    Button() {
      Row({ space: 10 }) {
        Text(label).layoutWeight(1).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          .textAlign(TextAlign.Start).fontSize(15).fontColor(AppTheme.textPrimary)
        if (fileCount > 0) {
          Text(`${fileCount}`).fontSize(12).fontColor(AppTheme.textSecondary)
        }
        if (this.IsMediaCatalogSelectorValueSelected(value)) {
          SymbolGlyph($r('sys.symbol.checkmark')).fontSize(17).fontColor([AppTheme.primary])
        }
      }.width('100%')
    }.width('100%').constraintSize({ minHeight: 52 }).padding({ left: 14, right: 14, top: 6, bottom: 6 })
      .fontColor(AppTheme.textPrimary)
      .backgroundColor(this.IsMediaCatalogSelectorValueSelected(value) ? AppTheme.surfaceTint : Color.Transparent)
      .borderRadius(14).onClick(() => {
        if (this.mediaCatalogDirectorySelectorVisible) this.SelectMediaCatalogDirectory(value, label);
        else this.SelectMediaCatalogTag(value);
      })
  }

  @Builder
  private MediaCatalogGallery() {
    if (!this.mediaCatalogLoading && this.mediaCatalogItems.length === 0) {
      this.PlaceholderRow('没有符合条件的媒体', '可切换目录、标签或搜索内容')
    }
    ForEach(this.MediaCatalogGalleryDirectories(), (directory: MediaDirectoryFilter) => {
      Column({ space: 10 }) {
        Row() {
          Text(directory.name).layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .fontSize(18).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
          Text(`${this.mediaCatalogTotal} 个`)
            .fontSize(12).fontColor(AppTheme.textSecondary)
        }.width('100%')
        Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.SpaceBetween }) {
          ForEach(this.MediaCatalogDirectoryItems(directory.directoryId), (item: MediaCatalogItem) => {
            this.MediaCatalogGalleryItem(item)
          }, (item: MediaCatalogItem) => item.itemId)
        }.width('100%')
      }.width('100%').padding({ top: 10, bottom: 14 })
        .border({ width: { bottom: 1 }, color: AppTheme.divider })
        .onVisibleAreaChange([0.2], (visible: boolean, ratio: number) => {
          if (visible && ratio >= 0.2) this.PrefetchMediaCatalogAfter(directory.directoryId);
        })
    }, (directory: MediaDirectoryFilter) => directory.directoryId)
    if (this.mediaCatalogItems.length > 0) {
      Text(`已展示 ${this.mediaCatalogItems.length}/${this.mediaCatalogTotal} 个媒体`)
        .width('100%').textAlign(TextAlign.Center).fontSize(12).fontColor(AppTheme.textSecondary)
        .padding({ top: 10, bottom: 12 })
    }
  }

  @Builder
  private MediaCatalogVideos() {
    if (this.selectedVideoDirectoryId.length > 0) {
      Row({ space: 8 }) {
        Button() { SymbolGlyph($r('sys.symbol.arrow_left')).fontSize(18) }
          .width(42).height(42).backgroundColor(AppTheme.surfaceTint).onClick(() => this.LeaveMediaVideoDirectory())
        Text(this.selectedVideoDirectoryName).layoutWeight(1).fontSize(18).fontWeight(FontWeight.Bold)
          .fontColor(AppTheme.textPrimary)
      }.width('100%')
      Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Start }) {
        ForEach(this.mediaVideoDirectoryItems, (item: MediaCatalogItem) => {
          this.MediaCatalogTopItem(item)
        }, (item: MediaCatalogItem) => item.itemId)
      }.width('100%').margin({ top: 12 })
    } else {
      if (!this.mediaCatalogLoading && this.mediaVideoDirectories.length === 0) {
        this.PlaceholderRow(this.mediaCatalogMode === 'audio' ? '没有符合条件的音频专辑' : '没有符合条件的视频目录',
          this.mediaCatalogMode === 'audio' ? '专辑中至少需要包含一个音频文件' : '目录中至少需要包含一个视频')
      }
      ForEach(this.mediaVideoDirectories, (directory: MediaVideoDirectory) => {
        Column({ space: 10 }) {
          Row() {
            Column({ space: 4 }) {
              Text(directory.name).width('100%').fontSize(18).fontWeight(FontWeight.Bold)
                .fontColor(AppTheme.textPrimary)
              Text(`${directory.fileCount} 个文件 · ${this.FormatFileSize(directory.totalSizeBytes)}`)
                .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
            }.layoutWeight(1).alignItems(HorizontalAlign.Start)
            SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(16).fontColor([AppTheme.textSecondary])
          }.width('100%').onClick(() => this.OpenMediaVideoDirectory(directory))
          Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Start }) {
            ForEach(directory.topItems, (item: MediaCatalogItem) => {
              this.MediaCatalogTopItem(item)
            }, (item: MediaCatalogItem) => item.itemId)
          }.width('100%')
        }.width('100%').padding({ top: 12, bottom: 14 }).border({ width: { bottom: 1 }, color: AppTheme.divider })
      }, (directory: MediaVideoDirectory) => directory.directoryId)
    }
  }

  @Builder
  private MediaCatalogTopItem(item: MediaCatalogItem) {
    Column({ space: 5 }) {
      Stack({ alignContent: Alignment.TopStart }) {
        Stack() {
          if (this.BrowseMediaThumbnailUri(`/media/${item.itemId}`).length > 0) {
            Image(this.BrowseMediaThumbnailUri(`/media/${item.itemId}`)).width('100%').height(82)
              .objectFit(ImageFit.Cover).borderRadius(12)
          } else {
            SymbolGlyph($r(this.MediaCatalogSymbol(item))).width('100%').height(82).fontSize(28)
              .fontColor([AppTheme.primary]).backgroundColor(AppTheme.surfaceTint).borderRadius(12)
          }
        }.width('100%').height(82)
          .onClick(() => this.TapMediaCatalogItem(item, true))
        if (item.tags.length > 0) this.MediaCatalogItemTags(item)
      }.width('100%').height(82).borderRadius(12).clip(true)
      Row({ space: 2 }) {
        Text(item.name).layoutWeight(1).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          .fontSize(11).fontColor(AppTheme.textPrimary)
          .onClick(() => this.TapMediaCatalogItem(item, true))
        this.MediaCatalogActionButton(item)
      }.width('100%')
    }.width('30.5%').margin({ right: 8, bottom: 10 })
  }

  @Builder
  private MediaCatalogGalleryItem(item: MediaCatalogItem) {
    Column({ space: 6 }) {
      Stack({ alignContent: Alignment.TopStart }) {
        Stack() {
          if (this.BrowseMediaThumbnailUri(`/media/${item.itemId}`).length > 0) {
            Image(this.BrowseMediaThumbnailUri(`/media/${item.itemId}`)).width('100%').height(154)
              .objectFit(ImageFit.Cover)
          } else {
            Column({ space: 8 }) {
              SymbolGlyph($r(this.MediaCatalogSymbol(item))).fontSize(32).fontColor([AppTheme.primary])
              Text(item.kind === 'IMAGE' || item.kind === 'VIDEO' ? '正在加载预览' : item.kind)
                .fontSize(11).fontColor(AppTheme.textSecondary)
            }.width('100%').height(154).justifyContent(FlexAlign.Center).backgroundColor(AppTheme.surfaceTint)
          }
        }.width('100%').height(154)
          // 预览区只处理查看动作，避免长按手势与普通点击竞争。
          .onClick(() => this.TapMediaCatalogItem(item, false))
        if (item.tags.length > 0) this.MediaCatalogItemTags(item)
      }.width('100%').height(154).borderRadius(14).clip(true)
        .border({ width: 1, color: AppTheme.divider, radius: 14 })
      Row({ space: 4 }) {
        Text(item.name).layoutWeight(1).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          .fontSize(11).fontColor(AppTheme.textPrimary)
          .onClick(() => this.TapMediaCatalogItem(item, false))
        this.MediaCatalogActionButton(item)
      }.width('100%').alignItems(VerticalAlign.Top)
    }.width('48.5%').margin({ bottom: 12 }).alignItems(HorizontalAlign.Start)
      .accessibilityText(`${item.kind}：${item.name}`)
  }

  @Builder
  private MediaCatalogItemTags(item: MediaCatalogItem) {
    Row({ space: 4 }) {
      ForEach(item.tags.slice(0, 3), (tag: string) => {
        Button() {
          Text(tag).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .fontSize(10).fontColor(Color.White)
        }.height(26).constraintSize({ maxWidth: 76 }).padding({ left: 7, right: 7 })
          .borderRadius(9).backgroundColor('#B30F172A')
          .accessibilityText(`按标签筛选：${tag}`)
          .onClick(() => this.SelectMediaCatalogItemTag(tag))
      }, (tag: string) => tag)
      if (item.tags.length > 3) {
        Text(`+${item.tags.length - 3}`).height(26).fontSize(10).fontColor(Color.White)
          .padding({ left: 7, right: 7 }).borderRadius(9).backgroundColor('#B30F172A')
      }
    // 标签浮层只占用实际内容宽度，避免透明区域吞掉底层图片的查看点击。
    }.padding(8)
  }

  @Builder
  private MediaCatalogActionButton(item: MediaCatalogItem) {
    Button() {
      SymbolGlyph($r('sys.symbol.ellipsis_circle')).fontSize(16).fontColor([AppTheme.textSecondary])
    }.width(30).height(30).padding(0).borderRadius(10).backgroundColor(Color.Transparent)
      .accessibilityText(`管理媒体：${item.name}`)
      .onClick(() => this.OpenMediaCatalogActionSheet(item))
  }

  private MediaCatalogGalleryDirectories(): MediaDirectoryFilter[] {
    const directories = new Map<string, MediaDirectoryFilter>();
    // Top500筛选项仅用于下拉框；页面分组必须以当前已加载条目为准，不能丢弃Top500之外的目录。
    this.mediaCatalogItems.forEach((item: MediaCatalogItem) => {
      const filter = this.mediaCatalogFilters.directories.find((value: MediaDirectoryFilter) =>
        value.directoryId === item.directoryId);
      const current = directories.get(item.directoryId);
      if (current === undefined) {
        directories.set(item.directoryId, {
          directoryId: item.directoryId,
          name: filter?.name ?? item.directoryName,
          fileCount: filter?.fileCount ?? 1,
          latestModifiedAt: filter?.latestModifiedAt ?? item.modifiedAt,
          parentDirectoryId: filter?.parentDirectoryId ?? '',
          parentDirectoryName: filter?.parentDirectoryName ?? ''
        });
      } else if (item.modifiedAt.localeCompare(current.latestModifiedAt) > 0) {
        directories.set(item.directoryId, {
          directoryId: current.directoryId,
          name: current.name,
          fileCount: current.fileCount,
          latestModifiedAt: item.modifiedAt,
          parentDirectoryId: current.parentDirectoryId,
          parentDirectoryName: current.parentDirectoryName
        });
      }
    });
    return Array.from(directories.values())
      .sort((left: MediaDirectoryFilter, right: MediaDirectoryFilter) => right.name.localeCompare(left.name));
  }

  /** 列表滚动过半后预取下一页，避免用户到达底部才开始等待网络。 */
  private PrefetchMediaCatalogAfter(directoryId: string): void {
    if (this.mediaCatalogMode !== 'gallery' || this.mediaCatalogLoading ||
      this.mediaCatalogItems.length >= this.mediaCatalogTotal) return;
    const directories = this.MediaCatalogGalleryDirectories();
    const index = directories.findIndex((directory: MediaDirectoryFilter) => directory.directoryId === directoryId);
    if (index < Math.floor(directories.length * 0.5)) return;
    this.LoadMoreMediaCatalog();
  }

  private MediaCatalogDirectoryItems(directoryId: string): MediaCatalogItem[] {
    return this.mediaCatalogItems.filter((item: MediaCatalogItem) => item.directoryId === directoryId)
      .sort((left: MediaCatalogItem, right: MediaCatalogItem) => right.modifiedAt.localeCompare(left.modifiedAt));
  }

  @Builder
  private MediaCatalogDirectoryItemRow(item: MediaCatalogItem) {
    Row({ space: 12 }) {
      SymbolGlyph($r(this.MediaCatalogSymbol(item))).width(44).height(44).fontSize(22)
        .fontColor([AppTheme.primary]).backgroundColor(AppTheme.surfaceTint).borderRadius(12)
      Column({ space: 4 }) {
        Text(item.name).width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .fontSize(15).fontColor(AppTheme.textPrimary)
        Text(`${item.kind} · ${this.FormatFileSize(item.sizeBytes)}`).fontSize(12).fontColor(AppTheme.textSecondary)
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
    }.width('100%').padding({ top: 11, bottom: 11 }).border({ width: { bottom: 1 }, color: AppTheme.divider })
      .onClick(() => this.OpenMediaCatalogItem(item, true))
  }

  private ChangeMediaCatalogMode(mode: string): void {
    if (mode !== 'gallery' && mode !== 'video' && mode !== 'audio' || mode === this.mediaCatalogMode) return;
    this.mediaCatalogMode = mode;
    // 图片和视频拥有独立目录集合，切换页面时不能沿用另一页面的目录或标签标识。
    this.mediaCatalogDirectoryId = '';
    this.mediaCatalogDirectoryParentId = '';
    this.mediaCatalogTag = '';
    this.mediaCatalogFilters = { directories: [], tags: [] };
    this.selectedVideoDirectoryId = '';
    this.selectedVideoDirectoryName = '';
    this.mediaVideoDirectoryItems = [];
    this.LoadMediaCatalog();
  }

  private SelectedMediaCatalogDirectoryName(): string {
    if (this.mediaCatalogDirectoryId.length === 0) return '全部目录';
    const directory = this.mediaCatalogFilters.directories.find((value: MediaDirectoryFilter) =>
      value.directoryId === this.mediaCatalogDirectoryId);
    return directory === undefined ? '全部目录' : `${directory.parentDirectoryName}/${directory.name}`;
  }

  private MediaCatalogFilterSelected(mode: string): boolean {
    return mode === 'directory' ? this.mediaCatalogDirectoryId.length > 0 : this.mediaCatalogTag.length > 0;
  }

  private FilteredMediaCatalogDirectories(): MediaDirectoryFilter[] {
    const query = this.mediaCatalogSelectorQuery.trim().toLowerCase();
    if (query.length > 0) return this.mediaCatalogFilters.directories.filter((value: MediaDirectoryFilter) =>
      value.name.toLowerCase().includes(query) || value.parentDirectoryName.toLowerCase().includes(query))
      .map((value: MediaDirectoryFilter): MediaDirectoryFilter => ({ directoryId: value.directoryId,
        name: `${value.parentDirectoryName}/${value.name}`, fileCount: value.fileCount,
        latestModifiedAt: value.latestModifiedAt, parentDirectoryId: value.parentDirectoryId,
        parentDirectoryName: value.parentDirectoryName }))
      .sort((left: MediaDirectoryFilter, right: MediaDirectoryFilter) => right.name.localeCompare(left.name));
    if (this.mediaCatalogDirectoryParentId.length > 0) {
      return this.mediaCatalogFilters.directories.filter((value: MediaDirectoryFilter) =>
        value.parentDirectoryId === this.mediaCatalogDirectoryParentId)
        .sort((left: MediaDirectoryFilter, right: MediaDirectoryFilter) => right.name.localeCompare(left.name));
    }
    const parents = new Map<string, MediaDirectoryFilter>();
    this.mediaCatalogFilters.directories.forEach((value: MediaDirectoryFilter): void => {
      const current = parents.get(value.parentDirectoryId);
      if (current === undefined) {
        parents.set(value.parentDirectoryId, { directoryId: value.parentDirectoryId,
          name: value.parentDirectoryName, fileCount: value.fileCount, latestModifiedAt: '',
          parentDirectoryId: '', parentDirectoryName: '' });
      } else {
        current.fileCount += value.fileCount;
      }
    });
    return Array.from(parents.values())
      .sort((left: MediaDirectoryFilter, right: MediaDirectoryFilter) => right.name.localeCompare(left.name));
  }

  private FilteredMediaCatalogTags(): MediaTagFilter[] {
    const query = this.mediaCatalogSelectorQuery.trim().toLowerCase();
    if (query.length === 0) return this.mediaCatalogFilters.tags;
    return this.mediaCatalogFilters.tags.filter((value: MediaTagFilter) => value.name.toLowerCase().includes(query));
  }

  private MediaCatalogSelectorSummary(): string {
    const count = this.mediaCatalogDirectorySelectorVisible ? this.FilteredMediaCatalogDirectories().length :
      this.FilteredMediaCatalogTags().length;
    return `共 ${count} 个${this.mediaCatalogDirectorySelectorVisible ? '目录' : '标签'} · 单击立即筛选`;
  }

  private MediaCatalogSelectorHasValue(): boolean {
    return this.mediaCatalogDirectorySelectorVisible ? this.mediaCatalogDirectoryId.length > 0 :
      this.mediaCatalogTag.length > 0;
  }

  private IsMediaCatalogSelectorValueSelected(value: string): boolean {
    return this.mediaCatalogDirectorySelectorVisible ? value === this.mediaCatalogDirectoryId :
      value === this.mediaCatalogTag;
  }

  private ClearMediaCatalogSelector(): void {
    if (this.mediaCatalogDirectorySelectorVisible) this.SelectMediaCatalogDirectory('', '');
    else this.SelectMediaCatalogTag('');
  }

  private CloseMediaCatalogSelector(): void {
    this.mediaCatalogDirectorySelectorVisible = false;
    this.mediaCatalogTagSelectorVisible = false;
    this.mediaCatalogSelectorQuery = '';
  }

  private SelectMediaCatalogDirectory(directoryId: string, _name: string): void {
    if (directoryId.length > 0 && this.mediaCatalogDirectoryParentId.length === 0 &&
      this.mediaCatalogFilters.directories.some((value: MediaDirectoryFilter) =>
        value.parentDirectoryId === directoryId)) {
      this.mediaCatalogDirectoryParentId = directoryId;
      this.mediaCatalogSelectorQuery = '';
      return;
    }
    this.mediaCatalogDirectoryId = directoryId;
    this.CloseMediaCatalogSelector();
    this.selectedVideoDirectoryId = '';
    this.LoadMediaCatalog();
  }

  private SelectMediaCatalogTag(tag: string): void {
    this.mediaCatalogTag = tag;
    this.CloseMediaCatalogSelector();
    this.selectedVideoDirectoryId = '';
    this.tabScrollers[3].scrollEdge(Edge.Top);
    this.LoadMediaCatalog();
  }

  /** 从资源卡片的标签立即应用筛选。 */
  private SelectMediaCatalogItemTag(tag: string): void {
    this.SelectMediaCatalogTag(tag);
  }

  private async LoadMediaCatalog(): Promise<void> {
    if (!this.authenticated) return;
    this.activeMediaCatalogCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeMediaCatalogCancellation = cancellation;
    this.mediaCatalogLoading = true;
    this.mediaCatalogStatus = '';
    try {
      // 新版媒体目录由服务端直接聚合，不再依赖旧远程媒体数据源配置。
      const api = new MediaCatalogApi(this.ActiveServiceUrl(), this.authManager);
      const filters = await api.filters(this.mediaCatalogMode, this.mediaCatalogKeyword,
        this.hideAdultContent, cancellation);
      if (cancellation.isCancelled()) return;
      this.mediaCatalogFilters = filters;
      if (this.mediaCatalogMode === 'gallery') {
        this.mediaVideoDirectories = [];
        this.mediaVideoDirectoryItems = [];
        const page = await api.gallery(this.mediaCatalogDirectoryId, this.mediaCatalogTag,
          this.mediaCatalogKeyword, 1, this.hideAdultContent, cancellation);
        if (cancellation.isCancelled()) return;
        this.mediaCatalogItems = page.items;
        this.mediaCatalogTotal = page.total;
        this.mediaCatalogPage = page.page;
        this.PrepareMediaCatalogThumbnails(page.items);
      } else {
        this.mediaCatalogItems = [];
        this.mediaCatalogTotal = 0;
        this.mediaVideoDirectories = this.mediaCatalogMode === 'audio' ?
          await api.audioDirectories(this.mediaCatalogDirectoryId, this.mediaCatalogTag,
            this.mediaCatalogKeyword, this.hideAdultContent, cancellation) :
          await api.videoDirectories(this.mediaCatalogDirectoryId, this.mediaCatalogTag,
            this.mediaCatalogKeyword, this.hideAdultContent, cancellation);
        if (cancellation.isCancelled()) return;
        const previews: MediaCatalogItem[] = [];
        this.mediaVideoDirectories.forEach((directory: MediaVideoDirectory) =>
          directory.topItems.forEach((item: MediaCatalogItem) => previews.push(item)));
        this.PrepareMediaCatalogThumbnails(previews);
      }
    } catch (error) {
      if (!cancellation.isCancelled()) {
        this.mediaCatalogStatus = error instanceof Error ? error.message : '多媒体目录加载失败';
      }
    } finally {
      if (this.activeMediaCatalogCancellation === cancellation) {
        this.activeMediaCatalogCancellation = undefined;
        this.mediaCatalogLoading = false;
      }
    }
  }

  /** 从媒体列表顶部下拉时刷新当前图片或视频数据。 */
  private async RefreshMediaCatalog(): Promise<void> {
    this.mediaCatalogRefreshing = true;
    try {
      await this.LoadMediaCatalog();
    } finally {
      this.mediaCatalogRefreshing = false;
    }
  }

  private async LoadMoreMediaCatalog(): Promise<void> {
    if (!this.authenticated || this.mediaCatalogMode !== 'gallery' || this.mediaCatalogLoading ||
      this.mediaCatalogItems.length >= this.mediaCatalogTotal) return;
    const cancellation = new DownloadCancellationToken();
    this.activeMediaCatalogCancellation?.cancel();
    this.activeMediaCatalogCancellation = cancellation;
    this.mediaCatalogLoading = true;
    const nextPage = this.mediaCatalogPage + 1;
    try {
      const page = await new MediaCatalogApi(this.ActiveServiceUrl(), this.authManager).gallery(
        this.mediaCatalogDirectoryId, this.mediaCatalogTag, this.mediaCatalogKeyword, nextPage,
        this.hideAdultContent, cancellation);
      if (cancellation.isCancelled()) return;
      const merged = new Map<string, MediaCatalogItem>();
      this.mediaCatalogItems.forEach((item: MediaCatalogItem) => merged.set(item.itemId, item));
      page.items.forEach((item: MediaCatalogItem) => merged.set(item.itemId, item));
      this.mediaCatalogItems = Array.from(merged.values());
      this.mediaCatalogPage = page.page;
      this.mediaCatalogTotal = page.total;
      this.PrepareMediaCatalogThumbnails(this.mediaCatalogItems);
    } catch (error) {
      if (!cancellation.isCancelled()) this.mediaCatalogStatus = error instanceof Error ? error.message : '加载更多失败';
    } finally {
      if (this.activeMediaCatalogCancellation === cancellation) {
        this.activeMediaCatalogCancellation = undefined;
        this.mediaCatalogLoading = false;
      }
    }
  }

  private PrepareMediaCatalogThumbnails(items: MediaCatalogItem[]): void {
    const remoteItems = items.map((item: MediaCatalogItem): RemoteMediaItem => this.CatalogRemoteItem(item));
    this.mediaItems = remoteItems;
    this.mediaDirectoryItems = remoteItems;
    const revision = ++this.mediaCatalogThumbnailRevision;
    this.CacheMediaCatalogThumbnails(items, revision);
  }

  /** 下载新版媒体目录提供的受认证缩略图，并以媒体标识更新浏览缓存。 */
  private async CacheMediaCatalogThumbnails(items: MediaCatalogItem[], revision: number): Promise<void> {
    const context = this.ApplicationContext();
    if (context === undefined) return;
    this.activeMediaCatalogThumbnailCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeMediaCatalogThumbnailCancellation = cancellation;
    const directory = `${context.cacheDir}/media-catalog-thumbnails`;
    try {
      if (!fs.accessSync(directory)) fs.mkdirSync(directory, true);
    } catch (_) {
      return;
    }
    const api = new MediaCatalogApi(this.ActiveServiceUrl(), this.authManager);
    // 首屏和视频目录预览优先，限制并发下载规模，避免大目录阻塞页面交互。
    const candidates = items.filter((item: MediaCatalogItem) => item.thumbnailUrl.length > 0).slice(0, 90);
    const candidatePaths = new Set<string>(candidates.map((item: MediaCatalogItem) => `/media/${item.itemId}`));
    const cached: MediaThumbnailEntry[] = candidates.filter((item: MediaCatalogItem): boolean => {
      const target = `${directory}/${item.itemId}.jpg`;
      try {
        return fs.accessSync(target) && fs.statSync(target).size >= 3;
      } catch (_) {
        return false;
      }
    }).map((item: MediaCatalogItem): MediaThumbnailEntry => ({
      path: `/media/${item.itemId}`, uri: `file://${directory}/${item.itemId}.jpg`
    }));
    // 标签筛选后先恢复磁盘缓存，不能等待整个下载队列跑完才显示已有缩略图。
    this.SetMediaBrowseThumbnailEntries(this.mediaBrowseThumbnailEntries.filter(
      (entry: MediaThumbnailEntry) => !candidatePaths.has(entry.path)).concat(cached));
    const cachedPaths = new Set<string>(cached.map((entry: MediaThumbnailEntry) => entry.path));
    const missingCandidates = candidates.filter((item: MediaCatalogItem) =>
      !cachedPaths.has(`/media/${item.itemId}`));
    // HarmonyOS 虚拟机的网络连接数较小，缩略图必须分批下载，避免大量并发请求长期阻塞。
    for (let offset = 0; offset < missingCandidates.length; offset++) {
      const batch = missingCandidates.slice(offset, offset + 1);
      const tasks: Promise<MediaThumbnailEntry | undefined>[] = batch.map(
        async (item: MediaCatalogItem): Promise<MediaThumbnailEntry | undefined> => {
        const target = `${directory}/${item.itemId}.jpg`;
        try {
          if (!fs.accessSync(target) || fs.statSync(target).size < 3) {
            const temporary = `${target}.${util.generateRandomUUID(true)}.part`;
            try {
              await api.downloadImage(item.thumbnailUrl, temporary, cancellation);
              if (revision !== this.mediaCatalogThumbnailRevision || cancellation.isCancelled()) return undefined;
              if (fs.accessSync(target)) fs.unlinkSync(target);
              fs.renameSync(temporary, target);
            } finally {
              try {
                if (fs.accessSync(temporary)) fs.unlinkSync(temporary);
              } catch (_) {
              }
            }
          }
          return { path: `/media/${item.itemId}`, uri: `file://${target}` };
        } catch (error) {
          // 单个资源尚无可读缩略图时保留类型占位卡，不把它显示成整个目录故障。
          return undefined;
        }
        });
      const resolved = (await Promise.all(tasks)).filter(
        (entry: MediaThumbnailEntry | undefined): boolean => entry !== undefined) as MediaThumbnailEntry[];
      cached.push(...resolved);
      if (revision !== this.mediaCatalogThumbnailRevision || cancellation.isCancelled()) return;
      // 每批完成后立即更新首屏，后续缩略图无需全部完成才可见。
      if (resolved.length > 0) {
        const resolvedPaths = new Set<string>(resolved.map((entry: MediaThumbnailEntry) => entry.path));
        this.SetMediaBrowseThumbnailEntries(this.mediaBrowseThumbnailEntries.filter(
          (entry: MediaThumbnailEntry) => !resolvedPaths.has(entry.path)).concat(resolved));
      }
    }
    if (revision !== this.mediaCatalogThumbnailRevision || cancellation.isCancelled()) return;
    const paths = new Set<string>();
    candidates.forEach((item: MediaCatalogItem) => paths.add(`/media/${item.itemId}`));
    this.SetMediaBrowseThumbnailEntries(this.mediaBrowseThumbnailEntries.filter(
      (entry: MediaThumbnailEntry) => !paths.has(entry.path)).concat(cached));
    if (this.activeMediaCatalogThumbnailCancellation === cancellation) {
      this.activeMediaCatalogThumbnailCancellation = undefined;
    }
  }

  private CatalogRemoteItem(item: MediaCatalogItem): RemoteMediaItem {
    const kind = item.kind === 'IMAGE' ? 'image' : (item.kind === 'VIDEO' ? 'video' :
      (item.kind === 'AUDIO' ? 'audio' : 'other'));
    return { id: item.itemId, name: item.name, path: `/media/${item.itemId}`, directoryName: item.directoryName, kind,
      contentType: item.mimeType, size: item.sizeBytes, lastModified: item.modifiedAt,
      tags: item.tags.map((tag: string): RemoteMediaTag => ({ name: tag, type: '', confidence: 1 })) };
  }

  private IsCatalogMediaPath(path: string): boolean {
    return path.startsWith('/media/') || path.startsWith('/local/');
  }

  private CatalogMediaItemId(path: string, fallback: string): string {
    if (path.startsWith('/media/')) return path.substring('/media/'.length);
    if (path.startsWith('/local/')) return path.substring('/local/'.length);
    return fallback;
  }

  /** 返回新版媒体目录使用的虚拟本地数据源。 */
  private CatalogLocalMediaSource(): RemoteMediaSource {
    return { id: 'library', name: 'MyTools Media', type: 'MYTOOLS_LIBRARY', url: '',
      isDefault: true, isActive: true, localDirectoryType: 'MULTIMEDIA' };
  }

  private MediaCatalogSymbol(item: MediaCatalogItem): string {
    if (item.kind === 'VIDEO') return 'sys.symbol.play_fill';
    if (item.kind === 'IMAGE') return 'sys.symbol.picture';
    if (item.kind === 'AUDIO') return 'sys.symbol.music_note_circle_fill';
    if (item.kind === 'WEB') return 'sys.symbol.globe';
    return 'sys.symbol.doc_plaintext';
  }

  private OpenMediaCatalogItem(item: MediaCatalogItem, useVideoDetail: boolean): void {
    if (item.kind === 'VIDEO' && useVideoDetail) {
      this.LoadMediaVideoDetail(item.itemId);
      return;
    }
    if (item.kind === 'IMAGE' || item.kind === 'VIDEO' || item.kind === 'AUDIO') {
      this.OpenRemoteMedia(this.CatalogRemoteItem(item));
      return;
    }
    this.OpenMediaCatalogSystemItem(item);
  }

  private ToggleAdultContentFilter(): void {
    this.SetAdultContentFilter(!this.hideAdultContent);
  }

  /** 更新成人内容过滤设置，并立即刷新多媒体和电子书目录。 */
  private SetAdultContentFilter(value: boolean): void {
    if (value === this.hideAdultContent) return;
    this.hideAdultContent = value;
    this.profileSettingsRevision++;
    try {
      this.settingsPreferences?.putSync('hide_adult_content', this.hideAdultContent);
      this.settingsPreferences?.flush();
    } catch (_) {
      this.mediaCatalogStatus = '成人内容过滤设置保存失败';
    }
    this.selectedVideoDirectoryId = '';
    this.selectedVideoDirectoryName = '';
    this.mediaVideoDirectoryItems = [];
    this.LoadMediaCatalog();
    if (this.authenticated && this.currentIndex === 0 && this.bookModeIndex === 1 &&
      this.remoteLibraryMode === 'mytools') {
      this.LoadRemoteBookDirectory();
    }
  }

  private async OpenMediaCatalogSystemItem(item: MediaCatalogItem): Promise<void> {
    const source = this.mediaSources.find((value: RemoteMediaSource) => value.id === this.selectedMediaSourceId);
    if (source === undefined) return;
    try {
      const descriptor = await new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager)
        .resolvePlayback(source, `/media/${item.itemId}`);
      const context = this.ApplicationContext();
      if (context === undefined) throw new Error('无法获取系统打开能力');
      try {
        await context.startAbility({ action: 'ohos.want.action.viewData', uri: descriptor.url,
          type: item.mimeType.length > 0 ? item.mimeType : 'application/octet-stream' });
      } catch (_) {
        throw new Error('系统内没有可打开此文件的应用');
      }
    } catch (error) {
      this.mediaCatalogStatus = error instanceof Error ? error.message : '文件打开失败';
    }
  }

  private async OpenMediaVideoDirectory(directory: MediaVideoDirectory): Promise<void> {
    this.mediaVideoDirectoryListOffset = this.tabScrollers[3].currentOffset().yOffset;
    this.activeMediaCatalogCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeMediaCatalogCancellation = cancellation;
    this.mediaCatalogLoading = true;
    try {
      const api = new MediaCatalogApi(this.ActiveServiceUrl(), this.authManager);
      const items = this.mediaCatalogMode === 'audio' ?
        await api.audioDirectoryItems(directory.name, this.hideAdultContent, cancellation) :
        await api.videoDirectoryItems(directory.directoryId, this.hideAdultContent, cancellation);
      if (cancellation.isCancelled()) return;
      this.selectedVideoDirectoryId = directory.directoryId;
      this.selectedVideoDirectoryName = directory.name;
      this.mediaVideoDirectoryItems = items;
      this.PrepareMediaCatalogThumbnails(items);
      setTimeout((): void => {
        this.tabScrollers[3].scrollTo({ xOffset: 0, yOffset: 0, animation: false });
      }, 0);
    } catch (error) {
      if (!cancellation.isCancelled()) this.mediaCatalogStatus = error instanceof Error ? error.message : '目录加载失败';
    } finally {
      if (this.activeMediaCatalogCancellation === cancellation) {
        this.activeMediaCatalogCancellation = undefined;
        this.mediaCatalogLoading = false;
      }
    }
  }

  private LeaveMediaVideoDirectory(): void {
    this.selectedVideoDirectoryId = '';
    this.selectedVideoDirectoryName = '';
    this.mediaVideoDirectoryItems = [];
    const previews: MediaCatalogItem[] = [];
    this.mediaVideoDirectories.forEach((directory: MediaVideoDirectory) =>
      directory.topItems.forEach((item: MediaCatalogItem) => previews.push(item)));
    this.PrepareMediaCatalogThumbnails(previews);
    const offset = this.mediaVideoDirectoryListOffset;
    setTimeout((): void => {
      this.tabScrollers[3].scrollTo({ xOffset: 0, yOffset: offset, animation: false });
    }, 0);
  }

  @Builder
  private MediaVideoDetailPage() {
    Column() {
      Row({ space: 10 }) {
        Button() { SymbolGlyph($r('sys.symbol.arrow_left')).fontSize(20) }
          .width(48).height(48).backgroundColor(Color.Transparent).onClick(() => this.CloseMediaVideoDetail())
        Text(this.mediaVideoDetail?.name ?? '视频详情').layoutWeight(1).maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis }).fontSize(20).fontWeight(FontWeight.Bold)
          .fontColor(AppTheme.textPrimary)
        if (this.lanModeActive) {
          SymbolGlyph($r('sys.symbol.wifi')).fontSize(14).fontColor([AppTheme.primary])
        }
      }.width('100%').height(64).padding({ left: 8, right: 16 })
      Scroll(this.mediaVideoDetailScroller) {
        Column({ space: 18 }) {
          if (this.mediaVideoDetail !== undefined) {
            Row({ space: 16 }) {
              if (this.BrowseMediaThumbnailUri(`/local/${this.mediaVideoDetail.videoId}`).length > 0) {
                Image(this.BrowseMediaThumbnailUri(`/local/${this.mediaVideoDetail.videoId}`))
                  .width(148).height(112).objectFit(ImageFit.Cover).borderRadius(16)
              } else {
                SymbolGlyph($r('sys.symbol.play_fill')).width(148).height(112).fontSize(42)
                  .fontColor([AppTheme.primary]).backgroundColor(AppTheme.surfaceTint).borderRadius(16)
              }
              Column({ space: 5 }) {
                Text(`${this.mediaVideoDetail.format.toUpperCase()} · ${this.FormatFileSize(this.mediaVideoDetail.sizeBytes)}`)
                  .width('100%').fontSize(13).fontColor(AppTheme.textPrimary)
                Text(`${this.mediaVideoDetail.width} × ${this.mediaVideoDetail.height}`)
                  .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
                Text(`${this.mediaVideoDetail.videoCodec} / ${this.mediaVideoDetail.audioCodec}`)
                  .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
                Text(`${this.mediaVideoDetail.directoryName} · ${this.FormatDuration(this.mediaVideoDetail.durationMs)}`)
                  .width('100%').maxLines(2).fontSize(12).fontColor(AppTheme.textSecondary)
                Text(`上次播放：${this.FormatDuration(this.MediaVideoDetailProgress())}`)
                  .width('100%').fontSize(12).fontColor(AppTheme.primary)
                Button('播放').width('100%').height(42).fontColor(Color.White).backgroundColor(AppTheme.primary)
                  .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
                  .borderRadius(AppTheme.pillRadius)
                  .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
                  .onClick(() => this.PlayMediaVideoDetail())
              }.layoutWeight(1).alignItems(HorizontalAlign.Start)
            }.width('100%')
            Column({ space: 9 }) {
              Row() {
                Text(this.mediaVideoDetail.summary.length > 0 ? this.mediaVideoDetail.summary : '视频简介')
                  .layoutWeight(1).fontSize(18).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
                Text('AI 生成').fontSize(11).fontColor(AppTheme.primary).padding({ left: 8, right: 8, top: 4, bottom: 4 })
                  .borderRadius(9).backgroundColor(AppTheme.surfaceTint)
              }.width('100%')
              if (this.mediaVideoDetail.descriptionStatus === 'READY' && this.mediaVideoDetail.description.length > 0) {
                Text(this.mediaVideoDetail.description).width('100%')
                  .maxLines(this.mediaVideoDescriptionExpanded ? 40 : 6)
                  .textOverflow({ overflow: TextOverflow.Ellipsis }).fontSize(14).lineHeight(22)
                  .fontColor(AppTheme.textPrimary)
                if (this.mediaVideoDetail.description.length > 180) {
                  Button(this.mediaVideoDescriptionExpanded ? '收起文字' : '展开文字').height(40)
                    .fontColor(AppTheme.primary).backgroundColor(Color.Transparent)
                    .onClick(() => this.mediaVideoDescriptionExpanded = !this.mediaVideoDescriptionExpanded)
                }
              } else {
                Text(this.MediaVideoAnalysisStatus()).width('100%').fontSize(14).fontColor(AppTheme.textSecondary)
              }
            }.width('100%').padding(16).borderRadius(18).backgroundColor(AppTheme.surface)
            Column({ space: 10 }) {
              Text('视频截图').width('100%').fontSize(18).fontWeight(FontWeight.Bold)
                .fontColor(AppTheme.textPrimary)
              Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.SpaceBetween }) {
                ForEach(this.mediaVideoDetail.storyboard, (frame: MediaStoryboardFrame) => {
                  if (this.MediaStoryboardUri(frame.sequence).length > 0) {
                    Image(this.MediaStoryboardUri(frame.sequence)).width('31.5%').height(86)
                      .objectFit(ImageFit.Cover).borderRadius(10).margin({ bottom: 9 })
                  } else {
                    Column() { LoadingProgress().width(28).height(28) }.width('31.5%').height(86)
                      .justifyContent(FlexAlign.Center).backgroundColor(AppTheme.surfaceTint)
                      .borderRadius(10).margin({ bottom: 9 })
                  }
                }, (frame: MediaStoryboardFrame) => `${frame.sequence}`)
              }.width('100%')
            }.width('100%')
          } else {
            this.NetworkLoadingRows('正在读取视频详情')
          }
        }.width('100%').padding({ left: 18, right: 18, top: 14, bottom: 28 })
          .alignItems(HorizontalAlign.Start)
      }.layoutWeight(1).width('100%').align(Alignment.TopStart).scrollBar(BarState.Auto)
    }.width('100%').height('100%').alignItems(HorizontalAlign.Start).justifyContent(FlexAlign.Start)
      .backgroundColor(AppTheme.background)
  }

  private async LoadMediaVideoDetail(videoId: string): Promise<void> {
    this.activeMediaCatalogCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeMediaCatalogCancellation = cancellation;
    this.mediaCatalogStatus = '';
    this.mediaVideoDetail = undefined;
    this.mediaVideoDescriptionExpanded = false;
    this.mediaStoryboardUris = [];
    this.mediaVideoDetailVisible = true;
    setTimeout((): void => this.mediaVideoDetailScroller.scrollTo({ xOffset: 0, yOffset: 0, animation: false }), 0);
    try {
      const api = new MediaCatalogApi(this.ActiveServiceUrl(), this.authManager);
      const detail = await api.videoDetail(videoId, cancellation);
      if (cancellation.isCancelled()) return;
      this.mediaVideoDetail = detail;
      setTimeout((): void => this.mediaVideoDetailScroller.scrollTo({ xOffset: 0, yOffset: 0, animation: false }), 0);
      this.mediaStoryboardUris = await this.CacheMediaStoryboard(api, detail, cancellation);
    } catch (error) {
      if (!cancellation.isCancelled()) {
        this.mediaCatalogStatus = error instanceof Error ? error.message : '详情加载失败';
        this.mediaVideoDetailVisible = false;
      }
    } finally {
      if (this.activeMediaCatalogCancellation === cancellation) this.activeMediaCatalogCancellation = undefined;
    }
  }

  private async CacheMediaStoryboard(api: MediaCatalogApi, detail: MediaVideoDetail,
    cancellation: DownloadCancellationToken): Promise<string[]> {
    const context = this.ApplicationContext();
    if (context === undefined) return [];
    const directory = `${context.cacheDir}/media-storyboard`;
    try {
      if (!fs.accessSync(directory)) fs.mkdirSync(directory, true);
    } catch (_) {
      return [];
    }
    const tasks: Promise<string>[] = detail.storyboard.map(async (frame: MediaStoryboardFrame): Promise<string> => {
      if (cancellation.isCancelled()) return '';
      const target = `${directory}/${detail.videoId}-${frame.sequence}.jpg`;
      try {
        if (!fs.accessSync(target) || fs.statSync(target).size < 3) {
          const temporary = `${target}.${util.generateRandomUUID(true)}.part`;
          try {
            await api.downloadImage(frame.imageUrl, temporary, cancellation);
            if (cancellation.isCancelled()) return '';
            if (fs.accessSync(target)) fs.unlinkSync(target);
            fs.renameSync(temporary, target);
          } finally {
            try {
              if (fs.accessSync(temporary)) fs.unlinkSync(temporary);
            } catch (_) {
            }
          }
        }
        return `file://${target}`;
      } catch (_) {
        return '';
      }
    });
    return Promise.all(tasks);
  }

  private MediaStoryboardUri(sequence: number): string {
    return sequence < 1 || sequence > this.mediaStoryboardUris.length ? '' : this.mediaStoryboardUris[sequence - 1];
  }

  private MediaVideoAnalysisStatus(): string {
    const status = this.mediaVideoDetail?.descriptionStatus ?? 'PENDING';
    if (status === 'FAILED') return '视频简介生成失败，后台任务会按退避策略重试';
    if (status === 'RUNNING') return '正在分析视频并生成简介与截图…';
    return '视频简介等待后台分析';
  }

  private MediaVideoDetailProgress(): number {
    const detail = this.mediaVideoDetail;
    if (detail === undefined) return 0;
    const sourceId = this.selectedMediaSourceId;
    const path = `/local/${detail.videoId}`;
    const key = `${sourceId.length}:${sourceId}${path}`;
    return this.mediaPlaybackProgress.find((value: MediaPlaybackProgress) =>
      value.owner === this.localAccountScope && value.key === key)?.positionMs ?? 0;
  }

  private async PlayMediaVideoDetail(): Promise<void> {
    const detail = this.mediaVideoDetail;
    if (detail === undefined) return;
    this.activeMediaOpenCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeMediaOpenCancellation = cancellation;
    try {
      const descriptor = await new MediaCatalogApi(this.ActiveServiceUrl(), this.authManager)
        .playTicket(detail.videoId, cancellation);
      if (cancellation.isCancelled()) return;
      this.currentMediaName = detail.name;
      this.currentMediaKind = 'video';
      this.currentMediaUrl = descriptor.url;
      this.currentMediaPath = `/local/${detail.videoId}`;
      this.currentMediaContentType = `video/${detail.format.split(',')[0]}`;
      this.currentMediaSize = detail.sizeBytes;
      this.currentMediaLastModified = '';
      this.currentMediaTicket = descriptor.ticket;
      this.currentMediaResumeMs = this.MediaVideoDetailProgress();
      this.mediaPlaybackError = '';
      this.videoStatus = '正在连接视频…';
      this.videoFullscreen = false;
      this.mediaViewerChromeVisible = true;
      this.mediaViewerVisible = true;
      this.ApplyMediaViewerSystemBars(true);
      this.ScheduleMediaViewerChromeAutoHide();
    } catch (error) {
      if (!cancellation.isCancelled()) this.mediaCatalogStatus = error instanceof Error ? error.message : '播放失败';
    } finally {
      if (this.activeMediaOpenCancellation === cancellation) this.activeMediaOpenCancellation = undefined;
    }
  }

  private CloseMediaVideoDetail(): void {
    this.activeMediaCatalogCancellation?.cancel();
    this.mediaVideoDetailVisible = false;
    this.mediaVideoDetail = undefined;
    this.mediaStoryboardUris = [];
    this.mediaVideoDescriptionExpanded = false;
  }

  @Builder
  private DrivePage() {
    if (!this.authenticated) {
      this.HeroPanel('登录后使用网盘', '统一浏览已授权的远程文件和目录', '登录', () => this.OpenLogin())
    } else {
      Row({ space: 10 }) {
        Button() {
          Row({ space: 6 }) {
            SymbolGlyph($r('sys.symbol.cloud')).fontSize(17).fontColor([AppTheme.primary])
            Text(this.SelectedDriveName()).layoutWeight(1).maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis }).fontSize(13).fontColor(AppTheme.textPrimary)
            SymbolGlyph($r('sys.symbol.chevron_down')).fontSize(14).fontColor([AppTheme.textSecondary])
          }.width('100%')
        }.width(154).height(48).padding({ left: 12, right: 10 }).backgroundColor(AppTheme.surface)
          .border({ width: 1, color: AppTheme.divider, radius: 16 })
          .accessibilityText(`选择网盘：${this.SelectedDriveName()}`)
          .onClick(() => this.driveSelectorVisible = !this.driveSelectorVisible)
        Row({ space: 8 }) {
          SymbolGlyph($r('sys.symbol.magnifyingglass')).fontSize(18).fontColor([AppTheme.textSecondary])
          TextInput({ text: this.driveKeyword, placeholder: '搜索文件' }).layoutWeight(1).height(46)
            .maxLength(100).backgroundColor(Color.Transparent)
            .onChange((value: string) => this.driveKeyword = value)
            .onSubmit(() => this.LoadDriveDirectory(this.CurrentDriveItemId()))
          if (this.driveKeyword.length > 0) {
            Button() { SymbolGlyph($r('sys.symbol.xmark')).fontSize(14) }
              .width(40).height(40).backgroundColor(Color.Transparent).onClick(() => {
                this.driveKeyword = '';
                this.LoadDriveDirectory(this.CurrentDriveItemId());
              })
          }
        }.layoutWeight(1).height(48).padding({ left: 14, right: 6 }).backgroundColor(AppTheme.surface)
          .border({ width: 1, color: AppTheme.divider, radius: 16 })
      }.width('100%')

      Row({ space: 8 }) {
        if (this.driveBreadcrumbs.length > 0) {
          Button() { SymbolGlyph($r('sys.symbol.arrow_left')).fontSize(18) }
            .width(40).height(40).backgroundColor(AppTheme.surfaceTint).onClick(() => this.LeaveDriveDirectory())
        }
        Column({ space: 3 }) {
          Text(this.driveDirectory?.name ?? '根目录').width('100%').fontSize(16)
            .fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
          Text(`${this.driveDirectory?.itemCount ?? 0} 个项目 · ` +
            `${this.FormatFileSize(this.driveDirectory?.totalSizeBytes ?? 0)}`)
            .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        Button() {
          SymbolGlyph($r('sys.symbol.arrow_up_left')).fontSize(18).fontColor([AppTheme.primary])
        }.width(44).height(44).backgroundColor(AppTheme.surfaceTint).accessibilityText('切换排序')
          .onClick(() => this.ChangeDriveSort())
      }.width('100%').padding({ top: 4, bottom: 4 })

      if (this.driveLoading && (this.driveDirectory?.items.length ?? 0) === 0) {
        this.NetworkLoadingRows('正在读取网盘目录')
      } else if (!this.driveLoading && this.driveAccounts.length === 0) {
        this.PlaceholderRow('还没有可用网盘', '请由管理员配置已授权的网盘')
      } else if (!this.driveLoading && (this.driveDirectory?.items.length ?? 0) === 0) {
        this.PlaceholderRow(this.driveKeyword.length > 0 ? '没有匹配文件' : '当前目录为空', '')
      }
      if (this.driveStatus.length > 0) {
        Text(this.driveStatus).width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
      }
      ForEach(this.driveDirectory?.items ?? [], (item: DriveItem) => {
        Row({ space: 12 }) {
          this.DriveItemSymbol(item)
          Column({ space: 5 }) {
            Text(item.name).width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              .fontSize(16).fontColor(AppTheme.textPrimary)
            Text(item.kind === 'DIRECTORY' ? '文件夹' :
              `${item.mimeType.length > 0 ? item.mimeType : item.kind} · ${this.FormatFileSize(item.sizeBytes)}`)
              .width('100%').maxLines(1).fontSize(12).fontColor(AppTheme.textSecondary)
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          if (item.kind === 'DIRECTORY') {
            SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(16).fontColor([AppTheme.textSecondary])
          }
        }.width('100%').padding({ top: 13, bottom: 13 })
          .border({ width: { bottom: 1 }, color: AppTheme.divider }).onClick(() => this.OpenDriveItem(item))
      }, (item: DriveItem) => item.itemId)
    }
  }

  @Builder
  private DriveItemSymbol(item: DriveItem) {
    if (item.kind === 'DIRECTORY') {
      SymbolGlyph($r('sys.symbol.folder_fill')).width(42).height(42).fontSize(22)
        .fontColor([AppTheme.primary]).backgroundColor(AppTheme.surfaceTint).borderRadius(12)
    } else if (item.kind === 'IMAGE') {
      SymbolGlyph($r('sys.symbol.picture')).width(42).height(42).fontSize(22)
        .fontColor([AppTheme.primary]).backgroundColor(AppTheme.surfaceTint).borderRadius(12)
    } else if (item.kind === 'AUDIO') {
      SymbolGlyph($r('sys.symbol.music_note_circle_fill')).width(42).height(42).fontSize(22)
        .fontColor([AppTheme.primary]).backgroundColor(AppTheme.surfaceTint).borderRadius(12)
    } else if (item.kind === 'VIDEO') {
      SymbolGlyph($r('sys.symbol.play_fill')).width(42).height(42).fontSize(22)
        .fontColor([AppTheme.primary]).backgroundColor(AppTheme.surfaceTint).borderRadius(12)
    } else {
      SymbolGlyph($r('sys.symbol.doc_plaintext')).width(42).height(42).fontSize(22)
        .fontColor([AppTheme.primary]).backgroundColor(AppTheme.surfaceTint).borderRadius(12)
    }
  }

  private SelectedDriveName(): string {
    return this.driveAccounts.find((drive: DriveAccount) => drive.driveId === this.selectedDriveId)?.name ?? '选择网盘';
  }

  @Builder
  private DriveSelectorSheet() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column().width('100%').height('100%').backgroundColor('#66000000')
        .onClick(() => this.driveSelectorVisible = false)
      Column({ space: 10 }) {
        Row() {
          Text('选择网盘').layoutWeight(1).fontSize(20).fontWeight(FontWeight.Bold)
            .fontColor(AppTheme.textPrimary)
          Button('关闭').height(42).padding({ left: 12, right: 12 }).fontSize(13)
            .fontColor(AppTheme.primary).backgroundColor(Color.Transparent)
            .onClick(() => this.driveSelectorVisible = false)
        }.width('100%')
        Text('由 MyTools 后端通过 rclone 统一代理 Alist、坚果云及其他远程主机')
          .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
        List({ space: 6 }) {
          ForEach(this.driveAccounts, (drive: DriveAccount) => {
            ListItem() {
              Button() {
                Row({ space: 12 }) {
                  SymbolGlyph($r('sys.symbol.cloud_fill')).width(42).height(42).fontSize(21)
                    .fontColor([AppTheme.primary])
                    .backgroundColor(drive.driveId === this.selectedDriveId ? AppTheme.accentSoft : AppTheme.surfaceTint)
                    .borderRadius(13)
                  Column({ space: 3 }) {
                    Text(drive.name).width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                      .fontSize(16).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
                    Text(drive.status.length > 0 ? drive.status : '可用').width('100%').fontSize(12)
                      .fontColor(AppTheme.textSecondary)
                  }.layoutWeight(1).alignItems(HorizontalAlign.Start)
                  if (drive.driveId === this.selectedDriveId) {
                    SymbolGlyph($r('sys.symbol.checkmark_circle_fill')).fontSize(20)
                      .fontColor([AppTheme.primary])
                  }
                }.width('100%')
              }.width('100%').height(66).padding({ left: 10, right: 10 })
                .backgroundColor(drive.driveId === this.selectedDriveId ? AppTheme.surfaceTint : Color.Transparent)
                .borderRadius(15).onClick(() => this.SelectDrive(drive.driveId))
            }
          }, (drive: DriveAccount) => drive.driveId)
        }.width('100%').layoutWeight(1).scrollBar(BarState.Auto)
      }.width('100%').height(Math.min(520, 132 + this.driveAccounts.length * 72))
        .padding({ left: 18, right: 18, top: 16, bottom: 18 })
        .borderRadius({ topLeft: 28, topRight: 28 }).backgroundColor(AppTheme.surface)
        .onClick(() => {})
    }.width('100%').height('100%')
  }

  private CurrentDriveItemId(): string {
    return this.driveBreadcrumbs.length === 0 ? '' : this.driveBreadcrumbs[this.driveBreadcrumbs.length - 1].itemId;
  }

  private async LoadDrives(): Promise<void> {
    if (!this.authenticated || this.driveLoading) return;
    this.activeDriveCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeDriveCancellation = cancellation;
    this.driveLoading = true;
    this.driveStatus = '';
    try {
      this.driveAccounts = await new DriveApi(this.ActiveServiceUrl(), this.authManager).listDrives(cancellation);
      if (cancellation.isCancelled()) return;
      if (!this.driveAccounts.some((drive: DriveAccount) => drive.driveId === this.selectedDriveId)) {
        this.selectedDriveId = this.driveAccounts[0]?.driveId ?? '';
        this.driveBreadcrumbs = [];
      }
      if (this.selectedDriveId.length > 0) await this.LoadDriveDirectory('');
    } catch (error) {
      if (!cancellation.isCancelled()) this.driveStatus = error instanceof Error ? error.message : '网盘加载失败';
    } finally {
      if (this.activeDriveCancellation === cancellation) this.activeDriveCancellation = undefined;
      this.driveLoading = false;
    }
  }

  private async LoadDriveDirectory(itemId: string): Promise<void> {
    if (!this.authenticated || this.selectedDriveId.length === 0) return;
    const cancellation = this.activeDriveCancellation ?? new DownloadCancellationToken();
    if (this.activeDriveCancellation === undefined) this.activeDriveCancellation = cancellation;
    this.driveLoading = true;
    this.driveStatus = '';
    try {
      const directory = await new DriveApi(this.ActiveServiceUrl(), this.authManager).listItems(
        this.selectedDriveId, itemId, this.driveKeyword, this.driveSort, this.driveDirection, cancellation);
      if (!cancellation.isCancelled()) this.driveDirectory = directory;
    } catch (error) {
      if (!cancellation.isCancelled()) this.driveStatus = error instanceof Error ? error.message : '网盘目录加载失败';
    } finally {
      if (this.activeDriveCancellation === cancellation) this.activeDriveCancellation = undefined;
      this.driveLoading = false;
    }
  }

  private SelectDrive(driveId: string): void {
    this.selectedDriveId = driveId;
    this.driveBreadcrumbs = [];
    this.driveKeyword = '';
    this.driveSelectorVisible = false;
    this.LoadDriveDirectory('');
  }

  private OpenDriveItem(item: DriveItem): void {
    if (item.kind === 'DIRECTORY') {
      this.driveBreadcrumbs.push({ itemId: item.itemId, name: item.name });
      this.driveKeyword = '';
      this.LoadDriveDirectory(item.itemId);
      return;
    }
    this.OpenDriveFile(item);
  }

  private async OpenDriveFile(item: DriveItem): Promise<void> {
    this.activeDriveCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeDriveCancellation = cancellation;
    this.driveStatus = item.kind === 'VIDEO' ? '正在准备视频播放' : '正在准备文件';
    try {
      const descriptor = await new DriveApi(this.ActiveServiceUrl(), this.authManager)
        .openTicket(this.selectedDriveId, item.itemId, cancellation);
      if (cancellation.isCancelled()) return;
      if (item.kind === 'WEB' || item.kind === 'TEXT' || item.kind === 'OTHER') {
        await this.OpenDriveWithSystem(descriptor, item);
        this.driveStatus = '';
        return;
      }
      this.currentMediaName = item.name;
      this.currentMediaKind = item.kind === 'IMAGE' ? 'image' : (item.kind === 'AUDIO' ? 'audio' : 'video');
      this.currentMediaUrl = descriptor.url;
      this.currentMediaPreviewUri = '';
      this.currentMediaPath = `drive:${this.selectedDriveId}:${item.itemId}`;
      this.currentMediaContentType = item.mimeType;
      this.currentMediaSize = item.sizeBytes;
      this.currentMediaLastModified = item.modifiedAt;
      this.currentMediaTicket = descriptor.ticket;
      this.currentMediaResumeMs = 0;
      this.mediaPlaybackError = '';
      this.videoStatus = item.kind === 'VIDEO' ? '正在连接网盘视频…' : '';
      this.videoFullscreen = false;
      this.mediaViewerChromeVisible = true;
      this.mediaViewerVisible = true;
      this.ApplyMediaViewerSystemBars(true);
      this.ScheduleMediaViewerChromeAutoHide();
      if (item.kind === 'AUDIO') {
        const hostContext = this.getUIContext().getHostContext();
        if (hostContext === undefined) throw new Error('无法获取后台音频上下文');
        await this.audioPlayer.open(descriptor.url, 0, hostContext as common.UIAbilityContext, item.name);
      }
      this.driveStatus = '';
    } catch (error) {
      if (!cancellation.isCancelled()) {
        this.driveStatus = error instanceof Error ? error.message : '文件打开失败';
      }
    } finally {
      if (this.activeDriveCancellation === cancellation) this.activeDriveCancellation = undefined;
    }
  }

  private async OpenDriveWithSystem(descriptor: DriveOpenDescriptor, item: DriveItem): Promise<void> {
    const hostContext = this.getUIContext().getHostContext();
    if (hostContext === undefined) throw new Error('无法获取系统打开能力');
    try {
      await (hostContext as common.UIAbilityContext).startAbility({
        action: 'ohos.want.action.viewData',
        uri: descriptor.url,
        type: item.mimeType.length > 0 ? item.mimeType : 'application/octet-stream'
      });
    } catch (_) {
      throw new Error('系统内没有可打开此文件的应用');
    }
  }

  private LeaveDriveDirectory(): void {
    if (this.driveBreadcrumbs.length > 0) this.driveBreadcrumbs.pop();
    this.driveKeyword = '';
    this.LoadDriveDirectory(this.CurrentDriveItemId());
  }

  private ChangeDriveSort(): void {
    if (this.driveSort === 'modified') {
      this.driveSort = 'name';
      this.driveDirection = 'asc';
    } else if (this.driveSort === 'name') {
      this.driveSort = 'size';
      this.driveDirection = 'desc';
    } else {
      this.driveSort = 'modified';
      this.driveDirection = 'desc';
    }
    this.LoadDriveDirectory(this.CurrentDriveItemId());
  }

  @Builder
  private MediaFilterTrigger(mode: string) {
    Button() {
      Row({ space: 5 }) {
        Text(this.MediaFilterLabel(mode)).layoutWeight(1).maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .fontSize(12).fontFamily('HarmonyOS Sans')
          .fontWeight(this.MediaFilterActive(mode) ? FontWeight.Medium : FontWeight.Normal)
          .fontColor(this.MediaFilterActive(mode) ? AppTheme.primary : AppTheme.textPrimary)
        SymbolGlyph($r('sys.symbol.chevron_down')).fontSize(14)
          .fontColor([this.MediaFilterActive(mode) ? AppTheme.primary : AppTheme.textSecondary])
      }.width('100%')
    }.layoutWeight(1).height(38).padding({ left: 12, right: 10 })
      .backgroundColor(this.MediaFilterActive(mode) ? AppTheme.surfaceTint : AppTheme.surface)
      .border({ width: 1, color: this.MediaFilterActive(mode) ? AppTheme.primary : AppTheme.divider, radius: 18 })
      .accessibilityText(this.MediaFilterLabel(mode)).accessibilityDescription('打开筛选选择器')
      .onClick(() => this.OpenMediaSelector(mode))
  }

  @Builder
  private MediaEmptyState() {
    Column({ space: 12 }) {
      SymbolGlyph($r(this.HasActiveMediaFilters() ? 'sys.symbol.picture' : 'sys.symbol.folder_fill'))
        .fontSize(38).fontColor([AppTheme.primary])
      Text(this.HasActiveMediaFilters() ? '没有符合条件的媒体' : '当前目录暂无媒体')
        .fontSize(17).fontFamily('HarmonyOS Sans').fontWeight(FontWeight.Medium)
        .fontColor(AppTheme.textPrimary)
      Text(this.HasActiveMediaFilters() ? '可清除筛选条件后查看全部远程媒体' : '可切换媒体来源或目录后再试')
        .width('100%').textAlign(TextAlign.Center).fontSize(13).fontFamily('HarmonyOS Sans')
        .fontColor(AppTheme.textSecondary)
      if (this.HasActiveMediaFilters()) {
        Button('清除筛选').height(44).padding({ left: 22, right: 22 }).fontSize(14)
          .fontFamily('HarmonyOS Sans').fontColor(Color.White).backgroundColor(AppTheme.primary)
          .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
          .borderRadius(AppTheme.pillRadius)
          .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
          .accessibilityText('清除全部媒体筛选条件').onClick(() => this.ResetMediaFilters())
      }
    }.width('100%').padding({ top: 38, bottom: 38, left: 20, right: 20 })
      .borderRadius(20).backgroundColor(AppTheme.surface)
  }

  @Builder
  private MediaSelectorSheet() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column().width('100%').height('100%').backgroundColor('#66000000')
        .onClick(() => this.CloseMediaSelector())
      Column({ space: 12 }) {
        Row() {
          Button(this.MediaSelectorUsesSearchList() ? '关闭' : '取消')
            .width(64).height(48).padding(0).fontSize(14).fontColor(AppTheme.textSecondary)
            .backgroundColor(Color.Transparent).onClick(() => this.CloseMediaSelector())
          Text(this.MediaSelectorTitle()).layoutWeight(1).textAlign(TextAlign.Center)
            .fontSize(18).fontFamily('HarmonyOS Sans').fontWeight(FontWeight.Bold)
            .fontColor(AppTheme.textPrimary)
          if (this.MediaSelectorUsesSearchList()) {
            Button('清除').width(64).height(48).padding(0).fontSize(14)
              .fontColor(this.MediaSearchableSelectorHasValue() ? AppTheme.primary : AppTheme.textSecondary)
              .backgroundColor(Color.Transparent).enabled(this.MediaSearchableSelectorHasValue())
              .onClick(() => this.ClearMediaSearchableSelector())
          } else {
            Button('完成').width(64).height(48).padding(0).fontSize(14)
              .fontColor(AppTheme.primary).backgroundColor(Color.Transparent)
              .onClick(() => this.ConfirmMediaSelector())
          }
        }.width('100%')
        if (this.MediaSelectorUsesSearchList()) {
          this.MediaSearchableSelectorContent()
        } else {
          TextPicker({ range: this.MediaPickerDisplayOptions(), selected: this.mediaPickerSelectedIndex })
            .width('100%').height(this.mediaSelectorMode === 'source' ? 238 : 286)
            .canLoop(false).defaultPickerItemHeight(48)
            .textStyle({ color: AppTheme.textSecondary, font: { size: 16 } })
            .selectedTextStyle({ color: AppTheme.primary, font: { size: 20, weight: FontWeight.Bold } })
            .onChange((value: string | string[], index: number | number[]) => {
              const rawOptions = this.MediaPickerRawOptions();
              const displayOptions = this.MediaPickerDisplayOptions();
              const selected = this.mediaPickerSelectionPolicy.resolve(value, index, rawOptions, displayOptions);
              if (selected.length === 0) return;
              this.mediaPickerPendingValue = selected;
              this.mediaPickerSelectedIndex = rawOptions.indexOf(selected);
            })
          if (this.mediaSelectorMode === 'source') {
            Row({ space: 10 }) {
              SymbolGlyph($r('sys.symbol.folder_fill')).fontSize(18).fontColor([AppTheme.primary])
              Text(this.PendingMediaSourceDescription()).layoutWeight(1).maxLines(2)
                .fontSize(13).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textSecondary)
            }.width('calc(100% - 36vp)').constraintSize({ minHeight: 52 })
              .padding({ left: 14, right: 14, top: 8, bottom: 8 })
              .borderRadius(14).backgroundColor(AppTheme.surfaceTint)
          }
        }
      }.width('100%').height(this.MediaSelectorUsesSearchList() ? 570 : 420).padding({ top: 8, bottom: 18 })
        .borderRadius({ topLeft: 28, topRight: 28 }).backgroundColor(AppTheme.surface)
        .onClick(() => {})
    }.width('100%').height('100%')
  }

  @Builder
  private MediaSearchableSelectorContent() {
    Row({ space: 8 }) {
      SymbolGlyph($r('sys.symbol.magnifyingglass')).fontSize(18)
        .fontColor([this.MediaSearchableSelectorQuery().length > 0 ? AppTheme.primary : AppTheme.textSecondary])
      TextInput({ text: this.MediaSearchableSelectorQuery(),
        placeholder: this.mediaSelectorMode === 'tag' ? '输入内容筛选标签' : '输入内容筛选目录' })
        .layoutWeight(1).height(48).fontSize(15).fontFamily('HarmonyOS Sans')
        .backgroundColor(Color.Transparent).padding(0)
        .onChange((value: string) => this.UpdateMediaSearchableSelectorQuery(value))
      if (this.MediaSearchableSelectorQuery().length > 0) {
        Button() {
          SymbolGlyph($r('sys.symbol.xmark')).fontSize(14).fontColor([AppTheme.textSecondary])
        }.width(40).height(40).padding(0).backgroundColor(Color.Transparent)
          .accessibilityText(this.mediaSelectorMode === 'tag' ? '清除标签搜索内容' : '清除目录搜索内容')
          .onClick(() => this.UpdateMediaSearchableSelectorQuery(''))
      }
    }.width('calc(100% - 36vp)').height(52).padding({ left: 14, right: 8 })
      .border({ width: 1,
        color: this.MediaSearchableSelectorQuery().length > 0 ? AppTheme.primary : AppTheme.divider,
        radius: 16 }).backgroundColor(AppTheme.surfaceTint)
    Text(`共 ${this.FilteredMediaSelectorOptions().length} 个${this.mediaSelectorMode === 'tag' ? '标签' : '目录'} · 单击立即筛选`)
      .width('calc(100% - 36vp)').fontSize(12).fontFamily('HarmonyOS Sans')
      .fontColor(AppTheme.textSecondary)
    if (this.FilteredMediaSelectorOptions().length > 0) {
      List({ space: 0, scroller: this.MediaSearchableSelectorScroller() }) {
        ForEach(this.FilteredMediaSelectorOptions(), (name: string) => {
          ListItem() {
            Button() {
              Row({ space: 12 }) {
                Text(this.MediaSearchableSelectorItemLabel(name)).layoutWeight(1).maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                  .fontSize(15).fontFamily('HarmonyOS Sans')
                  .fontWeight(this.MediaSearchableSelectorItemSelected(name) ?
                    FontWeight.Medium : FontWeight.Normal)
                  .fontColor(this.MediaSearchableSelectorItemSelected(name) ?
                    AppTheme.primary : AppTheme.textPrimary)
                if (this.MediaSearchableSelectorItemSelected(name)) {
                  SymbolGlyph($r('sys.symbol.checkmark')).fontSize(17).fontColor([AppTheme.primary])
                }
              }.width('100%')
            }.width('100%').height(52).padding({ left: 16, right: 16 })
              .backgroundColor(this.MediaSearchableSelectorItemSelected(name) ?
                AppTheme.surfaceTint : Color.Transparent)
              .border({ width: { bottom: 1 }, color: AppTheme.divider })
              .accessibilityText(this.mediaSelectorMode === 'tag' ?
                `使用标签筛选：${name}` : `使用目录筛选：${this.MediaDirectoryDisplayLabel(name)}`)
              .accessibilityDescription('单击立即应用筛选')
              .onClick(() => this.SelectMediaSearchableOption(name))
          }
        }, (name: string) => name)
      }.width('calc(100% - 36vp)').height(310).scrollBar(BarState.Auto)
        .border({ width: 1, color: AppTheme.divider, radius: 16 })
    } else {
      Column({ space: 8 }) {
        SymbolGlyph($r('sys.symbol.magnifyingglass')).fontSize(28).fontColor([AppTheme.textSecondary])
        Text(this.mediaSelectorMode === 'tag' ? '没有匹配的标签' : '没有匹配的目录')
          .fontSize(15).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textPrimary)
        Text('可修改上方输入内容').fontSize(12).fontFamily('HarmonyOS Sans')
          .fontColor(AppTheme.textSecondary)
      }.width('calc(100% - 36vp)').height(180).justifyContent(FlexAlign.Center)
    }
  }

  @Builder
  private MediaDirectoryFilterBar() {
    Scroll() {
      Row({ space: 8 }) {
        Button('全部目录').height(40).padding({ left: 14, right: 14 }).fontFamily('HarmonyOS Sans')
          .fontColor(this.mediaDirectoryGroupFilter === 'all' ? AppTheme.primary : AppTheme.textSecondary)
          .backgroundColor(this.mediaDirectoryGroupFilter === 'all' ? AppTheme.accentSoft : AppTheme.surface)
          .border({ width: 1, color: this.mediaDirectoryGroupFilter === 'all' ? AppTheme.primary : AppTheme.divider,
            radius: AppTheme.pillRadius }).borderRadius(AppTheme.pillRadius)
          .onClick(() => this.mediaDirectoryGroupFilter = 'all')
        ForEach(this.MediaDirectoryNames(), (name: string) => {
          Button(name).height(40).padding({ left: 14, right: 14 }).fontFamily('HarmonyOS Sans')
            .fontColor(this.mediaDirectoryGroupFilter === name ? AppTheme.primary : AppTheme.textSecondary)
            .backgroundColor(this.mediaDirectoryGroupFilter === name ? AppTheme.accentSoft : AppTheme.surface)
            .border({ width: 1, color: this.mediaDirectoryGroupFilter === name ? AppTheme.primary : AppTheme.divider,
              radius: AppTheme.pillRadius }).borderRadius(AppTheme.pillRadius)
            .onClick(() => this.mediaDirectoryGroupFilter = name)
        }, (name: string) => name)
      }
    }.width('100%').height(44).scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off)
  }

  @Builder
  private MediaTagFilterBar() {
    Column({ space: 6 }) {
      Scroll() {
        Row({ space: 8 }) {
          Button('全部').height(40).padding({ left: 14, right: 14 })
            .fontFamily('HarmonyOS Sans')
            .fontColor(this.mediaSelectedTags.length === 0 ? AppTheme.primary : AppTheme.textSecondary)
            .backgroundColor(this.mediaSelectedTags.length === 0 ? AppTheme.accentSoft : AppTheme.surface)
            .border({ width: 1, color: this.mediaSelectedTags.length === 0 ? AppTheme.primary : AppTheme.divider,
              radius: AppTheme.pillRadius }).borderRadius(AppTheme.pillRadius)
            .onClick(() => this.mediaSelectedTags = [])
          ForEach(this.MediaTagNames(), (name: string) => {
            Button(name).height(40).padding({ left: 14, right: 14 })
              .fontFamily('HarmonyOS Sans')
              .fontColor(this.mediaSelectedTags.includes(name) ? AppTheme.primary : AppTheme.textSecondary)
              .backgroundColor(this.mediaSelectedTags.includes(name) ? AppTheme.accentSoft : AppTheme.surface)
              .border({ width: 1, color: this.mediaSelectedTags.includes(name) ? AppTheme.primary : AppTheme.divider,
                radius: AppTheme.pillRadius }).borderRadius(AppTheme.pillRadius)
              .onClick(() => this.ToggleMediaTag(name))
          }, (name: string) => name)
        }
      }.width('100%').height(44).scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off)
      if (this.mediaSelectedTags.length > 1) {
        Row() {
          Text(`已选 ${this.mediaSelectedTags.length} 个标签`).layoutWeight(1).fontSize(12)
            .fontFamily('HarmonyOS Sans').fontColor(AppTheme.textSecondary)
          Button(this.mediaRequireAllTags ? '全部匹配' : '任一匹配').height(40).padding({ left: 14, right: 14 })
            .fontFamily('HarmonyOS Sans')
            .fontColor(AppTheme.primary).backgroundColor(AppTheme.surface)
            .border({ width: 1, color: AppTheme.primary, radius: 18 })
            .onClick(() => this.mediaRequireAllTags = !this.mediaRequireAllTags)
        }.width('100%')
      }
    }.width('100%')
  }

  @Builder
  private MediaContinueCard(progress: MediaPlaybackProgress) {
    Row({ space: 16 }) {
      Column() {
        SymbolGlyph($r(progress.kind === 'audio' ? 'sys.symbol.music_note_circle_fill' :
          'sys.symbol.play_fill')).fontSize(34).fontColor([AppTheme.primary])
      }.width(92).height(92).justifyContent(FlexAlign.Center).borderRadius(AppTheme.cardRadius)
        .backgroundColor(AppTheme.accentSoft)
      Column({ space: 8 }) {
        Text('继续播放').fontSize(12).fontColor(AppTheme.primary)
        Text(progress.name).width('100%').maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          .fontSize(19).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
        Text(`${this.MediaHistorySourceName(progress.accountId)} · ${this.FormatDuration(progress.positionMs)}`)
          .fontSize(12).fontColor(AppTheme.textSecondary)
        if (progress.durationMs > 0) {
          Progress({ value: Math.min(progress.positionMs, progress.durationMs), total: progress.durationMs,
            type: ProgressType.Linear }).width('100%').height(4).color(AppTheme.primary)
        }
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      Button() {
        SymbolGlyph($r('sys.symbol.play_fill')).fontSize(22).fontColor([Color.White])
      }.width(56).height(56).borderRadius(28).backgroundColor(AppTheme.primary)
        .accessibilityText(`继续播放${progress.name}`).onClick(() => this.OpenMediaHistory(progress))
    }.width('100%').padding(18).borderRadius(22).backgroundColor(AppTheme.surfaceTint)
  }

  @Builder
  private MediaPreviewCard(item: RemoteMediaItem) {
    Column() {
      if (item.kind === 'audio') {
        Row({ space: 12 }) {
          SymbolGlyph($r('sys.symbol.music_note_circle_fill')).width(58).height(58).fontSize(26)
            .fontColor([AppTheme.primary]).backgroundColor(AppTheme.surfaceTint).borderRadius(14)
          Column({ space: 4 }) {
            Text(item.name).width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              .fontSize(15).fontFamily('HarmonyOS Sans').fontWeight(FontWeight.Medium)
              .fontColor(AppTheme.textPrimary)
            Text(`${this.FormatMediaSize(item.size)} · ${item.contentType || '音频'}`)
              .fontSize(12).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textSecondary)
            if ((item.tags?.length ?? 0) > 0) {
              Text(this.MediaCompactTagSummary(item)).width('100%').maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis }).fontSize(11)
                .fontFamily('HarmonyOS Sans').fontColor(AppTheme.primary)
            }
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          SymbolGlyph($r('sys.symbol.play_fill')).width(42).height(42).fontSize(17)
            .fontColor([Color.White]).backgroundColor(AppTheme.primary).borderRadius(21)
        }.width('100%').height(82).padding({ left: 12, right: 12 })
          .backgroundColor(AppTheme.surface).border({ width: 1, color: AppTheme.divider, radius: 16 })
      } else {
        Stack({ alignContent: Alignment.TopStart }) {
          if (this.BrowseMediaThumbnailUri(item.path).length > 0) {
            Image(this.BrowseMediaThumbnailUri(item.path)).width('100%').height(176).objectFit(ImageFit.Cover)
          } else {
            Column({ space: 10 }) {
              SymbolGlyph($r(item.kind === 'image' ? 'sys.symbol.picture' : 'sys.symbol.play_fill'))
                .fontSize(34).fontColor([AppTheme.primary])
              Text(this.MediaThumbnailFailed(item.path) ? '预览加载失败' : '正在加载预览')
                .fontSize(11).fontFamily('HarmonyOS Sans')
                .fontColor(this.MediaThumbnailFailed(item.path) ? AppTheme.danger : AppTheme.textSecondary)
            }.width('100%').height(176).justifyContent(FlexAlign.Center).backgroundColor(AppTheme.surfaceTint)
          }
          if ((item.tags?.length ?? 0) > 0) {
            Row({ space: 4 }) {
              ForEach((item.tags ?? []).slice(0, 2), (tag: RemoteMediaTag) => {
                Text(tag.name).fontSize(10).fontColor(Color.White).maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis }).fontFamily('HarmonyOS Sans')
                  .constraintSize({ maxWidth: 64 })
                  .padding({ left: 7, right: 7, top: 4, bottom: 4 }).borderRadius(9)
                  .backgroundColor('#B30F172A')
              }, (tag: RemoteMediaTag) => tag.name)
              if ((item.tags?.length ?? 0) > 2) {
                Text(`+${(item.tags?.length ?? 0) - 2}`).fontSize(10).fontColor(Color.White)
                  .fontFamily('HarmonyOS Sans')
                  .padding({ left: 7, right: 7, top: 4, bottom: 4 }).borderRadius(9).backgroundColor('#B30F172A')
              }
            }.padding(8)
          }
          if (item.kind === 'video') {
            SymbolGlyph($r('sys.symbol.play_fill')).fontSize(16).fontColor([Color.White]).width(38).height(38)
              .backgroundColor('#B30F172A').borderRadius(19).position({ x: 10, y: 128 })
          }
        }.width('100%').height(176).borderRadius(16).clip(true)
          .border({ width: 1, color: AppTheme.divider, radius: 16 })
      }
    }.width(item.kind === 'audio' ? '100%' : '48.5%').alignItems(HorizontalAlign.Start)
      .margin({ bottom: item.kind === 'audio' ? 12 : 10 })
      .accessibilityText(`${this.MediaKindLabel(item)}：${item.name}`)
      // 点击与长按互斥，再与外层纵向滚动并行识别，避免滑动误开媒体或阻断列表滚动。
      .parallelGesture(
        GestureGroup(GestureMode.Exclusive,
          LongPressGesture({ repeat: false, duration: 500 })
            .onAction(() => this.OpenMediaActionSheet(item)),
          TapGesture({ count: 1 }).onAction(() => this.OpenRemoteMedia(item))))
  }

  @Builder
  private MediaKindCard(label: string, kind: string, count: number) {
    Column({ space: 7 }) {
      SymbolGlyph($r(kind === 'image' ? 'sys.symbol.picture' : (kind === 'audio' ?
        'sys.symbol.music_note_circle_fill' : 'sys.symbol.play_fill')))
        .fontSize(25).fontColor([AppTheme.primary])
      Text(label).fontSize(14).fontWeight(FontWeight.Medium)
        .fontColor(this.mediaKindFilter === kind ? AppTheme.primary : AppTheme.textPrimary)
      Text(`${count} 个`).fontSize(11)
        .fontColor(AppTheme.textSecondary)
    }.layoutWeight(1).height(104).justifyContent(FlexAlign.Center).borderRadius(16)
      .backgroundColor(this.mediaKindFilter === kind ? AppTheme.accentSoft : AppTheme.surface)
      .border({ width: 1, color: this.mediaKindFilter === kind ? AppTheme.primary : AppTheme.divider })
      .onClick(() => this.mediaKindFilter = this.mediaKindFilter === kind ? 'all' : kind)
  }

  @Builder
  private MediaHistoryRow(progress: MediaPlaybackProgress, primary: boolean) {
    Row({ space: 12 }) {
      SymbolGlyph($r(progress.kind === 'audio' ? 'sys.symbol.music_note_circle_fill' :
        'sys.symbol.play_fill')).width(40).height(40)
        .fontSize(18).fontColor([AppTheme.primary]).backgroundColor(AppTheme.surfaceTint).borderRadius(20)
      Column({ space: 4 }) {
        Text(progress.name).width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .fontSize(primary ? 16 : 15).fontWeight(primary ? FontWeight.Bold : FontWeight.Normal)
          .fontColor(AppTheme.textPrimary)
        Text(`${this.MediaHistorySourceName(progress.accountId)} · ${this.FormatDuration(progress.positionMs)}`)
          .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
        if (progress.durationMs > 0) {
          Progress({ value: Math.min(progress.positionMs, progress.durationMs), total: progress.durationMs,
            type: ProgressType.Linear }).width('100%').height(3).color(AppTheme.primary)
        }
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      Button(primary ? '继续' : '播放').width(64).height(48).fontSize(13)
        .fontColor(primary ? Color.White : AppTheme.primary)
        .backgroundColor(primary ? AppTheme.primary : AppTheme.surfaceTint)
        .onClick(() => this.OpenMediaHistory(progress))
    }.width('100%').padding({ top: 8, bottom: 8 })
  }

  @Builder
  private ProfilePage() {
    if (this.profileMode === 'privacy') {
      this.ProfilePrivacyPage()
    } else if (this.profileMode === 'about') {
      this.ProfileAboutPage()
    } else if (this.profileMode === 'appearance') {
      this.ProfileAppearancePage()
    } else if (this.profileMode === 'cache') {
      this.ProfileCachePage()
    } else {
      // 显式订阅设置修订号，确保通用设置项点击后同页文案立即刷新。
      Text(`${this.profileSettingsRevision}`).width(0).height(0).opacity(0)
      if (this.authenticated) {
        this.ProfileIdentityPanel()
      } else {
        this.HeroPanel('登录 MyTools', '同步书架、远程媒体和 DSH 会话', '登录', () => this.OpenLogin())
    }
    this.SectionTitle('连接设置')
    this.ProfileActionRow('服务', this.connectionStatus.length > 0 ? `${this.connectionStatus} · 点击重新检测` :
      'MyTools 云服务 · 点击检测连接', () => this.TestConnection())
    this.ProfileActionRow('远程数据源', `${this.mediaSources.length} 个可用来源 · 点击查看`,
      () => this.OpenMainTabFromProfile(3))
    this.SectionTitle('应用设置')
    this.ProfileActionRow('阅读数据', `${this.books.length} 本书 · ${this.bookSources.length} 个书源 · 点击查看`,
      () => this.OpenMainTabFromProfile(0))
    this.ProfileAdultContentRow()
    this.ProfileActionRow('DSH', 'WebView 直连远程 DSH · 点击打开', () => this.OpenMainTabFromProfile(2))
    this.SectionTitle('偏好与支持')
    this.ProfileActionRow('阅读外观', `默认主题：${this.ReaderThemeLabel(this.readerSettings.theme)}`,
      () => this.profileMode = 'appearance')
    this.ProfileActionRow('缓存管理', '查看缓存、阅读容器和全文索引', () => this.OpenProfileCachePage())
    this.ProfileActionRow('隐私与数据', '查看数据边界并管理云端阅读数据', () => this.OpenPrivacyPage())
    this.ProfileActionRow('关于MyTools', '版本、能力与开源边界', () => this.profileMode = 'about')
    if (this.authenticated) {
      Button('退出登录')
        .width('100%')
        .height(48)
        .fontSize(15)
        .fontColor(AppTheme.primary)
        .backgroundColor(Color.Transparent)
        .border({ width: 1, color: AppTheme.primary, radius: 14 })
        .onClick(() => this.RequestLogout())
      if (this.logoutConfirmVisible) {
        Column({ space: 12 }) {
          Text('仍有阅读数据尚未完成同步').width('100%').fontSize(16).fontWeight(FontWeight.Medium)
            .fontColor(AppTheme.textPrimary)
          Text(this.LogoutRiskDescription()).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
          Row({ space: 12 }) {
            Button('继续同步').layoutWeight(1).height(48).onClick(() => {
              this.logoutConfirmVisible = false;
              this.DrainAllReaderSyncQueues();
            })
            Button('仍要退出').layoutWeight(1).height(48).fontColor(Color.White).backgroundColor(AppTheme.danger)
              .onClick(() => this.Logout(true))
          }.width('100%')
        }.width('100%').padding(16).alignItems(HorizontalAlign.Start).borderRadius(14)
          .backgroundColor(AppTheme.surfaceTint)
    }
      }
    }
  }

  @Builder
  private ProfileStandalonePage() {
    Column({ space: 14 }) {
      Row() {
        this.BackButton(AppTheme.textPrimary, () => {
          if (this.profileMode !== 'home') this.LeaveProfileSubpage();
          else this.profileVisible = false;
        })
        Text('我的').layoutWeight(1).fontSize(21).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
        Blank().width(48)
      }.width('100%')
      Scroll() {
        Column({ space: 14 }) {
          this.ProfilePage()
        }.width('100%').alignItems(HorizontalAlign.Start)
      }.layoutWeight(1).width('100%').align(Alignment.TopStart).scrollBar(BarState.Off)
    }.width('100%').height('100%').padding({ left: 18, right: 18, top: 16, bottom: 18 })
      .alignItems(HorizontalAlign.Start).justifyContent(FlexAlign.Start)
      .backgroundColor(AppTheme.background)
  }

  @Builder
  private ProfileSubpageHeader(title: string) {
    Row() {
      this.BackButton(AppTheme.textPrimary, () => this.LeaveProfileSubpage())
      Text(title).layoutWeight(1).textAlign(TextAlign.Center).fontSize(20)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Blank().width(48)
    }.width('100%')
  }

  @Builder
  private ProfileAppearancePage() {
    // 显式订阅设置修订号，确保通用设置按钮参数在同页点击后立即重建。
    Text(`${this.profileSettingsRevision}`).width(0).height(0).opacity(0)
    this.ProfileSubpageHeader('阅读外观')
    Text('默认阅读主题').width('100%').fontSize(16).fontWeight(FontWeight.Medium)
      .fontColor(AppTheme.textPrimary)
    Text('新打开的图书和当前阅读器统一使用该主题，设置会保存在设备上。')
      .width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
    Row({ space: 8 }) {
      this.ProfileReaderThemeButton('纸张', 'paper', '#F5EDD8')
      this.ProfileReaderThemeButton('白色', 'white', '#FFFFFF')
      this.ProfileReaderThemeButton('护眼', 'green', '#DDEBDD')
      this.ProfileReaderThemeButton('夜间', 'night', '#252938')
    }.width('100%')
    this.ProfileAppearanceActionRow('fontSize')
    this.ProfileAppearanceActionRow('fontFamily')
    this.ProfileAppearanceActionRow('lineHeight')
    this.ProfileAppearanceActionRow('paragraphSpacing')
    this.ProfileAppearanceActionRow('orientation')
    this.ProfileAppearanceActionRow('pageTurnMode')
  }

  @Builder
  private ProfileAppearanceActionRow(mode: string) {
    Button() {
      Row({ space: 12 }) {
        Column({ space: 5 }) {
          if (mode === 'fontSize') {
            Text('字号').width('100%').fontSize(15).fontColor(AppTheme.textPrimary)
            Text(`${this.readerSettings.fontSize.toFixed(0)} px · 点击增大`)
              .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
          } else if (mode === 'fontFamily') {
            Text('字体').width('100%').fontSize(15).fontColor(AppTheme.textPrimary)
            Text(`${this.ReaderFontLabel()} · 点击切换`)
              .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
          } else if (mode === 'lineHeight') {
            Text('行距').width('100%').fontSize(15).fontColor(AppTheme.textPrimary)
            Text(`${this.readerSettings.lineHeight.toFixed(2)} · 点击增大`)
              .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
          } else if (mode === 'paragraphSpacing') {
            Text('段距').width('100%').fontSize(15).fontColor(AppTheme.textPrimary)
            Text(`${this.readerSettings.paragraphSpacing.toFixed(0)} vp · 点击增大`)
              .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
          } else if (mode === 'orientation') {
            Text('屏幕方向').width('100%').fontSize(15).fontColor(AppTheme.textPrimary)
            Text(`${this.ReaderOrientationLabel()} · 点击切换`)
              .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
          } else {
            Text('翻页方式').width('100%').fontSize(15).fontColor(AppTheme.textPrimary)
            Text(`${this.readerSettings.pageTurnMode === 'scroll' ? '滚动' :
              (this.readerSettings.pageTurnMode === 'slide' ? '滑动' : '覆盖')} · 点击切换`)
              .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
          }
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        Text('›').fontSize(24).fontColor(AppTheme.textSecondary)
      }.width('100%')
    }.width('100%').constraintSize({ minHeight: 64 }).padding({ left: 0, right: 0, top: 13, bottom: 13 })
      .backgroundColor(Color.Transparent).border({ width: { bottom: 1 }, color: AppTheme.divider })
      .onClick(() => {
        if (mode === 'fontSize') this.CycleProfileFontSize();
        else if (mode === 'fontFamily') this.CycleProfileFontFamily();
        else if (mode === 'lineHeight') this.CycleProfileLineHeight();
        else if (mode === 'paragraphSpacing') this.CycleProfileParagraphSpacing();
        else if (mode === 'orientation') this.CycleProfileOrientation();
        else this.CycleProfilePageTurnMode();
      })
  }

  @Builder
  private ProfileReaderThemeButton(label: string, theme: string, color: string) {
    Button(label).layoutWeight(1).height(48).fontSize(13)
      .fontColor(theme === 'night' ? Color.White : AppTheme.textPrimary)
      .backgroundColor(color).border({ width: this.readerSettings.theme === theme ? 2 : 1,
        color: this.readerSettings.theme === theme ? AppTheme.primary : AppTheme.divider, radius: 12 })
      .accessibilityDescription(this.readerSettings.theme === theme ? '已选择' : '未选择')
      .onClick(() => {
        this.readerSettings = this.CopyReaderSettings(this.readerSettings.fontSize, this.readerSettings.lineHeight,
          this.readerSettings.horizontalPadding, this.readerSettings.brightness, theme as ReaderTheme,
          this.readerSettings.pageTurnMode);
        this.PersistReaderState();
      })
  }

  @Builder
  private ProfileCachePage() {
    this.ProfileSubpageHeader('缓存管理')
    this.ProfileInfoRow('远程封面', `${this.FormatFileSize(this.profileCoverCacheBytes)} · 最多96项`)
    this.ProfileInfoRow('书源漫画页', `${this.FormatFileSize(this.profileComicCacheBytes)} · 最多256项`)
    this.ProfileInfoRow('媒体缩略图', `${this.FormatFileSize(this.profileMediaCacheBytes)} · 最多64项`)
    this.ProfileInfoRow('阅读容器', '关闭阅读器时自动清理')
    this.ProfileInfoRow('全文索引', '仅保存检索特征 · 最多16本')
    if (this.profileCacheStatus.length > 0) {
      Text(this.profileCacheStatus).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
    }
    Button('清理全部远程图片缓存').width('100%').height(48)
      .fontSize(14).fontColor(AppTheme.danger).backgroundColor(Color.Transparent)
      .border({ width: 1, color: AppTheme.danger, radius: 14 })
      .onClick(() => this.ClearRemoteImageCaches())
    Text('该操作只删除可恢复的图书封面、书源漫画页和远程媒体缩略图，不会删除书架、书源、阅读记录、本地导入文件或远程原文件。内容会在下次需要时重新安全下载。')
      .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
  }

  @Builder
  private ProfilePrivacyPage() {
    this.ProfileSubpageHeader('隐私说明')
    this.PrivacySection('登录凭证', 'Access Token与Refresh Token保存在系统Asset Store，Preferences不保存令牌。')
    this.PrivacySection('本地图书', '仅在用户主动选择文件后读取；多媒体页面不会扫描设备本地图片、音频或视频。')
    this.PrivacySection('远程内容', 'WebDAV与Alist凭据保留在MyTools后端，播放地址只包含短期随机票据。')
    this.PrivacySection('网络书源', '公开地址经过公网DNS、重定向和内容大小检查；脚本规则不会被直接执行。')
    this.PrivacySection('DSH', 'JWT、模型密钥和远程账户凭据不写入提示词；会话按账户隔离。')
    this.SectionTitle('阅读同步数据')
    if (this.readerDataSummary !== undefined) {
      this.ProfileInfoRow('云端记录', `${this.readerDataSummary.shelfRecords} 本书 · ` +
        `${this.readerDataSummary.sourceRecords} 个书源 · ${this.readerDataSummary.progressRecords} 条进度 · ` +
        `${this.readerDataSummary.markerRecords} 条标记`)
    }
    if (this.readerDataStatus.length > 0) {
      Text(this.readerDataStatus).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
    }
    if (this.authenticated && this.readerDataDeleteConfirm) {
      Text('将删除当前MyTools账号中的书架、书源、进度、书签和批注同步记录，并清空本应用中的对应索引。不会删除设备原文件或远程书库文件。其他客户端仍可能再次上传其本地记录。')
        .width('100%').fontSize(13).fontColor(AppTheme.danger)
      Row({ space: 12 }) {
        Button('取消').layoutWeight(1).height(48).enabled(!this.readerDataDeleting)
          .onClick(() => this.readerDataDeleteConfirm = false)
        Button(this.readerDataDeleting ? '正在删除…' : '确认删除').layoutWeight(1).height(48)
          .fontColor(Color.White).backgroundColor(AppTheme.danger)
          .enabled(!this.readerDataDeleting && !this.ReaderSyncOperationActive())
          .onClick(() => this.DeleteReaderCloudData())
      }.width('100%')
    } else if (this.authenticated) {
      Button('删除阅读同步数据').width('100%').height(48).fontColor(AppTheme.danger)
        .backgroundColor(Color.Transparent).border({ width: 1, color: AppTheme.danger, radius: 14 })
        .onClick(() => this.readerDataDeleteConfirm = true)
    } else {
      Text('登录后可查看或删除当前账号的云端阅读同步数据。').width('100%').fontSize(13)
        .fontColor(AppTheme.textSecondary)
    }
  }

  @Builder
  private PrivacySection(title: string, content: string) {
    Column({ space: 6 }) {
      Text(title).width('100%').fontSize(16).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
      Text(content).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
    }.width('100%').padding(16).alignItems(HorizontalAlign.Start)
      .borderRadius(14).backgroundColor(AppTheme.surfaceTint)
  }

  private OpenPrivacyPage(): void {
    this.profileMode = 'privacy';
    this.readerDataDeleteConfirm = false;
    this.readerDataStatus = '';
    this.LoadReaderDataSummary();
  }

  /** 离开个人二级页时终止隐私页拥有的摘要读取。 */
  private LeaveProfileSubpage(): void {
    if (this.profileMode === 'privacy') this.CancelReaderDataSummary();
    this.readerDataDeleteConfirm = false;
    this.profileMode = 'home';
  }

  /** 使当前云端阅读数据摘要读取失效，不影响删除写操作。 */
  private CancelReaderDataSummary(): void {
    this.activeReaderDataSummaryCancellation?.cancel();
    this.activeReaderDataSummaryCancellation = undefined;
    this.readerDataSummaryRevision++;
  }

  private async LoadReaderDataSummary(): Promise<void> {
    if (!this.authenticated || this.serviceUrl.length === 0) {
      this.readerDataSummary = undefined;
      return;
    }
    this.activeReaderDataSummaryCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeReaderDataSummaryCancellation = cancellation;
    const summaryRevision = ++this.readerDataSummaryRevision;
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.readerDataStatus = '正在读取云端数据摘要…';
    try {
      const summary = await new ReaderDataApi(this.ActiveServiceUrl(), this.authManager).summary(cancellation);
      if (!this.AccountOperationCurrent(accountRevision, owner) || summaryRevision !== this.readerDataSummaryRevision ||
        this.profileMode !== 'privacy') return;
      this.readerDataSummary = summary;
      this.readerDataStatus = '';
    } catch (error) {
      if (!this.AccountOperationCurrent(accountRevision, owner) || summaryRevision !== this.readerDataSummaryRevision ||
        cancellation.isCancelled()) return;
      this.readerDataStatus = error instanceof Error ? error.message : '读取云端数据摘要失败';
    } finally {
      if (this.activeReaderDataSummaryCancellation === cancellation) {
        this.activeReaderDataSummaryCancellation = undefined;
      }
    }
  }

  private async DeleteReaderCloudData(): Promise<void> {
    if (!this.authenticated || this.readerDataDeleting || !this.readerDataDeleteConfirm) return;
    if (this.ReaderSyncOperationActive()) {
      this.readerDataStatus = '请等待当前同步请求结束后再删除';
      return;
    }
    this.CancelReaderDataSummary();
    const deleteRevision = ++this.readerDataDeleteRevision;
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    const sources = this.bookSources.slice();
    this.readerDataDeleting = true;
    this.readerDataStatus = '正在删除当前账号的阅读同步数据…';
    try {
      const deleted = await new ReaderDataApi(this.ActiveServiceUrl(), this.authManager).deleteAll();
      if (!this.AccountOperationCurrent(accountRevision, owner) || deleteRevision !== this.readerDataDeleteRevision) {
        return;
      }
      for (const source of sources) {
        if (!this.AccountOperationCurrent(accountRevision, owner) || deleteRevision !== this.readerDataDeleteRevision) {
          return;
        }
        try {
          await this.bookSourceCredentialStore.clear(source.bookSourceUrl);
        } catch (_) {
        }
        if (!this.AccountOperationCurrent(accountRevision, owner) || deleteRevision !== this.readerDataDeleteRevision) {
          return;
        }
      }
      if (!this.AccountOperationCurrent(accountRevision, owner) || deleteRevision !== this.readerDataDeleteRevision) {
        return;
      }
      this.readerProgressSyncGeneration++;
      this.pendingReaderProgress = [];
      this.pendingReaderMarkers = [];
      this.pendingShelfBooks = [];
      this.pendingBookSources = [];
      this.readerProgressPullPending = false;
      this.readerMarkerPullPending = false;
      this.shelfPullPending = false;
      this.bookSourcePullPending = false;
      this.books = [];
      this.shelfBookTombstones = [];
      this.bookSources = [];
      this.bookSourceTombstones = [];
      this.readingProgress = [];
      this.readingProgressTombstones = [];
      this.readerBookmarks = [];
      this.readerAnnotations = [];
      this.readerMarkerTombstones = [];
      this.readingStatistics = [];
      this.PersistReaderState();
      this.readerDataSummary = { shelfRecords: 0, sourceRecords: 0, progressRecords: 0, markerRecords: 0 };
      this.readerDataStatus = `已删除 ${deleted} 条云端记录；设备原文件和远程文件未被删除`;
      this.readerSyncStatus = '已清空阅读同步数据';
      this.readerDataDeleteConfirm = false;
    } catch (error) {
      if (!this.AccountOperationCurrent(accountRevision, owner) || deleteRevision !== this.readerDataDeleteRevision) {
        return;
      }
      this.readerDataStatus = error instanceof Error ? error.message : '删除阅读同步数据失败';
    } finally {
      if (deleteRevision === this.readerDataDeleteRevision) this.readerDataDeleting = false;
    }
  }

  private ReaderSyncOperationActive(): boolean {
    return this.readerProgressSyncing || this.readerMarkerSyncing || this.shelfSyncing || this.bookSourceSyncing;
  }

  @Builder
  private ProfileAboutPage() {
    this.ProfileSubpageHeader('关于MyTools')
    Column({ space: 10 }) {
      Text('MyTools').fontSize(28).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Text('HarmonyOS NEXT 客户端').fontSize(15).fontColor(AppTheme.textSecondary)
      Text('API 12 · Stage模型').fontSize(13).fontColor(AppTheme.textSecondary)
    }.width('100%').padding(24).borderRadius(18).backgroundColor(AppTheme.surfaceTint)
    this.ProfileInfoRow('主要能力', '电子书、工具、DSH、远程多媒体')
    this.ProfileInfoRow('DSH', this.copilotVersion.length > 0 ? `服务 ${this.copilotVersion}` : '等待连接')
    Text('书源兼容采用独立实现，不复制或内置开源阅读GPL代码，也不随应用分发第三方书源。')
      .width('100%').padding(14).fontSize(13).fontColor(AppTheme.textSecondary)
      .borderRadius(12).backgroundColor(AppTheme.surfaceTint)
  }

  @Builder
  private ProfileIdentityPanel() {
    Row({ space: 16 }) {
      Stack({ alignContent: Alignment.Center }) {
        if (this.profileAvatarPixelMap !== undefined && !this.profileAvatarFailed) {
          Image(this.profileAvatarPixelMap).width(72).height(72).objectFit(ImageFit.Cover)
            .borderRadius(36)
        } else if (this.ProfileAvatarSource().length > 0 && !this.profileAvatarFailed) {
          Image(this.ProfileAvatarSource()).width(72).height(72).objectFit(ImageFit.Cover)
            .borderRadius(36).onError(() => this.profileAvatarFailed = true)
        } else {
          Text(this.ProfileAvatarLabel()).width(72).height(72).fontSize(24).fontWeight(FontWeight.Bold)
            .textAlign(TextAlign.Center).fontColor(Color.White).backgroundColor(AppTheme.primary).borderRadius(36)
        }
      }.width(72).height(72)
      Column({ space: 5 }) {
        Text(this.profileName).width('100%').fontSize(21).fontWeight(FontWeight.Bold)
          .fontColor(AppTheme.textPrimary).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(`用户名：${this.profileUsername}`).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(`邮箱：${this.ProfileEmailLabel()}`).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(`角色：${this.profileRole}`).width('100%').fontSize(12).fontColor(AppTheme.textTertiary)
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
    }.width('100%').padding(20).borderRadius(AppTheme.cardRadius).backgroundColor(AppTheme.surface)
      .border({ width: 1, color: AppTheme.divider, radius: AppTheme.cardRadius })
      .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
  }

  private ProfileEmailLabel(): string {
    // 登录账号为邮箱时直接展示，否则明确提示当前资料未提供邮箱字段。
    return this.account.includes('@') ? this.account : '未绑定邮箱';
  }

  @Builder
  private ProfileActionRow(title: string, subtitle: string, action: () => void) {
    Row({ space: 12 }) {
      Column({ space: 5 }) {
        Text(title).width('100%').fontSize(15).fontColor(AppTheme.textPrimary)
        Text(subtitle).width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      Text('›').fontSize(24).fontColor(AppTheme.textSecondary)
    }.width('100%').height(68).padding({ left: 0, right: 0, top: 10, bottom: 10 })
      .backgroundColor(Color.Transparent)
      .border({ width: { bottom: 1 }, color: AppTheme.divider })
      .accessibilityText(title).accessibilityDescription(subtitle).onClick(action)
  }

  @Builder
  private ProfileAdultContentRow() {
    Row({ space: 12 }) {
      Column({ space: 5 }) {
        Text('成人内容过滤').width('100%').fontSize(15).fontColor(AppTheme.textPrimary)
        Text(this.hideAdultContent ? '已开启 · 隐藏确认的 R18 内容' : '已关闭')
          .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
      }.layoutWeight(1).height(68).justifyContent(FlexAlign.Center).alignItems(HorizontalAlign.Start)
        .onClick(() => this.ToggleAdultContentFilter())
      Toggle({ type: ToggleType.Switch, isOn: this.hideAdultContent })
        .selectedColor(AppTheme.primary)
        .accessibilityText('成人内容过滤')
        .accessibilityDescription(this.hideAdultContent ? '已开启' : '已关闭')
        .onChange((value: boolean) => this.SetAdultContentFilter(value))
    }.width('100%').height(68).border({ width: { bottom: 1 }, color: AppTheme.divider })
  }

  private OpenProfileCachePage(): void {
    this.profileCacheStatus = '';
    this.RefreshRemoteCacheUsage();
    this.profileMode = 'cache';
  }

  private OpenMainTabFromProfile(index: number): void {
    this.profileVisible = false;
    this.profileMode = 'home';
    this.ChangeMainTab(index);
    if (index === 3 && this.authenticated) this.LoadMediaCatalog();
    else if (index === 4 && this.authenticated) this.LoadDrives();
  }

  private CycleProfileFontSize(): void {
    const value = this.readerSettings.fontSize + 2 > 32 ? 14 : this.readerSettings.fontSize + 2;
    this.readerSettings = this.CopyReaderSettings(value, this.readerSettings.lineHeight,
      this.readerSettings.horizontalPadding, this.readerSettings.brightness, this.readerSettings.theme,
      this.readerSettings.pageTurnMode);
    this.PersistReaderState();
    this.profileSettingsRevision++;
  }

  private CycleProfileFontFamily(): void {
    const values: ReaderFontFamily[] = ['system', 'sans', 'serif', 'monospace'];
    const current = values.indexOf(this.readerSettings.fontFamily);
    this.SetReaderFontFamily(values[(current + 1) % values.length]);
    this.profileSettingsRevision++;
  }

  private CycleProfileLineHeight(): void {
    const value = this.readerSettings.lineHeight + 0.2 > 2.4 ? 1.2 :
      Number((this.readerSettings.lineHeight + 0.2).toFixed(1));
    this.readerSettings = this.CopyReaderSettings(this.readerSettings.fontSize, value,
      this.readerSettings.horizontalPadding, this.readerSettings.brightness, this.readerSettings.theme,
      this.readerSettings.pageTurnMode);
    this.PersistReaderState();
    this.profileSettingsRevision++;
  }

  private CycleProfileParagraphSpacing(): void {
    this.readerSettings.paragraphSpacing = this.readerSettings.paragraphSpacing >= 32 ? 0 :
      this.readerSettings.paragraphSpacing + 4;
    this.readerSettings = this.CopyReaderSettings(this.readerSettings.fontSize, this.readerSettings.lineHeight,
      this.readerSettings.horizontalPadding, this.readerSettings.brightness, this.readerSettings.theme,
      this.readerSettings.pageTurnMode);
    this.PersistReaderState();
    this.profileSettingsRevision++;
  }

  private CycleProfileOrientation(): void {
    const values: ReaderOrientation[] = ['system', 'portrait', 'landscape'];
    const current = values.indexOf(this.readerSettings.orientation);
    this.SetReaderOrientation(values[(current + 1) % values.length]);
    this.profileSettingsRevision++;
  }

  private CycleProfilePageTurnMode(): void {
    const values: PageTurnMode[] = ['scroll', 'slide', 'cover'];
    const current = values.indexOf(this.readerSettings.pageTurnMode);
    this.SetReaderTurnMode(values[(current + 1) % values.length]);
    this.PersistReaderState();
    this.profileSettingsRevision++;
  }

  @Builder
  private BookSourceSearchField() {
    Column({ space: 8 }) {
      Row({ space: 4 }) {
        Button() {
          Row({ space: 6 }) {
            Text(this.BookSourceSearchModeLabel()).fontSize(13)
              .fontWeight(this.sourceSearchModeVisible ? FontWeight.Medium : FontWeight.Normal)
              .fontColor(this.sourceSearchModeVisible ? AppTheme.primary : AppTheme.textSecondary)
            SymbolGlyph($r('sys.symbol.chevron_down')).fontSize(10)
              .fontColor([this.sourceSearchModeVisible ? AppTheme.primary : AppTheme.textTertiary])
          }
        }.width(68).height(42).padding(0).borderRadius(AppTheme.pillRadius)
          .backgroundColor(this.sourceSearchModeVisible ? AppTheme.accentSoft : Color.Transparent)
          .accessibilityText(`搜索模式：${this.BookSourceSearchModeLabel()}`)
          .accessibilityDescription('点击切换精确、模糊或探测搜索')
          .onClick(() => this.sourceSearchModeVisible = !this.sourceSearchModeVisible)
        TextInput({ text: this.sourceSearchText, placeholder: this.BookSourceSearchPlaceholder() })
          .layoutWeight(1).height(48).padding(0).fontSize(15).fontFamily('HarmonyOS Sans')
          .placeholderFont({ size: 15, weight: FontWeight.Normal, family: 'HarmonyOS Sans' })
          .backgroundColor(Color.Transparent)
          .onChange((value: string) => this.sourceSearchText = value)
          .onSubmit(() => this.SearchBookSources())
        if (this.sourceSearchLoading) {
          Button() {
            SymbolGlyph($r('sys.symbol.xmark')).fontSize(16).fontColor([AppTheme.danger])
          }.width(42).height(42).padding(0).borderRadius(AppTheme.pillRadius)
            .backgroundColor(AppTheme.dangerSoft)
            .accessibilityText('停止搜索')
            .onClick(() => this.CancelBookSourceSearch())
        } else {
          Button() {
            SymbolGlyph($r('sys.symbol.magnifyingglass')).fontSize(20)
              .fontColor([this.sourceSearchText.trim().length > 0 ? Color.White : AppTheme.primary])
          }.width(42).height(42).padding(0).borderRadius(AppTheme.pillRadius)
            .backgroundColor(this.sourceSearchText.trim().length > 0 ? AppTheme.primary : Color.Transparent)
            .accessibilityText('搜索书源')
            .onClick(() => this.SearchBookSources())
        }
      }.width('100%').height(54).padding({ left: 4, right: 6 }).backgroundColor(AppTheme.glass)
        .border({ width: 1, color: this.sourceSearchModeVisible ? AppTheme.primary : AppTheme.divider,
          radius: AppTheme.inputRadius })
        .borderRadius(AppTheme.inputRadius)
        .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
      if (this.sourceSearchModeVisible) {
        Row() {
          Column({ space: 2 }) {
          ForEach(['FUZZY', 'EXACT', 'PROBE'], (mode: string) => {
            Button() {
              Row({ space: 8 }) {
                Column({ space: 2 }) {
                  Text(this.BookSourceSearchModeLabel(mode)).width('100%').fontSize(13)
                    .fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
                  Text(this.BookSourceSearchModeDescription(mode)).width('100%').fontSize(10)
                    .fontColor(AppTheme.textSecondary)
                }.layoutWeight(1).alignItems(HorizontalAlign.Start)
                if (this.sourceSearchMode === mode) {
                  SymbolGlyph($r('sys.symbol.checkmark')).fontSize(15).fontColor([AppTheme.primary])
                }
              }.width('100%')
            }.width('100%').height(50).padding({ left: 12, right: 12 }).borderRadius(12)
              .backgroundColor(this.sourceSearchMode === mode ? AppTheme.surfaceTint : Color.Transparent)
              .onClick(() => {
                this.sourceSearchMode = mode;
                this.sourceSearchModeVisible = false;
              })
          })
          }.width(184).padding(6).borderRadius(15).backgroundColor(AppTheme.surface)
            .border({ width: 1, color: AppTheme.divider, radius: 15 })
        }.width('100%').justifyContent(FlexAlign.Start).padding({ left: 2 })
      }
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private BookSourceSearchResultsPage() {
    Column({ space: 10 }) {
      Row() {
        Button('‹ 返回书架').height(40).padding({ left: 4, right: 10 }).fontSize(13)
          .fontColor(AppTheme.primary).backgroundColor(Color.Transparent)
          .onClick(() => this.LeaveBookSourceSearchResults())
        Text('搜索结果').layoutWeight(1).textAlign(TextAlign.Center).fontSize(18)
          .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
        Text(`${this.sourceSearchResults.length} 本`).width(70).textAlign(TextAlign.End)
          .fontSize(12).fontColor(AppTheme.textSecondary)
      }.width('100%')
      this.BookSourceSearchField()
      if (this.bookStatus.length > 0) {
        Text(this.bookStatus).width('100%').maxLines(2).fontSize(12).fontColor(AppTheme.textSecondary)
      }
      Scroll(this.sourceSearchScroller) {
        Column() {
          if (this.sourceSearchResults.length > 0) {
            ForEach(this.VisibleBookSourceSearchResults(), (result: BookSourceSearchResult) => {
              this.BookSourceResultRow(result)
            }, (result: BookSourceSearchResult) => this.BookSourceResultIdentity(result))
          } else if (this.sourceSearchLoading) {
            this.NetworkLoadingRows('后端正在搜索已启用书源')
          } else {
            this.PlaceholderRow('暂无搜索结果', '可以更换关键词或搜索模式')
          }
        }.width('100%').alignItems(HorizontalAlign.Start).padding({ bottom: 18 })
      }.width('100%').layoutWeight(1).align(Alignment.TopStart)
        .edgeEffect(EdgeEffect.None).scrollBar(BarState.Off)
        .onReachEnd(() => this.LoadMoreBookSourceSearchResults())
    }.width('100%').layoutWeight(1).alignItems(HorizontalAlign.Start)
      .onAppear(() => this.RestoreBookSourceSearchReturnPosition())
  }

  /** 关闭书源搜索结果并回到当前书源书架。 */
  private LeaveBookSourceSearchResults(): void {
    if (this.sourceSearchLoading) this.CancelBookSourceSearch();
    this.sourceSearchPageVisible = false;
    this.sourceSearchReturnOffset = 0;
    this.sourceSearchReturnPending = false;
    this.sourceSearchReturnRestoreScheduled = false;
    this.sourceSearchReturnRevision++;
  }

  @Builder
  private BookSourceSummaryRow() {
    Row({ space: 10 }) {
      SymbolGlyph($r('sys.symbol.square_stack_3d_fill')).fontSize(18).fontColor([AppTheme.primary])
      Text(`已启用 ${this.EnabledBookSourceCount()} 个书源`)
        .layoutWeight(1).fontSize(14).fontColor(AppTheme.textPrimary)
      Button(this.bookSourceManagementVisible ? '收起' : '管理 ›')
        .height(40).padding({ left: 12, right: 12 }).fontSize(13)
        .fontColor(AppTheme.primary).backgroundColor(AppTheme.surface)
        .onClick(() => {
          this.sourceManageVisibleCount = this.sourceManageRenderBatchSize;
          this.bookSourceManagementVisible = !this.bookSourceManagementVisible;
        })
    }.width('100%').height(48).padding({ left: 12, right: 4 })
      .borderRadius(14).backgroundColor(AppTheme.surfaceTint)
  }

  @Builder
  private BookSourceManagementPanel() {
    Column({ space: 0 }) {
      ForEach(this.VisibleManagedBookSources(), (source: BookSource) => {
        this.BookSourceManageRow(source)
        if (this.sourceExploreSourceUrl === source.bookSourceUrl) this.BookSourceExplorePanel(source)
        if (this.sourceCredentialUrl === source.bookSourceUrl) this.BookSourceCredentialPanel(source)
        if (this.sourceDeleteUrl === source.bookSourceUrl) this.BookSourceDeletePanel(source)
      }, (source: BookSource) => source.bookSourceUrl)
      if (this.bookSources.length === 0) {
        this.PlaceholderRow('还没有书源', '导入开源阅读 JSON 书源后即可搜索')
      } else if (this.FilteredManagedBookSources().length === 0) {
        this.PlaceholderRow('没有匹配的书源', '可更换名称、分组或启用状态筛选条件')
      }
    }.width('100%').backgroundColor(Color.Transparent)
  }

  @Builder
  private BookSourceResultRow(result: BookSourceSearchResult) {
    Row({ space: 12 }) {
      if (result.coverUrl.length > 0) {
        Image(result.coverUrl).width(50).height(74).objectFit(ImageFit.Cover).borderRadius(6)
      } else {
        Image($r('app.media.reader_default_cover')).width(50).height(74)
          .objectFit(ImageFit.Cover).borderRadius(6)
      }
      Column({ space: 4 }) {
        Text(result.name).width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .fontSize(15).fontFamily('HarmonyOS Sans').fontWeight(FontWeight.Bold)
          .fontColor(AppTheme.textPrimary)
        Text(`${result.author.length > 0 ? result.author : '未知作者'} · ${result.sourceName}`)
          .width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .fontSize(12).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textSecondary)
        if (result.lastChapter.length > 0) {
          Text(result.lastChapter).width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .fontSize(12).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textSecondary)
        }
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(16).fontColor([AppTheme.textSecondary])
    }
    .width('100%').height(94).padding({ left: 0, right: 2, top: 10, bottom: 10 })
    .backgroundColor(Color.Transparent)
    .border({ width: { bottom: 1 }, color: AppTheme.divider })
    .alignItems(VerticalAlign.Center)
    .accessibilityDescription('打开图书详情')
    .onClick(() => this.OpenBookSourceDetail(result))
  }

  @Builder
  private ModeSelector(items: string[]) {
    Row() {
      ForEach(items, (item: string, index: number) => {
        Text(item)
          .layoutWeight(1)
          .textAlign(TextAlign.Center)
          .fontSize(15)
          .fontColor(index === 0 ? AppTheme.primary : AppTheme.textSecondary)
      })
    }
    .width('100%')
    .height(52)
    .border({ width: 1, color: AppTheme.divider, radius: 18 })
    .backgroundColor(AppTheme.surface)
  }

  @Builder
  private HeroPanel(title: string, subtitle: string, action: string, onAction: () => void) {
    Column({ space: 10 }) {
      Text(title).fontSize(22).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Text(subtitle).fontSize(15).fontColor(AppTheme.textSecondary)
      Button(action)
        .height(48)
        .fontSize(15)
        .fontColor(Color.White)
        .padding({ left: 22, right: 22 })
        .borderRadius(AppTheme.pillRadius)
        .backgroundColor(AppTheme.primary)
        .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
        .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
        .accessibilityText(action)
        .accessibilityDescription(`${title}：${subtitle}`)
        .onClick(onAction)
    }
    .width('100%')
    .padding(22)
    .alignItems(HorizontalAlign.Start)
    .borderRadius(AppTheme.cardRadius)
    .backgroundColor(AppTheme.surface)
    .border({ width: 1, color: AppTheme.divider, radius: AppTheme.cardRadius })
    .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
  }

  @Builder
  private SectionTitle(title: string) {
    Text(title)
      .width('100%')
      .fontSize(22)
      .fontFamily('HarmonyOS Sans')
      .fontWeight(FontWeight.Bold)
      .fontColor(AppTheme.textPrimary)
  }

  @Builder
  private PlaceholderRow(title: string, subtitle: string) {
    Row() {
      Column({ space: 5 }) {
        Text(title).fontSize(17).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textPrimary)
        if (subtitle.length > 0) {
          Text(subtitle).fontSize(13).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textSecondary)
        }
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      Text('›').fontSize(24).fontColor(AppTheme.textSecondary)
    }
    .width('100%')
    .padding({ top: 14, bottom: 14 })
    .border({ width: { bottom: 1 }, color: AppTheme.divider })
  }

  @Builder
  private BookSourceManageRow(source: BookSource) {
    Column({ space: 0 }) {
      Row({ space: 10 }) {
        Column() {
          SymbolGlyph($r('sys.symbol.book_open_fill')).fontSize(19)
            .fontColor([source.enabled ? AppTheme.primary : AppTheme.textSecondary])
        }.width(40).height(40).justifyContent(FlexAlign.Center).borderRadius(12)
          .backgroundColor(AppTheme.surfaceTint)
        Column({ space: 4 }) {
          Text(source.bookSourceName)
            .width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .fontSize(15).fontFamily('HarmonyOS Sans').fontWeight(FontWeight.Bold)
            .fontColor(AppTheme.textPrimary)
          Text(this.BookSourceManageSubtitle(source))
            .width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .fontSize(11).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textSecondary)
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        Toggle({ type: ToggleType.Switch, isOn: source.enabled })
          .width(44).height(26).selectedColor(AppTheme.primary)
          .accessibilityText(source.enabled ? '停用书源' : '启用书源')
          .onChange((enabled: boolean) => {
            if (enabled !== source.enabled) this.ToggleBookSource(source.bookSourceUrl);
          })
        Button() {
          if (this.sourceManageExpandedUrl === source.bookSourceUrl) {
            SymbolGlyph($r('sys.symbol.chevron_up')).fontSize(16).fontColor([AppTheme.textSecondary])
          } else {
            SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(16).fontColor([AppTheme.textSecondary])
          }
        }.width(34).height(40).padding(0).borderRadius(17).backgroundColor(Color.Transparent)
          .accessibilityText(this.sourceManageExpandedUrl === source.bookSourceUrl ? '收起书源操作' : '展开书源操作')
          .onClick(() => {
            this.sourceManageExpandedUrl = this.sourceManageExpandedUrl === source.bookSourceUrl ? '' : source.bookSourceUrl;
            this.CancelDeleteBookSource();
          })
      }.width('100%').height(72).alignItems(VerticalAlign.Center)
      if (this.sourceManageExpandedUrl === source.bookSourceUrl) {
        Row({ space: 0 }) {
          if (source.enabled && source.enabledExplore && source.exploreUrl.trim().length > 0) {
            Button('发现').layoutWeight(1).height(44).fontSize(13)
              .fontColor(AppTheme.primary).backgroundColor(Color.Transparent)
              .onClick(() => this.OpenBookSourceExplore(source))
            Divider().vertical(true).height(24).color(AppTheme.divider)
          }
          Button('凭据').layoutWeight(1).height(44).fontSize(13)
            .fontColor(AppTheme.primary).backgroundColor(Color.Transparent)
            .onClick(() => this.OpenBookSourceCredential(source))
          Divider().vertical(true).height(24).color(AppTheme.divider)
          Button('删除').layoutWeight(1).height(44).fontSize(13)
            .fontColor(AppTheme.danger).backgroundColor(Color.Transparent)
            .onClick(() => this.PrepareDeleteBookSource(source))
        }.width('100%').height(48).padding({ left: 4, right: 4 })
          .borderRadius(12).backgroundColor(AppTheme.surfaceTint)
      }
      Divider().width('100%').height(1).color(AppTheme.divider)
    }.width('100%')
  }

  @Builder
  private BookSourceExplorePanel(source: BookSource) {
    Column({ space: 10 }) {
      Text(`“${source.bookSourceName}”发现分类`).width('100%').fontSize(16)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Start }) {
        ForEach(this.sourceExploreCategories, (category: BookSourceExploreCategory) => {
          Button(category.title).fontSize(12).margin({ right: 8, bottom: 8 })
            .enabled(!this.sourceSearchLoading).backgroundColor(AppTheme.surface)
            .fontColor(AppTheme.primary).onClick(() => this.ExploreBookSource(source, category))
        }, (category: BookSourceExploreCategory) => category.title + '|' + category.url)
      }.width('100%')
      Button('收起').width('100%').backgroundColor(AppTheme.surface).fontColor(AppTheme.textSecondary)
        .onClick(() => this.CloseBookSourceExplore())
    }.width('100%').padding(16).borderRadius(16).backgroundColor(AppTheme.surfaceTint)
  }

  @Builder
  private BookSourceCredentialPanel(source: BookSource) {
    Column({ space: 12 }) {
      Text(`“${source.bookSourceName}”安全凭据`).width('100%').fontSize(16)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Text('凭据只发送给与书源地址相同的HTTPS origin，不写入书源文件或普通设置。')
        .width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
      Row({ space: 8 }) {
        this.BookSourceCredentialTypeButton('Authorization')
        this.BookSourceCredentialTypeButton('Cookie')
        this.BookSourceCredentialTypeButton('X-API-Key')
      }.width('100%')
      AppGlassTextInput({ value: this.sourceCredentialValue,
        placeholder: this.sourceCredentialHeader === 'Authorization' ? '例如：Bearer token' : '输入敏感凭据值',
        inputType: InputType.Password, available: !this.sourceCredentialBusy,
        valueChanged: (value: string) => {
          this.sourceCredentialValue = value;
          this.sourceCredentialStatus = '';
        } })
      Row({ space: 10 }) {
        Button('取消').layoutWeight(1).backgroundColor(AppTheme.surfaceTint).fontColor(AppTheme.textPrimary)
          .onClick(() => this.CloseBookSourceCredential())
        Button('清除').layoutWeight(1).backgroundColor(AppTheme.surfaceTint).fontColor(AppTheme.danger)
          .enabled(!this.sourceCredentialBusy)
          .onClick(() => this.ClearBookSourceCredential(source.bookSourceUrl))
        Button(this.sourceCredentialBusy ? '处理中…' : '安全保存').layoutWeight(1)
          .enabled(!this.sourceCredentialBusy).backgroundColor(AppTheme.primary).fontColor(Color.White)
          .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
          .borderRadius(AppTheme.pillRadius)
          .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
          .onClick(() => this.SaveBookSourceCredential(source.bookSourceUrl))
      }.width('100%')
      if (this.sourceCredentialStatus.length > 0) {
        Text(this.sourceCredentialStatus).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
      }
    }.width('100%').padding(16).borderRadius(16).backgroundColor(AppTheme.surfaceTint)
  }

  @Builder
  private BookSourceCredentialTypeButton(name: string) {
    Button(name).layoutWeight(1).fontSize(11)
      .enabled(!this.sourceCredentialBusy)
      .fontColor(this.sourceCredentialHeader === name ? AppTheme.primary : AppTheme.textSecondary)
      .backgroundColor(this.sourceCredentialHeader === name ? AppTheme.accentSoft : AppTheme.surface)
      .border({ width: 1, color: this.sourceCredentialHeader === name ? AppTheme.primary : AppTheme.divider,
        radius: AppTheme.pillRadius }).borderRadius(AppTheme.pillRadius)
      .onClick(() => this.sourceCredentialHeader = name)
  }

  @Builder
  private BookSourceManageFilter() {
    Row({ space: 10 }) {
      Row({ space: 8 }) {
        SymbolGlyph($r('sys.symbol.magnifyingglass')).fontSize(18).fontColor([AppTheme.textSecondary])
      TextInput({ text: this.sourceManageText, placeholder: '搜索书源名称、分组或地址' })
          .layoutWeight(1).height(46).padding(0).fontSize(14).fontFamily('HarmonyOS Sans')
          .placeholderFont({ size: 14, weight: FontWeight.Normal, family: 'HarmonyOS Sans' })
          .backgroundColor(Color.Transparent)
          .onChange((value: string) => {
            this.sourceManageText = value;
            this.sourceManageVisibleCount = this.sourceManageRenderBatchSize;
            this.sourceManageFilterVisible = false;
            this.CancelDeleteBookSource();
          })
        if (this.sourceManageText.length > 0) {
          Button() {
            SymbolGlyph($r('sys.symbol.xmark')).fontSize(13).fontColor([AppTheme.textSecondary])
          }.width(34).height(34).padding(0).borderRadius(17).backgroundColor(Color.Transparent)
            .accessibilityText('清除书源搜索').onClick(() => {
              this.sourceManageText = '';
              this.sourceManageVisibleCount = this.sourceManageRenderBatchSize;
            })
        }
      }.layoutWeight(1).height(50).padding({ left: 14, right: 6 })
        .backgroundColor(AppTheme.surface)
        .border({ width: 1, color: AppTheme.divider, radius: 15 })
      Button() {
        Row({ space: 7 }) {
          Text(this.BookSourceManageFilterLabel()).fontSize(13).fontColor(AppTheme.textPrimary)
            .fontFamily('HarmonyOS Sans')
          SymbolGlyph($r('sys.symbol.chevron_down')).fontSize(12).fontColor([AppTheme.textSecondary])
        }
      }.width(92).height(50).padding(0).borderRadius(15).backgroundColor(AppTheme.surface)
        .border({ width: 1, color: AppTheme.divider, radius: 15 })
        .accessibilityText('切换书源筛选')
        .accessibilityDescription(`当前${this.BookSourceManageFilterLabel()}，点击打开筛选菜单`)
        .onClick(() => {
          this.sourceManageFilterVisible = !this.sourceManageFilterVisible;
        })
    }.width('100%')
  }

  @Builder
  private BookSourceManageFilterMenu() {
    Row() {
      Column({ space: 4 }) {
        this.BookSourceManageFilterMenuItem('全部', 'all')
        this.BookSourceManageFilterMenuItem('已启用', 'enabled')
        this.BookSourceManageFilterMenuItem('已停用', 'disabled')
      }.width(164).padding(8).borderRadius(16).backgroundColor(AppTheme.surface)
        .border({ width: 1, color: AppTheme.divider, radius: 16 })
    }.width('100%').justifyContent(FlexAlign.End)
  }

  @Builder
  private BookSourceManageFilterMenuItem(label: string, filter: string) {
    Button() {
      Row({ space: 8 }) {
        Text(label).layoutWeight(1).textAlign(TextAlign.Start).fontSize(13)
          .fontColor(this.sourceManageFilter === filter ? AppTheme.primary : AppTheme.textPrimary)
        if (this.sourceManageFilter === filter) {
          SymbolGlyph($r('sys.symbol.checkmark')).fontSize(14).fontColor([AppTheme.primary])
        }
      }.width('100%')
    }.width('100%').height(42).padding({ left: 13, right: 13 }).borderRadius(12)
      .backgroundColor(this.sourceManageFilter === filter ? AppTheme.accentSoft : Color.Transparent)
      .accessibilityText(`筛选${label}`)
      .onClick(() => {
        this.sourceManageFilter = filter;
        this.sourceManageVisibleCount = this.sourceManageRenderBatchSize;
        this.sourceManageFilterVisible = false;
        this.CancelDeleteBookSource();
      })
  }

  @Builder
  private BookSourceFilterButton(label: string, filter: string) {
    AppFilterChip({ label, selected: this.sourceManageFilter === filter, action: () => {
        this.sourceManageFilter = filter;
        this.sourceManageVisibleCount = this.sourceManageRenderBatchSize;
        this.CancelDeleteBookSource();
      } }).layoutWeight(1)
  }

  @Builder
  private BookSourceManageToolsSheet() {
    Column({ space: 0 }) {
      Row().width(44).height(5).margin({ top: 10, bottom: 12 })
        .borderRadius(3).backgroundColor(AppTheme.divider)
      Row({ space: 12 }) {
        Column() {
          SymbolGlyph($r('sys.symbol.book_open_fill')).fontSize(22).fontColor([AppTheme.primary])
        }.width(44).height(44).justifyContent(FlexAlign.Center).borderRadius(22)
          .backgroundColor(AppTheme.surfaceTint)
        Column({ space: 3 }) {
          Text('从网站导入书源').fontSize(17).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
          Text('支持标准 JSON 书源仓库和已适配站点').fontSize(12).fontColor(AppTheme.textSecondary)
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      }.width('100%').padding({ left: 18, right: 18, bottom: 14 })
      Divider().width('100%').height(1).color(AppTheme.divider)
      Row({ space: 12 }) {
        Column() {
          SymbolGlyph($r('sys.symbol.plus')).fontSize(22).fontColor([AppTheme.primary])
        }.width(44).height(44).justifyContent(FlexAlign.Center).borderRadius(22)
          .backgroundColor(AppTheme.surfaceTint)
        Column({ space: 3 }) {
          Text('导入 JSON').fontSize(17).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
          Text('从本地文件导入书源规则').fontSize(12).fontColor(AppTheme.textSecondary)
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(16).fontColor([AppTheme.textSecondary])
      }.width('100%').height(76).padding({ left: 18, right: 18 })
        .onClick(() => {
          this.sourceManageToolsVisible = false;
          this.ImportBookSources();
        })
      Divider().width('100%').height(1).color(AppTheme.divider)
      Row({ space: 12 }) {
        Column() {
          SymbolGlyph($r('sys.symbol.checkmark')).fontSize(22).fontColor([AppTheme.primary])
        }.width(44).height(44).justifyContent(FlexAlign.Center).borderRadius(22)
          .backgroundColor(AppTheme.surfaceTint)
        Column({ space: 3 }) {
          Text(this.sourceHealthLoading ? '正在检查' : '健康检查')
            .fontSize(17).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
          Text('检测所有书源的可用性').fontSize(12).fontColor(AppTheme.textSecondary)
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(16).fontColor([AppTheme.textSecondary])
      }.width('100%').height(76).padding({ left: 18, right: 18 }).enabled(!this.sourceHealthLoading)
        .onClick(() => {
          this.sourceManageToolsVisible = false;
          this.CheckBookSourceHealth();
        })
      Divider().width('100%').height(1).color(AppTheme.divider)
      Column({ space: 10 }) {
        Text('网站地址').width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
        Row({ space: 8 }) {
          SymbolGlyph($r('sys.symbol.square_stack_3d_fill')).fontSize(18).fontColor([AppTheme.textSecondary])
          TextInput({ placeholder: 'https://example.com', text: this.sourceDiscoveryUrl })
            .layoutWeight(1).height(48).padding(0).fontSize(14).backgroundColor(Color.Transparent)
            .onChange((value: string) => this.sourceDiscoveryUrl = value)
          if (this.sourceDiscoveryUrl.length > 0) {
            Button() {
              SymbolGlyph($r('sys.symbol.xmark')).fontSize(13).fontColor([Color.White])
            }.width(28).height(28).padding(0).borderRadius(14).backgroundColor(AppTheme.textSecondary)
              .accessibilityText('清除网站地址').onClick(() => this.sourceDiscoveryUrl = '')
          }
        }.width('100%').height(52).padding({ left: 14, right: 10 })
          .backgroundColor(AppTheme.glass).borderRadius(AppTheme.inputRadius)
          .border({ width: 1, color: AppTheme.divider, radius: AppTheme.inputRadius })
          .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
        if (this.sourceDiscoveryStatus.length > 0) {
          Text(this.sourceDiscoveryStatus).width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
        }
        Button(this.sourceDiscoveryBusy ? '正在导入…' : '导入并添加').width('100%').height(52)
          .fontSize(16).fontColor(Color.White).backgroundColor(AppTheme.primary)
          .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
          .borderRadius(AppTheme.pillRadius)
          .enabled(!this.sourceDiscoveryBusy).onClick(() => this.DiscoverBookSource())
      }.width('100%').padding({ left: 18, right: 18, top: 16, bottom: 24 })
    }.width('100%').borderRadius({ topLeft: 28, topRight: 28 })
      .backgroundColor(AppTheme.surface).onClick(() => {})
  }

  @Builder
  private BookSourceDeletePanel(source: BookSource) {
    Column({ space: 12 }) {
      Text(`确认删除“${this.sourceDeleteName}”？`).width('100%').fontSize(16)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Text(this.BookSourceDeleteImpact(source.bookSourceUrl)).width('100%').fontSize(13).fontColor(AppTheme.danger)
      Row({ space: 10 }) {
        Button('取消').layoutWeight(1).backgroundColor(AppTheme.surfaceTint).fontColor(AppTheme.textPrimary)
          .onClick(() => this.CancelDeleteBookSource())
        Button('确认删除书源').layoutWeight(1).fontColor(Color.White).backgroundColor(AppTheme.danger)
          .onClick(() => this.DeleteBookSource(source.bookSourceUrl))
      }.width('100%')
    }.width('100%').padding(16).borderRadius(16).backgroundColor(AppTheme.dangerSoft)
  }

  @Builder
  private NetworkLoadingRows(label: string) {
    Row({ space: 12 }) {
      LoadingProgress().width(28).height(28).color(AppTheme.primary)
      Column({ space: 4 }) {
        Text(label).fontSize(15).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textPrimary)
        Text('请稍候，已有内容不会被清除')
          .fontSize(12).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textSecondary)
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
    }.width('100%').padding({ top: 12, bottom: 12 })
      .border({ width: { bottom: 1 }, color: AppTheme.divider })
  }

  @Builder
  private RetryAction(label: string, action: () => void) {
    Button(label)
      .height(AppTheme.controlHeight).padding({ left: 18, right: 18 })
      .fontSize(14).fontFamily('HarmonyOS Sans').fontColor(AppTheme.primary)
      .backgroundColor(AppTheme.accentSoft)
      .borderRadius(AppTheme.pillRadius)
      .onClick(action)
  }

  @Builder
  private BookShelfCard(book: Book) {
    Row({ space: 12 }) {
      if (book.coverUrl.length > 0) {
        Image(book.coverUrl).width(50).height(74).objectFit(ImageFit.Cover).borderRadius(6)
      } else {
        Image($r('app.media.reader_default_cover')).width(50).height(74)
          .objectFit(ImageFit.Cover).borderRadius(6)
      }
      Column({ space: 4 }) {
        Text(book.name).width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .fontSize(15).fontFamily('HarmonyOS Sans').fontWeight(FontWeight.Bold)
          .fontColor(AppTheme.textPrimary)
        Text(book.author.trim().length > 0 ? book.author : '未知作者')
          .width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .fontSize(11).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textSecondary)
        Text(this.BookShelfMetadataLine(book))
          .width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .fontSize(11).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textSecondary)
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      Text(`${book.progress}%`).fontSize(13).fontFamily('HarmonyOS Sans')
        .fontColor('#F29A16')
      SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(14).fontColor([AppTheme.textSecondary])
    }.width('100%').height(94).padding({ left: 0, right: 2, top: 10, bottom: 10 })
      .backgroundColor(Color.Transparent)
      .border({ width: { bottom: 1 }, color: AppTheme.divider })
      .alignItems(VerticalAlign.Center)
      .priorityGesture(
        GestureGroup(GestureMode.Exclusive,
          LongPressGesture({ repeat: false, duration: 500 })
            .onAction(() => this.OpenBookDetail(book)),
          TapGesture({ count: 1 }).onAction(() => this.ReadShelfBook(book))))
  }

  /** 从书架直接继续阅读，长按手势单独负责打开详情。 */
  private ReadShelfBook(book: Book): void {
    this.readerReturnToDetail = false;
    this.detailReturnToReader = false;
    this.OpenBook(book);
  }

  @Builder
  private BookShelfHeader() {
    Row({ space: 10 }) {
      Text(this.BookShelfTitle()).layoutWeight(1).fontSize(18).fontFamily('HarmonyOS Sans').fontWeight(FontWeight.Bold)
        .fontColor(AppTheme.textPrimary)
      Button() {
        Row({ space: 6 }) {
          SymbolGlyph($r(this.BookShelfHeaderSymbol())).fontSize(16).fontColor([AppTheme.primary])
          Text(this.BookShelfHeaderAction()).fontSize(12).fontFamily('HarmonyOS Sans').fontColor(AppTheme.primary)
        }
      }.height(38).padding({ left: 8, right: 8 })
        .backgroundColor(Color.Transparent)
        .accessibilityText(this.BookShelfHeaderAction())
        .onClick(() => this.RunBookShelfHeaderAction())
    }.width('100%')
  }

  @Builder
  private BookSourceManagementPage() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column({ space: 12 }) {
        Row() {
          this.BackButton(AppTheme.textPrimary, () => this.bookSourceManagementVisible = false)
          Column({ space: 2 }) {
            Text('书源管理').fontSize(23).fontFamily('HarmonyOS Sans')
              .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
            Text(`${this.EnabledBookSourceCount()} 个已启用 · ${this.bookSources.length} 个全部`)
              .fontSize(12).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textSecondary)
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Button() {
            SymbolGlyph($r('sys.symbol.plus')).fontSize(24).fontColor([AppTheme.primary])
          }.width(48).height(48).padding(0).borderRadius(24).backgroundColor(AppTheme.surfaceTint)
            .accessibilityText('添加或检查书源').onClick(() => {
              this.sourceManageFilterVisible = false;
              this.sourceManageToolsVisible = true;
            })
        }.width('100%')
        this.BookSourceManageFilter()
        if (this.sourceManageFilterVisible) {
          this.BookSourceManageFilterMenu()
        }
        Scroll() {
          Column({ space: 0 }) {
            this.BookSourceManagementPanel()
          }.width('100%').padding({ bottom: 24 }).alignItems(HorizontalAlign.Start)
        }.width('100%').layoutWeight(1).align(Alignment.TopStart)
          .edgeEffect(EdgeEffect.None).scrollBar(BarState.Off)
          .onReachEnd(() => this.LoadMoreManagedBookSources())
      }.width('100%').height('100%').padding({ left: 18, right: 18, top: 16 })
        .alignItems(HorizontalAlign.Start).backgroundColor(AppTheme.background)
      if (this.sourceManageToolsVisible) {
        Column().width('100%').height('100%').backgroundColor('#55000000')
          .onClick(() => this.sourceManageToolsVisible = false)
        this.BookSourceManageToolsSheet()
      }
    }.width('100%').height('100%').backgroundColor(AppTheme.background)
  }

  @Builder
  private BookShelfSearchField() {
    Row({ space: 8 }) {
      SymbolGlyph($r('sys.symbol.magnifyingglass')).fontSize(17).fontColor([AppTheme.textSecondary])
      TextInput({ text: this.shelfSearchText, placeholder: '搜索书名或作者' })
        .layoutWeight(1).height(46).padding(0).fontSize(14).fontFamily('HarmonyOS Sans')
        .placeholderFont({ size: 14, weight: FontWeight.Normal, family: 'HarmonyOS Sans' })
        .backgroundColor(Color.Transparent)
        .onChange((value: string) => this.shelfSearchText = value)
      if (this.shelfSearchText.length > 0) {
        Button() {
          SymbolGlyph($r('sys.symbol.xmark')).fontSize(14).fontColor([AppTheme.textSecondary])
        }.width(38).height(38).padding(0).borderRadius(19).backgroundColor(Color.Transparent)
          .accessibilityText('清除书架搜索').onClick(() => this.shelfSearchText = '')
      }
    }.width('100%').height(48).padding({ left: 14, right: 5 })
      .border({ width: 1, color: AppTheme.divider, radius: AppTheme.inputRadius })
      .borderRadius(AppTheme.inputRadius).backgroundColor(AppTheme.glass)
      .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
  }

  @Builder
  private BookDetailPage() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column({ space: 0 }) {
        Row() {
          this.BackButton(AppTheme.textPrimary, () => this.CloseBookDetail())
          Text('书籍详情').layoutWeight(1).textAlign(TextAlign.Center)
            .fontSize(20).fontFamily('HarmonyOS Sans').fontWeight(FontWeight.Bold)
            .fontColor(AppTheme.textPrimary)
          if (this.detailBook !== undefined) {
            Button() {
              SymbolGlyph($r('sys.symbol.ellipsis_circle')).fontSize(22).fontColor([AppTheme.textSecondary])
            }.width(48).height(48).padding(0).backgroundColor(Color.Transparent)
              .accessibilityText('书籍操作').accessibilityDescription('管理书架、缓存或图书文件')
              .onClick(() => this.detailRemoveConfirmVisible = true)
          } else {
            Blank().width(48).height(48)
          }
        }.width('100%').height(58).padding({ left: 18, right: 18 })
          .backgroundColor(AppTheme.background)

        Scroll() {
          Column({ space: 18 }) {
          Row({ space: 16 }) {
            if ((this.detailBook?.coverUrl ?? '').length > 0) {
              Image(this.detailBook?.coverUrl ?? '').width(92).height(138)
                .objectFit(ImageFit.Cover).borderRadius(8)
            } else {
              Image($r('app.media.reader_default_cover')).width(92).height(138)
                .objectFit(ImageFit.Cover).borderRadius(8)
            }
            Column({ space: 9 }) {
              Text(this.detailBook?.name ?? '').width('100%').maxLines(2)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .fontSize(20).fontFamily('HarmonyOS Sans').fontWeight(FontWeight.Bold)
                .fontColor(AppTheme.textPrimary)
              Text(this.detailBook?.author ?? '未知作者').width('100%')
                .fontSize(14).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textSecondary)
              Row({ space: 6 }) {
                Text(this.DetailOriginLabel()).fontSize(11).fontFamily('HarmonyOS Sans').fontColor(AppTheme.primary)
                  .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                  .backgroundColor(AppTheme.surfaceTint).borderRadius(10)
                Text(this.detailBook?.format.toUpperCase() ?? '').fontSize(11).fontFamily('HarmonyOS Sans')
                  .fontColor(AppTheme.textSecondary)
                  .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                  .backgroundColor(AppTheme.surface).borderRadius(10)
              }
              Text(this.detailLastChapter.length > 0 ? this.detailLastChapter :
                (this.DetailBookInShelf() ? `阅读进度 ${this.detailBook?.progress ?? 0}%` : '尚未加入书架'))
                .width('100%').maxLines(2).fontSize(12).fontColor(AppTheme.textSecondary)
                .fontFamily('HarmonyOS Sans')
            }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          }.width('100%').padding({ bottom: 18 })
            .border({ width: { bottom: 1 }, color: AppTheme.divider })

          if ((this.detailBook?.origin ?? '') === 'remote') {
            Column({ space: 12 }) {
              Row() {
                Text('文件信息').layoutWeight(1).fontSize(18).fontWeight(FontWeight.Bold)
                  .fontColor(AppTheme.textPrimary)
                Text(this.detailRemoteSize > 0 ? this.FormatMediaSize(this.detailRemoteSize) : '远程文件')
                  .fontSize(13).fontColor(AppTheme.textSecondary)
              }.width('100%')
              Row({ space: 8 }) {
                SymbolGlyph($r('sys.symbol.folder_fill')).fontSize(16).fontColor([AppTheme.primary])
                Text(this.detailSourceName.length > 0 ? this.detailSourceName : 'MyTools远程书库')
                  .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  .fontSize(14).fontColor(AppTheme.textPrimary)
                if (this.detailRemoteModified.length > 0) {
                  Text(this.FormatRemoteBookModified(this.detailRemoteModified)).fontSize(12)
                    .fontColor(AppTheme.textSecondary)
                }
              }.width('100%')
              if (this.detailRemoteTags.length > 0) {
                Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Start }) {
                  ForEach(this.detailRemoteTags, (tag: string) => {
                    Button(`#${tag}`).height(34).fontSize(12).fontColor(AppTheme.primary)
                      .padding({ left: 10, right: 10, top: 5, bottom: 5 })
                      .margin({ right: 8, bottom: 6 }).backgroundColor(AppTheme.surfaceTint).borderRadius(12)
                      .accessibilityText(`筛选远程图书标签：${tag}`)
                      .onClick(() => this.FilterRemoteBooksByTag(tag))
                  }, (tag: string) => tag)
                }.width('100%')
              }
            }.width('100%').padding({ top: 4, bottom: 18 })
              .border({ width: { bottom: 1 }, color: AppTheme.divider })
          }

          if ((this.detailBook?.origin ?? '') === 'source') {
            Column({ space: 14 }) {
              Row() {
                Text('当前书源').layoutWeight(1).fontSize(20).fontWeight(FontWeight.Bold)
                  .fontColor(AppTheme.textPrimary)
                Button(this.detailSourceCandidates.length > 1 ?
                  (this.detailShowAllSources ? '收起书源' : `查看全部书源 · ${this.detailSourceCandidates.length}`) :
                  (this.detailSourceLatencyMs > 0 ? `${this.detailSourceLatencyMs}ms` : '正在验证'))
                  .height(48).fontSize(13).fontColor(AppTheme.primary).backgroundColor(Color.Transparent)
                  .enabled(this.detailSourceCandidates.length > 1)
                  .onClick(() => {
                    if (this.detailSourceCandidates.length > 1) {
                      this.detailShowAllSources = !this.detailShowAllSources;
                    }
                  })
              }.width('100%')
              Row({ space: 12 }) {
                Text('层').width(44).height(44).textAlign(TextAlign.Center).fontSize(18)
                  .fontColor(Color.White).backgroundColor(AppTheme.primary).borderRadius(22)
                Column({ space: 4 }) {
                  Text(this.detailSourceName.length > 0 ? this.detailSourceName : '已导入书源')
                    .fontSize(16).fontColor(AppTheme.textPrimary)
                  Text(this.detailChapterCount > 0 ? `目录已验证 · ${this.detailChapterCount}章` :
                    '正在按书源规则验证目录')
                    .fontSize(12).fontColor(AppTheme.textSecondary)
                }.layoutWeight(1).alignItems(HorizontalAlign.Start)
                Text(this.detailSourceStatus).fontSize(13)
                  .fontColor(this.detailSourceStatus === '可用' ? AppTheme.success : '#D88916')
              }.width('100%')
              if (this.detailShowAllSources) {
                ForEach(this.detailSourceCandidates, (candidate: BookSourceSearchResult) => {
                  Button() {
                    Row({ space: 10 }) {
                      Text(candidate.sourceUrl === this.detailBook?.sourceId ? '●' : '○')
                        .fontSize(15).fontColor(candidate.sourceUrl === this.detailBook?.sourceId ?
                          AppTheme.primary : AppTheme.textSecondary)
                      Column({ space: 3 }) {
                        Text(candidate.sourceName).fontSize(15).fontColor(AppTheme.textPrimary)
                        Text(candidate.lastChapter.length > 0 ? candidate.lastChapter : '点击后验证目录')
                          .maxLines(1).fontSize(12).fontColor(AppTheme.textSecondary)
                      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
                      Text(candidate.sourceUrl === this.detailBook?.sourceId ? '当前' : '切换')
                        .fontSize(13).fontColor(AppTheme.primary)
                    }.width('100%')
                  }.width('100%').height(54).padding({ left: 0, right: 0 })
                    .backgroundColor(Color.Transparent)
                    .border({ width: { top: 1 }, color: AppTheme.divider })
                    .accessibilityText(`书源：${candidate.sourceName}`)
                    .accessibilityDescription(candidate.sourceUrl === this.detailBook?.sourceId ? '当前书源' : '切换到该书源')
                    .onClick(() => this.SwitchDetailSource(candidate))
                }, (candidate: BookSourceSearchResult) => `${candidate.sourceUrl}|${candidate.bookUrl}`)
              }
            }.width('100%').padding({ top: 2, bottom: 18 })
              .border({ width: { bottom: 1 }, color: AppTheme.divider })
          }

          if ((this.detailBook?.origin ?? '') === 'source' || (this.detailBook?.origin ?? '') === 'local') {
            Button() {
              Row({ space: 12 }) {
                SymbolGlyph($r('sys.symbol.cloud')).fontSize(20).fontColor([AppTheme.primary])
                Column({ space: 3 }) {
                  Text('上传到远程').width('100%').fontSize(16).fontWeight(FontWeight.Medium)
                    .fontColor(AppTheme.textPrimary)
                  Text(this.detailBook?.origin === 'source' ? '由后端完整下载书源章节并保存' :
                    '上传当前本地图书到MyTools远程书库')
                    .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
                }.layoutWeight(1).alignItems(HorizontalAlign.Start)
                if (this.ebookTransferBusy) {
                  LoadingProgress().width(22).height(22).color(AppTheme.primary)
                } else {
                  SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(18).fontColor([AppTheme.textSecondary])
                }
              }.width('100%')
            }.width('100%').height(64).padding({ left: 14, right: 14 })
              .enabled(!this.ebookTransferBusy && this.authenticated)
              .backgroundColor(AppTheme.surface).borderRadius(14)
              .accessibilityText('上传到远程书库')
              .onClick(() => this.UploadDetailBookToRemote())
            if (this.ebookTransferStatus.length > 0) {
              Text(this.ebookTransferStatus).width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
            }
          }

          Column({ space: 12 }) {
            Text('简介').width('100%').fontSize(18).fontFamily('HarmonyOS Sans')
              .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
            Text(this.detailIntro.length > 0 ? this.detailIntro : this.DetailFallbackIntro())
              .width('100%').fontSize(14).fontFamily('HarmonyOS Sans')
              .lineHeight(23).fontColor(AppTheme.textSecondary)
          }.width('100%').alignItems(HorizontalAlign.Start)

          Button() {
            Row({ space: 12 }) {
              SymbolGlyph($r('sys.symbol.doc_plaintext')).fontSize(22).fontColor([AppTheme.textPrimary])
              Text('目录').fontSize(17).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textPrimary)
              Blank().layoutWeight(1)
              Text(this.detailChapterCount > 0 ? `${this.detailChapterCount}章` :
                (this.detailLastChapter.length > 0 ? this.detailLastChapter : '打开后查看'))
                .maxLines(1).fontSize(13).fontColor(AppTheme.textSecondary)
              SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(22).fontColor([AppTheme.textSecondary])
            }.width('100%')
          }.width('100%').height(58).padding({ left: 14, right: 14 })
            .backgroundColor(AppTheme.surface).borderRadius(14)
            .accessibilityText('目录')
            .accessibilityDescription('打开当前图书并查看目录')
            .onClick(() => this.ReadDetailBook())

          Blank().height(92)
        }.width('100%').padding({ left: 24, right: 24, top: 14, bottom: 20 })
          .alignItems(HorizontalAlign.Start)
        }.width('100%').layoutWeight(1).align(Alignment.TopStart)
          .backgroundColor(AppTheme.background).scrollBar(BarState.Off)
      }.width('100%').height('100%').backgroundColor(AppTheme.background)

      Column({ space: 12 }) {
        if (this.detailRemoveConfirmVisible) {
          this.BookShelfRemovePanel()
        }
        Row({ space: 14 }) {
          Button(this.DetailBookInShelf() ? '书架中' : '试读')
            .layoutWeight(1).height(52).fontSize(16).fontFamily('HarmonyOS Sans')
            .fontColor(AppTheme.textPrimary)
            .backgroundColor(AppTheme.surfaceTint)
            .accessibilityDescription(this.DetailBookInShelf() ? '打开当前图书' : '不加入书架并打开试读')
            .onClick(() => this.ReadDetailBook())
          Button(this.DetailBookInShelf() ? '继续阅读' : '加入书架')
            .layoutWeight(2).height(52).fontSize(17).fontFamily('HarmonyOS Sans').fontColor(Color.White)
            .backgroundColor(AppTheme.primary)
            .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
            .borderRadius(AppTheme.pillRadius)
            .accessibilityDescription(this.DetailBookInShelf() ? '从上次位置继续阅读' : '加入书架并开始阅读')
            .onClick(() => {
              if (this.DetailBookInShelf()) this.ReadDetailBook();
              else this.AddDetailBookToShelf();
            })
        }.width('100%')
      }.width('100%').padding({ left: 24, right: 24, top: 12, bottom: 16 })
        .backgroundColor(AppTheme.surface)
    }.width('100%').height('100%')
  }

  @Builder
  private BookShelfRemovePanel() {
    Column({ space: 10 }) {
      Text(`管理《${this.detailBook?.name ?? ''}》`).width('100%').fontSize(16)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Text(this.detailBook?.origin === 'source' ?
        '可清除已下载的目录和章节缓存，原网络内容不受影响。' :
        '移出书架不会删除文件；删除文件属于不可恢复操作。')
        .width('100%').fontSize(12).fontColor(AppTheme.textSecondary)
      if (this.detailOperationStatus.length > 0) {
        Text(this.detailOperationStatus).width('100%').fontSize(12)
          .fontColor(this.detailOperationStatus.startsWith('已') ? AppTheme.success : AppTheme.danger)
      }
      Row({ space: 8 }) {
        Button('取消').layoutWeight(1).height(44).fontSize(13).fontFamily('HarmonyOS Sans')
          .backgroundColor(AppTheme.surfaceTint)
          .fontColor(AppTheme.textPrimary).enabled(!this.detailOperationBusy)
          .onClick(() => this.detailRemoveConfirmVisible = false)
        if (this.DetailBookInShelf()) {
          Button('仅移出书架').layoutWeight(1).height(44).fontSize(13).fontFamily('HarmonyOS Sans')
            .fontColor(AppTheme.danger).backgroundColor(AppTheme.dangerSoft).enabled(!this.detailOperationBusy)
            .onClick(() => this.RemoveDetailBookFromShelf(false))
        }
      }.width('100%')
      if (this.detailBook?.origin === 'source') {
        Button(this.detailOperationBusy ? '正在清除缓存…' : '清除离线缓存')
          .width('100%').height(44).fontSize(13).fontFamily('HarmonyOS Sans')
          .fontColor(AppTheme.primary).backgroundColor(AppTheme.surfaceTint).enabled(!this.detailOperationBusy)
          .accessibilityDescription('清除本书已下载的目录和章节缓存')
          .onClick(() => this.ClearDetailSourceCache())
      } else {
        Button(this.detailBook?.origin === 'remote' ? '删除远程文件并移出书架' : '删除本地文件并移出书架')
          .width('100%').height(44).fontSize(13).fontFamily('HarmonyOS Sans')
          .fontColor(Color.White).backgroundColor(AppTheme.danger).enabled(!this.detailOperationBusy)
          .accessibilityDescription('永久删除图书文件并从当前书架移除')
          .onClick(() => this.DeleteDetailBookFile())
      }
      if (this.DetailBookInShelf()) {
        Button('移出并清除阅读记录').width('100%').height(44).fontSize(13).fontFamily('HarmonyOS Sans')
          .fontColor(AppTheme.danger).backgroundColor(AppTheme.dangerSoft).enabled(!this.detailOperationBusy)
          .accessibilityDescription('移出书架并清除本书阅读进度、书签、批注和统计')
          .onClick(() => this.RemoveDetailBookFromShelf(true))
      }
    }.width('100%').padding(14).borderRadius(16).backgroundColor(AppTheme.dangerSoft)
  }

  @Builder
  private ReaderPage() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column() {
        if (this.readerStatus.length > 0) {
          Column({ space: 16 }) {
            Text(this.readerStatus).width('100%').fontSize(15).fontColor(this.ReaderSecondaryColor())
              .textAlign(TextAlign.Start)
            if (this.readerContent.length === 0) {
              Button('返回书架').fontColor(AppTheme.primary).backgroundColor(AppTheme.accentSoft)
                .borderRadius(AppTheme.pillRadius).onClick(() => this.CloseReader())
            }
          }
          .width('100%').layoutWeight(1).justifyContent(FlexAlign.Start)
          .padding({ top: 24, left: 20, right: 20 })
        } else if (this.pdfMode) {
          PdfView({
            controller: this.pdfController,
            pageLayout: pdfService.PageLayout.LAYOUT_SINGLE,
            isContinuous: true,
            showScroll: true,
            pageFit: pdfService.PageFit.FIT_WIDTH
          }).width('100%').layoutWeight(1)
        } else if (this.comicImageUris.length > 0) {
          this.ComicReaderContent()
        } else if (this.ReaderUsesContinuousText()) {
          Scroll(this.readerScroller) {
            if (this.ReaderUsesWindowedContinuousText()) {
              this.ReaderContinuousWindowContent()
            } else {
              this.ReaderChapterContent()
            }
          }
          .id(`reader-scroll-${this.readerScrollEpoch}`)
          .layoutWeight(1)
          .scrollBar(BarState.Off)
          // 保留边界外滑动帧，用户继续上拉或下拉时可触发相邻章节加载。
          .edgeEffect(EdgeEffect.Spring)
          .onScrollFrameBegin((offset: number) => {
            if (Math.abs(offset) > 0.5) {
              this.readerLastScrollAt = Date.now();
              this.readerScrollDirection = offset > 0 ? 1 : -1;
              // 真机上边界回调可能早于本帧方向记录，已到边界时由当前用户滑动直接触发章节衔接。
              try {
                if ((offset > 0 && this.readerScroller.isAtEnd()) ||
                  (offset < 0 && this.readerScroller.currentOffset().yOffset <= 0)) {
                  this.ContinueReaderScroll(this.readerScrollDirection);
                }
              } catch (_) {
              }
            }
            this.UpdateReaderProgress(offset);
            return { offsetRemain: offset };
          })
          .onReachStart(() => this.ContinueReaderScroll(-1))
          .onReachEnd(() => this.ContinueReaderScroll(1))
        } else {
          this.ReaderPagedContent()
        }
        if (this.readerStatus.length === 0) {
          this.ReaderFooter()
        }
      }
      .width('100%').height('100%')
      .backgroundColor(this.ReaderBackgroundColor())
      .opacity(this.readerSettings.brightness)
      // 限制点击允许的移动距离，滚动手势不再误触发阅读工具栏。
      .parallelGesture(TapGesture({ count: 1, distanceThreshold: 6 }).onAction(() => {
        // 滑动结束后的合成点击不会打开控制栏，独立轻点仍保持原有交互。
        if (!this.readerSettingsVisible && Date.now() - this.readerLastScrollAt > 220) {
          this.readerControlsVisible = !this.readerControlsVisible;
        }
      }))

      if (this.readerControlsVisible) this.ReaderControls()
      if (this.readerSelectedText.length > 0 && !this.readerControlsVisible &&
        !this.readerSettingsVisible && !this.readerAnnotationVisible) {
        this.ReaderSelectionAction()
      }
      if (this.readerSettingsVisible) {
        Row().width('100%').height('100%').backgroundColor('#52000000')
          .onClick(() => {
            this.readerSettingsVisible = false;
            this.PersistReaderState();
          })
        this.ReaderSettingsPanel()
      }
      if (this.readerCatalogVisible) {
        Row().width('100%').height('100%').backgroundColor('#52000000')
          .onClick(() => this.readerCatalogVisible = false)
        this.ReaderCatalogPanel()
      }
      if (this.readerShelfPromptVisible) this.ReaderShelfPrompt()
      if (this.readerAnnotationVisible) this.ReaderAnnotationPanel()
    }
    .width('100%').height('100%')
    .onAreaChange((_oldValue: Area, newValue: Area) => {
      this.UpdateReaderViewport(newValue.width as Object, newValue.height as Object);
    })
  }

  @Builder
  private ReaderChapterContent() {
    Column() {
      this.ReaderChapterHeader(this.readerChapterIndex, 24)
      if ((this.readerChapters[this.readerChapterIndex]?.blocks?.length ?? 0) > 0) {
        this.ReaderBlocksContent(this.readerChapters[this.readerChapterIndex].blocks ?? [])
      } else {
        this.ReaderPlainTextContent(this.readerContent, this.CurrentReaderLocationTitle())
      }
    }.width('100%').padding({ bottom: 24 })
      .alignItems(HorizontalAlign.Start).justifyContent(FlexAlign.Start)
  }

  @Builder
  private ReaderContinuousWindowContent() {
    Column() {
      ForEach(this.ReaderContinuousWindowIndexes(), (index: number) => {
        Column() {
          if ((this.readerChapters[index]?.blocks?.length ?? 0) > 0 ||
            (this.readerChapters[index]?.content.trim().length ?? 0) > 0) {
            this.ReaderChapterHeader(index, index === this.readerContinuousStartIndex ? 24 : 34)
            if ((this.readerChapters[index]?.blocks?.length ?? 0) > 0) {
              this.ReaderBlocksContent(this.readerChapters[index].blocks ?? [], this.readerChapters[index].title)
            } else {
              this.ReaderPlainTextContent(this.readerChapters[index]?.content ?? '',
                this.readerChapters[index]?.title ?? '正文')
            }
          }
        }
        .width('100%')
        .alignItems(HorizontalAlign.Start)
        .justifyContent(FlexAlign.Start)
        .onVisibleAreaChange([0.001], (visible: boolean, ratio: number) => {
          if (visible && ratio > 0.001) this.UpdateContinuousReaderChapter(index);
        })
      }, (index: number) => `${this.currentBook?.id ?? 'reader'}:${index}`)
    }.width('100%').padding({ bottom: 24 })
      .alignItems(HorizontalAlign.Start).justifyContent(FlexAlign.Start)
  }

  @Builder
  private ReaderChapterHeader(index: number, topPadding: number) {
    Column({ space: 7 }) {
      Text(`第${index + 1}/${Math.max(1, this.readerChapters.length)}节`)
        .width('100%').fontSize(11).fontColor(this.ReaderSecondaryColor())
        .fontFamily(this.ReaderFontName()).letterSpacing(0.4)
      Text(this.readerChapters[index]?.title ?? '正文')
        .width('100%')
        .fontSize(this.ReaderScaledFontSize(this.readerSettings.fontSize + 3))
        .fontFamily(this.ReaderFontName())
        .fontWeight(FontWeight.Bold)
        .fontColor(this.ReaderTextColor())
        .lineHeight(this.ReaderScaledFontSize((this.readerSettings.fontSize + 3) * 1.32))
    }
    .width('100%')
    .padding({
      left: this.ReaderContentHorizontalPadding(),
      right: this.ReaderContentHorizontalPadding(),
      top: topPadding,
      bottom: 18
    })
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  private ReaderPlainTextContent(content: string, chapterTitle: string) {
    Column() {
      ForEach(this.ReaderPlainParagraphs(content, chapterTitle), (paragraph: string, index: number) => {
        if (this.ReaderIsSceneBreak(paragraph)) {
          Text(paragraph).width('100%').textAlign(TextAlign.Center)
            .fontSize(this.ReaderScaledFontSize(Math.max(13, this.readerSettings.fontSize - 2)))
            .fontColor(this.ReaderSecondaryColor()).fontFamily(this.ReaderFontName())
            .letterSpacing(3).padding({ top: 8, bottom: this.readerSettings.paragraphSpacing + 8 })
        } else {
          Text(this.ReaderIndentedParagraph(paragraph)).width('100%')
            .fontSize(this.ReaderScaledFontSize(this.readerSettings.fontSize))
            .fontColor(this.ReaderTextColor())
            .fontFamily(this.ReaderFontName())
            .fontWeight(FontWeight.Normal)
            .lineHeight(this.ReaderScaledFontSize(this.readerSettings.fontSize * this.readerSettings.lineHeight))
            .textAlign(TextAlign.Start)
            .copyOption(CopyOptions.LocalDevice)
            .onTextSelectionChange((start: number, end: number) =>
              this.UpdateReaderSelection(this.ReaderIndentedParagraph(paragraph), start, end))
            .padding({ top: 0, bottom: this.readerSettings.paragraphSpacing })
        }
      }, (_paragraph: string, index: number) => `${chapterTitle}:${index}`)
    }
    .width('100%')
    .padding({
      left: this.ReaderContentHorizontalPadding(),
      right: this.ReaderContentHorizontalPadding(),
      bottom: 26
    })
    .alignItems(HorizontalAlign.Start)
    .justifyContent(FlexAlign.Start)
  }

  @Builder
  private ReaderBlocksContent(blocks: ReaderBlock[], chapterTitle?: string) {
      Column({ space: this.readerSettings.paragraphSpacing }) {
        ForEach(blocks, (block: ReaderBlock, index: number) => {
          if (index === 0 && block.kind === 'heading' &&
            block.text.trim() === (chapterTitle ?? this.CurrentReaderLocationTitle()).trim()) {
            // 章节标题已经由正文容器统一展示，结构化首块不再重复渲染。
          } else if (block.kind === 'image') {
            Column({ space: 6 }) {
              Image(block.uri).width('100%').height(420).objectFit(ImageFit.Contain)
              if (block.text.length > 0) {
                Text(block.text).width('100%').textAlign(TextAlign.Center).fontSize(12)
                  .fontColor(this.ReaderSecondaryColor())
              }
            }.width('100%')
          } else if (block.kind === 'heading') {
            this.ReaderHeadingBlock(block.text, block.level ?? 2)
          } else if (block.kind === 'quote') {
            this.ReaderQuoteBlock(block.text, false)
          } else if (block.kind === 'footnote') {
            this.ReaderQuoteBlock(block.text, true)
          } else if (block.kind === 'code') {
            this.ReaderCodeBlock(block.text)
          } else if (block.kind === 'math') {
            this.ReaderMathBlock(block.text)
          } else if (block.kind === 'diagram') {
            this.ReaderDiagramBlock(block.text)
          } else if (block.kind === 'list') {
            Text(block.text).width('100%').fontSize(this.ReaderScaledFontSize(this.readerSettings.fontSize))
              .fontFamily(this.ReaderFontName())
              .fontColor(this.ReaderTextColor())
              .lineHeight(this.ReaderScaledFontSize(this.readerSettings.fontSize * this.readerSettings.lineHeight))
              .padding({ left: 12, right: 6, top: 3, bottom: 3 })
          } else {
            this.ReaderTextBlock(block.text)
          }
        }, (block: ReaderBlock, index: number) => `${index}:${block.kind}:${block.uri}`)
      }.width('100%').padding({
        left: this.ReaderContentHorizontalPadding(),
        right: this.ReaderContentHorizontalPadding(),
        top: 12,
        bottom: 24
      }).alignItems(HorizontalAlign.Start).justifyContent(FlexAlign.Start)
  }

  @Builder
  private ReaderPagedContent() {
    // 当前页由统一手势驱动，末页继续滑动时直接进入相邻章节。
    Stack() {
      Scroll() {
        this.ReaderBlocksContent(this.readerTextPages[this.readerTextPageIndex] ?? [])
      }.width('100%').height('100%').align(Alignment.TopStart).scrollBar(BarState.Off)
        // 手势挂在实际接收触摸的Scroll上，保留单页纵向滚动并稳定识别横向翻页。
        .parallelGesture(PanGesture({ fingers: 1, direction: PanDirection.Horizontal, distance: 12 })
          .onActionEnd((event: GestureEvent) => this.TurnReaderTextPageBySwipe(event.offsetX)))
    }.width('100%').layoutWeight(1)
      .animation({ duration: this.readerSettings.pageTurnMode === 'cover' ? 160 : 260, curve: Curve.EaseOut })
  }

  @Builder
  private ReaderTextBlock(content: string) {
    Text(this.ReaderIndentedParagraph(this.ReaderVisibleText(content))).width('100%')
      .fontSize(this.ReaderScaledFontSize(this.readerSettings.fontSize))
      .fontColor(this.ReaderTextColor())
      .fontFamily(this.ReaderFontName())
      .lineHeight(this.ReaderScaledFontSize(this.readerSettings.fontSize * this.readerSettings.lineHeight))
      .textAlign(TextAlign.Start)
      .copyOption(CopyOptions.LocalDevice)
      .onTextSelectionChange((start: number, end: number) =>
        this.UpdateReaderSelection(this.ReaderIndentedParagraph(this.ReaderVisibleText(content)), start, end))
      .padding({ top: 4, bottom: 4 })
  }

  @Builder
  private ReaderFooter() {
    Row() {
      if (!this.pdfMode && this.comicImageUris.length === 0 && !this.ReaderUsesContinuousText()) {
        Button() {
          SymbolGlyph($r('sys.symbol.chevron_left')).fontSize(14).fontColor([this.ReaderSecondaryColor()])
        }.width(44).height(34).padding(0).backgroundColor(Color.Transparent)
          .accessibilityText('上一页').onClick(() => this.ChangeReaderPageFromFooter(-1))
      }
      Text(this.ReaderSingleLineStatus())
        .layoutWeight(1).maxLines(1).textAlign(TextAlign.Center)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .fontSize(10).fontColor(this.ReaderSecondaryColor())
      if (!this.pdfMode && this.comicImageUris.length === 0 && !this.ReaderUsesContinuousText()) {
        Button() {
          SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(14).fontColor([this.ReaderSecondaryColor()])
        }.width(44).height(34).padding(0).backgroundColor(Color.Transparent)
          .accessibilityText('下一页').onClick(() => this.ChangeReaderPageFromFooter(1))
      }
    }.width('100%').height(34).padding({ left: 4, right: 4 })
      .border({ width: { top: 1 }, color: this.ReaderDividerColor() })
  }

  @Builder
  private ReaderSelectionAction() {
    Button() {
      SymbolGlyph($r('sys.symbol.ellipsis_message')).fontSize(21).fontColor([this.ReaderTextColor()])
    }.width(48).height(48).padding(0).align(Alignment.End).margin({ right: 18 })
      .backgroundColor(this.ReaderControlSurfaceColor()).borderRadius(24)
      .border({ width: 1, color: this.ReaderDividerColor() })
      .shadow({ radius: 10, color: '#26000000', offsetY: 3 })
      .accessibilityText('为选中文字添加批注')
      .onClick(() => {
        this.readerAnnotationInput = '';
        this.readerAnnotationVisible = true;
      })
  }

  @Builder
  private ReaderHeadingBlock(content: string, level: number) {
    Text(content).width('100%')
      .fontSize(this.ReaderScaledFontSize(this.readerSettings.fontSize + Math.max(3, 10 - level)))
      .fontFamily(this.ReaderFontName())
      .fontWeight(FontWeight.Bold).fontColor(this.ReaderTextColor())
      .copyOption(CopyOptions.LocalDevice)
      .onTextSelectionChange((start: number, end: number) => this.UpdateReaderSelection(content, start, end))
      .lineHeight(this.ReaderScaledFontSize((this.readerSettings.fontSize + Math.max(3, 10 - level)) * 1.38))
      .padding({ top: level <= 2 ? 20 : 12, bottom: 8 })
  }

  @Builder
  private ReaderShelfPrompt() {
    Stack({ alignContent: Alignment.Center }) {
      Row().width('100%').height('100%').backgroundColor('#66000000')
      Column({ space: 14 }) {
        Text('加入书架？').width('100%').fontSize(20).fontWeight(FontWeight.Bold)
          .fontColor(AppTheme.textPrimary)
        Text(`要将《${this.currentBook?.name ?? ''}》加入书架，便于下次继续阅读吗？`)
          .width('100%').fontSize(14).lineHeight(21).fontColor(AppTheme.textSecondary)
        Row({ space: 10 }) {
          Button('暂不加入').layoutWeight(1).height(46).fontSize(14)
            .fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surfaceTint)
            .onClick(() => this.CloseReader(true))
          Button('加入书架').layoutWeight(1).height(46).fontSize(14)
            .fontColor(Color.White).backgroundColor(AppTheme.primary)
            .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
            .borderRadius(AppTheme.pillRadius)
            .onClick(() => {
              this.AddCurrentReaderBookToShelf();
              this.CloseReader(true);
            })
        }.width('100%')
      }.width('84%').padding(20).borderRadius(22).backgroundColor(AppTheme.surface)
        .shadow({ radius: 22, color: '#30000000', offsetY: 8 })
    }.width('100%').height('100%')
  }

  @Builder
  private ReaderQuoteBlock(content: string, footnote: boolean) {
    Column({ space: 6 }) {
      if (footnote) Text('注释').fontSize(12).fontWeight(FontWeight.Bold).fontColor(AppTheme.primary)
      Text(content).width('100%').fontSize(this.ReaderScaledFontSize(
        footnote ? this.readerSettings.fontSize - 2 : this.readerSettings.fontSize))
        .fontFamily(this.ReaderFontName())
        .fontColor(this.ReaderSecondaryColor())
        .copyOption(CopyOptions.LocalDevice)
        .onTextSelectionChange((start: number, end: number) => this.UpdateReaderSelection(content, start, end))
        .lineHeight(this.ReaderScaledFontSize(
          (footnote ? this.readerSettings.fontSize - 2 : this.readerSettings.fontSize) *
          this.readerSettings.lineHeight))
    }.width('100%').alignItems(HorizontalAlign.Start).padding(12)
      .backgroundColor(this.readerSettings.theme === 'night' ? '#252938' : '#F2E5C5').borderRadius(6)
      .border({ width: { left: 2 }, color: this.readerSettings.theme === 'night' ? '#596071' : '#D5B36B' })
  }

  @Builder
  private ReaderCodeBlock(content: string) {
    Scroll() {
      Text(content).fontFamily('monospace')
        .fontSize(this.ReaderScaledFontSize(Math.max(13, this.readerSettings.fontSize - 2)))
        .fontColor(this.ReaderTextColor())
        .lineHeight(this.ReaderScaledFontSize(Math.max(13, this.readerSettings.fontSize - 2) * 1.55))
        .copyOption(CopyOptions.LocalDevice)
        .onTextSelectionChange((start: number, end: number) => this.UpdateReaderSelection(content, start, end))
        .padding(14)
    }.width('100%').scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off)
      .backgroundColor(this.readerSettings.theme === 'night' ? '#11141C' : '#EEF0F5').borderRadius(8)
  }

  @Builder
  private ReaderMathBlock(content: string) {
    Text(content).width('100%').textAlign(TextAlign.Center)
      .fontSize(this.ReaderScaledFontSize(this.readerSettings.fontSize + 1))
      .fontFamily(this.ReaderFontName())
      .fontColor(this.ReaderTextColor())
      .lineHeight(this.ReaderScaledFontSize((this.readerSettings.fontSize + 1) * 1.7))
      .copyOption(CopyOptions.LocalDevice)
      .onTextSelectionChange((start: number, end: number) => this.UpdateReaderSelection(content, start, end))
      .padding({ top: 12, bottom: 12 })
      .backgroundColor(this.readerSettings.theme === 'night' ? '#202431' : '#F7F7FA').borderRadius(8)
  }

  @Builder
  private ReaderDiagramBlock(content: string) {
    Column({ space: 6 }) {
      Text('图示').fontSize(12).fontWeight(FontWeight.Bold).fontColor(AppTheme.primary)
      Text(content).width('100%').textAlign(TextAlign.Center)
        .fontSize(this.ReaderScaledFontSize(this.readerSettings.fontSize - 1))
        .fontFamily(this.ReaderFontName())
        .fontColor(this.ReaderSecondaryColor())
        .lineHeight(this.ReaderScaledFontSize((this.readerSettings.fontSize - 1) * 1.6))
        .copyOption(CopyOptions.LocalDevice)
        .onTextSelectionChange((start: number, end: number) => this.UpdateReaderSelection(content, start, end))
    }.width('100%').padding(14).border({ width: 1, color: AppTheme.divider, radius: 8 })
  }

  @Builder
  private ComicReaderContent() {
    if (this.readerSettings.comicPageMode === 'long') {
      List({ space: 0 }) {
        ForEach(this.comicImageUris, (_uri: string, position: number) => {
          ListItem() {
            this.ComicLongPageImage(position)
          }
        }, (_uri: string, position: number) => `${position}`)
      }
      .width('100%').layoutWeight(1).scrollBar(BarState.Off)
      .onScrollIndex((_start: number, _end: number, center: number) => this.UpdateComicPosition(center))
    } else {
      Column() {
        Stack() {
          if (this.readerSettings.comicPageMode === 'double') {
            Row({ space: 2 }) {
              if (this.readerSettings.comicDirection === 'rtl' &&
                this.comicPageIndex + 1 < this.comicImageUris.length) {
                this.ComicPageImage(this.comicPageIndex + 1)
              }
              this.ComicPageImage(this.comicPageIndex)
              if (this.readerSettings.comicDirection === 'ltr' &&
                this.comicPageIndex + 1 < this.comicImageUris.length) {
                this.ComicPageImage(this.comicPageIndex + 1)
              }
            }.width('100%').height('100%')
          } else {
            this.ComicPageImage(this.comicPageIndex)
          }
          ForEach(this.ComicPreloadUris(), (uri: string) => {
            Image(uri).width(1).height(1).opacity(0.01)
          }, (uri: string) => uri)
        }.width('100%').layoutWeight(1)
      }
      .width('100%').layoutWeight(1)
    }
  }

  @Builder
  private ComicPageImage(position: number) {
    if (this.comicImageUris[this.ComicFileIndex(position)].length > 0) {
      Image(this.comicImageUris[this.ComicFileIndex(position)])
        .layoutWeight(1).height('100%').objectFit(this.ComicObjectFit())
        .scale({ x: this.readerSettings.comicScale, y: this.readerSettings.comicScale })
    } else {
      Column({ space: 10 }) {
        if (this.IsSourceComicPageFailed(position)) {
          Text('漫画页缓存失败').fontSize(13).fontColor(this.ReaderSecondaryColor())
          Button('重试').height(48).fontColor(AppTheme.primary).backgroundColor(AppTheme.accentSoft)
            .borderRadius(AppTheme.pillRadius).onClick(() => this.RetrySourceComicPage(position))
        } else {
          LoadingProgress().width(34).height(34).color(AppTheme.primary)
          Text('正在缓存漫画页').fontSize(13).fontColor(this.ReaderSecondaryColor())
        }
      }.layoutWeight(1).height('100%').justifyContent(FlexAlign.Center)
    }
  }

  @Builder
  private ComicLongPageImage(position: number) {
    if (this.comicImageUris[this.ComicFileIndex(position)].length > 0) {
      Image(this.comicImageUris[this.ComicFileIndex(position)])
        .width('100%').height(720).objectFit(this.ComicObjectFit())
    } else {
      Column({ space: 10 }) {
        if (this.IsSourceComicPageFailed(position)) {
          Text('漫画页缓存失败').fontSize(13).fontColor(this.ReaderSecondaryColor())
          Button('重试').height(48).fontColor(AppTheme.primary).backgroundColor(AppTheme.accentSoft)
            .borderRadius(AppTheme.pillRadius).onClick(() => this.RetrySourceComicPage(position))
        } else {
          LoadingProgress().width(34).height(34).color(AppTheme.primary)
          Text('正在缓存漫画页').fontSize(13).fontColor(this.ReaderSecondaryColor())
        }
      }.width('100%').height(720).justifyContent(FlexAlign.Center)
    }
  }

  private ComicFileIndex(position: number): number {
    const safe = Math.max(0, Math.min(this.comicImageUris.length - 1, position));
    return this.readerSettings.comicDirection === 'rtl' ? this.comicImageUris.length - 1 - safe : safe;
  }

  private ComicObjectFit(): ImageFit {
    return this.readerSettings.comicFitMode === 'cover' ? ImageFit.Cover : ImageFit.Contain;
  }

  private ComicPreloadUris(): string[] {
    const uris: string[] = [];
    for (let offset = 1; offset <= this.readerSettings.comicPreload; offset++) {
      const position = this.comicPageIndex + offset;
      if (position >= this.comicImageUris.length) break;
      const uri = this.comicImageUris[this.ComicFileIndex(position)];
      if (uri.length > 0) uris.push(uri);
    }
    return uris;
  }

  private UpdateComicPosition(position: number): void {
    if (position < 0 || position >= this.comicImageUris.length) return;
    this.comicPageIndex = position;
    if (this.currentBook !== undefined) {
      this.currentBook.progress = Math.round((position + 1) * 100 / this.comicImageUris.length);
    }
    this.EnsureSourceComicWindow(position).catch((error: Error) => {
      this.readerStatus = error.message;
    });
    this.ScheduleReaderProgressSave();
  }

  private UpdateComicSettings(kind: string, value: string): void {
    let direction = this.readerSettings.comicDirection;
    let pageMode = this.readerSettings.comicPageMode;
    let fitMode = this.readerSettings.comicFitMode;
    if (kind === 'direction') {
      const currentFile = this.ComicFileIndex(this.comicPageIndex);
      direction = value as ComicDirection;
      this.comicPageIndex = direction === 'rtl' ? this.comicImageUris.length - 1 - currentFile : currentFile;
    } else if (kind === 'page') {
      pageMode = value as ComicPageMode;
      if (pageMode === 'double') this.comicPageIndex -= this.comicPageIndex % 2;
    } else if (kind === 'fit') {
      fitMode = value as ComicFitMode;
    }
    this.readerSettings = {
      fontFamily: this.readerSettings.fontFamily,
      fontSize: this.readerSettings.fontSize,
      lineHeight: this.readerSettings.lineHeight,
      paragraphSpacing: this.readerSettings.paragraphSpacing,
      orientation: this.readerSettings.orientation,
      horizontalPadding: this.readerSettings.horizontalPadding,
      brightness: this.readerSettings.brightness,
      theme: this.readerSettings.theme,
      pageTurnMode: this.readerSettings.pageTurnMode,
      comicDirection: direction,
      comicPageMode: pageMode,
      comicFitMode: fitMode,
      comicScale: this.readerSettings.comicScale,
      comicPreload: this.readerSettings.comicPreload
    };
    this.EnsureSourceComicWindow(this.comicPageIndex).catch((error: Error) => {
      this.readerStatus = error.message;
    });
  }

  @Builder
  private ReaderControls() {
    Column() {
      Row({ space: 4 }) {
        this.BackButton(this.ReaderTextColor(), () => this.CloseReader())
        Text(this.CurrentReaderLocationTitle()).layoutWeight(1).maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .textAlign(TextAlign.Start).fontSize(16).fontWeight(FontWeight.Medium)
          .fontColor(this.ReaderTextColor())
        Button() {
          Column({ space: 1 }) {
            SymbolGlyph($r('sys.symbol.headphones')).fontSize(22)
              .fontColor([this.readerSpeechActive ? AppTheme.primary : this.ReaderTextColor()])
            Text(this.readerSpeechActive ? '停止' : '听书').fontSize(10)
              .fontColor(this.readerSpeechActive ? AppTheme.primary : this.ReaderSecondaryColor())
          }
        }.width(48).height(48).padding(0).backgroundColor(Color.Transparent)
          .accessibilityText(this.readerSpeechActive ? '停止朗读' : '开始朗读')
          .onClick(() => this.ToggleReaderSpeech())
        Button() {
          SymbolGlyph($r('sys.symbol.ellipsis_circle')).fontSize(23).fontColor([this.ReaderTextColor()])
        }.width(48).height(48).padding(0).backgroundColor(Color.Transparent)
          .accessibilityText('更多')
          .accessibilityDescription(this.readerMoreVisible ? '关闭更多操作' : '打开更多操作')
          .onClick(() => {
            this.readerMoreVisible = !this.readerMoreVisible;
            this.readerSearchVisible = false;
          })
      }
      .width('100%').height(56).padding({ left: 8, right: 10 })
      .backgroundColor(this.ReaderControlSurfaceColor())
      .border({ width: { bottom: 1 }, color: this.ReaderDividerColor() })

      if (this.readerSearchVisible) {
        TextInput({ text: this.readerSearchText, placeholder: '搜索本书正文' })
          .width('92%').height(46).margin({ top: 10 }).backgroundColor(this.ReaderControlSurfaceTintColor())
          .onChange((value: string) => this.readerSearchText = value)
          .onSubmit(() => this.SearchReaderContent())
        if (this.readerSearchBusy) {
          Text(this.readerSearchStatus).width('92%').fontSize(12).fontColor(this.ReaderSecondaryColor())
        } else if (this.readerSearchResults.length > 0) {
          Scroll() {
            Column() {
              ForEach(this.readerSearchResults, (hit: IndexedTextSearchHit) => {
                Column({ space: 3 }) {
                  Text(hit.chapterTitle).width('100%').fontSize(12).fontColor(AppTheme.primary)
                  Text(hit.excerpt).width('100%').maxLines(2).fontSize(13).fontColor(AppTheme.textPrimary)
                }.width('100%').padding(10).alignItems(HorizontalAlign.Start)
                  .backgroundColor(this.ReaderControlSurfaceColor()).onClick(() => this.OpenReaderSearchHit(hit))
              }, (hit: IndexedTextSearchHit) => `${hit.chapterIndex}:${hit.characterIndex}`)
            }.width('100%')
          }.width('92%').height(Math.min(220, this.readerSearchResults.length * 62))
        }
      }

      if (this.readerMoreVisible) {
        if (this.readerSelectedText.length > 0) {
          Row() {
            Text(`已选 ${this.readerSelectedText.length} 字 · DSH可读取`)
              .layoutWeight(1).fontSize(12).fontColor(this.ReaderSecondaryColor())
            Button('询问').width(56).height(48).fontSize(13).fontColor(AppTheme.primary)
              .backgroundColor(Color.Transparent).onClick(() => this.AskCopilotAboutSelection())
            Button('清除').width(56).height(48).fontSize(13).fontColor(this.ReaderTextColor())
              .backgroundColor(Color.Transparent).onClick(() => this.ClearReaderSelection())
          }.width('92%').height(48).margin({ top: 10 }).padding({ left: 12 })
            .backgroundColor(this.ReaderControlSurfaceTintColor()).borderRadius(12)
        }
        Row() {
          this.ReaderMoreButton('搜索正文', () => {
            this.readerSearchVisible = true;
            this.readerMoreVisible = false;
          })
          this.ReaderMoreButton('添加书签', () => this.AddCurrentReaderBookmark())
          this.ReaderMoreButton('阅读批注', () => {
            this.readerAnnotationInput = '';
            this.readerAnnotationVisible = true;
            this.readerControlsVisible = false;
            this.readerMoreVisible = false;
          })
          this.ReaderMoreButton('书籍详情', () => this.OpenCurrentReaderBookDetail())
        }.width('92%').height(52).margin({ top: 10 }).padding({ left: 6, right: 6 })
          .backgroundColor(this.ReaderControlSurfaceTintColor()).borderRadius(12)
      }

      Blank().layoutWeight(1).onClick(() => {
        this.readerControlsVisible = false;
        this.readerSearchVisible = false;
        this.readerMoreVisible = false;
      })
      Column({ space: 2 }) {
        Text(this.ReaderSingleLineStatus())
          .width('100%').height(20).maxLines(1).textAlign(TextAlign.Center)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .fontSize(11).fontColor(this.ReaderSecondaryColor())
        Row() {
          Slider({ value: this.currentBook?.progress ?? 0, min: 0, max: 100, step: 1,
            style: SliderStyle.OutSet })
            .width('100%').selectedColor(AppTheme.primary)
            .onChange((value: number) => this.SeekReaderProgress(value).catch((error: Error) => {
              this.readerInfo = error.message;
            }))
        }.width('100%').height(34)
        Row() {
          this.ReaderCatalogQuickAction()
          this.ReaderNightQuickAction()
          this.ReaderSpeechQuickAction()
          this.ReaderFontQuickAction()
          this.ReaderMoreQuickAction()
        }.width('100%').height(58)
      }
      .width('100%').padding({ left: 14, right: 14, top: 6, bottom: 8 })
      .backgroundColor(this.ReaderControlSurfaceColor())
      .border({ width: { top: 1 }, color: this.ReaderDividerColor() })
    }.width('100%').height('100%')
  }

  @Builder
  private ReaderCatalogQuickAction() {
    Button() {
      Column({ space: 3 }) {
        SymbolGlyph($r('sys.symbol.list_bullet')).fontSize(22).fontColor([this.ReaderTextColor()])
        Text(this.pdfMode ? '页码' : '目录').fontSize(11).fontColor(this.ReaderTextColor())
      }
    }.layoutWeight(1).height(56).padding(0).backgroundColor(Color.Transparent)
      .accessibilityText(this.pdfMode ? '打开页码' : '打开目录')
      .onClick(() => {
        this.readerCatalogMode = 'catalog';
        this.readerCatalogVisible = true;
        this.readerControlsVisible = false;
      })
  }

  @Builder
  private ReaderNightQuickAction() {
    Button() {
      Column({ space: 3 }) {
        SymbolGlyph($r(this.readerSettings.theme === 'night' ? 'sys.symbol.moon_fill' : 'sys.symbol.moon'))
          .fontSize(22).fontColor([this.readerSettings.theme === 'night' ? AppTheme.primary : this.ReaderTextColor()])
        Text(this.readerSettings.theme === 'night' ? '日间' : '夜间').fontSize(11)
          .fontColor(this.readerSettings.theme === 'night' ? AppTheme.primary : this.ReaderTextColor())
      }
    }.layoutWeight(1).height(56).padding(0).backgroundColor(Color.Transparent)
      .accessibilityText(this.readerSettings.theme === 'night' ? '切换日间模式' : '切换夜间模式')
      .onClick(() => this.ToggleReaderNightMode())
  }

  @Builder
  private ReaderSpeechQuickAction() {
    Button() {
      Column({ space: 3 }) {
        SymbolGlyph($r('sys.symbol.headphones')).fontSize(22)
          .fontColor([this.readerSpeechActive ? AppTheme.primary : this.ReaderTextColor()])
        Text(this.readerSpeechActive ? '停止' : '朗读').fontSize(11)
          .fontColor(this.readerSpeechActive ? AppTheme.primary : this.ReaderTextColor())
      }
    }.layoutWeight(1).height(56).padding(0).backgroundColor(Color.Transparent)
      .accessibilityText(this.readerSpeechActive ? '停止朗读' : '朗读当前内容')
      .onClick(() => this.ToggleReaderSpeech())
  }

  @Builder
  private ReaderFontQuickAction() {
    Button() {
      Column({ space: 3 }) {
        SymbolGlyph($r('sys.symbol.textformat')).fontSize(22).fontColor([this.ReaderTextColor()])
        Text('字号').fontSize(11).fontColor(this.ReaderTextColor())
      }
    }.layoutWeight(1).height(56).padding(0).backgroundColor(Color.Transparent)
      .accessibilityText('打开字号与排版设置')
      .onClick(() => {
        this.readerSettingsVisible = true;
        this.readerControlsVisible = false;
      })
  }

  @Builder
  private ReaderMoreQuickAction() {
    Button() {
      Column({ space: 3 }) {
        SymbolGlyph($r('sys.symbol.ellipsis_circle')).fontSize(22)
          .fontColor([this.readerMoreVisible ? AppTheme.primary : this.ReaderTextColor()])
        Text('更多').fontSize(11)
          .fontColor(this.readerMoreVisible ? AppTheme.primary : this.ReaderTextColor())
      }
    }.layoutWeight(1).height(56).padding(0).backgroundColor(Color.Transparent)
      .accessibilityText(this.readerMoreVisible ? '关闭更多操作' : '打开更多操作')
      .onClick(() => {
        this.readerMoreVisible = !this.readerMoreVisible;
        this.readerSearchVisible = false;
      })
  }

  @Builder
  private ReaderMoreButton(label: string, action: () => void) {
    Button(label).layoutWeight(1).height(48).fontSize(13).fontColor(this.ReaderTextColor())
      .backgroundColor(Color.Transparent)
      .onClick(action)
  }

  @Builder
  private ReaderAnnotationPanel() {
    Column({ space: 14 }) {
      Row() {
        Text('阅读批注').layoutWeight(1).fontSize(20).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
        this.CompactActionButton('关闭', () => this.readerAnnotationVisible = false)
      }.width('100%')
      Text(this.CurrentReadingStatisticLabel()).width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
      TextArea({ text: this.readerAnnotationInput, placeholder: '记录当前章节或页面的想法（最多1000字）' })
        .width('100%').height(100).onChange((value: string) => this.readerAnnotationInput = value)
      Button('保存当前位置批注').width('100%').enabled(this.readerAnnotationInput.trim().length > 0)
        .height(48).fontColor(Color.White).backgroundColor(AppTheme.primary)
        .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
        .borderRadius(AppTheme.pillRadius).accessibilityDescription('保存批注并关联当前阅读位置')
        .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
        .onClick(() => this.AddCurrentReaderAnnotation())
      Text(`本书批注 · ${this.CurrentReaderAnnotations().length}`).width('100%').fontSize(16)
        .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Scroll() {
        Column() {
          if (this.CurrentReaderAnnotations().length === 0) {
            Text('还没有批注').width('100%').padding(20).textAlign(TextAlign.Center)
              .fontColor(AppTheme.textSecondary)
          } else {
            ForEach(this.CurrentReaderAnnotations(), (annotation: ReaderAnnotation) => {
              Row({ space: 10 }) {
                Column({ space: 4 }) {
                  Text(annotation.chapterTitle).width('100%').fontSize(13).fontColor(AppTheme.primary)
                  Text(annotation.note).width('100%').maxLines(3).fontSize(14).fontColor(AppTheme.textPrimary)
                }.layoutWeight(1).alignItems(HorizontalAlign.Start).onClick(() => this.OpenReaderAnnotation(annotation))
                Button('删除').width(48).height(48).fontSize(13).fontColor(AppTheme.danger)
                  .backgroundColor(Color.Transparent).accessibilityDescription('删除这条批注')
                  .onClick(() => this.DeleteReaderAnnotation(annotation.id))
              }.width('100%').padding({ top: 10, bottom: 10 })
              .border({ width: { bottom: 1 }, color: AppTheme.divider })
            }, (annotation: ReaderAnnotation) => annotation.id)
          }
        }.width('100%')
      }.width('100%').height(230)
    }.width('100%').padding(20).backgroundColor(AppTheme.surface)
      .borderRadius({ topLeft: 24, topRight: 24 })
  }

  @Builder
  private ReaderSettingsPanel() {
    Column({ space: 14 }) {
      Row() {
        Text('阅读设置').layoutWeight(1).fontSize(20).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
        this.CompactActionButton('完成', () => {
          this.readerSettingsVisible = false;
          this.PersistReaderState();
        })
      }.width('100%')
      Scroll() {
        Column({ space: 18 }) {
          if (this.comicImageUris.length > 0) {
            this.ComicSettingsFields()
          } else {
            Text('字体').width('100%').fontSize(14).fontColor(AppTheme.textSecondary)
            Row({ space: 8 }) {
              this.ReaderFontButton('系统', 'system')
              this.ReaderFontButton('黑体', 'sans')
              this.ReaderFontButton('宋体', 'serif')
              this.ReaderFontButton('等宽', 'monospace')
            }.width('100%')
            Text('排版预设').width('100%').fontSize(14).fontColor(AppTheme.textSecondary)
            Row({ space: 8 }) {
              this.ReaderTypographyPresetButton('紧凑', 17, 1.55, 9, 20)
              this.ReaderTypographyPresetButton('舒适', 19, 1.8, 14, 24)
              this.ReaderTypographyPresetButton('大字', 23, 1.9, 18, 28)
            }.width('100%')
            this.ReaderSettingSlider('字号', 14, 32, this.readerSettings.fontSize, (value: number) => {
              this.readerSettings = this.CopyReaderSettings(value, this.readerSettings.lineHeight,
                this.readerSettings.horizontalPadding, this.readerSettings.brightness,
                this.readerSettings.theme, this.readerSettings.pageTurnMode);
              this.RebuildReaderPages();
            })
            this.ReaderSettingSlider('行距', 1.2, 2.4, this.readerSettings.lineHeight, (value: number) => {
              this.readerSettings = this.CopyReaderSettings(this.readerSettings.fontSize, value,
                this.readerSettings.horizontalPadding, this.readerSettings.brightness,
                this.readerSettings.theme, this.readerSettings.pageTurnMode);
              this.RebuildReaderPages();
            })
            this.ReaderSettingSlider('段距', 0, 32, this.readerSettings.paragraphSpacing, (value: number) => {
              this.readerSettings.paragraphSpacing = value;
              this.readerSettings = this.CopyReaderSettings(this.readerSettings.fontSize, this.readerSettings.lineHeight,
                this.readerSettings.horizontalPadding, this.readerSettings.brightness,
                this.readerSettings.theme, this.readerSettings.pageTurnMode);
              this.RebuildReaderPages();
            })
            this.ReaderSettingSlider('页边距', 12, 48, this.readerSettings.horizontalPadding, (value: number) => {
              this.readerSettings = this.CopyReaderSettings(this.readerSettings.fontSize, this.readerSettings.lineHeight,
                value, this.readerSettings.brightness, this.readerSettings.theme, this.readerSettings.pageTurnMode);
              this.RebuildReaderPages();
            })
            this.ReaderSettingSlider('亮度', 0.35, 1, this.readerSettings.brightness, (value: number) => {
              this.readerSettings = this.CopyReaderSettings(this.readerSettings.fontSize, this.readerSettings.lineHeight,
                this.readerSettings.horizontalPadding, value, this.readerSettings.theme,
                this.readerSettings.pageTurnMode);
            })
            Text('主题').width('100%').fontSize(14).fontColor(AppTheme.textSecondary)
            Row({ space: 12 }) {
              this.ReaderThemeButton('纸张', 'paper', '#F6F0E3')
              this.ReaderThemeButton('白色', 'white', '#FFFFFF')
              this.ReaderThemeButton('护眼', 'green', '#DDEBDD')
              this.ReaderThemeButton('夜间', 'night', '#171A20')
            }.width('100%')
            Text('翻页方式').width('100%').fontSize(14).fontColor(AppTheme.textSecondary)
            Row({ space: 10 }) {
              this.ReaderTurnButton('滑动', 'slide')
              this.ReaderTurnButton('覆盖', 'cover')
              this.ReaderTurnButton('滚动', 'scroll')
            }.width('100%')
            Text('屏幕方向').width('100%').fontSize(14).fontColor(AppTheme.textSecondary)
            Row({ space: 10 }) {
              this.ReaderOrientationButton('跟随系统', 'system')
              this.ReaderOrientationButton('竖屏', 'portrait')
              this.ReaderOrientationButton('横屏', 'landscape')
            }.width('100%')
          }
        }.width('100%').alignItems(HorizontalAlign.Start).padding({ bottom: 8 })
      }.width('100%').layoutWeight(1).scrollBar(BarState.Auto)
    }
    .width('100%').height('78%').padding({ left: 22, right: 22, top: 22, bottom: 20 })
    .borderRadius({ topLeft: 24, topRight: 24 }).backgroundColor(AppTheme.surface)
  }

  @Builder
  private ComicSettingsFields() {
    Text('阅读方向').width('100%').fontSize(14).fontColor(AppTheme.textSecondary)
    Row({ space: 10 }) {
      this.ComicOptionButton('从左到右', 'ltr', this.readerSettings.comicDirection,
        () => this.UpdateComicSettings('direction', 'ltr'))
      this.ComicOptionButton('从右到左', 'rtl', this.readerSettings.comicDirection,
        () => this.UpdateComicSettings('direction', 'rtl'))
    }.width('100%')
    Text('页面模式').width('100%').fontSize(14).fontColor(AppTheme.textSecondary)
    Row({ space: 10 }) {
      this.ComicOptionButton('单页', 'single', this.readerSettings.comicPageMode,
        () => this.UpdateComicSettings('page', 'single'))
      this.ComicOptionButton('双页', 'double', this.readerSettings.comicPageMode,
        () => this.UpdateComicSettings('page', 'double'))
      this.ComicOptionButton('长图', 'long', this.readerSettings.comicPageMode,
        () => this.UpdateComicSettings('page', 'long'))
    }.width('100%')
    Text('图片适配').width('100%').fontSize(14).fontColor(AppTheme.textSecondary)
    Row({ space: 10 }) {
      this.ComicOptionButton('完整显示', 'contain', this.readerSettings.comicFitMode,
        () => this.UpdateComicSettings('fit', 'contain'))
      this.ComicOptionButton('填满屏幕', 'cover', this.readerSettings.comicFitMode,
        () => this.UpdateComicSettings('fit', 'cover'))
    }.width('100%')
    this.ComicSettingSlider('缩放', 1, 3, 0.1, this.readerSettings.comicScale, (value: number) => {
      this.readerSettings.comicScale = value;
      this.readerSettings = this.CopyReaderSettings(this.readerSettings.fontSize, this.readerSettings.lineHeight,
        this.readerSettings.horizontalPadding, this.readerSettings.brightness, this.readerSettings.theme,
        this.readerSettings.pageTurnMode);
    })
    this.ComicSettingSlider('预加载', 1, 5, 1, this.readerSettings.comicPreload, (value: number) => {
      this.readerSettings.comicPreload = Math.round(value);
      this.readerSettings = this.CopyReaderSettings(this.readerSettings.fontSize, this.readerSettings.lineHeight,
        this.readerSettings.horizontalPadding, this.readerSettings.brightness, this.readerSettings.theme,
        this.readerSettings.pageTurnMode);
    })
  }

  @Builder
  private ComicOptionButton(label: string, value: string, selected: string, action: () => void) {
    Button(label).layoutWeight(1).height(48).fontSize(13)
      .fontColor(selected === value ? AppTheme.primary : AppTheme.textSecondary)
      .backgroundColor(selected === value ? AppTheme.accentSoft : AppTheme.surface)
      .border({ width: 1, color: selected === value ? AppTheme.primary : AppTheme.divider,
        radius: AppTheme.pillRadius }).borderRadius(AppTheme.pillRadius)
      .accessibilityDescription(selected === value ? '已选择' : '未选择')
      .onClick(action)
  }

  @Builder
  private ComicSettingSlider(label: string, min: number, max: number, step: number, value: number,
    change: (value: number) => void) {
    Row({ space: 12 }) {
      Text(label).width(56).fontSize(14).fontColor(AppTheme.textPrimary)
      Slider({ value, min, max, step, style: SliderStyle.OutSet })
        .layoutWeight(1).selectedColor(AppTheme.primary).onChange(change)
      Text(value.toFixed(step < 1 ? 1 : 0)).width(36).fontSize(12).fontColor(AppTheme.textSecondary)
    }.width('100%')
  }

  @Builder
  private ReaderCatalogPanel() {
    Column({ space: 12 }) {
      Row() {
        Text(this.pdfMode ? 'PDF页码' : (this.comicImageUris.length > 0 ? '漫画页码' : '目录'))
          .layoutWeight(1).fontSize(20).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
        this.CompactActionButton('关闭', () => this.readerCatalogVisible = false)
      }.width('100%')
      Row({ space: 8 }) {
        this.ReaderCatalogModeButton(this.pdfMode ? '页码' : '目录', 'catalog')
        this.ReaderCatalogModeButton(`书签 ${this.CurrentReaderBookmarks().length}`, 'bookmarks')
      }.width('100%')
      Scroll() {
        Column() {
          if (this.readerCatalogMode === 'bookmarks') {
            if (this.CurrentReaderBookmarks().length === 0) {
              Text('当前图书还没有书签').width('100%').padding({ top: 28 })
                .textAlign(TextAlign.Center).fontSize(14).fontColor(AppTheme.textSecondary)
            } else {
              ForEach(this.CurrentReaderBookmarks(), (bookmark: ReaderBookmark) => {
                Row() {
                  Column({ space: 4 }) {
                    Text(bookmark.chapterTitle).width('100%').maxLines(1).fontSize(15)
                      .fontColor(AppTheme.textPrimary)
                    Text(bookmark.note.length > 0 ? bookmark.note : `位置 ${bookmark.locator}`)
                      .width('100%').maxLines(1).fontSize(12).fontColor(AppTheme.textSecondary)
                  }.layoutWeight(1).alignItems(HorizontalAlign.Start)
                  Text('›').fontSize(22).fontColor(AppTheme.textSecondary)
                }.width('100%').padding({ top: 12, bottom: 12 })
                  .border({ width: { bottom: 1 }, color: AppTheme.divider })
                  .onClick(() => this.OpenReaderBookmark(bookmark))
              }, (bookmark: ReaderBookmark) => bookmark.id)
            }
          } else if (this.pdfMode) {
            Column({ space: 18 }) {
              Text(`第 ${this.pdfPageIndex + 1} 页，共 ${this.pdfPageCount} 页`)
                .fontSize(16).fontColor(AppTheme.textPrimary)
              Row({ space: 12 }) {
                Button('上一页').layoutWeight(1).enabled(this.pdfPageIndex > 0)
                  .height(48).accessibilityDescription('跳转到上一页PDF')
                  .fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surfaceTint)
                  .borderRadius(AppTheme.pillRadius)
                  .onClick(() => this.ChangeReaderSection(-1))
                Button('下一页').layoutWeight(1).enabled(this.pdfPageIndex + 1 < this.pdfPageCount)
                  .height(48).accessibilityDescription('跳转到下一页PDF')
                  .fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surfaceTint)
                  .borderRadius(AppTheme.pillRadius)
                  .onClick(() => this.ChangeReaderSection(1))
              }.width('100%')
            }.width('100%').padding({ top: 24 })
          } else if (this.comicImageUris.length > 0) {
            ForEach(this.comicImageUris, (_uri: string, index: number) => {
              Button(`第${index + 1}页`).width('100%').height(48).fontSize(15)
                .backgroundColor(Color.Transparent)
                .fontColor(index === this.comicPageIndex ? AppTheme.primary : AppTheme.textPrimary)
                .accessibilityDescription(index === this.comicPageIndex ? '当前页' : '跳转到该页')
                .onClick(() => {
                  this.comicPageIndex = index;
                  this.readerCatalogVisible = false;
                })
            }, (uri: string) => uri)
          } else {
            ForEach(this.readerChapters, (chapter: ReaderChapter, index: number) => {
              Button(chapter.title).width('100%').height(48).fontSize(15)
                .backgroundColor(Color.Transparent)
                .fontColor(index === this.readerChapterIndex ? AppTheme.primary : AppTheme.textPrimary)
                .accessibilityDescription(index === this.readerChapterIndex ? '当前章节' : '跳转到该章节')
                .onClick(() => {
                  this.readerCatalogVisible = false;
                  this.SelectReaderChapter(index).catch((error: Error) => {
                    this.readerStatus = error.message;
                  });
                })
            }, (chapter: ReaderChapter, index: number) => `${index}:${chapter.title}`)
          }
        }.width('100%').alignItems(HorizontalAlign.Start).justifyContent(FlexAlign.Start)
      }.layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
    }
    .width('86%').height('100%').padding({ left: 20, right: 18, top: 34, bottom: 24 })
    .borderRadius({ topRight: 24, bottomRight: 24 }).backgroundColor(AppTheme.surface)
    .position({ x: 0, y: 0 })
  }

  @Builder
  private ReaderCatalogModeButton(label: string, mode: string) {
    Button(label).layoutWeight(1).height(48).fontSize(14)
      .fontColor(this.readerCatalogMode === mode ? AppTheme.primary : AppTheme.textSecondary)
      .backgroundColor(this.readerCatalogMode === mode ? AppTheme.accentSoft : AppTheme.surface)
      .border({ width: 1, color: this.readerCatalogMode === mode ? AppTheme.primary : AppTheme.divider,
        radius: AppTheme.pillRadius })
      .accessibilityDescription(this.readerCatalogMode === mode ? '已选择' : '未选择')
      .borderRadius(AppTheme.pillRadius).onClick(() => this.readerCatalogMode = mode)
  }

  @Builder
  private ReaderSettingSlider(label: string, min: number, max: number, value: number,
    change: (value: number) => void) {
    Row({ space: 12 }) {
      Text(label).width(56).fontSize(14).fontColor(AppTheme.textPrimary)
      Slider({ value, min, max, step: 0.1, style: SliderStyle.OutSet })
        .layoutWeight(1).selectedColor(AppTheme.primary).onChange(change)
      Text(value.toFixed(1)).width(36).fontSize(12).fontColor(AppTheme.textSecondary)
    }.width('100%')
  }

  @Builder
  private ReaderThemeButton(label: string, theme: string, color: string) {
    Button(label).layoutWeight(1).height(48).fontSize(13)
      .fontColor(theme === 'night' ? Color.White : AppTheme.textPrimary)
      .border({ width: this.readerSettings.theme === theme ? 2 : 1,
        color: this.readerSettings.theme === theme ? AppTheme.primary : AppTheme.divider, radius: 12 })
      .backgroundColor(color)
      .accessibilityDescription(this.readerSettings.theme === theme ? '已选择' : '未选择')
      .onClick(() => this.SelectReaderTheme(theme as ReaderTheme))
  }

  /** 切换阅读主题并同步系统状态栏与导航栏颜色。 */
  private SelectReaderTheme(theme: ReaderTheme): void {
    this.readerSettings = this.CopyReaderSettings(this.readerSettings.fontSize,
      this.readerSettings.lineHeight, this.readerSettings.horizontalPadding, this.readerSettings.brightness,
      theme, this.readerSettings.pageTurnMode);
    this.ApplyReaderSystemBars(true);
  }

  @Builder
  private ReaderTurnButton(label: string, mode: string) {
    Button(label).layoutWeight(1).height(48).fontSize(13)
      .fontColor(this.readerSettings.pageTurnMode === mode ? AppTheme.primary : AppTheme.textSecondary)
      .borderRadius(AppTheme.pillRadius)
      .backgroundColor(this.readerSettings.pageTurnMode === mode ? AppTheme.accentSoft : AppTheme.surface)
      .border({ width: 1, color: this.readerSettings.pageTurnMode === mode ? AppTheme.primary : AppTheme.divider,
        radius: AppTheme.pillRadius })
      .accessibilityDescription(this.readerSettings.pageTurnMode === mode ? '已选择' : '未选择')
      .onClick(() => this.SetReaderTurnMode(mode as PageTurnMode))
  }

  @Builder
  private ReaderFontButton(label: string, family: ReaderFontFamily) {
    Button(label).layoutWeight(1).height(48).fontSize(13)
      .fontColor(this.readerSettings.fontFamily === family ? AppTheme.primary : AppTheme.textSecondary)
      .backgroundColor(this.readerSettings.fontFamily === family ? AppTheme.accentSoft : AppTheme.surface)
      .border({ width: 1, color: this.readerSettings.fontFamily === family ? AppTheme.primary : AppTheme.divider,
        radius: AppTheme.pillRadius })
      .accessibilityDescription(this.readerSettings.fontFamily === family ? '已选择' : '未选择')
      .borderRadius(AppTheme.pillRadius).onClick(() => this.SetReaderFontFamily(family))
  }

  @Builder
  private ReaderTypographyPresetButton(label: string, fontSize: number, lineHeight: number,
    paragraphSpacing: number, horizontalPadding: number) {
    Button(label).layoutWeight(1).height(44).fontSize(13)
      .fontColor(this.ReaderTypographyPresetSelected(fontSize, lineHeight, paragraphSpacing, horizontalPadding) ?
        AppTheme.primary : AppTheme.textSecondary)
      .backgroundColor(this.ReaderTypographyPresetSelected(fontSize, lineHeight, paragraphSpacing,
        horizontalPadding) ? AppTheme.accentSoft : AppTheme.surface)
      .border({ width: 1, color: this.ReaderTypographyPresetSelected(fontSize, lineHeight, paragraphSpacing,
        horizontalPadding) ? AppTheme.primary : AppTheme.divider, radius: AppTheme.pillRadius })
      .borderRadius(AppTheme.pillRadius)
      .accessibilityDescription(this.ReaderTypographyPresetSelected(fontSize, lineHeight, paragraphSpacing,
        horizontalPadding) ? '已选择' : '应用该排版')
      .onClick(() => this.ApplyReaderTypographyPreset(fontSize, lineHeight, paragraphSpacing, horizontalPadding))
  }

  private ReaderTypographyPresetSelected(fontSize: number, lineHeight: number, paragraphSpacing: number,
    horizontalPadding: number): boolean {
    return Math.abs(this.readerSettings.fontSize - fontSize) < 0.1 &&
      Math.abs(this.readerSettings.lineHeight - lineHeight) < 0.05 &&
      Math.abs(this.readerSettings.paragraphSpacing - paragraphSpacing) < 0.1 &&
      Math.abs(this.readerSettings.horizontalPadding - horizontalPadding) < 0.1;
  }

  private ApplyReaderTypographyPreset(fontSize: number, lineHeight: number, paragraphSpacing: number,
    horizontalPadding: number): void {
    this.readerSettings.paragraphSpacing = paragraphSpacing;
    this.readerSettings = this.CopyReaderSettings(fontSize, lineHeight, horizontalPadding,
      this.readerSettings.brightness, this.readerSettings.theme, this.readerSettings.pageTurnMode);
    this.RebuildReaderPages();
    this.PersistReaderState();
  }

  private SetReaderFontFamily(family: ReaderFontFamily): void {
    if (this.readerSettings.fontFamily === family) return;
    this.readerSettings.fontFamily = family;
    this.readerSettings = this.CopyReaderSettings(this.readerSettings.fontSize, this.readerSettings.lineHeight,
      this.readerSettings.horizontalPadding, this.readerSettings.brightness,
      this.readerSettings.theme, this.readerSettings.pageTurnMode);
    this.RebuildReaderPages();
    this.PersistReaderState();
  }

  private ReaderFontName(): string {
    if (this.readerSettings.fontFamily === 'sans') return 'HarmonyOS Sans';
    if (this.readerSettings.fontFamily === 'serif') return 'Noto Serif CJK JP';
    if (this.readerSettings.fontFamily === 'monospace') return 'monospace';
    // 空字体族由系统主题解析，跟随用户在系统中设置的字体。
    return '';
  }

  private ReaderSystemFontScale(): number {
    return this.readerTextScalePolicy.normalize(this.systemFontScale);
  }

  private ReaderScaledFontSize(value: number): number {
    return value * this.ReaderSystemFontScale();
  }

  /** 将旧版页边距刻度校准为更适合手机屏幕的正文实际边距。 */
  private ReaderContentHorizontalPadding(): number {
    return Math.max(6, this.readerSettings.horizontalPadding - 16);
  }

  private OnSystemFontScaleChanged(): void {
    if (!this.readerVisible || this.pdfMode || this.comicImageUris.length > 0) return;
    // 保留章节内逻辑位置后重排，防止系统字号改变时跳回章节开头。
    this.RebuildReaderPages();
  }

  private ReaderFontLabel(): string {
    if (this.readerSettings.fontFamily === 'sans') return '黑体风格';
    if (this.readerSettings.fontFamily === 'serif') return '宋体风格';
    if (this.readerSettings.fontFamily === 'monospace') return '等宽';
    return '系统';
  }

  @Builder
  private ReaderOrientationButton(label: string, orientation: ReaderOrientation) {
    Button(label).layoutWeight(1).height(48).fontSize(13)
      .fontColor(this.readerSettings.orientation === orientation ? AppTheme.primary : AppTheme.textSecondary)
      .backgroundColor(this.readerSettings.orientation === orientation ? AppTheme.accentSoft : AppTheme.surface)
      .border({ width: 1, color: this.readerSettings.orientation === orientation ? AppTheme.primary : AppTheme.divider,
        radius: AppTheme.pillRadius })
      .accessibilityDescription(this.readerSettings.orientation === orientation ? '已选择' : '未选择')
      .borderRadius(AppTheme.pillRadius).onClick(() => this.SetReaderOrientation(orientation))
  }

  private SetReaderOrientation(orientation: ReaderOrientation): void {
    if (this.readerSettings.orientation === orientation) return;
    this.readerSettings.orientation = orientation;
    this.readerSettings = this.CopyReaderSettings(this.readerSettings.fontSize, this.readerSettings.lineHeight,
      this.readerSettings.horizontalPadding, this.readerSettings.brightness,
      this.readerSettings.theme, this.readerSettings.pageTurnMode);
    this.ApplyReaderOrientation(orientation);
    this.PersistReaderState();
  }

  private ApplyReaderOrientation(orientation: ReaderOrientation): void {
    const context = this.ApplicationContext();
    if (context === undefined) return;
    const preferred = orientation === 'portrait' ? window.Orientation.PORTRAIT :
      (orientation === 'landscape' ? window.Orientation.LANDSCAPE : window.Orientation.UNSPECIFIED);
    window.getLastWindow(context).then((mainWindow: window.Window) => {
      return mainWindow.setPreferredOrientation(preferred);
    }).then(() => {
      if (this.readerVisible) {
        setTimeout(() => this.RebuildReaderPages(), 180);
      }
    }).catch((error: Error) => {
      if (this.readerVisible) this.readerInfo = `屏幕方向切换失败：${error.message}`;
    });
  }

  private ReaderOrientationLabel(): string {
    if (this.readerSettings.orientation === 'portrait') return '竖屏';
    if (this.readerSettings.orientation === 'landscape') return '横屏';
    return '跟随系统';
  }

  private SetReaderTurnMode(mode: PageTurnMode): void {
    if (this.readerSettings.pageTurnMode === mode) return;
    const chapterFraction = this.CurrentTextChapterFraction();
    this.readerSettings = this.CopyReaderSettings(this.readerSettings.fontSize,
      this.readerSettings.lineHeight, this.readerSettings.horizontalPadding, this.readerSettings.brightness,
      this.readerSettings.theme, mode);
    this.RebuildReaderPages();
    if (mode === 'scroll') {
      this.readerScrollValue = chapterFraction * this.EstimatedReaderTextHeight();
      setTimeout(() => {
        try {
          this.readerScroller.scrollTo({ xOffset: 0, yOffset: this.ReaderAbsoluteScrollOffset(), animation: false });
        } catch (_) {
        }
      }, 0);
    }
  }

  private CopyReaderSettings(fontSize: number, lineHeight: number, horizontalPadding: number, brightness: number,
    theme: ReaderTheme, pageTurnMode: PageTurnMode): ReaderSettings {
    return {
      fontFamily: this.readerSettings.fontFamily, fontSize, lineHeight,
      paragraphSpacing: this.readerSettings.paragraphSpacing,
      orientation: this.readerSettings.orientation,
      horizontalPadding, brightness, theme, pageTurnMode,
      comicDirection: this.readerSettings.comicDirection,
      comicPageMode: this.readerSettings.comicPageMode,
      comicFitMode: this.readerSettings.comicFitMode,
      comicScale: this.readerSettings.comicScale,
      comicPreload: this.readerSettings.comicPreload
    };
  }

  private async OpenBook(book: Book): Promise<void> {
    this.readerSearchRevision++;
    this.readerOpeningLoader?.cancel();
    this.activeReaderTicketCancellation?.cancel();
    this.activeSourceCatalogCancellation?.cancel();
    this.CancelReaderChapterPrefetch();
    const openRevision = ++this.readerOpenRevision;
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    let lifecycleRevision = this.pageLifecycleRevision;
    const loader = new ReaderContentLoader();
    this.readerOpeningLoader = loader;
    this.sourceChapterLoadRevision++;
    this.FinishReadingSession();
    this.currentBook = book;
    this.readerSessionStartedAt = Date.now();
    this.readerVisible = true;
    this.ApplyReaderSystemBars(true);
    this.readerViewportWidth = 360;
    this.readerViewportHeight = 700;
    this.ApplyReaderOrientation(this.readerSettings.orientation);
    // 应用阅读方向可能触发页面可见性回调；以方向切换完成后的生命周期为本次打开基线。
    lifecycleRevision = this.pageLifecycleRevision;
    this.StartReaderPersistenceTimer();
    this.readerControlsVisible = false;
    this.readerSettingsVisible = false;
    this.readerSearchVisible = false;
    this.readerMoreVisible = false;
    this.readerCatalogVisible = false;
    this.readerSearchText = '';
    this.readerSearchResults = [];
    this.readerSearchBusy = false;
    this.readerSearchStatus = '';
    this.readerContent = '';
    this.readerChapters = [];
    this.readerChapterIndex = 0;
    this.comicImageUris = [];
    this.sourceComicFailedIndexes = [];
    this.sourceComicRemoteUrls = [];
    this.comicPageIndex = 0;
    this.pdfMode = book.format === 'pdf';
    this.pdfPageIndex = 0;
    this.pdfPageCount = 0;
    this.readerStatus = '正在打开图书…';
    this.readerInfo = '';
    this.readerScrollValue = 0;
    this.readerScroller = new Scroller();
    this.readerScrollEpoch++;
    try {
      if (book.format === 'cbr') {
        this.readerStatus = 'CBR使用RAR容器，当前安全解码器尚未接入；请优先使用CBZ';
        return;
      }
      if (book.origin !== 'source' && book.format !== 'txt' && book.format !== 'epub' &&
        book.format !== 'cbz' && book.format !== 'pdf' && book.format !== 'mobi' && book.format !== 'azw3') {
        this.readerStatus = `${book.format.toUpperCase()} 已加入书架，格式渲染器尚未接入`;
        return;
      }
      const hostContext = this.getUIContext().getHostContext();
      if (hostContext === undefined) throw new Error('无法获取应用上下文');
      const context = hostContext as common.UIAbilityContext;
      if (book.origin === 'remote' && this.authenticated &&
        this.readingProgress.find((value: ReadingProgress) => value.bookId === book.id) === undefined) {
        await this.RestoreRemoteBookProgress(book, this.readerProgressSyncGeneration, this.localAccountScope);
        if (!this.ReaderOpenCurrent(openRevision, accountRevision, owner, lifecycleRevision, book)) return;
      }
      if (book.origin === 'local') {
        await this.localBookPicker.activate(book.resourceUri);
        if (!this.ReaderOpenCurrent(openRevision, accountRevision, owner, lifecycleRevision, book)) return;
        if (book.format === 'txt') this.ApplyOpenedReaderDocument(openRevision, accountRevision, owner,
          lifecycleRevision, book, loader, await loader.loadLocalText(context, book.resourceUri));
        else if (book.format === 'pdf') await this.OpenPreparedPdf(openRevision, accountRevision, owner,
          lifecycleRevision, book, loader, await loader.prepareLocalPdf(context, book.resourceUri));
        else if (book.format === 'mobi' || book.format === 'azw3') this.ApplyOpenedReaderDocument(openRevision,
          accountRevision, owner, lifecycleRevision, book,
          loader, await loader.loadLocalMobi(context, book.resourceUri, book.format));
        else this.ApplyOpenedReaderDocument(openRevision, accountRevision, owner, lifecycleRevision, book,
          loader, await loader.loadLocalArchive(context, book.resourceUri, book.format));
      } else if (book.origin === 'remote') {
        const ticketCancellation = new DownloadCancellationToken();
        this.activeReaderTicketCancellation = ticketCancellation;
        let descriptor: RemotePlaybackDescriptor;
        try {
          const api = new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager);
          let source = this.remoteBookSources.find((value: RemoteMediaSource) => value.id === book.sourceId);
          if (source === undefined) {
            const sources = await api.listBookSources(ticketCancellation);
            if (!this.ReaderOpenCurrent(openRevision, accountRevision, owner, lifecycleRevision, book)) return;
            this.remoteBookSources = sources;
            source = sources.find((value: RemoteMediaSource) => value.id === book.sourceId);
          }
          if (source === undefined) throw new Error('电子书远程来源当前不可用');
          descriptor = await api.resolveBookPlayback(source, book.resourceUri, ticketCancellation);
        } finally {
          if (this.activeReaderTicketCancellation === ticketCancellation) {
            this.activeReaderTicketCancellation = undefined;
          }
        }
        if (!this.ReaderOpenCurrent(openRevision, accountRevision, owner, lifecycleRevision, book)) return;
        if (book.format === 'txt') this.ApplyOpenedReaderDocument(openRevision, accountRevision, owner,
          lifecycleRevision, book, loader, await loader.loadRemoteText(context, descriptor.url));
        else if (book.format === 'pdf') await this.OpenPreparedPdf(openRevision, accountRevision, owner,
          lifecycleRevision, book, loader, await loader.prepareRemotePdf(context, descriptor.url));
        else if (book.format === 'mobi' || book.format === 'azw3') this.ApplyOpenedReaderDocument(openRevision,
          accountRevision, owner, lifecycleRevision, book,
          loader, await loader.loadRemoteMobi(context, descriptor.url, book.format));
        else this.ApplyOpenedReaderDocument(openRevision, accountRevision, owner, lifecycleRevision, book,
          loader, await loader.loadRemoteArchive(context, descriptor.url, book.format, undefined,
            book.id, book.cacheVersion));
      } else if (book.origin === 'source') {
        this.readerStatus = '正在准备书源图书…';
        if (!book.resourceUri.startsWith('https://') && !book.resourceUri.startsWith('http://')) {
          throw new Error('书源没有返回有效图书地址');
        }
        // 规则型小说命中本地目录和正文时不触发DNS或临时网络请求；真正回源前再执行地址安全校验。
        if (book.format !== 'unknown') {
          this.readerStatus = '正在验证图书地址…';
          await this.sourceUrlPolicy.assertSafe(book.resourceUri);
        }
        if (!this.ReaderOpenCurrent(openRevision, accountRevision, owner, lifecycleRevision, book)) return;
        let sourceRequestHeaders: Record<string, string> = {};
        if (book.format !== 'unknown') {
          // 只有APP直连文件时才读取端侧凭据；规则型书源由后端执行，避免Asset Store阻塞打开流程。
          if (book.id.startsWith('opds:')) {
            const authorization = await this.opdsCredentialStore.authorizationFor(book.resourceUri);
            if (authorization !== undefined) sourceRequestHeaders.Authorization = authorization;
          } else {
            sourceRequestHeaders = await this.bookSourceCredentialStore.headersFor(book.sourceId, book.resourceUri);
          }
          if (!this.ReaderOpenCurrent(openRevision, accountRevision, owner, lifecycleRevision, book)) return;
        }
        if (book.format === 'txt') {
          this.ApplyOpenedReaderDocument(openRevision, accountRevision, owner, lifecycleRevision, book,
            loader, await loader.loadRemoteText(context, book.resourceUri, sourceRequestHeaders));
        } else if (book.format === 'pdf') {
          await this.OpenPreparedPdf(openRevision, accountRevision, owner, lifecycleRevision, book,
            loader, await loader.prepareRemotePdf(context, book.resourceUri, sourceRequestHeaders));
        } else if (book.format === 'epub' || book.format === 'cbz') {
          this.ApplyOpenedReaderDocument(openRevision, accountRevision, owner, lifecycleRevision, book,
            loader, await loader.loadRemoteArchive(context, book.resourceUri, book.format,
              sourceRequestHeaders));
        } else if (book.format === 'mobi' || book.format === 'azw3') {
          this.ApplyOpenedReaderDocument(openRevision, accountRevision, owner, lifecycleRevision, book,
            loader, await loader.loadRemoteMobi(context, book.resourceUri, book.format,
              sourceRequestHeaders));
        } else {
          const source = this.bookSources.find((value: BookSource) => value.bookSourceUrl === book.sourceId);
          if (source === undefined) throw new Error('图书对应的书源已被移除');
          const localCacheEnabled = this.SourceBookLocalCacheEnabled(book);
          const cachedCatalog = this.detailCatalogBookId === book.id ? this.detailCatalog : undefined;
          let catalog = cachedCatalog;
          let localCatalogCachedAt = 0;
          if (catalog === undefined && localCacheEnabled) {
            this.readerStatus = '正在读取本地目录…';
            const localCatalog = await this.bookSourceLocalCache.catalog(context, owner,
              source.bookSourceUrl, book.resourceUri);
            if (localCatalog !== undefined) {
              catalog = localCatalog.document;
              localCatalogCachedAt = localCatalog.cachedAt;
            }
          }
          if (catalog === undefined) {
            // 规则型书源由后端代理执行并完成SSRF校验，APP不再使用无超时的本地DNS解析阻塞打开。
            this.readerStatus = '正在加载图书目录…';
            const cancellation = new DownloadCancellationToken();
            this.activeSourceCatalogCancellation = cancellation;
            try {
              catalog = (await new BookSourceRuntimeReaderApi(this.ActiveServiceUrl(), this.authManager)
                .catalog(source.bookSourceUrl, book.resourceUri, cancellation)).document;
              if (localCacheEnabled) {
                await this.bookSourceLocalCache.putCatalog(context, owner, source.bookSourceUrl,
                  book.resourceUri, catalog);
              }
            } finally {
              if (this.activeSourceCatalogCancellation === cancellation) {
                this.activeSourceCatalogCancellation = undefined;
              }
            }
          }
          if (!this.ReaderOpenCurrent(openRevision, accountRevision, owner, lifecycleRevision, book)) return;
          this.ApplyReaderDocument(catalog);
          await this.LoadSourceChapter();
          if (localCacheEnabled && localCatalogCachedAt > 0 &&
            this.bookSourceLocalCache.shouldRefreshCatalog(localCatalogCachedAt)) {
            this.RefreshSourceCatalogCache(book, source, context, owner);
          } else if (localCacheEnabled && cachedCatalog !== undefined) {
            this.bookSourceLocalCache.putCatalog(context, owner, source.bookSourceUrl, book.resourceUri,
              cachedCatalog).catch((error: Error) => console.warn(`Source catalog cache failed: ${error.message}`));
          }
        }
      }
      if (!this.ReaderOpenCurrent(openRevision, accountRevision, owner, lifecycleRevision, book)) return;
      if (this.readerStatus === '正在打开图书…') {
        this.readerStatus = this.readerContent.length === 0 && this.comicImageUris.length === 0 && !this.pdfMode ?
          '图书没有可显示的内容' : '';
      }
      if (this.readerStatus.length === 0) this.ScheduleReaderChapterPrefetch();
    } catch (error) {
      if (!this.ReaderOpenCurrent(openRevision, accountRevision, owner, lifecycleRevision, book)) {
        loader.cleanup();
        return;
      }
      loader.cleanup();
      this.readerStatus = error instanceof Error ? error.message : '打开图书失败';
    } finally {
      if (this.readerOpeningLoader === loader) this.readerOpeningLoader = undefined;
    }
  }

  private ReaderOpenCurrent(openRevision: number, accountRevision: number, owner: string,
    _lifecycleRevision: number, book: Book): boolean {
    // 阅读方向切换会触发页面可见性回调，不能据此误杀仍在前台的打开任务；销毁时由取消令牌负责终止网络。
    return openRevision === this.readerOpenRevision && this.AccountOperationCurrent(accountRevision, owner) &&
      this.readerVisible && this.currentBook?.id === book.id;
  }

  /** 仅对已加入当前账户书架的规则型网络小说启用持久化章节缓存。 */
  private SourceBookLocalCacheEnabled(book: Book): boolean {
    return book.origin === 'source' && book.format === 'unknown' && this.localAccountScope.length > 0 &&
      this.books.some((stored: Book) => stored.id === book.id && stored.origin === 'source');
  }

  /** 命中本地旧目录后在后台更新下次打开使用的目录，不阻塞当前阅读。 */
  private RefreshSourceCatalogCache(book: Book, source: BookSource, context: common.UIAbilityContext,
    owner: string): void {
    const accountRevision = this.accountOperationRevision;
    setTimeout(() => {
      this.sourceUrlPolicy.assertSafe(book.resourceUri).then(() =>
        new BookSourceRuntimeReaderApi(this.ActiveServiceUrl(), this.authManager)
          .catalog(source.bookSourceUrl, book.resourceUri)).then((catalog) => {
          if (!this.AccountOperationCurrent(accountRevision, owner)) return;
          return this.bookSourceLocalCache.putCatalog(context, owner, source.bookSourceUrl,
            book.resourceUri, catalog.document);
        }).catch((error: Error) => console.warn(`Source catalog refresh failed: ${error.message}`));
    }, 0);
  }

  private ApplyOpenedReaderDocument(openRevision: number, accountRevision: number, owner: string,
    lifecycleRevision: number, book: Book, loader: ReaderContentLoader, document: ReaderDocument): void {
    if (!this.ReaderOpenCurrent(openRevision, accountRevision, owner, lifecycleRevision, book)) {
      loader.cleanup();
      return;
    }
    this.AdoptReaderContentLoader(loader);
    this.ApplyReaderDocument(document);
  }

  private async OpenPreparedPdf(openRevision: number, accountRevision: number, owner: string,
    lifecycleRevision: number, book: Book, loader: ReaderContentLoader, path: string): Promise<void> {
    if (!this.ReaderOpenCurrent(openRevision, accountRevision, owner, lifecycleRevision, book)) {
      loader.cleanup();
      return;
    }
    this.AdoptReaderContentLoader(loader);
    await this.OpenPdf(path);
    if (!this.ReaderOpenCurrent(openRevision, accountRevision, owner, lifecycleRevision, book)) {
      try {
        this.pdfController.releaseDocument();
      } catch (_) {
      }
      loader.cleanup();
    }
  }

  private AdoptReaderContentLoader(loader: ReaderContentLoader): void {
    if (this.readerContentLoader === loader) return;
    this.readerContentLoader.cleanup();
    this.readerContentLoader = loader;
    if (this.readerOpeningLoader === loader) this.readerOpeningLoader = undefined;
  }

  private async OpenPdf(path: string): Promise<void> {
    const saved = this.readingProgress.find((value: ReadingProgress) => value.bookId === this.currentBook?.id);
    const initialPage = saved === undefined ? 0 : Math.max(0, Math.floor(saved.locator));
    try {
      this.pdfController.registerPageChangedListener((pageIndex: number) => {
        this.pdfPageIndex = pageIndex;
        if (this.currentBook !== undefined && this.pdfPageCount > 0) {
          this.currentBook.progress = Math.round((pageIndex + 1) * 100 / this.pdfPageCount);
        }
        this.ScheduleReaderProgressSave();
      });
      this.pdfController.registerPageCountChangedListener((pageCount: number) => this.pdfPageCount = pageCount);
      const result = await this.pdfController.loadDocument(path, undefined, initialPage);
      if (result !== pdfService.ParseResult.PARSE_SUCCESS) throw new Error(`PDF解析失败（${result}）`);
      this.pdfPageIndex = this.pdfController.getPageIndex();
      this.pdfPageCount = this.pdfController.getPageCount();
      if (this.currentBook !== undefined && this.pdfPageCount > 0) {
        const restoredProgress = Math.round((this.pdfPageIndex + 1) * 100 / this.pdfPageCount);
        const progressChanged = this.currentBook.progress !== restoredProgress;
        this.currentBook.progress = restoredProgress;
        if (progressChanged) this.ScheduleReaderProgressSave();
      }
      this.readerStatus = '';
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('PDF打开失败');
    }
  }

  private ApplyReaderDocument(document: ReaderDocument): void {
    this.readerChapters = document.chapters;
    this.comicImageUris = document.imageUris;
    this.readerScrollDirection = 0;
    this.readerLastScrollAt = 0;
    const saved = this.readingProgress.find((value: ReadingProgress) => value.bookId === this.currentBook?.id);
    if (document.kind === 'text' && document.chapters.length > 0) {
      const savedIndex = saved === undefined ? -1 : this.FindSavedReaderChapterIndex(document.chapters, saved);
      this.readerChapterIndex = savedIndex < 0 ? 0 : savedIndex;
      this.readerContent = document.chapters[this.readerChapterIndex].content;
      this.PrepareReaderContinuousWindow(this.readerChapterIndex);
      if (document.encoding !== undefined) {
        this.readerInfo = document.encoding;
      }
      const estimatedHeight = this.EstimatedReaderTextHeight();
      const decoded = saved === undefined ? undefined : new ReaderTextLocatorPolicy().decode(saved.locator,
        estimatedHeight);
      this.readerScrollValue = decoded === undefined ? 0 :
        (decoded.versioned ? decoded.fraction * estimatedHeight : decoded.legacyPixels);
      if (this.readerContent.length > 0) {
        this.RebuildReaderPages(undefined, decoded?.fraction ?? 0);
      } else {
        // 网络书源目录初次加载时没有正文，不能提前生成空白页并写入虚假阅读进度。
        this.readerTextPages = [];
        this.readerTextPageIndex = 0;
      }
      if (this.ReaderUsesContinuousText() && this.readerScrollValue > 0) {
        setTimeout(() => {
          try {
            const offset = this.ReaderUsesWindowedContinuousText() ?
              this.ReaderContinuousOffsetBefore(this.readerChapterIndex) + this.readerScrollValue :
              this.readerScrollValue;
            this.readerScroller.scrollTo({ xOffset: 0, yOffset: offset, animation: false });
          } catch (_) {
          }
        }, 0);
      }
    } else if (document.kind === 'comic' && document.imageUris.length > 0) {
      if (saved !== undefined) {
        this.comicPageIndex = Math.max(0, Math.min(document.imageUris.length - 1, Math.floor(saved.locator)));
      }
      if (this.currentBook !== undefined) {
        const restoredProgress = Math.round((this.comicPageIndex + 1) * 100 / document.imageUris.length);
        const progressChanged = this.currentBook.progress !== restoredProgress;
        this.currentBook.progress = restoredProgress;
        if (progressChanged) this.ScheduleReaderProgressSave();
      }
    }
  }

  /** 在书源章节标题发生变化时，按章节序号和全书进度恢复最接近的位置。 */
  private FindSavedReaderChapterIndex(chapters: ReaderChapter[], saved: ReadingProgress): number {
    const exactIndex = chapters.findIndex((chapter: ReaderChapter) => chapter.title === saved.chapterTitle);
    if (exactIndex >= 0) return exactIndex;
    const savedOrdinal = this.ReaderChapterOrdinal(saved.chapterTitle);
    if (savedOrdinal > 0) {
      const ordinalIndex = chapters.findIndex((chapter: ReaderChapter) =>
        this.ReaderChapterOrdinal(chapter.title) === savedOrdinal);
      if (ordinalIndex >= 0) return ordinalIndex;
    }
    if (saved.percentage > 0) {
      return Math.max(0, Math.min(chapters.length - 1,
        Math.floor(saved.percentage * chapters.length / 100)));
    }
    return -1;
  }

  /** 提取“第N章”中的章节序号，不匹配时返回零。 */
  private ReaderChapterOrdinal(title: string): number {
    const match = title.match(/第\s*(\d+)\s*[章节回卷]/);
    if (match === null || match.length < 2) return 0;
    const value = Number(match[1]);
    return Number.isSafeInteger(value) && value > 0 ? value : 0;
  }

  /**
   * 判断当前文本是否使用纵向连续阅读。
   *
   * 本地、远程和网络书源文本统一采用连续阅读，避免章节内分页让竖向阅读停在局部内容。
   * PDF和漫画继续使用各自的原生分页与画廊交互。
   */
  private ReaderUsesContinuousText(): boolean {
    return !this.pdfMode && this.comicImageUris.length === 0 &&
      (this.currentBook?.origin === 'local' || this.currentBook?.origin === 'source' ||
        this.currentBook?.origin === 'remote' ||
        this.readerSettings.pageTurnMode === 'scroll');
  }

  /**
   * 判断当前图书是否使用章节窗口连续排版。
   *
   * 所有滚动文本通过同一个滚动容器连续展示相邻章节，使滚动距离完全由手势决定。
   */
  private ReaderUsesWindowedContinuousText(): boolean {
    return this.ReaderUsesContinuousText() && this.readerChapters.length > 0;
  }

  /** 初始化当前章节前后有限数量的连续排版窗口。 */
  private PrepareReaderContinuousWindow(index: number): void {
    if (this.readerChapters.length === 0) {
      this.readerContinuousStartIndex = 0;
      this.readerContinuousEndIndex = 0;
      return;
    }
    this.readerContinuousStartIndex = Math.max(0, index - 1);
    // 与本地缓存策略保持一致，当前章后的20章直接挂载到连续阅读窗口。
    this.readerContinuousEndIndex = Math.min(this.readerChapters.length - 1,
      index + this.readerChapterPrefetchCount);
    this.EnsureContinuousReaderContent(this.readerContinuousStartIndex, this.readerContinuousEndIndex);
  }

  /** 以目标章节作为窗口起点，确保目录跳转精确落在章节标题。 */
  private PrepareReaderContinuousWindowAtChapter(index: number): void {
    if (this.readerChapters.length === 0) return;
    this.readerContinuousStartIndex = Math.max(0, Math.min(index, this.readerChapters.length - 1));
    this.readerContinuousEndIndex = Math.min(this.readerChapters.length - 1,
      this.readerContinuousStartIndex + this.readerChapterPrefetchCount);
    this.EnsureContinuousReaderContent(this.readerContinuousStartIndex, this.readerContinuousEndIndex);
  }

  /** 返回当前连续排版窗口中的章节序号。 */
  private ReaderContinuousWindowIndexes(): number[] {
    const indexes: number[] = [];
    for (let index = this.readerContinuousStartIndex; index <= this.readerContinuousEndIndex; index++) {
      if (index >= 0 && index < this.readerChapters.length) indexes.push(index);
    }
    return indexes;
  }

  /** 按需加载窗口内的大型TXT磁盘分块。 */
  private EnsureContinuousReaderContent(start: number, end: number): void {
    let changed = false;
    for (let index = start; index <= end; index++) {
      const chapter = this.readerChapters[index];
      if (chapter === undefined || chapter.content.length > 0 ||
        !chapter.resourceUri?.startsWith('txt-index:')) continue;
      try {
        chapter.content = this.readerContentLoader.loadIndexedTextChapter(chapter.resourceUri);
        changed = true;
      } catch (_) {
      }
    }
    if (changed) this.readerChapters = this.readerChapters.slice();
  }

  /** 估算指定章节的纵向排版高度。 */
  private EstimatedReaderChapterHeight(index: number): number {
    const content = this.readerChapters[index]?.content ?? '';
    const fontSize = Math.max(12, this.ReaderScaledFontSize(this.readerSettings.fontSize));
    const contentWidth = Math.max(120, this.readerViewportWidth - this.ReaderContentHorizontalPadding() * 2);
    const charactersPerLine = Math.max(8, Math.floor(contentWidth / fontSize));
    const estimatedLineCount = Math.max(1, content.length / charactersPerLine);
    return estimatedLineCount * this.ReaderScaledFontSize(this.readerSettings.fontSize) *
      this.readerSettings.lineHeight + 120;
  }

  /** 估算连续窗口起点到指定章节之前的累计高度。 */
  private ReaderContinuousOffsetBefore(index: number): number {
    let offset = 0;
    for (let cursor = this.readerContinuousStartIndex; cursor < index; cursor++) {
      offset += this.EstimatedReaderChapterHeight(cursor);
    }
    return offset;
  }

  /** 返回当前章节位置在活动滚动容器中的绝对偏移。 */
  private ReaderAbsoluteScrollOffset(): number {
    return this.ReaderUsesWindowedContinuousText() ?
      this.ReaderContinuousOffsetBefore(this.readerChapterIndex) + this.readerScrollValue :
      this.readerScrollValue;
  }

  /** 根据正文可见章节更新目录状态和服务端阅读进度。 */
  private UpdateContinuousReaderChapter(index: number): void {
    if (!this.ReaderUsesWindowedContinuousText() || index === this.readerChapterIndex ||
      index < this.readerContinuousStartIndex || index > this.readerContinuousEndIndex) return;
    // 首次布局和进度恢复会同时挂载相邻章节，没有真实滑动方向时不得改写当前章节。
    if (this.readerScrollDirection === 0) return;
    if ((this.readerScrollDirection > 0 && index < this.readerChapterIndex) ||
      (this.readerScrollDirection < 0 && index > this.readerChapterIndex)) return;
    this.readerChapterIndex = index;
    this.readerContent = this.readerChapters[index]?.content ?? '';
    this.readerScrollValue = this.readerScrollDirection < 0 ? this.EstimatedReaderChapterHeight(index) * 0.99 : 0;
    if (this.currentBook !== undefined) {
      this.currentBook.progress = Math.max(0, Math.min(99,
        Math.round((index + this.CurrentTextChapterFraction()) * 100 / this.readerChapters.length)));
    }
    this.ScheduleReaderProgressSave();
    this.ScheduleReaderChapterPrefetch();
  }

  /** 在连续窗口到达边缘时追加相邻章节，保持当前像素位置不变。 */
  private ExtendReaderContinuousWindow(direction: number): void {
    if (!this.ReaderUsesWindowedContinuousText() || this.readerChapters.length === 0) return;
    if (direction > 0) {
      const end = Math.min(this.readerChapters.length - 1,
        this.readerContinuousEndIndex + this.readerChapterPrefetchCount);
      if (end === this.readerContinuousEndIndex) return;
      this.EnsureContinuousReaderContent(this.readerContinuousEndIndex + 1, end);
      this.readerContinuousEndIndex = end;
      return;
    }
    const start = Math.max(0, this.readerContinuousStartIndex - 1);
    if (start === this.readerContinuousStartIndex) return;
    let addedHeight = 0;
    this.EnsureContinuousReaderContent(start, this.readerContinuousStartIndex - 1);
    for (let index = start; index < this.readerContinuousStartIndex; index++) {
      addedHeight += this.EstimatedReaderChapterHeight(index);
    }
    this.readerContinuousStartIndex = start;
    setTimeout(() => {
      try {
        this.readerScroller.scrollBy(0, addedHeight);
      } catch (_) {
      }
    }, 0);
  }

  /**
   * 在滚动到正文边界后自动加载相邻章节。
   *
   * @param direction 阅读方向，负数表示上一章，正数表示下一章。
   */
  private ContinueReaderScroll(direction: number): void {
    if (this.ReaderUsesWindowedContinuousText()) {
      if (direction > 0 && this.readerContinuousEndIndex >= this.readerChapters.length - 1) {
        // 连续窗口已经覆盖末章时，边界回调负责把全书进度正式结算到末尾。
        const lastIndex = this.readerChapters.length - 1;
        this.readerChapterIndex = lastIndex;
        this.readerContent = this.readerChapters[lastIndex]?.content ?? '';
        this.readerScrollValue = this.EstimatedReaderChapterHeight(lastIndex);
        if (this.currentBook !== undefined) this.currentBook.progress = 100;
        this.ScheduleReaderProgressSave();
        return;
      }
      this.ExtendReaderContinuousWindow(direction);
      return;
    }
    if (!this.ReaderUsesContinuousText() || this.readerScrollChapterLoading ||
      Date.now() < this.readerScrollContinuationBlockedUntil ||
      this.readerScrollDirection !== direction || this.readerChapters.length === 0) return;
    const target = this.readerChapterIndex + direction;
    if (target < 0 || target >= this.readerChapters.length) {
      this.readerScrollDirection = 0;
      return;
    }
    this.readerScrollChapterLoading = true;
    // 边界回调可能在新章节尚未稳定布局时连续触发，短暂冷却可避免一次滑动跳过多章。
    this.readerScrollContinuationBlockedUntil = Date.now() + 900;
    const previous = this.readerChapterIndex;
    this.ChangeReaderSection(direction).then(() => {
      if (this.readerChapterIndex === previous) return;
      this.readerScrollValue = direction < 0 ? this.EstimatedReaderTextHeight() * 0.99 : 0;
      setTimeout(() => {
        try {
          this.readerScroller.scrollEdge(direction < 0 ? Edge.Bottom : Edge.Top);
        } catch (_) {
        }
      }, 0);
    }).catch((error: Error) => {
      this.readerStatus = error.message;
    }).finally(() => {
      this.readerScrollDirection = 0;
      this.readerScrollChapterLoading = false;
    });
  }

  private async ChangeReaderSection(offset: number): Promise<void> {
    this.StopReaderSpeech();
    this.ClearReaderSelection();
    this.CancelReaderChapterPrefetch();
    if (this.pdfMode) {
      try {
        const target = Math.max(0, Math.min(this.pdfPageCount - 1, this.pdfPageIndex + offset));
        this.pdfController.goToPage(target);
        this.pdfPageIndex = target;
      } catch (error) {
        this.readerStatus = error instanceof Error ? error.message : 'PDF翻页失败';
      }
      this.ScheduleReaderProgressSave();
      return;
    }
    if (this.comicImageUris.length > 0) {
      const step = this.readerSettings.comicPageMode === 'double' ? 2 : 1;
      const target = this.comicPageIndex + offset * step;
      if (this.currentBook?.origin === 'source' && target >= this.comicImageUris.length &&
        this.readerChapterIndex + 1 < this.readerChapters.length) {
        this.readerChapterIndex++;
        await this.LoadSourceChapter();
      } else if (this.currentBook?.origin === 'source' && target < 0 && this.readerChapterIndex > 0) {
        this.readerChapterIndex--;
        await this.LoadSourceChapter();
        this.comicPageIndex = Math.max(0, this.comicImageUris.length - step);
      } else {
        this.comicPageIndex = Math.max(0, Math.min(this.comicImageUris.length - 1, target));
        await this.EnsureSourceComicWindow(this.comicPageIndex);
      }
      if (this.currentBook !== undefined) {
        this.currentBook.progress = Math.round((this.comicPageIndex + 1) * 100 / this.comicImageUris.length);
      }
      this.ScheduleReaderProgressSave();
      return;
    }
    if (this.readerChapters.length === 0) return;
    this.readerChapterIndex = Math.max(0, Math.min(this.readerChapters.length - 1,
      this.readerChapterIndex + offset));
    this.readerContent = this.readerChapters[this.readerChapterIndex].content;
    this.readerScrollValue = 0;
    this.RebuildReaderPages(0);
    try {
      this.readerScroller.scrollEdge(Edge.Top);
    } catch (_) {
    }
    if (this.currentBook?.origin === 'source' && this.readerContent.length === 0) {
      await this.LoadSourceChapter();
    } else if (this.readerContent.length === 0 &&
      this.readerChapters[this.readerChapterIndex].resourceUri?.startsWith('txt-index:')) {
      this.LoadIndexedTextChapter();
    }
    if (this.currentBook !== undefined) {
      this.currentBook.progress = Math.round((this.readerChapterIndex + 1) * 100 / this.readerChapters.length);
    }
    this.ScheduleReaderProgressSave();
    this.ScheduleReaderChapterPrefetch();
  }

  private async ChangeReaderPageOrSection(offset: number): Promise<void> {
    if (this.pdfMode || this.comicImageUris.length > 0 || this.ReaderUsesContinuousText()) {
      await this.ChangeReaderSection(offset);
      return;
    }
    const target = this.readerTextPageIndex + offset;
    if (target >= 0 && target < this.readerTextPages.length) {
      this.UpdateReaderTextPage(target);
      return;
    }
    const previousChapter = this.readerChapterIndex;
    await this.ChangeReaderSection(offset);
    if (this.readerChapterIndex === previousChapter || this.readerTextPages.length === 0) return;
    this.UpdateReaderTextPage(offset < 0 ? this.readerTextPages.length - 1 : 0);
  }

  private ChangeReaderPageFromFooter(offset: number): void {
    this.ChangeReaderPageOrSection(offset).catch((error: Error) => {
      this.readerStatus = error.message;
    }).finally(() => {
      // 页脚按钮位于正文容器内，父级并行点击手势会切换工具栏；操作完成后恢复沉浸阅读。
      setTimeout(() => this.readerControlsVisible = false, 0);
    });
  }

  /**
   * 取消当前图书的章节预取任务。
   */
  private CancelReaderChapterPrefetch(): void {
    this.readerChapterPrefetchRevision++;
    this.activeReaderChapterPrefetchCancellation?.cancel();
    this.activeReaderChapterPrefetchCancellation = undefined;
  }

  /**
   * 调度当前章节和后续二十章的后台预取。
   */
  private ScheduleReaderChapterPrefetch(): void {
    this.CancelReaderChapterPrefetch();
    const book = this.currentBook;
    if (!this.readerVisible || book === undefined || book.origin === 'local' ||
      this.readerChapters.length === 0 || this.pdfMode || this.comicImageUris.length > 0) return;
    const startIndex = this.readerChapterIndex;
    const revision = this.readerChapterPrefetchRevision;
    const cancellation = new DownloadCancellationToken();
    this.activeReaderChapterPrefetchCancellation = cancellation;
    setTimeout(() => {
      this.PrefetchReaderChapterWindow(book, startIndex, revision, cancellation).catch((error: Error) => {
        if (!cancellation.isCancelled()) console.warn(`Reader chapter prefetch failed: ${error.message}`);
      }).finally(() => {
        if (this.activeReaderChapterPrefetchCancellation === cancellation) {
          this.activeReaderChapterPrefetchCancellation = undefined;
        }
      });
    }, 0);
  }

  /**
   * 将当前章节及后续章节填充到内存，网络书源正文同时由后端持久缓存。
   *
   * @param book 当前图书。
   * @param startIndex 当前章节序号。
   * @param revision 预取任务版本。
   * @param cancellation 独立取消令牌。
   */
  private async PrefetchReaderChapterWindow(book: Book, startIndex: number, revision: number,
    cancellation: DownloadCancellationToken): Promise<void> {
    const firstIndex = Math.max(0, startIndex);
    const endIndex = Math.min(this.readerChapters.length - 1, startIndex + this.readerChapterPrefetchCount);
    const source = book.origin === 'source' && book.format === 'unknown' ?
      this.bookSources.find((value: BookSource) => value.bookSourceUrl === book.sourceId) : undefined;
    const api = source === undefined ? undefined :
      new BookSourceRuntimeReaderApi(this.ActiveServiceUrl(), this.authManager);
    const context = this.ApplicationContext();
    const localCacheEnabled = source !== undefined && context !== undefined &&
      this.SourceBookLocalCacheEnabled(book);
    let changed = false;
    for (let index = firstIndex; index <= endIndex; index++) {
      if (!this.ReaderChapterPrefetchCurrent(book, revision, cancellation)) return;
      const chapter = this.readerChapters[index];
      if (chapter === undefined) continue;
      if (chapter.content.trim().length > 0) {
        // 当前或已阅读章节可能先进入内存，加入书架后仍必须补写到账户隔离的持久缓存。
        if (localCacheEnabled && context !== undefined) {
          await this.bookSourceLocalCache.putChapter(context, this.localAccountScope,
            source?.bookSourceUrl ?? book.sourceId, book.resourceUri, chapter, index,
            { kind: 'text', text: chapter.content, imageUris: [], remoteImageUrls: [] });
        }
        continue;
      }
      const resourceUri = chapter.resourceUri ?? '';
      if (resourceUri.startsWith('txt-index:')) {
        chapter.content = this.readerContentLoader.loadIndexedTextChapter(resourceUri);
        changed = true;
      } else if (source !== undefined && api !== undefined) {
        try {
          let content: SourceChapterContent | undefined = undefined;
          if (localCacheEnabled && context !== undefined) {
            content = await this.bookSourceLocalCache.chapter(context, this.localAccountScope,
              source.bookSourceUrl, book.resourceUri, chapter, index);
          }
          if (content === undefined) {
            content = await api.content(source.bookSourceUrl, chapter, index, cancellation);
            if (localCacheEnabled && context !== undefined && content.kind === 'text') {
              await this.bookSourceLocalCache.putChapter(context, this.localAccountScope,
                source.bookSourceUrl, book.resourceUri, chapter, index, content);
            }
          }
          if (!this.ReaderChapterPrefetchCurrent(book, revision, cancellation)) return;
          // 漫画章节仍由前台加载图片窗口，避免正文命中后跳过图片初始化。
          if (content.kind === 'text') {
            chapter.content = content.text;
            changed = true;
            // 每章完成就刷新连续阅读窗口，不等后续20章全部请求完成。
            this.readerChapters = this.readerChapters.slice();
          }
        } catch (error) {
          if (cancellation.isCancelled()) return;
          // 单章失败不阻断后续章节，前台打开时仍可再次请求。
          if (error instanceof Error) console.warn(`Chapter ${index} prefetch failed: ${error.message}`);
        }
      }
      // 为页面输入、滚动与动画留出执行窗口，避免连续章节解析阻塞前台交互。
      await new Promise<void>((resolve: () => void) => setTimeout(resolve, 40));
    }
    if (changed && this.ReaderChapterPrefetchCurrent(book, revision, cancellation)) {
      this.readerChapters = this.readerChapters.slice();
    }
  }

  private ReaderChapterPrefetchCurrent(book: Book, revision: number,
    cancellation: DownloadCancellationToken): boolean {
    return !cancellation.isCancelled() && revision === this.readerChapterPrefetchRevision &&
      this.readerVisible && this.currentBook === book;
  }

  private async LoadSourceChapter(): Promise<void> {
    this.activeComicPageCancellation?.cancel();
    this.activeSourceChapterCancellation?.cancel();
    const book = this.currentBook;
    const chapter = this.readerChapters[this.readerChapterIndex];
    if (book === undefined || chapter === undefined || book.origin !== 'source') return;
    const source = this.bookSources.find((value: BookSource) => value.bookSourceUrl === book.sourceId);
    if (source === undefined) throw new Error('图书对应的书源已被移除');
    const context = this.ApplicationContext();
    if (context === undefined) throw new Error('无法获取应用上下文');
    if (source.bookSourceType !== 2 && chapter.content.trim().length > 0) {
      this.ApplySourceTextChapter(book, chapter, chapter.content);
      return;
    }
    const revision = ++this.sourceChapterLoadRevision;
    const cancellation = new DownloadCancellationToken();
    this.activeSourceChapterCancellation = cancellation;
    if (source.bookSourceType === 2) {
      this.comicImageUris = [];
      this.sourceComicFailedIndexes = [];
      this.readerContent = '';
      this.comicPageIndex = 0;
    }
    this.readerStatus = `正在加载${chapter.title}…`;
    try {
      if (source.bookSourceType !== 2 && this.SourceBookLocalCacheEnabled(book)) {
        const cached = await this.bookSourceLocalCache.chapter(context, this.localAccountScope,
          source.bookSourceUrl, book.resourceUri, chapter, this.readerChapterIndex);
        if (revision !== this.sourceChapterLoadRevision || this.currentBook !== book ||
          this.readerChapters[this.readerChapterIndex] !== chapter) return;
        if (cached !== undefined) {
          this.ApplySourceTextChapter(book, chapter, cached.text);
          return;
        }
      }
      const content: SourceChapterContent = await new BookSourceRuntimeReaderApi(this.ActiveServiceUrl(),
        this.authManager).content(source.bookSourceUrl, chapter, this.readerChapterIndex, cancellation);
      if (revision !== this.sourceChapterLoadRevision || this.currentBook !== book ||
        this.readerChapters[this.readerChapterIndex] !== chapter) return;
      if (content.kind === 'text' && this.SourceBookLocalCacheEnabled(book)) {
        await this.bookSourceLocalCache.putChapter(context, this.localAccountScope,
          source.bookSourceUrl, book.resourceUri, chapter, this.readerChapterIndex, content);
      }
      chapter.content = content.text;
      this.readerChapters = this.readerChapters.slice();
      this.readerContent = content.text;
      this.sourceComicRemoteUrls = content.remoteImageUrls;
      this.comicImageUris = content.kind === 'comic' ? new Array(content.remoteImageUrls.length).fill('') :
        content.imageUris;
      const saved = this.readingProgress.find((value: ReadingProgress) => value.bookId === book.id &&
        value.chapterTitle === chapter.title);
      this.comicPageIndex = content.kind === 'comic' && saved !== undefined ?
        Math.max(0, Math.min(content.remoteImageUrls.length - 1, Math.floor(saved.locator))) : 0;
      if (content.kind === 'comic') {
        this.readerInfo = `${this.readerChapters.length}话 · ${content.remoteImageUrls.length}页`;
        this.readerStatus = '正在缓存当前漫画页…';
        await this.EnsureSourceComicWindow(this.comicPageIndex);
      } else {
        this.ApplySourceTextChapter(book, chapter, content.text);
      }
    } catch (error) {
      if (revision !== this.sourceChapterLoadRevision) return;
      if (cancellation.isCancelled()) {
        if (this.currentBook === book && this.readerChapters[this.readerChapterIndex] === chapter) {
          this.readerStatus = '章节加载已中断，请点击重试';
        }
        return;
      }
      this.readerStatus = error instanceof Error ? error.message : '章节正文加载失败';
    } finally {
      if (this.activeSourceChapterCancellation === cancellation) this.activeSourceChapterCancellation = undefined;
    }
  }

  /** 将内存或磁盘命中的书源文本章节应用到阅读器，不再发起临时网络请求。 */
  private ApplySourceTextChapter(book: Book, chapter: ReaderChapter, text: string): void {
    chapter.content = text;
    this.readerChapters = this.readerChapters.slice();
    this.readerContent = text;
    this.sourceComicRemoteUrls = [];
    this.comicImageUris = [];
    const saved = this.readingProgress.find((value: ReadingProgress) => value.bookId === book.id &&
      value.chapterTitle === chapter.title);
    const decoded = saved === undefined ? undefined : new ReaderTextLocatorPolicy().decode(saved.locator,
      this.EstimatedReaderTextHeight());
    this.RebuildReaderPages(undefined, decoded?.fraction ?? 0);
    this.readerStatus = '';
  }

  private async EnsureSourceComicWindow(position: number): Promise<void> {
    const book = this.currentBook;
    if (book?.origin !== 'source' || this.sourceComicRemoteUrls.length === 0) return;
    const source = this.bookSources.find((value: BookSource) => value.bookSourceUrl === book.sourceId);
    const context = this.ApplicationContext();
    if (source === undefined || context === undefined) return;
    const revision = this.sourceChapterLoadRevision;
    const positions: number[] = [];
    const preload = Math.min(5, Math.max(1, this.readerSettings.comicPreload));
    for (let offset = -1; offset <= preload + (this.readerSettings.comicPageMode === 'double' ? 1 : 0); offset++) {
      const displayPosition = position + offset;
      if (displayPosition < 0 || displayPosition >= this.comicImageUris.length) continue;
      const fileIndex = this.ComicFileIndex(displayPosition);
      if (!this.IsAvailableComicUri(this.comicImageUris[fileIndex]) && !positions.includes(fileIndex)) {
        positions.push(fileIndex);
      }
    }
    if (positions.length === 0) {
      this.readerStatus = '';
      return;
    }
    const loading = this.comicImageUris.slice();
    positions.forEach((fileIndex: number) => loading[fileIndex] = '');
    this.comicImageUris = loading;
    this.sourceComicFailedIndexes = this.sourceComicFailedIndexes
      .filter((fileIndex: number) => !positions.includes(fileIndex));
    const urls = positions.map((index: number) => this.sourceComicRemoteUrls[index]);
    this.activeComicPageCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeComicPageCancellation = cancellation;
    let cached: string[] = [];
    try {
      cached = await this.bookSourceReaderEngine.cacheComicPages(context, source, urls, cancellation);
    } catch (error) {
      if (revision === this.sourceChapterLoadRevision && this.currentBook === book) {
        const failed = this.sourceComicFailedIndexes.slice();
        positions.forEach((fileIndex: number) => {
          if (!failed.includes(fileIndex)) failed.push(fileIndex);
        });
        this.sourceComicFailedIndexes = failed;
      }
      if (cancellation.isCancelled()) return;
      if (error instanceof Error) throw error;
      throw new Error('漫画页缓存失败');
    } finally {
      if (this.activeComicPageCancellation === cancellation) this.activeComicPageCancellation = undefined;
    }
    if (revision !== this.sourceChapterLoadRevision || this.currentBook !== book) return;
    const next = this.comicImageUris.slice();
    positions.forEach((fileIndex: number, index: number) => next[fileIndex] = cached[index]);
    this.comicImageUris = next;
    this.readerStatus = '';
  }

  private IsSourceComicPageFailed(position: number): boolean {
    return this.sourceComicFailedIndexes.includes(this.ComicFileIndex(position));
  }

  private RetrySourceComicPage(position: number): void {
    const fileIndex = this.ComicFileIndex(position);
    this.sourceComicFailedIndexes = this.sourceComicFailedIndexes
      .filter((value: number) => value !== fileIndex);
    this.readerStatus = '正在重新缓存漫画页…';
    this.EnsureSourceComicWindow(position).catch((error: Error) => {
      this.readerStatus = error.message;
    });
  }

  private IsAvailableComicUri(uri: string): boolean {
    if (uri.length === 0) return false;
    if (!uri.startsWith('file://')) return true;
    try {
      return fs.accessSync(uri.substring('file://'.length));
    } catch (_) {
      return false;
    }
  }

  private async SelectReaderChapter(index: number): Promise<void> {
    if (index < 0 || index >= this.readerChapters.length) return;
    this.StopReaderSpeech();
    this.ClearReaderSelection();
    this.CancelReaderChapterPrefetch();
    this.readerChapterIndex = index;
    this.readerScrollDirection = 0;
    this.sourceChapterLoadRevision++;
    this.readerContent = this.readerChapters[index].content;
    this.PrepareReaderContinuousWindowAtChapter(index);
    if (this.currentBook?.origin === 'source') this.comicImageUris = [];
    this.readerScrollValue = 0;
    this.readerCatalogVisible = false;
    this.readerControlsVisible = false;
    this.readerSearchVisible = false;
    this.readerMoreVisible = false;
    if (this.currentBook?.origin === 'source' && this.readerContent.length === 0) {
      await this.LoadSourceChapter();
    } else if (this.readerContent.length === 0 &&
      this.readerChapters[this.readerChapterIndex].resourceUri?.startsWith('txt-index:')) {
      this.LoadIndexedTextChapter();
    }
    // 异步正文加载完成后重新建立相邻章节窗口，目录跳转位置以目标章节标题为锚点。
    this.PrepareReaderContinuousWindowAtChapter(index);
    this.readerChapterIndex = index;
    this.readerContent = this.readerChapters[index].content;
    this.readerScrollValue = 0;
    // 重建滚动容器，避免目录切换后 ArkUI 复用上一章节的像素偏移。
    this.readerScroller = new Scroller();
    this.readerScrollEpoch++;
    if (this.currentBook !== undefined) {
      this.currentBook.progress = Math.max(0, Math.min(99,
        Math.round(index * 100 / Math.max(1, this.readerChapters.length))));
    }
    // 目录跳转后必须按目标章节重新分页，否则分页模式仍会显示旧章节缓存。
    this.RebuildReaderPages();
    if (this.ReaderUsesWindowedContinuousText()) {
      setTimeout(() => {
        try {
          this.readerScroller.scrollTo({
            xOffset: 0,
            yOffset: 0,
            animation: false
          });
        } catch (_) {
        }
      }, 0);
      // 等待新章节完成排版后再次校正，避免复用滚动容器时保留旧章节偏移。
      setTimeout(() => {
        try {
          this.readerScroller.scrollEdge(Edge.Top);
        } catch (_) {
        }
      }, 100);
    }
    this.ScheduleReaderProgressSave();
    this.ScheduleReaderChapterPrefetch();
  }

  private LoadIndexedTextChapter(): void {
    const chapter = this.readerChapters[this.readerChapterIndex];
    if (chapter?.resourceUri === undefined || !chapter.resourceUri.startsWith('txt-index:')) return;
    this.readerStatus = `正在从磁盘读取${chapter.title}…`;
    try {
      chapter.content = this.readerContentLoader.loadIndexedTextChapter(chapter.resourceUri);
      this.readerChapters = this.readerChapters.slice();
      this.readerContent = chapter.content;
      this.RebuildReaderPages();
      this.readerStatus = '';
    } catch (error) {
      this.readerStatus = error instanceof Error ? error.message : 'TXT章节读取失败';
    }
  }

  private CurrentReaderBookmarks(): ReaderBookmark[] {
    const bookId = this.currentBook?.id ?? '';
    return this.readerBookmarks.filter((value: ReaderBookmark) => value.bookId === bookId)
      .sort((left: ReaderBookmark, right: ReaderBookmark) => right.createdAt - left.createdAt);
  }

  private async OpenReaderBookmark(bookmark: ReaderBookmark): Promise<void> {
    if (this.currentBook === undefined || bookmark.bookId !== this.currentBook.id) return;
    if (this.pdfMode) {
      const target = Math.max(0, Math.min(this.pdfPageCount - 1, Math.floor(bookmark.locator)));
      this.pdfController.goToPage(target);
      this.pdfPageIndex = target;
    } else if (this.comicImageUris.length > 0) {
      this.comicPageIndex = Math.max(0, Math.min(this.comicImageUris.length - 1, Math.floor(bookmark.locator)));
    } else {
      const index = this.readerChapters.findIndex((chapter: ReaderChapter) => chapter.title === bookmark.chapterTitle);
      if (index >= 0) {
        await this.SelectReaderChapter(index);
        const estimatedHeight = this.EstimatedReaderTextHeight();
        const decoded = new ReaderTextLocatorPolicy().decode(bookmark.locator, estimatedHeight);
        const fraction = Math.max(0, Math.min(0.999, decoded.fraction));
        if (this.ReaderUsesContinuousText()) {
          this.readerScrollValue = decoded.versioned ? fraction * estimatedHeight : decoded.legacyPixels;
          setTimeout(() => {
            try {
              this.readerScroller.scrollTo({
                xOffset: 0,
                yOffset: this.ReaderAbsoluteScrollOffset(),
                animation: false
              });
            } catch (_) {
            }
          }, 0);
        } else {
          this.readerTextPageIndex = Math.max(0, Math.min(this.readerTextPages.length - 1,
            Math.floor(fraction * this.readerTextPages.length)));
          this.UpdateReaderTextPage(this.readerTextPageIndex);
        }
      }
    }
    this.readerCatalogVisible = false;
  }

  private CloseReader(skipShelfPrompt: boolean = false): void {
    if (!skipShelfPrompt && this.ShouldPromptReaderShelf()) {
      this.readerControlsVisible = false;
      this.readerShelfPromptVisible = true;
      return;
    }
    const returnToDetail = this.readerReturnToDetail && this.detailBook !== undefined;
    this.readerShelfPromptVisible = false;
    this.readerOpenRevision++;
    this.readerSearchRevision++;
    this.readerSearchBusy = false;
    this.readerOpeningLoader?.cancel();
    this.readerOpeningLoader = undefined;
    this.activeReaderTicketCancellation?.cancel();
    this.activeReaderTicketCancellation = undefined;
    this.activeComicPageCancellation?.cancel();
    this.activeComicPageCancellation = undefined;
    this.activeSourceChapterCancellation?.cancel();
    this.activeSourceChapterCancellation = undefined;
    this.CancelReaderChapterPrefetch();
    this.activeSourceCatalogCancellation?.cancel();
    this.activeSourceCatalogCancellation = undefined;
    this.activeOpdsCatalogCancellation?.cancel();
    this.activeOpdsCatalogCancellation = undefined;
    this.StopReaderSpeech();
    this.ClearReaderViewportTimer();
    this.ApplyReaderOrientation('system');
    this.sourceChapterLoadRevision++;
    this.ClearScheduledReaderProgressSave();
    this.SaveCurrentProgress();
    this.FinishReadingSession();
    this.StopReaderPersistenceTimer();
    if (this.pdfMode) {
      try {
        this.pdfController.releaseDocument();
      } catch (_) {
      }
    }
    this.readerVisible = false;
    this.ApplyReaderSystemBars(false);
    this.readerControlsVisible = false;
    this.readerSettingsVisible = false;
    this.readerSearchVisible = false;
    this.readerMoreVisible = false;
    this.readerCatalogVisible = false;
    this.readerAnnotationVisible = false;
    this.currentBook = undefined;
    this.readerContent = '';
    this.readerChapters = [];
    this.readerTextPages = [];
    this.readerTextPageIndex = 0;
    this.comicImageUris = [];
    this.sourceComicFailedIndexes = [];
    this.sourceComicRemoteUrls = [];
    this.pdfMode = false;
    this.readerStatus = '';
    this.readerInfo = '';
    this.ClearReaderSelection();
    this.readerContentLoader.cleanup();
    this.readerReturnToDetail = false;
    if (returnToDetail) {
      this.bookDetailVisible = true;
    } else {
      this.RestoreBookSourceSearchReturnPosition();
    }
  }

  private UpdateReaderProgress(offset: number): void {
    if (this.ReaderUsesWindowedContinuousText()) {
      try {
        // 连续章节容器中的滚动坐标是全窗口绝对值，换算为当前章节内偏移后再计算进度。
        // Scroller返回物理像素，正文高度按vp估算，先统一单位再计算章节内位置。
        const absoluteOffset = Math.max(0,
          this.getUIContext().px2vp(this.readerScroller.currentOffset().yOffset));
        this.readerScrollValue = Math.max(0,
          absoluteOffset - this.ReaderContinuousOffsetBefore(this.readerChapterIndex));
      } catch (_) {
        this.readerScrollValue = Math.max(0, this.readerScrollValue + offset);
      }
    } else {
      this.readerScrollValue = Math.max(0, this.readerScrollValue + offset);
    }
    if (this.readerContent.length > 0 && this.readerChapters.length > 0) {
      const estimatedLineCount = Math.max(1, this.readerContent.length / 22);
      const estimatedHeight = estimatedLineCount * this.ReaderScaledFontSize(this.readerSettings.fontSize) *
        this.readerSettings.lineHeight;
      const chapterFraction = Math.min(0.99, this.readerScrollValue / Math.max(1, estimatedHeight));
      const estimate = Math.round((this.readerChapterIndex + chapterFraction) * 100 / this.readerChapters.length);
      if (this.currentBook !== undefined) this.currentBook.progress = Math.max(0, Math.min(99, estimate));
    }
    this.ScheduleReaderProgressSave();
  }

  private UpdateReaderViewport(width: Object | undefined, height: Object | undefined): void {
    if (!this.readerVisible) return;
    const next = this.readerViewportPolicy.normalize(width, height);
    if (next === undefined || !this.readerViewportPolicy.changed(
      { width: this.readerViewportWidth, height: this.readerViewportHeight }, next)) return;
    this.readerViewportWidth = next.width;
    this.readerViewportHeight = next.height;
    this.ClearReaderViewportTimer();
    this.readerViewportTimer = setTimeout(() => {
      this.readerViewportTimer = -1;
      if (this.readerVisible) this.RebuildReaderPages();
    }, 120);
  }

  private ClearReaderViewportTimer(): void {
    if (this.readerViewportTimer < 0) return;
    clearTimeout(this.readerViewportTimer);
    this.readerViewportTimer = -1;
  }

  private RebuildReaderPages(bookPercentage?: number, restoredChapterFraction?: number): void {
    if (this.pdfMode || this.comicImageUris.length > 0 || this.readerChapters.length === 0) {
      this.readerTextPages = [];
      this.readerTextPageIndex = 0;
      return;
    }
    if (this.ReaderUsesContinuousText()) {
      this.readerTextPages = [];
      this.readerTextPageIndex = 0;
      const fraction = restoredChapterFraction !== undefined ? restoredChapterFraction :
        bookPercentage !== undefined ?
          bookPercentage * this.readerChapters.length / 100 - this.readerChapterIndex : undefined;
      if (fraction !== undefined) {
        this.readerScrollValue = Math.max(0, Math.min(0.999, fraction)) * this.EstimatedReaderTextHeight();
        setTimeout(() => {
          try {
            this.readerScroller.scrollTo({
              xOffset: 0,
              yOffset: this.ReaderAbsoluteScrollOffset(),
              animation: false
            });
          } catch (_) {
          }
        }, 0);
      }
      return;
    }
    const previousFraction = restoredChapterFraction !== undefined ?
      Math.max(0, Math.min(0.999, restoredChapterFraction)) : bookPercentage === undefined ?
      this.CurrentTextChapterFraction() :
      Math.max(0, Math.min(0.999, bookPercentage * this.readerChapters.length / 100 - this.readerChapterIndex));
    const chapter = this.readerChapters[this.readerChapterIndex];
    const sourceBlocks = (chapter.blocks ?? []).filter((block: ReaderBlock, index: number) =>
      !(index === 0 && block.kind === 'heading' && block.text.trim() === chapter.title.trim()));
    const fallbackBlock: ReaderBlock = {
      kind: 'paragraph', text: this.ReaderVisibleText(this.readerContent), uri: ''
    };
    const bodyBlocks: ReaderBlock[] = sourceBlocks.length > 0 ? sourceBlocks : [fallbackBlock];
    const headingBlock: ReaderBlock = {
      kind: 'heading', text: chapter.title.length > 0 ? chapter.title : '正文', uri: '', level: 1
    };
    const pagedBlocks: ReaderBlock[] = [headingBlock];
    bodyBlocks.forEach((block: ReaderBlock) => pagedBlocks.push(block));
    const paginationSettings = this.CopyReaderSettings(this.readerSettings.fontSize, this.readerSettings.lineHeight,
      this.ReaderContentHorizontalPadding(), this.readerSettings.brightness,
      this.readerSettings.theme, this.readerSettings.pageTurnMode);
    this.readerTextPages = this.readerPaginationPolicy.paginate(pagedBlocks, '',
      paginationSettings, { width: this.readerViewportWidth, height: this.readerViewportHeight },
      this.ReaderSystemFontScale());
    this.readerTextPageIndex = Math.max(0, Math.min(this.readerTextPages.length - 1,
      Math.floor(previousFraction * this.readerTextPages.length)));
    this.UpdateReaderTextPage(this.readerTextPageIndex);
  }

  private UpdateReaderTextPage(index: number): void {
    if (this.readerTextPages.length === 0 || this.readerChapters.length === 0) return;
    this.readerTextPageIndex = Math.max(0, Math.min(this.readerTextPages.length - 1, index));
    this.readerScrollValue = this.readerTextPageIndex;
    const chapterFraction = (this.readerTextPageIndex + 1) / this.readerTextPages.length;
    const estimate = Math.round((this.readerChapterIndex + chapterFraction) * 100 / this.readerChapters.length);
    if (this.currentBook !== undefined) this.currentBook.progress = Math.max(0, Math.min(99, estimate));
    this.ScheduleReaderProgressSave();
  }

  private TurnReaderTextPageBySwipe(offsetX: number): void {
    if (!Number.isFinite(offsetX) || Math.abs(offsetX) < 60) return;
    this.ChangeReaderPageOrSection(offsetX < 0 ? 1 : -1).catch((error: Error) => {
      this.readerStatus = error.message;
    });
  }

  private EstimatedReaderTextHeight(): number {
    const fontSize = Math.max(12, this.ReaderScaledFontSize(this.readerSettings.fontSize));
    const contentWidth = Math.max(120, this.readerViewportWidth - this.ReaderContentHorizontalPadding() * 2);
    const charactersPerLine = Math.max(8, Math.floor(contentWidth / fontSize));
    const estimatedLineCount = Math.max(1, this.readerContent.length / charactersPerLine);
    return estimatedLineCount * this.ReaderScaledFontSize(this.readerSettings.fontSize) *
      this.readerSettings.lineHeight;
  }

  private CurrentTextChapterFraction(): number {
    if (!this.ReaderUsesContinuousText() && this.readerTextPages.length > 0) {
      return Math.max(0, Math.min(0.999, (this.readerTextPageIndex + 1) / this.readerTextPages.length));
    }
    if (this.ReaderUsesWindowedContinuousText() &&
      this.readerChapterIndex === this.readerChapters.length - 1) {
      try {
        if (this.readerScroller.isAtEnd()) return 1;
      } catch (_) {
      }
    }
    return Math.max(0, Math.min(0.999, this.readerScrollValue /
      Math.max(1, this.EstimatedReaderTextHeight())));
  }

  private ToggleReaderSpeech(): void {
    if (this.readerSpeechActive) {
      this.StopReaderSpeech();
      return;
    }
    if (this.pdfMode || this.comicImageUris.length > 0) {
      this.readerSpeechStatus = '当前格式没有可朗读文本';
      return;
    }
    const chapterBlocks = this.readerChapters[this.readerChapterIndex]?.blocks ?? [];
    const fraction = this.ReaderUsesContinuousText() ? this.CurrentTextChapterFraction() : 0;
    const firstBlock = Math.max(0, Math.min(chapterBlocks.length,
      Math.floor(fraction * chapterBlocks.length)));
    // 连续阅读从当前可见位置附近开始，分页阅读只朗读当前页。
    const blocks = this.ReaderUsesContinuousText() ? chapterBlocks.slice(firstBlock) :
      (this.readerTextPages[this.readerTextPageIndex] ?? []);
    const firstCharacter = Math.max(0, Math.min(this.readerContent.length,
      Math.floor(fraction * this.readerContent.length)));
    const fallback = this.ReaderUsesContinuousText() ? this.readerContent.substring(firstCharacter) :
      blocks.map((block: ReaderBlock) => block.text).join('\n');
    const chunks = this.readerSpeechPolicy.chunks(this.readerSpeechPolicy.readableText(blocks, fallback));
    this.readerSpeechController.start(chunks, {
      onStatus: (status: string, active: boolean): void => {
        this.readerSpeechStatus = status;
        this.readerSpeechActive = active;
      }
    }).catch((error: Error) => {
      this.readerSpeechActive = false;
      this.readerSpeechStatus = error.message;
    });
  }

  private StopReaderSpeech(): void {
    if (!this.readerSpeechActive && this.readerSpeechStatus.length === 0) return;
    this.readerSpeechController.stop();
    this.readerSpeechActive = false;
  }

  private SaveCurrentProgress(): void {
    if (this.currentBook === undefined) return;
    const current = this.currentBook;
    const previous = this.readingProgress.find((value: ReadingProgress) => value.bookId === current.id);
    const tombstone = this.readingProgressTombstones
      .find((value: ReadingProgressTombstone) => value.bookId === current.id);
    const progress: ReadingProgress = {
      bookId: current.id,
      chapterTitle: this.pdfMode ? `第${this.pdfPageIndex + 1}页` :
        (this.comicImageUris.length > 0 ? `第${this.comicPageIndex + 1}页` :
        (this.readerChapters[this.readerChapterIndex]?.title ?? '正文')),
      locator: this.pdfMode ? this.pdfPageIndex :
        (this.comicImageUris.length > 0 ? this.comicPageIndex : this.CurrentReaderLocator()),
      percentage: current.progress,
      updatedAt: Date.now(),
      revision: previous?.revision ?? tombstone?.revision ?? 0,
      deleted: false
    };
    this.readingProgressTombstones = this.readingProgressTombstones
      .filter((value: ReadingProgressTombstone) => value.bookId !== current.id);
    this.readingProgress = this.readingProgress.filter((value: ReadingProgress) => value.bookId !== current.id)
      .concat(progress);
    this.books = this.books.map((book: Book) => book.id === current.id ? current : book);
    this.RememberRemoteBookProgress(current, progress.chapterTitle, progress.updatedAt);
    this.PersistReaderState();
    // 试读图书尚未进入服务端书架，只保存本地进度，避免创建无归属的远程阅读状态。
    if (this.books.some((book: Book) => book.id === current.id)) this.SyncReadingProgress(progress);
  }

  private SyncReadingProgress(progress: ReadingProgress): void {
    if (!this.authenticated || this.profileUsername.length === 0) return;
    this.PrepareReadingSyncForAccount(this.localAccountScope);
    // 同一本书只保留最新检查点，避免慢网络形成无界请求队列。
    this.pendingReaderProgress = this.pendingReaderProgress
      .filter((value: ReadingProgress) => value.bookId !== progress.bookId).concat(progress);
    this.MarkReaderSyncPending();
    this.DrainReadingProgressQueue();
  }

  private async DrainReadingProgressQueue(): Promise<void> {
    if (!this.authenticated || this.serviceUrl.length === 0 || this.readerProgressSyncing ||
      this.pendingReaderProgress.length === 0) return;
    this.readerProgressSyncing = true;
    const generation = this.readerProgressSyncGeneration;
    const queueOwner = this.readerProgressQueueOwner;
    let networkFailed = false;
    try {
      const api = new ReaderProgressApi(this.ActiveServiceUrl(), this.authManager);
      while (this.pendingReaderProgress.length > 0) {
        const progress = this.pendingReaderProgress[0];
        this.pendingReaderProgress = this.pendingReaderProgress.slice(1);
        try {
          let result = await api.save(progress);
          this.readerProgressSyncError = '';
          if (generation !== this.readerProgressSyncGeneration) return;
          if (!result.accepted && result.progress !== undefined && progress.updatedAt > result.progress.updatedAt) {
            // 本地修改更新时仅带上权威版本重试，避免旧设备静默回退进度。
            progress.revision = result.progress.revision;
            result = await api.save(progress);
            if (generation !== this.readerProgressSyncGeneration) return;
          }
          const authoritative = result.progress;
          if (authoritative !== undefined && (result.accepted || authoritative.updatedAt >= progress.updatedAt)) {
            this.ReplaceReadingProgress(authoritative);
          }
        } catch (error) {
          // 失败项重新并入队首；后续检查点或重新登录时继续同步。
          this.readerProgressSyncError = error instanceof Error ? error.message : '阅读进度同步失败';
          const newer = this.pendingReaderProgress.find((value: ReadingProgress) => value.bookId === progress.bookId);
          if (this.readerProgressQueueOwner === queueOwner && newer === undefined) {
            this.pendingReaderProgress = [progress].concat(this.pendingReaderProgress);
          }
          networkFailed = true;
          break;
        }
      }
    } finally {
      this.readerProgressSyncing = false;
      this.UpdateReaderSyncStatus();
      if (generation !== this.readerProgressSyncGeneration) {
        if (this.readerProgressPullPending && this.authenticated) {
          this.readerProgressPullPending = false;
          this.PullReadingProgress();
        }
        return;
      }
      if (this.readerProgressPullPending) {
        this.readerProgressPullPending = false;
        this.PullReadingProgress();
      } else if (!networkFailed && this.pendingReaderProgress.length > 0) {
        this.DrainReadingProgressQueue();
      }
    }
  }

  private async PullReadingProgress(): Promise<void> {
    if (!this.authenticated || this.serviceUrl.length === 0) return;
    if (this.readerProgressSyncing) {
      this.readerProgressPullPending = true;
      return;
    }
    this.readerProgressSyncing = true;
    this.MarkReaderSyncPending();
    const generation = this.readerProgressSyncGeneration;
    const cancellation = new DownloadCancellationToken();
    this.activeProgressPullCancellation = cancellation;
    try {
      const api = new ReaderProgressApi(this.ActiveServiceUrl(), this.authManager);
      const localProgress = this.AllLocalReadingProgress();
      const remote = await api.list(localProgress, this.books.map((book: Book) => book.id), cancellation);
      if (generation !== this.readerProgressSyncGeneration || cancellation.isCancelled()) return;
      const remoteBookIds = new Set<string>();
      for (const serverProgress of remote) {
        remoteBookIds.add(serverProgress.bookId);
        const local = localProgress.find((value: ReadingProgress) => value.bookId === serverProgress.bookId);
        if (local === undefined || serverProgress.updatedAt >= local.updatedAt) {
          this.ReplaceReadingProgress(serverProgress);
        } else {
          local.revision = serverProgress.revision;
          const result = await api.save(local);
          if (generation !== this.readerProgressSyncGeneration || cancellation.isCancelled()) return;
          if (result.progress !== undefined) this.ReplaceReadingProgress(result.progress);
        }
      }
      for (const local of localProgress) {
        if (!remoteBookIds.has(local.bookId)) {
          this.pendingReaderProgress = this.pendingReaderProgress
            .filter((value: ReadingProgress) => value.bookId !== local.bookId).concat(local);
        }
      }
      this.PersistReaderState();
    } catch (_) {
      // 同步失败不影响离线阅读。
    } finally {
      if (this.activeProgressPullCancellation === cancellation) this.activeProgressPullCancellation = undefined;
      this.readerProgressSyncing = false;
      this.UpdateReaderSyncStatus();
      if (cancellation.isCancelled()) {
        this.readerProgressPullPending = false;
        return;
      }
      if (generation !== this.readerProgressSyncGeneration) {
        if (this.readerProgressPullPending && this.authenticated) {
          this.readerProgressPullPending = false;
          this.PullReadingProgress();
        }
        return;
      }
      if (this.readerProgressPullPending) {
        this.readerProgressPullPending = false;
        this.PullReadingProgress();
      } else if (this.pendingReaderProgress.length > 0) {
        this.DrainReadingProgressQueue();
      }
    }
  }

  private PrepareReadingSyncForAccount(accountScope: string): void {
    if (!/^account:v2:[a-f0-9]{64}$/.test(accountScope)) return;
    if (this.readerProgressQueueOwner.length > 0 && this.readerProgressQueueOwner !== accountScope) {
      this.readerProgressSyncGeneration++;
      this.pendingReaderProgress = [];
      this.pendingReaderMarkers = [];
      this.pendingShelfBooks = [];
      this.shelfPullPending = false;
      this.pendingBookSources = [];
      this.bookSourcePullPending = false;
      this.readerMarkerPullPending = false;
      this.readerProgressPullPending = false;
    }
    this.readerProgressQueueOwner = accountScope;
  }

  private MarkReaderSyncPending(): void {
    this.readerSyncStatus = this.authenticated ? '正在同步…' : '未登录 · 数据保存在本机';
  }

  private UpdateReaderSyncStatus(): void {
    if (!this.authenticated) {
      this.readerSyncStatus = '未登录 · 数据保存在本机';
      return;
    }
    const pending = this.pendingReaderProgress.length + this.pendingReaderMarkers.length +
      this.pendingShelfBooks.length + this.pendingBookSources.length;
    const active = this.readerProgressSyncing || this.readerMarkerSyncing || this.shelfSyncing ||
      this.bookSourceSyncing;
    if (pending > 0) {
      this.readerSyncStatus = this.readerProgressSyncError.length > 0 ?
        `${pending} 项等待同步 · ${this.readerProgressSyncError}` : `${pending} 项等待网络同步`;
    } else if (active) {
      this.readerSyncStatus = '正在同步…';
    } else {
      this.readerSyncStatus = `已同步 · ${new Date().toLocaleTimeString()}`;
    }
  }

  /** 仅取消四类阅读数据拉取GET，不中断已发出的同步写请求。 */
  private CancelReaderPulls(): void {
    this.activeProgressPullCancellation?.cancel();
    this.activeMarkerPullCancellation?.cancel();
    this.activeShelfPullCancellation?.cancel();
    this.activeBookSourcePullCancellation?.cancel();
    this.activeProgressPullCancellation = undefined;
    this.activeMarkerPullCancellation = undefined;
    this.activeShelfPullCancellation = undefined;
    this.activeBookSourcePullCancellation = undefined;
    this.readerProgressPullPending = false;
    this.readerMarkerPullPending = false;
    this.shelfPullPending = false;
    this.bookSourcePullPending = false;
  }

  private BookSourceSyncItemFromSource(source: BookSource, deleted: boolean = false): BookSourceSyncItem {
    const snapshot: Record<string, Object> = {};
    snapshot['bookSourceUrl'] = source.bookSourceUrl;
    snapshot['bookSourceName'] = source.bookSourceName;
    snapshot['bookSourceGroup'] = source.bookSourceGroup;
    snapshot['bookSourceType'] = source.bookSourceType;
    snapshot['enabled'] = source.enabled;
    snapshot['enabledExplore'] = source.enabledExplore;
    snapshot['searchUrl'] = source.searchUrl;
    snapshot['exploreUrl'] = source.exploreUrl;
    snapshot['header'] = source.header;
    snapshot['ruleSearch'] = source.ruleSearch;
    snapshot['ruleExplore'] = source.ruleExplore;
    snapshot['ruleBookInfo'] = source.ruleBookInfo;
    snapshot['ruleToc'] = source.ruleToc;
    snapshot['ruleContent'] = source.ruleContent;
    return { sourceUrl: source.bookSourceUrl, snapshotJson: JSON.stringify(snapshot),
      updatedAt: source.syncUpdatedAt ?? 0, deleted: deleted, revision: source.syncRevision ?? 0 };
  }

  private AllLocalBookSourceItems(): BookSourceSyncItem[] {
    const active = this.bookSources.map((source: BookSource): BookSourceSyncItem =>
      this.BookSourceSyncItemFromSource(source));
    const deleted = this.bookSourceTombstones.map((source: BookSourceTombstone): BookSourceSyncItem => ({
      sourceUrl: source.sourceUrl, snapshotJson: source.snapshotJson, updatedAt: source.updatedAt,
      deleted: true, revision: source.revision
    }));
    return active.concat(deleted);
  }

  private QueueBookSource(source: BookSourceSyncItem): void {
    if (!this.authenticated || this.profileUsername.length === 0) return;
    this.PrepareReadingSyncForAccount(this.localAccountScope);
    this.pendingBookSources = this.pendingBookSources
      .filter((value: BookSourceSyncItem) => value.sourceUrl !== source.sourceUrl).concat(source);
    this.MarkReaderSyncPending();
    this.DrainBookSourceQueue();
  }

  private async DrainBookSourceQueue(): Promise<void> {
    if (!this.authenticated || this.serviceUrl.length === 0 || this.bookSourceSyncing ||
      this.pendingBookSources.length === 0) return;
    this.bookSourceSyncing = true;
    const generation = this.readerProgressSyncGeneration;
    const owner = this.readerProgressQueueOwner;
    try {
      const api = new BookSourceSyncApi(this.ActiveServiceUrl(), this.authManager);
      while (this.pendingBookSources.length > 0) {
        const source = this.pendingBookSources[0];
        this.pendingBookSources = this.pendingBookSources.slice(1);
        try {
          let result: BookSourceSyncResult = await api.save(source);
          if (generation !== this.readerProgressSyncGeneration) return;
          if (!result.accepted && result.source !== undefined && source.updatedAt > result.source.updatedAt) {
            source.revision = result.source.revision;
            result = await api.save(source);
            if (generation !== this.readerProgressSyncGeneration) return;
          }
          if (result.source !== undefined && (result.accepted || result.source.updatedAt >= source.updatedAt)) {
            this.ApplyBookSourceSyncItem(result.source);
          }
        } catch (_) {
          const newer = this.pendingBookSources.find((value: BookSourceSyncItem) =>
            value.sourceUrl === source.sourceUrl);
          if (this.readerProgressQueueOwner === owner && newer === undefined) {
            this.pendingBookSources = [source].concat(this.pendingBookSources);
          }
          break;
        }
      }
    } finally {
      this.bookSourceSyncing = false;
      this.UpdateReaderSyncStatus();
      if (this.bookSourcePullPending && this.authenticated) {
        this.bookSourcePullPending = false;
        this.PullBookSources();
      }
    }
  }

  private async PullBookSources(): Promise<void> {
    if (!this.authenticated || this.serviceUrl.length === 0) return;
    if (this.bookSourceSyncing) {
      this.bookSourcePullPending = true;
      return;
    }
    this.bookSourceSyncing = true;
    this.MarkReaderSyncPending();
    const generation = this.readerProgressSyncGeneration;
    const cancellation = new DownloadCancellationToken();
    this.activeBookSourcePullCancellation = cancellation;
    try {
      const local = this.AllLocalBookSourceItems();
      const remote = await new BookSourceSyncApi(this.ActiveServiceUrl(), this.authManager).list(cancellation);
      if (generation !== this.readerProgressSyncGeneration || cancellation.isCancelled()) return;
      const remoteUrls = new Set<string>();
      const remoteToApply: BookSourceSyncItem[] = [];
      for (const serverSource of remote) {
        if (!serverSource.deleted && this.ValidateSyncedBookSource(serverSource) === undefined) continue;
        remoteUrls.add(serverSource.sourceUrl);
        const localSource = local.find((value: BookSourceSyncItem) => value.sourceUrl === serverSource.sourceUrl);
        if (localSource !== undefined && localSource.updatedAt > serverSource.updatedAt) {
          localSource.revision = serverSource.revision;
          this.pendingBookSources = this.pendingBookSources
            .filter((value: BookSourceSyncItem) => value.sourceUrl !== localSource.sourceUrl).concat(localSource);
        } else {
          remoteToApply.push(serverSource);
        }
      }
      this.ApplyBookSourceSyncItems(remoteToApply, false);
      for (const localSource of local) {
        if (!remoteUrls.has(localSource.sourceUrl)) this.pendingBookSources = this.pendingBookSources
          .filter((value: BookSourceSyncItem) => value.sourceUrl !== localSource.sourceUrl).concat(localSource);
      }
      this.PersistReaderState();
    } catch (_) {
      // 同步失败不影响设备内既有书源。
    } finally {
      if (this.activeBookSourcePullCancellation === cancellation) this.activeBookSourcePullCancellation = undefined;
      this.bookSourceSyncing = false;
      this.UpdateReaderSyncStatus();
      if (cancellation.isCancelled()) {
        this.bookSourcePullPending = false;
        return;
      }
      if (this.bookSourcePullPending && this.authenticated) {
        this.bookSourcePullPending = false;
        this.PullBookSources();
      } else if (generation === this.readerProgressSyncGeneration && this.pendingBookSources.length > 0) {
        this.DrainBookSourceQueue();
      }
      if (generation === this.readerProgressSyncGeneration) this.PullShelfBooks();
    }
  }

  private ValidateSyncedBookSource(item: BookSourceSyncItem): BookSource | undefined {
    try {
      const source = this.bookSourceImporter.importJson(item.snapshotJson).sources[0];
      if (source === undefined || source.bookSourceUrl !== item.sourceUrl) return undefined;
      source.syncUpdatedAt = item.updatedAt;
      source.syncRevision = item.revision;
      return source;
    } catch (_) {
      return undefined;
    }
  }

  private ApplyBookSourceSyncItem(item: BookSourceSyncItem): void {
    this.ApplyBookSourceSyncItems([item], true);
  }

  private ApplyBookSourceSyncItems(items: BookSourceSyncItem[], persist: boolean): void {
    const activeSources = new Map<string, BookSource>();
    const deletedItems: BookSourceSyncItem[] = [];
    items.forEach((item: BookSourceSyncItem) => {
      if (item.deleted) {
        deletedItems.push(item);
        return;
      }
      const source = this.ValidateSyncedBookSource(item);
      if (source !== undefined) activeSources.set(item.sourceUrl, source);
    });
    if (activeSources.size === 0 && deletedItems.length === 0) return;
    this.InvalidateBookSourceOperation();
    this.sourceManagementRevision++;
    this.sourceCredentialBusy = false;
    const sourceMap = new Map<string, BookSource>();
    this.bookSources.forEach((source: BookSource) => sourceMap.set(source.bookSourceUrl, source));
    const tombstoneMap = new Map<string, BookSourceTombstone>();
    this.bookSourceTombstones.forEach((source: BookSourceTombstone) => tombstoneMap.set(source.sourceUrl, source));
    activeSources.forEach((source: BookSource, sourceUrl: string) => {
      sourceMap.set(sourceUrl, source);
      tombstoneMap.delete(sourceUrl);
    });
    deletedItems.forEach((item: BookSourceSyncItem) => {
      sourceMap.delete(item.sourceUrl);
      tombstoneMap.set(item.sourceUrl, { sourceUrl: item.sourceUrl, snapshotJson: item.snapshotJson,
        updatedAt: item.updatedAt, revision: item.revision });
    });
    this.bookSources = Array.from(sourceMap.values());
    this.bookSourceTombstones = Array.from(tombstoneMap.values());
    deletedItems.forEach((item: BookSourceSyncItem) => {
      this.ClearSyncedBookSourceCredential(item.sourceUrl);
      this.HandleSyncedBookSourceRemoval(item.sourceUrl);
    });
    if (persist) this.PersistReaderState();
  }

  private HandleSyncedBookSourceRemoval(sourceUrl: string): void {
    if (this.detailBook?.sourceId === sourceUrl) this.activeSourceCatalogCancellation?.cancel();
    const remainingSourceUrls = new Set<string>();
    this.bookSources.forEach((source: BookSource) => remainingSourceUrls.add(source.bookSourceUrl));
    const migratedBooks: Book[] = [];
    this.books = this.books.map((book: Book): Book => {
      if (book.origin !== 'source') return book;
      const alternatives = (book.sourceAlternatives ?? []).filter((alternative: BookSourceAlternative) =>
        alternative.sourceId !== sourceUrl && remainingSourceUrls.has(alternative.sourceId));
      if (book.sourceId !== sourceUrl) return this.CopyBookWithAlternatives(book, alternatives);
      const replacement = alternatives.length > 0 ? alternatives[0] : undefined;
      if (replacement === undefined) return this.CopyBookWithAlternatives(book, alternatives);
      const migrated: Book = {
        id: book.id, name: book.name, author: book.author, coverUrl: replacement.coverUrl,
        remoteCoverUrl: replacement.remoteCoverUrl, origin: book.origin, format: book.format,
        resourceUri: replacement.resourceUri, sourceId: replacement.sourceId, tags: book.tags,
        progress: book.progress,
        sourceAlternatives: alternatives, updatedAt: Date.now(), revision: book.revision
      };
      migratedBooks.push(migrated);
      return migrated;
    });
    this.sourceSearchResults = this.sourceSearchResults.filter((result: BookSourceSearchResult) =>
      result.sourceUrl !== sourceUrl);
    this.detailSourceCandidates = this.detailSourceCandidates.filter((result: BookSourceSearchResult) =>
      result.sourceUrl !== sourceUrl);
    if (this.sourceExploreActiveSourceUrl === sourceUrl) this.ResetBookSourceExplorePaging();
    if (this.sourceExploreSourceUrl === sourceUrl) this.CloseBookSourceExplore();
    if (this.detailBook?.sourceId === sourceUrl) {
      const migrated = this.books.find((book: Book) => book.id === this.detailBook?.id);
      if (migrated !== undefined && migrated.sourceId !== sourceUrl) this.detailBook = migrated;
      else this.bookDetailVisible = false;
    }
    migratedBooks.forEach((book: Book) => this.QueueShelfBook(this.ShelfItemFromBook(book)));
  }

  private async ClearSyncedBookSourceCredential(sourceUrl: string): Promise<void> {
    try {
      await this.bookSourceCredentialStore.clear(sourceUrl);
    } catch (_) {
      // 墓碑优先生效；安全存储暂时失败时不重新激活已删除书源。
    }
  }

  private QueueShelfBook(book: ShelfSyncItem): void {
    if (!this.authenticated || this.profileUsername.length === 0 || book.id.startsWith('local:')) return;
    this.PrepareReadingSyncForAccount(this.localAccountScope);
    this.pendingShelfBooks = this.pendingShelfBooks
      .filter((value: ShelfSyncItem) => value.id !== book.id).concat(book);
    this.MarkReaderSyncPending();
    this.DrainShelfQueue();
  }

  private async DrainShelfQueue(): Promise<void> {
    if (!this.authenticated || this.serviceUrl.length === 0 || this.shelfSyncing ||
      this.pendingShelfBooks.length === 0) return;
    this.shelfSyncing = true;
    const generation = this.readerProgressSyncGeneration;
    const owner = this.readerProgressQueueOwner;
    try {
      const api = new ShelfSyncApi(this.ActiveServiceUrl(), this.authManager);
      while (this.pendingShelfBooks.length > 0) {
        const book = this.pendingShelfBooks[0];
        this.pendingShelfBooks = this.pendingShelfBooks.slice(1);
        try {
          let result: ShelfSyncResult = await api.save(book);
          if (generation !== this.readerProgressSyncGeneration) return;
          if (!result.accepted && result.book !== undefined && book.updatedAt > result.book.updatedAt) {
            book.revision = result.book.revision;
            result = await api.save(book);
            if (generation !== this.readerProgressSyncGeneration) return;
          }
          if (result.book !== undefined && (result.accepted || result.book.updatedAt >= book.updatedAt)) {
            this.ApplyShelfBook(result.book);
          }
        } catch (_) {
          const newer = this.pendingShelfBooks.find((value: ShelfSyncItem) => value.id === book.id);
          if (this.readerProgressQueueOwner === owner && newer === undefined) {
            this.pendingShelfBooks = [book].concat(this.pendingShelfBooks);
          }
          break;
        }
      }
    } finally {
      this.shelfSyncing = false;
      this.UpdateReaderSyncStatus();
      if (this.shelfPullPending && this.authenticated) {
        this.shelfPullPending = false;
        this.PullShelfBooks();
      }
    }
  }

  private async PullShelfBooks(): Promise<void> {
    if (!this.authenticated || this.serviceUrl.length === 0) return;
    if (this.shelfSyncing) {
      this.shelfPullPending = true;
      return;
    }
    this.shelfSyncing = true;
    this.MarkReaderSyncPending();
    const generation = this.readerProgressSyncGeneration;
    const cancellation = new DownloadCancellationToken();
    this.activeShelfPullCancellation = cancellation;
    try {
      const local = this.AllLocalShelfBooks();
      const remote = await new ShelfSyncApi(this.ActiveServiceUrl(), this.authManager).list(cancellation);
      if (generation !== this.readerProgressSyncGeneration || cancellation.isCancelled()) return;
      const remoteIds = new Set<string>();
      for (const serverBook of remote) {
        remoteIds.add(serverBook.id);
        const localBook = local.find((value: ShelfSyncItem) => value.id === serverBook.id);
        if (localBook !== undefined && localBook.updatedAt > serverBook.updatedAt) {
          localBook.revision = serverBook.revision;
          this.pendingShelfBooks = this.pendingShelfBooks
            .filter((value: ShelfSyncItem) => value.id !== localBook.id).concat(localBook);
        } else {
          this.ApplyShelfBook(serverBook);
        }
      }
      for (const localBook of local) {
        if (!remoteIds.has(localBook.id)) this.pendingShelfBooks = this.pendingShelfBooks
          .filter((value: ShelfSyncItem) => value.id !== localBook.id).concat(localBook);
      }
      const context = this.ApplicationContext();
      if (context !== undefined) this.CacheShelfCovers(context);
      this.PersistReaderState();
    } catch (_) {
      // 远程同步失败不影响本地和书源阅读。
    } finally {
      if (this.activeShelfPullCancellation === cancellation) this.activeShelfPullCancellation = undefined;
      this.shelfSyncing = false;
      this.UpdateReaderSyncStatus();
      if (cancellation.isCancelled()) {
        this.shelfPullPending = false;
        return;
      }
      if (this.shelfPullPending && this.authenticated) {
        this.shelfPullPending = false;
        this.PullShelfBooks();
      } else if (generation === this.readerProgressSyncGeneration && this.pendingShelfBooks.length > 0) {
        this.DrainShelfQueue();
      }
      if (generation === this.readerProgressSyncGeneration) {
        this.PullReadingProgress();
        this.PullReaderMarkers();
      }
    }
  }

  private AllLocalShelfBooks(): ShelfSyncItem[] {
    const active = this.books.filter((book: Book) => book.origin !== 'local')
      .map((book: Book): ShelfSyncItem => this.ShelfItemFromBook(book));
    const deleted = this.shelfBookTombstones.map((book: ShelfBookTombstone): ShelfSyncItem => ({
      id: book.id, name: book.name, author: book.author, origin: book.origin, format: book.format,
      resourceUri: book.resourceUri, sourceId: book.sourceId, remoteCoverUrl: book.remoteCoverUrl,
      updatedAt: book.updatedAt, deleted: true, revision: book.revision
    }));
    return active.concat(deleted);
  }

  private ShelfItemFromBook(book: Book, deleted: boolean = false): ShelfSyncItem {
    return { id: book.id, name: book.name, author: book.author,
      origin: book.origin === 'remote' ? 'remote' : 'source', format: book.format,
      resourceUri: book.resourceUri, sourceId: book.sourceId, remoteCoverUrl: book.remoteCoverUrl ?? '',
      updatedAt: book.updatedAt ?? 0, deleted: deleted, revision: book.revision ?? 0 };
  }

  private ApplyShelfBook(item: ShelfSyncItem): void {
    const existing = this.books.find((book: Book) => book.id === item.id);
    this.books = this.books.filter((book: Book) => book.id !== item.id);
    this.shelfBookTombstones = this.shelfBookTombstones
      .filter((book: ShelfBookTombstone) => book.id !== item.id);
    if (item.deleted) {
      this.shelfBookTombstones = this.shelfBookTombstones.concat({ id: item.id, name: item.name,
        author: item.author, origin: item.origin, format: item.format, resourceUri: item.resourceUri,
        sourceId: item.sourceId, remoteCoverUrl: item.remoteCoverUrl, updatedAt: item.updatedAt,
        revision: item.revision });
    } else {
      const progress = this.readingProgress.find((value: ReadingProgress) => value.bookId === item.id);
      this.books = this.books.concat({ id: item.id, name: item.name, author: item.author, coverUrl: '',
        remoteCoverUrl: item.remoteCoverUrl.length > 0 ? item.remoteCoverUrl : undefined,
        origin: item.origin, format: item.format, resourceUri: item.resourceUri, sourceId: item.sourceId,
        cacheVersion: existing?.cacheVersion, tags: existing?.tags,
        progress: progress?.percentage ?? 0, updatedAt: item.updatedAt, revision: item.revision });
    }
    this.PersistReaderState();
  }

  private QueueReaderMarker(marker: ReaderMarkerSyncItem): void {
    if (!this.authenticated || this.profileUsername.length === 0) return;
    this.PrepareReadingSyncForAccount(this.localAccountScope);
    this.pendingReaderMarkers = this.pendingReaderMarkers
      .filter((value: ReaderMarkerSyncItem) => value.id !== marker.id).concat(marker);
    this.MarkReaderSyncPending();
    this.DrainReaderMarkerQueue();
  }

  private async DrainReaderMarkerQueue(): Promise<void> {
    if (!this.authenticated || this.serviceUrl.length === 0 || this.readerMarkerSyncing ||
      this.pendingReaderMarkers.length === 0) return;
    this.readerMarkerSyncing = true;
    const generation = this.readerProgressSyncGeneration;
    const owner = this.readerProgressQueueOwner;
    try {
      const api = new ReaderMarkerApi(this.ActiveServiceUrl(), this.authManager);
      while (this.pendingReaderMarkers.length > 0) {
        const marker = this.pendingReaderMarkers[0];
        this.pendingReaderMarkers = this.pendingReaderMarkers.slice(1);
        try {
          let result: ReaderMarkerSyncResult = await api.save(marker);
          if (generation !== this.readerProgressSyncGeneration) return;
          if (!result.accepted && result.marker !== undefined && marker.updatedAt > result.marker.updatedAt) {
            marker.revision = result.marker.revision;
            result = await api.save(marker);
            if (generation !== this.readerProgressSyncGeneration) return;
          }
          if (result.marker !== undefined && (result.accepted || result.marker.updatedAt >= marker.updatedAt)) {
            this.ApplyReaderMarker(result.marker);
          }
        } catch (_) {
          const newer = this.pendingReaderMarkers.find((value: ReaderMarkerSyncItem) => value.id === marker.id);
          if (this.readerProgressQueueOwner === owner && newer === undefined) {
            this.pendingReaderMarkers = [marker].concat(this.pendingReaderMarkers);
          }
          break;
        }
      }
    } finally {
      this.readerMarkerSyncing = false;
      this.UpdateReaderSyncStatus();
      if (this.readerMarkerPullPending && this.authenticated) {
        this.readerMarkerPullPending = false;
        this.PullReaderMarkers();
      }
    }
  }

  private async PullReaderMarkers(): Promise<void> {
    if (!this.authenticated || this.serviceUrl.length === 0) return;
    if (this.readerMarkerSyncing) {
      this.readerMarkerPullPending = true;
      return;
    }
    this.readerMarkerSyncing = true;
    this.MarkReaderSyncPending();
    const generation = this.readerProgressSyncGeneration;
    const cancellation = new DownloadCancellationToken();
    this.activeMarkerPullCancellation = cancellation;
    try {
      const local = this.AllLocalReaderMarkers();
      const api = new ReaderMarkerApi(this.ActiveServiceUrl(), this.authManager);
      const remote = await api.list(this.books.map((book: Book) => book.id), local, cancellation);
      if (generation !== this.readerProgressSyncGeneration || cancellation.isCancelled()) return;
      const remoteIds = new Set<string>();
      for (const serverMarker of remote) {
        remoteIds.add(serverMarker.id);
        const localMarker = local.find((value: ReaderMarkerSyncItem) => value.id === serverMarker.id);
        if (localMarker !== undefined && localMarker.updatedAt > serverMarker.updatedAt) {
          localMarker.revision = serverMarker.revision;
          this.pendingReaderMarkers = this.pendingReaderMarkers
            .filter((value: ReaderMarkerSyncItem) => value.id !== localMarker.id).concat(localMarker);
        } else {
          this.ApplyReaderMarker(serverMarker);
        }
      }
      for (const localMarker of local) {
        if (!remoteIds.has(localMarker.id)) this.pendingReaderMarkers = this.pendingReaderMarkers
          .filter((value: ReaderMarkerSyncItem) => value.id !== localMarker.id).concat(localMarker);
      }
      this.PersistReaderState();
    } catch (_) {
      // 离线时保留本地标记和删除墓碑。
    } finally {
      if (this.activeMarkerPullCancellation === cancellation) this.activeMarkerPullCancellation = undefined;
      this.readerMarkerSyncing = false;
      this.UpdateReaderSyncStatus();
      if (cancellation.isCancelled()) {
        this.readerMarkerPullPending = false;
        return;
      }
      if (this.readerMarkerPullPending && this.authenticated) {
        this.readerMarkerPullPending = false;
        this.PullReaderMarkers();
      } else if (generation === this.readerProgressSyncGeneration && this.pendingReaderMarkers.length > 0) {
        this.DrainReaderMarkerQueue();
      }
    }
  }

  private AllLocalReaderMarkers(): ReaderMarkerSyncItem[] {
    const bookmarks = this.readerBookmarks.map((value: ReaderBookmark): ReaderMarkerSyncItem => ({
      id: value.id, kind: 'BOOKMARK', bookId: value.bookId, chapterTitle: value.chapterTitle,
      locator: value.locator, note: value.note, createdAt: value.createdAt,
      updatedAt: value.updatedAt ?? value.createdAt, deleted: false, revision: value.revision ?? 0
    }));
    const annotations = this.readerAnnotations.map((value: ReaderAnnotation): ReaderMarkerSyncItem => ({
      id: value.id, kind: 'ANNOTATION', bookId: value.bookId, chapterTitle: value.chapterTitle,
      locator: value.locator, note: value.note, createdAt: value.createdAt,
      updatedAt: value.updatedAt ?? value.createdAt, deleted: false, revision: value.revision ?? 0
    }));
    const tombstones = this.readerMarkerTombstones.map((value: ReaderMarkerTombstone): ReaderMarkerSyncItem => ({
      id: value.id, kind: value.kind, bookId: value.bookId, chapterTitle: value.chapterTitle,
      locator: value.locator, note: value.note, createdAt: value.createdAt, updatedAt: value.updatedAt,
      deleted: true, revision: value.revision
    }));
    return bookmarks.concat(annotations).concat(tombstones);
  }

  private ApplyReaderMarker(marker: ReaderMarkerSyncItem): void {
    this.readerBookmarks = this.readerBookmarks.filter((value: ReaderBookmark) => value.id !== marker.id);
    this.readerAnnotations = this.readerAnnotations.filter((value: ReaderAnnotation) => value.id !== marker.id);
    this.readerMarkerTombstones = this.readerMarkerTombstones
      .filter((value: ReaderMarkerTombstone) => value.id !== marker.id);
    if (marker.deleted) {
      this.readerMarkerTombstones = this.readerMarkerTombstones.concat({
        id: marker.id, kind: marker.kind, bookId: marker.bookId, chapterTitle: marker.chapterTitle,
        locator: marker.locator, note: marker.note, createdAt: marker.createdAt,
        updatedAt: marker.updatedAt, revision: marker.revision
      });
    } else if (marker.kind === 'BOOKMARK') {
      this.readerBookmarks = this.readerBookmarks.concat({ id: marker.id, bookId: marker.bookId,
        chapterTitle: marker.chapterTitle, locator: marker.locator, note: marker.note,
        createdAt: marker.createdAt, updatedAt: marker.updatedAt, revision: marker.revision });
    } else {
      this.readerAnnotations = this.readerAnnotations.concat({ id: marker.id, bookId: marker.bookId,
        chapterTitle: marker.chapterTitle, locator: marker.locator, note: marker.note,
        createdAt: marker.createdAt, updatedAt: marker.updatedAt, revision: marker.revision });
    }
    this.PersistReaderState();
  }

  private ReplaceReadingProgress(progress: ReadingProgress): void {
    this.readingProgress = this.readingProgress.filter((value: ReadingProgress) => value.bookId !== progress.bookId);
    this.readingProgressTombstones = this.readingProgressTombstones
      .filter((value: ReadingProgressTombstone) => value.bookId !== progress.bookId);
    if (progress.deleted) {
      this.readingProgressTombstones = this.readingProgressTombstones.concat({
        bookId: progress.bookId, chapterTitle: progress.chapterTitle, locator: progress.locator,
        percentage: progress.percentage, updatedAt: progress.updatedAt, revision: progress.revision ?? 0,
        deleted: true
      });
    } else {
      this.readingProgress = this.readingProgress.concat(progress);
    }
    this.books = this.books.map((book: Book): Book => book.id === progress.bookId ?
      this.CopyBookWithProgress(book, progress.deleted ? 0 : progress.percentage) : book);
    this.PersistReaderState();
  }

  private CopyBookWithProgress(book: Book, progress: number): Book {
    return {
      id: book.id, name: book.name, author: book.author, coverUrl: book.coverUrl,
      remoteCoverUrl: book.remoteCoverUrl, origin: book.origin, format: book.format,
      resourceUri: book.resourceUri, sourceId: book.sourceId, cacheVersion: book.cacheVersion, tags: book.tags,
      progress: progress, sourceAlternatives: book.sourceAlternatives,
      updatedAt: book.updatedAt, revision: book.revision
    };
  }

  private AllLocalReadingProgress(): ReadingProgress[] {
    const shelfBookIds = new Set<string>(this.books.map((book: Book): string => book.id));
    const tombstones = this.readingProgressTombstones.map(
      (value: ReadingProgressTombstone): ReadingProgress => ({
        bookId: value.bookId, chapterTitle: value.chapterTitle, locator: value.locator,
        percentage: value.percentage, updatedAt: value.updatedAt, revision: value.revision, deleted: true
      }));
    // 活跃进度只同步书架图书；移出书架产生的墓碑仍需同步到服务端完成删除。
    return this.readingProgress.filter((value: ReadingProgress) => shelfBookIds.has(value.bookId)).concat(tombstones);
  }

  private StartReaderPersistenceTimer(): void {
    this.StopReaderPersistenceTimer();
    if (!this.readerVisible || this.currentBook === undefined) return;
    this.readerPersistenceTimerId = setTimeout(() => {
      this.readerPersistenceTimerId = -1;
      if (!this.readerVisible || this.currentBook === undefined) return;
      this.SaveCurrentProgress();
      this.StartReaderPersistenceTimer();
    }, 30000);
  }

  /** 翻页或滚动停止后尽快把最新阅读位置写入本地并同步服务端。 */
  private ScheduleReaderProgressSave(): void {
    this.ClearScheduledReaderProgressSave();
    if (!this.readerVisible || this.currentBook === undefined) return;
    this.readerProgressCommitTimerId = setTimeout(() => {
      this.readerProgressCommitTimerId = -1;
      if (this.readerVisible && this.currentBook !== undefined) this.SaveCurrentProgress();
    }, 1200);
  }

  /** 清除尚未执行的阅读位置防抖任务。 */
  private ClearScheduledReaderProgressSave(): void {
    if (this.readerProgressCommitTimerId >= 0) clearTimeout(this.readerProgressCommitTimerId);
    this.readerProgressCommitTimerId = -1;
  }

  private StopReaderPersistenceTimer(): void {
    if (this.readerPersistenceTimerId >= 0) clearTimeout(this.readerPersistenceTimerId);
    this.readerPersistenceTimerId = -1;
  }

  private async SearchReaderContent(): Promise<void> {
    const keyword = this.readerSearchText.trim();
    if (keyword.length === 0 || this.readerSearchBusy) return;
    const searchRevision = ++this.readerSearchRevision;
    const searchedBook = this.currentBook;
    const searchedLoader = this.readerContentLoader;
    this.readerSearchBusy = true;
    this.readerSearchResults = [];
    const hasUnloadedNetworkChapter = this.readerChapters.some((chapter: ReaderChapter) =>
      chapter.content.length === 0 && !chapter.resourceUri?.startsWith('txt-index:'));
    const searchScope = hasUnloadedNetworkChapter ? '已加载章节' : '全书';
    this.readerSearchStatus = `正在搜索${searchScope}…`;
    try {
      const results = await searchedLoader.searchIndexedTextChapters(
        this.readerChapters, keyword, 100, (completed: number, total: number) => {
          if (searchRevision === this.readerSearchRevision && searchedBook === this.currentBook &&
            searchedLoader === this.readerContentLoader) {
            this.readerSearchStatus = `正在搜索${searchScope} ${completed}/${total}`;
          }
        }, (): boolean => searchRevision !== this.readerSearchRevision || searchedBook !== this.currentBook ||
          searchedLoader !== this.readerContentLoader || !this.readerVisible);
      if (searchRevision !== this.readerSearchRevision || searchedBook !== this.currentBook ||
        searchedLoader !== this.readerContentLoader) return;
      this.readerSearchResults = results;
      this.readerSearchStatus = this.readerSearchResults.length === 0 ? `${searchScope}未找到“${keyword}”` :
        `找到 ${this.readerSearchResults.length} 处结果${this.readerSearchResults.length >= 100 ? '（仅显示前100处）' : ''}`;
    } catch (error) {
      if (searchRevision !== this.readerSearchRevision || searchedBook !== this.currentBook ||
        searchedLoader !== this.readerContentLoader) return;
      this.readerSearchStatus = error instanceof Error ? error.message : '全文搜索失败';
    }
    if (searchRevision === this.readerSearchRevision) this.readerSearchBusy = false;
  }

  private async OpenReaderSearchHit(hit: IndexedTextSearchHit): Promise<void> {
    if (hit.chapterIndex < 0 || hit.chapterIndex >= this.readerChapters.length) return;
    this.ClearReaderSelection();
    this.readerChapterIndex = hit.chapterIndex;
    this.LoadIndexedTextChapter();
    this.readerContent = this.readerChapters[hit.chapterIndex].content;
    const estimatedLine = Math.max(0, hit.characterIndex / 22);
    this.readerScrollValue = estimatedLine * this.readerSettings.fontSize * this.readerSettings.lineHeight;
    this.RebuildReaderPages();
    if (this.ReaderUsesContinuousText()) {
      try {
        this.readerScroller.scrollTo({
          xOffset: 0,
          yOffset: this.ReaderAbsoluteScrollOffset(),
          animation: false
        });
      } catch (_) {
      }
    } else if (this.readerTextPages.length > 0) {
      const ratio = Math.max(0, Math.min(0.999, hit.characterIndex /
        Math.max(1, this.readerChapters[hit.chapterIndex].content.length)));
      this.UpdateReaderTextPage(Math.floor(ratio * this.readerTextPages.length));
    }
    this.readerSearchVisible = false;
    this.readerControlsVisible = false;
    this.readerStatus = '';
    this.readerInfo = `搜索结果 · ${hit.chapterTitle}`;
    this.SaveCurrentProgress();
  }

  private ReaderProgressLabel(): string {
    if (this.pdfMode && this.pdfPageCount > 0) return `第${this.pdfPageIndex + 1}/${this.pdfPageCount}页`;
    if (this.comicImageUris.length > 0) {
      return `第${this.comicPageIndex + 1}/${this.comicImageUris.length}页 · ${this.currentBook?.progress ?? 0}%`;
    }
    if (this.comicImageUris.length === 0 && !this.ReaderUsesContinuousText() &&
      this.readerTextPages.length > 0) {
      return `第${this.readerChapterIndex + 1}/${Math.max(1, this.readerChapters.length)}节 · ` +
        `${this.readerTextPageIndex + 1}/${this.readerTextPages.length}页 · ${this.currentBook?.progress ?? 0}%`;
    }
    return `第${this.readerChapterIndex + 1}/${Math.max(1, this.readerChapters.length)}节 · ` +
      `${this.currentBook?.progress ?? 0}%`;
  }

  private ReaderCompactProgressLabel(): string {
    if (this.pdfMode && this.pdfPageCount > 0) return `${this.pdfPageIndex + 1}/${this.pdfPageCount}页`;
    if (this.comicImageUris.length > 0) return `${this.comicPageIndex + 1}/${this.comicImageUris.length}页`;
    return `${this.readerChapterIndex + 1}/${Math.max(1, this.readerChapters.length)}节 · ` +
      `${this.currentBook?.progress ?? 0}%`;
  }

  /** 返回正文底部单行状态，避免控制区域侵占阅读空间。 */
  private ReaderSingleLineStatus(): string {
    const rawTitle = this.CurrentReaderLocationTitle().trim().length > 0 ?
      this.CurrentReaderLocationTitle().trim() : '正文';
    const title = rawTitle.length > 13 ? `${rawTitle.substring(0, 12)}…` : rawTitle;
    const pages = this.ReaderWholeBookPagePosition();
    return `${title} · 全书${this.currentBook?.progress ?? 0}% · ${pages.current}/${pages.total}页`;
  }

  /** 估算跨章节连续阅读下的当前页与全书页数。 */
  private ReaderWholeBookPagePosition(): ReaderPagePosition {
    if (this.pdfMode && this.pdfPageCount > 0) {
      return { current: this.pdfPageIndex + 1, total: this.pdfPageCount };
    }
    if (this.comicImageUris.length > 0) {
      return { current: this.comicPageIndex + 1, total: Math.max(1, this.comicImageUris.length) };
    }
    if (this.readerChapters.length === 0) return { current: 1, total: 1 };
    const loaded = this.readerChapters.filter((chapter: ReaderChapter) => chapter.content.length > 0);
    const pageCapacity = this.ReaderEstimatedCharactersPerPage();
    const loadedPages = loaded.reduce((total: number, chapter: ReaderChapter): number => {
      if (chapter === this.readerChapters[this.readerChapterIndex] && this.readerTextPages.length > 0) {
        return total + this.readerTextPages.length;
      }
      return total + Math.max(1, Math.ceil(chapter.content.length / pageCapacity));
    }, 0);
    const averagePages = Math.max(1, Math.round(loadedPages / Math.max(1, loaded.length)));
    const total = Math.max(1, averagePages * this.readerChapters.length);
    const chapterFraction = this.CurrentTextChapterFraction();
    const current = Math.max(1, Math.min(total,
      Math.floor((this.readerChapterIndex + chapterFraction) * averagePages) + 1));
    return { current: current, total: total };
  }

  /** 根据当前视口、字号和行距估算单页可容纳的字符数。 */
  private ReaderEstimatedCharactersPerPage(): number {
    const fontSize = Math.max(12, this.ReaderScaledFontSize(this.readerSettings.fontSize));
    const contentWidth = Math.max(120, this.readerViewportWidth - this.ReaderContentHorizontalPadding() * 2);
    const contentHeight = Math.max(180, this.readerViewportHeight - 46);
    const charactersPerLine = Math.max(8, Math.floor(contentWidth / fontSize));
    const linesPerPage = Math.max(6,
      Math.floor(contentHeight / Math.max(fontSize, fontSize * this.readerSettings.lineHeight)));
    return Math.max(80, charactersPerLine * linesPerPage);
  }

  private ReaderBookDisplayTitle(): string {
    const name = this.currentBook?.name ?? '';
    return this.remoteBookDisplayMetadataPolicy.metadata(name).title;
  }

  private ReaderVisibleText(content: string): string {
    const title = this.CurrentReaderLocationTitle().trim();
    return this.ReaderVisibleChapterText(content, title);
  }

  /** 清理正文开头重复出现的章节标题。 */
  private ReaderVisibleChapterText(content: string, chapterTitle: string): string {
    const title = chapterTitle.trim();
    const visible = content.trimStart();
    if (title.length === 0 || !visible.startsWith(title)) return content;
    const following = visible.substring(title.length, title.length + 1);
    if (following.length > 0 && !/\s/.test(following)) return content;
    return visible.substring(title.length).trimStart();
  }

  /** 将纯文本正文拆成独立段落，避免超长文本节点截断并改善阅读节奏。 */
  private ReaderPlainParagraphs(content: string, chapterTitle: string): string[] {
    const visible = this.ReaderVisibleChapterText(content, chapterTitle)
      .replace(/\r\n/g, '\n').replace(/\r/g, '\n').trim();
    if (visible.length === 0) return [];
    const lines = visible.split(/\n+/).map((line: string) => line.trim())
      .filter((line: string) => line.length > 0);
    if (lines.length === 0) return [visible];
    return lines;
  }

  /** 为正文段落补充中文阅读习惯中的两字符首行缩进。 */
  private ReaderIndentedParagraph(content: string): string {
    const trimmed = content.trim();
    if (trimmed.length === 0 || trimmed.startsWith('\u3000\u3000')) return trimmed;
    return `\u3000\u3000${trimmed}`;
  }

  /** 判断纯文本中的场景分隔行，使其居中显示并与正文建立层级。 */
  private ReaderIsSceneBreak(content: string): boolean {
    const trimmed = content.trim();
    return trimmed.length >= 3 && trimmed.length <= 16 && /^[—―─…·*＊\-\s]+$/.test(trimmed);
  }

  private UpdateReaderSelection(content: string, start: number, end: number): void {
    const lower = Math.max(0, Math.min(content.length, Math.floor(Math.min(start, end))));
    const upper = Math.max(0, Math.min(content.length, Math.floor(Math.max(start, end))));
    if (upper <= lower || this.currentBook === undefined) {
      this.ClearReaderSelection();
      return;
    }
    const selected = content.substring(lower, upper)
      .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '').trim();
    if (selected.length === 0) {
      this.ClearReaderSelection();
      return;
    }
    this.readerSelectedText = selected.substring(0, 4000);
    this.readerSelectionBookId = this.currentBook.id;
    this.readerSelectionChapterTitle = this.readerChapters[this.readerChapterIndex]?.title ?? '';
    this.readerInfo = selected.length > 4000 ? '已选择文本 · DSH仅可读取前4000字' :
      `已选择 ${selected.length} 字 · DSH可读取`;
  }

  private ClearReaderSelection(): void {
    this.readerSelectedText = '';
    this.readerSelectionBookId = '';
    this.readerSelectionChapterTitle = '';
  }

  private async AskCopilotAboutSelection(): Promise<void> {
    if (this.readerSelectedText.length === 0 || this.currentBook === undefined) return;
    if (!this.authenticated) {
      this.readerInfo = '请先登录MyTools后使用DSH';
      return;
    }
    if (this.copilotBusy || this.IsCopilotTurnActive()) {
      this.readerInfo = 'DSH仍在处理上一轮消息';
      return;
    }
    if (!this.copilotInitialized) {
      await this.InitializeCopilot();
      if (!this.copilotInitialized) {
        this.readerInfo = this.copilotStatus.length > 0 ? this.copilotStatus : 'DSH初始化失败';
        return;
      }
    }
    const chapterTitle = this.readerChapters[this.readerChapterIndex]?.title ?? '';
    this.copilotInput = `请解释以下由我明确选择的阅读文本，并给出简洁说明。\n` +
      `图书：${this.currentBook.name}\n章节：${chapterTitle}\n\n选中文本：\n${this.readerSelectedText}`;
    this.readerInfo = '已提交给DSH，可稍后到DSH页面查看';
    await this.SendCopilotMessage();
    if (this.copilotStatus.length > 0 && !this.IsCopilotTurnActive()) this.readerInfo = this.copilotStatus;
  }

  private ReaderBackgroundColor(): string {
    if (this.readerSettings.theme === 'night') return '#171A20';
    if (this.readerSettings.theme === 'green') return '#DDEBDD';
    if (this.readerSettings.theme === 'white') return '#FFFFFF';
    return '#F6F0E3';
  }

  private ReaderControlSurfaceColor(): string {
    return this.ReaderBackgroundColor();
  }

  private ReaderControlSurfaceTintColor(): string {
    return this.readerSettings.theme === 'night' ? '#252938' : '#F1EBDD';
  }

  private ReaderPreviousLabel(): string {
    return this.pdfMode || this.comicImageUris.length > 0 || !this.ReaderUsesContinuousText() ?
      '上一页' : '上一章';
  }

  private ReaderNextLabel(): string {
    return this.pdfMode || this.comicImageUris.length > 0 || !this.ReaderUsesContinuousText() ?
      '下一页' : '下一章';
  }

  private ReaderRemainingLabel(): string {
    if (this.pdfMode && this.pdfPageCount > 0) {
      return `还剩 ${Math.max(0, this.pdfPageCount - this.pdfPageIndex - 1)} 页`;
    }
    if (this.comicImageUris.length > 0) {
      return `还剩 ${Math.max(0, this.comicImageUris.length - this.comicPageIndex - 1)} 页`;
    }
    if (this.readerTextPages.length > 0) {
      return `本章还剩 ${Math.max(0, this.readerTextPages.length - this.readerTextPageIndex - 1)} 页`;
    }
    return `全书 ${this.currentBook?.progress ?? 0}%`;
  }

  private ToggleReaderNightMode(): void {
    const target: ReaderTheme = this.readerSettings.theme === 'night' ? 'paper' : 'night';
    this.readerSettings = this.CopyReaderSettings(this.readerSettings.fontSize, this.readerSettings.lineHeight,
      this.readerSettings.horizontalPadding, this.readerSettings.brightness, target,
      this.readerSettings.pageTurnMode);
    this.PersistReaderState();
  }

  private async SeekReaderProgress(value: number): Promise<void> {
    const progress = Math.max(0, Math.min(99, Math.round(value)));
    if (this.currentBook === undefined) return;
    if (this.pdfMode && this.pdfPageCount > 0) {
      const page = Math.max(0, Math.min(this.pdfPageCount - 1,
        Math.floor(progress * this.pdfPageCount / 100)));
      this.pdfController.goToPage(page);
      this.pdfPageIndex = page;
      this.currentBook.progress = Math.round((page + 1) * 100 / this.pdfPageCount);
      this.ScheduleReaderProgressSave();
      return;
    }
    if (this.comicImageUris.length > 0) {
      const page = Math.max(0, Math.min(this.comicImageUris.length - 1,
        Math.floor(progress * this.comicImageUris.length / 100)));
      this.UpdateComicPosition(page);
      return;
    }
    if (this.readerChapters.length === 0) return;
    const scaled = progress * this.readerChapters.length / 100;
    this.readerChapterIndex = Math.max(0, Math.min(this.readerChapters.length - 1, Math.floor(scaled)));
    if (this.currentBook.origin === 'source') {
      await this.LoadSourceChapter();
    } else {
      this.readerContent = this.readerChapters[this.readerChapterIndex].content;
    }
    this.RebuildReaderPages(progress);
    this.ScheduleReaderProgressSave();
  }

  private ReaderTextColor(): string {
    return this.readerSettings.theme === 'night' ? '#E4E6EB' : '#25282E';
  }

  private ReaderSecondaryColor(): string {
    return this.readerSettings.theme === 'night' ? '#9EA4AF' : '#74706A';
  }

  private ReaderDividerColor(): string {
    return this.readerSettings.theme === 'night' ? '#343945' : '#DED8CC';
  }

  private PersistReaderState(): void {
    this.readerRepository.save(this.books, this.shelfBookTombstones, this.bookSources, this.bookSourceTombstones,
      this.readingProgress,
      this.readingProgressTombstones,
      this.readerBookmarks, this.readerAnnotations, this.readerMarkerTombstones,
      this.readingStatistics, this.readerSettings);
  }

  private ApplyReaderSnapshot(snapshot: ReaderSnapshot): void {
    this.books = snapshot.books;
    this.shelfBookTombstones = snapshot.shelfTombstones;
    this.bookSources = snapshot.sources;
    this.bookSourceTombstones = snapshot.sourceTombstones;
    this.readingProgress = snapshot.progress;
    this.readingProgressTombstones = snapshot.progressTombstones;
    this.readerBookmarks = snapshot.bookmarks;
    this.readerAnnotations = snapshot.annotations;
    this.readerMarkerTombstones = snapshot.markerTombstones;
    this.readingStatistics = snapshot.statistics;
    this.readerSettings = snapshot.settings;
  }

  private SwitchReaderStorageScope(context: common.UIAbilityContext, scope: string): void {
    if (this.readerVisible) this.CloseReader();
    this.activeSourceCatalogCancellation?.cancel();
    this.activeSourceCatalogCancellation = undefined;
    this.PersistReaderState();
    this.InvalidateBookSourceOperation();
    this.opdsNavigationRevision++;
    this.opdsCredentialRevision++;
    this.opdsLoading = false;
    this.opdsCredentialBusy = false;
    this.opdsCredentialVisible = false;
    this.opdsUsername = '';
    this.opdsPassword = '';
    this.opdsCredentialStatus = '';
    this.bookSourceCredentialStore.selectScope(scope);
    this.opdsCredentialStore.selectScope(scope);
    this.readerStorageScope = scope;
    this.ApplyReaderSnapshot(this.readerRepository.restore(context, scope));
    this.bookDetailVisible = false;
    this.detailBook = undefined;
    this.sourceSearchResults = [];
    this.opdsBooks = [];
    try {
      const storedOpdsUrl = this.settingsPreferences?.getSync(this.OpdsCatalogPreferenceKey(), '');
      this.opdsUrl = typeof storedOpdsUrl === 'string' ? storedOpdsUrl : '';
    } catch (_) {
      this.opdsUrl = '';
    }
    this.remoteBookItems = [];
    this.remoteBookCoverEntries = [];
    this.ClearReaderSelection();
    this.CacheShelfCovers(context);
  }

  private OpdsCatalogPreferenceKey(): string {
    if (this.readerStorageScope === 'device:v2:guest') return 'opds_catalog_url';
    return `opds_catalog_url_${this.readerStorageScope.substring('account:v2:'.length)}`;
  }

  private ReaderThemeLabel(theme: string): string {
    if (theme === 'white') return '白色';
    if (theme === 'green') return '护眼';
    if (theme === 'night') return '夜间';
    return '纸张';
  }

  private RefreshRemoteCacheUsage(): void {
    const hostContext = this.getUIContext().getHostContext();
    if (hostContext === undefined) return;
    const context = hostContext as common.UIAbilityContext;
    this.profileCoverCacheBytes = this.remoteCoverCache.size(context);
    this.profileComicCacheBytes = this.remoteCoverCache.readerPageSize(context);
    this.profileMediaCacheBytes = this.mediaThumbnailCache.size(context);
  }

  private ClearRemoteImageCaches(): void {
    const hostContext = this.getUIContext().getHostContext();
    if (hostContext === undefined) {
      this.profileCacheStatus = '无法获取应用缓存目录';
      return;
    }
    try {
      const context = hostContext as common.UIAbilityContext;
      const released = this.remoteCoverCache.clear(context) + this.remoteCoverCache.clearReaderPages(context) +
        this.mediaThumbnailCache.clear(context);
      this.books.forEach((book: Book) => {
        if ((book.remoteCoverUrl ?? '').length > 0) book.coverUrl = '';
      });
      this.sourceSearchResults.forEach((result: BookSourceSearchResult) => {
        if ((result.remoteCoverUrl ?? '').length > 0) result.coverUrl = '';
      });
      this.opdsBooks.forEach((entry: OpdsBookEntry) => {
        if ((entry.remoteCoverUrl ?? '').length > 0) entry.coverUrl = '';
      });
      this.mediaThumbnailEntries = [];
      this.SetMediaBrowseThumbnailEntries([]);
      this.mediaBrowseThumbnailFailedPaths = [];
      this.PersistReaderState();
      this.RefreshRemoteCacheUsage();
      this.profileCacheStatus = released > 0 ? `已释放 ${this.FormatFileSize(released)}` : '当前没有远程图片缓存';
    } catch (error) {
      this.profileCacheStatus = error instanceof Error ? error.message : '远程图片缓存清理失败';
    }
  }

  private RestoreCopilotConversations(value: string): void {
    try {
      if (value.length === 0 || value.length > 2 * 1024 * 1024) {
        this.copilotConversations = [];
        return;
      }
      const parsed = JSON.parse(value) as CopilotConversation[];
      this.copilotConversations = this.copilotConversationSnapshotPolicy.normalize(parsed);
    } catch (_) {
      this.copilotConversations = [];
    }
  }

  private CurrentCopilotConversations(): CopilotConversation[] {
    const owner = this.localAccountScope;
    if (owner.length === 0) return [];
    return this.copilotConversations.filter((conversation: CopilotConversation) => conversation.owner === owner)
      .sort((left: CopilotConversation, right: CopilotConversation) => right.updatedAt - left.updatedAt).slice(0, 12);
  }

  private ActivateLatestCopilotConversation(): void {
    const conversations = this.CurrentCopilotConversations();
    if (conversations.length === 0) {
      this.StartNewCopilotConversation(false);
      return;
    }
    this.SwitchCopilotConversation(conversations[0].id);
  }

  private async StartNewCopilotConversation(userInitiated: boolean): Promise<void> {
    if (this.IsCopilotTurnActive() || this.copilotBusy || this.copilotRecoveryPlan !== undefined) return;
    const owner = this.localAccountScope;
    if (owner.length === 0 || this.dshApi === undefined) return;
    this.UpdateActiveCopilotConversation('');
    this.copilotBusy = true;
    try {
      const session = await this.dshApi.createSession();
      const conversation: CopilotConversation = {
        id: session.sessionId,
        owner: owner,
        title: userInitiated ? '新会话' : '开始对话',
        messages: [],
        updatedAt: session.updatedAt
      };
      this.copilotConversations = this.BoundedCopilotConversations([conversation].concat(this.copilotConversations));
      this.activeCopilotConversationId = conversation.id;
      this.copilotMessages = [];
      this.copilotRunSteps = [];
      this.copilotAttachment = undefined;
      this.copilotAttachmentStatus = '';
      this.copilotDeleteConfirm = false;
      this.copilotTurn = undefined;
      this.copilotStatus = '';
      this.PersistCopilotConversations();
    } catch (error) {
      this.copilotStatus = error instanceof Error ? error.message : '创建DSH会话失败';
    } finally {
      this.copilotBusy = false;
    }
  }

  private async SwitchCopilotConversation(conversationId: string): Promise<void> {
    if (this.IsCopilotTurnActive() || this.copilotBusy || this.copilotRecoveryPlan !== undefined) return;
    const target = this.CurrentCopilotConversations().find((conversation: CopilotConversation) =>
      conversation.id === conversationId);
    if (target === undefined) return;
    this.UpdateActiveCopilotConversation('');
    this.CancelCopilotRefresh();
    this.activeCopilotConversationId = target.id;
    this.copilotMessages = target.messages.slice();
    this.copilotRunSteps = [];
    this.copilotAttachment = undefined;
    this.copilotAttachmentStatus = '';
    this.copilotDeleteConfirm = false;
    this.copilotTurn = undefined;
    this.copilotPollingTimedOut = false;
    this.copilotStatus = '正在读取DSH会话';
    if (this.dshApi === undefined) return;
    try {
      const history = await this.dshApi.history(target.id);
      this.copilotMessages = history.messages.map((message): CopilotMessage => ({
        id: message.id,
        role: message.role,
        text: message.text,
        status: message.status.length > 0 ? message.status : 'done'
      }));
      this.copilotRunSteps = history.steps;
      this.copilotStatus = '';
      this.UpdateActiveCopilotConversation('');
    } catch (error) {
      this.copilotStatus = error instanceof Error ? error.message : '读取DSH会话失败';
    }
  }

  private async DeleteCurrentCopilotConversation(): Promise<void> {
    if (!this.copilotDeleteConfirm || this.IsCopilotTurnActive() || this.copilotBusy ||
      this.copilotRecoveryPlan !== undefined) return;
    const owner = this.localAccountScope;
    const id = this.activeCopilotConversationId;
    const target = this.copilotConversations.find((conversation: CopilotConversation) =>
      conversation.id === id && conversation.owner === owner);
    if (target === undefined) {
      this.copilotDeleteConfirm = false;
      return;
    }
    try {
      if (this.dshApi === undefined) throw new Error('DSH尚未连接');
      await this.dshApi.archiveSession(target.id);
      this.copilotConversations = this.copilotConversations.filter((conversation: CopilotConversation) =>
        conversation.id !== target.id || conversation.owner !== owner);
      this.activeCopilotConversationId = '';
      this.copilotMessages = [];
      this.copilotRunSteps = [];
      this.copilotTurn = undefined;
      this.copilotDeleteConfirm = false;
      this.PersistCopilotConversations();
      const next = this.CurrentCopilotConversations()[0];
      if (next === undefined) {
        this.StartNewCopilotConversation(false);
      } else {
        this.SwitchCopilotConversation(next.id);
      }
      this.copilotStatus = '当前会话已删除';
    } catch (error) {
      this.copilotStatus = error instanceof Error ? error.message : '删除DSH会话失败';
      this.copilotDeleteConfirm = false;
    }
  }

  private UpdateActiveCopilotConversation(firstUserText: string): void {
    const id = this.activeCopilotConversationId;
    const owner = this.localAccountScope;
    if (id.length === 0 || owner.length === 0) return;
    this.copilotConversations = this.copilotConversations.map((conversation: CopilotConversation) => {
      if (conversation.id !== id || conversation.owner !== owner) return conversation;
      const title = conversation.messages.length === 0 && firstUserText.trim().length > 0 ?
        firstUserText.trim().replace(/[\r\n\t]+/g, ' ').substring(0, 40) : conversation.title;
      const persistedMessages = this.copilotMessages.slice(-20).map((message: CopilotMessage): CopilotMessage => ({
        id: message.id.substring(0, 160),
        role: message.role,
        text: message.text.substring(0, 4000),
        status: message.status === 'sent' ? 'sent' : 'done'
      }));
      return { id: conversation.id, owner: conversation.owner, title: title,
        messages: persistedMessages, updatedAt: Date.now() };
    });
    this.PersistCopilotConversations();
  }

  private PersistCopilotConversations(): void {
    try {
      const candidates = this.copilotPersistPreview ? this.copilotConversations :
        this.copilotConversations.filter((conversation: CopilotConversation) =>
          conversation.owner !== this.localAccountScope);
      this.settingsPreferences?.putSync('copilot_conversations',
        JSON.stringify(this.copilotConversationSnapshotPolicy.normalize(candidates)));
      this.settingsPreferences?.flush();
    } catch (_) {
      this.copilotStatus = 'DSH会话索引保存失败';
    }
  }

  private SetCopilotPreviewPersistence(enabled: boolean): void {
    try {
      if (this.localAccountScope.length === 0) throw new Error('DSH账户作用域尚未建立');
      const policies = this.ReadCopilotPreviewPolicies()
        .filter((policy: CopilotPreviewPolicy) => policy.owner !== this.localAccountScope);
      policies.unshift({ owner: this.localAccountScope, enabled: enabled });
      this.settingsPreferences?.putSync('copilot_preview_policies', JSON.stringify(policies.slice(0, 50)));
      const candidates = enabled ? this.copilotConversations :
        this.copilotConversations.filter((conversation: CopilotConversation) =>
          conversation.owner !== this.localAccountScope);
      this.settingsPreferences?.putSync('copilot_conversations',
        JSON.stringify(this.copilotConversationSnapshotPolicy.normalize(candidates)));
      this.settingsPreferences?.flush();
      this.copilotPersistPreview = enabled;
      this.copilotStatus = enabled ? '后续会话预览将保存在本机' : '已删除本机会话预览；当前内存会话仍可继续使用';
    } catch (_) {
      this.copilotStatus = 'DSH预览保留设置保存失败';
    }
  }

  private BoundedCopilotConversations(conversations: CopilotConversation[]): CopilotConversation[] {
    return this.copilotConversationSnapshotPolicy.normalize(conversations);
  }

  private ReadCopilotPreviewPolicies(): CopilotPreviewPolicy[] {
    try {
      const stored = this.settingsPreferences?.getSync('copilot_preview_policies', '');
      if (typeof stored !== 'string' || stored.length === 0 || stored.length > 16384) return [];
      const parsed = JSON.parse(stored) as CopilotPreviewPolicy[];
      if (!Array.isArray(parsed)) return [];
      return parsed.filter((policy: CopilotPreviewPolicy) => typeof policy.owner === 'string' &&
        /^account:v2:[a-f0-9]{64}$/.test(policy.owner) && typeof policy.enabled === 'boolean').slice(0, 50);
    } catch (_) {
      return [];
    }
  }

  private async PrepareLocalAccountScope(username: string): Promise<void> {
    const normalizedUsername = username.trim();
    if (this.serviceUrl.length === 0 || normalizedUsername.length === 0) {
      throw new Error('无法建立本地账户作用域');
    }
    const digest = await this.digestTool.digestText(`v2\u0000${this.serviceUrl}\u0000${normalizedUsername}`, 'SHA256');
    this.localAccountScope = `account:v2:${digest}`;
    const policy = this.ReadCopilotPreviewPolicies().find((item: CopilotPreviewPolicy) =>
      item.owner === this.localAccountScope);
    this.copilotPersistPreview = policy?.enabled ?? true;
    // 旧版仅按用户名保存，无法证明所属服务；成功登录后清除而不进行不安全归属迁移。
    this.copilotConversations = this.copilotConversations.filter((conversation: CopilotConversation) =>
      conversation.owner !== normalizedUsername && (this.copilotPersistPreview ||
        conversation.owner !== this.localAccountScope));
    this.mediaFavorites = this.mediaFavorites.filter((favorite: MediaFavorite) =>
      favorite.owner !== normalizedUsername);
    this.PersistCopilotConversations();
    this.PersistMediaFavorites();
  }

  private async SelectCopilotTextAttachment(): Promise<void> {
    if (this.copilotBusy || this.IsCopilotTurnActive()) return;
    const hostContext = this.getUIContext().getHostContext();
    if (hostContext === undefined) {
      this.copilotAttachmentStatus = '无法打开系统文件选择器';
      return;
    }
    let file: fs.File | undefined = undefined;
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    const lifecycleRevision = this.pageLifecycleRevision;
    try {
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      options.fileSuffixFilters = ['文本附件|txt,md,json,csv'];
      const uris = await new picker.DocumentViewPicker(hostContext as common.UIAbilityContext).select(options);
      if (!this.PickerContextCurrent(accountRevision, owner, lifecycleRevision)) return;
      if (uris.length === 0) {
        this.copilotAttachmentStatus = '已取消选择附件';
        return;
      }
      const uri = uris[0];
      file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      const stat = fs.statSync(file.fd);
      if (!stat.isFile() || stat.size <= 0 || stat.size > 65536) throw new Error('文本附件必须为1 B至64 KB的普通文件');
      const buffer = new ArrayBuffer(stat.size);
      const readBytes = fs.readSync(file.fd, buffer);
      if (readBytes !== stat.size) throw new Error('文本附件读取长度不完整');
      const text = util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(new Uint8Array(buffer));
      if (!this.PickerContextCurrent(accountRevision, owner, lifecycleRevision)) return;
      if (text.length === 0 || text.length > 32768 || text.includes('\u0000')) {
        throw new Error('附件不是有效的UTF-8文本或超过32768字符');
      }
      let name = decodeURIComponent(uri.substring(uri.lastIndexOf('/') + 1)).trim();
      if (name.length === 0) name = 'attachment.txt';
      name = name.replace(/[\u0000-\u001F\u007F]/g, '').substring(0, 256);
      const extension = name.toLowerCase().substring(name.lastIndexOf('.') + 1);
      let mimeType = 'text/plain';
      if (extension === 'md') mimeType = 'text/markdown';
      if (extension === 'json') mimeType = 'application/json';
      if (extension === 'csv') mimeType = 'text/csv';
      this.copilotAttachment = { name: name, mimeType: mimeType, text: text, size: stat.size };
      this.copilotAttachmentStatus = '附件正文将在本轮发送到远程DSH；不保存设备URI';
    } catch (error) {
      this.copilotAttachment = undefined;
      this.copilotAttachmentStatus = error instanceof Error ? error.message : '读取文本附件失败';
    } finally {
      if (file !== undefined) {
        try {
          fs.closeSync(file);
        } catch (_) {
        }
      }
    }
  }

  private async InitializeCopilot(): Promise<void> {
    const revision = ++this.copilotOperationRevision;
    try {
      if (!this.authenticated) throw new Error('请先登录MyTools');
      this.copilotStatus = '正在连接远程DSH';
      const api = new DshApi(this.ActiveServiceUrl(), this.authManager);
      const status = await api.status();
      if (!status.enabled || !status.connected) throw new Error('远程DSH当前不可用');
      if (revision !== this.copilotOperationRevision || !this.authenticated) return;
      this.dshApi = api;
      this.copilotVersion = status.version;
      this.copilotGatewayModel = status.model;
      this.copilotInitialized = true;
      this.copilotRecoveryPlan = undefined;
      const remoteSessions = await api.sessions();
      if (revision !== this.copilotOperationRevision || !this.authenticated) return;
      const cached = this.copilotConversations;
      this.copilotConversations = this.BoundedCopilotConversations(remoteSessions.map((session): CopilotConversation => {
        const local = cached.find((conversation: CopilotConversation) => conversation.id === session.sessionId &&
          conversation.owner === this.localAccountScope);
        return {
          id: session.sessionId,
          owner: this.localAccountScope,
          title: session.title === 'New session' && local !== undefined ? local.title : session.title,
          messages: local?.messages ?? [],
          updatedAt: session.updatedAt
        };
      }).concat(cached.filter((conversation: CopilotConversation) => conversation.owner !== this.localAccountScope)));
      if (remoteSessions.length === 0) {
        await this.StartNewCopilotConversation(false);
      } else {
        await this.SwitchCopilotConversation(remoteSessions[0].sessionId);
      }
      this.copilotStatus = `DSH已连接 · ${status.provider}`;
    } catch (error) {
      if (revision !== this.copilotOperationRevision) return;
      this.copilotInitialized = false;
      this.dshApi = undefined;
      this.copilotStatus = error instanceof Error ? error.message : 'DSH初始化失败';
    }
  }

  private async SendCopilotMessage(): Promise<void> {
    const inputText = this.copilotInput.trim();
    const attachment = this.copilotAttachment;
    if ((inputText.length === 0 && attachment === undefined) || this.copilotBusy || this.IsCopilotTurnActive()) return;
    const text = inputText.length > 0 ? inputText : '请分析这个文本附件。';
    const revision = ++this.copilotOperationRevision;
    this.copilotBusy = true;
    this.CancelCopilotRefresh();
    this.copilotPollStartedAt = Date.now();
    this.copilotPollingTimedOut = false;
    this.copilotInput = '';
    this.copilotMessages = this.copilotMessages.concat({
      id: `user-${Date.now()}`,
      role: 'user',
      text: attachment === undefined ? text : `${text}\n[文本附件：${attachment.name}]`,
      status: 'sent'
    });
    this.UpdateActiveCopilotConversation(text);
    try {
      if (this.dshApi === undefined || this.activeCopilotConversationId.length === 0) {
        throw new Error('DSH会话尚未建立');
      }
      const prompt = attachment === undefined ? text : `${text}\n\n附件名称：${attachment.name}\n` +
        `附件类型：${attachment.mimeType}\n\n${attachment.text}`;
      await this.dshApi.prompt(this.activeCopilotConversationId, prompt);
      if (revision !== this.copilotOperationRevision || !this.authenticated) return;
      this.copilotAttachment = undefined;
      this.copilotAttachmentStatus = '';
      this.copilotTurn = {
        handle: 0,
        sessionId: this.activeCopilotConversationId,
        turnId: `dsh-${Date.now()}`,
        phase: 'running',
        revision: 0,
        waitingReason: '',
        finalMessage: ''
      };
      this.copilotStatus = 'DSH正在处理';
      this.copilotBusy = false;
      this.ScheduleCopilotRefresh(this.copilotTurn.turnId);
    } catch (error) {
      if (revision !== this.copilotOperationRevision) return;
      this.copilotStatus = error instanceof Error ? error.message : 'DSH执行失败';
      this.copilotBusy = false;
      this.CancelCopilotRefresh();
    }
  }

  private async RecoverCopilotTurn(): Promise<void> {
    this.copilotRecoveryPlan = undefined;
    this.copilotStatus = 'DSH会话历史会在重新进入时自动恢复';
  }

  private ApplyCopilotSnapshot(snapshot: CopilotTurnSnapshot): void {
    this.copilotTurn = snapshot;
    this.copilotPollingTimedOut = false;
    if (snapshot.finalMessage.length > 0) {
      this.copilotMessages = this.copilotMessages.concat({
        id: `assistant-${snapshot.turnId}-${snapshot.revision}`,
        role: 'assistant',
        text: snapshot.finalMessage,
        status: 'done'
      });
      this.UpdateActiveCopilotConversation('');
      this.copilotStatus = '';
      this.copilotBusy = false;
      this.CancelCopilotRefresh();
      return;
    }
    if (snapshot.permission !== undefined) {
      this.copilotStatus = 'DSH正在等待你的授权决定';
      this.copilotBusy = false;
      this.CancelCopilotRefresh();
      return;
    }
    this.copilotStatus = `DSH：${snapshot.phase}`;
    if (snapshot.phase === 'done' || snapshot.phase === 'failed' || snapshot.phase === 'blocked' ||
      snapshot.phase === 'cancelled') {
      this.copilotBusy = false;
      this.CancelCopilotRefresh();
      return;
    }
    if (this.copilotPollStartedAt === 0) this.copilotPollStartedAt = Date.now();
    this.ScheduleCopilotRefresh(snapshot.turnId);
  }

  private ScheduleCopilotRefresh(turnId: string): void {
    if (this.copilotRefreshTimerId >= 0) clearTimeout(this.copilotRefreshTimerId);
    this.copilotRefreshTimerId = setTimeout(() => {
      this.copilotRefreshTimerId = -1;
      this.RefreshCopilotTurn(turnId);
    }, 250);
  }

  private CancelCopilotRefresh(): void {
    if (this.copilotRefreshTimerId >= 0) {
      clearTimeout(this.copilotRefreshTimerId);
      this.copilotRefreshTimerId = -1;
    }
    this.copilotPollStartedAt = 0;
  }

  private async RefreshCopilotTurn(expectedTurnId: string): Promise<void> {
    const current = this.copilotTurn;
    if (current === undefined || current.turnId !== expectedTurnId) return;
    try {
      if (this.dshApi === undefined) throw new Error('DSH尚未连接');
      const history = await this.dshApi.history(current.sessionId);
      const sessions = await this.dshApi.sessions();
      const session = sessions.find((item) => item.sessionId === current.sessionId);
      this.copilotMessages = history.messages.map((message): CopilotMessage => ({
        id: message.id,
        role: message.role,
        text: message.text,
        status: message.status.length > 0 ? message.status : 'done'
      }));
      this.copilotRunSteps = history.steps;
      this.UpdateActiveCopilotConversation('');
      if (session?.running === true) {
        this.copilotStatus = 'DSH正在处理';
        if (this.copilotPollStartedAt > 0 && Date.now() - this.copilotPollStartedAt < 120000) {
          this.ScheduleCopilotRefresh(expectedTurnId);
        } else {
          this.copilotStatus = 'DSH仍在处理，可稍后重新进入查看';
          this.copilotBusy = false;
          this.copilotPollingTimedOut = true;
          this.CancelCopilotRefresh();
        }
        return;
      }
      const last = history.messages.length > 0 ? history.messages[history.messages.length - 1] : undefined;
      if (last?.role !== 'assistant' && this.copilotPollStartedAt > 0 &&
        Date.now() - this.copilotPollStartedAt < 120000) {
        // DSH运行状态和历史落盘不是同一个响应，等待最终助手消息完成投影。
        this.copilotStatus = 'DSH正在同步最终回复';
        this.ScheduleCopilotRefresh(expectedTurnId);
        return;
      }
      this.copilotTurn = {
        handle: 0,
        sessionId: current.sessionId,
        turnId: current.turnId,
        phase: 'done',
        revision: history.lastSeq,
        waitingReason: '',
        finalMessage: last?.role === 'assistant' ? last.text : ''
      };
      this.copilotStatus = '';
      this.copilotBusy = false;
      this.copilotPollingTimedOut = false;
      this.CancelCopilotRefresh();
    } catch (error) {
      this.copilotStatus = error instanceof Error ? error.message : '刷新DSH状态失败';
      this.copilotBusy = false;
      this.copilotPollingTimedOut = this.IsCopilotTurnActive();
      this.CancelCopilotRefresh();
    }
  }

  private IsCopilotTurnActive(): boolean {
    const snapshot = this.copilotTurn;
    if (snapshot === undefined || snapshot.finalMessage.length > 0) return false;
    return snapshot.phase !== 'done' && snapshot.phase !== 'failed' && snapshot.phase !== 'blocked' &&
      snapshot.phase !== 'cancelled';
  }

  private RefreshCurrentCopilotTurn(): void {
    const current = this.copilotTurn;
    if (current === undefined || !this.IsCopilotTurnActive() || this.copilotBusy) return;
    this.copilotBusy = true;
    this.copilotPollingTimedOut = false;
    this.copilotPollStartedAt = Date.now();
    this.RefreshCopilotTurn(current.turnId);
  }

  private async CancelCurrentCopilotTurn(): Promise<void> {
    const current = this.copilotTurn;
    if (current === undefined || !this.IsCopilotTurnActive() || this.copilotCancelling) return;
    const revision = ++this.copilotOperationRevision;
    this.copilotCancelling = true;
    this.CancelCopilotRefresh();
    try {
      if (this.dshApi === undefined) throw new Error('DSH尚未连接');
      await this.dshApi.cancel(current.sessionId);
      if (revision !== this.copilotOperationRevision) return;
      this.copilotTurn = {
        handle: current.handle, sessionId: current.sessionId, turnId: current.turnId, phase: 'cancelled',
        revision: current.revision, waitingReason: current.waitingReason, finalMessage: current.finalMessage
      };
      this.copilotBusy = false;
      this.copilotStatus = '当前轮次已由DSH取消';
    } catch (error) {
      if (revision !== this.copilotOperationRevision) return;
      this.copilotStatus = error instanceof Error ? error.message : '取消DSH轮次失败';
      this.copilotPollingTimedOut = this.IsCopilotTurnActive();
    } finally {
      if (revision === this.copilotOperationRevision) this.copilotCancelling = false;
    }
  }

  private async ReplyCopilotPermission(allow: boolean): Promise<void> {
    const current = this.copilotTurn;
    if (current === undefined || current.permission === undefined) return;
    const revision = ++this.copilotOperationRevision;
    try {
      this.copilotBusy = true;
      this.CancelCopilotRefresh();
      this.copilotPollStartedAt = Date.now();
      if (this.dshApi === undefined) throw new Error('DSH尚未连接');
      await this.dshApi.replyApproval(current.sessionId, current.permission.requestId, allow);
      if (revision !== this.copilotOperationRevision || !this.authenticated) return;
      this.copilotStatus = allow ? '已仅授权本次操作' : '已拒绝操作';
      this.copilotTurn = {
        handle: current.handle, sessionId: current.sessionId, turnId: current.turnId, phase: 'running',
        revision: current.revision, waitingReason: '', finalMessage: current.finalMessage
      };
      this.ScheduleCopilotRefresh(current.turnId);
    } catch (error) {
      if (revision !== this.copilotOperationRevision) return;
      this.copilotStatus = error instanceof Error ? error.message : '提交授权决定失败';
      this.copilotBusy = false;
      this.CancelCopilotRefresh();
    }
  }

  private async AddCopilotBookmark(bookId: string, chapterTitle: string, locator: number,
    note: string): Promise<Object> {
    const normalizedTitle = chapterTitle.trim();
    const normalizedNote = note.trim();
    if (bookId.length === 0 || bookId.length > 256 || normalizedTitle.length === 0 ||
      normalizedTitle.length > 500 || normalizedNote.length > 500 || /[\u0000-\u001F\u007F]/.test(normalizedTitle) ||
      !Number.isFinite(locator) || locator < 0 || locator > 1000000000) throw new Error('书签参数超出允许范围');
    const book = this.books.find((value: Book) => value.id === bookId);
    if (book === undefined) throw new Error('书签目标不在当前书架');
    const normalizedLocator = Math.max(0, Math.floor(locator));
    const existing = this.readerBookmarks.find((value: ReaderBookmark) => value.bookId === bookId &&
      value.chapterTitle === normalizedTitle && value.locator === normalizedLocator);
    if (existing !== undefined) throw new Error('该阅读位置已经存在书签');
    const bookmark: ReaderBookmark = {
      id: `bookmark-${util.generateRandomUUID(true)}`,
      bookId: bookId,
      chapterTitle: normalizedTitle,
      locator: normalizedLocator,
      note: normalizedNote,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      revision: 0
    };
    this.readerBookmarks = this.readerBookmarks.concat(bookmark);
    this.PersistReaderState();
    this.QueueReaderMarker({ id: bookmark.id, kind: 'BOOKMARK', bookId: bookmark.bookId,
      chapterTitle: bookmark.chapterTitle, locator: bookmark.locator, note: bookmark.note,
      createdAt: bookmark.createdAt, updatedAt: bookmark.updatedAt ?? bookmark.createdAt,
      deleted: false, revision: bookmark.revision ?? 0 });
    const result: Record<string, Object> = {};
    result['success'] = true;
    result['bookmark_id'] = bookmark.id;
    result['book_name'] = book.name;
    result['chapter_title'] = normalizedTitle;
    return result;
  }

  private async AddCopilotNote(bookId: string, chapterTitle: string, locator: number,
    note: string): Promise<Object> {
    const normalizedTitle = chapterTitle.trim();
    const normalizedNote = note.trim();
    if (bookId.length === 0 || bookId.length > 256 || normalizedTitle.length === 0 ||
      normalizedTitle.length > 500 || normalizedNote.length === 0 || normalizedNote.length > 1000 ||
      /[\u0000-\u001F\u007F]/.test(normalizedTitle) ||
      /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/.test(normalizedNote) ||
      !Number.isFinite(locator) || locator < 0 || locator > 1000000000) throw new Error('批注参数超出允许范围');
    const book = this.books.find((value: Book) => value.id === bookId);
    if (book === undefined) throw new Error('批注目标不在当前书架');
    const normalizedLocator = Math.max(0, Math.floor(locator));
    const annotation: ReaderAnnotation = {
      id: `annotation-${util.generateRandomUUID(true)}`,
      bookId: bookId,
      chapterTitle: normalizedTitle,
      locator: normalizedLocator,
      note: normalizedNote,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      revision: 0
    };
    this.readerAnnotations = this.readerAnnotations.concat(annotation);
    this.PersistReaderState();
    this.QueueReaderMarker({ id: annotation.id, kind: 'ANNOTATION', bookId: annotation.bookId,
      chapterTitle: annotation.chapterTitle, locator: annotation.locator, note: annotation.note,
      createdAt: annotation.createdAt, updatedAt: annotation.updatedAt ?? annotation.createdAt,
      deleted: false, revision: annotation.revision ?? 0 });
    const result: Record<string, Object> = {};
    result['success'] = true;
    result['annotation_id'] = annotation.id;
    result['book_name'] = book.name;
    result['chapter_title'] = normalizedTitle;
    return result;
  }

  private async AddCopilotMediaFavorite(accountId: string, path: string): Promise<Object> {
    if (accountId !== this.selectedMediaSourceId) throw new Error('收藏目标不属于当前远程数据源');
    const target = this.mediaItems.find((value: RemoteMediaItem) => value.path === path && value.kind !== 'directory');
    if (target === undefined) throw new Error('收藏目标不在当前可见媒体列表');
    const alreadyFavorite = this.IsMediaFavorite(accountId, path);
    this.AddMediaFavorite(target);
    const result: Record<string, Object> = {};
    result['success'] = true;
    result['account_id'] = accountId;
    result['path'] = target.path;
    result['name'] = target.name;
    result['already_favorite'] = alreadyFavorite;
    return result;
  }

  private async EnqueueCopilotDownload(accountId: string, path: string): Promise<Object> {
    if (accountId !== this.selectedMediaSourceId) throw new Error('下载目标不属于当前远程数据源');
    const target = this.mediaItems.find((value: RemoteMediaItem) => value.path === path && value.kind !== 'directory');
    if (target === undefined) throw new Error('下载目标不在当前可见媒体列表');
    return this.DownloadRemoteFileWithPicker(accountId, target.path, target.name);
  }

  private async AddCurrentReaderBookmark(): Promise<void> {
    const book = this.currentBook;
    if (book === undefined) return;
    const chapterTitle = this.pdfMode ? `第${this.pdfPageIndex + 1}页` :
      (this.comicImageUris.length > 0 ? `第${this.comicPageIndex + 1}页` :
        (this.readerChapters[this.readerChapterIndex]?.title ?? '正文'));
    const locator = this.pdfMode ? this.pdfPageIndex :
      (this.comicImageUris.length > 0 ? this.comicPageIndex : this.readerScrollValue);
    try {
      await this.AddCopilotBookmark(book.id, chapterTitle, locator, '');
      this.readerInfo = `已添加书签 · ${chapterTitle}`;
      this.readerControlsVisible = false;
    } catch (error) {
      this.readerInfo = error instanceof Error ? error.message : '添加书签失败';
    }
  }

  private CurrentReaderAnnotations(): ReaderAnnotation[] {
    const bookId = this.currentBook?.id ?? '';
    return this.readerAnnotations.filter((value: ReaderAnnotation) => value.bookId === bookId)
      .sort((left: ReaderAnnotation, right: ReaderAnnotation) => right.createdAt - left.createdAt);
  }

  private AddCurrentReaderAnnotation(): void {
    const book = this.currentBook;
    const note = this.readerAnnotationInput.trim();
    if (book === undefined || note.length === 0) return;
    const annotation: ReaderAnnotation = {
      id: `annotation-${util.generateRandomUUID(true)}`,
      bookId: book.id,
      chapterTitle: this.CurrentReaderLocationTitle(),
      locator: this.CurrentReaderLocator(),
      note: note.substring(0, 1000),
      createdAt: Date.now(),
      updatedAt: Date.now(),
      revision: 0
    };
    this.readerAnnotations = this.readerAnnotations.concat(annotation);
    this.readerAnnotationInput = '';
    this.readerInfo = `已保存批注 · ${annotation.chapterTitle}`;
    this.PersistReaderState();
    this.QueueReaderMarker({ id: annotation.id, kind: 'ANNOTATION', bookId: annotation.bookId,
      chapterTitle: annotation.chapterTitle, locator: annotation.locator, note: annotation.note,
      createdAt: annotation.createdAt, updatedAt: annotation.updatedAt ?? annotation.createdAt,
      deleted: false, revision: annotation.revision ?? 0 });
  }

  private DeleteReaderAnnotation(annotationId: string): void {
    const annotation = this.readerAnnotations.find((value: ReaderAnnotation) => value.id === annotationId);
    if (annotation === undefined) return;
    this.readerAnnotations = this.readerAnnotations.filter((value: ReaderAnnotation) => value.id !== annotationId);
    const tombstone: ReaderMarkerSyncItem = { id: annotation.id, kind: 'ANNOTATION', bookId: annotation.bookId,
      chapterTitle: annotation.chapterTitle, locator: annotation.locator, note: annotation.note,
      createdAt: annotation.createdAt, updatedAt: Date.now(), deleted: true, revision: annotation.revision ?? 0 };
    this.ApplyReaderMarker(tombstone);
    this.QueueReaderMarker(tombstone);
    this.PersistReaderState();
  }

  private async OpenReaderAnnotation(annotation: ReaderAnnotation): Promise<void> {
    const location: ReaderBookmark = {
      id: annotation.id,
      bookId: annotation.bookId,
      chapterTitle: annotation.chapterTitle,
      locator: annotation.locator,
      note: annotation.note,
      createdAt: annotation.createdAt
    };
    await this.OpenReaderBookmark(location);
    this.readerAnnotationVisible = false;
    this.readerInfo = `已跳转至批注 · ${annotation.chapterTitle}`;
  }

  private CurrentReaderLocationTitle(): string {
    if (this.pdfMode) return `第${this.pdfPageIndex + 1}页`;
    if (this.comicImageUris.length > 0) return `第${this.comicPageIndex + 1}页`;
    return this.readerChapters[this.readerChapterIndex]?.title ?? '正文';
  }

  private CurrentReaderLocator(): number {
    if (this.pdfMode) return this.pdfPageIndex;
    if (this.comicImageUris.length > 0) return this.comicPageIndex;
    return new ReaderTextLocatorPolicy().encode(this.CurrentTextChapterFraction());
  }

  private FinishReadingSession(): void {
    const bookId = this.currentBook?.id;
    if (bookId === undefined || this.readerSessionStartedAt <= 0) return;
    const elapsed = Math.max(0, Date.now() - this.readerSessionStartedAt);
    this.readerSessionStartedAt = 0;
    if (elapsed < 1000) return;
    const existing = this.readingStatistics.find((value: ReadingStatistic) => value.bookId === bookId);
    const updated: ReadingStatistic = {
      bookId: bookId,
      totalMillis: (existing?.totalMillis ?? 0) + elapsed,
      sessionCount: (existing?.sessionCount ?? 0) + 1,
      lastReadAt: Date.now()
    };
    this.readingStatistics = this.readingStatistics.filter((value: ReadingStatistic) => value.bookId !== bookId)
      .concat(updated);
    this.PersistReaderState();
  }

  private CurrentReadingStatisticLabel(): string {
    const bookId = this.currentBook?.id ?? '';
    const statistic = this.readingStatistics.find((value: ReadingStatistic) => value.bookId === bookId);
    const ongoing = this.readerSessionStartedAt > 0 ? Date.now() - this.readerSessionStartedAt : 0;
    const totalMinutes = Math.floor(((statistic?.totalMillis ?? 0) + ongoing) / 60000);
    return `累计阅读 ${totalMinutes} 分钟 · ${(statistic?.sessionCount ?? 0) + (ongoing > 0 ? 1 : 0)} 次`;
  }

  private TotalReadingMinutes(): number {
    return Math.floor(this.readingStatistics.reduce((total: number, value: ReadingStatistic) =>
      total + value.totalMillis, 0) / 60000);
  }

  private async DeleteCopilotRemoteFile(accountId: string, path: string): Promise<Object> {
    if (accountId.length === 0 || accountId.length > 256 || path.length === 0 || path.length > 4096 ||
      !path.startsWith('/') || /[\u0000-\u001F\u007F\\]/.test(path)) throw new Error('远程删除参数超出允许范围');
    if (accountId !== this.selectedMediaSourceId) throw new Error('只能删除当前正在浏览的数据源文件');
    const target = this.mediaItems.find((value: RemoteMediaItem) => value.path === path && value.kind !== 'directory');
    if (target === undefined) throw new Error('删除目标不在当前远程文件列表或不是普通文件');
    await new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager).deleteFile(accountId, path);
    this.mediaItems = this.mediaItems.filter((value: RemoteMediaItem) => value.path !== path);
    this.remoteBookItems = this.remoteBookItems.filter((value: RemoteMediaItem) => value.path !== path);
    this.toolFiles = this.toolFiles.filter((value: RemoteMediaItem) => value.path !== path);
    this.mediaFavorites = this.mediaFavorites.filter((favorite: MediaFavorite) =>
      favorite.accountId !== accountId || favorite.path !== path);
    this.PersistMediaFavorites();
    const result: Record<string, Object> = {};
    result['success'] = true;
    result['deleted_path'] = path;
    result['account_id'] = accountId;
    return result;
  }

  @Builder
  private MediaActionSheet(item: RemoteMediaItem) {
    Column() {
      Blank().layoutWeight(1).width('100%').onClick(() => this.CloseMediaActionSheet())
      Column({ space: 14 }) {
        Row({ space: 12 }) {
          if (this.BrowseMediaThumbnailUri(item.path).length > 0) {
            Image(this.BrowseMediaThumbnailUri(item.path)).width(58).height(58).objectFit(ImageFit.Cover)
              .borderRadius(10)
          }
          Column({ space: 5 }) {
            Text(item.name).width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              .fontSize(16).fontFamily('HarmonyOS Sans').fontWeight(FontWeight.Medium)
              .fontColor(AppTheme.textPrimary)
            Text(`${this.FormatMediaSize(item.size)} · ${(item.tags ?? []).map((tag: RemoteMediaTag) => tag.name).join(' · ')}`)
              .width('100%').maxLines(2).fontSize(12).fontFamily('HarmonyOS Sans')
              .fontColor(AppTheme.textSecondary)
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        }.width('100%')
        if (this.mediaActionError.length > 0) {
          Text(this.mediaActionError).width('100%').fontSize(12).fontFamily('HarmonyOS Sans')
            .fontColor(AppTheme.danger).padding(10).borderRadius(10).backgroundColor(AppTheme.dangerSoft)
        }
        if (this.mediaActionMode === 'menu') {
          Row({ space: 8 }) {
            this.MediaActionButton('重命名', () => this.BeginMediaAction('rename', item.name))
            this.MediaActionButton('移动到', () => this.BeginMediaAction('move', '/'))
          }.width('100%')
          Row({ space: 8 }) {
            this.MediaActionButton('管理标签', () => this.BeginMediaAction('tags',
              (item.tags ?? []).map((tag: RemoteMediaTag) => tag.name).join(',')))
            this.MediaActionButton('查看详情', () => this.BeginMediaAction('details', ''))
          }.width('100%')
          Button('删除').width('100%').height(48).fontFamily('HarmonyOS Sans')
            .fontColor(AppTheme.danger).backgroundColor(AppTheme.dangerSoft)
            .onClick(() => this.mediaActionMode = 'delete-confirm')
        } else if (this.mediaActionMode === 'details') {
          Text(`类型：${this.MediaKindLabel(item)}\n大小：${this.FormatMediaSize(item.size)}\n目录：${item.directoryName ?? '根目录'}\n更新时间：${item.lastModified}\n标签：${(item.tags ?? []).map((tag: RemoteMediaTag) => tag.name).join('、') || '无'}\n路径：${item.path}`)
            .width('100%').fontSize(14).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textPrimary)
        } else {
          if (this.mediaActionMode !== 'delete-confirm') {
            TextInput({ text: this.mediaActionInput, placeholder: this.MediaActionPlaceholder() })
              .width('100%').height(50).fontFamily('HarmonyOS Sans')
              .onChange((value: string) => this.mediaActionInput = value)
          }
          if (this.mediaActionMode === 'delete-confirm') {
            Text('删除后无法恢复，确认删除这个远程媒体？').width('100%').fontSize(13)
              .fontFamily('HarmonyOS Sans').fontColor(AppTheme.danger)
          }
          Button(this.mediaActionBusy ? '处理中…' : '确认').width('100%').height(48)
            .enabled(!this.mediaActionBusy).fontFamily('HarmonyOS Sans').fontColor(Color.White)
            .backgroundColor(this.mediaActionMode === 'delete-confirm' ? AppTheme.danger : AppTheme.primary)
            .onClick(() => this.ExecuteMediaAction(item))
        }
        Button('取消').width('100%').height(48).fontFamily('HarmonyOS Sans')
          .fontColor(AppTheme.primary).backgroundColor(Color.Transparent)
          .onClick(() => this.CloseMediaActionSheet())
      }.width('100%').padding({ left: 20, right: 20, top: 20, bottom: 16 })
        .borderRadius({ topLeft: 24, topRight: 24 }).backgroundColor(AppTheme.surface)
    }.width('100%').height('100%').backgroundColor('#55000000')
  }

  @Builder
  private MediaActionButton(label: string, action: () => void) {
    Button(label).layoutWeight(1).height(50).fontSize(14).fontFamily('HarmonyOS Sans')
      .fontColor(AppTheme.textPrimary)
      .backgroundColor(AppTheme.surfaceTint).onClick(action)
  }

  build() {
    if (this.readerVisible) {
      this.ReaderPage()
    } else if (this.bookDetailVisible) {
      this.BookDetailPage()
    } else if (this.bookSourceManagementVisible) {
      this.BookSourceManagementPage()
    } else if (this.mediaViewerVisible) {
      if (this.currentMediaKind === 'image' || this.currentMediaKind === 'video') {
        this.RemoteVisualMediaViewerPage()
      } else {
        this.RemoteMediaViewerPage()
      }
    } else if (this.mediaVideoDetailVisible) {
      this.MediaVideoDetailPage()
    } else if (this.profileVisible) {
      this.ProfileStandalonePage()
    } else if (this.showLogin) {
      this.LoginPage()
    } else {
      Stack({ alignContent: Alignment.Bottom }) {
        Tabs({ barPosition: BarPosition.End, index: this.currentIndex }) {
          ForEach(this.tabs, (tab: TabItem, index: number) => {
            TabContent() {
              if (index === 2) {
                Column().width('100%').height('100%')
              } else {
                this.StandardTabPage(index)
              }
            }
            .tabBar(this.TabBarItem(tab, index))
          })
        }
        .barWidth('calc(100% - 24vp)')
        .barHeight(60)
        .barBackgroundColor(Color.Transparent)
        .divider({ strokeWidth: 0, color: Color.Transparent })
        .onChange((index: number) => {
          this.ChangeMainTab(index);
          if (index === 3 && this.authenticated && this.mediaCatalogItems.length === 0 &&
            this.mediaVideoDirectories.length === 0) {
            this.LoadMediaCatalog();
          } else if (index === 4 && this.authenticated && this.driveAccounts.length === 0) {
            this.LoadDrives();
          }
        })
        // DSH WebView常驻主栈，仅切换可见性，避免切换标签时重新初始化插件。
        Column() {
          this.DshTabPage()
        }.width('100%').height('calc(100% - 60vp)').position({ x: 0, y: 0 })
          .opacity(this.currentIndex === 2 ? 1 : 0)
          // 原生Web组件即使透明且禁用仍可能占用触摸命中层；隐藏渲染节点后才可操作其他页面。
          .visibility(this.currentIndex === 2 ? Visibility.Visible : Visibility.Hidden)
          .enabled(this.currentIndex === 2)
          .hitTestBehavior(this.currentIndex === 2 ? HitTestMode.Default : HitTestMode.Transparent)
        if (this.currentIndex === 2 && this.dshSidebarOverlayVisible) {
          Blank()
            .width('20%')
            .height('calc(100% - 68vp)')
            .align(Alignment.TopEnd)
            .backgroundColor('#01000000')
            .onClick(() => this.CloseDshSidebar())
        }
        if (this.mediaActionMode.length > 0 && this.MediaActionItem() !== undefined) {
          this.MediaActionSheet(this.MediaActionItem()!)
        }
        if (this.mediaSelectorMode.length > 0) {
          this.MediaSelectorSheet()
        }
        if (this.mediaCatalogDirectorySelectorVisible || this.mediaCatalogTagSelectorVisible) {
          this.MediaCatalogSelectorSheet()
        }
        if (this.remoteBookSourceSelectorVisible) {
          this.RemoteBookSourceSelectorSheet()
        }
        if (this.driveSelectorVisible) {
          this.DriveSelectorSheet()
        }
      }.width('100%').height('100%')
    }
  }

  /** 切换一级页面前终止离开页面拥有的只读网络事务。 */
  private ChangeMainTab(index: number): void {
    if (!Number.isSafeInteger(index) || index < 0 || index >= this.tabs.length || index === this.currentIndex) return;
    if (this.currentIndex === 1 && this.toolMode === 'market') this.CancelMarketOperation();
    if (this.currentIndex === 3) this.activeMediaCatalogCancellation?.cancel();
    if (this.currentIndex === 4) this.activeDriveCancellation?.cancel();
    this.currentIndex = index;
  }

  /** 通过头像打开独立账户页面，不改变主导航索引。 */
  private OpenProfilePage(): void {
    this.profileVisible = true;
    this.profileMode = 'home';
    if (this.authenticated) {
      this.LoadReaderDataSummary();
    }
  }

  /** 返回当前经过身份验证的业务服务地址。 */
  private ActiveServiceUrl(): string {
    return this.activeServiceUrl.length > 0 ? this.activeServiceUrl : this.serviceUrl;
  }

  /** 在登录后发现并验证局域网服务，任何失败都安全回退公网。 */
  private async DiscoverLanEndpoint(refreshCurrentPage: boolean = true): Promise<void> {
    if (!this.authenticated) return;
    const context = this.ApplicationContext();
    if (context === undefined) return;
    const revision = ++this.lanDiscoveryRevision;
    const previousUrl = this.ActiveServiceUrl();
    try {
      const result = await new EndpointRouter(this.serviceUrl, context, this.authManager).resolve();
      if (!this.authenticated || revision !== this.lanDiscoveryRevision) return;
      if (result.baseUrl === this.serviceUrl) {
        this.activeServiceUrl = this.serviceUrl;
        this.lanModeActive = false;
        this.dshWebUrl = this.dshPublicWebUrl;
        if (refreshCurrentPage && previousUrl !== this.serviceUrl) this.RefreshAfterEndpointSwitch();
        return;
      }
      // 挑战通过后再发送首次JWT业务请求，成功才向用户展示局域网状态。
      const identity = await new ProfileApi(result.baseUrl, this.authManager, false).current();
      if (!this.authenticated || revision !== this.lanDiscoveryRevision) return;
      this.activeServiceUrl = result.baseUrl;
      this.lanModeActive = true;
      this.dshWebUrl = this.DshLanWebUrl(result.baseUrl);
      if (identity.nickname.trim().length > 0) this.profileName = identity.nickname.trim();
      if (refreshCurrentPage && previousUrl !== result.baseUrl) this.RefreshAfterEndpointSwitch();
    } catch (_) {
      if (revision !== this.lanDiscoveryRevision) return;
      this.activeServiceUrl = this.serviceUrl;
      this.lanModeActive = false;
      this.dshWebUrl = this.dshPublicWebUrl;
      if (refreshCurrentPage && previousUrl !== this.serviceUrl) this.RefreshAfterEndpointSwitch();
    }
  }

  /** 根据已验证的MyTools局域网地址生成DSH局域网代理地址。 */
  private DshLanWebUrl(baseUrl: string): string {
    const matched = /^http:\/\/(\d{1,3}(?:\.\d{1,3}){3}):23110\/?$/.exec(baseUrl.trim());
    return matched === null ? 'http://192.168.3.159:3081' : `http://${matched[1]}:3081`;
  }

  /** 在路由变化后刷新当前页面，避免继续复用切换前创建的API和播放票据。 */
  private RefreshAfterEndpointSwitch(): void {
    if (!this.authenticated) return;
    if (this.currentIndex === 3) this.LoadMediaCatalog();
    else if (this.currentIndex === 4) this.LoadDrives();
    else if (this.currentIndex === 0 && this.bookModeIndex === 1) this.LoadRemoteBooks();
    else if (this.currentIndex === 1 && this.toolMode === 'files') this.LoadToolFiles();
    else if (this.currentIndex === 1 && this.toolMode === 'market') this.LoadMarketApps(true);
    else if (this.currentIndex === 2) this.RefreshDshWeb();
  }

  /** 监听系统网络切换并重新执行局域网服务发现。 */
  private StartNetworkMonitor(): void {
    if (this.networkConnection !== undefined) return;
    try {
      const monitor = connection.createNetConnection(undefined, 5000);
      monitor.on('netAvailable', () => this.ScheduleEndpointRediscovery());
      monitor.on('netLost', () => {
        const wasLan = this.lanModeActive;
        this.lanDiscoveryRevision++;
        this.activeServiceUrl = this.serviceUrl;
        this.lanModeActive = false;
        this.dshWebUrl = this.dshPublicWebUrl;
        if (wasLan) this.RefreshAfterEndpointSwitch();
        this.ScheduleEndpointRediscovery();
      });
      monitor.register((error) => {
        if (error !== undefined) console.info('Network monitor unavailable');
      });
      this.networkConnection = monitor;
    } catch (_) {
      this.networkConnection = undefined;
    }
  }

  /** 注销网络监听并清理尚未执行的去抖任务。 */
  private StopNetworkMonitor(): void {
    if (this.networkChangeTimerId >= 0) {
      clearTimeout(this.networkChangeTimerId);
      this.networkChangeTimerId = -1;
    }
    const monitor = this.networkConnection;
    this.networkConnection = undefined;
    if (monitor === undefined) return;
    try {
      monitor.unregister(() => {});
    } catch (_) {
    }
  }

  /** 去抖处理网络事件，避免系统连续回调触发并行发现。 */
  private ScheduleEndpointRediscovery(): void {
    if (this.networkChangeTimerId >= 0) clearTimeout(this.networkChangeTimerId);
    this.networkChangeTimerId = setTimeout(() => {
      this.networkChangeTimerId = -1;
      if (this.authenticated) this.DiscoverLanEndpoint();
    }, 800);
  }

  /** 返回应用市场列表或工具首页，并终止当前市场请求。 */
  private LeaveMarketPage(): void {
    this.CancelMarketOperation();
    if (this.marketDetail !== undefined) this.marketDetail = undefined;
    else this.toolMode = 'home';
  }

  /** 使当前应用市场读取事务失效。 */
  private CancelMarketOperation(): void {
    this.activeMarketCancellation?.cancel();
    this.activeMarketCancellation = undefined;
    this.marketOperationRevision++;
    this.marketLoading = false;
  }

  @Builder
  private TabBarItem(tab: TabItem, index: number) {
    Column({ space: 2 }) {
      if (this.currentIndex === index) {
        Column().width(4).height(4).borderRadius(2).backgroundColor(AppTheme.primary)
        Stack({ alignContent: Alignment.Center }) {
          Column().width(50).height(36).borderRadius(AppTheme.iconRadius).backgroundColor(AppTheme.accentSoft)
          this.TabBarSymbol(index, AppTheme.primary)
        }.width(50).height(36)
        Text(tab.title).fontSize(11).fontFamily('HarmonyOS Sans').fontWeight(FontWeight.Medium)
          .fontColor(AppTheme.primary)
      } else {
        Column().width(4).height(4).borderRadius(2).backgroundColor(Color.Transparent)
        this.TabBarSymbol(index, AppTheme.textSecondary)
        Text(tab.title).fontSize(11).fontFamily('HarmonyOS Sans').fontColor(AppTheme.textSecondary)
      }
    }
    .width('100%')
    .height(60)
    .justifyContent(FlexAlign.Center)
    .backgroundColor(AppTheme.glass)
    .backdropBlur(22)
    .border({
      width: { top: 1, bottom: 1, left: index === 0 ? 1 : 0, right: index === this.tabs.length - 1 ? 1 : 0 },
      color: AppTheme.glassBorder
    })
    .borderRadius({
      topLeft: index === 0 ? 26 : 0,
      bottomLeft: index === 0 ? 26 : 0,
      topRight: index === this.tabs.length - 1 ? 26 : 0,
      bottomRight: index === this.tabs.length - 1 ? 26 : 0
    })
    .shadow({ radius: 8, color: AppTheme.shadow, offsetY: 2 })
    .accessibilityText(`${tab.title}，${this.currentIndex === index ? '已选择' : '未选择'}`)
    .accessibilityDescription(`主导航第${index + 1}项，共${this.tabs.length}项`)
  }

  @Builder
  private TabBarSymbol(index: number, color: ResourceColor) {
    if (index === 0) {
      SymbolGlyph($r('sys.symbol.book')).fontSize(28).fontColor([color])
    } else if (index === 1) {
      SymbolGlyph($r('sys.symbol.wrench_and_screwdriver')).fontSize(28).fontColor([color])
    } else if (index === 2) {
      SymbolGlyph($r('sys.symbol.ai_edit')).fontSize(28).fontColor([color])
    } else if (index === 3) {
      SymbolGlyph($r('sys.symbol.play_circle')).fontSize(28).fontColor([color])
    } else {
      SymbolGlyph($r('sys.symbol.externaldrive')).fontSize(28).fontColor([color])
    }
  }

  @Builder
  private LoginPage() {
    Scroll() {
      Column({ space: 18 }) {
        Row() {
          this.BackButton(AppTheme.textPrimary, () => {
            if (!this.authInProgress) {
              this.showLogin = false;
              this.password = '';
            }
          })
          Text('登录 MyTools')
            .layoutWeight(1)
            .textAlign(TextAlign.Center)
            .fontSize(22)
            .fontWeight(FontWeight.Bold)
            .fontColor(AppTheme.textPrimary)
          Blank().width(48)
        }
        .width('100%')

        Column({ space: 8 }) {
          Text('欢迎回来')
            .width('100%')
            .fontSize(30)
            .fontWeight(FontWeight.Bold)
            .fontColor(AppTheme.textPrimary)
          Text('连接你的 MyTools 服务')
            .width('100%')
            .fontSize(16)
            .fontColor(AppTheme.textSecondary)
        }
        .margin({ top: 28, bottom: 12 })

        Row({ space: 12 }) {
          Text('◇').width(42).height(42).textAlign(TextAlign.Center).fontSize(18)
            .fontColor(AppTheme.primary).backgroundColor(AppTheme.surfaceTint).borderRadius(12)
          Column({ space: 4 }) {
            Text('MyTools 云服务').fontSize(15).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
            Text(this.connectionStatus.length > 0 ? this.connectionStatus : '正在检查服务连接…')
              .fontSize(13).fontColor(this.connectionHealthy ? AppTheme.success : AppTheme.textSecondary)
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Button('重试').height(48).fontSize(13).fontColor(AppTheme.primary).backgroundColor(Color.Transparent)
            .enabled(!this.authInProgress && !this.connectionHealthy)
            .accessibilityDescription('重新检查固定MyTools云服务连接')
            .onClick(() => this.TestConnection())
        }.width('100%').padding(14).backgroundColor(AppTheme.surface).borderRadius(14)
        this.LoginField('用户名或邮箱', '请输入用户名或邮箱', this.account, false, (value: string) => {
          this.account = value;
        })
        this.LoginField('密码', '请输入密码', this.password, true, (value: string) => {
          this.password = value;
        })

        if (this.authStatus.length > 0) {
          Text(this.authStatus)
            .width('100%')
            .fontSize(14)
            .fontColor(this.authenticated ? AppTheme.success : AppTheme.danger)
        }

        Button(this.authInProgress ? '正在登录…' : '登录')
          .width('100%')
          .height(54)
          .fontSize(17)
          .fontColor(Color.White)
          .backgroundColor(AppTheme.primary)
          .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
          .borderRadius(AppTheme.pillRadius)
          .shadow({ radius: 4, color: AppTheme.shadowSmall, offsetY: 2 })
          .enabled(!this.authInProgress && this.account.trim().length > 0 && this.password.length > 0)
          .accessibilityDescription('使用当前账号和密码登录MyTools')
          .onClick(() => {
            this.SubmitLogin();
          })

        Row({ space: 8 }) {
          Text('◇').fontSize(18).fontColor(AppTheme.primary)
          Text('登录凭证将使用系统安全能力加密保存')
            .fontSize(13)
            .fontColor(AppTheme.textSecondary)
        }
        .margin({ top: 18 })
      }
      .width('100%')
      .padding({ left: 28, right: 28, top: 24, bottom: 36 })
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .height('100%')
    .align(Alignment.TopStart)
    .backgroundColor(AppTheme.background)
  }

  @Builder
  private LoginField(label: string, placeholder: string, value: string, password: boolean,
    onChange: (value: string) => void) {
    Column({ space: 8 }) {
      Text(label)
        .width('100%')
        .fontSize(15)
        .fontWeight(FontWeight.Medium)
        .fontColor(AppTheme.textPrimary)
      AppGlassTextInput({ value: value, placeholder: placeholder,
        inputType: password ? InputType.Password : InputType.Normal, valueChanged: onChange })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  private ProcessToolText(operation: string): void {
    this.toolTextOutput = this.textToolProcessor.process(this.toolTextInput, operation);
  }

  private async CalculateDigest(): Promise<void> {
    if (this.digestBusy) return;
    this.digestBusy = true;
    this.digestStatus = '正在使用系统密码框架计算…';
    const input = this.digestInput;
    const algorithm = this.digestAlgorithm;
    try {
      const output = await this.digestTool.digestText(input, algorithm);
      if (this.digestInput === input && this.digestAlgorithm === algorithm) {
        this.digestOutput = output;
        this.digestStatus = `${algorithm === 'SHA256' ? 'SHA-256' : 'SHA-512'} · ${output.length * 4} bit`;
      }
    } catch (error) {
      this.digestOutput = '';
      this.digestStatus = error instanceof Error ? error.message : '摘要计算失败';
    }
    this.digestBusy = false;
  }

  private async CreateToolArchive(): Promise<void> {
    if (this.archiveBusy) return;
    const hostContext = this.getUIContext().getHostContext();
    if (hostContext === undefined) {
      this.archiveStatus = '无法获取应用上下文';
      return;
    }
    this.archiveBusy = true;
    const lifecycleRevision = this.pageLifecycleRevision;
    this.archiveStatus = '请选择需要压缩的普通文件';
    try {
      const selectOptions = new picker.DocumentSelectOptions();
      selectOptions.maxSelectNumber = 20;
      const selected = await new picker.DocumentViewPicker(hostContext as common.UIAbilityContext).select(selectOptions);
      if (lifecycleRevision !== this.pageLifecycleRevision) return;
      if (selected.length === 0) {
        this.archiveStatus = '已取消选择';
        return;
      }
      const saveOptions = new picker.DocumentSaveOptions();
      saveOptions.newFileNames = ['MyTools-Archive.zip'];
      const destinations = await new picker.DocumentViewPicker(hostContext as common.UIAbilityContext).save(saveOptions);
      if (lifecycleRevision !== this.pageLifecycleRevision) return;
      if (destinations.length === 0) {
        this.archiveStatus = '已取消保存';
        return;
      }
      this.archiveStatus = `正在压缩 ${selected.length} 个文件…`;
      const result: ArchiveCreateResult = await this.archiveTool.createZip(
        hostContext as common.UIAbilityContext, selected, destinations[0]);
      if (lifecycleRevision !== this.pageLifecycleRevision) return;
      this.archiveStatus = `ZIP已保存：${result.fileCount}个文件，${this.FormatFileSize(result.inputBytes)} → ` +
        this.FormatFileSize(result.outputBytes);
    } catch (error) {
      if (lifecycleRevision !== this.pageLifecycleRevision) return;
      this.archiveStatus = error instanceof Error ? error.message : '创建ZIP失败';
    } finally {
      if (lifecycleRevision === this.pageLifecycleRevision) this.archiveBusy = false;
    }
  }

  private async ExtractToolArchive(): Promise<void> {
    if (this.archiveBusy) return;
    const hostContext = this.getUIContext().getHostContext();
    if (hostContext === undefined) {
      this.archiveStatus = '无法获取应用上下文';
      return;
    }
    this.archiveBusy = true;
    const lifecycleRevision = this.pageLifecycleRevision;
    this.archiveStatus = '请选择需要安全检查的ZIP';
    try {
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      options.fileSuffixFilters = ['ZIP压缩包|zip'];
      const selected = await new picker.DocumentViewPicker(hostContext as common.UIAbilityContext).select(options);
      if (lifecycleRevision !== this.pageLifecycleRevision) return;
      if (selected.length === 0) {
        this.archiveStatus = '已取消选择';
        return;
      }
      this.archiveStatus = '正在检查ZIP结构并解压到临时目录…';
      const extractedItems = await this.archiveTool.extractZip(
        hostContext as common.UIAbilityContext, selected[0]);
      if (lifecycleRevision !== this.pageLifecycleRevision) return;
      this.archiveExtractedItems = extractedItems;
      const totalBytes = this.archiveExtractedItems.reduce((total: number, item: ArchiveExtractedItem) =>
        total + item.size, 0);
      this.archiveStatus = `安全检查通过：${this.archiveExtractedItems.length}个文件，` +
        `实际展开${this.FormatFileSize(totalBytes)}；请选择需要导出的文件`;
    } catch (error) {
      if (lifecycleRevision !== this.pageLifecycleRevision) return;
      this.archiveExtractedItems = [];
      this.archiveStatus = error instanceof Error ? error.message : 'ZIP安全解压失败';
    } finally {
      if (lifecycleRevision === this.pageLifecycleRevision) this.archiveBusy = false;
    }
  }

  private async ExportArchiveItem(item: ArchiveExtractedItem): Promise<void> {
    if (this.archiveBusy) return;
    const hostContext = this.getUIContext().getHostContext();
    if (hostContext === undefined) {
      this.archiveStatus = '无法打开系统保存窗口';
      return;
    }
    this.archiveBusy = true;
    const lifecycleRevision = this.pageLifecycleRevision;
    try {
      const options = new picker.DocumentSaveOptions();
      options.newFileNames = [this.ArchiveExportFileName(item.relativePath)];
      const destinations = await new picker.DocumentViewPicker(hostContext as common.UIAbilityContext).save(options);
      if (lifecycleRevision !== this.pageLifecycleRevision) return;
      if (destinations.length === 0) return;
      await this.archiveTool.exportExtractedFile(item.relativePath, destinations[0]);
      if (lifecycleRevision !== this.pageLifecycleRevision) return;
      this.archiveStatus = `已导出：${item.relativePath}`;
    } catch (error) {
      if (lifecycleRevision !== this.pageLifecycleRevision) return;
      this.archiveStatus = error instanceof Error ? error.message : '导出解压文件失败';
    } finally {
      if (lifecycleRevision === this.pageLifecycleRevision) this.archiveBusy = false;
    }
  }

  private ArchiveExportFileName(relativePath: string): string {
    const name = relativePath.substring(relativePath.lastIndexOf('/') + 1)
      .replace(/[\\/:*?"<>|\u0000-\u001F]/g, '_').trim().substring(0, 160);
    return name.length > 0 ? name : 'extracted-file';
  }

  private async GenerateQrCode(): Promise<void> {
    if (this.qrBusy) return;
    const content = this.qrInput.trim();
    if (content.length === 0) {
      this.qrStatus = '请输入需要生成二维码的内容';
      return;
    }
    const bytes = new util.TextEncoder().encodeInto(content);
    if (bytes.byteLength > 2950) {
      this.qrStatus = `内容为 ${bytes.byteLength} 字节，超过二维码2950字节上限`;
      return;
    }
    this.qrBusy = true;
    this.qrStatus = '正在本地生成二维码…';
    try {
      const pixelMap = await generateBarcode.createBarcode(content, {
        scanType: scanCore.ScanType.QR_CODE,
        width: 720,
        height: 720,
        margin: 4,
        level: generateBarcode.ErrorCorrectionLevel.LEVEL_L,
        backgroundColor: 0xFFFFFFFF,
        pixelMapColor: 0xFF111827
      });
      const previous = this.qrPixelMap;
      this.qrPixelMap = pixelMap;
      this.qrResult = '';
      this.qrStatus = `已在设备本地生成 · ${bytes.byteLength} 字节`;
      if (previous !== undefined) await previous.release();
    } catch (error) {
      this.qrStatus = error instanceof Error ? error.message : '二维码生成失败';
    } finally {
      this.qrBusy = false;
    }
  }

  private async ScanQrCode(): Promise<void> {
    if (this.qrBusy) return;
    const hostContext = this.getUIContext().getHostContext();
    if (hostContext === undefined) {
      this.qrStatus = '无法启动系统扫码页面';
      return;
    }
    this.qrBusy = true;
    this.qrStatus = '系统扫码仅在你确认后使用相机或相册';
    try {
      const result = await scanBarcode.startScanForResult(hostContext as common.UIAbilityContext, {
        scanTypes: [scanCore.ScanType.QR_CODE],
        enableMultiMode: false,
        enableAlbum: true
      });
      this.qrResult = result.originalValue.substring(0, 8192);
      this.qrStatus = this.qrResult.length > 0 ? '扫码完成；结果不会自动打开' : '二维码内容为空';
    } catch (_) {
      this.qrStatus = '扫码已取消或系统扫码暂不可用';
    } finally {
      this.qrBusy = false;
    }
  }

  private async SubmitFeedback(): Promise<void> {
    if (this.feedbackSubmitting) return;
    const name = this.feedbackName.trim();
    const email = this.feedbackEmail.trim();
    const title = this.feedbackTitle.trim();
    const content = this.feedbackContent.trim();
    if (name.length === 0 || title.length === 0 || content.length === 0) {
      this.feedbackStatus = '请填写称呼、标题和反馈内容';
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      this.feedbackStatus = '请输入有效的联系邮箱';
      return;
    }
    this.feedbackSubmitting = true;
    this.feedbackStatus = '正在提交反馈…';
    try {
      const receipt = await new FeedbackApi(this.ActiveServiceUrl().trim())
        .submit(name, email, this.feedbackCategory, title, content);
      this.feedbackReceiptId = receipt.feedbackId;
      this.feedbackStatus = '';
    } catch (error) {
      this.feedbackStatus = error instanceof Error ? error.message : '意见反馈提交失败';
    } finally {
      this.feedbackSubmitting = false;
    }
  }

  private async LoadMarketApps(reset: boolean): Promise<void> {
    if (this.marketLoading && !reset) return;
    if (!this.authenticated) {
      this.OpenLogin();
      return;
    }
    this.activeMarketCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeMarketCancellation = cancellation;
    const operationRevision = ++this.marketOperationRevision;
    const targetPage = reset ? 1 : this.marketPage + 1;
    const query = this.marketQuery;
    const revision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.marketLoading = true;
    this.marketStatus = reset ? '正在加载应用目录…' : '正在加载更多…';
    if (reset) {
      this.marketItems = [];
      this.marketTotal = 0;
      this.marketPage = 1;
    }
    try {
      const result = await new AppMarketApi(this.ActiveServiceUrl(), this.authManager)
        .list(targetPage, 20, query, cancellation);
      if (!this.AccountOperationCurrent(revision, owner) || operationRevision !== this.marketOperationRevision ||
        query !== this.marketQuery) return;
      this.marketItems = reset ? result.items : this.marketItems.concat(result.items);
      this.marketTotal = result.total;
      this.marketPage = result.page;
      this.marketStatus = result.items.length === 0 && !reset ? '没有更多应用' : '';
    } catch (error) {
      if (!this.AccountOperationCurrent(revision, owner) || operationRevision !== this.marketOperationRevision) return;
      if (cancellation.isCancelled()) return;
      this.marketStatus = error instanceof Error ? error.message : '应用市场加载失败';
    } finally {
      if (this.activeMarketCancellation === cancellation) this.activeMarketCancellation = undefined;
      if (this.AccountOperationCurrent(revision, owner) && operationRevision === this.marketOperationRevision) {
        this.marketLoading = false;
      }
    }
  }

  private async LoadMarketDetail(id: string): Promise<void> {
    if (id.length === 0) return;
    this.activeMarketCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeMarketCancellation = cancellation;
    const operationRevision = ++this.marketOperationRevision;
    this.marketLoading = true;
    const revision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.marketStatus = '正在读取应用详情…';
    try {
      const detail = await new AppMarketApi(this.ActiveServiceUrl(), this.authManager).detail(id, cancellation);
      if (!this.AccountOperationCurrent(revision, owner) || operationRevision !== this.marketOperationRevision) return;
      this.marketDetail = detail;
      this.marketStatus = '';
    } catch (error) {
      if (!this.AccountOperationCurrent(revision, owner) || operationRevision !== this.marketOperationRevision) return;
      if (cancellation.isCancelled()) return;
      this.marketStatus = error instanceof Error ? error.message : '应用详情加载失败';
    } finally {
      if (this.activeMarketCancellation === cancellation) this.activeMarketCancellation = undefined;
      if (this.AccountOperationCurrent(revision, owner) && operationRevision === this.marketOperationRevision) {
        this.marketLoading = false;
      }
    }
  }

  private FormatFileSize(bytes: number): string {
    if (bytes <= 0) return '大小未知';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`;
  }

  private async LoadToolFiles(): Promise<void> {
    if (!this.authenticated) {
      this.OpenLogin();
      return;
    }
    if (this.mediaSources.length === 0) {
      await this.LoadMediaSources();
    }
    if (this.selectedMediaSourceId.length > 0) await this.LoadToolFileDirectory();
  }

  private async LoadToolFileDirectory(): Promise<void> {
    if (this.selectedMediaSourceId.length === 0) return;
    this.activeToolDirectoryCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeToolDirectoryCancellation = cancellation;
    const directoryRevision = ++this.toolDirectoryRevision;
    const sourceId = this.selectedMediaSourceId;
    const path = this.toolFilePath;
    this.toolLoading = true;
    const revision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.toolStatus = '正在加载远程目录…';
    try {
      const directory = await new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager)
        .listDirectory(sourceId, path, cancellation);
      if (!this.AccountOperationCurrent(revision, owner) || directoryRevision !== this.toolDirectoryRevision ||
        sourceId !== this.selectedMediaSourceId || path !== this.toolFilePath) return;
      this.toolFiles = directory.items;
      this.toolStatus = this.toolFiles.length === 0 ? '当前目录为空' : '';
    } catch (error) {
      if (!this.AccountOperationCurrent(revision, owner) || directoryRevision !== this.toolDirectoryRevision) return;
      if (cancellation.isCancelled()) return;
      this.toolStatus = error instanceof Error ? error.message : '加载远程文件失败';
    } finally {
      if (this.activeToolDirectoryCancellation === cancellation) {
        this.activeToolDirectoryCancellation = undefined;
      }
      if (this.AccountOperationCurrent(revision, owner) && directoryRevision === this.toolDirectoryRevision) {
        this.toolLoading = false;
      }
    }
  }

  private ParentToolFilePath(): string {
    const normalized = this.toolFilePath.endsWith('/') && this.toolFilePath.length > 1 ?
      this.toolFilePath.substring(0, this.toolFilePath.length - 1) : this.toolFilePath;
    const lastSlash = normalized.lastIndexOf('/');
    return lastSlash <= 0 ? '/' : normalized.substring(0, lastSlash);
  }

  private SelectToolFile(item: RemoteMediaItem): void {
    this.toolSelectedPath = item.path;
    this.toolSelectedName = item.name;
    this.toolSelectedDirectory = item.kind === 'directory';
    this.toolOperationInput = '';
    this.toolOperation = 'select';
  }

  private StartCreateToolDirectory(): void {
    if (this.selectedMediaSourceId.length === 0) {
      this.toolStatus = '请先选择远程数据源';
      return;
    }
    this.toolSelectedPath = '';
    this.toolSelectedName = '';
    this.toolSelectedDirectory = false;
    this.toolOperationInput = '';
    this.toolOperation = 'mkdir';
  }

  private async DownloadToolFile(): Promise<void> {
    if (this.toolOperationBusy || this.toolSelectedDirectory || this.toolSelectedPath.length === 0) return;
    try {
      await this.DownloadRemoteFileWithPicker(this.selectedMediaSourceId, this.toolSelectedPath,
        this.toolSelectedName);
    } catch (_) {
    }
  }

  private async DownloadRemoteFileWithPicker(accountId: string, path: string, fileName: string): Promise<Object> {
    if (this.toolOperationBusy) throw new Error('已有下载或远程文件操作正在进行');
    const taskOwner = this.localAccountScope;
    const revision = this.accountOperationRevision;
    const lifecycleRevision = this.pageLifecycleRevision;
    if (!/^account:v2:[a-f0-9]{64}$/.test(taskOwner)) throw new Error('下载账户作用域无效');
    const hostContext = this.getUIContext().getHostContext();
    if (hostContext === undefined) {
      this.toolStatus = '无法打开系统保存窗口';
      throw new Error(this.toolStatus);
    }
    let taskId = '';
    this.toolOperationBusy = true;
    try {
      const options = new picker.DocumentSaveOptions();
      options.newFileNames = [fileName];
      const uris = await new picker.DocumentViewPicker(hostContext as common.UIAbilityContext).save(options);
      if (!this.PickerContextCurrent(revision, taskOwner, lifecycleRevision)) {
        throw new Error('页面或登录会话已变化，下载已取消');
      }
      if (uris.length === 0) {
        this.toolOperationBusy = false;
        const cancelled: Record<string, Object> = {};
        cancelled['success'] = false;
        cancelled['cancelled'] = true;
        cancelled['assistant_summary'] = '用户取消了系统保存位置选择，未创建下载任务';
        return cancelled;
      }
      taskId = `${Date.now()}-${Math.random().toString(36).substring(2, 8)}`;
      const cancellation = new DownloadCancellationToken();
      this.activeDownloadCancellation = cancellation;
      this.activeDownloadOwner = taskOwner;
      this.activeDownloadTaskId = taskId;
      this.lastDownloadProgressPublishedAt = 0;
      this.AddDownloadHistory(taskOwner, taskId, fileName);
      this.toolStatus = `正在下载：${fileName}`;
      const bytes = await new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager)
        .downloadFile(accountId, path, uris[0],
          (receivedBytes: number, totalBytes: number) => {
            if (!this.PickerContextCurrent(revision, taskOwner, lifecycleRevision) || cancellation.isCancelled()) return;
            const now = Date.now();
            if (now - this.lastDownloadProgressPublishedAt < 250 &&
              (totalBytes <= 0 || receivedBytes < totalBytes)) return;
            this.lastDownloadProgressPublishedAt = now;
            this.UpdateDownloadHistory(taskOwner, taskId, 'running', receivedBytes, totalBytes, false);
            this.toolStatus = totalBytes > 0 ?
              `正在下载：${fileName} · ${Math.min(100, Math.round(receivedBytes / totalBytes * 100))}%` :
              `正在下载：${fileName} · ${this.FormatFileSize(receivedBytes)}`;
          }, cancellation);
      if (cancellation.isCancelled()) throw new Error('下载已取消');
      if (!this.PickerContextCurrent(revision, taskOwner, lifecycleRevision)) {
        throw new Error('页面或登录会话已变化，下载已取消');
      }
      this.UpdateDownloadHistory(taskOwner, taskId, 'completed', bytes, bytes, true);
      this.CloseToolOperation();
      this.toolStatus = `下载完成：${fileName}（${this.FormatMediaSize(bytes)}）`;
      const result: Record<string, Object> = {};
      result['success'] = true;
      result['cancelled'] = false;
      result['task_id'] = taskId;
      result['name'] = fileName;
      result['bytes'] = bytes;
      result['assistant_summary'] = '远程文件已保存到用户选择的位置';
      return result;
    } catch (error) {
      if (taskId.length > 0) {
        const current = this.downloadHistory.find((item: DownloadHistoryItem) =>
          item.owner === taskOwner && item.id === taskId);
        const cancelled = this.activeDownloadCancellation?.isCancelled() === true &&
          this.activeDownloadOwner === taskOwner && this.activeDownloadTaskId === taskId;
        this.UpdateDownloadHistory(taskOwner, taskId, cancelled ? 'cancelled' : 'failed', current?.receivedBytes ?? 0,
          current?.totalBytes ?? 0, true);
      }
      const status = this.activeDownloadCancellation?.isCancelled() === true ? '下载已取消，目标文件已清空' :
        (error instanceof Error ? error.message : '下载远程文件失败');
      if (this.AccountOperationCurrent(revision, taskOwner)) {
        this.toolStatus = status;
        this.toolOperationBusy = false;
      }
      throw new Error(status);
    } finally {
      if (this.activeDownloadOwner === taskOwner && this.activeDownloadTaskId === taskId) {
        this.activeDownloadCancellation = undefined;
        this.activeDownloadOwner = '';
        this.activeDownloadTaskId = '';
      }
    }
  }

  private CancelActiveDownload(): void {
    if (this.activeDownloadCancellation === undefined || this.activeDownloadTaskId.length === 0) return;
    this.activeDownloadCancellation.cancel();
    this.toolStatus = '正在取消下载…';
  }

  private AddDownloadHistory(owner: string, id: string, name: string): void {
    if (!/^account:v2:[a-f0-9]{64}$/.test(owner)) throw new Error('下载账户作用域无效');
    const safeName = name.replace(/[\u0000-\u001F\u007F]/g, '').substring(0, 1024);
    const item: DownloadHistoryItem = {
      owner: owner,
      id: id,
      name: safeName.length > 0 ? safeName : 'remote-file',
      status: 'running',
      receivedBytes: 0,
      totalBytes: 0,
      updatedAt: Date.now()
    };
    this.downloadHistory = this.BoundedDownloadHistory([item].concat(this.downloadHistory));
    this.PersistDownloadHistory();
  }

  private UpdateDownloadHistory(owner: string, id: string, status: string, receivedBytes: number,
    totalBytes: number, persist: boolean): void {
    const updated = this.downloadHistory.map((item: DownloadHistoryItem) => {
      if (item.owner !== owner || item.id !== id) return item;
      return {
        owner: item.owner,
        id: item.id,
        name: item.name,
        status: status,
        receivedBytes: Math.max(0, receivedBytes),
        totalBytes: Math.max(0, totalBytes),
        updatedAt: Date.now()
      };
    });
    this.downloadHistory = this.downloadHistoryPolicy.normalize(updated as Object);
    if (persist) this.PersistDownloadHistory();
  }

  private PersistDownloadHistory(): void {
    try {
      this.settingsPreferences?.putSync('download_history', JSON.stringify(this.BoundedDownloadHistory(
        this.downloadHistory)));
      this.settingsPreferences?.flush();
    } catch (_) {
    }
  }

  private ClearDownloadHistory(): void {
    this.downloadHistory = this.downloadHistory.filter((item: DownloadHistoryItem) =>
      item.owner !== this.localAccountScope || item.status === 'running');
    this.PersistDownloadHistory();
  }

  private CurrentDownloadHistory(): DownloadHistoryItem[] {
    if (this.localAccountScope.length === 0) return [];
    return this.downloadHistory.filter((item: DownloadHistoryItem) => item.owner === this.localAccountScope)
      .sort((left: DownloadHistoryItem, right: DownloadHistoryItem) => right.updatedAt - left.updatedAt).slice(0, 50);
  }

  private RestoreDownloadHistory(value: string): void {
    try {
      if (value.length > 1024 * 1024) return;
      this.downloadHistory = this.downloadHistoryPolicy.normalize(JSON.parse(value) as Object, true);
    } catch (_) {
      this.downloadHistory = [];
    }
  }

  private BoundedDownloadHistory(items: DownloadHistoryItem[]): DownloadHistoryItem[] {
    return this.downloadHistoryPolicy.normalize(items as Object);
  }

  private DownloadStatusLabel(status: string): string {
    if (status === 'running') return '下载中';
    if (status === 'completed') return '已完成';
    if (status === 'interrupted') return '已中断';
    if (status === 'cancelled') return '已取消';
    return '失败';
  }

  private FormatRecordTime(timestamp: number): string {
    if (timestamp <= 0) return '';
    const date = new Date(timestamp);
    const month = `${date.getMonth() + 1}`.padStart(2, '0');
    const day = `${date.getDate()}`.padStart(2, '0');
    const hour = `${date.getHours()}`.padStart(2, '0');
    const minute = `${date.getMinutes()}`.padStart(2, '0');
    return `${month}-${day} ${hour}:${minute}`;
  }

  private async UploadToolFile(): Promise<void> {
    if (this.toolOperationBusy || this.selectedMediaSourceId.length === 0) return;
    const hostContext = this.getUIContext().getHostContext();
    const revision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    const lifecycleRevision = this.pageLifecycleRevision;
    if (hostContext === undefined) {
      this.toolStatus = '无法打开系统文件选择器';
      return;
    }
    try {
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      const uris = await new picker.DocumentViewPicker(hostContext as common.UIAbilityContext).select(options);
      if (!this.PickerContextCurrent(revision, owner, lifecycleRevision)) return;
      if (uris.length === 0) return;
      const filename = bookNameFromUri(uris[0]);
      this.toolOperationBusy = true;
      this.toolUploadProgress = 0;
      this.toolStatus = `正在上传：${filename}`;
      await new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager).uploadFile(
        hostContext as common.UIAbilityContext, this.selectedMediaSourceId, this.toolFilePath, uris[0], filename,
        (uploadedBytes: number, totalBytes: number) => {
          if (!this.PickerContextCurrent(revision, owner, lifecycleRevision)) return;
          this.toolUploadProgress = totalBytes <= 0 ? 0 : Math.min(1, uploadedBytes / totalBytes);
          this.toolStatus = totalBytes <= 0 ? `正在上传：${filename} · ${this.FormatMediaSize(uploadedBytes)}` :
            `正在上传：${filename} · ${Math.round(this.toolUploadProgress * 100)}%`;
        });
      if (!this.PickerContextCurrent(revision, owner, lifecycleRevision)) return;
      this.toolOperationBusy = false;
      this.toolUploadProgress = 1;
      this.toolStatus = `上传完成：${filename}`;
      await this.LoadToolFileDirectory();
    } catch (error) {
      if (!this.AccountOperationCurrent(revision, owner)) return;
      this.toolStatus = error instanceof Error ? error.message : '上传远程文件失败';
      this.toolOperationBusy = false;
      this.toolUploadProgress = 0;
    }
  }

  private CloseToolOperation(): void {
    this.toolOperation = '';
    this.toolSelectedPath = '';
    this.toolSelectedName = '';
    this.toolSelectedDirectory = false;
    this.toolOperationInput = '';
    this.toolOperationBusy = false;
  }

  private async ConfirmToolOperation(): Promise<void> {
    if (this.toolOperationBusy || this.selectedMediaSourceId.length === 0 ||
      (this.toolOperation !== 'mkdir' && this.toolSelectedPath.length === 0)) return;
    this.toolOperationBusy = true;
    const revision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    const operation = this.toolOperation;
    const sourceId = this.selectedMediaSourceId;
    const directoryPath = this.toolFilePath;
    const selectedPath = this.toolSelectedPath;
    const selectedName = this.toolSelectedName;
    const selectedDirectory = this.toolSelectedDirectory;
    const input = this.toolOperationInput;
    try {
      const api = new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager);
      if (operation === 'mkdir') {
        await api.createDirectory(sourceId, directoryPath, input);
      } else if (operation === 'rename') {
        await api.renameEntry(sourceId, selectedPath, input);
      } else if (operation === 'move') {
        await api.moveEntry(sourceId, selectedPath, input);
      } else if (operation === 'delete') {
        await api.deleteEntry(sourceId, selectedPath, selectedDirectory);
      }
      if (!this.AccountOperationCurrent(revision, owner)) return;
      this.toolStatus = operation === 'mkdir' ? `已新建文件夹：${input.trim()}` :
        (operation === 'rename' ? `已重命名：${selectedName}` :
          (operation === 'move' ? `已移动：${selectedName}` : `已删除：${selectedName}`));
      this.CloseToolOperation();
      await this.LoadToolFileDirectory();
    } catch (error) {
      if (!this.AccountOperationCurrent(revision, owner)) return;
      this.toolStatus = error instanceof Error ? error.message : '远程文件操作失败';
      this.toolOperationBusy = false;
    }
  }

  private async SubmitLogin(): Promise<void> {
    if (this.authInProgress) {
      return;
    }
    if (this.account.trim().length === 0 || this.password.length === 0) {
      this.authStatus = '请填写账号和密码';
      return;
    }
    this.authInProgress = true;
    const revision = ++this.authenticationUiRevision;
    this.authStatus = '';
    try {
      const normalizedServiceUrl = this.authManager.normalizeBaseUrl(this.serviceUrl);
      const session = await this.authManager.login(normalizedServiceUrl, {
        account: this.account.trim(),
        password: this.password,
        deviceName: 'HarmonyOS NEXT'
      });
      if (revision !== this.authenticationUiRevision) return;
      await this.PrepareLocalAccountScope(session.username);
      if (revision !== this.authenticationUiRevision) return;
      const readerContext = this.ApplicationContext();
      if (readerContext === undefined) throw new Error('无法切换阅读数据账户');
      this.SwitchReaderStorageScope(readerContext, this.localAccountScope);
      this.authenticated = true;
      this.PrepareReadingSyncForAccount(this.localAccountScope);
      this.profileName = session.nickname.length > 0 ? session.nickname : session.username;
      this.profileUsername = session.username;
      this.profileRole = session.role;
      this.ApplyProfileAvatar(session.avatar);
      this.password = '';
      this.authStatus = '登录成功';
      this.showLogin = false;
      this.RefreshProfileIdentity();
      await this.DiscoverLanEndpoint(false);
      this.PullBookSources();
      if (this.currentIndex === 3) {
        this.LoadMediaCatalog();
      } else if (this.currentIndex === 4) {
        this.LoadDrives();
      } else if (this.currentIndex === 2) {
        this.RefreshDshWeb();
      } else if (this.currentIndex === 0 && this.bookModeIndex === 1) {
        this.LoadRemoteBooks();
      } else if (this.currentIndex === 1 && this.toolMode === 'files') {
        this.LoadToolFiles();
      } else if (this.currentIndex === 1 && this.toolMode === 'market') {
        this.LoadMarketApps(true);
      }
    } catch (error) {
      if (revision !== this.authenticationUiRevision) return;
      this.password = '';
      this.authStatus = error instanceof Error ? error.message : '登录失败，请稍后重试';
    }
    if (revision === this.authenticationUiRevision) this.authInProgress = false;
  }

  private async TestConnection(): Promise<void> {
    if (this.authInProgress) return;
    this.authInProgress = true;
    const revision = ++this.authenticationUiRevision;
    const requestedUrl = this.serviceUrl;
    this.connectionStatus = '正在连接…';
    try {
      const normalizedServiceUrl = this.authManager.normalizeBaseUrl(requestedUrl);
      const elapsedMs = await this.authManager.testConnection(normalizedServiceUrl);
      if (revision !== this.authenticationUiRevision || this.serviceUrl !== requestedUrl) return;
      this.connectionHealthy = true;
      this.connectionStatus = `服务可用 · ${elapsedMs} ms`;
    } catch (error) {
      if (revision !== this.authenticationUiRevision) return;
      this.connectionHealthy = false;
      this.connectionStatus = error instanceof Error ? error.message : '连接失败';
    }
    if (revision === this.authenticationUiRevision) this.authInProgress = false;
  }

  private OpenLogin(): void {
    this.profileVisible = false;
    this.showLogin = true;
    this.authStatus = '';
    this.password = '';
    if (!this.connectionHealthy && !this.authInProgress) this.TestConnection();
  }

  private async RestoreSession(): Promise<void> {
    const revision = ++this.authenticationUiRevision;
    try {
      const normalizedServiceUrl = this.authManager.normalizeBaseUrl(this.serviceUrl);
      const session = await this.authManager.restore(normalizedServiceUrl);
      if (revision !== this.authenticationUiRevision) return;
      if (session !== undefined) {
        await this.PrepareLocalAccountScope(session.username);
        if (revision !== this.authenticationUiRevision) return;
        const readerContext = this.ApplicationContext();
        if (readerContext === undefined) throw new Error('无法恢复阅读数据账户');
        this.SwitchReaderStorageScope(readerContext, this.localAccountScope);
        this.authenticated = true;
        this.PrepareReadingSyncForAccount(this.localAccountScope);
        this.profileName = session.nickname.length > 0 ? session.nickname : session.username;
        this.profileUsername = session.username;
        this.profileRole = session.role;
        this.ApplyProfileAvatar(session.avatar);
        this.RefreshProfileIdentity();
        await this.DiscoverLanEndpoint(false);
        this.PullBookSources();
        if (this.currentIndex === 3) {
          this.LoadMediaCatalog();
        } else if (this.currentIndex === 4) {
          this.LoadDrives();
        }
      }
    } catch (error) {
      console.info('Session restore skipped');
    }
  }

  private ReaderLogoutRiskSnapshot(): LogoutRiskSnapshot {
    return {
      pendingProgress: this.pendingReaderProgress.length,
      pendingMarkers: this.pendingReaderMarkers.length,
      pendingShelfBooks: this.pendingShelfBooks.length,
      pendingBookSources: this.pendingBookSources.length,
      syncActive: this.ReaderSyncOperationActive()
    };
  }

  private LogoutRiskDescription(): string {
    const snapshot = this.ReaderLogoutRiskSnapshot();
    const pending = this.logoutRiskPolicy.pendingCount(snapshot);
    if (pending > 0 && snapshot.syncActive) return `${pending} 项更改仍在队列中，且同步请求尚未结束。强制退出会保留本机数据，但当前账号的同步需要下次登录后继续。`;
    if (pending > 0) return `${pending} 项更改尚未写入云端。强制退出会保留本机数据，但当前账号的同步需要下次登录后继续。`;
    return '同步请求尚未结束。请等待完成，或确认强制退出。';
  }

  private RequestLogout(): void {
    if (this.logoutRiskPolicy.requiresConfirmation(this.ReaderLogoutRiskSnapshot())) {
      this.logoutConfirmVisible = true;
      return;
    }
    this.Logout(false);
  }

  private DrainAllReaderSyncQueues(): void {
    this.DrainReadingProgressQueue();
    this.DrainReaderMarkerQueue();
    this.DrainShelfQueue();
    this.DrainBookSourceQueue();
    this.UpdateReaderSyncStatus();
  }

  private async Logout(force: boolean): Promise<void> {
    if (!force && this.logoutRiskPolicy.requiresConfirmation(this.ReaderLogoutRiskSnapshot())) {
      this.logoutConfirmVisible = true;
      return;
    }
    this.authenticationUiRevision++;
    this.accountOperationRevision++;
    this.activeDownloadCancellation?.cancel();
    this.activeOpdsCatalogCancellation?.cancel();
    this.activeMediaSourceCancellation?.cancel();
    this.activeRemoteBookSourceCancellation?.cancel();
    this.activeMediaDirectoryCancellation?.cancel();
    this.activeMediaLoadMoreCancellation?.cancel();
    this.activeRemoteBookDirectoryCancellation?.cancel();
    this.activeRemoteBookCoverCancellation?.cancel();
    this.activeToolDirectoryCancellation?.cancel();
    this.activeMediaOpenCancellation?.cancel();
    this.activeImageUpgradeCancellation?.cancel();
    this.activeBrowseThumbnailCancellation?.cancel();
    this.activeThumbnailCancellation?.cancel();
    this.ClearMediaPreloads();
    this.activeReaderTicketCancellation?.cancel();
    this.readerOpeningLoader?.cancel();
    this.activeMarketCancellation?.cancel();
    this.marketOperationRevision++;
    this.activeProfileCancellation?.cancel();
    this.CancelReaderPulls();
    this.CancelReaderDataSummary();
    this.readerDataDeleteRevision++;
    this.readerDataDeleting = false;
    this.marketLoading = false;
    this.mediaLoading = false;
    this.toolLoading = false;
    this.mediaDirectoryRevision++;
    this.opdsNavigationRevision++;
    this.opdsCredentialRevision++;
    this.remoteBookDirectoryRevision++;
    this.toolDirectoryRevision++;
    this.mediaDirectoryLoading = false;
    this.mediaLoadingMore = false;
    this.remoteBookLoading = false;
    this.opdsLoading = false;
    this.opdsCredentialBusy = false;
    this.toolOperationBusy = false;
    this.toolUploadProgress = 0;
    try {
      await this.authManager.logout();
    } catch (error) {
      this.connectionStatus = error instanceof Error ? error.message : '本地安全凭证清除失败，请重试退出登录';
      return;
    }
    this.logoutConfirmVisible = false;
    this.mediaOpenRevision++;
    this.queuedImageNavigationDirection = 0;
    this.queuedAudioNavigationDirection = 0;
    this.audioQueueExpanded = false;
    this.InvalidateBookSourceOperation();
    this.mediaOpening = false;
    this.mediaViewerVisible = false;
    this.videoController.stop();
    this.StopMediaMetrics();
    await this.audioPlayer.release();
    this.copilotOperationRevision++;
    this.CancelCopilotRefresh();
    this.dshApi = undefined;
    this.copilotInitialized = false;
    this.copilotGatewayModel = '';
    this.copilotSettingsVisible = false;
    this.copilotMessages = [];
    this.copilotRunSteps = [];
    this.activeCopilotConversationId = '';
    this.copilotAttachment = undefined;
    this.copilotAttachmentStatus = '';
    this.copilotDeleteConfirm = false;
    this.copilotTurn = undefined;
    this.copilotRecoveryPlan = undefined;
    this.copilotBusy = false;
    this.copilotCancelling = false;
    this.readerProgressSyncGeneration++;
    this.readerProgressQueueOwner = '';
    this.pendingReaderProgress = [];
    this.pendingReaderMarkers = [];
    this.pendingShelfBooks = [];
    this.shelfPullPending = false;
    this.pendingBookSources = [];
    this.bookSourcePullPending = false;
    this.readerMarkerPullPending = false;
    this.readerProgressPullPending = false;
    this.authenticated = false;
    this.lanDiscoveryRevision++;
    this.activeServiceUrl = this.serviceUrl;
    this.lanModeActive = false;
    this.profileVisible = false;
    const readerContext = this.ApplicationContext();
    if (readerContext !== undefined) this.SwitchReaderStorageScope(readerContext, 'device:v2:guest');
    this.localAccountScope = '';
    this.readerSyncStatus = '未登录 · 数据保存在本机';
    this.profileName = '';
    this.profileUsername = '';
    this.profileRole = '';
    this.profileAvatar = '';
    this.ReleaseProfileAvatarPixelMap();
    this.profileAvatarFailed = false;
    this.account = '';
    this.password = '';
    this.authStatus = '';
    this.mediaSources = [];
    this.remoteBookSources = [];
    this.selectedRemoteBookSourceId = '';
    this.remoteBookSourceSelectorVisible = false;
    this.remoteBookItems = [];
    this.remoteBookCoverEntries = [];
    this.remoteBookPath = '/';
    this.remoteBookPage = 1;
    this.remoteBookTotal = 0;
    this.remoteBookLoadedCount = 0;
    this.remoteBookHasMore = false;
    this.remoteBookLoadingMore = false;
    this.mediaItems = [];
    this.SetMediaBrowseThumbnailEntries([]);
    this.mediaBrowseThumbnailFailedPaths = [];
    this.mediaThumbnailEntries = [];
    this.mediaSelectorMode = '';
    this.remoteBookSourceSelectorVisible = false;
    this.mediaDirectoryGroupFilter = 'all';
    this.mediaDirectoryItems = [];
    this.ClearRemoteSubtitle();
    this.selectedMediaSourceId = '';
    this.mediaPath = '/';
    this.mediaStatus = '';
    this.marketItems = [];
    this.marketTotal = 0;
    this.marketDetail = undefined;
    this.marketStatus = '';
    this.marketLoading = false;
    this.mediaLoading = false;
    this.toolLoading = false;
    this.mediaDirectoryRevision++;
    this.opdsNavigationRevision++;
    this.opdsCredentialRevision++;
    this.remoteBookDirectoryRevision++;
    this.toolDirectoryRevision++;
    this.mediaDirectoryLoading = false;
    this.remoteBookLoading = false;
    this.opdsLoading = false;
    this.opdsCredentialBusy = false;
    this.toolOperationBusy = false;
    this.toolUploadProgress = 0;
    this.pendingMediaSourceId = '';
    this.pendingMediaPath = '/';
  }

  private HandleSessionInvalidated(): void {
    this.authenticationUiRevision++;
    this.accountOperationRevision++;
    this.activeDownloadCancellation?.cancel();
    this.activeMediaSourceCancellation?.cancel();
    this.activeRemoteBookSourceCancellation?.cancel();
    this.activeMediaDirectoryCancellation?.cancel();
    this.activeMediaLoadMoreCancellation?.cancel();
    this.activeRemoteBookDirectoryCancellation?.cancel();
    this.activeRemoteBookCoverCancellation?.cancel();
    this.activeToolDirectoryCancellation?.cancel();
    this.activeMediaOpenCancellation?.cancel();
    this.activeImageUpgradeCancellation?.cancel();
    this.activeBrowseThumbnailCancellation?.cancel();
    this.activeThumbnailCancellation?.cancel();
    this.ClearMediaPreloads();
    this.activeReaderTicketCancellation?.cancel();
    this.readerOpeningLoader?.cancel();
    this.activeMarketCancellation?.cancel();
    this.marketOperationRevision++;
    this.activeProfileCancellation?.cancel();
    this.CancelReaderPulls();
    this.CancelReaderDataSummary();
    this.readerDataDeleteRevision++;
    this.readerDataDeleting = false;
    if (this.mediaViewerVisible && this.currentMediaKind !== 'image') {
      this.UpdateMediaPlaybackProgress(this.playerTimeMs, this.playerDurationMs);
      this.PersistMediaPlaybackProgress();
    }
    this.pendingMediaSourceId = this.selectedMediaSourceId;
    this.pendingMediaPath = this.mediaPath;
    this.mediaOpenRevision++;
    this.queuedImageNavigationDirection = 0;
    this.queuedAudioNavigationDirection = 0;
    this.audioQueueExpanded = false;
    this.InvalidateBookSourceOperation();
    this.copilotOperationRevision++;
    this.readerProgressSyncGeneration++;
    this.mediaOpening = false;
    this.mediaSelectorMode = '';
    this.remoteBookSourceSelectorVisible = false;
    this.authenticated = false;
    this.localAccountScope = '';
    this.readerSyncStatus = '会话失效 · 等待重新登录同步';
    this.profileName = '';
    this.profileUsername = '';
    this.profileRole = '';
    this.profileAvatar = '';
    this.ReleaseProfileAvatarPixelMap();
    this.profileAvatarFailed = false;
    this.mediaSources = [];
    this.remoteBookSources = [];
    this.mediaItems = [];
    this.mediaDirectoryGroupFilter = 'all';
    this.mediaDirectoryItems = [];
    this.ClearRemoteSubtitle();
    this.remoteBookItems = [];
    this.remoteBookCoverEntries = [];
    this.selectedRemoteBookSourceId = '';
    this.remoteBookPath = '/';
    this.remoteBookPage = 1;
    this.remoteBookTotal = 0;
    this.remoteBookLoadedCount = 0;
    this.remoteBookHasMore = false;
    this.remoteBookLoadingMore = false;
    this.selectedMediaSourceId = '';
    this.mediaStatus = '';
    this.mediaViewerVisible = false;
    this.currentMediaName = '';
    this.currentMediaKind = '';
    this.currentMediaUrl = '';
    this.currentMediaPath = '';
    this.mediaPlaybackError = '';
    this.videoController.stop();
    this.StopMediaMetrics();
    this.audioPlayer.release();
    this.CancelCopilotRefresh();
    this.dshApi = undefined;
    this.copilotInitialized = false;
    this.copilotGatewayModel = '';
    this.copilotSettingsVisible = false;
    this.copilotMessages = [];
    this.copilotRunSteps = [];
    this.activeCopilotConversationId = '';
    this.copilotAttachment = undefined;
    this.copilotAttachmentStatus = '';
    this.copilotDeleteConfirm = false;
    this.copilotTurn = undefined;
    this.copilotRecoveryPlan = undefined;
    this.copilotBusy = false;
    this.copilotCancelling = false;
    this.marketItems = [];
    this.marketTotal = 0;
    this.marketDetail = undefined;
    this.marketStatus = '登录会话已失效，请重新登录';
    this.marketLoading = false;
    this.mediaLoading = false;
    this.toolLoading = false;
    this.authStatus = '登录会话已失效，请重新登录';
    this.password = '';
    this.OpenLogin();
  }

  private AccountOperationCurrent(revision: number, owner: string): boolean {
    if (revision !== this.accountOperationRevision || owner !== this.localAccountScope) return false;
    return owner.length === 0 ? !this.authenticated : this.authenticated;
  }

  private PickerContextCurrent(accountRevision: number, owner: string, lifecycleRevision: number): boolean {
    return lifecycleRevision === this.pageLifecycleRevision &&
      this.AccountOperationCurrent(accountRevision, owner);
  }

  @Builder
  private MediaSourceSelector() {
    Scroll() {
      Row({ space: 8 }) {
        ForEach(this.mediaSources, (source: RemoteMediaSource) => {
          Button(source.name.length > 0 ? source.name : source.type)
            .height(42).fontSize(13).fontFamily('HarmonyOS Sans')
            .fontColor(this.selectedMediaSourceId === source.id ? AppTheme.primary : AppTheme.textSecondary)
            .padding({ left: 14, right: 14 }).borderRadius(AppTheme.pillRadius)
            .backgroundColor(this.selectedMediaSourceId === source.id ? AppTheme.accentSoft : AppTheme.surface)
            .border({ width: 1, color: this.selectedMediaSourceId === source.id ? AppTheme.primary : AppTheme.divider,
              radius: AppTheme.pillRadius })
            .accessibilityText(`远程数据源：${source.name.length > 0 ? source.name : source.type}`)
            .accessibilityDescription(this.selectedMediaSourceId === source.id ? '已选择' : '未选择')
            .onClick(() => this.SelectMediaSource(source))
        }, (source: RemoteMediaSource) => source.id)
      }
    }.width('100%').height(46).scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off)
  }

  @Builder
  private MediaItemRow(item: RemoteMediaItem) {
    Row({ space: 12 }) {
      SymbolGlyph($r(item.name === '返回上级目录' ? 'sys.symbol.arrow_up_left' :
        (item.kind === 'directory' ? 'sys.symbol.folder_fill' : (item.kind === 'image' ?
          'sys.symbol.picture' : (item.kind === 'audio' ? 'sys.symbol.music_note_circle_fill' :
            'sys.symbol.play_fill')))))
        .width(38)
        .height(38)
        .fontSize(19)
        .fontColor([AppTheme.primary])
        .backgroundColor(AppTheme.surfaceTint)
        .borderRadius(10)
      Column({ space: 4 }) {
        Text(item.name)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .fontSize(16)
          .fontColor(AppTheme.textPrimary)
        if (item.kind !== 'directory') {
          Text(this.FormatMediaSize(item.size))
            .fontSize(12)
            .fontColor(AppTheme.textSecondary)
        }
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)
      if (item.kind !== 'directory') {
        Button() {
          SymbolGlyph($r(this.IsMediaFavorite(this.selectedMediaSourceId, item.path) ?
            'sys.symbol.star_fill' : 'sys.symbol.star')).fontSize(22)
            .fontColor([this.IsMediaFavorite(this.selectedMediaSourceId, item.path) ? AppTheme.primary :
              AppTheme.textSecondary])
        }.width(48).height(48)
          .backgroundColor(Color.Transparent)
          .accessibilityText(this.IsMediaFavorite(this.selectedMediaSourceId, item.path) ? '取消收藏' : '收藏媒体')
          .onClick(() => this.ToggleMediaFavorite(item))
      }
      SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(24).fontColor([AppTheme.textSecondary])
    }
    .width('100%')
    .padding({ top: 12, bottom: 12 })
    .border({ width: { bottom: 1 }, color: AppTheme.divider })
    .onClick(() => {
      if (item.kind === 'directory') {
        this.mediaPath = item.path;
        this.mediaDirectoryGroupFilter = 'all';
        this.LoadMediaDirectory();
      } else {
        this.OpenRemoteMedia(item);
      }
    })
  }

  @Builder
  private RemoteBookSourceSelector() {
    Button() {
      Row({ space: 10 }) {
        SymbolGlyph($r('sys.symbol.doc_plaintext')).fontSize(18)
          .fontColor([AppTheme.primary])
        Column({ space: 2 }) {
          Text('图书来源').fontSize(12).fontColor(AppTheme.textSecondary)
          Text(this.CurrentRemoteBookSourceLabel()).width('100%').maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis }).fontSize(15).fontWeight(FontWeight.Medium)
            .fontColor(AppTheme.textPrimary)
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        Text(`${this.remoteBookSources.length} 个`).fontSize(12).fontColor(AppTheme.textSecondary)
        SymbolGlyph($r('sys.symbol.chevron_down')).fontSize(16).fontColor([AppTheme.textSecondary])
      }.width('100%')
    }.width('100%').height(56).padding({ left: 15, right: 14 })
      .backgroundColor(AppTheme.surface).border({ width: 1, color: AppTheme.divider, radius: 16 })
      .accessibilityText(`图书来源：${this.CurrentRemoteBookSourceLabel()}`)
      .accessibilityDescription('打开电子书来源选择器')
      .onClick(() => this.OpenRemoteBookSourceSelector())
  }

  @Builder
  private RemoteBookSourceSelectorSheet() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column().width('100%').height('100%').backgroundColor('#66000000')
        .onClick(() => this.remoteBookSourceSelectorVisible = false)
      Column({ space: 12 }) {
        Row() {
          Button('取消').width(64).height(48).padding(0).fontSize(14).fontColor(AppTheme.textSecondary)
            .backgroundColor(Color.Transparent).onClick(() => this.remoteBookSourceSelectorVisible = false)
          Text('选择图书来源').layoutWeight(1).textAlign(TextAlign.Center)
            .fontSize(18).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
          Button('完成').width(64).height(48).padding(0).fontSize(14).fontColor(AppTheme.primary)
            .backgroundColor(Color.Transparent).onClick(() => this.ConfirmRemoteBookSourceSelector())
        }.width('100%')
        TextPicker({ range: this.RemoteBookSourcePickerOptions(), selected: this.remoteBookSourcePickerIndex })
          .width('100%').height(238).canLoop(false).defaultPickerItemHeight(48)
          .textStyle({ color: AppTheme.textSecondary, font: { size: 16 } })
          .selectedTextStyle({ color: AppTheme.primary, font: { size: 20, weight: FontWeight.Bold } })
          .onChange((_value: string | string[], index: number | number[]) => {
            if (typeof index === 'number') this.remoteBookSourcePickerIndex = index;
          })
        Row({ space: 10 }) {
          SymbolGlyph($r('sys.symbol.folder_fill')).fontSize(18).fontColor([AppTheme.primary])
          Text(this.PendingRemoteBookSourceDescription()).layoutWeight(1).maxLines(2)
            .fontSize(13).fontColor(AppTheme.textSecondary)
        }.width('calc(100% - 36vp)').constraintSize({ minHeight: 52 })
          .padding({ left: 14, right: 14, top: 8, bottom: 8 })
          .borderRadius(14).backgroundColor(AppTheme.surfaceTint)
      }.width('100%').height(420).padding({ top: 8, bottom: 18 })
        .borderRadius({ topLeft: 28, topRight: 28 }).backgroundColor(AppTheme.surface)
        .onClick(() => {})
    }.width('100%').height('100%')
  }

  @Builder
  private RemoteBookRow(item: RemoteMediaItem) {
    Row({ space: 12 }) {
      if (item.kind === 'directory') {
        Column() {
          SymbolGlyph($r('sys.symbol.folder_fill')).fontSize(20).fontColor([AppTheme.primary])
        }.width(44).height(44).justifyContent(FlexAlign.Center)
          .backgroundColor(AppTheme.surfaceTint).borderRadius(12)
      } else if ((item.bookCoverUri ?? '').length > 0) {
        Image(item.bookCoverUri ?? '').width(44).height(58).objectFit(ImageFit.Cover)
          .borderRadius(6).backgroundColor(AppTheme.surfaceTint)
      } else {
        Image($r('app.media.reader_default_cover')).width(44).height(66)
          .objectFit(ImageFit.Cover).borderRadius(6)
      }
      Column({ space: 4 }) {
        Text(item.kind === 'directory' ? item.name : this.RemoteBookDisplayTitle(item)).maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .fontSize(16).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
        if (item.kind !== 'directory') {
          Text(this.RemoteBookMetaLine(item)).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .fontSize(12).fontColor(AppTheme.textSecondary)
          if (this.RemoteBookDisplayTags(item).length > 0) {
            Row({ space: 5 }) {
              ForEach(this.RemoteBookDisplayTags(item).slice(0, 3), (tag: string) => {
                Button(`#${tag}`).height(26).constraintSize({ maxWidth: 86 }).padding({ left: 7, right: 7 })
                  .fontSize(10).fontColor(AppTheme.primary).backgroundColor(AppTheme.surfaceTint).borderRadius(9)
                  .accessibilityText(`筛选远程图书标签：${tag}`)
                  .onClick(() => this.FilterRemoteBooksByTag(tag))
              }, (tag: string) => tag)
            }.width('100%')
          }
        }
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      if (item.kind !== 'directory' && this.RemoteBookProgress(item) > 0) {
        Text(`${this.RemoteBookProgress(item)}%`).fontSize(13).fontColor('#F29A16')
      }
      SymbolGlyph($r('sys.symbol.chevron_right')).width(22).fontSize(17).fontColor([AppTheme.textSecondary])
    }
    .width('100%')
    .constraintSize({ minHeight: item.kind === 'directory' ? 64 : 78 })
    .padding({ left: 2, right: 2, top: 10, bottom: 10 })
    .backgroundColor(Color.Transparent)
    .border({ width: { bottom: 1 }, color: AppTheme.divider })
    .accessibilityText(item.kind === 'directory' ? `打开目录：${item.name}` : `查看图书：${item.name}`)
    .accessibilityDescription(item.kind === 'directory' ? '进入远程电子书目录' : '打开图书详情')
    .onClick(() => {
      if (item.kind === 'directory') {
        this.remoteBookPath = item.path;
        this.LoadRemoteBookDirectory();
      } else {
        this.OpenRemoteBookDetail(item);
      }
    })
  }

  @Builder
  private OpdsNavigationRow(entry: OpdsNavigationEntry) {
    Button() {
      Row({ space: 12 }) {
        SymbolGlyph($r('sys.symbol.folder_fill')).width(38).height(38).fontSize(19)
          .fontColor([AppTheme.primary]).backgroundColor(AppTheme.surfaceTint).borderRadius(10)
        Text(entry.title).layoutWeight(1).maxLines(1).fontSize(16).fontColor(AppTheme.textPrimary)
        SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(22).fontColor([AppTheme.primary])
      }
    }.width('100%').height(64).padding({ top: 12, bottom: 12 }).backgroundColor(Color.Transparent)
      .border({ width: { bottom: 1 }, color: AppTheme.divider })
      .accessibilityText(`打开OPDS目录：${entry.title}`).onClick(() => this.LoadOpdsCatalog(entry.url))
  }

  @Builder
  private OpdsBookRow(entry: OpdsBookEntry) {
    Button() {
      Row({ space: 12 }) {
        Image($r('app.media.reader_default_cover')).width(40).height(60)
          .objectFit(ImageFit.Cover).borderRadius(5)
        Column({ space: 4 }) {
          Text(entry.title).width('100%').maxLines(1).fontSize(16).fontColor(AppTheme.textPrimary)
          Text(`${entry.author} · ${entry.format.toUpperCase()}`).fontSize(12).fontColor(AppTheme.textSecondary)
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        Text('详情').fontSize(14).fontColor(AppTheme.primary)
      }
    }.width('100%').height(80).padding({ top: 10, bottom: 10 }).backgroundColor(Color.Transparent)
      .border({ width: { bottom: 1 }, color: AppTheme.divider })
      .accessibilityText(`查看OPDS图书：${entry.title}`).accessibilityDescription('打开图书详情')
      .onClick(() => this.OpenOpdsBookDetail(entry))
  }

  private async ImportBookSources(): Promise<void> {
    this.InvalidateBookSourceOperation();
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    const lifecycleRevision = this.pageLifecycleRevision;
    try {
      const hostContext = this.getUIContext().getHostContext();
      if (hostContext === undefined) throw new Error('无法获取应用上下文');
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      options.fileSuffixFilters = ['书源 JSON|json'];
      const documentPicker = new picker.DocumentViewPicker(hostContext as common.UIAbilityContext);
      const uris = await documentPicker.select(options);
      if (!this.PickerContextCurrent(accountRevision, owner, lifecycleRevision)) return;
      if (uris.length === 0) return;
      const content = await fs.readText(uris[0]);
      if (!this.PickerContextCurrent(accountRevision, owner, lifecycleRevision)) return;
      this.ApplyImportedBookSources(content, '导入');
    } catch (error) {
      if (!this.PickerContextCurrent(accountRevision, owner, lifecycleRevision)) return;
      this.bookStatus = error instanceof Error ? error.message : '导入书源失败';
    }
  }

  private ApplyImportedBookSources(content: string, action: string): void {
      const result = this.bookSourceImporter.importJson(content);
      const merged = new Map<string, BookSource>();
      this.bookSources.forEach((source: BookSource) => merged.set(source.bookSourceUrl, source));
      result.sources.forEach((source: BookSource) => {
        const previous = merged.get(source.bookSourceUrl);
        if (previous !== undefined) {
          source.healthStatus = previous.healthStatus;
          source.healthLatencyMs = previous.healthLatencyMs;
          source.healthCheckedAt = previous.healthCheckedAt;
          source.healthMessage = previous.healthMessage;
          source.healthHistory = previous.healthHistory;
          source.healthSuccessRate = previous.healthSuccessRate;
          source.healthAverageLatencyMs = previous.healthAverageLatencyMs;
          source.healthConsecutiveFailures = previous.healthConsecutiveFailures;
          source.healthDegraded = previous.healthDegraded;
          source.syncRevision = previous.syncRevision;
        }
        source.syncUpdatedAt = Date.now();
        this.bookSourceTombstones = this.bookSourceTombstones
          .filter((value: BookSourceTombstone) => value.sourceUrl !== source.bookSourceUrl);
        merged.set(source.bookSourceUrl, source);
      });
      this.bookSources = Array.from(merged.values());
      this.PersistReaderState();
      result.sources.forEach((source: BookSource) => this.QueueBookSource(this.BookSourceSyncItemFromSource(source)));
      this.bookStatus = `${action} ${result.sources.length} 个书源` +
        (result.scriptedCount > 0 ? `；${result.scriptedCount} 个需要受限脚本运行时` : '') +
        (result.rejectedCount > 0 ? `；忽略 ${result.rejectedCount} 个无效项` : '');
  }

  private async DiscoverBookSource(): Promise<void> {
    const url = this.sourceDiscoveryUrl.trim();
    if (this.sourceDiscoveryBusy) return;
    if (!this.authenticated) {
      this.sourceDiscoveryStatus = '请先登录后使用网站书源分析';
      return;
    }
    if (!/^https?:\/\/[^\s]+$/i.test(url)) {
      this.sourceDiscoveryStatus = '请输入完整的 HTTP 或 HTTPS 网站地址';
      return;
    }
    const operationRevision = ++this.bookSourceOperationRevision;
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    const lifecycleRevision = this.pageLifecycleRevision;
    this.sourceDiscoveryBusy = true;
    this.sourceDiscoveryStatus = '后端正在校验书源仓库或站点适配器…';
    try {
      const api = new BookSourceDiscoveryApi(this.ActiveServiceUrl(), this.authManager);
      let task: BookSourceDiscoveryTask = await api.start(url);
      const deadline = Date.now() + 510000;
      while (task.status === 'RUNNING' && Date.now() < deadline) {
        await this.WaitForBookSourceDiscoveryPoll();
        if (operationRevision !== this.bookSourceOperationRevision ||
          !this.PickerContextCurrent(accountRevision, owner, lifecycleRevision)) return;
        task = await api.find(task.taskId);
      }
      if (task.status !== 'SUCCEEDED') {
        throw new Error(task.status === 'RUNNING' ? '书源分析超时，请稍后重试' : '该网站暂时无法生成安全书源');
      }
      if (task.sourceJson.length > 0) this.ApplyImportedBookSources(task.sourceJson, '生成并添加');
      await this.PullBookSources();
      this.sourceDiscoveryStatus = task.message.length > 0 ? task.message : '书源已保存到服务端并同步';
    } catch (error) {
      if (operationRevision !== this.bookSourceOperationRevision ||
        !this.PickerContextCurrent(accountRevision, owner, lifecycleRevision)) return;
      this.sourceDiscoveryStatus = error instanceof Error ? error.message : '网站书源分析失败';
    } finally {
      if (operationRevision === this.bookSourceOperationRevision) this.sourceDiscoveryBusy = false;
    }
  }

  private WaitForBookSourceDiscoveryPoll(): Promise<void> {
    return new Promise<void>((resolve: () => void): void => {
      setTimeout(resolve, 1500);
    });
  }

  private async SearchBookSources(): Promise<void> {
    const keyword = this.sourceSearchText.trim();
    if (keyword.length === 0 || this.sourceSearchLoading) return;
    this.ResetBookSourceExplorePaging();
    this.sourceSearchReturnOffset = 0;
    this.sourceSearchReturnPending = false;
    this.sourceSearchReturnRestoreScheduled = false;
    this.sourceSearchReturnRevision++;
    try {
      this.sourceSearchScroller.scrollEdge(Edge.Top);
    } catch (_) {
    }
    this.sourceSearchPageVisible = true;
    this.sourceSearchActiveKeyword = keyword;
    this.sourceSearchVisibleCount = this.sourceSearchRenderBatchSize;
    this.sourceSearchPage = 0;
    this.sourceSearchHasMore = false;
    this.sourceSearchResults = [];
    if (this.bookSources.length === 0) {
      this.bookStatus = '请先导入书源';
      return;
    }
    const revision = ++this.bookSourceOperationRevision;
    this.activeBookSourceSearchCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeBookSourceSearchCancellation = cancellation;
    this.sourceSearchLoading = true;
    this.bookStatus = '正在查询后端搜索缓存…';
    try {
      const results = await this.RunBookSourceRuntimeSearch(keyword, 1, revision, cancellation, false);
      if (revision !== this.bookSourceOperationRevision || cancellation.isCancelled()) return;
      this.sourceSearchPage = 1;
      this.sourceSearchHasMore = results.length > 0;
      this.bookSources = this.SortedBookSources();
      this.PersistReaderState();
    } catch (error) {
      if (revision !== this.bookSourceOperationRevision) return;
      if (cancellation.isCancelled()) return;
      this.bookStatus = error instanceof Error ? error.message : '书源搜索失败';
    } finally {
      if (this.activeBookSourceSearchCancellation === cancellation) {
        this.activeBookSourceSearchCancellation = undefined;
      }
      if (revision === this.bookSourceOperationRevision) this.sourceSearchLoading = false;
      if (revision === this.bookSourceOperationRevision) this.sourceSearchTaskId = '';
    }
  }

  private async LoadNextBookSourceSearchPage(): Promise<void> {
    if (this.sourceSearchLoading || !this.sourceSearchHasMore || this.sourceSearchPage >= 100 ||
      this.sourceSearchActiveKeyword.length === 0) return;
    const page = this.sourceSearchPage + 1;
    const revision = ++this.bookSourceOperationRevision;
    this.activeBookSourceSearchCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeBookSourceSearchCancellation = cancellation;
    this.sourceSearchLoading = true;
    this.bookStatus = `后端正在搜索“${this.sourceSearchActiveKeyword}”第 ${page} 页…`;
    try {
      const previousSize = this.sourceSearchResults.length;
      const pageResults = await this.RunBookSourceRuntimeSearch(this.sourceSearchActiveKeyword, page,
        revision, cancellation, true);
      if (revision !== this.bookSourceOperationRevision || cancellation.isCancelled()) return;
      this.sourceSearchPage = page;
      this.sourceSearchHasMore = pageResults.length > 0 && this.sourceSearchResults.length > previousSize && page < 100;
      this.bookStatus = pageResults.length === 0 || this.sourceSearchResults.length === previousSize ?
        '已经到达搜索结果末页' : `已加载 ${this.sourceSearchResults.length} 条搜索结果`;
    } catch (error) {
      if (revision !== this.bookSourceOperationRevision) return;
      if (cancellation.isCancelled()) return;
      this.bookStatus = error instanceof Error ? error.message : '书源搜索翻页失败';
    } finally {
      if (this.activeBookSourceSearchCancellation === cancellation) {
        this.activeBookSourceSearchCancellation = undefined;
      }
      if (revision === this.bookSourceOperationRevision) this.sourceSearchLoading = false;
    }
  }

  private async RunBookSourceRuntimeSearch(keyword: string, page: number, revision: number,
    cancellation: DownloadCancellationToken, append: boolean): Promise<BookSourceSearchResult[]> {
    const api = new BookSourceRuntimeSearchApi(this.ActiveServiceUrl(), this.authManager);
    let task: BookSourceRuntimeSearchTask = await api.start(keyword, page, this.sourceSearchMode);
    this.sourceSearchTaskId = task.taskId;
    let offset = 0;
    const pageResults = new Map<string, BookSourceSearchResult>();
    const merged = new Map<string, BookSourceSearchResult>();
    if (append) this.sourceSearchResults.forEach((result: BookSourceSearchResult) =>
      merged.set(this.BookSourceResultIdentity(result), result));
    while (true) {
      if (revision !== this.bookSourceOperationRevision || cancellation.isCancelled()) return [];
      if (task.results.length > 0) {
        task.results.forEach((result: BookSourceSearchResult) => {
          const key = this.BookSourceResultIdentity(result);
          pageResults.set(key, result);
          merged.set(key, result);
        });
        this.sourceSearchResults = this.RankBookSourceSearchResults(Array.from(merged.values()), keyword);
      }
      offset = task.nextOffset;
      const searchedSources = Math.max(0, task.processedSources - task.cachedSources);
      const progress = `缓存 ${task.cachedSources} · 已查 ${searchedSources}/${task.pendingSources} · ` +
        `失败 ${task.failedSources} · 结果 ${task.resultCount}`;
      this.bookStatus = task.status === 'RUNNING' ? progress :
        (task.status === 'COMPLETED' ? `完成 · ${progress}` : task.message);
      if (task.hasMore) {
        task = await api.find(task.taskId, offset);
        continue;
      }
      if (task.status !== 'RUNNING') {
        if (task.status === 'FAILED') throw new Error(task.message.length > 0 ? task.message : '后端书源搜索失败');
        if (task.status === 'CANCELLED') return Array.from(pageResults.values());
        return Array.from(pageResults.values());
      }
      await this.WaitForBookSourceRuntimeSearchPoll();
      task = await api.find(task.taskId, offset);
    }
  }

  private WaitForBookSourceRuntimeSearchPoll(): Promise<void> {
    return new Promise<void>((resolve: () => void): void => {
      setTimeout(resolve, 500);
    });
  }

  /** 终止当前后端搜索并保留已经返回的增量结果。 */
  private CancelBookSourceSearch(): void {
    const taskId = this.sourceSearchTaskId;
    this.activeBookSourceSearchCancellation?.cancel();
    this.activeBookSourceSearchCancellation = undefined;
    this.bookSourceOperationRevision++;
    this.sourceSearchLoading = false;
    this.sourceSearchTaskId = '';
    this.bookStatus = `已终止搜索，保留 ${this.sourceSearchResults.length} 条结果`;
    if (taskId.length > 0) {
      new BookSourceRuntimeSearchApi(this.ActiveServiceUrl(), this.authManager).cancel(taskId).catch(() => {});
    }
  }

  private BookSourceSearchModeLabel(mode: string = this.sourceSearchMode): string {
    if (mode === 'EXACT') return '精确';
    if (mode === 'PROBE') return '探测';
    return '模糊';
  }

  private BookSourceSearchModeDescription(mode: string): string {
    if (mode === 'EXACT') return '书名完全匹配';
    if (mode === 'PROBE') return '智能分析线索';
    return '包含关键字';
  }

  private BookSourceSearchPlaceholder(): string {
    return this.sourceSearchMode === 'PROBE' ? '输入主角、部分书名或剧情' : '搜索书名或作者';
  }

  /** 使用规范化书名和作者消除跨书源重复结果。 */
  private BookSourceResultIdentity(result: BookSourceSearchResult): string {
    const name = result.name.toLocaleLowerCase().replace(/[\s　·・:：,，.。!！?？()（）\[\]【】_-]/g, '');
    // 同一本书在不同书源中的作者格式经常不一致，列表按规范化书名保留一个结果。
    return name;
  }

  private RankBookSourceSearchResults(results: BookSourceSearchResult[], keyword: string): BookSourceSearchResult[] {
    const normalizedKeyword = keyword.trim().toLocaleLowerCase();
    if (normalizedKeyword.length === 0) return results;
    return results.slice().sort((left: BookSourceSearchResult, right: BookSourceSearchResult): number => {
      const leftName = left.name.trim().toLocaleLowerCase();
      const rightName = right.name.trim().toLocaleLowerCase();
      const leftRank = leftName === normalizedKeyword ? 0 : leftName.startsWith(normalizedKeyword) ? 1 :
        leftName.includes(normalizedKeyword) ? 2 : 3;
      const rightRank = rightName === normalizedKeyword ? 0 : rightName.startsWith(normalizedKeyword) ? 1 :
        rightName.includes(normalizedKeyword) ? 2 : 3;
      if (leftRank !== rightRank) return leftRank - rightRank;
      return leftName.localeCompare(rightName);
    });
  }

  private OpenBookSourceExplore(source: BookSource): void {
    try {
      const categories = this.bookSourceSearchEngine.exploreCategories(source);
      if (categories.length === 0) {
        this.bookStatus = '该书源没有可请求的发现分类';
        return;
      }
      if (categories.length === 1) {
        this.ExploreBookSource(source, categories[0]);
        return;
      }
      this.sourceExploreSourceUrl = source.bookSourceUrl;
      this.sourceExploreCategories = categories;
      this.bookStatus = `请选择“${source.bookSourceName}”的发现分类`;
    } catch (error) {
      this.bookStatus = error instanceof Error ? error.message : '书源发现分类解析失败';
    }
  }

  private CloseBookSourceExplore(): void {
    this.sourceExploreSourceUrl = '';
    this.sourceExploreCategories = [];
  }

  private async ExploreBookSource(source: BookSource, category: BookSourceExploreCategory): Promise<void> {
    if (this.sourceSearchLoading) return;
    // 发现结果展示在电子书主页面，离开管理页避免用户误以为点击没有响应。
    this.bookSourceManagementVisible = false;
    this.sourceManageExpandedUrl = '';
    this.sourceManageFilterVisible = false;
    this.ResetBookSourceSearchPaging();
    this.sourceExploreActiveUrl = category.url;
    this.sourceExploreActiveTitle = category.title;
    this.sourceExplorePage = 0;
    this.sourceExploreHasMore = false;
    this.sourceSearchResults = [];
    this.sourceSearchPageVisible = true;
    this.sourceSearchVisibleCount = this.sourceSearchRenderBatchSize;
    await this.LoadBookSourceExplorePage(source, category, 1, false);
  }

  private async LoadNextBookSourceExplorePage(): Promise<void> {
    if (this.sourceSearchLoading || !this.sourceExploreHasMore || this.sourceExplorePage >= 100) return;
    const source = this.bookSources.find((value: BookSource) =>
      value.bookSourceUrl === this.sourceExploreActiveSourceUrl);
    if (source === undefined) {
      this.bookStatus = '当前发现书源已不存在';
      this.ResetBookSourceExplorePaging();
      return;
    }
    const category: BookSourceExploreCategory = {
      title: this.sourceExploreActiveTitle,
      url: this.sourceExploreActiveUrl
    };
    await this.LoadBookSourceExplorePage(source, category, this.sourceExplorePage + 1, true);
  }

  private async LoadBookSourceExplorePage(source: BookSource, category: BookSourceExploreCategory, page: number,
    append: boolean): Promise<void> {
    const revision = ++this.bookSourceOperationRevision;
    this.activeBookSourceSearchCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeBookSourceSearchCancellation = cancellation;
    this.sourceSearchLoading = true;
    this.sourceExploreActiveSourceUrl = source.bookSourceUrl;
    this.bookStatus = `正在加载“${source.bookSourceName} · ${category.title}”第 ${page} 页…`;
    try {
      const pageResults = await this.bookSourceSearchEngine.explore(source, category.url, page, cancellation);
      if (revision !== this.bookSourceOperationRevision) return;
      const merged = new Map<string, BookSourceSearchResult>();
      const previousSize = append ? this.sourceSearchResults.length : 0;
      if (append) this.sourceSearchResults.forEach((result: BookSourceSearchResult) =>
        merged.set(`${result.sourceUrl}|${result.bookUrl}`, result));
      pageResults.forEach((result: BookSourceSearchResult) =>
        merged.set(`${result.sourceUrl}|${result.bookUrl}`, result));
      const results = await this.CacheSourceSearchCovers(Array.from(merged.values()).slice(0, 500), cancellation);
      if (revision !== this.bookSourceOperationRevision) return;
      this.sourceSearchResults = results;
      this.sourceExplorePage = page;
      this.sourceExploreHasMore = pageResults.length > 0 && this.sourceSearchResults.length > previousSize &&
        page < 100 && this.sourceSearchResults.length < 500;
      this.bookStatus = pageResults.length === 0 ? (append ? '已经到达该分类末页' :
        '该书源发现请求成功，但当前没有返回图书') :
        `已从“${source.bookSourceName} · ${category.title}”加载 ${this.sourceSearchResults.length} 本图书`;
    } catch (error) {
      if (revision !== this.bookSourceOperationRevision) return;
      if (cancellation.isCancelled()) return;
      if (!append) this.sourceSearchResults = [];
      this.bookStatus = error instanceof Error ? error.message : '书源发现加载失败';
    } finally {
      if (this.activeBookSourceSearchCancellation === cancellation) {
        this.activeBookSourceSearchCancellation = undefined;
      }
      if (revision === this.bookSourceOperationRevision) this.sourceSearchLoading = false;
    }
  }

  private ResetBookSourceExplorePaging(): void {
    this.sourceExploreActiveUrl = '';
    this.sourceExploreActiveTitle = '';
    this.sourceExploreActiveSourceUrl = '';
    this.sourceExplorePage = 0;
    this.sourceExploreHasMore = false;
  }

  private ResetBookSourceSearchPaging(): void {
    this.sourceSearchActiveKeyword = '';
    this.sourceSearchPage = 0;
    this.sourceSearchHasMore = false;
  }

  private InvalidateBookSourceOperation(): void {
    this.activeBookSourceSearchCancellation?.cancel();
    this.activeBookSourceSearchCancellation = undefined;
    this.activeBookSourceHealthCancellation?.cancel();
    this.activeBookSourceHealthCancellation = undefined;
    this.bookSourceOperationRevision++;
    this.sourceSearchLoading = false;
    this.sourceHealthLoading = false;
  }

  private async CheckBookSourceHealth(): Promise<void> {
    if (this.sourceHealthLoading || this.bookSources.length === 0) return;
    this.sourceHealthLoading = true;
    this.activeBookSourceHealthCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeBookSourceHealthCancellation = cancellation;
    const managementRevision = ++this.sourceManagementRevision;
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.bookStatus = '正在分批检查书源…';
    const keyword = this.sourceSearchText.trim().length > 0 ? this.sourceSearchText.trim() : '测试';
    const targets = this.bookSources.slice();
    try {
      for (let offset = 0; offset < targets.length; offset += 4) {
        const batch = targets.slice(offset, offset + 4);
        await Promise.all(batch.map((source: BookSource) =>
          this.bookSourceSearchEngine.checkHealth(source, keyword, cancellation)));
        if (cancellation.isCancelled()) return;
        if (!this.SourceManagementCurrent(accountRevision, owner, managementRevision)) return;
        this.bookSources = this.SortedBookSources();
        this.bookStatus = `已检查 ${Math.min(offset + batch.length, targets.length)} / ${targets.length}`;
      }
      const available = this.bookSources.filter((source: BookSource) => source.healthStatus === 'available').length;
      const partial = this.bookSources.filter((source: BookSource) => source.healthStatus === 'partial').length;
      const unavailable = this.bookSources.filter((source: BookSource) => source.healthStatus === 'unavailable').length;
      this.bookStatus = `检查完成：可用 ${available}，部分可用 ${partial}，不可用 ${unavailable}`;
      this.PersistReaderState();
    } catch (error) {
      if (!this.SourceManagementCurrent(accountRevision, owner, managementRevision)) return;
      if (cancellation.isCancelled()) return;
      this.bookStatus = error instanceof Error ? error.message : '书源健康检查失败';
    } finally {
      if (this.activeBookSourceHealthCancellation === cancellation) {
        this.activeBookSourceHealthCancellation = undefined;
      }
    }
    if (this.SourceManagementCurrent(accountRevision, owner, managementRevision)) this.sourceHealthLoading = false;
  }

  private SortedBookSources(): BookSource[] {
    const rank = (source: BookSource): number => {
      if (source.healthDegraded === true) return 5;
      if (source.healthStatus === 'available') return 0;
      if (source.healthStatus === 'partial') return 1;
      if (source.healthStatus === undefined || source.healthStatus === 'unknown') return 2;
      if (source.healthStatus === 'unsupported') return 3;
      return 4;
    };
    return this.bookSources.slice().sort((left: BookSource, right: BookSource) => {
      const rankDifference = rank(left) - rank(right);
      if (rankDifference !== 0) return rankDifference;
      const leftLatency = (left.healthAverageLatencyMs ?? 0) > 0 ? left.healthAverageLatencyMs ?? 999999 :
        (left.healthLatencyMs === undefined || left.healthLatencyMs <= 0 ? 999999 : left.healthLatencyMs);
      const rightLatency = (right.healthAverageLatencyMs ?? 0) > 0 ? right.healthAverageLatencyMs ?? 999999 :
        (right.healthLatencyMs === undefined || right.healthLatencyMs <= 0 ? 999999 : right.healthLatencyMs);
      return leftLatency - rightLatency;
    });
  }

  private EnabledBookSourceCount(): number {
    return this.bookSources.filter((source: BookSource) => source.enabled).length;
  }

  private FilteredShelfBooks(): Book[] {
    const keyword = this.shelfSearchText.trim().toLowerCase();
    const scoped = this.ModeShelfBooks();
    if (keyword.length === 0) return scoped;
    return scoped.filter((book: Book) => book.name.toLowerCase().includes(keyword) ||
      book.author.toLowerCase().includes(keyword) ||
      this.BookShelfChapterTitle(book).toLowerCase().includes(keyword) ||
      this.BookShelfTags(book).some((tag: string) => tag.toLowerCase().includes(keyword)));
  }

  /** 根据顶部模式返回完全隔离的书架数据。 */
  private ModeShelfBooks(): Book[] {
    if (this.bookModeIndex === 0) return this.books.filter((book: Book) => book.origin === 'source');
    if (this.bookModeIndex === 2) return this.books.filter((book: Book) => book.origin === 'local');
    return this.books.filter((book: Book) => book.origin === 'remote');
  }

  private BookShelfTitle(): string {
    if (this.bookModeIndex === 0) return '书源书架';
    if (this.bookModeIndex === 1) return '远程书库';
    return '本地书架';
  }

  private BookShelfHeaderAction(): string {
    if (this.bookModeIndex === 0) return '书源管理';
    if (this.bookModeIndex === 1) return this.ebookTransferBusy ? '上传中' : '上传书籍';
    return '添加书籍';
  }

  private BookShelfHeaderSymbol(): string {
    if (this.bookModeIndex === 0) return 'sys.symbol.square_stack_3d_fill';
    if (this.bookModeIndex === 1) return 'sys.symbol.cloud';
    return 'sys.symbol.plus';
  }

  private RunBookShelfHeaderAction(): void {
    if (this.bookModeIndex === 0) {
      this.sourceManageVisibleCount = this.sourceManageRenderBatchSize;
      this.bookSourceManagementVisible = true;
    } else if (this.bookModeIndex === 1) {
      this.UploadSelectedBooksToRemote();
    } else {
      this.PickLocalBooks();
    }
  }

  private EmptyShelfTitle(): string {
    return this.bookModeIndex === 0 ? '书源书架还是空的' : '本地书架还是空的';
  }

  private EmptyShelfDescription(): string {
    return this.bookModeIndex === 0 ? '从搜索结果加入图书，进度只在书源书架中展示' :
      '通过系统文件选择器一次添加一本或多本图书';
  }

  private BookShelfChapterTitle(book: Book): string {
    const progress = this.readingProgress.find((value: ReadingProgress) => value.bookId === book.id);
    const chapter = progress?.chapterTitle.trim() ?? '';
    if (chapter.length > 0) return chapter;
    return book.progress > 0 ? '正文' : '尚未开始';
  }

  private BookShelfMetadataLine(book: Book): string {
    const tags = this.BookShelfTags(book).slice(0, 2).join(' · ');
    const suffix = tags.length > 0 ? ` · ${tags}` : '';
    return `阅读至 ${this.BookShelfChapterTitle(book)}${suffix}`;
  }

  private BookShelfTags(book: Book): string[] {
    const tags: string[] = [];
    (book.tags ?? []).forEach((tag: string) => {
      const normalized = tag.trim();
      if (normalized.length > 0 && !tags.includes(normalized)) tags.push(normalized);
    });
    const origin = this.BookOriginShortLabel(book);
    if (!tags.includes(origin)) tags.push(origin);
    const format = book.format.toUpperCase();
    if (!tags.includes(format)) tags.push(format);
    return tags.sort((left: string, right: string) => {
      const leftAdult = left.startsWith('R18-');
      const rightAdult = right.startsWith('R18-');
      if (leftAdult === rightAdult) return 0;
      return leftAdult ? -1 : 1;
    }).slice(0, 3);
  }

  private BookOriginShortLabel(book: Book): string {
    if (book.origin === 'source') return '书源';
    if (book.origin === 'remote') return '远程';
    return '本地';
  }

  private ProfileAvatarLabel(): string {
    if (!this.authenticated) return '我';
    const label = this.profileName.trim().length > 0 ? this.profileName.trim() :
      (this.profileUsername.trim().length > 0 ? this.profileUsername.trim() : this.account.trim());
    const characters = Array.from(label);
    return characters.length > 0 ? characters[0].toUpperCase() : '我';
  }

  private async RefreshProfileIdentity(): Promise<void> {
    if (!this.authenticated || this.localAccountScope.length === 0) return;
    this.activeProfileCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeProfileCancellation = cancellation;
    const revision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    try {
      const identity = await new ProfileApi(this.ActiveServiceUrl(), this.authManager).current(cancellation);
      if (!this.AccountOperationCurrent(revision, owner) || cancellation.isCancelled()) return;
      if (identity.nickname.trim().length > 0) this.profileName = identity.nickname.trim();
      await this.ApplyProfileAvatar(identity.avatar);
    } catch (_) {
      // 用户资料刷新失败不影响已恢复的登录会话与姓名头像回退。
    } finally {
      if (this.activeProfileCancellation === cancellation) this.activeProfileCancellation = undefined;
    }
  }

  private async ApplyProfileAvatar(avatar: string): Promise<void> {
    const normalized = avatar.trim();
    this.profileAvatar = normalized;
    this.profileAvatarFailed = false;
    this.ReleaseProfileAvatarPixelMap();
    const match = /^data:image\/(?:png|jpeg|webp);base64,([A-Za-z0-9+/]+={0,2})$/.exec(normalized);
    if (match === null) return;
    try {
      const bytes = new util.Base64Helper().decodeSync(match[1], util.Type.BASIC);
      if (bytes.byteLength < 3 || bytes.byteLength > 384 * 1024) throw new Error('头像图片大小无效');
      const source = image.createImageSource(bytes.buffer as ArrayBuffer);
      let pixelMap: image.PixelMap | undefined = undefined;
      try {
        pixelMap = await source.createPixelMap({ desiredSize: { width: 72, height: 72 } });
      } finally {
        source.release();
      }
      if (this.profileAvatar !== normalized || !this.authenticated) {
        pixelMap?.release();
        return;
      }
      this.profileAvatarPixelMap = pixelMap;
    } catch (_) {
      if (this.profileAvatar === normalized) this.profileAvatarFailed = true;
    }
  }

  private ReleaseProfileAvatarPixelMap(): void {
    this.profileAvatarPixelMap?.release();
    this.profileAvatarPixelMap = undefined;
  }

  private ProfileAvatarSource(): string {
    const avatar = this.profileAvatar.trim();
    if (avatar.startsWith('https://')) return avatar;
    if (avatar.startsWith('/') && !avatar.startsWith('//')) return `${this.ActiveServiceUrl()}${avatar}`;
    return '';
  }

  private FilteredManagedBookSources(): BookSource[] {
    const keyword = this.sourceManageText.trim().toLowerCase();
    return this.SortedBookSources().filter((source: BookSource) => {
      if (this.sourceManageFilter === 'enabled' && !source.enabled) return false;
      if (this.sourceManageFilter === 'disabled' && source.enabled) return false;
      if (keyword.length === 0) return true;
      return source.bookSourceName.toLowerCase().includes(keyword) ||
        source.bookSourceGroup.toLowerCase().includes(keyword) ||
        source.bookSourceUrl.toLowerCase().includes(keyword);
    });
  }

  private VisibleManagedBookSources(): BookSource[] {
    return this.FilteredManagedBookSources().slice(0, this.sourceManageVisibleCount);
  }

  private LoadMoreManagedBookSources(): void {
    const total = this.FilteredManagedBookSources().length;
    this.sourceManageVisibleCount = Math.min(total,
      this.sourceManageVisibleCount + this.sourceManageRenderBatchSize);
  }

  private VisibleBookSourceSearchResults(): BookSourceSearchResult[] {
    return this.sourceSearchResults.slice(0, this.sourceSearchVisibleCount);
  }

  private LoadMoreBookSourceSearchResults(): void {
    if (this.sourceSearchVisibleCount < this.sourceSearchResults.length) {
      this.sourceSearchVisibleCount = Math.min(this.sourceSearchResults.length,
        this.sourceSearchVisibleCount + this.sourceSearchRenderBatchSize);
      return;
    }
    if (this.sourceSearchLoading) return;
    if (this.sourceExploreActiveUrl.length > 0 && this.sourceExploreHasMore) {
      this.LoadNextBookSourceExplorePage();
    } else if (this.sourceExploreActiveUrl.length === 0 && this.sourceSearchHasMore) {
      this.LoadNextBookSourceSearchPage();
    }
  }

  private BookSourceManageFilterLabel(): string {
    if (this.sourceManageFilter === 'enabled') return '已启用';
    if (this.sourceManageFilter === 'disabled') return '已停用';
    return '全部';
  }

  private BookSourceManageSubtitle(source: BookSource): string {
    const group = source.bookSourceGroup.trim().length > 0 ? `${source.bookSourceGroup.trim()} · ` : '';
    return `${group}${this.BookSourceHealthLabel(source)}`;
  }

  private BookSourceHealthLabel(source: BookSource): string {
    if (!source.enabled) return '已停用 · 不参与搜索和健康检查';
    if (source.healthStatus === undefined || source.healthStatus === 'unknown') {
      return source.requiresScriptRuntime ? '待检查 · 包含脚本规则' : '待检查';
    }
    const latency = (source.healthLatencyMs ?? 0) > 0 ? ` · ${source.healthLatencyMs}ms` : '';
    const history = source.healthHistory ?? [];
    const trend = history.length === 0 ? '' :
      ` · 近${history.length}次 ${Math.round((source.healthSuccessRate ?? 0) * 100)}%`;
    const degraded = source.healthDegraded === true ? ' · 已自动降级' : '';
    const failures = (source.healthConsecutiveFailures ?? 0) > 0 ?
      ` · 连续失败${source.healthConsecutiveFailures}次` : '';
    if (source.healthStatus === 'available') return `可用${latency}${trend}${degraded} · ${source.healthMessage ?? ''}`;
    if (source.healthStatus === 'partial') return `部分可用${latency}${trend}${degraded} · ${source.healthMessage ?? ''}`;
    if (source.healthStatus === 'unsupported') return `未检查 · ${source.healthMessage ?? '需要脚本运行时'}`;
    return `不可用${latency}${trend}${failures}${degraded} · ${source.healthMessage ?? '请求失败'}`;
  }

  private ToggleBookSource(sourceUrl: string): void {
    this.InvalidateBookSourceOperation();
    let sourceName = '';
    let enabled = false;
    this.bookSources = this.bookSources.map((source: BookSource): BookSource => {
      if (source.bookSourceUrl !== sourceUrl) return source;
      sourceName = source.bookSourceName;
      enabled = !source.enabled;
      return this.CopyBookSourceWithEnabled(source, enabled);
    });
    this.sourceSearchResults = this.sourceSearchResults.filter((result: BookSourceSearchResult) =>
      result.sourceUrl !== sourceUrl || enabled);
    if (!enabled && this.sourceExploreActiveSourceUrl === sourceUrl) this.ResetBookSourceExplorePaging();
    if (!enabled && this.sourceExploreSourceUrl === sourceUrl) this.CloseBookSourceExplore();
    this.PersistReaderState();
    const changed = this.bookSources.find((source: BookSource) => source.bookSourceUrl === sourceUrl);
    if (changed !== undefined) this.QueueBookSource(this.BookSourceSyncItemFromSource(changed));
    this.bookStatus = `${sourceName}已${enabled ? '启用' : '停用'}`;
  }

  private CopyBookSourceWithEnabled(source: BookSource, enabled: boolean): BookSource {
    return {
      bookSourceUrl: source.bookSourceUrl,
      bookSourceName: source.bookSourceName,
      bookSourceGroup: source.bookSourceGroup,
      bookSourceType: source.bookSourceType,
      enabled,
      enabledExplore: source.enabledExplore,
      searchUrl: source.searchUrl,
      exploreUrl: source.exploreUrl,
      header: source.header,
      ruleSearch: source.ruleSearch,
      ruleExplore: source.ruleExplore,
      ruleBookInfo: source.ruleBookInfo,
      ruleToc: source.ruleToc,
      ruleContent: source.ruleContent,
      requiresScriptRuntime: source.requiresScriptRuntime,
      healthStatus: source.healthStatus,
      healthLatencyMs: source.healthLatencyMs,
      healthCheckedAt: source.healthCheckedAt,
      healthMessage: source.healthMessage,
      healthHistory: source.healthHistory,
      healthSuccessRate: source.healthSuccessRate,
      healthAverageLatencyMs: source.healthAverageLatencyMs,
      healthConsecutiveFailures: source.healthConsecutiveFailures,
      healthDegraded: source.healthDegraded,
      syncUpdatedAt: Date.now(),
      syncRevision: source.syncRevision
    };
  }

  private PrepareDeleteBookSource(source: BookSource): void {
    this.sourceManagementRevision++;
    this.sourceDeleteUrl = source.bookSourceUrl;
    this.sourceDeleteName = source.bookSourceName;
  }

  private async OpenBookSourceCredential(source: BookSource): Promise<void> {
    const managementRevision = ++this.sourceManagementRevision;
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.sourceCredentialUrl = source.bookSourceUrl;
    this.sourceCredentialName = source.bookSourceName;
    this.sourceCredentialValue = '';
    this.sourceCredentialBusy = false;
    if (!source.bookSourceUrl.startsWith('https://')) {
      this.sourceCredentialStatus = '该书源不是HTTPS地址，不能配置敏感凭据';
    } else {
      try {
        const configured = await this.bookSourceCredentialStore.hasCredential(source.bookSourceUrl);
        if (!this.SourceCredentialCurrent(accountRevision, owner, managementRevision, source.bookSourceUrl)) return;
        this.sourceCredentialStatus = configured ?
          '已配置安全凭据；保存新值将原子替换旧凭据' : '当前未配置安全凭据';
      } catch (error) {
        if (!this.SourceCredentialCurrent(accountRevision, owner, managementRevision, source.bookSourceUrl)) return;
        this.sourceCredentialStatus = error instanceof Error ? error.message : '无法读取书源凭据状态';
      }
    }
    this.CancelDeleteBookSource();
  }

  private CloseBookSourceCredential(): void {
    this.sourceManagementRevision++;
    this.sourceCredentialUrl = '';
    this.sourceCredentialName = '';
    this.sourceCredentialValue = '';
    this.sourceCredentialStatus = '';
    this.sourceCredentialBusy = false;
  }

  private async SaveBookSourceCredential(sourceUrl: string): Promise<void> {
    if (sourceUrl !== this.sourceCredentialUrl) return;
    const managementRevision = ++this.sourceManagementRevision;
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    const header = this.sourceCredentialHeader;
    const value = this.sourceCredentialValue;
    this.sourceCredentialBusy = true;
    try {
      await this.bookSourceCredentialStore.save(sourceUrl, header, value);
      if (!this.SourceCredentialCurrent(accountRevision, owner, managementRevision, sourceUrl)) return;
      this.sourceCredentialValue = '';
      this.sourceCredentialStatus = '凭据已保存；搜索、详情、目录和正文请求将按同源规则使用';
      this.sourceCredentialBusy = false;
    } catch (error) {
      if (!this.SourceCredentialCurrent(accountRevision, owner, managementRevision, sourceUrl)) return;
      this.sourceCredentialValue = '';
      this.sourceCredentialStatus = error instanceof Error ? error.message : '书源凭据保存失败';
      this.sourceCredentialBusy = false;
    }
  }

  private async ClearBookSourceCredential(sourceUrl: string): Promise<void> {
    if (sourceUrl !== this.sourceCredentialUrl) return;
    const managementRevision = ++this.sourceManagementRevision;
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.sourceCredentialBusy = true;
    try {
      await this.bookSourceCredentialStore.clear(sourceUrl);
      if (!this.SourceCredentialCurrent(accountRevision, owner, managementRevision, sourceUrl)) return;
      this.sourceCredentialValue = '';
      this.sourceCredentialStatus = '该书源凭据已清除';
      this.sourceCredentialBusy = false;
    } catch (error) {
      if (!this.SourceCredentialCurrent(accountRevision, owner, managementRevision, sourceUrl)) return;
      this.sourceCredentialStatus = error instanceof Error ? error.message : '书源凭据清除失败';
      this.sourceCredentialBusy = false;
    }
  }

  private SourceManagementCurrent(accountRevision: number, owner: string, managementRevision: number): boolean {
    return this.AccountOperationCurrent(accountRevision, owner) &&
      managementRevision === this.sourceManagementRevision;
  }

  private SourceCredentialCurrent(accountRevision: number, owner: string, managementRevision: number,
    sourceUrl: string): boolean {
    return this.SourceManagementCurrent(accountRevision, owner, managementRevision) &&
      sourceUrl === this.sourceCredentialUrl;
  }

  private CancelDeleteBookSource(): void {
    this.sourceManagementRevision++;
    this.sourceDeleteUrl = '';
    this.sourceDeleteName = '';
  }

  private BookSourceDeleteImpact(sourceUrl: string): string {
    const affected = this.books.filter((book: Book) => book.origin === 'source' &&
      (book.sourceId === sourceUrl || (book.sourceAlternatives ?? []).some(
        (alternative: BookSourceAlternative) => alternative.sourceId === sourceUrl))).length;
    if (affected === 0) return '删除后无法撤销；不会影响书架和阅读记录。';
    return `涉及书架中的 ${affected} 本图书：优先切换到其他已导入来源；没有备用来源的图书仍会保留。`;
  }

  private async DeleteBookSource(sourceUrl: string): Promise<void> {
    if (sourceUrl !== this.sourceDeleteUrl) return;
    if (this.detailBook?.sourceId === sourceUrl) this.activeSourceCatalogCancellation?.cancel();
    const removedSource = this.bookSources.find((source: BookSource) => source.bookSourceUrl === sourceUrl);
    const deletedName = this.sourceDeleteName;
    const managementRevision = ++this.sourceManagementRevision;
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.InvalidateBookSourceOperation();
    try {
      await this.bookSourceCredentialStore.clear(sourceUrl);
      if (!this.SourceManagementCurrent(accountRevision, owner, managementRevision) ||
        sourceUrl !== this.sourceDeleteUrl) return;
    } catch (error) {
      if (!this.SourceManagementCurrent(accountRevision, owner, managementRevision) ||
        sourceUrl !== this.sourceDeleteUrl) return;
      this.bookStatus = error instanceof Error ? error.message : '书源凭据清除失败，未删除书源';
      return;
    }
    const remainingSourceUrls = new Set<string>();
    this.bookSources.forEach((source: BookSource) => {
      if (source.bookSourceUrl !== sourceUrl) remainingSourceUrls.add(source.bookSourceUrl);
    });
    let migratedCount = 0;
    const migratedBooks: Book[] = [];
    this.books = this.books.map((book: Book): Book => {
      if (book.origin !== 'source') return book;
      const alternatives = (book.sourceAlternatives ?? []).filter((alternative: BookSourceAlternative) =>
        alternative.sourceId !== sourceUrl && remainingSourceUrls.has(alternative.sourceId));
      if (book.sourceId !== sourceUrl) return this.CopyBookWithAlternatives(book, alternatives);
      const replacement = alternatives.length > 0 ? alternatives[0] : undefined;
      if (replacement === undefined) return this.CopyBookWithAlternatives(book, alternatives);
      migratedCount++;
      const migratedBook: Book = {
        id: book.id,
        name: book.name,
        author: book.author,
        coverUrl: replacement.coverUrl,
        remoteCoverUrl: replacement.remoteCoverUrl,
        origin: book.origin,
        format: book.format,
        resourceUri: replacement.resourceUri,
        sourceId: replacement.sourceId,
        progress: book.progress,
        sourceAlternatives: alternatives,
        updatedAt: Date.now(),
        revision: book.revision
      };
      migratedBooks.push(migratedBook);
      return migratedBook;
    });
    this.bookSources = this.bookSources.filter((source: BookSource) => source.bookSourceUrl !== sourceUrl);
    this.sourceSearchResults = this.sourceSearchResults.filter((result: BookSourceSearchResult) =>
      result.sourceUrl !== sourceUrl);
    this.detailSourceCandidates = this.detailSourceCandidates.filter((result: BookSourceSearchResult) =>
      result.sourceUrl !== sourceUrl);
    if (this.sourceExploreActiveSourceUrl === sourceUrl) this.ResetBookSourceExplorePaging();
    if (this.sourceExploreSourceUrl === sourceUrl) this.CloseBookSourceExplore();
    if (this.detailBook?.sourceId === sourceUrl) {
      const migrated = this.books.find((book: Book) => book.id === this.detailBook?.id);
      if (migrated !== undefined && migrated.sourceId !== sourceUrl) this.detailBook = migrated;
      else this.bookDetailVisible = false;
    }
    this.CancelDeleteBookSource();
    if (removedSource !== undefined) {
      const tombstone = this.BookSourceSyncItemFromSource(removedSource, true);
      tombstone.updatedAt = Date.now();
      this.ApplyBookSourceSyncItem(tombstone);
      this.QueueBookSource(tombstone);
    }
    this.PersistReaderState();
    migratedBooks.forEach((book: Book) => this.QueueShelfBook(this.ShelfItemFromBook(book)));
    this.bookStatus = migratedCount > 0 ?
      `已删除${deletedName}，并为 ${migratedCount} 本图书切换备用来源` : `已删除${deletedName}`;
  }

  private CopyBookWithAlternatives(book: Book, alternatives: BookSourceAlternative[]): Book {
    return {
      id: book.id,
      name: book.name,
      author: book.author,
      coverUrl: book.coverUrl,
      remoteCoverUrl: book.remoteCoverUrl,
      origin: book.origin,
      format: book.format,
      resourceUri: book.resourceUri,
      sourceId: book.sourceId,
      tags: book.tags,
      progress: book.progress,
      sourceAlternatives: alternatives,
      updatedAt: book.updatedAt,
      revision: book.revision
    };
  }

  private OpenBookSourceDetail(result: BookSourceSearchResult): void {
    this.CaptureBookSourceSearchReturnPosition();
    this.activeSourceCatalogCancellation?.cancel();
    const existing = this.books.find((book: Book) => book.origin === 'source' &&
      this.BookMatchesResult(book, result));
    this.detailShelfBookId = existing?.id ?? '';
    this.detailBook = existing !== undefined && existing.sourceId === result.sourceUrl &&
      existing.resourceUri === result.bookUrl ? existing : {
      id: existing?.id ?? `source:${result.sourceUrl}:${result.bookUrl}`,
      name: result.name,
      author: result.author.length > 0 ? result.author : '未知作者',
      coverUrl: result.coverUrl,
      remoteCoverUrl: result.remoteCoverUrl,
      origin: 'source',
      format: resolveBookFormat(result.bookUrl),
      resourceUri: result.bookUrl,
      sourceId: result.sourceUrl,
      progress: existing?.progress ?? 0,
      sourceAlternatives: existing?.sourceAlternatives,
      updatedAt: existing?.updatedAt,
      revision: existing?.revision
    };
    this.detailIntro = result.intro;
    this.detailLastChapter = result.lastChapter;
    this.detailSourceName = result.sourceName;
    this.detailSourceStatus = '验证中';
    this.detailSourceLatencyMs = 0;
    this.detailChapterCount = 0;
    this.detailRemoteTags = [];
    this.detailRemoteSize = 0;
    this.detailRemoteModified = '';
    this.detailSourceCandidates = this.CollectSourceCandidates(result, existing?.sourceAlternatives);
    if (existing !== undefined) this.StoreAlternativesForShelf(existing.id);
    this.detailShowAllSources = false;
    this.detailCatalog = undefined;
    this.detailCatalogBookId = '';
    this.detailRemoveConfirmVisible = false;
    this.detailOperationBusy = false;
    this.detailOperationStatus = '';
    this.bookDetailVisible = true;
    this.ValidateDetailSource(this.detailBook);
  }

  private OpenBookDetail(book: Book, intro: string = ''): void {
    this.activeSourceCatalogCancellation?.cancel();
    const shelfBook = this.books.find((value: Book) => value.id === book.id);
    let selectedBook = shelfBook ?? book;
    if (shelfBook !== undefined && book.origin === 'remote' && book.cacheVersion !== undefined) {
      // 远程目录是文件版本权威来源，浏览详情时将旧书架快照升级到当前下载版本。
      selectedBook = {
        id: shelfBook.id, name: book.name, author: book.author,
        coverUrl: book.coverUrl.length > 0 ? book.coverUrl : shelfBook.coverUrl,
        remoteCoverUrl: book.remoteCoverUrl ?? shelfBook.remoteCoverUrl,
        origin: 'remote', format: book.format, resourceUri: book.resourceUri, sourceId: book.sourceId,
        cacheVersion: book.cacheVersion, tags: book.tags ?? shelfBook.tags, progress: shelfBook.progress,
        sourceAlternatives: shelfBook.sourceAlternatives,
        updatedAt: shelfBook.updatedAt, revision: shelfBook.revision
      };
      this.books = this.books.map((value: Book) => value.id === shelfBook.id ? selectedBook : value);
      this.PersistReaderState();
    }
    this.detailBook = selectedBook;
    this.detailShelfBookId = shelfBook?.id ?? '';
    this.detailIntro = intro;
    const progress = this.readingProgress.find((value: ReadingProgress) => value.bookId === selectedBook.id);
    this.detailLastChapter = progress?.chapterTitle ?? '';
    this.detailSourceName = selectedBook.id.startsWith('opds:') ? 'OPDS远程书库' :
      (selectedBook.origin === 'source' ?
        (this.bookSources.find((source: BookSource) => source.bookSourceUrl === selectedBook.sourceId)?.bookSourceName ??
          '已导入书源') : '');
    this.detailSourceStatus = selectedBook.origin === 'source' ? '验证中' : '';
    this.detailSourceLatencyMs = 0;
    this.detailChapterCount = 0;
    this.detailRemoteTags = [];
    this.detailRemoteSize = 0;
    this.detailRemoteModified = '';
    if (selectedBook.origin === 'source') {
      const currentCandidate: BookSourceSearchResult = {
        name: selectedBook.name,
        author: selectedBook.author,
        intro: intro,
        lastChapter: this.detailLastChapter,
        coverUrl: selectedBook.coverUrl,
        remoteCoverUrl: selectedBook.remoteCoverUrl,
        bookUrl: selectedBook.resourceUri,
        sourceUrl: selectedBook.sourceId,
        sourceName: this.detailSourceName
      };
      this.detailSourceCandidates = this.CollectSourceCandidates(currentCandidate, selectedBook.sourceAlternatives);
    } else {
      this.detailSourceCandidates = [];
    }
    this.detailShowAllSources = false;
    this.detailCatalog = undefined;
    this.detailCatalogBookId = '';
    this.detailRemoveConfirmVisible = false;
    this.detailOperationBusy = false;
    this.detailOperationStatus = '';
    this.bookDetailVisible = true;
    if (selectedBook.origin === 'source') this.ValidateDetailSource(selectedBook);
  }

  private CloseBookDetail(): void {
    const returnToReader = this.detailReturnToReader && this.currentBook !== undefined;
    this.activeSourceCatalogCancellation?.cancel();
    this.activeSourceCatalogCancellation = undefined;
    this.bookDetailVisible = false;
    this.detailBook = undefined;
    this.detailIntro = '';
    this.detailLastChapter = '';
    this.detailSourceName = '';
    this.detailSourceStatus = '待验证';
    this.detailSourceLatencyMs = 0;
    this.detailChapterCount = 0;
    this.detailSourceCandidates = [];
    this.detailRemoteTags = [];
    this.detailRemoteSize = 0;
    this.detailRemoteModified = '';
    this.detailShowAllSources = false;
    this.detailShelfBookId = '';
    this.detailCatalog = undefined;
    this.detailCatalogBookId = '';
    this.detailRemoveConfirmVisible = false;
    this.detailOperationBusy = false;
    this.detailOperationStatus = '';
    this.detailReturnToReader = false;
    if (returnToReader) {
      this.readerVisible = true;
      this.readerSessionStartedAt = Date.now();
      this.ApplyReaderOrientation(this.readerSettings.orientation);
      this.ApplyReaderSystemBars(true);
      this.StartReaderPersistenceTimer();
    } else {
      this.RestoreBookSourceSearchReturnPosition();
    }
  }

  /** 记录书源搜索列表离开前的滚动位置。 */
  private CaptureBookSourceSearchReturnPosition(): void {
    if (!this.sourceSearchPageVisible || this.bookModeIndex !== 0 || this.currentIndex !== 0) return;
    try {
      this.sourceSearchReturnOffset = Math.max(0, this.sourceSearchScroller.currentOffset().yOffset);
    } catch (_) {
      this.sourceSearchReturnOffset = 0;
    }
    this.sourceSearchReturnPending = true;
    this.sourceSearchReturnRestoreScheduled = false;
    this.sourceSearchReturnRevision++;
  }

  /** 在全屏详情或阅读页关闭、搜索列表重新挂载后恢复原位置。 */
  private RestoreBookSourceSearchReturnPosition(): void {
    if (!this.sourceSearchReturnPending || !this.sourceSearchPageVisible || this.bookModeIndex !== 0 ||
      this.currentIndex !== 0 || this.sourceSearchReturnRestoreScheduled) return;
    const offset = this.sourceSearchReturnOffset;
    const revision = this.sourceSearchReturnRevision;
    this.sourceSearchReturnRestoreScheduled = true;
    this.RestoreBookSourceSearchReturnPositionAttempt(revision, offset, 0);
  }

  /** 等待搜索列表完成布局后恢复滚动位置，避免首次scrollTo被未挂载容器忽略。 */
  private RestoreBookSourceSearchReturnPositionAttempt(revision: number, offset: number, attempt: number): void {
    const delays: number[] = [0, 16, 64, 160, 320];
    setTimeout(() => {
      if (revision !== this.sourceSearchReturnRevision || !this.sourceSearchReturnPending ||
        !this.sourceSearchPageVisible || this.bookModeIndex !== 0 || this.currentIndex !== 0) {
        if (revision === this.sourceSearchReturnRevision) this.sourceSearchReturnRestoreScheduled = false;
        return;
      }
      if (this.bookDetailVisible || this.readerVisible) {
        this.sourceSearchReturnRestoreScheduled = false;
        return;
      }
      try {
        this.sourceSearchScroller.scrollTo({ xOffset: 0, yOffset: offset, animation: false });
      } catch (_) {
      }
      if (attempt + 1 < delays.length) {
        this.RestoreBookSourceSearchReturnPositionAttempt(revision, offset, attempt + 1);
        return;
      }
      this.sourceSearchReturnPending = false;
      this.sourceSearchReturnRestoreScheduled = false;
    }, delays[attempt]);
  }

  /** 清除当前书源图书已持久化的目录和章节缓存。 */
  private async ClearDetailSourceCache(): Promise<void> {
    const book = this.detailBook;
    const context = this.ApplicationContext();
    if (book === undefined || book.origin !== 'source' || context === undefined || this.detailOperationBusy) return;
    this.detailOperationBusy = true;
    this.detailOperationStatus = '';
    try {
      await this.bookSourceLocalCache.clearBook(context, this.localAccountScope, book.sourceId, book.resourceUri);
      this.detailCatalog = undefined;
      this.detailCatalogBookId = '';
      if (this.currentBook?.id === book.id) this.CancelReaderChapterPrefetch();
      this.detailOperationStatus = '已清除离线缓存，下次阅读时会重新下载';
    } catch (error) {
      this.detailOperationStatus = error instanceof Error ? error.message : '清除书源缓存失败';
    } finally {
      this.detailOperationBusy = false;
    }
  }

  /** 永久删除当前远程或本地图书文件，并同步移出对应书架。 */
  private async DeleteDetailBookFile(): Promise<void> {
    const book = this.detailBook;
    if (book === undefined || book.origin === 'source' || this.detailOperationBusy) return;
    this.detailOperationBusy = true;
    this.detailOperationStatus = '';
    try {
      if (book.origin === 'remote') {
        if (!this.authenticated) throw new Error('登录后才能删除远程图书文件');
        await new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager)
          .deleteEntry(book.sourceId, book.resourceUri, false);
        // 服务端成功后才更新远程目录投影，失败时保留列表和书架数据。
        this.remoteBookItems = this.remoteBookItems.filter((item: RemoteMediaItem) => item.path !== book.resourceUri);
        this.remoteBookTotal = Math.max(0, this.remoteBookTotal - 1);
        this.remoteBookLoadedCount = Math.max(0, this.remoteBookLoadedCount - 1);
      } else {
        await this.localBookPicker.delete(book.resourceUri);
      }
      if (this.DetailBookInShelf()) this.RemoveDetailBookFromShelf(false);
      else this.CloseBookDetail();
      this.bookStatus = book.origin === 'remote' ? `已删除远程文件《${book.name}》` :
        `已删除本地文件《${book.name}》`;
    } catch (error) {
      this.detailOperationStatus = error instanceof Error ? error.message : '删除图书文件失败';
      this.detailOperationBusy = false;
    }
  }

  private RemoveDetailBookFromShelf(clearReadingData: boolean): void {
    const book = this.detailBook;
    if (book === undefined || !this.DetailBookInShelf()) return;
    if (book.origin !== 'local') {
      const removed = this.ShelfItemFromBook(book, true);
      removed.updatedAt = Date.now();
      this.ApplyShelfBook(removed);
      this.QueueShelfBook(removed);
    }
    this.books = this.books.filter((value: Book) => value.id !== book.id);
    if (clearReadingData) {
      const previousProgress = this.readingProgress.find((value: ReadingProgress) => value.bookId === book.id);
      const previousTombstone = this.readingProgressTombstones
        .find((value: ReadingProgressTombstone) => value.bookId === book.id);
      const deletedProgress: ReadingProgress = {
        bookId: book.id,
        chapterTitle: previousProgress?.chapterTitle ?? previousTombstone?.chapterTitle ?? '正文',
        locator: previousProgress?.locator ?? previousTombstone?.locator ?? 0,
        percentage: 0,
        updatedAt: Date.now(),
        revision: previousProgress?.revision ?? previousTombstone?.revision ?? 0,
        deleted: true
      };
      const removedMarkers: ReaderMarkerSyncItem[] = this.AllLocalReaderMarkers()
        .filter((value: ReaderMarkerSyncItem) => value.bookId === book.id && !value.deleted)
        .map((value: ReaderMarkerSyncItem): ReaderMarkerSyncItem => ({
          id: value.id, kind: value.kind, bookId: value.bookId, chapterTitle: value.chapterTitle,
          locator: value.locator, note: value.note, createdAt: value.createdAt,
          updatedAt: Date.now(), deleted: true, revision: value.revision
        }));
      this.readingProgress = this.readingProgress.filter((value: ReadingProgress) => value.bookId !== book.id);
      this.ReplaceReadingProgress(deletedProgress);
      this.SyncReadingProgress(deletedProgress);
      this.readerBookmarks = this.readerBookmarks.filter((value: ReaderBookmark) => value.bookId !== book.id);
      this.readerAnnotations = this.readerAnnotations.filter((value: ReaderAnnotation) => value.bookId !== book.id);
      removedMarkers.forEach((marker: ReaderMarkerSyncItem) => {
        this.readerMarkerTombstones = this.readerMarkerTombstones
          .filter((value: ReaderMarkerTombstone) => value.id !== marker.id).concat({
            id: marker.id, kind: marker.kind, bookId: marker.bookId, chapterTitle: marker.chapterTitle,
            locator: marker.locator, note: marker.note, createdAt: marker.createdAt,
            updatedAt: marker.updatedAt, revision: marker.revision
          });
        this.QueueReaderMarker(marker);
      });
      this.readingStatistics = this.readingStatistics.filter((value: ReadingStatistic) => value.bookId !== book.id);
    }
    this.PersistReaderState();
    this.bookStatus = clearReadingData ? `已移出《${book.name}》并清除阅读记录` :
      `已移出《${book.name}》，阅读记录已保留`;
    this.CloseBookDetail();
  }

  private DetailBookInShelf(): boolean {
    const book = this.detailBook;
    return book !== undefined && (this.detailShelfBookId.length > 0 ||
      this.books.some((value: Book) => value.id === book.id));
  }

  private BookMatchesResult(book: Book, result: BookSourceSearchResult): boolean {
    const candidate: BookSourceSearchResult = {
      name: book.name,
      author: book.author,
      intro: '',
      lastChapter: '',
      coverUrl: book.coverUrl,
      remoteCoverUrl: book.remoteCoverUrl,
      bookUrl: book.resourceUri,
      sourceUrl: book.sourceId,
      sourceName: ''
    };
    return this.SameBookIdentity(candidate, result);
  }

  private CollectSourceCandidates(current: BookSourceSearchResult,
    stored?: BookSourceAlternative[]): BookSourceSearchResult[] {
    const candidates: BookSourceSearchResult[] = [];
    const keys = new Set<string>();
    stored?.forEach((alternative: BookSourceAlternative) => {
      const key = `${alternative.sourceId}|${alternative.resourceUri}`;
      if (keys.has(key)) return;
      keys.add(key);
      candidates.push({
        name: current.name,
        author: current.author,
        intro: alternative.intro,
        lastChapter: alternative.lastChapter,
        coverUrl: alternative.coverUrl,
        remoteCoverUrl: alternative.remoteCoverUrl,
        bookUrl: alternative.resourceUri,
        sourceUrl: alternative.sourceId,
        sourceName: alternative.sourceName
      });
    });
    this.sourceSearchResults.forEach((result: BookSourceSearchResult) => {
      if (!this.SameBookIdentity(current, result)) return;
      const key = `${result.sourceUrl}|${result.bookUrl}`;
      if (keys.has(key)) return;
      keys.add(key);
      candidates.push(result);
    });
    const currentKey = `${current.sourceUrl}|${current.bookUrl}`;
    if (!keys.has(currentKey)) candidates.unshift(current);
    return candidates;
  }

  private SameBookIdentity(left: BookSourceSearchResult, right: BookSourceSearchResult): boolean {
    const leftName = this.NormalizeBookIdentity(left.name);
    const rightName = this.NormalizeBookIdentity(right.name);
    if (leftName.length === 0 || leftName !== rightName) return false;
    const leftAuthor = this.NormalizeBookIdentity(left.author.replace(/^作者[:：]?/i, ''));
    const rightAuthor = this.NormalizeBookIdentity(right.author.replace(/^作者[:：]?/i, ''));
    if (leftAuthor.length === 0 || rightAuthor.length === 0 || leftAuthor === '未知作者' || rightAuthor === '未知作者') {
      return true;
    }
    return leftAuthor === rightAuthor;
  }

  private NormalizeBookIdentity(value: string): string {
    return value.toLowerCase().replace(/[\s·・:：,，.。\-—_《》「」『』()（）\[\]]/g, '');
  }

  private SwitchDetailSource(candidate: BookSourceSearchResult): void {
    if (candidate.sourceUrl === this.detailBook?.sourceId && candidate.bookUrl === this.detailBook?.resourceUri) return;
    this.activeSourceCatalogCancellation?.cancel();
    const shelfBook = this.detailShelfBookId.length > 0 ?
      this.books.find((book: Book) => book.id === this.detailShelfBookId) : undefined;
    const selected: Book = {
      id: shelfBook?.id ?? `source:${candidate.sourceUrl}:${candidate.bookUrl}`,
      name: candidate.name,
      author: candidate.author.length > 0 ? candidate.author : '未知作者',
      coverUrl: candidate.coverUrl,
      remoteCoverUrl: candidate.remoteCoverUrl,
      origin: 'source',
      format: resolveBookFormat(candidate.bookUrl),
      resourceUri: candidate.bookUrl,
      sourceId: candidate.sourceUrl,
      progress: shelfBook?.progress ?? 0,
      sourceAlternatives: this.BuildStoredAlternatives(),
      updatedAt: Date.now(),
      revision: shelfBook?.revision ?? 0
    };
    this.detailBook = selected;
    if (shelfBook !== undefined) {
      this.books = this.books.map((book: Book) => book.id === shelfBook.id ? selected : book);
      this.PersistReaderState();
      this.QueueShelfBook(this.ShelfItemFromBook(selected));
    }
    this.detailIntro = candidate.intro;
    this.detailLastChapter = candidate.lastChapter;
    this.detailSourceName = candidate.sourceName;
    this.detailSourceStatus = '验证中';
    this.detailSourceLatencyMs = 0;
    this.detailChapterCount = 0;
    this.detailCatalog = undefined;
    this.detailCatalogBookId = '';
    this.detailShowAllSources = false;
    this.ValidateDetailSource(this.detailBook);
  }

  private DetailOriginLabel(): string {
    if (this.detailBook?.origin === 'source') return '网络书源';
    if (this.detailBook?.origin === 'remote') return '远程书库';
    return '本地图书';
  }

  private DetailFallbackIntro(): string {
    if (this.detailBook?.origin === 'source') return '该书源未返回简介，试读时将实时加载目录和正文。';
    if (this.detailBook?.origin === 'remote') return '来自已登录MyTools远程书库，打开时使用短期访问票据读取。';
    return '由你通过系统文件选择器主动导入，应用不会扫描设备中的其他文件。';
  }

  private AddDetailBookToShelf(): void {
    const book = this.detailBook;
    if (book === undefined || this.DetailBookInShelf()) return;
    const stored: Book = {
      id: book.id,
      name: book.name,
      author: book.author,
      coverUrl: book.coverUrl,
      remoteCoverUrl: book.remoteCoverUrl,
      origin: book.origin,
      format: book.format,
      resourceUri: book.resourceUri,
      sourceId: book.sourceId,
      cacheVersion: book.cacheVersion,
      tags: book.tags,
      progress: book.progress,
      sourceAlternatives: book.origin === 'source' ? this.BuildStoredAlternatives() : undefined,
      updatedAt: Date.now(),
      revision: 0
    };
    this.detailBook = stored;
    this.detailShelfBookId = stored.id;
    this.books = this.books.concat(stored);
    this.PersistReaderState();
    if (stored.origin !== 'local') this.QueueShelfBook(this.ShelfItemFromBook(stored));
    this.bookStatus = `已将《${book.name}》加入书架`;
    this.ScheduleShelfBookLocalCache(stored, this.detailCatalog);
  }

  /** 加入书架后独立缓存目录、当前阅读位置和后续二十章，不依赖阅读页面继续显示。 */
  private ScheduleShelfBookLocalCache(book: Book, preparedDocument?: ReaderDocument): void {
    if (book.origin !== 'source' || book.format !== 'unknown' || this.localAccountScope.length === 0) return;
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    setTimeout(() => {
      this.CacheShelfBookWindow(book, preparedDocument, accountRevision, owner)
        .catch((error: Error) => console.warn(`Shelf book cache failed: ${error.message}`));
    }, 0);
  }

  /** 执行书架图书的本地章节缓存窗口。 */
  private async CacheShelfBookWindow(book: Book, preparedDocument: ReaderDocument | undefined,
    accountRevision: number, owner: string): Promise<void> {
    const context = this.ApplicationContext();
    const source = this.bookSources.find((value: BookSource) => value.bookSourceUrl === book.sourceId);
    if (context === undefined || source === undefined || !this.ShelfBookCacheCurrent(book, accountRevision, owner)) {
      return;
    }
    const api = new BookSourceRuntimeReaderApi(this.ActiveServiceUrl(), this.authManager);
    const document = preparedDocument ?? (await api.catalog(source.bookSourceUrl, book.resourceUri)).document;
    if (!this.ShelfBookCacheCurrent(book, accountRevision, owner)) return;
    await this.bookSourceLocalCache.putCatalog(context, owner, source.bookSourceUrl, book.resourceUri, document);
    const progress = this.readingProgress.find((value: ReadingProgress) => value.bookId === book.id);
    const progressIndex = progress === undefined ? -1 :
      document.chapters.findIndex((chapter: ReaderChapter) => chapter.title === progress.chapterTitle);
    const startIndex = Math.max(0, progressIndex);
    const endIndex = Math.min(document.chapters.length - 1, startIndex + this.readerChapterPrefetchCount);
    for (let index = startIndex; index <= endIndex; index++) {
      if (!this.ShelfBookCacheCurrent(book, accountRevision, owner)) return;
      const chapter = document.chapters[index];
      if (chapter === undefined) continue;
      const cached = await this.bookSourceLocalCache.chapter(context, owner, source.bookSourceUrl,
        book.resourceUri, chapter, index);
      if (cached !== undefined) continue;
      const loadedChapter = this.currentBook?.id === book.id ? this.readerChapters[index] : undefined;
      const content = loadedChapter !== undefined && loadedChapter.content.trim().length > 0 ?
        { kind: 'text', text: loadedChapter.content, imageUris: [], remoteImageUrls: [] } as SourceChapterContent :
        await api.content(source.bookSourceUrl, chapter, index);
      if (content.kind === 'text' && this.ShelfBookCacheCurrent(book, accountRevision, owner)) {
        await this.bookSourceLocalCache.putChapter(context, owner, source.bookSourceUrl,
          book.resourceUri, chapter, index, content);
      }
      await new Promise<void>((resolve: () => void) => setTimeout(resolve, 40));
    }
  }

  private ShelfBookCacheCurrent(book: Book, accountRevision: number, owner: string): boolean {
    return this.AccountOperationCurrent(accountRevision, owner) &&
      this.books.some((value: Book) => value.id === book.id && value.sourceId === book.sourceId &&
        value.resourceUri === book.resourceUri);
  }

  private BuildStoredAlternatives(): BookSourceAlternative[] {
    return this.detailSourceCandidates.map((candidate: BookSourceSearchResult): BookSourceAlternative => {
      return {
        sourceId: candidate.sourceUrl,
        sourceName: candidate.sourceName,
        resourceUri: candidate.bookUrl,
        coverUrl: candidate.coverUrl,
        remoteCoverUrl: candidate.remoteCoverUrl,
        intro: candidate.intro,
        lastChapter: candidate.lastChapter
      };
    });
  }

  private StoreAlternativesForShelf(bookId: string): void {
    const alternatives = this.BuildStoredAlternatives();
    this.books = this.books.map((book: Book) => {
      if (book.id !== bookId) return book;
      return {
        id: book.id,
        name: book.name,
        author: book.author,
        coverUrl: book.coverUrl,
        remoteCoverUrl: book.remoteCoverUrl,
        origin: book.origin,
        format: book.format,
        resourceUri: book.resourceUri,
        sourceId: book.sourceId,
        progress: book.progress,
        sourceAlternatives: alternatives,
        updatedAt: book.updatedAt,
        revision: book.revision
      };
    });
    this.PersistReaderState();
  }

  private async ValidateDetailSource(book: Book | undefined): Promise<void> {
    if (book === undefined || book.origin !== 'source') return;
    if (book.id.startsWith('opds:') && book.resourceUri.startsWith('https://') && book.format !== 'unknown') {
      this.detailSourceStatus = '可用';
      return;
    }
    const source = this.bookSources.find((value: BookSource) => value.bookSourceUrl === book.sourceId);
    if (source === undefined) {
      this.detailSourceStatus = '已移除';
      return;
    }
    const startedAt = Date.now();
    this.activeSourceCatalogCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeSourceCatalogCancellation = cancellation;
    try {
      const catalog = await new BookSourceRuntimeReaderApi(this.ActiveServiceUrl(), this.authManager)
        .catalog(source.bookSourceUrl, book.resourceUri);
      const document = catalog.document;
      if (!this.bookDetailVisible || this.detailBook?.id !== book.id) return;
      this.detailSourceLatencyMs = Math.max(1, Date.now() - startedAt);
      this.detailChapterCount = document.chapters.length;
      this.detailCatalog = document;
      this.detailCatalogBookId = book.id;
      this.detailSourceStatus = '可用';
      this.detailIntro = this.PreferredDetailIntro(this.detailIntro, catalog.intro);
      if (catalog.latestChapter.length > 0) this.detailLastChapter = catalog.latestChapter;
      if (this.detailLastChapter.length === 0 && document.chapters.length > 0) {
        this.detailLastChapter = document.chapters[document.chapters.length - 1].title;
      }
    } catch (_) {
      if (!this.bookDetailVisible || this.detailBook?.id !== book.id) return;
      if (cancellation.isCancelled()) return;
      this.detailSourceLatencyMs = Math.max(1, Date.now() - startedAt);
      this.detailSourceStatus = '不可用';
    } finally {
      if (this.activeSourceCatalogCancellation === cancellation) this.activeSourceCatalogCancellation = undefined;
    }
  }

  private ReadDetailBook(): void {
    const book = this.detailBook;
    if (book === undefined) return;
    if (this.detailReturnToReader && this.currentBook?.id === book.id) {
      this.CloseBookDetail();
      return;
    }
    if (book.origin === 'source' && !this.DetailBookInShelf()) {
      book.sourceAlternatives = this.BuildStoredAlternatives();
    }
    this.activeSourceCatalogCancellation?.cancel();
    this.readerReturnToDetail = true;
    this.detailReturnToReader = false;
    this.bookDetailVisible = false;
    this.OpenBook(book);
  }

  /** 从阅读控制栏打开详情，关闭详情时恢复同一阅读现场。 */
  private OpenCurrentReaderBookDetail(): void {
    const book = this.currentBook;
    if (book === undefined) return;
    this.ClearScheduledReaderProgressSave();
    this.SaveCurrentProgress();
    this.FinishReadingSession();
    this.StopReaderPersistenceTimer();
    this.StopReaderSpeech();
    this.readerControlsVisible = false;
    this.readerMoreVisible = false;
    this.readerVisible = false;
    this.detailReturnToReader = true;
    this.readerReturnToDetail = false;
    this.ApplyReaderSystemBars(false);
    this.OpenBookDetail(book);
  }

  private PreferredDetailIntro(current: string, refreshed: string): string {
    const previous = current.trim();
    const incoming = refreshed.trim();
    if (incoming.length === 0 || incoming === 'null' || incoming === 'undefined' || incoming === '暂无简介') {
      return previous;
    }
    if (previous.length > 0 && incoming.length < previous.length) return previous;
    return incoming;
  }

  private ShouldPromptReaderShelf(): boolean {
    const book = this.currentBook;
    return book !== undefined && book.origin === 'source' &&
      this.books.find((value: Book) => value.id === book.id) === undefined;
  }

  private AddCurrentReaderBookToShelf(): void {
    const book = this.currentBook;
    if (book === undefined || this.books.find((value: Book) => value.id === book.id) !== undefined) return;
    const stored: Book = {
      id: book.id, name: book.name, author: book.author, coverUrl: book.coverUrl,
      remoteCoverUrl: book.remoteCoverUrl, origin: book.origin, format: book.format,
      resourceUri: book.resourceUri, sourceId: book.sourceId, cacheVersion: book.cacheVersion,
      tags: book.tags, progress: book.progress, sourceAlternatives: book.sourceAlternatives,
      updatedAt: Date.now(), revision: 0
    };
    this.currentBook = stored;
    this.books = this.books.concat(stored);
    this.PersistReaderState();
    this.QueueShelfBook(this.ShelfItemFromBook(stored));
    const context = this.ApplicationContext();
    const source = this.bookSources.find((value: BookSource) => value.bookSourceUrl === stored.sourceId);
    if (context !== undefined && source !== undefined && stored.format === 'unknown' &&
      this.readerChapters.length > 0) {
      this.bookSourceLocalCache.putCatalog(context, this.localAccountScope, source.bookSourceUrl,
        stored.resourceUri, { kind: 'text', chapters: this.readerChapters, imageUris: [] })
        .catch((error: Error) => console.warn(`Source catalog cache failed: ${error.message}`));
    }
    this.ScheduleShelfBookLocalCache(stored,
      this.readerChapters.length > 0 ? { kind: 'text', chapters: this.readerChapters, imageUris: [] } : undefined);
    // 试读加入书架后立即把当前章节和后续二十章转为账户隔离的持久缓存。
    this.ScheduleReaderChapterPrefetch();
  }

  private async PickLocalBooks(): Promise<void> {
    try {
      const hostContext = this.getUIContext().getHostContext();
      if (hostContext === undefined) throw new Error('无法获取应用上下文');
      const selected = await this.localBookPicker.select(hostContext as common.UIAbilityContext);
      // 系统文件选择器返回瞬间会重建页面状态，用户的明确选择结果不应被短暂状态变化丢弃。
      const merged = new Map<string, Book>();
      this.books.forEach((book: Book) => merged.set(book.id, book));
      let addedCount = 0;
      selected.forEach((book: Book) => {
        if (!merged.has(book.id)) addedCount++;
        merged.set(book.id, book);
      });
      this.books = Array.from(merged.values());
      this.PersistReaderState();
      this.bookStatus = addedCount > 0 ? `已添加 ${addedCount} 本图书` :
        (selected.length > 0 ? '所选图书已在书架中' : '未选择支持的电子书文件');
    } catch (error) {
      this.bookStatus = error instanceof Error ? error.message : '选择本地图书失败';
    }
  }

  /** 从远程书库入口批量选择并顺序上传电子书。 */
  private async UploadSelectedBooksToRemote(): Promise<void> {
    if (this.ebookTransferBusy) return;
    if (!this.authenticated) {
      this.OpenLogin();
      return;
    }
    const context = this.ApplicationContext();
    if (context === undefined) {
      this.bookStatus = '无法打开系统文件选择器';
      return;
    }
    try {
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 50;
      options.fileSuffixFilters = ['电子书|txt,epub,pdf,mobi,azw3,cbz,zip'];
      const uris = await new picker.DocumentViewPicker(context).select(options);
      // 文件选择器期间用户无法在应用内切换账号，Token有效性由上传客户端校验和刷新。
      if (uris.length === 0) return;
      this.ebookTransferBusy = true;
      this.ebookTransferProgress = 0;
      const api = new EbookImportApi(this.ActiveServiceUrl(), this.authManager);
      for (let index = 0; index < uris.length; index++) {
        const filename = bookNameFromUri(uris[index]);
        this.bookStatus = `正在上传 ${index + 1}/${uris.length} · ${filename}`;
        await api.upload(context, uris[index], filename, (uploadedBytes: number, totalBytes: number) => {
          const fileFraction = totalBytes > 0 ? uploadedBytes / totalBytes : 0;
          this.ebookTransferProgress = Math.min(1, (index + fileFraction) / uris.length);
        });
      }
      this.ebookTransferProgress = 1;
      this.bookStatus = `已上传 ${uris.length} 本图书`;
      this.remoteBookPath = '/';
      await this.LoadRemoteBooks();
    } catch (error) {
      const message = error instanceof Error ? error.message : '远程图书上传失败';
      console.error(`Remote ebook upload failed: ${message}`);
      this.bookStatus = message;
    } finally {
      this.ebookTransferBusy = false;
    }
  }

  /** 从详情页把本地图书上传或把书源图书交给后端完整下载。 */
  private async UploadDetailBookToRemote(): Promise<void> {
    const book = this.detailBook;
    if (book === undefined || this.ebookTransferBusy) return;
    if (!this.authenticated) {
      this.OpenLogin();
      return;
    }
    this.ebookTransferBusy = true;
    this.ebookTransferProgress = 0;
    this.ebookTransferStatus = book.origin === 'source' ? '正在创建后台下载任务…' : '正在上传本地图书…';
    try {
      const api = new EbookImportApi(this.ActiveServiceUrl(), this.authManager);
      if (book.origin === 'source') {
        const task = await api.startSource({ sourceUrl: book.sourceId, bookUrl: book.resourceUri,
          title: book.name, author: book.author });
        this.ebookTransferBusy = false;
        this.ebookTransferStatus = `${task.message}，可离开本页继续使用`;
        this.PollSourceBookImport(task.taskId, book.name);
        return;
      }
      if (book.origin !== 'local') return;
      const context = this.ApplicationContext();
      if (context === undefined) throw new Error('无法获取应用上下文');
      const filename = bookNameFromUri(book.resourceUri);
      await api.upload(context, book.resourceUri, filename, (uploadedBytes: number, totalBytes: number) => {
        this.ebookTransferProgress = totalBytes > 0 ? Math.min(1, uploadedBytes / totalBytes) : 0;
        this.ebookTransferStatus = totalBytes > 0 ? `正在上传 · ${Math.round(this.ebookTransferProgress * 100)}%` :
          '正在上传本地图书…';
      });
      this.ebookTransferProgress = 1;
      this.ebookTransferStatus = '已上传到远程书库';
      await this.LoadRemoteBooks();
    } catch (error) {
      this.ebookTransferStatus = error instanceof Error ? error.message : '上传到远程书库失败';
    } finally {
      this.ebookTransferBusy = false;
    }
  }

  /** 轮询后台书源下载状态，任务本身不依赖详情页生命周期。 */
  private async PollSourceBookImport(taskId: string, bookName: string): Promise<void> {
    const api = new EbookImportApi(this.ActiveServiceUrl(), this.authManager);
    while (this.authenticated) {
      await new Promise<void>((resolve: () => void) => setTimeout(resolve, 1500));
      try {
        const task: EbookImportTask = await api.findSource(taskId);
        this.ebookTransferStatus = `《${bookName}》${task.message}`;
        if (task.status === 'COMPLETED') {
          await this.LoadRemoteBooks();
          return;
        }
        if (task.status === 'FAILED') return;
      } catch (error) {
        this.ebookTransferStatus = error instanceof Error ? error.message : '读取后台下载状态失败';
        return;
      }
    }
  }

  private async LoadRemoteBooks(): Promise<void> {
    if (!this.authenticated || this.serviceUrl.length === 0) return;
    if (this.remoteBookSources.length === 0) await this.LoadRemoteBookSources();
    if (this.selectedRemoteBookSourceId.length > 0) await this.LoadRemoteBookDirectory();
  }

  private async LoadRemoteBookSources(cancellation?: DownloadCancellationToken): Promise<void> {
    if (!this.authenticated || this.serviceUrl.length === 0) return;
    this.activeRemoteBookSourceCancellation?.cancel();
    const sourceCancellation = cancellation ?? new DownloadCancellationToken();
    this.activeRemoteBookSourceCancellation = sourceCancellation;
    const revision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.remoteBookLoading = true;
    this.bookStatus = '正在连接远程书库…';
    try {
      const sources = (await new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager)
        .listBookSources(sourceCancellation)).filter((source: RemoteMediaSource) =>
          source.localDirectoryType === 'EBOOK');
      if (!this.AccountOperationCurrent(revision, owner) || sourceCancellation.isCancelled()) return;
      this.remoteBookSources = sources;
      const current = sources.find((source: RemoteMediaSource) => source.id === this.selectedRemoteBookSourceId);
      const preferred = current ?? sources.find((source: RemoteMediaSource) => source.isDefault) ?? sources[0];
      this.selectedRemoteBookSourceId = preferred?.id ?? '';
      if (preferred === undefined) {
        this.remoteBookItems = [];
        this.bookStatus = '尚未配置MyTools电子书目录或远程书库';
      } else {
        this.bookStatus = '';
      }
    } catch (error) {
      if (!this.AccountOperationCurrent(revision, owner) || sourceCancellation.isCancelled()) return;
      this.bookStatus = error instanceof Error ? error.message : '加载电子书来源失败';
    } finally {
      if (this.activeRemoteBookSourceCancellation === sourceCancellation) {
        this.activeRemoteBookSourceCancellation = undefined;
      }
      if (this.AccountOperationCurrent(revision, owner)) this.remoteBookLoading = false;
    }
  }

  private async LoadRemoteBookDirectory(): Promise<void> {
    if (this.selectedRemoteBookSourceId.length === 0) return;
    this.activeRemoteBookDirectoryCancellation?.cancel();
    this.activeRemoteBookCoverCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeRemoteBookDirectoryCancellation = cancellation;
    const directoryRevision = ++this.remoteBookDirectoryRevision;
    const sourceId = this.selectedRemoteBookSourceId;
    const path = this.remoteBookPath;
    const pageSize = 40;
    this.remoteBookPage = 1;
    this.remoteBookTotal = 0;
    this.remoteBookLoadedCount = 0;
    this.remoteBookHasMore = false;
    this.remoteBookLoadingMore = false;
    this.remoteBookLoading = true;
    const revision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.bookStatus = '正在加载远程书库…';
    try {
      const api = new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager);
      const source = this.remoteBookSources.find((value: RemoteMediaSource) => value.id === sourceId);
      if (source === undefined) throw new Error('远程电子书目录不可用');
      const localRootPath = source.localDirectoryType === 'EBOOK' ? source.url : '';
      const directory = source.localDirectoryType === 'EBOOK'
        ? await api.listBookDirectory(sourceId, path, cancellation, localRootPath, 1, pageSize,
          this.remoteBookSearchText.trim(), this.hideAdultContent)
        : await api.listDirectory(sourceId, path, cancellation, localRootPath, 1, pageSize);
      if (!this.AccountOperationCurrent(revision, owner) ||
        directoryRevision !== this.remoteBookDirectoryRevision || sourceId !== this.selectedRemoteBookSourceId ||
        path !== this.remoteBookPath) return;
      this.remoteBookItems = directory.items.filter((item: RemoteMediaItem) =>
        item.kind === 'directory' || resolveBookFormat(item.name) !== 'unknown');
      this.remoteBookTotal = directory.total ?? this.remoteBookItems.length;
      this.remoteBookLoadedCount = directory.items.length;
      this.remoteBookHasMore = (sourceId.startsWith('local:') || sourceId === 'ebook-library') &&
        directory.total !== undefined &&
        this.remoteBookLoadedCount < directory.total;
      this.bookStatus = this.remoteBookItems.length === 0 ? '当前目录没有支持的电子书' : '';
      this.LoadRemoteBookCovers(this.remoteBookItems, sourceId, directoryRevision);
      this.LoadRemoteBookProgress(this.remoteBookItems, source, directoryRevision);
    } catch (error) {
      if (!this.AccountOperationCurrent(revision, owner) ||
        directoryRevision !== this.remoteBookDirectoryRevision) return;
      if (cancellation.isCancelled()) return;
      this.bookStatus = error instanceof Error ? error.message : '加载远程书库失败';
    } finally {
      if (this.activeRemoteBookDirectoryCancellation === cancellation) {
        this.activeRemoteBookDirectoryCancellation = undefined;
      }
      if (this.AccountOperationCurrent(revision, owner) && directoryRevision === this.remoteBookDirectoryRevision) {
        this.remoteBookLoading = false;
      }
    }
  }

  private async LoadMoreRemoteBookDirectory(): Promise<void> {
    if (!this.remoteBookHasMore || this.remoteBookLoading || this.remoteBookLoadingMore ||
      this.selectedRemoteBookSourceId.length === 0 ||
      !this.selectedRemoteBookSourceId.startsWith('local:') && this.selectedRemoteBookSourceId !== 'ebook-library') {
      return;
    }
    const source = this.remoteBookSources.find((value: RemoteMediaSource) =>
      value.id === this.selectedRemoteBookSourceId);
    if (source?.localDirectoryType !== 'EBOOK') return;
    const cancellation = new DownloadCancellationToken();
    this.activeRemoteBookDirectoryCancellation?.cancel();
    this.activeRemoteBookDirectoryCancellation = cancellation;
    const directoryRevision = this.remoteBookDirectoryRevision;
    const sourceId = this.selectedRemoteBookSourceId;
    const path = this.remoteBookPath;
    const nextPage = this.remoteBookPage + 1;
    const pageSize = 40;
    const revision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.remoteBookLoadingMore = true;
    try {
      const directory = await new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager)
        .listBookDirectory(sourceId, path, cancellation, source.url, nextPage, pageSize,
          this.remoteBookSearchText.trim(), this.hideAdultContent);
      if (!this.AccountOperationCurrent(revision, owner) || cancellation.isCancelled() ||
        directoryRevision !== this.remoteBookDirectoryRevision || sourceId !== this.selectedRemoteBookSourceId ||
        path !== this.remoteBookPath) return;
      const merged = new Map<string, RemoteMediaItem>();
      this.remoteBookItems.forEach((item: RemoteMediaItem) => merged.set(item.path, item));
      directory.items.filter((item: RemoteMediaItem) => item.kind === 'directory' ||
        resolveBookFormat(item.name) !== 'unknown')
        .forEach((item: RemoteMediaItem) => merged.set(item.path, item));
      this.remoteBookItems = Array.from(merged.values());
      this.remoteBookPage = nextPage;
      this.remoteBookTotal = directory.total ?? this.remoteBookTotal;
      this.remoteBookLoadedCount += directory.items.length;
      this.remoteBookHasMore = directory.total !== undefined ? this.remoteBookLoadedCount < directory.total :
        directory.items.length >= pageSize;
      this.LoadRemoteBookCovers(this.remoteBookItems, sourceId, directoryRevision);
      this.LoadRemoteBookProgress(this.remoteBookItems, source, directoryRevision);
    } catch (error) {
      if (!this.AccountOperationCurrent(revision, owner) || cancellation.isCancelled()) return;
      this.bookStatus = error instanceof Error ? error.message : '加载更多远程图书失败';
    } finally {
      if (this.activeRemoteBookDirectoryCancellation === cancellation) {
        this.activeRemoteBookDirectoryCancellation = undefined;
      }
      if (this.AccountOperationCurrent(revision, owner) && directoryRevision === this.remoteBookDirectoryRevision) {
        this.remoteBookLoadingMore = false;
      }
    }
  }

  /** 使共享远程数据源的三个目录页面同时放弃旧来源请求。 */
  private InvalidateRemoteDirectoryLoads(): void {
    this.activeMediaDirectoryCancellation?.cancel();
    this.activeMediaLoadMoreCancellation?.cancel();
    this.activeBrowseThumbnailCancellation?.cancel();
    this.activeRemoteBookDirectoryCancellation?.cancel();
    this.activeRemoteBookCoverCancellation?.cancel();
    this.activeToolDirectoryCancellation?.cancel();
    this.mediaDirectoryRevision++;
    this.remoteBookDirectoryRevision++;
    this.toolDirectoryRevision++;
    this.mediaDirectoryLoading = false;
    this.remoteBookLoading = false;
    this.toolLoading = false;
  }

  private async OpenRemoteBookDetail(item: RemoteMediaItem): Promise<void> {
    const source = this.remoteBookSources.find((value: RemoteMediaSource) =>
      value.id === this.selectedRemoteBookSourceId);
    if (source === undefined) {
      this.bookStatus = '远程数据源不可用';
      return;
    }
    try {
      const candidate = this.remoteBookImportPolicy.candidate(item, source);
      const generation = this.readerProgressSyncGeneration;
      const accountScope = this.localAccountScope;
      const digest = await this.digestTool.digestText(candidate.identityMaterial, 'SHA256');
      if (!this.authenticated || generation !== this.readerProgressSyncGeneration ||
        accountScope.length === 0 || accountScope !== this.localAccountScope ||
        this.remoteBookSources.find((value: RemoteMediaSource) => value.id === candidate.sourceId) === undefined) {
        return;
      }
      let coverUrl = '';
      let remoteCoverUrl = '';
      if (item.bookCoverAvailable === true && item.id !== undefined && source.id.startsWith('local:')) {
        const context = this.ApplicationContext();
        const publicBaseUrl = this.authManager.publicBusinessBaseUrl();
        const directoryId = source.id.substring(6);
        remoteCoverUrl = `${publicBaseUrl}/api/ebooks/${item.id}/cover?directoryId=${directoryId}` +
          `&excludeAdult=${this.hideAdultContent ? 'true' : 'false'}`;
        if (context !== undefined) {
          const target = this.RemoteBookCoverCachePath(context, item);
          try {
            await new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager)
              .downloadEbookCover(item.id, directoryId, target, this.hideAdultContent);
            coverUrl = `file://${target}`;
            this.SetRemoteBookCover(item.id, coverUrl);
          } catch (_) {
            coverUrl = '';
          }
        }
      }
      const book: Book = {
        id: `remote:sha256:${digest}`,
        name: item.bookTitle !== undefined && item.bookTitle.length > 0 ? item.bookTitle : candidate.name,
        author: item.bookAuthor !== undefined && item.bookAuthor.length > 0 ? item.bookAuthor : candidate.author,
        coverUrl, remoteCoverUrl, origin: 'remote', format: candidate.format, resourceUri: candidate.resourceUri,
        sourceId: candidate.sourceId, cacheVersion: candidate.cacheVersion, tags: candidate.tags,
        progress: 0, updatedAt: Date.now(), revision: 0
      };
      await this.RestoreRemoteBookProgress(book, generation, accountScope);
      if (generation !== this.readerProgressSyncGeneration || accountScope !== this.localAccountScope) return;
      this.OpenBookDetail(book);
      this.detailSourceName = this.RemoteBookSourceDisplayName(source);
      this.detailRemoteTags = candidate.tags;
      this.detailRemoteSize = candidate.size;
      this.detailRemoteModified = candidate.lastModified;
      this.detailIntro = item.bookDescription ?? '';
      this.detailChapterCount = item.bookChapterCount ?? 0;
    } catch (error) {
      this.bookStatus = error instanceof Error ? error.message : '远程图书详情打开失败';
    }
  }

  /** 远程目录首次出现图书时按稳定哈希拉取服务端进度，避免必须先加入书架才能跨设备续读。 */
  private async RestoreRemoteBookProgress(book: Book, generation: number, accountScope: string): Promise<void> {
    const local = this.readingProgress.find((value: ReadingProgress) => value.bookId === book.id);
    if (local !== undefined) {
      book.progress = local.deleted === true ? 0 : local.percentage;
      this.RememberRemoteBookProgress(book, local.deleted === true ? '' : local.chapterTitle, local.updatedAt);
      return;
    }
    // 服务端阅读状态从属于书架，未加入书架的远程条目不发起无效进度查询。
    if (!this.books.some((value: Book) => value.id === book.id)) return;
    try {
      const progress = await new ReaderProgressApi(this.ActiveServiceUrl(), this.authManager).find(book.id);
      if (progress === undefined || !this.authenticated || generation !== this.readerProgressSyncGeneration ||
        accountScope !== this.localAccountScope) return;
      this.ReplaceReadingProgress(progress);
      book.progress = progress.deleted === true ? 0 : progress.percentage;
      this.RememberRemoteBookProgress(book, progress.deleted === true ? '' : progress.chapterTitle,
        progress.updatedAt);
      if (this.detailBook?.id === book.id) {
        this.detailBook = this.CopyBookWithProgress(this.detailBook, book.progress);
        this.detailLastChapter = progress.deleted === true ? '' : progress.chapterTitle;
      }
    } catch (_) {
      // 单本进度预取失败不阻断远程图书打开，定时同步会在网络恢复后继续合并。
    }
  }

  /** 为当前远程书目页预取稳定身份和阅读进度，使跨设备进度无需先打开详情即可展示。 */
  private async LoadRemoteBookProgress(items: RemoteMediaItem[], source: RemoteMediaSource,
    directoryRevision: number): Promise<void> {
    const generation = this.readerProgressSyncGeneration;
    const accountScope = this.localAccountScope;
    const candidates = items.filter((item: RemoteMediaItem) => item.kind !== 'directory').slice(0, 80);
    for (const item of candidates) {
      if (directoryRevision !== this.remoteBookDirectoryRevision || source.id !== this.selectedRemoteBookSourceId ||
        generation !== this.readerProgressSyncGeneration || accountScope !== this.localAccountScope) return;
      try {
        const candidate = this.remoteBookImportPolicy.candidate(item, source);
        const digest = await this.digestTool.digestText(candidate.identityMaterial, 'SHA256');
        const book: Book = {
          id: `remote:sha256:${digest}`,
          name: item.bookTitle !== undefined && item.bookTitle.length > 0 ? item.bookTitle : candidate.name,
          author: item.bookAuthor !== undefined && item.bookAuthor.length > 0 ? item.bookAuthor : candidate.author,
          coverUrl: '', remoteCoverUrl: '', origin: 'remote', format: candidate.format,
          resourceUri: candidate.resourceUri, sourceId: candidate.sourceId, cacheVersion: candidate.cacheVersion,
          tags: candidate.tags, progress: 0, updatedAt: Date.now(), revision: 0
        };
        await this.RestoreRemoteBookProgress(book, generation, accountScope);
      } catch (_) {
        // 单本身份或进度读取失败不影响书库列表，其余书籍继续加载。
      }
    }
  }

  private async ToggleOpdsCredentialPanel(): Promise<void> {
    const credentialRevision = ++this.opdsCredentialRevision;
    this.opdsCredentialVisible = !this.opdsCredentialVisible;
    this.opdsCredentialBusy = false;
    if (!this.opdsCredentialVisible || this.opdsUrl.trim().length === 0) return;
    const requestedUrl = this.opdsUrl.trim();
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    try {
      const configured = await this.opdsCredentialStore.hasCredential(requestedUrl);
      if (!this.OpdsCredentialOperationCurrent(accountRevision, owner, credentialRevision, requestedUrl)) return;
      this.opdsCredentialStatus = configured ?
        '该HTTPS origin已配置安全凭据；保存新值将原子替换旧凭据' : '当前目录未配置安全凭据';
    } catch (error) {
      if (!this.OpdsCredentialOperationCurrent(accountRevision, owner, credentialRevision, requestedUrl)) return;
      this.opdsCredentialStatus = error instanceof Error ? error.message : '无法读取OPDS凭据状态';
    }
  }

  private async SaveOpdsCredential(): Promise<void> {
    if (this.opdsUrl.trim().length === 0) {
      this.opdsCredentialStatus = '请先填写OPDS目录地址';
      return;
    }
    const credentialRevision = ++this.opdsCredentialRevision;
    this.activeOpdsCatalogCancellation?.cancel();
    const requestedUrl = this.opdsUrl.trim();
    const username = this.opdsUsername;
    const password = this.opdsPassword;
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.opdsCredentialBusy = true;
    try {
      await this.opdsCredentialStore.save(requestedUrl, username, password);
      if (!this.OpdsCredentialOperationCurrent(accountRevision, owner, credentialRevision, requestedUrl)) return;
      this.opdsPassword = '';
      this.opdsCredentialStatus = '凭据已保存到系统安全存储，仅向同源OPDS请求发送';
      this.opdsCredentialBusy = false;
      await this.LoadOpdsCatalog(requestedUrl);
    } catch (error) {
      if (!this.OpdsCredentialOperationCurrent(accountRevision, owner, credentialRevision, requestedUrl)) return;
      this.opdsPassword = '';
      this.opdsCredentialStatus = error instanceof Error ? error.message : 'OPDS凭据保存失败';
      this.opdsCredentialBusy = false;
    }
  }

  private async ClearOpdsCredential(): Promise<void> {
    const credentialRevision = ++this.opdsCredentialRevision;
    this.activeOpdsCatalogCancellation?.cancel();
    const requestedUrl = this.opdsUrl.trim();
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.opdsCredentialBusy = true;
    try {
      await this.opdsCredentialStore.clear();
      if (!this.OpdsCredentialOperationCurrent(accountRevision, owner, credentialRevision, requestedUrl)) return;
      this.opdsUsername = '';
      this.opdsPassword = '';
      this.opdsCredentialStatus = 'OPDS凭据已清除';
      this.opdsCredentialBusy = false;
    } catch (error) {
      if (!this.OpdsCredentialOperationCurrent(accountRevision, owner, credentialRevision, requestedUrl)) return;
      this.opdsCredentialStatus = error instanceof Error ? error.message : 'OPDS凭据清除失败';
      this.opdsCredentialBusy = false;
    }
  }

  private OpdsCredentialOperationCurrent(accountRevision: number, owner: string, credentialRevision: number,
    requestedUrl: string): boolean {
    return this.AccountOperationCurrent(accountRevision, owner) &&
      credentialRevision === this.opdsCredentialRevision && requestedUrl === this.opdsUrl.trim() &&
      this.opdsCredentialVisible;
  }

  private async LoadOpdsCatalog(url: string): Promise<void> {
    this.activeOpdsCatalogCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeOpdsCatalogCancellation = cancellation;
    const navigationRevision = ++this.opdsNavigationRevision;
    const requestedUrl = url.trim();
    this.opdsLoading = true;
    const revision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.bookStatus = '正在加载OPDS目录…';
    try {
      const authorization = await this.opdsCredentialStore.authorizationFor(requestedUrl);
      if (!this.OpdsOperationCurrent(revision, owner, navigationRevision)) return;
      const catalog: OpdsCatalog = await this.opdsProvider.loadCatalog(requestedUrl, authorization, cancellation);
      if (!this.OpdsOperationCurrent(revision, owner, navigationRevision)) return;
      const books = await this.CacheOpdsCovers(catalog.books, revision, owner, navigationRevision, cancellation);
      if (!this.OpdsOperationCurrent(revision, owner, navigationRevision)) return;
      this.opdsUrl = requestedUrl;
      this.opdsTitle = catalog.title.length > 0 ? catalog.title : 'OPDS书库';
      this.opdsBooks = books;
      this.opdsNavigation = catalog.navigation;
      this.settingsPreferences?.putSync(this.OpdsCatalogPreferenceKey(), this.opdsUrl);
      this.settingsPreferences?.flush();
      this.opdsCredentialStatus = authorization === undefined ? '' : '已使用同源安全凭据';
      this.bookStatus = catalog.books.length === 0 && catalog.navigation.length === 0 ? '当前OPDS目录为空' : '';
    } catch (error) {
      if (!this.OpdsOperationCurrent(revision, owner, navigationRevision)) return;
      if (cancellation.isCancelled()) return;
      this.bookStatus = error instanceof Error ? error.message : '加载OPDS目录失败';
    } finally {
      if (this.activeOpdsCatalogCancellation === cancellation) this.activeOpdsCatalogCancellation = undefined;
    }
    if (this.OpdsOperationCurrent(revision, owner, navigationRevision)) this.opdsLoading = false;
  }

  private OpdsOperationCurrent(revision: number, owner: string, navigationRevision: number): boolean {
    return this.AccountOperationCurrent(revision, owner) && navigationRevision === this.opdsNavigationRevision;
  }

  private OpenOpdsBookDetail(entry: OpdsBookEntry): void {
    const book: Book = {
      id: `opds:${entry.id}`,
      name: entry.title,
      author: entry.author,
      coverUrl: entry.coverUrl,
      remoteCoverUrl: entry.remoteCoverUrl,
      origin: 'source',
      format: entry.format,
      resourceUri: entry.acquisitionUrl,
      sourceId: this.opdsUrl,
      progress: 0,
      updatedAt: Date.now(),
      revision: 0
    };
    this.OpenBookDetail(book, entry.summary);
  }

  private async CacheSourceSearchCovers(results: BookSourceSearchResult[],
    cancellation: DownloadCancellationToken): Promise<BookSourceSearchResult[]> {
    const context = this.ApplicationContext();
    if (context === undefined) return results;
    for (let offset = 0; offset < results.length; offset += 4) {
      if (cancellation.isCancelled()) return [];
      const batch = results.slice(offset, offset + 4);
      await Promise.all(batch.map(async (result: BookSourceSearchResult) => {
        if (!/^https?:\/\//i.test(result.coverUrl)) return;
        result.remoteCoverUrl = result.coverUrl;
        try {
          const headers = await this.bookSourceCredentialStore.headersFor(result.sourceUrl, result.remoteCoverUrl);
          if (cancellation.isCancelled()) return;
          result.coverUrl = await this.remoteCoverCache.cache(context, result.remoteCoverUrl, headers, cancellation);
        } catch (_) {
          if (cancellation.isCancelled()) return;
          result.coverUrl = '';
        }
      }));
    }
    return results.slice();
  }

  private async CacheOpdsCovers(entries: OpdsBookEntry[], revision: number, owner: string,
    navigationRevision: number, cancellation: DownloadCancellationToken): Promise<OpdsBookEntry[]> {
    const context = this.ApplicationContext();
    if (context === undefined) return entries;
    for (let offset = 0; offset < entries.length; offset += 4) {
      if (cancellation.isCancelled() || !this.OpdsOperationCurrent(revision, owner, navigationRevision)) return [];
      const batch = entries.slice(offset, offset + 4);
      await Promise.all(batch.map(async (entry: OpdsBookEntry) => {
        if (!/^https?:\/\//i.test(entry.coverUrl)) return;
        entry.remoteCoverUrl = entry.coverUrl;
        try {
          const authorization = await this.opdsCredentialStore.authorizationFor(entry.remoteCoverUrl);
          const headers: Record<string, string> = {};
          if (authorization !== undefined) headers.Authorization = authorization;
          entry.coverUrl = await this.remoteCoverCache.cache(context, entry.remoteCoverUrl, headers, cancellation);
        } catch (_) {
          if (cancellation.isCancelled()) return;
          entry.coverUrl = '';
        }
      }));
    }
    return entries.slice();
  }

  private async CacheShelfCovers(context: common.UIAbilityContext): Promise<void> {
    let changed = false;
    for (let offset = 0; offset < this.books.length; offset += 4) {
      const batch = this.books.slice(offset, offset + 4);
      await Promise.all(batch.map(async (book: Book) => {
        if (this.CachedCoverExists(book.coverUrl)) return;
        const sourceUrl = book.remoteCoverUrl ?? (/^https?:\/\//i.test(book.coverUrl) ? book.coverUrl : '');
        if (sourceUrl.length === 0) {
          if (book.coverUrl.startsWith('file://')) {
            book.coverUrl = '';
            changed = true;
          }
          return;
        }
        book.remoteCoverUrl = sourceUrl;
        try {
          let headers: Record<string, string> = {};
          if (book.id.startsWith('opds:')) {
            const authorization = await this.opdsCredentialStore.authorizationFor(sourceUrl);
            if (authorization !== undefined) headers.Authorization = authorization;
          } else if (book.origin === 'source') {
            headers = await this.bookSourceCredentialStore.headersFor(book.sourceId, sourceUrl);
          } else if (book.origin === 'remote' && book.sourceId.startsWith('local:')) {
            headers.Authorization = `Bearer ${await this.authManager.validAccessToken()}`;
          }
          book.coverUrl = await this.remoteCoverCache.cache(context, sourceUrl, headers);
        } catch (_) {
          book.coverUrl = '';
        }
        changed = true;
      }));
    }
    if (changed) {
      this.books = this.books.slice();
      this.PersistReaderState();
    }
  }

  private CachedCoverExists(uri: string): boolean {
    if (!uri.startsWith('file://')) return false;
    try {
      return fs.accessSync(uri.substring('file://'.length));
    } catch (_) {
      return false;
    }
  }

  private ApplicationContext(): common.UIAbilityContext | undefined {
    const context = this.getUIContext().getHostContext();
    return context === undefined ? undefined : context as common.UIAbilityContext;
  }

  private SetRemoteBookCover(id: string, uri: string): void {
    const entries = this.remoteBookCoverEntries.filter((entry: RemoteBookCoverEntry) => entry.id !== id);
    entries.push({ id, uri });
    this.remoteBookCoverEntries = entries;
    const item = this.remoteBookItems.find((candidate: RemoteMediaItem) => candidate.id === id);
    if (item !== undefined) {
      item.bookCoverUri = uri;
      // 替换数组引用，确保ForEach重绘刚完成封面下载的书目行。
      this.remoteBookItems = this.remoteBookItems.slice();
    }
  }

  private RemoteBookCoverCachePath(context: common.UIAbilityContext, item: RemoteMediaItem): string {
    const itemId = item.id ?? 'unknown';
    const version = item.bookFileHash?.substring(0, 16) ?? 'current';
    return `${context.cacheDir}/ebook-cover-${itemId}-${version}.image`;
  }

  /** 渐进下载当前MyTools书目页的真实封面，列表无需等待全部封面即可先展示。 */
  private async LoadRemoteBookCovers(items: RemoteMediaItem[], sourceId: string,
    directoryRevision: number): Promise<void> {
    this.activeRemoteBookCoverCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeRemoteBookCoverCancellation = cancellation;
    const context = this.ApplicationContext();
    if (context === undefined || !sourceId.startsWith('local:')) {
      this.activeRemoteBookCoverCancellation = undefined;
      return;
    }
    const directoryId = sourceId.substring(6);
    const api = new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager);
    const candidates = items.filter((item: RemoteMediaItem) => item.kind !== 'directory' &&
      item.bookCoverAvailable === true && item.id !== undefined);
    for (const item of candidates) {
      if (cancellation.isCancelled() || directoryRevision !== this.remoteBookDirectoryRevision ||
        sourceId !== this.selectedRemoteBookSourceId) break;
      const itemId = item.id ?? '';
      const target = this.RemoteBookCoverCachePath(context, item);
      try {
        fs.accessSync(target);
        this.SetRemoteBookCover(itemId, `file://${target}`);
        continue;
      } catch (_) {
        // 缓存不存在时再读取认证封面接口。
      }
      try {
        await api.downloadEbookCover(itemId, directoryId, target, this.hideAdultContent, cancellation);
        if (!cancellation.isCancelled() && directoryRevision === this.remoteBookDirectoryRevision &&
          sourceId === this.selectedRemoteBookSourceId) this.SetRemoteBookCover(itemId, `file://${target}`);
      } catch (_) {
        // 单本封面失败时保留文档占位，不阻塞其余书目。
      }
    }
    if (this.activeRemoteBookCoverCancellation === cancellation) {
      this.activeRemoteBookCoverCancellation = undefined;
    }
  }

  private FilteredRemoteBookItems(): RemoteMediaItem[] {
    const keyword = this.remoteBookSearchText.trim().toLowerCase();
    const filtered = keyword.length === 0 ? this.remoteBookItems.slice() :
      this.remoteBookItems.filter((item: RemoteMediaItem) => item.name.toLowerCase().includes(keyword) ||
        this.RemoteBookDisplayTitle(item).toLowerCase().includes(keyword) ||
        this.RemoteBookDisplayTags(item).some((tag: string) => tag.toLowerCase().includes(keyword)));
    return filtered.sort((left: RemoteMediaItem, right: RemoteMediaItem): number => {
      if (left.kind === 'directory' && right.kind !== 'directory') return -1;
      if (left.kind !== 'directory' && right.kind === 'directory') return 1;
      const readDifference = this.RemoteBookLastReadAt(right) - this.RemoteBookLastReadAt(left);
      if (readDifference !== 0) return readDifference;
      return `${right.lastModified}`.localeCompare(`${left.lastModified}`);
    });
  }

  private RemoteBookLastReadAt(item: RemoteMediaItem): number {
    const entry = this.remoteBookProgressEntries.find((value: RemoteBookProgressEntry) =>
      value.sourceId === this.selectedRemoteBookSourceId && value.path === item.path);
    return entry !== undefined && entry.percentage > 0 ? entry.updatedAt : 0;
  }

  private RemoteBookDisplayTitle(item: RemoteMediaItem): string {
    const candidate = item.bookTitle !== undefined && item.bookTitle.length > 0 ? item.bookTitle : item.name;
    return this.remoteBookDisplayMetadataPolicy.metadata(candidate, item.tags).title;
  }

  private RemoteBookMetaLine(item: RemoteMediaItem): string {
    const segments: string[] = [];
    if (item.bookAuthor !== undefined && item.bookAuthor.length > 0) segments.push(item.bookAuthor);
    segments.push(resolveBookFormat(item.name).toUpperCase());
    if (item.bookChapterCount !== undefined && item.bookChapterCount > 0) {
      segments.push(`${item.bookChapterCount}章`);
    }
    segments.push(this.FormatMediaSize(item.size));
    return segments.join(' · ');
  }

  private OnRemoteBookSearchChanged(value: string): void {
    this.remoteBookSearchText = value;
    this.ClearRemoteBookSearchTimer();
    if (!this.selectedRemoteBookSourceId.startsWith('local:')) return;
    this.remoteBookSearchTimerId = setTimeout((): void => {
      this.remoteBookSearchTimerId = -1;
      this.LoadRemoteBookDirectory();
    }, 400);
  }

  private ClearRemoteBookSearchTimer(): void {
    if (this.remoteBookSearchTimerId < 0) return;
    clearTimeout(this.remoteBookSearchTimerId);
    this.remoteBookSearchTimerId = -1;
  }

  private RemoteBookDisplayTags(item: RemoteMediaItem): string[] {
    return this.remoteBookDisplayMetadataPolicy.metadata(item.name, item.tags).tags;
  }

  /** 点击远程图书标签后返回远程书库并应用相同标签筛选。 */
  private FilterRemoteBooksByTag(tag: string): void {
    this.bookModeIndex = 1;
    this.remoteBookSearchText = tag;
    if (this.bookDetailVisible) this.CloseBookDetail();
    if (this.selectedRemoteBookSourceId.startsWith('local:')) this.LoadRemoteBookDirectory();
  }

  private RemoteBookProgress(item: RemoteMediaItem): number {
    const entry = this.remoteBookProgressEntries.find((value: RemoteBookProgressEntry) =>
      value.sourceId === this.selectedRemoteBookSourceId && value.path === item.path);
    if (entry !== undefined) return entry.percentage;
    const shelf = this.books.find((book: Book) => book.origin === 'remote' &&
      book.sourceId === this.selectedRemoteBookSourceId && book.resourceUri === item.path);
    return shelf?.progress ?? 0;
  }

  private RememberRemoteBookProgress(book: Book, chapterTitle: string = '', updatedAt: number = Date.now()): void {
    if (book.origin !== 'remote') return;
    const next: RemoteBookProgressEntry = {
      sourceId: book.sourceId,
      path: book.resourceUri,
      bookId: book.id,
      percentage: Math.max(0, Math.min(100, book.progress)),
      chapterTitle: chapterTitle,
      updatedAt: updatedAt
    };
    this.remoteBookProgressEntries = this.remoteBookProgressEntries
      .filter((value: RemoteBookProgressEntry) => value.sourceId !== next.sourceId || value.path !== next.path)
      .concat(next);
  }

  private RemoteBookSourceDisplayName(source: RemoteMediaSource): string {
    if (source.localDirectoryType === 'EBOOK') return 'MyTools 电子书';
    return source.name.length > 0 ? source.name : source.type;
  }

  private CurrentRemoteBookSourceLabel(): string {
    const source = this.remoteBookSources.find((value: RemoteMediaSource) =>
      value.id === this.selectedRemoteBookSourceId);
    return source === undefined ? '选择来源' : this.RemoteBookSourceDisplayName(source);
  }

  private RemoteBookSourcePickerOptions(): string[] {
    const names = this.remoteBookSources.map((source: RemoteMediaSource) => this.RemoteBookSourceDisplayName(source));
    return names.length > 0 ? names : ['暂无可用来源'];
  }

  private PendingRemoteBookSourceDescription(): string {
    const source = this.remoteBookSources[this.remoteBookSourcePickerIndex];
    if (source?.localDirectoryType === 'EBOOK') return 'MyTools 电子书目录 · 使用登录会话安全读取';
    if (source !== undefined) return `${source.type} · 远程电子书存储`;
    return '当前账户没有可用的电子书来源';
  }

  private OpenRemoteBookSourceSelector(): void {
    const current = this.remoteBookSources.findIndex((source: RemoteMediaSource) =>
      source.id === this.selectedRemoteBookSourceId);
    this.remoteBookSourcePickerIndex = current >= 0 ? current : 0;
    this.remoteBookSourceSelectorVisible = true;
  }

  private ConfirmRemoteBookSourceSelector(): void {
    const source = this.remoteBookSources[this.remoteBookSourcePickerIndex];
    this.remoteBookSourceSelectorVisible = false;
    if (source !== undefined) this.SelectRemoteBookSource(source);
  }

  private SelectRemoteBookSource(source: RemoteMediaSource): void {
    this.remoteBookSourceSelectorVisible = false;
    if (this.selectedRemoteBookSourceId === source.id) return;
    this.activeRemoteBookDirectoryCancellation?.cancel();
    this.activeRemoteBookCoverCancellation?.cancel();
    this.remoteBookDirectoryRevision++;
    this.selectedRemoteBookSourceId = source.id;
    this.remoteBookPath = '/';
    this.remoteBookItems = [];
    this.remoteBookCoverEntries = [];
    this.remoteBookPage = 1;
    this.remoteBookTotal = 0;
    this.remoteBookLoadedCount = 0;
    this.remoteBookHasMore = false;
    this.remoteBookLoadingMore = false;
    this.bookStatus = '';
    this.LoadRemoteBookDirectory();
  }

  private FilteredOpdsBooks(): OpdsBookEntry[] {
    const keyword = this.remoteBookSearchText.trim().toLowerCase();
    if (keyword.length === 0) return this.opdsBooks;
    return this.opdsBooks.filter((entry: OpdsBookEntry) => entry.title.toLowerCase().includes(keyword) ||
      entry.author.toLowerCase().includes(keyword));
  }

  private MediaDirectoryNames(): string[] {
    if (this.mediaDirectoryFilterOptions.length > 0) return this.mediaDirectoryFilterOptions;
    return this.mediaGroupingPolicy.directoryNames(this.mediaItems);
  }

  private CurrentMediaSourceLabel(): string {
    const source = this.mediaSources.find((item: RemoteMediaSource) => item.id === this.selectedMediaSourceId);
    if (source === undefined) return this.mediaLoading ? '加载中' : '选择来源';
    return this.MediaSourceDisplayName(source);
  }

  private MediaSourceDisplayName(source: RemoteMediaSource): string {
    if (source.localDirectoryType === 'MULTIMEDIA') return '远程文件';
    if (source.localDirectoryType === 'LARGE_MEDIA') return '大文件';
    return source.name.length > 0 ? source.name : source.type;
  }

  private PendingMediaSourceDescription(): string {
    const rawOptions = this.MediaPickerRawOptions();
    const sourceId = this.mediaPickerPendingValue.length > 0 ? this.mediaPickerPendingValue :
      rawOptions[this.mediaPickerSelectedIndex];
    const source = this.mediaSources.find((item: RemoteMediaSource) => item.id === sourceId);
    if (source?.localDirectoryType === 'MULTIMEDIA') return 'MyTools 远程目录 · 图片、视频和音频';
    if (source?.localDirectoryType === 'LARGE_MEDIA') return 'MyTools big_media · 大图和视频';
    if (source !== undefined) return `${source.type} · 远程存储`;
    return '选择要浏览的远程来源';
  }

  private MediaKindFilterName(kind: string): string {
    if (kind === 'image') return '图片';
    if (kind === 'video') return '视频';
    if (kind === 'audio') return '音频';
    return '全部媒体';
  }

  private MediaKindFilterLabel(): string {
    return this.MediaKindFilterName(this.mediaKindFilter);
  }

  private MediaFilterLabel(mode: string): string {
    if (mode === 'directory') return this.MediaDirectoryFilterLabel();
    if (mode === 'tag') return this.MediaTagFilterLabel();
    return this.MediaKindFilterLabel();
  }

  private MediaFilterActive(mode: string): boolean {
    if (mode === 'directory') return this.mediaDirectoryGroupFilter !== 'all';
    if (mode === 'tag') return this.mediaSelectedTags.length > 0;
    return this.mediaKindFilter !== 'all';
  }

  private HasActiveMediaFilters(): boolean {
    return this.mediaKindFilter !== 'all' || this.mediaDirectoryGroupFilter !== 'all' ||
      this.mediaSelectedTags.length > 0 || this.mediaSearchText.trim().length > 0 ||
      this.mediaSearchQuery.length > 0;
  }

  private MediaStatusRetryable(): boolean {
    return /失败|错误|不可用|超时|网络|认证|连接/.test(this.mediaStatus);
  }

  private MediaThumbnailFailed(path: string): boolean {
    return this.mediaBrowseThumbnailFailedPaths.includes(path);
  }

  private MediaDirectoryFilterLabel(): string {
    return this.mediaDirectoryGroupFilter === 'all' ? '全部目录' :
      this.MediaDirectoryDisplayLabel(this.mediaDirectoryGroupFilter);
  }

  private MediaTagFilterLabel(): string {
    if (this.mediaSelectedTags.length === 0) return '全部标签';
    if (this.mediaSelectedTags.length === 1) return this.mediaSelectedTags[0];
    return `标签 ${this.mediaSelectedTags.length}`;
  }

  private MediaDirectoryDisplayLabel(name: string): string {
    return this.mediaGroupingPolicy.displayName(name);
  }

  private MediaCompactTagSummary(item: RemoteMediaItem): string {
    const tags = item.tags ?? [];
    if (tags.length === 0) return '';
    const visible = tags.slice(0, 2).map((tag: RemoteMediaTag) => `#${tag.name}`).join('  ');
    return tags.length > 2 ? `${visible}  +${tags.length - 2}` : visible;
  }

  private MediaSelectorTitle(): string {
    if (this.mediaSelectorMode === 'source') return '选择媒体来源';
    if (this.mediaSelectorMode === 'directory') return '筛选目录';
    if (this.mediaSelectorMode === 'tag') return '筛选标签';
    return '选择媒体类型';
  }

  private MediaSelectorUsesSearchList(): boolean {
    return this.mediaSelectorMode === 'tag' || this.mediaSelectorMode === 'directory';
  }

  private OpenMediaSelector(mode: string): void {
    if (!['source', 'directory', 'tag', 'kind'].includes(mode)) return;
    this.mediaSelectorMode = mode;
    if (this.MediaSelectorUsesSearchList()) {
      this.RestoreMediaSearchableSelectorOffset();
      return;
    }
    const rawOptions = this.MediaPickerRawOptions();
    const current = mode === 'source' ? this.selectedMediaSourceId :
      (mode === 'directory' ? this.mediaDirectoryGroupFilter :
        (mode === 'tag' ? (this.pendingMediaSelectedTags[0] ?? rawOptions[0] ?? '') : this.mediaKindFilter));
    const currentIndex = rawOptions.indexOf(current);
    this.mediaPickerSelectedIndex = currentIndex >= 0 ? currentIndex : 0;
    this.mediaPickerPendingValue = rawOptions[this.mediaPickerSelectedIndex] ?? '';
  }

  private MediaPickerRawOptions(): string[] {
    if (this.mediaSelectorMode === 'source') return this.mediaSources.map((source: RemoteMediaSource) => source.id);
    if (this.mediaSelectorMode === 'directory') return ['all'].concat(this.MediaDirectoryNames());
    if (this.mediaSelectorMode === 'kind') return ['all', 'image', 'video', 'audio'];
    if (this.mediaSelectorMode === 'tag') return this.MediaTagNames();
    return [];
  }

  private MediaPickerDisplayOptions(): string[] {
    if (this.mediaSelectorMode === 'source') {
      const names = this.mediaSources.map((source: RemoteMediaSource) =>
        this.MediaSourceDisplayName(source));
      return names.length > 0 ? names : ['暂无可用来源'];
    }
    if (this.mediaSelectorMode === 'directory') {
      return ['全部目录'].concat(this.MediaDirectoryNames().map((name: string) =>
        this.MediaDirectoryDisplayLabel(name)));
    }
    if (this.mediaSelectorMode === 'kind') {
      return ['all', 'image', 'video', 'audio'].map((kind: string) => this.MediaKindFilterName(kind));
    }
    if (this.mediaSelectorMode === 'tag') {
      const names = this.MediaTagNames();
      return names.length > 0 ? names : ['暂无标签'];
    }
    return ['暂无选项'];
  }

  private CurrentPendingMediaPickerTag(): string {
    return this.MediaPickerRawOptions()[this.mediaPickerSelectedIndex] ?? '';
  }

  private CurrentPendingMediaPickerTagSelected(): boolean {
    const tag = this.CurrentPendingMediaPickerTag();
    return tag.length > 0 && this.pendingMediaSelectedTags.includes(tag);
  }

  private ToggleCurrentPendingMediaPickerTag(): void {
    const tag = this.CurrentPendingMediaPickerTag();
    if (tag.length > 0) this.TogglePendingMediaTag(tag);
  }

  private PendingMediaTagSummary(): string {
    if (this.pendingMediaSelectedTags.length === 0) return '尚未选择标签';
    return `已选：${this.pendingMediaSelectedTags.map((name: string) => `#${name}`).join('  ')}`;
  }

  private ConfirmMediaSelector(): void {
    if (this.mediaSelectorMode === 'tag') {
      this.mediaSelectedTags = this.pendingMediaSelectedTags.slice();
      this.mediaRequireAllTags = this.pendingMediaRequireAllTags;
      this.CloseMediaSelector();
      this.ApplyMediaServerFilters();
      return;
    }
    const values = this.MediaPickerRawOptions();
    const value = this.mediaPickerPendingValue.length > 0 ? this.mediaPickerPendingValue :
      values[this.mediaPickerSelectedIndex];
    const mode = this.mediaSelectorMode;
    this.CloseMediaSelector();
    if (value === undefined) return;
    if (mode === 'source') {
      const source = this.mediaSources.find((item: RemoteMediaSource) => item.id === value);
      if (source !== undefined) this.SelectMediaSource(source);
    } else if (mode === 'directory') {
      if (this.mediaDirectoryGroupFilter === value) return;
      this.mediaDirectoryGroupFilter = value;
      this.ApplyMediaServerFilters();
    } else if (mode === 'kind') {
      if (this.mediaKindFilter === value) return;
      this.mediaKindFilter = value;
      this.ApplyMediaServerFilters();
    }
  }

  private ApplyMediaServerFilters(): void {
    if (!this.CurrentMediaSourceIsLocal()) return;
    this.InvalidateRemoteDirectoryLoads();
    this.mediaItems = [];
    this.mediaDirectoryItems = [];
    this.SetMediaBrowseThumbnailEntries([]);
    this.mediaBrowseThumbnailFailedPaths = [];
    this.mediaLocalPage = 1;
    this.mediaLocalTotal = 0;
    this.mediaLocalHasMore = false;
    this.mediaStatus = this.mediaSearchQuery.length > 0 ? '正在搜索远程媒体…' : '正在应用筛选条件…';
    this.LoadMediaDirectory();
  }

  private ScheduleMediaSearch(value: string): void {
    this.mediaSearchText = value;
    this.CancelMediaSearchTimer();
    this.mediaSearchTimerId = setTimeout(() => {
      this.mediaSearchTimerId = -1;
      this.CommitMediaSearch();
    }, 450);
  }

  private CommitMediaSearch(): void {
    this.CancelMediaSearchTimer();
    const query = this.mediaSearchText.trim();
    if (query === this.mediaSearchQuery) return;
    this.mediaSearchQuery = query;
    this.ApplyMediaServerFilters();
  }

  private ClearMediaSearch(): void {
    this.CancelMediaSearchTimer();
    const hadCommittedQuery = this.mediaSearchQuery.length > 0;
    this.mediaSearchText = '';
    this.mediaSearchQuery = '';
    if (hadCommittedQuery) this.ApplyMediaServerFilters();
  }

  private ResetMediaFilters(): void {
    this.CancelMediaSearchTimer();
    this.mediaSearchText = '';
    this.mediaSearchQuery = '';
    this.mediaKindFilter = 'all';
    this.mediaDirectoryGroupFilter = 'all';
    this.mediaSelectedTags = [];
    this.mediaRequireAllTags = false;
    this.ResetMediaSearchableSelectorContexts();
    if (this.CurrentMediaSourceIsLocal()) this.ApplyMediaServerFilters();
  }

  private CancelMediaSearchTimer(): void {
    if (this.mediaSearchTimerId >= 0) clearTimeout(this.mediaSearchTimerId);
    this.mediaSearchTimerId = -1;
  }

  private MediaServerSubdirectory(): string {
    if (this.mediaDirectoryGroupFilter === 'all') return '';
    return this.mediaDirectoryGroupFilter === '根目录' ? '.' : this.mediaDirectoryGroupFilter;
  }

  private MediaServerFileType(kind: string = this.mediaKindFilter): string {
    return kind === 'all' ? 'MEDIA' : kind.toUpperCase();
  }

  private SelectMediaSource(source: RemoteMediaSource): void {
    this.CloseMediaSelector();
    if (this.selectedMediaSourceId === source.id) return;
    this.InvalidateRemoteDirectoryLoads();
    this.selectedMediaSourceId = source.id;
    this.mediaPath = '/';
    this.mediaItems = [];
    this.mediaDirectoryItems = [];
    this.mediaLocalPage = 1;
    this.mediaLocalTotal = 0;
    this.mediaLocalHasMore = false;
    this.SetMediaBrowseThumbnailEntries([]);
    this.mediaBrowseThumbnailFailedPaths = [];
    this.mediaThumbnailEntries = [];
    this.mediaDirectoryFilterOptions = [];
    this.mediaTagFilterOptions = [];
    this.mediaDirectoryGroupFilter = 'all';
    this.mediaSelectedTags = [];
    this.ResetMediaSearchableSelectorContexts();
    this.mediaStatus = '正在加载远程目录…';
    this.LoadMediaDirectory();
  }

  private CurrentMediaSourceIsLocal(): boolean {
    return this.mediaSources.some((source: RemoteMediaSource) =>
      source.id === this.selectedMediaSourceId && source.type === 'MYTOOLS_LOCAL');
  }

  private CloseMediaSelector(): void {
    this.CaptureMediaSearchableSelectorOffset();
    this.mediaSelectorMode = '';
    this.mediaPickerPendingValue = '';
    // 滚轮手势可能被底层页面接收，关闭弹层后立即恢复媒体页的稳定起始位置。
    setTimeout((): void => {
      this.tabScrollers[3].scrollTo({ xOffset: 0, yOffset: 0, animation: false });
    }, 0);
  }

  private HasMoreLocalMedia(): boolean {
    return this.CurrentMediaSourceIsLocal() && this.mediaLocalHasMore;
  }

  private MediaDirectoryGroups(): RemoteMediaDirectoryGroup[] {
    return this.mediaGroupingPolicy.groups(this.FilteredMediaItems());
  }

  private MediaItemRenderKey(item: RemoteMediaItem): string {
    const tags = (item.tags ?? []).map((tag: RemoteMediaTag) => tag.name).join(',');
    return `${item.path}|${item.name}|${item.lastModified}|${item.size}|${tags}`;
  }

  private MediaGroupRenderKey(group: RemoteMediaDirectoryGroup): string {
    return `${group.directoryName}|${group.items.map((item: RemoteMediaItem) =>
      this.MediaItemRenderKey(item)).join(';')}`;
  }

  private MediaViewerSequence(): RemoteMediaItem[] {
    if (this.currentIndex === 3 && this.mediaCatalogItems.length > 0) {
      return this.mediaCatalogItems.map((item: MediaCatalogItem): RemoteMediaItem => this.CatalogRemoteItem(item));
    }
    const sequence: RemoteMediaItem[] = [];
    this.MediaDirectoryGroups().forEach((group: RemoteMediaDirectoryGroup) => {
      group.items.forEach((item: RemoteMediaItem) => sequence.push(item));
    });
    return sequence;
  }

  private VisualMediaViewerSequence(): RemoteMediaItem[] {
    return this.MediaViewerSequence().filter((item: RemoteMediaItem) =>
      item.kind === 'image' || item.kind === 'video');
  }

  private FilteredMediaItems(): RemoteMediaItem[] {
    const keyword = this.mediaSearchText.trim().toLowerCase();
    return this.mediaItems.filter((item: RemoteMediaItem) =>
      (!this.mediaFavoritesOnly || item.kind === 'directory' ||
        this.IsMediaFavorite(this.selectedMediaSourceId, item.path)) &&
      (this.mediaDirectoryGroupFilter === 'all' || item.kind === 'directory' ||
        (item.directoryName ?? '根目录') === this.mediaDirectoryGroupFilter) &&
      (this.mediaKindFilter === 'all' || item.kind === 'directory' || item.kind === this.mediaKindFilter) &&
      (this.mediaSelectedTags.length === 0 || item.kind === 'directory' ||
        (this.mediaRequireAllTags ? this.mediaSelectedTags.every((name: string) =>
          (item.tags ?? []).some((tag: RemoteMediaTag) => tag.name === name)) :
          this.mediaSelectedTags.some((name: string) =>
            (item.tags ?? []).some((tag: RemoteMediaTag) => tag.name === name)))) &&
      (keyword.length === 0 || item.name.toLowerCase().includes(keyword) ||
        (item.directoryName ?? '').toLowerCase().includes(keyword) ||
        (item.tags ?? []).some((tag: RemoteMediaTag) => tag.name.toLowerCase().includes(keyword))));
  }

  private MediaTagNames(): string[] {
    if (this.mediaTagFilterOptions.length > 0) return this.mediaTagFilterOptions;
    const names = new Set<string>();
    this.mediaItems.forEach((item: RemoteMediaItem) =>
      (item.tags ?? []).forEach((tag: RemoteMediaTag) => names.add(tag.name)));
    return Array.from(names).slice(0, 50);
  }

  private FilteredMediaTagNames(): string[] {
    const query = this.mediaTagSelectorQuery.trim().toLowerCase();
    const names = this.MediaTagNames();
    if (query.length === 0) return names;
    return names.filter((name: string) => name.toLowerCase().includes(query));
  }

  private SortedMediaDirectoryNames(): string[] {
    return this.MediaDirectoryNames().slice().sort((left: string, right: string) => {
      const leftDate = this.MediaDirectoryDateSortKey(left);
      const rightDate = this.MediaDirectoryDateSortKey(right);
      if (leftDate.length > 0 && rightDate.length > 0) {
        const dateOrder = rightDate.localeCompare(leftDate);
        return dateOrder !== 0 ? dateOrder : right.localeCompare(left);
      }
      if (leftDate.length > 0) return -1;
      if (rightDate.length > 0) return 1;
      return right.localeCompare(left);
    });
  }

  private MediaDirectoryDateSortKey(name: string): string {
    return name.split('/').filter((segment: string) => /^\d{6,8}$/.test(segment)).join('');
  }

  private FilteredMediaDirectoryNames(): string[] {
    const query = this.mediaDirectorySelectorQuery.trim().toLowerCase();
    const names = this.SortedMediaDirectoryNames();
    if (query.length === 0) return names;
    return names.filter((name: string) => name.toLowerCase().includes(query) ||
      this.MediaDirectoryDisplayLabel(name).toLowerCase().includes(query));
  }

  private MediaSearchableSelectorQuery(): string {
    return this.mediaSelectorMode === 'tag' ? this.mediaTagSelectorQuery : this.mediaDirectorySelectorQuery;
  }

  private FilteredMediaSelectorOptions(): string[] {
    return this.mediaSelectorMode === 'tag' ? this.FilteredMediaTagNames() : this.FilteredMediaDirectoryNames();
  }

  private MediaSearchableSelectorScroller(): Scroller {
    return this.mediaSelectorMode === 'tag' ? this.mediaTagSelectorScroller : this.mediaDirectorySelectorScroller;
  }

  private MediaSearchableSelectorItemLabel(name: string): string {
    return this.mediaSelectorMode === 'tag' ? `#${name}` : this.MediaDirectoryDisplayLabel(name);
  }

  private MediaSearchableSelectorItemSelected(name: string): boolean {
    return this.mediaSelectorMode === 'tag' ? this.mediaSelectedTags.includes(name) :
      this.mediaDirectoryGroupFilter === name;
  }

  private MediaSearchableSelectorHasValue(): boolean {
    return this.MediaSearchableSelectorQuery().length > 0 ||
      (this.mediaSelectorMode === 'tag' ? this.mediaSelectedTags.length > 0 :
        this.mediaDirectoryGroupFilter !== 'all');
  }

  private UpdateMediaSearchableSelectorQuery(value: string): void {
    if (this.mediaSelectorMode === 'tag') {
      this.mediaTagSelectorQuery = value;
      this.mediaTagSelectorOffset = 0;
    } else {
      this.mediaDirectorySelectorQuery = value;
      this.mediaDirectorySelectorOffset = 0;
    }
    // 输入条件变化后回到结果顶部，只有关闭并重新打开时才恢复该筛选器的已有位置。
    setTimeout((): void => {
      this.MediaSearchableSelectorScroller().scrollTo({ xOffset: 0, yOffset: 0, animation: false });
    }, 0);
  }

  private CaptureMediaSearchableSelectorOffset(): void {
    if (!this.MediaSelectorUsesSearchList()) return;
    const offset = this.MediaSearchableSelectorScroller().currentOffset().yOffset;
    if (this.mediaSelectorMode === 'tag') this.mediaTagSelectorOffset = offset;
    else this.mediaDirectorySelectorOffset = offset;
  }

  private RestoreMediaSearchableSelectorOffset(): void {
    const mode = this.mediaSelectorMode;
    const scroller = this.MediaSearchableSelectorScroller();
    const offset = mode === 'tag' ? this.mediaTagSelectorOffset : this.mediaDirectorySelectorOffset;
    setTimeout((): void => {
      if (this.mediaSelectorMode === mode) {
        scroller.scrollTo({ xOffset: 0, yOffset: offset, animation: false });
      }
    }, 50);
  }

  private SelectMediaSearchableOption(name: string): void {
    const mode = this.mediaSelectorMode;
    if (mode === 'tag') {
      this.mediaSelectedTags = [name];
      this.mediaRequireAllTags = false;
    } else if (mode === 'directory') {
      this.mediaDirectoryGroupFilter = name;
    }
    this.CloseMediaSelector();
    this.ApplyMediaServerFilters();
  }

  private ClearMediaSearchableSelector(): void {
    const mode = this.mediaSelectorMode;
    if (mode === 'tag') {
      this.mediaSelectedTags = [];
      this.mediaRequireAllTags = false;
      this.mediaTagSelectorQuery = '';
      this.mediaTagSelectorOffset = 0;
      this.mediaTagSelectorScroller.scrollTo({ xOffset: 0, yOffset: 0, animation: false });
    } else if (mode === 'directory') {
      this.mediaDirectoryGroupFilter = 'all';
      this.mediaDirectorySelectorQuery = '';
      this.mediaDirectorySelectorOffset = 0;
      this.mediaDirectorySelectorScroller.scrollTo({ xOffset: 0, yOffset: 0, animation: false });
    }
    this.CloseMediaSelector();
    if (mode === 'tag') this.mediaTagSelectorOffset = 0;
    else if (mode === 'directory') this.mediaDirectorySelectorOffset = 0;
    this.ApplyMediaServerFilters();
  }

  private ResetMediaSearchableSelectorContexts(): void {
    this.mediaTagSelectorQuery = '';
    this.mediaDirectorySelectorQuery = '';
    this.mediaTagSelectorOffset = 0;
    this.mediaDirectorySelectorOffset = 0;
    this.mediaTagSelectorScroller.scrollTo({ xOffset: 0, yOffset: 0, animation: false });
    this.mediaDirectorySelectorScroller.scrollTo({ xOffset: 0, yOffset: 0, animation: false });
  }

  private ToggleMediaTag(name: string): void {
    this.mediaSelectedTags = this.mediaSelectedTags.includes(name) ?
      this.mediaSelectedTags.filter((item: string) => item !== name) : this.mediaSelectedTags.concat(name);
  }

  private TogglePendingMediaTag(name: string): void {
    this.pendingMediaSelectedTags = this.pendingMediaSelectedTags.includes(name) ?
      this.pendingMediaSelectedTags.filter((item: string) => item !== name) :
      this.pendingMediaSelectedTags.concat(name);
  }

  private OpenMediaActionSheet(item: RemoteMediaItem): void {
    if (item.kind === 'directory') return;
    this.mediaActionPath = item.path;
    this.mediaActionMode = 'menu';
    this.mediaActionInput = '';
    this.mediaActionError = '';
  }

  private OpenMediaCatalogActionSheet(item: MediaCatalogItem): void {
    this.OpenMediaActionSheet(this.CatalogRemoteItem(item));
  }

  private TapMediaCatalogItem(item: MediaCatalogItem, videoDetail: boolean): void {
    this.OpenMediaCatalogItem(item, videoDetail);
  }

  private MediaActionItem(): RemoteMediaItem | undefined {
    const item = this.mediaItems.find((value: RemoteMediaItem) => value.path === this.mediaActionPath);
    if (item !== undefined) return item;
    const catalogItem = this.mediaCatalogItems.find((value: MediaCatalogItem) =>
      `/media/${value.itemId}` === this.mediaActionPath || `/local/${value.itemId}` === this.mediaActionPath);
    return catalogItem === undefined ? undefined : this.CatalogRemoteItem(catalogItem);
  }

  private BeginMediaAction(mode: string, input: string): void {
    this.mediaActionMode = mode;
    this.mediaActionInput = input;
    this.mediaActionError = '';
  }

  private CloseMediaActionSheet(): void {
    if (this.mediaActionBusy) return;
    this.mediaActionPath = '';
    this.mediaActionMode = '';
    this.mediaActionInput = '';
    this.mediaActionError = '';
  }

  private MediaActionPlaceholder(): string {
    if (this.mediaActionMode === 'rename') return '输入新文件名';
    if (this.mediaActionMode === 'move') return '输入目标目录';
    if (this.mediaActionMode === 'tags') return '多个标签使用逗号分隔';
    return '';
  }

  private async ExecuteMediaAction(item: RemoteMediaItem): Promise<void> {
    if (this.mediaActionBusy) return;
    this.mediaActionBusy = true;
    this.mediaActionError = '';
    try {
      const api = new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager);
      const accountId = this.IsCatalogMediaPath(item.path) ? this.CatalogLocalMediaSource().id :
        this.selectedMediaSourceId;
      if (this.mediaActionMode === 'rename') {
        await api.renameEntry(accountId, item.path, this.mediaActionInput.trim());
      } else if (this.mediaActionMode === 'move') {
        await api.moveEntry(accountId, item.path, this.mediaActionInput.trim());
      } else if (this.mediaActionMode === 'tags') {
        const tags = this.mediaActionInput.split(',').map((value: string) => value.trim())
          .filter((value: string, index: number, all: string[]) => value.length > 0 && all.indexOf(value) === index);
        await api.replaceTags(accountId, item.path, tags);
      } else if (this.mediaActionMode === 'delete-confirm') {
        await api.deleteEntry(accountId, item.path, false);
        // 服务端确认删除后立即移除本地投影，不让慢网络下的权威重载遮蔽操作结果。
        this.RemoveMediaMutationProjection(item);
      }
      this.mediaActionBusy = false;
      this.CloseMediaActionSheet();
      await this.RefreshMediaAfterMutation(item);
    } catch (error) {
      this.mediaActionError = error instanceof Error ? error.message : '媒体操作失败';
      this.mediaStatus = this.mediaActionError;
    } finally {
      this.mediaActionBusy = false;
    }
  }

  /** 媒体变更成功后按当前图片、视频目录或远程目录状态重新读取权威列表。 */
  private async RefreshMediaAfterMutation(item: RemoteMediaItem): Promise<void> {
    if (!this.IsCatalogMediaPath(item.path)) {
      await this.LoadMediaDirectory();
      return;
    }
    const directoryId = this.selectedVideoDirectoryId;
    const directoryName = this.selectedVideoDirectoryName;
    await this.LoadMediaCatalog();
    if (this.mediaCatalogMode === 'gallery' || directoryId.length === 0) return;
    const directory = this.mediaVideoDirectories.find((value: MediaVideoDirectory) =>
      value.directoryId === directoryId);
    if (directory === undefined) {
      this.LeaveMediaVideoDirectory();
      return;
    }
    const cancellation = new DownloadCancellationToken();
    this.activeMediaCatalogCancellation?.cancel();
    this.activeMediaCatalogCancellation = cancellation;
    try {
      const api = new MediaCatalogApi(this.ActiveServiceUrl(), this.authManager);
      const items = this.mediaCatalogMode === 'audio' ?
        await api.audioDirectoryItems(directory.name, this.hideAdultContent, cancellation) :
        await api.videoDirectoryItems(directoryId, this.hideAdultContent, cancellation);
      if (cancellation.isCancelled() || this.selectedVideoDirectoryId !== directoryId) return;
      this.selectedVideoDirectoryName = directory.name.length > 0 ? directory.name : directoryName;
      this.mediaVideoDirectoryItems = items;
      this.PrepareMediaCatalogThumbnails(items);
    } finally {
      if (this.activeMediaCatalogCancellation === cancellation) this.activeMediaCatalogCancellation = undefined;
    }
  }

  /** 删除成功后同步移除图片、视频目录和缩略图中的当前投影。 */
  private RemoveMediaMutationProjection(item: RemoteMediaItem): void {
    const itemId = this.CatalogMediaItemId(item.path, item.id ?? '');
    const catalogItem = this.mediaCatalogItems.find((value: MediaCatalogItem) => value.itemId === itemId) ??
      this.mediaVideoDirectoryItems.find((value: MediaCatalogItem) => value.itemId === itemId);
    const directoryId = catalogItem?.directoryId ?? this.selectedVideoDirectoryId;
    this.mediaCatalogItems = this.mediaCatalogItems.filter((value: MediaCatalogItem) => value.itemId !== itemId);
    this.mediaVideoDirectoryItems = this.mediaVideoDirectoryItems.filter(
      (value: MediaCatalogItem) => value.itemId !== itemId);
    this.mediaVideoDirectories = this.mediaVideoDirectories.map((directory: MediaVideoDirectory) => {
      const affected = directory.directoryId === directoryId;
      const updated: MediaVideoDirectory = {
        directoryId: directory.directoryId,
        name: directory.name,
        fileCount: Math.max(0, directory.fileCount - (affected ? 1 : 0)),
        totalSizeBytes: Math.max(0, directory.totalSizeBytes - (affected ? item.size : 0)),
        latestModifiedAt: directory.latestModifiedAt,
        topItems: directory.topItems.filter((value: MediaCatalogItem) => value.itemId !== itemId)
      };
      return updated;
    }).filter((directory: MediaVideoDirectory) => directory.fileCount > 0);
    this.mediaItems = this.mediaItems.filter((value: RemoteMediaItem) => value.path !== item.path);
    this.mediaDirectoryItems = this.mediaDirectoryItems.filter((value: RemoteMediaItem) => value.path !== item.path);
    this.SetMediaBrowseThumbnailEntries(this.mediaBrowseThumbnailEntries.filter(
      (entry: MediaThumbnailEntry) => entry.path !== item.path));
    this.mediaCatalogTotal = Math.max(0, this.mediaCatalogTotal - 1);
  }

  private MediaKindCount(kind: string): number {
    return this.mediaItems.filter((item: RemoteMediaItem) => item.kind === kind).length;
  }

  private RestoreMediaFavorites(value: string): void {
    try {
      const parsed = JSON.parse(value) as MediaFavorite[];
      if (!Array.isArray(parsed)) return;
      this.mediaFavorites = parsed.filter((item: MediaFavorite) =>
        typeof item.owner === 'string' && item.owner.length > 0 && item.owner.length <= 256 &&
        typeof item.accountId === 'string' && item.accountId.length > 0 && item.accountId.length <= 256 &&
        typeof item.name === 'string' && item.name.length > 0 && item.name.length <= 1024 &&
        typeof item.path === 'string' && item.path.startsWith('/') && item.path.length <= 4096 &&
        item.path !== '/' && !/[\u0000-\u001F\u007F\\]/.test(item.path) &&
        (item.kind === 'image' || item.kind === 'audio' || item.kind === 'video') &&
        Number.isFinite(item.size) && item.size >= 0 && Number.isFinite(item.addedAt) && item.addedAt > 0)
        .slice(0, 1000);
    } catch (_) {
      this.mediaFavorites = [];
    }
  }

  private IsMediaFavorite(accountId: string, path: string): boolean {
    const owner = this.localAccountScope;
    if (owner.length === 0 || accountId.length === 0) return false;
    return this.mediaFavorites.some((item: MediaFavorite) => item.owner === owner &&
      item.accountId === accountId && item.path === path);
  }

  private AddMediaFavorite(item: RemoteMediaItem): void {
    const owner = this.localAccountScope;
    if (owner.length === 0 || this.selectedMediaSourceId.length === 0 || item.kind === 'directory') {
      throw new Error('当前媒体无法收藏');
    }
    if (this.IsMediaFavorite(this.selectedMediaSourceId, item.path)) return;
    const favorite: MediaFavorite = {
      owner: owner,
      accountId: this.selectedMediaSourceId,
      name: item.name.substring(0, 1024),
      path: item.path,
      kind: item.kind,
      contentType: item.contentType.substring(0, 256),
      size: Math.max(0, item.size),
      lastModified: item.lastModified.substring(0, 256),
      addedAt: Date.now()
    };
    this.mediaFavorites = [favorite].concat(this.mediaFavorites).slice(0, 1000);
    this.PersistMediaFavorites();
  }

  private ToggleMediaFavorite(item: RemoteMediaItem): void {
    if (this.IsMediaFavorite(this.selectedMediaSourceId, item.path)) {
      const owner = this.localAccountScope;
      this.mediaFavorites = this.mediaFavorites.filter((favorite: MediaFavorite) => favorite.owner !== owner ||
        favorite.accountId !== this.selectedMediaSourceId || favorite.path !== item.path);
      this.PersistMediaFavorites();
      this.mediaStatus = `已取消收藏 · ${item.name}`;
      return;
    }
    try {
      this.AddMediaFavorite(item);
      this.mediaStatus = `已收藏 · ${item.name}`;
    } catch (error) {
      this.mediaStatus = error instanceof Error ? error.message : '收藏失败';
    }
  }

  private PersistMediaFavorites(): void {
    try {
      this.settingsPreferences?.putSync('media_favorites', JSON.stringify(this.mediaFavorites));
      this.settingsPreferences?.flush();
    } catch (_) {
      this.mediaStatus = '收藏保存失败';
    }
  }

  private ParentBookPath(): string {
    const normalized = this.remoteBookPath.endsWith('/') && this.remoteBookPath.length > 1 ?
      this.remoteBookPath.substring(0, this.remoteBookPath.length - 1) : this.remoteBookPath;
    const lastSlash = normalized.lastIndexOf('/');
    return lastSlash <= 0 ? '/' : normalized.substring(0, lastSlash);
  }

  private async LoadMediaSources(): Promise<void> {
    if (!this.authenticated || this.mediaLoading || this.serviceUrl.length === 0) {
      return;
    }
    this.mediaLoading = true;
    this.activeMediaSourceCancellation?.cancel();
    const sourceCancellation = new DownloadCancellationToken();
    this.activeMediaSourceCancellation = sourceCancellation;
    const revision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    this.mediaStatus = '正在加载远程数据源…';
    try {
      const api = new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager);
      const sources = await api.listSources(sourceCancellation);
      if (!this.AccountOperationCurrent(revision, owner)) return;
      this.mediaSources = sources;
      if (this.mediaSources.length === 0) {
        this.mediaStatus = '尚未配置可用的远程数据源';
      } else {
        const defaultSource = this.mediaSources.find((source: RemoteMediaSource) => source.isDefault);
        const preferredSource = this.mediaSources.find((source: RemoteMediaSource) =>
          source.id === this.pendingMediaSourceId);
        this.selectedMediaSourceId = preferredSource?.id ?? defaultSource?.id ?? this.mediaSources[0].id;
        this.mediaPath = preferredSource !== undefined && this.pendingMediaPath.startsWith('/') &&
          this.pendingMediaPath.length <= 4096 ? this.pendingMediaPath : '/';
        let directoryRevision = ++this.mediaDirectoryRevision;
        let sourceId = this.selectedMediaSourceId;
        let path = this.mediaPath;
        this.mediaDirectoryLoading = true;
        this.mediaLoadingMore = false;
        this.activeMediaDirectoryCancellation?.cancel();
        this.activeMediaLoadMoreCancellation?.cancel();
        const cancellation = new DownloadCancellationToken();
        this.activeMediaDirectoryCancellation = cancellation;
        this.mediaStatus = '';
        try {
          try {
            await this.LoadMediaDirectoryWithApi(api, revision, owner, directoryRevision, sourceId, path, cancellation);
            if (!this.AccountOperationCurrent(revision, owner) || directoryRevision !== this.mediaDirectoryRevision) return;
          } catch (error) {
            if (directoryRevision !== this.mediaDirectoryRevision) return;
            if (this.mediaPath === '/') {
              throw new Error(error instanceof Error ? error.message : '加载远程目录失败');
            }
            this.mediaPath = '/';
            directoryRevision = ++this.mediaDirectoryRevision;
            sourceId = this.selectedMediaSourceId;
            path = this.mediaPath;
            if (cancellation.isCancelled()) return;
            await this.LoadMediaDirectoryWithApi(api, revision, owner, directoryRevision, sourceId, path, cancellation);
            if (!this.AccountOperationCurrent(revision, owner) || directoryRevision !== this.mediaDirectoryRevision) return;
            this.mediaStatus = '原远程目录不可用，已返回数据源根目录';
          }
          this.pendingMediaSourceId = '';
          this.pendingMediaPath = '/';
        } finally {
          if (this.activeMediaDirectoryCancellation === cancellation) {
            this.activeMediaDirectoryCancellation = undefined;
          }
        }
      }
    } catch (error) {
      if (!this.AccountOperationCurrent(revision, owner)) return;
      if (sourceCancellation.isCancelled()) return;
      const detail = error instanceof Error ? `${error.message}\n${error.stack ?? ''}` : '未知错误';
      console.error(`LoadMediaSources failed: ${detail}`);
      this.mediaStatus = error instanceof Error ? error.message : '加载远程数据源失败';
    } finally {
      if (this.activeMediaSourceCancellation === sourceCancellation) {
        this.activeMediaSourceCancellation = undefined;
      }
      if (this.AccountOperationCurrent(revision, owner)) {
        this.mediaLoading = false;
        this.mediaDirectoryLoading = false;
      }
    }
  }

  private async LoadMediaDirectory(): Promise<void> {
    if (this.selectedMediaSourceId.length === 0) {
      return;
    }
    this.activeMediaDirectoryCancellation?.cancel();
    this.activeMediaLoadMoreCancellation?.cancel();
    this.activeBrowseThumbnailCancellation?.cancel();
    this.mediaLoadingMore = false;
    this.mediaLocalPage = 1;
    this.mediaLocalTotal = 0;
    this.mediaLocalHasMore = false;
    const cancellation = new DownloadCancellationToken();
    this.activeMediaDirectoryCancellation = cancellation;
    const directoryRevision = ++this.mediaDirectoryRevision;
    const sourceId = this.selectedMediaSourceId;
    const path = this.mediaPath;
    const mediaKindFilter = this.mediaKindFilter;
    this.mediaDirectoryLoading = true;
    const revision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    try {
      await this.LoadMediaDirectoryWithApi(new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager), revision, owner,
        directoryRevision, sourceId, path, cancellation, mediaKindFilter);
      if (!this.AccountOperationCurrent(revision, owner) || directoryRevision !== this.mediaDirectoryRevision) return;
    } catch (error) {
      if (!this.AccountOperationCurrent(revision, owner) || directoryRevision !== this.mediaDirectoryRevision) return;
      if (cancellation.isCancelled()) return;
      // 当前数据源读取失败时移除旧数据，避免把上一数据源的卡片误呈现为可操作内容。
      this.mediaDirectoryItems = [];
      this.mediaItems = [];
      this.mediaLocalPage = 1;
      this.mediaLocalTotal = 0;
      this.mediaLocalHasMore = false;
      this.SetMediaBrowseThumbnailEntries([]);
      this.mediaBrowseThumbnailFailedPaths = [];
      this.mediaStatus = error instanceof Error ? error.message : '加载远程目录失败';
    } finally {
      if (this.activeMediaDirectoryCancellation === cancellation) this.activeMediaDirectoryCancellation = undefined;
      if (this.AccountOperationCurrent(revision, owner) && directoryRevision === this.mediaDirectoryRevision) {
        this.mediaDirectoryLoading = false;
      }
    }
  }

  private async LoadMediaDirectoryWithApi(api: RemoteMediaApi, revision: number, owner: string,
    directoryRevision: number = this.mediaDirectoryRevision, sourceId: string = this.selectedMediaSourceId,
    path: string = this.mediaPath, cancellation?: DownloadCancellationToken,
    mediaKindFilter: string = this.mediaKindFilter): Promise<void> {
    this.mediaStatus = '正在加载远程目录…';
    const source = this.mediaSources.find((item: RemoteMediaSource) => item.id === sourceId);
    const localSource = source?.type === 'MYTOOLS_LOCAL';
    const localRootPath = localSource ? source?.url ?? '' : '';
    const directory = await api.listDirectory(sourceId, path, cancellation, localRootPath, 1, 24,
      localSource ? this.MediaServerSubdirectory() : '', localSource ? this.MediaServerFileType(mediaKindFilter) : '',
      localSource ? this.mediaSelectedTags : [], localSource && this.mediaRequireAllTags,
      localSource ? this.mediaSearchQuery : '');
    if (!this.AccountOperationCurrent(revision, owner) || directoryRevision !== this.mediaDirectoryRevision ||
      sourceId !== this.selectedMediaSourceId || path !== this.mediaPath ||
      mediaKindFilter !== this.mediaKindFilter) return;
    this.mediaLocalPage = 1;
    const pageSize = directory.pageSize ?? 24;
    const serverTotal = directory.total ?? 0;
    const result: RemoteMediaLoadResult = this.mediaLoadResultPolicy.resolve(
      directory.items, serverTotal, pageSize, mediaKindFilter);
    this.mediaDirectoryItems = result.directoryItems;
    this.mediaItems = result.mediaItems;
    // 始终保留服务端真实总数，避免当前页数量被误展示为媒体库总数。
    this.mediaLocalTotal = localSource ? result.total : this.mediaItems.length;
    this.mediaLocalHasMore = localSource && result.hasMore;
    if (this.mediaDirectoryGroupFilter !== 'all' &&
      !this.MediaDirectoryNames().includes(this.mediaDirectoryGroupFilter)) this.mediaDirectoryGroupFilter = 'all';
    this.mediaStatus = this.mediaItems.length === 0 ?
      (this.mediaSearchQuery.length > 0 ? '没有找到匹配的远程媒体' : '当前目录没有可查看的远程媒体') : '';
    if (!localSource) {
      this.mediaDirectoryFilterOptions = [];
      this.mediaTagFilterOptions = [];
    }
    // 媒体元数据先进入页面，缩略图与完整筛选项在后台并行补齐，避免首屏等待所有图片。
    this.LoadMediaBrowseThumbnails(api, sourceId, directoryRevision, this.mediaItems, true);
    if (localSource) this.LoadMediaFilterOptions(api, revision, owner, directoryRevision, sourceId, cancellation);
  }

  private async LoadMediaFilterOptions(api: RemoteMediaApi, revision: number, owner: string,
    directoryRevision: number, sourceId: string, cancellation?: DownloadCancellationToken): Promise<void> {
    try {
      const options: RemoteMediaFilterOptions = await api.listFilterOptions(sourceId, cancellation);
      if (!this.AccountOperationCurrent(revision, owner) || directoryRevision !== this.mediaDirectoryRevision ||
        sourceId !== this.selectedMediaSourceId) return;
      this.mediaDirectoryFilterOptions = options.directories;
      this.mediaTagFilterOptions = options.tags;
    } catch (_) {
      // 完整筛选项读取失败时继续使用当前已加载媒体推导出的目录和标签。
    }
  }

  private async LoadMoreMediaDirectory(): Promise<void> {
    if (!this.authenticated || this.mediaDirectoryLoading || this.mediaLoadingMore || this.mediaViewerVisible ||
      !this.mediaLocalHasMore) return;
    const source = this.mediaSources.find((item: RemoteMediaSource) => item.id === this.selectedMediaSourceId);
    if (source?.type !== 'MYTOOLS_LOCAL') return;
    const nextPage = this.mediaLocalPage + 1;
    const directoryRevision = this.mediaDirectoryRevision;
    const accountRevision = this.accountOperationRevision;
    const owner = this.localAccountScope;
    const sourceId = source.id;
    const path = this.mediaPath;
    const mediaKindFilter = this.mediaKindFilter;
    const cancellation = new DownloadCancellationToken();
    this.activeMediaLoadMoreCancellation?.cancel();
    this.activeMediaLoadMoreCancellation = cancellation;
    this.mediaLoadingMore = true;
    try {
      const api = new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager);
      const directory = await api.listDirectory(sourceId, path, cancellation, source.url, nextPage, 24,
        this.MediaServerSubdirectory(), this.MediaServerFileType(mediaKindFilter), this.mediaSelectedTags,
        this.mediaRequireAllTags, this.mediaSearchQuery);
      if (!this.AccountOperationCurrent(accountRevision, owner) ||
        directoryRevision !== this.mediaDirectoryRevision || sourceId !== this.selectedMediaSourceId ||
        path !== this.mediaPath || mediaKindFilter !== this.mediaKindFilter || cancellation.isCancelled()) return;
      const pageSize = directory.pageSize ?? 24;
      const serverTotal = directory.total ?? 0;
      const result: RemoteMediaLoadResult = this.mediaLoadResultPolicy.resolve(
        directory.items, serverTotal, pageSize, mediaKindFilter);
      const incoming = result.mediaItems;
      const merged = new Map<string, RemoteMediaItem>();
      this.mediaDirectoryItems.forEach((item: RemoteMediaItem) => merged.set(item.path, item));
      result.directoryItems.forEach((item: RemoteMediaItem) => merged.set(item.path, item));
      this.mediaDirectoryItems = Array.from(merged.values());
      const visible = new Map<string, RemoteMediaItem>();
      this.mediaItems.forEach((item: RemoteMediaItem) => visible.set(item.path, item));
      incoming.forEach((item: RemoteMediaItem) => visible.set(item.path, item));
      this.mediaItems = Array.from(visible.values());
      this.mediaLocalPage = nextPage;
      if (result.total > 0) this.mediaLocalTotal = result.total;
      this.mediaLocalHasMore = !result.inconsistent &&
        (this.mediaDirectoryItems.length < result.total ||
          (result.total === 0 && result.directoryItems.length >= pageSize));
      this.LoadMediaBrowseThumbnails(api, sourceId, directoryRevision, incoming, false);
    } catch (error) {
      if (!this.AccountOperationCurrent(accountRevision, owner) ||
        directoryRevision !== this.mediaDirectoryRevision || cancellation.isCancelled()) return;
      this.mediaStatus = error instanceof Error ? `加载更多失败：${error.message}` : '加载更多远程媒体失败';
    } finally {
      if (this.activeMediaLoadMoreCancellation === cancellation) this.activeMediaLoadMoreCancellation = undefined;
      if (this.AccountOperationCurrent(accountRevision, owner) && directoryRevision === this.mediaDirectoryRevision) {
        this.mediaLoadingMore = false;
      }
    }
  }

  private async LoadMediaBrowseThumbnails(api: RemoteMediaApi, sourceId: string,
    directoryRevision: number, candidates: RemoteMediaItem[] = this.mediaItems,
    reset: boolean = true): Promise<void> {
    const previewItems = candidates.filter((item: RemoteMediaItem) =>
      item.kind === 'image' || item.kind === 'video').slice(0, 240);
    const existingCancellation = this.activeBrowseThumbnailCancellation;
    let cancellation: DownloadCancellationToken;
    if (reset || existingCancellation === undefined || existingCancellation.isCancelled()) {
      if (reset) this.activeBrowseThumbnailCancellation?.cancel();
      cancellation = new DownloadCancellationToken();
      this.activeBrowseThumbnailCancellation = cancellation;
    } else {
      cancellation = existingCancellation;
    }
    if (reset) {
      // 刷新同一投影时保留仍有效的本地缩略图，避免筛选或返回页面后出现整屏闪白。
      const validPaths = new Set<string>(previewItems.map((item: RemoteMediaItem) => item.path));
      const retained = this.mediaBrowseThumbnailEntries.filter((entry: MediaThumbnailEntry) =>
        validPaths.has(entry.path) && this.IsAvailableMediaPreviewUri(entry.uri));
      this.SetMediaBrowseThumbnailEntries(retained);
      this.mediaBrowseThumbnailFailedPaths = [];
    }
    const hostContext = this.getUIContext().getHostContext();
    if (hostContext === undefined) return;
    const loadedPaths = new Set<string>(this.mediaBrowseThumbnailEntries.map((entry: MediaThumbnailEntry) =>
      entry.path));
    const missingItems = previewItems.filter((item: RemoteMediaItem) => !loadedPaths.has(item.path));
    for (let index = 0; index < missingItems.length; index += 4) {
      const batch = missingItems.slice(index, index + 4);
      const results = await Promise.all(batch.map((item: RemoteMediaItem) =>
        this.LoadOneMediaBrowseThumbnail(hostContext as common.UIAbilityContext, api, sourceId,
          directoryRevision, item, cancellation)));
      if (cancellation.isCancelled() || directoryRevision !== this.mediaDirectoryRevision ||
        sourceId !== this.selectedMediaSourceId) return;
      // 每批只发布一次状态，降低大量图片完成时对整个画廊的重复布局压力。
      const resolved = results.filter((result: MediaThumbnailLoadResult) => !result.failed && result.uri.length > 0);
      if (resolved.length > 0) {
        const resolvedPaths = new Set<string>(resolved.map((result: MediaThumbnailLoadResult) => result.path));
        const newEntries: MediaThumbnailEntry[] = resolved.map((result: MediaThumbnailLoadResult) => {
          const entry: MediaThumbnailEntry = { path: result.path, uri: result.uri };
          return entry;
        });
        this.SetMediaBrowseThumbnailEntries(this.mediaBrowseThumbnailEntries.filter((entry: MediaThumbnailEntry) =>
          !resolvedPaths.has(entry.path)).concat(newEntries));
      }
      const failed = results.filter((result: MediaThumbnailLoadResult) => result.failed)
        .map((result: MediaThumbnailLoadResult) => result.path);
      if (failed.length > 0) {
        this.mediaBrowseThumbnailFailedPaths = Array.from(new Set<string>(
          this.mediaBrowseThumbnailFailedPaths.concat(failed)));
      }
    }
  }

  private async LoadOneMediaBrowseThumbnail(context: common.UIAbilityContext, api: RemoteMediaApi,
    sourceId: string, directoryRevision: number, item: RemoteMediaItem,
    cancellation: DownloadCancellationToken): Promise<MediaThumbnailLoadResult> {
    let lastMessage = '未知错误';
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const uri = await this.mediaThumbnailCache.cache(context, api, sourceId, item.path, cancellation,
          item.kind === 'image');
        if (cancellation.isCancelled() || directoryRevision !== this.mediaDirectoryRevision ||
          sourceId !== this.selectedMediaSourceId) return { path: item.path, uri: '', failed: false };
        return { path: item.path, uri: uri, failed: false };
      } catch (error) {
        if (cancellation.isCancelled()) return { path: item.path, uri: '', failed: false };
        lastMessage = error instanceof Error ? error.message : '未知错误';
      }
    }
    console.error(`LoadMediaThumbnail failed: ${item.path}: ${lastMessage}`);
    // 两次下载均失败时保留可识别的媒体类型占位，不影响其他并发缩略图。
    return { path: item.path, uri: '', failed: true };
  }

  private async RetryFailedMediaThumbnails(): Promise<void> {
    const failed = new Set<string>(this.mediaBrowseThumbnailFailedPaths);
    const candidates = this.mediaItems.filter((item: RemoteMediaItem) => failed.has(item.path));
    if (candidates.length === 0 || this.selectedMediaSourceId.length === 0) {
      this.mediaBrowseThumbnailFailedPaths = [];
      return;
    }
    this.mediaBrowseThumbnailFailedPaths = [];
    await this.LoadMediaBrowseThumbnails(new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager),
      this.selectedMediaSourceId, this.mediaDirectoryRevision, candidates, false);
  }

  private ParentMediaPath(): string {
    const normalized = this.mediaPath.endsWith('/') && this.mediaPath.length > 1 ?
      this.mediaPath.substring(0, this.mediaPath.length - 1) : this.mediaPath;
    const lastSlash = normalized.lastIndexOf('/');
    return lastSlash <= 0 ? '/' : normalized.substring(0, lastSlash);
  }

  private MediaKindLabel(item: RemoteMediaItem): string {
    if (item.kind === 'image') return '图片';
    if (item.kind === 'audio') return '音频';
    if (item.kind === 'video') return '视频';
    return '文件';
  }

  private FormatMediaSize(size: number): string {
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
    if (size < 1024 * 1024 * 1024) return `${(size / (1024 * 1024)).toFixed(1)} MB`;
    return `${(size / (1024 * 1024 * 1024)).toFixed(1)} GB`;
  }

  private FormatRemoteBookModified(value: string): string {
    const normalized = value.trim().replace('T', ' ').replace(/Z$/, '');
    return normalized.length > 16 ? normalized.substring(0, 16) : normalized;
  }

  @Builder
  private RemoteVisualMediaViewerPage() {
    Stack({ alignContent: Alignment.Center }) {
      if (this.DragAdjacentMediaPreviewUri().length > 0) {
        Image(this.DragAdjacentMediaPreviewUri()).width('100%').height('100%').objectFit(ImageFit.Contain)
          .opacity(this.MediaViewerDragProgress()).scale({ x: 0.94 + this.MediaViewerDragProgress() * 0.06,
            y: 0.94 + this.MediaViewerDragProgress() * 0.06 })
      }
      Stack({ alignContent: Alignment.Center }) {
        if (this.currentMediaKind === 'image') {
          Image(this.currentMediaUrl)
            .width('100%')
            .height('100%')
            .objectFit(ImageFit.Contain)
            .interpolation(ImageInterpolation.High)
            .scale({ x: this.imageScale, y: this.imageScale })
            .translate({ x: this.imageOffsetX, y: this.imageOffsetY })
            .onComplete(() => this.mediaPlaybackError = '')
            .onError(() => this.mediaPlaybackError = '远程图片加载失败，请检查数据源或网络')
            .parallelGesture(
              GestureGroup(GestureMode.Exclusive,
                TapGesture({ count: 2 }).onAction(() => this.ToggleRemoteImageZoom()),
                TapGesture({ count: 1 }).onAction(() => this.ToggleMediaViewerChrome()),
                PinchGesture({ fingers: 2 })
                  .onActionStart(() => this.BeginRemoteImagePinch())
                  .onActionUpdate((event: GestureEvent) => this.UpdateRemoteImagePinch(event.scale))
                  .onActionEnd(() => this.FinishRemoteImageTransform()),
                PanGesture({ fingers: 1, direction: PanDirection.All, distance: 8 })
                  .onActionStart(() => this.BeginRemoteImagePan())
                  .onActionUpdate((event: GestureEvent) => this.UpdateRemoteImagePan(event.offsetX, event.offsetY))
                  .onActionEnd((event: GestureEvent) => this.EndRemoteImagePan(event.offsetX, event.offsetY))
              )
            )
        } else {
          Video({ src: this.currentMediaUrl, controller: this.videoController,
            currentProgressRate: this.mediaPlaybackRate, previewUri: this.currentMediaPreviewUri })
            .width('100%')
            .height('100%')
            .autoPlay(true)
            .controls(true)
            .objectFit(ImageFit.Contain)
            .onPrepared((event: PreparedInfo) => {
              this.mediaPlaybackError = '';
              this.playerDurationMs = event.duration * 1000;
              this.videoStatus = '';
              this.mediaBufferState = '视频已就绪';
              if (this.currentMediaResumeMs > 0) this.SeekVideoTo(this.currentMediaResumeMs);
            })
            .onStart(() => {
              this.videoExpectedPlaying = true;
              this.lastVideoProgressAt = Date.now();
              this.videoStatus = '';
              this.mediaBufferState = '播放中';
              this.ScheduleMediaViewerChromeAutoHide();
            })
            .onPause(() => {
              this.videoExpectedPlaying = false;
              this.videoStatus = '已暂停';
              this.mediaBufferState = '已暂停';
            })
            .onSeeking(() => {
              this.lastVideoProgressAt = Date.now();
              this.videoStatus = '正在定位…';
              this.mediaBufferState = '正在定位';
            })
            .onSeeked(() => this.videoStatus = '')
            .onUpdate((event: PlaybackInfo) => {
              this.playerTimeMs = event.time * 1000;
              this.lastVideoProgressAt = Date.now();
              this.mediaSubtitleText = this.mediaSubtitleEnabled ?
                this.subtitleParser.textAt(this.mediaSubtitleCues, this.playerTimeMs) : '';
              this.UpdateMediaPlaybackProgress(this.playerTimeMs, this.playerDurationMs);
            })
            .onFinish(() => {
              this.videoExpectedPlaying = false;
              this.mediaBufferState = '播放完成';
              this.ClearCurrentMediaProgress();
            })
            .onStop(() => this.videoStatus = '已停止')
            .onError(() => {
              this.UpdateMediaPlaybackProgress(this.playerTimeMs, this.playerDurationMs);
              this.PersistMediaPlaybackProgress();
              this.videoStatus = '视频播放失败';
              this.mediaBufferState = '播放失败';
              this.mediaPlaybackError = '远程视频播放失败，请检查数据源或网络';
            })
            .onFullscreenChange((event: FullscreenInfo) => this.videoFullscreen = event.fullscreen)
        }

        if (this.currentMediaKind === 'video' && this.videoStatus === '正在连接远程视频…') {
          Column({ space: 10 }) {
            LoadingProgress().width(28).height(28).color(Color.White)
            Text('正在准备视频').fontSize(13).fontColor(Color.White)
          }.padding({ left: 22, right: 22, top: 16, bottom: 16 })
            .backgroundColor('#9911151D').borderRadius(16)
        }
      }
      .width('100%').height('100%')
      .translate({ y: this.mediaViewerDragOffsetY })
      .opacity(1 - this.MediaViewerDragProgress() * 0.24)
      .scale({ x: 1 - this.MediaViewerDragProgress() * 0.035,
        y: 1 - this.MediaViewerDragProgress() * 0.035 })
      .animation({ duration: this.mediaViewerDragging ? 0 : 180, curve: Curve.EaseOut })

      if (this.currentMediaKind === 'video' && this.mediaSubtitleText.length > 0) {
        Column() {
          Blank().layoutWeight(1)
          Text(this.mediaSubtitleText)
            .maxLines(4)
            .textAlign(TextAlign.Center)
            .fontSize(18)
            .fontColor(Color.White)
            .padding({ left: 10, right: 10, top: 6, bottom: 6 })
            .margin({ left: 18, right: 18, bottom: 86 })
            .backgroundColor('#99000000')
            .borderRadius(8)
        }.width('100%').height('100%')
      }

      if (this.mediaViewerChromeVisible) {
        Column() {
          Row() {
            this.BackButton(Color.White, () => this.CloseRemoteMedia())
            Blank().layoutWeight(1)
            Text(this.CurrentMediaSequenceLabel())
              .width(78)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .textAlign(TextAlign.Center)
              .fontSize(13)
              .fontWeight(FontWeight.Medium)
              .fontColor(Color.White)
              .padding({ left: 12, right: 12, top: 7, bottom: 7 })
              .backgroundColor('#6611151D').borderRadius(16)
            Blank().layoutWeight(1)
            if (this.currentMediaKind === 'image') {
              Button() {
                SymbolGlyph($r(this.CurrentMediaIsFavorite() ? 'sys.symbol.star_fill' : 'sys.symbol.star'))
                  .fontSize(22).fontColor([this.CurrentMediaIsFavorite() ? AppTheme.primaryEnd : Color.White])
              }.width(46).height(48).padding(0).backgroundColor(Color.Transparent)
                .accessibilityText(this.CurrentMediaIsFavorite() ? '取消收藏当前图片' : '收藏当前图片')
                .enabled(!this.mediaImageDeleting && !this.mediaImageSharing)
                .onClick(() => this.ToggleCurrentMediaFavorite())
              Button(this.mediaImageSharing ? '…' : '分享').width(52).height(48).fontSize(13)
                .fontColor(Color.White).backgroundColor(Color.Transparent)
                .accessibilityText('分享当前远程图片')
                .enabled(!this.mediaImageDeleting && !this.mediaImageSharing)
                .onClick(() => this.ShareCurrentRemoteImage())
            } else {
              Blank().width(48)
            }
          }.width('100%').height(60).padding({ left: 16, right: 16 }).backgroundColor('#52000000')
          Blank().layoutWeight(1)
          if (this.currentMediaKind === 'image') {
            Column({ space: 8 }) {
              Row({ space: 10 }) {
                Column({ space: 3 }) {
                  Text(this.CurrentMediaDisplayName()).width('100%').maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis }).fontSize(13).fontColor(Color.White)
                  if (this.CurrentMediaTagSummary().length > 0) {
                    Text(this.CurrentMediaTagSummary()).width('100%').maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis }).fontSize(10).fontColor('#C7CEDA')
                  }
                }.layoutWeight(1).alignItems(HorizontalAlign.Start)
                Text(this.FormatMediaSize(this.currentMediaSize)).maxLines(1).fontSize(11).fontColor('#C7CEDA')
              }.width('100%')
              Row({ space: 8 }) {
                Button('上一个').width(88).height(48).accessibilityDescription('查看上一个远程媒体')
                  .fontColor(Color.White).backgroundColor('#4D2F3545')
                  .onClick(() => this.ChangeRemoteMedia(-1))
                Button(`${this.imageScale.toFixed(2)}×`).layoutWeight(1).height(48).fontSize(12)
                  .fontColor(Color.White).backgroundColor('#4D2F3545')
                  .accessibilityDescription('恢复图片原始比例和位置')
                  .onClick(() => this.ResetRemoteImageTransform())
                Button('下一个').width(88).height(48).accessibilityDescription('查看下一个远程媒体')
                  .fontColor(Color.White).backgroundColor('#4D2F3545')
                  .onClick(() => this.ChangeRemoteMedia(1))
              }.width('100%')
            }.width('90%').padding({ left: 14, right: 14, top: 10, bottom: 11 })
              .margin({ bottom: 14 }).backgroundColor('#A611151D').borderRadius(18)
          } else {
            Column({ space: 8 }) {
              Slider({ value: this.playerTimeMs, min: 0, max: Math.max(1, this.playerDurationMs) })
                .width('100%').selectedColor(AppTheme.primaryEnd)
                .onChange((value: number, mode: SliderChangeMode) => {
                  if (mode === SliderChangeMode.End) this.SeekVideoTo(value);
                })
              Row() {
                Text(this.FormatDuration(this.playerTimeMs)).fontSize(11).fontColor('#D7DEEA')
                Blank()
                Text(this.FormatDuration(this.playerDurationMs)).fontSize(11).fontColor('#D7DEEA')
              }.width('100%')
              Row({ space: 8 }) {
                Button('−10秒').height(46).fontColor(Color.White).backgroundColor('#4D2F3545')
                  .onClick(() => this.SeekVideoBy(-10000))
                Button(this.videoExpectedPlaying ? '暂停' : '播放').layoutWeight(1).height(46)
                  .fontColor(Color.White).backgroundColor('#4D2F3545').onClick(() => {
                    if (this.videoExpectedPlaying) this.videoController.pause();
                    else this.videoController.start();
                  })
                Button('+10秒').height(46).fontColor(Color.White).backgroundColor('#4D2F3545')
                  .onClick(() => this.SeekVideoBy(10000))
                Button(this.videoFullscreen ? '竖屏' : '横屏').height(46)
                  .fontColor(Color.White).backgroundColor('#4D2F3545')
                  .onClick(() => this.ToggleVideoFullscreen())
              }.width('100%')
            }.width('94%').padding({ left: 14, right: 14, top: 9, bottom: 12 })
              .margin({ bottom: 12 }).backgroundColor('#B311151D').borderRadius(18)
          }
        }.width('100%').height('100%')
      }

      if (this.mediaPlaybackError.length > 0) {
        Column({ space: 12 }) {
          Text(this.mediaPlaybackError).width('100%').textAlign(TextAlign.Center)
            .fontSize(14).fontColor('#FFD7D7')
          Row({ space: 12 }) {
            Button('返回').height(48).onClick(() => this.CloseRemoteMedia())
            Button(this.mediaOpening ? '正在重试…' : '重试').height(48).enabled(!this.mediaOpening)
              .fontColor(AppTheme.primary).backgroundColor(AppTheme.accentSoft).borderRadius(AppTheme.pillRadius)
              .onClick(() => this.RetryCurrentRemoteMedia())
          }
        }.width('86%').padding(18).backgroundColor('#CC1A1D25').borderRadius(16)
      }

      if (this.currentMediaKind === 'image' && this.mediaImageDeleteConfirm) {
        Column({ space: 12 }) {
          Text(`确认永久删除“${this.currentMediaName}”？`).width('100%').fontSize(16)
            .fontWeight(FontWeight.Bold).fontColor(Color.White)
          Text('操作不可撤销，不会影响其他媒体。').width('100%').fontSize(13).fontColor('#D7DEEA')
          Row({ space: 10 }) {
            Button('取消').layoutWeight(1).height(48).enabled(!this.mediaImageDeleting)
              .fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surfaceTint).borderRadius(AppTheme.pillRadius)
              .onClick(() => this.mediaImageDeleteConfirm = false)
            Button(this.mediaImageDeleting ? '正在删除…' : '确认永久删除').layoutWeight(1).height(48)
              .enabled(!this.mediaImageDeleting).fontColor(Color.White).backgroundColor(AppTheme.danger)
              .onClick(() => this.DeleteCurrentRemoteImage())
          }.width('100%')
        }.width('88%').padding(18).backgroundColor('#E61A1D25').borderRadius(16)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.Black)
    // 在查看器根节点并行识别纵向手势，避免Image内部缩放手势吞掉上下切换。
    .parallelGesture(PanGesture({ fingers: 1, direction: PanDirection.Vertical, distance: 48 })
      .onActionStart(() => this.BeginRemoteViewerVerticalSwipe())
      .onActionUpdate((event: GestureEvent) =>
        this.UpdateRemoteViewerVerticalSwipe(event.offsetX, event.offsetY))
      .onActionEnd((event: GestureEvent) => this.EndRemoteViewerVerticalSwipe(event.offsetX, event.offsetY)))
  }

  @Builder
  private RemoteMediaViewerPage() {
    Column() {
      Row() {
        this.BackButton(this.currentMediaKind === 'video' ? Color.White : AppTheme.textPrimary, () => {
          this.CloseRemoteMedia();
        })
        Text(this.currentMediaName)
          .layoutWeight(1)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .textAlign(TextAlign.Center)
          .fontSize(17)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.currentMediaKind === 'video' ? Color.White : AppTheme.textPrimary)
        if (this.currentMediaKind === 'video') {
          Button(this.videoFullscreen ? '退出全屏' : '全屏').width(64).height(48).fontSize(13)
            .fontColor(Color.White).backgroundColor(Color.Transparent)
            .onClick(() => this.videoController.requestFullscreen(!this.videoFullscreen))
        } else if (this.currentMediaKind === 'image') {
          Button() {
            SymbolGlyph($r(this.CurrentMediaIsFavorite() ? 'sys.symbol.star_fill' : 'sys.symbol.star'))
              .fontSize(22).fontColor([this.CurrentMediaIsFavorite() ? AppTheme.primary : AppTheme.textSecondary])
          }.width(48).height(48).padding(0).backgroundColor(Color.Transparent)
            .accessibilityText(this.CurrentMediaIsFavorite() ? '取消收藏当前图片' : '收藏当前图片')
            .enabled(!this.mediaImageDeleting && !this.mediaImageSharing).onClick(() => this.ToggleCurrentMediaFavorite())
          Button(this.mediaImageSharing ? '…' : '分享').width(48).height(48).fontSize(13)
            .fontColor(AppTheme.primary).backgroundColor(Color.Transparent)
            .accessibilityText('分享当前远程图片')
            .enabled(!this.mediaImageDeleting && !this.mediaImageSharing)
            .onClick(() => this.ShareCurrentRemoteImage())
          if (!this.CurrentMediaUsesLocalSource()) {
            Button('删').width(48).height(48).fontSize(13).fontColor(AppTheme.danger)
              .backgroundColor(Color.Transparent).accessibilityText('删除当前远程图片')
              .enabled(!this.mediaImageDeleting && !this.mediaImageSharing)
              .onClick(() => this.mediaImageDeleteConfirm = true)
          }
        } else {
          Blank().width(64)
        }
      }
      .width('100%')
      .height(64)
      .padding({ left: 18, right: 18 })

      if (this.currentMediaKind === 'image') {
        Text(this.CurrentImageSummary()).width('100%').maxLines(2)
          .textAlign(TextAlign.Center).fontSize(12).fontColor(AppTheme.textSecondary)
          .padding({ left: 18, right: 18, bottom: 8 })
      }

      if (this.currentMediaKind === 'image' && this.mediaImageDeleteConfirm) {
        Column({ space: 10 }) {
          Text(`确认永久删除“${this.currentMediaName}”？`).width('100%').fontSize(16)
            .fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
          Text('将从当前远程数据源删除该图片，操作不可撤销；不会删除其他图片。')
            .width('100%').fontSize(13).fontColor(AppTheme.textSecondary)
          Row({ space: 10 }) {
            Button('取消').layoutWeight(1).height(48).enabled(!this.mediaImageDeleting)
              .fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surfaceTint)
              .onClick(() => this.mediaImageDeleteConfirm = false)
            Button(this.mediaImageDeleting ? '正在删除…' : '确认永久删除').layoutWeight(1).height(48)
              .enabled(!this.mediaImageDeleting).fontColor(Color.White).backgroundColor(AppTheme.danger)
              .onClick(() => this.DeleteCurrentRemoteImage())
          }.width('100%')
        }.width('100%').padding(14).backgroundColor(AppTheme.dangerSoft).borderRadius(14)
          .margin({ left: 18, right: 18, bottom: 10 })
      }

      if (this.currentMediaResumeMs > 0 && this.currentMediaKind !== 'image') {
        Text(`已从 ${this.FormatDuration(this.currentMediaResumeMs)} 继续播放`).width('100%')
          .textAlign(TextAlign.Center).fontSize(12)
          .fontColor(this.currentMediaKind === 'video' ? Color.White : AppTheme.textSecondary)
      }

      if (this.currentMediaKind !== 'image' && this.currentMediaTicket.length > 0) {
        Row() {
          Text(this.mediaBufferState.length > 0 ? this.mediaBufferState : '正在检测播放状态')
            .fontSize(12).fontColor(this.currentMediaKind === 'video' ? Color.White : AppTheme.textSecondary)
          Blank()
          Text(this.mediaTransferSpeed > 0 ? `${this.FormatTransferSpeed(this.mediaTransferSpeed)}` : '0 KB/s')
            .fontSize(12).fontColor(this.currentMediaKind === 'video' ? Color.White : AppTheme.textSecondary)
        }.width('100%').padding({ left: 18, right: 18, bottom: 8 })
      }

      if (this.mediaPlaybackError.length > 0) {
        Column({ space: 10 }) {
          Text(this.mediaPlaybackError).width('100%').textAlign(TextAlign.Center).fontSize(14)
            .fontColor(this.currentMediaKind === 'video' ? '#FFD7D7' : AppTheme.danger)
          Row({ space: 12 }) {
            Button('返回').height(48).fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surfaceTint)
              .borderRadius(AppTheme.pillRadius).accessibilityDescription('关闭当前远程媒体')
              .onClick(() => this.CloseRemoteMedia())
            Button(this.mediaOpening ? '正在重试…' : '重试')
              .height(48).fontColor(AppTheme.primary).backgroundColor(AppTheme.accentSoft)
              .borderRadius(AppTheme.pillRadius).accessibilityDescription('重新申请播放票据并打开当前远程媒体')
              .enabled(!this.mediaOpening)
              .onClick(() => this.RetryCurrentRemoteMedia())
          }
        }.width('100%').padding({ left: 18, right: 18, bottom: 12 })
      }

      if (this.currentMediaKind === 'image') {
        Column({ space: 12 }) {
          Image(this.currentMediaUrl)
            .width('100%').layoutWeight(1).objectFit(ImageFit.Contain)
            .scale({ x: this.imageScale, y: this.imageScale })
            .translate({ x: this.imageOffsetX, y: this.imageOffsetY })
            .onComplete(() => this.mediaPlaybackError = '')
            .onError(() => this.mediaPlaybackError = '远程图片加载失败，请检查数据源或网络')
            .gesture(
              GestureGroup(GestureMode.Exclusive,
                PinchGesture({ fingers: 2 })
                  .onActionStart(() => this.BeginRemoteImagePinch())
                  .onActionUpdate((event: GestureEvent) => this.UpdateRemoteImagePinch(event.scale))
                  .onActionEnd(() => this.FinishRemoteImageTransform()),
                PanGesture({ fingers: 1, direction: PanDirection.All, distance: 8 })
                  .onActionStart(() => this.BeginRemoteImagePan())
                  .onActionUpdate((event: GestureEvent) => this.UpdateRemoteImagePan(event.offsetX, event.offsetY))
                  .onActionEnd((event: GestureEvent) => this.EndRemoteImagePan(event.offsetX, event.offsetY))
              )
            )
          Row() {
            Button('上一个').layoutWeight(1).height(48).accessibilityDescription('查看上一个远程媒体')
              .fontColor(Color.White).backgroundColor('#4D2F3545').borderRadius(AppTheme.pillRadius)
              .onClick(() => this.ChangeRemoteMedia(-1))
            Button('−').width(52).height(48).accessibilityText('缩小图片')
              .fontColor(Color.White).backgroundColor('#4D2F3545').borderRadius(AppTheme.pillRadius)
              .onClick(() => this.AdjustRemoteImageScale(-0.25))
            Button(`${this.imageScale.toFixed(2)}×`).width(70).height(48).fontSize(12)
              .fontColor(Color.White).backgroundColor('#4D2F3545').borderRadius(AppTheme.pillRadius)
              .accessibilityDescription('恢复图片原始比例和位置')
              .onClick(() => this.ResetRemoteImageTransform())
            Button('+').width(52).height(48).accessibilityText('放大图片')
              .fontColor(Color.White).backgroundColor('#4D2F3545').borderRadius(AppTheme.pillRadius)
              .onClick(() => this.AdjustRemoteImageScale(0.25))
            Button('下一个').layoutWeight(1).height(48).accessibilityDescription('查看下一个远程媒体')
              .fontColor(Color.White).backgroundColor('#4D2F3545').borderRadius(AppTheme.pillRadius)
              .onClick(() => this.ChangeRemoteMedia(1))
          }.width('100%').padding({ left: 14, right: 14, bottom: 14 })
          if (this.mediaThumbnailItems.length > 1) {
            Scroll() {
              Row({ space: 8 }) {
                ForEach(this.mediaThumbnailItems, (item: RemoteMediaItem) => {
                  Button() {
                    Stack({ alignContent: Alignment.Center }) {
                      if (this.MediaThumbnailUri(item.path).length > 0) {
                        Image(this.MediaThumbnailUri(item.path)).width(56).height(56).objectFit(ImageFit.Cover)
                      } else {
                        Text('…').width(56).height(56).textAlign(TextAlign.Center).fontColor(Color.White)
                          .backgroundColor('#303646')
                      }
                    }
                  }.width(56).height(56).padding(0).borderRadius(8).backgroundColor(Color.Transparent)
                    .border({ width: item.path === this.currentMediaPath ? 2 : 1,
                      color: item.path === this.currentMediaPath ? AppTheme.primary : '#6B7280' })
                    .accessibilityText(`查看图片：${item.name}`)
                    .accessibilityDescription(item.path === this.currentMediaPath ? '当前图片' : '打开远程图片')
                    .onClick(() => {
                      if (item.path !== this.currentMediaPath && !this.mediaImageDeleting) this.OpenRemoteMedia(item);
                    })
                }, (item: RemoteMediaItem) => item.path)
              }
            }.width('100%').height(64).scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off)
              .padding({ left: 14, right: 14, bottom: 8 })
          }
        }.width('100%').layoutWeight(1).backgroundColor('#0A0D14')
      } else if (this.currentMediaKind === 'video') {
        Column() {
          if (this.videoStatus.length > 0) {
            Text(this.videoStatus).fontSize(12).fontColor(Color.White).margin({ bottom: 10 })
          }
          if (this.mediaSubtitleStatus.length > 0) {
            Text(this.mediaSubtitleStatus).fontSize(12).fontColor('#D7DEFF').margin({ bottom: 8 })
          }
          Stack({ alignContent: Alignment.Bottom }) {
            Video({ src: this.currentMediaUrl, controller: this.videoController,
              currentProgressRate: this.mediaPlaybackRate })
              .width('100%').height('100%')
              .autoPlay(true)
              .controls(true)
              .objectFit(ImageFit.Contain)
              .onPrepared((event: PreparedInfo) => {
                this.mediaPlaybackError = '';
                this.playerDurationMs = event.duration * 1000;
                this.videoStatus = '已就绪 · 支持Range拖动';
                this.mediaBufferState = '视频已就绪';
                if (this.currentMediaResumeMs > 0) this.SeekVideoTo(this.currentMediaResumeMs);
              })
              .onStart(() => {
                this.videoExpectedPlaying = true;
                this.lastVideoProgressAt = Date.now();
                this.videoStatus = '';
                this.mediaBufferState = '播放中';
              })
              .onPause(() => {
                this.videoExpectedPlaying = false;
                this.videoStatus = '已暂停';
                this.mediaBufferState = '已暂停';
              })
              .onSeeking(() => {
                this.lastVideoProgressAt = Date.now();
                this.videoStatus = '正在定位…';
                this.mediaBufferState = '正在定位';
              })
              .onSeeked(() => this.videoStatus = '')
              .onUpdate((event: PlaybackInfo) => {
                this.playerTimeMs = event.time * 1000;
                this.lastVideoProgressAt = Date.now();
                this.mediaSubtitleText = this.mediaSubtitleEnabled ?
                  this.subtitleParser.textAt(this.mediaSubtitleCues, this.playerTimeMs) : '';
                this.UpdateMediaPlaybackProgress(this.playerTimeMs, this.playerDurationMs);
              })
              .onFinish(() => {
                this.videoExpectedPlaying = false;
                this.mediaBufferState = '播放完成';
                this.ClearCurrentMediaProgress();
              })
              .onStop(() => this.videoStatus = '已停止')
              .onError(() => {
                this.UpdateMediaPlaybackProgress(this.playerTimeMs, this.playerDurationMs);
                this.PersistMediaPlaybackProgress();
                this.videoStatus = '视频播放失败';
                this.mediaBufferState = '播放失败';
                this.mediaPlaybackError = '远程视频播放失败，请检查数据源或网络';
              })
              .onFullscreenChange((event: FullscreenInfo) => this.videoFullscreen = event.fullscreen)
            if (this.mediaSubtitleText.length > 0) {
              Text(this.mediaSubtitleText)
                .maxLines(4).textAlign(TextAlign.Center).fontSize(18).fontColor(Color.White)
                .padding({ left: 10, right: 10, top: 6, bottom: 6 }).margin({ left: 18, right: 18, bottom: 38 })
                .backgroundColor('#99000000').borderRadius(8)
            }
          }.width('100%').aspectRatio(16 / 9)
          Row({ space: 10 }) {
            Button('−10秒').height(48).accessibilityDescription('视频快退10秒')
              .fontColor(Color.White).backgroundColor('#4D2F3545').borderRadius(AppTheme.pillRadius)
              .onClick(() => this.SeekVideoBy(-10000))
            Button(`${this.mediaPlaybackRate.toFixed(2)}×`).height(48).accessibilityDescription('切换视频播放倍速')
              .fontColor(Color.White).backgroundColor('#4D2F3545').borderRadius(AppTheme.pillRadius)
              .onClick(() => this.CycleMediaPlaybackRate(false))
            Button('+10秒').height(48).accessibilityDescription('视频快进10秒')
              .fontColor(Color.White).backgroundColor('#4D2F3545').borderRadius(AppTheme.pillRadius)
              .onClick(() => this.SeekVideoBy(10000))
          }.margin({ top: 16 })
          if (this.mediaSubtitleCues.length > 0) {
            Row({ space: 10 }) {
              Button(this.mediaSubtitleEnabled ? '字幕开' : '字幕关').height(48)
                .fontColor(Color.White).backgroundColor('#4D2F3545').borderRadius(AppTheme.pillRadius)
                .accessibilityDescription(this.mediaSubtitleEnabled ? '关闭远程外挂字幕' : '开启远程外挂字幕').onClick(() => {
                this.mediaSubtitleEnabled = !this.mediaSubtitleEnabled;
                this.mediaSubtitleText = this.mediaSubtitleEnabled ?
                  this.subtitleParser.textAt(this.mediaSubtitleCues, this.playerTimeMs) : '';
              })
              if (this.mediaSubtitleTracks.length > 1) {
                Button(`切换字幕 ${this.mediaSubtitleTracks.findIndex((item: RemoteMediaItem) =>
                  item.path === this.mediaSubtitlePath) + 1}/${this.mediaSubtitleTracks.length}`)
                  .height(48).accessibilityDescription('切换到下一条远程外挂字幕')
                  .fontColor(Color.White).backgroundColor('#4D2F3545').borderRadius(AppTheme.pillRadius)
                  .onClick(() => this.CycleRemoteSubtitle())
              }
            }.margin({ top: 10 })
          }
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Start)
        .backgroundColor(Color.Black)
        .gesture(PanGesture({ direction: PanDirection.Vertical, distance: 80 })
          .onActionEnd((event: GestureEvent) => this.ChangeRemoteMedia(event.offsetY < 0 ? 1 : -1)))
      } else {
        Column({ space: 28 }) {
          SymbolGlyph($r('sys.symbol.music_note_circle_fill'))
            .width(220)
            .height(220)
            .fontSize(80)
            .fontColor([AppTheme.primary])
            .borderRadius(28)
            .backgroundColor(AppTheme.surfaceTint)
          Text(this.currentMediaName)
            .fontSize(22)
            .fontWeight(FontWeight.Bold)
            .fontColor(AppTheme.textPrimary)
          Slider({
            value: this.playerTimeMs,
            min: 0,
            max: Math.max(1, this.playerDurationMs)
          })
            .width('88%')
            .selectedColor(AppTheme.primary)
            .onChange((value: number, mode: SliderChangeMode) => {
              if (mode === SliderChangeMode.End) {
                const target = this.mediaSeekPolicy.absolute(value, this.playerDurationMs);
                if (target !== undefined) this.audioPlayer.seek(target);
              }
            })
          Row() {
            Text(this.FormatDuration(this.playerTimeMs))
              .fontSize(12)
              .fontColor(AppTheme.textSecondary)
            Blank()
            Text(this.FormatDuration(this.playerDurationMs))
              .fontSize(12)
              .fontColor(AppTheme.textSecondary)
          }
          .width('88%')
          Button(this.playerState === 'playing' ? '暂停' : '播放')
            .width(120)
            .height(50)
            .fontColor(Color.White)
            .backgroundColor(AppTheme.primary)
            .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
            .borderRadius(AppTheme.pillRadius)
            .onClick(() => {
              this.audioPlayer.toggle();
            })
          Row({ space: 10 }) {
            Button('上一首').height(48).accessibilityDescription('播放队列中的上一首远程音频')
              .fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surfaceTint).borderRadius(AppTheme.pillRadius)
              .onClick(() => this.ChangeRemoteAudio(-1))
            Button('−10秒').height(48).accessibilityDescription('音频快退10秒')
              .fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surfaceTint).borderRadius(AppTheme.pillRadius)
              .onClick(() => this.SeekAudioBy(-10000))
            Button('+10秒').height(48).accessibilityDescription('音频快进10秒')
              .fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surfaceTint).borderRadius(AppTheme.pillRadius)
              .onClick(() => this.SeekAudioBy(10000))
            Button('下一首').height(48).accessibilityDescription('播放队列中的下一首远程音频')
              .fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surfaceTint).borderRadius(AppTheme.pillRadius)
              .onClick(() => this.ChangeRemoteAudio(1))
          }
          Row({ space: 12 }) {
            Button(`${this.mediaPlaybackRate.toFixed(2)}×`).height(48).accessibilityDescription('切换音频播放倍速')
              .fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surfaceTint).borderRadius(AppTheme.pillRadius)
              .onClick(() => this.CycleMediaPlaybackRate(true))
            Button(this.audioLoopEnabled ? '单曲循环：开' : '单曲循环：关').height(48)
              .fontColor(this.audioLoopEnabled ? AppTheme.primary : AppTheme.textPrimary)
              .backgroundColor(this.audioLoopEnabled ? AppTheme.accentSoft : AppTheme.surfaceTint)
              .borderRadius(AppTheme.pillRadius)
              .accessibilityDescription(this.audioLoopEnabled ? '关闭单曲循环' : '开启单曲循环').onClick(() => {
              this.audioLoopEnabled = !this.audioLoopEnabled;
              this.audioPlayer.setLoop(this.audioLoopEnabled);
            })
            Button(this.audioSleepMinutes === 0 ? '定时：关' : `定时：${this.audioSleepMinutes}分`)
              .height(48).accessibilityDescription('切换音频定时关闭时长')
              .fontColor(this.audioSleepMinutes > 0 ? AppTheme.primary : AppTheme.textPrimary)
              .backgroundColor(this.audioSleepMinutes > 0 ? AppTheme.accentSoft : AppTheme.surfaceTint)
              .borderRadius(AppTheme.pillRadius)
              .onClick(() => this.CycleAudioSleepTimer())
          }
          Text(`输出设备：${this.audioOutputDevice}`).fontSize(12).fontColor(AppTheme.textSecondary)
          Button(this.audioQueueExpanded ? `收起播放队列 · ${this.CurrentAudioQueuePosition()}` :
            `播放队列 · ${this.CurrentAudioQueuePosition()}`)
            .height(48).accessibilityDescription(this.audioQueueExpanded ? '收起远程音频播放队列' : '展开远程音频播放队列')
            .fontColor(this.audioQueueExpanded ? AppTheme.primary : AppTheme.textPrimary)
            .backgroundColor(this.audioQueueExpanded ? AppTheme.accentSoft : AppTheme.surfaceTint)
            .borderRadius(AppTheme.pillRadius)
            .onClick(() => this.audioQueueExpanded = !this.audioQueueExpanded)
          if (this.audioQueueExpanded) {
            List({ space: 0 }) {
              ForEach(this.AudioQueueItems(), (item: RemoteMediaItem) => {
                ListItem() {
                  this.AudioQueueRow(item)
                }
              }, (item: RemoteMediaItem) => item.path)
            }
            .width('88%')
            .height(190)
            .borderRadius(12)
            .backgroundColor(AppTheme.surfaceTint)
          }
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Start)
        .padding({ top: 24 })
        .gesture(PanGesture({ direction: PanDirection.Vertical, distance: 80 })
          .onActionEnd((event: GestureEvent) => this.ChangeRemoteMedia(event.offsetY < 0 ? 1 : -1)))
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.currentMediaKind === 'video' ? Color.Black : AppTheme.background)
  }

  @Builder
  private AudioQueueRow(item: RemoteMediaItem) {
    Button() {
      Row({ space: 10 }) {
        SymbolGlyph($r(item.path === this.currentMediaPath ? 'sys.symbol.play_fill' :
          'sys.symbol.music_note_circle_fill'))
          .width(28).fontSize(18)
          .fontColor([item.path === this.currentMediaPath ? AppTheme.primary : AppTheme.textSecondary])
        Column({ space: 2 }) {
          Text(item.name).width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .fontSize(14).fontColor(item.path === this.currentMediaPath ? AppTheme.primary : AppTheme.textPrimary)
          Text(this.FormatMediaSize(item.size)).fontSize(11).fontColor(AppTheme.textSecondary)
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      }.width('100%')
    }.width('100%').height(58).padding({ left: 12, right: 12 })
      .backgroundColor(Color.Transparent)
      .border({ width: { bottom: 1 }, color: AppTheme.divider })
      .accessibilityText(`播放远程音频：${item.name}`)
      .accessibilityDescription(item.path === this.currentMediaPath ? '当前正在播放' : '切换到该音频')
      .onClick(() => {
      if (item.path !== this.currentMediaPath) this.OpenRemoteMedia(item);
    })
  }

  private MediaPreloadKey(sourceId: string, path: string): string {
    return `${sourceId.length}:${sourceId}${path}`;
  }

  private async CreateRemotePlaybackDescriptor(api: RemoteMediaApi, source: RemoteMediaSource,
    item: RemoteMediaItem, cancellation: DownloadCancellationToken): Promise<RemotePlaybackDescriptor> {
    if (item.kind === 'image' && source.type === 'MYTOOLS_LOCAL') {
      const hostContext = this.getUIContext().getHostContext();
      if (hostContext === undefined) throw new Error('无法获取图片缓存上下文');
      const imageUri = await this.mediaThumbnailCache.cacheLocalOriginal(hostContext as common.UIAbilityContext,
        api, source.id, item.path, cancellation);
      return { url: imageUri, ticket: '', expiresAt: 0, sourceType: source.type, supportsRange: false };
    }
    return api.resolvePlayback(source, item.path, cancellation);
  }

  private async CreateRemotePreloadDescriptor(api: RemoteMediaApi, source: RemoteMediaSource,
    item: RemoteMediaItem, cancellation: DownloadCancellationToken): Promise<RemotePlaybackDescriptor> {
    if (item.kind !== 'image') return this.CreateRemotePlaybackDescriptor(api, source, item, cancellation);
    const hostContext = this.getUIContext().getHostContext();
    if (hostContext === undefined) throw new Error('无法获取图片预加载上下文');
    const previewUri = await this.mediaThumbnailCache.cache(hostContext as common.UIAbilityContext, api, source.id,
      item.path, cancellation, source.type === 'MYTOOLS_LOCAL');
    return { url: previewUri, ticket: '', expiresAt: 0, sourceType: source.type, supportsRange: false };
  }

  private async ResolveRemotePlaybackDescriptor(api: RemoteMediaApi, source: RemoteMediaSource,
    item: RemoteMediaItem, cancellation: DownloadCancellationToken): Promise<RemotePlaybackDescriptor> {
    const key = this.MediaPreloadKey(source.id, item.path);
    const cached = this.mediaPreloadDescriptors.get(key);
    if (cached !== undefined && this.mediaPreloadPolicy.reusable(cached.url, cached.expiresAt)) return cached;
    this.mediaPreloadDescriptors.delete(key);
    const preload = this.mediaPreloadTasks.get(key);
    if (preload !== undefined) {
      const descriptor = await preload;
      if (cancellation.isCancelled()) throw new Error('远程媒体加载已取消');
      if (descriptor !== undefined && this.mediaPreloadPolicy.reusable(descriptor.url, descriptor.expiresAt)) {
        this.mediaPreloadTasks.delete(key);
        this.RememberMediaPreload(key, descriptor);
        return descriptor;
      }
    }
    return item.kind === 'image' ? this.CreateRemotePreloadDescriptor(api, source, item, cancellation) :
      this.CreateRemotePlaybackDescriptor(api, source, item, cancellation);
  }

  private RememberMediaPreload(key: string, descriptor: RemotePlaybackDescriptor): void {
    this.mediaPreloadDescriptors.delete(key);
    this.mediaPreloadDescriptors.set(key, descriptor);
    while (this.mediaPreloadDescriptors.size > 16) {
      const oldest = this.mediaPreloadDescriptors.keys().next();
      if (oldest.done) return;
      this.mediaPreloadDescriptors.delete(oldest.value);
    }
  }

  private StartAdjacentMediaPreload(source: RemoteMediaSource, current: RemoteMediaItem): void {
    this.CancelMediaPreloads();
    const items = current.kind === 'image' || current.kind === 'video' ?
      this.VisualMediaViewerSequence() : this.MediaViewerSequence();
    const currentIndex = items.findIndex((item: RemoteMediaItem) => item.path === current.path);
    const neighborIndexes = this.mediaPreloadPolicy.neighborIndexes(items.length, currentIndex);
    if (neighborIndexes.length === 0) return;
    const revision = this.mediaPreloadRevision;
    const cancellation = new DownloadCancellationToken();
    this.activeMediaPreloadCancellation = cancellation;
    const api = new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager);
    const lanes: Promise<void>[] = [Promise.resolve(), Promise.resolve()];
    neighborIndexes.forEach((index: number, order: number) => {
      const item = items[index];
      const key = this.MediaPreloadKey(source.id, item.path);
      const cached = this.mediaPreloadDescriptors.get(key);
      if (cached !== undefined && this.mediaPreloadPolicy.reusable(cached.url, cached.expiresAt)) return;
      this.mediaPreloadDescriptors.delete(key);
      const lane = order % lanes.length;
      const task = lanes[lane].then(() => this.CreateRemotePreloadDescriptor(api, source, item, cancellation))
        .then(async (descriptor: RemotePlaybackDescriptor): Promise<RemotePlaybackDescriptor | undefined> => {
          if (revision !== this.mediaPreloadRevision || cancellation.isCancelled()) return undefined;
          if (item.kind === 'video') await api.preloadVideoSegment(descriptor.url, cancellation);
          if (revision !== this.mediaPreloadRevision || cancellation.isCancelled()) return undefined;
          this.RememberMediaPreload(key, descriptor);
          return descriptor;
        })
        .catch((): RemotePlaybackDescriptor | undefined => undefined);
      lanes[lane] = task.then((): void => {});
      this.mediaPreloadTasks.set(key, task);
    });
  }

  private CancelMediaPreloads(): void {
    this.mediaPreloadRevision++;
    this.activeMediaPreloadCancellation?.cancel();
    this.activeMediaPreloadCancellation = undefined;
    this.mediaPreloadTasks.clear();
  }

  private ClearMediaPreloads(): void {
    this.CancelMediaPreloads();
    this.mediaPreloadDescriptors.clear();
  }

  private async OpenRemoteMedia(item: RemoteMediaItem, resumeOverrideMs?: number): Promise<void> {
    // 同一媒体正在获取播放地址时复用当前请求，避免连续点击反复取消并重新计时。
    if (this.mediaOpening && this.mediaOpeningPath === item.path) return;
    this.activeMediaOpenCancellation?.cancel();
    this.activeImageUpgradeCancellation?.cancel();
    this.activeThumbnailCancellation?.cancel();
    const configuredSource = this.mediaSources.find((value: RemoteMediaSource) =>
      value.id === this.selectedMediaSourceId);
    // 新媒体库的/media路径必须使用媒体库票据，不能被历史选中的远程源覆盖。
    const source = this.IsCatalogMediaPath(item.path) ? this.CatalogLocalMediaSource() : configuredSource;
    if (source === undefined) {
      this.mediaStatus = '远程数据源不可用';
      return;
    }
    if (!this.mediaViewerVisible) {
      // 全屏页面会临时移除主Tabs组件，先保留当前图库投影，返回时用于恢复Image数据源绑定。
      this.mediaBrowseViewerSnapshotSourceId = source.id;
      this.mediaBrowseViewerSnapshotEntries = this.mediaBrowseThumbnailEntries.slice();
    }
    const cancellation = new DownloadCancellationToken();
    this.activeMediaOpenCancellation = cancellation;
    const revision = ++this.mediaOpenRevision;
    this.mediaOpeningPath = item.path;
    if (item.kind === 'image') this.queuedImageNavigationDirection = 0;
    if (item.kind === 'audio') this.queuedAudioNavigationDirection = 0;
    this.mediaOpening = true;
    this.mediaStatus = '正在获取安全播放地址…';
    this.mediaPlaybackError = '';
    try {
      const api = new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager);
      const descriptor = await this.ResolveRemotePlaybackDescriptor(api, source, item, cancellation);
      if (revision !== this.mediaOpenRevision) return;
      this.RememberMediaPreload(this.MediaPreloadKey(source.id, item.path), descriptor);
      this.currentMediaName = item.name;
      this.currentMediaKind = item.kind;
      this.currentMediaUrl = descriptor.url;
      this.currentMediaPreviewUri = this.BrowseMediaThumbnailUri(item.path);
      this.currentMediaPath = item.path;
      this.currentMediaContentType = item.contentType;
      this.currentMediaSize = item.size;
      this.currentMediaLastModified = item.lastModified;
      this.mediaViewerChromeVisible = true;
      this.mediaViewerDragging = false;
      this.mediaViewerDragOffsetY = 0;
      this.mediaImageDeleteConfirm = false;
      if (item.kind === 'image') this.LoadMediaThumbnailWindow(api, source, item, revision);
      this.currentMediaTicket = descriptor.ticket;
      this.ClearRemoteSubtitle();
      if (item.kind !== 'image' && source.type !== 'MYTOOLS_LOCAL') this.StartMediaMetrics();
      this.videoStatus = item.kind === 'video' ? '正在连接远程视频…' : '';
      this.videoFullscreen = false;
      this.ResetRemoteImageTransform();
      this.currentMediaResumeMs = resumeOverrideMs === undefined ? this.RestoredMediaPosition() :
        this.mediaRecoveryPolicy.position(resumeOverrideMs, this.RestoredMediaPosition(), this.playerDurationMs);
      this.mediaViewerVisible = true;
      this.ApplyMediaViewerSystemBars(true);
      this.ScheduleMediaViewerChromeAutoHide();
      this.mediaStatus = '';
      this.StartAdjacentMediaPreload(source, item);
      if (item.kind === 'image') this.UpgradeCurrentRemoteImage(api, source, item, revision);
      if (item.kind === 'video') this.LoadRemoteSubtitle(api, source, item, revision);
      if (item.kind === 'audio') {
        const hostContext = this.getUIContext().getHostContext();
        if (hostContext === undefined) throw new Error('无法获取后台音频上下文');
        await this.audioPlayer.open(descriptor.url, this.currentMediaResumeMs,
          hostContext as common.UIAbilityContext, item.name);
        if (revision !== this.mediaOpenRevision) await this.audioPlayer.release();
      }
    } catch (error) {
      if (revision !== this.mediaOpenRevision) return;
      if (cancellation.isCancelled()) return;
      this.StopMediaMetrics();
      const message = error instanceof Error ? error.message : '无法打开远程媒体';
      this.mediaStatus = message;
      if (this.mediaViewerVisible) this.mediaPlaybackError = message;
    } finally {
      if (this.activeMediaOpenCancellation === cancellation) this.activeMediaOpenCancellation = undefined;
      if (revision === this.mediaOpenRevision) {
        this.mediaOpening = false;
        this.mediaOpeningPath = '';
        const queuedDirection = this.queuedImageNavigationDirection;
        this.queuedImageNavigationDirection = 0;
        if (queuedDirection !== 0 && this.currentMediaKind === 'image' && this.mediaViewerVisible) {
          this.ChangeRemoteImage(queuedDirection);
        }
        const queuedAudioDirection = this.queuedAudioNavigationDirection;
        this.queuedAudioNavigationDirection = 0;
        if (queuedAudioDirection !== 0 && this.currentMediaKind === 'audio' && this.mediaViewerVisible) {
          this.ChangeRemoteAudio(queuedAudioDirection);
        }
      }
    }
  }

  private async UpgradeCurrentRemoteImage(api: RemoteMediaApi, source: RemoteMediaSource,
    item: RemoteMediaItem, revision: number): Promise<void> {
    const hostContext = this.getUIContext().getHostContext();
    if (hostContext === undefined) return;
    const cancellation = new DownloadCancellationToken();
    this.activeImageUpgradeCancellation = cancellation;
    try {
      const uri = await this.mediaThumbnailCache.cacheShare(hostContext as common.UIAbilityContext, api, source.id,
        item.path, cancellation);
      if (cancellation.isCancelled() || revision !== this.mediaOpenRevision ||
        this.currentMediaPath !== item.path || this.currentMediaKind !== 'image') return;
      this.currentMediaUrl = uri;
    } catch (_) {
      // 高清图升级失败时继续保留已预加载的512像素预览，避免阻断上下滑动查看。
    } finally {
      if (this.activeImageUpgradeCancellation === cancellation) this.activeImageUpgradeCancellation = undefined;
    }
  }

  private async CloseRemoteMedia(): Promise<void> {
    this.mediaOpenRevision++;
    this.activeMediaOpenCancellation?.cancel();
    this.activeMediaOpenCancellation = undefined;
    this.activeImageUpgradeCancellation?.cancel();
    this.activeImageUpgradeCancellation = undefined;
    this.ClearMediaPreloads();
    this.activeThumbnailCancellation?.cancel();
    this.activeThumbnailCancellation = undefined;
    this.activeImageShareCancellation?.cancel();
    this.activeImageShareCancellation = undefined;
    this.queuedImageNavigationDirection = 0;
    this.queuedAudioNavigationDirection = 0;
    this.mediaOpening = false;
    this.mediaImageDeleteConfirm = false;
    this.mediaImageDeleting = false;
    this.mediaImageSharing = false;
    this.mediaThumbnailItems = [];
    this.mediaThumbnailEntries = [];
    this.mediaViewerVisible = false;
    this.mediaViewerDragging = false;
    this.mediaViewerDragOffsetY = 0;
    this.ClearMediaViewerChromeTimer();
    this.ApplyMediaViewerSystemBars(false);
    this.RestoreMediaBrowseThumbnailsAfterViewer();
    this.mediaViewerChromeVisible = true;
    this.audioQueueExpanded = false;
    this.ClearAudioSleepTimer();
    this.PersistMediaPlaybackProgress();
    if (this.currentMediaKind === 'audio') {
      await this.audioPlayer.release();
    }
    this.videoController.stop();
    this.ApplyVideoOrientation(false);
    this.currentMediaName = '';
    this.currentMediaKind = '';
    this.currentMediaUrl = '';
    this.currentMediaPreviewUri = '';
    this.currentMediaPath = '';
    this.currentMediaContentType = '';
    this.currentMediaSize = 0;
    this.currentMediaLastModified = '';
    this.currentMediaResumeMs = 0;
    this.ClearRemoteSubtitle();
    this.mediaPlaybackError = '';
    this.videoStatus = '';
    this.videoFullscreen = false;
    this.StopMediaMetrics();
    this.videoExpectedPlaying = false;
    this.lastVideoProgressAt = 0;
    this.playerState = 'idle';
    this.playerTimeMs = 0;
    this.playerDurationMs = 0;
  }

  private RestoreMediaBrowseThumbnailsAfterViewer(): void {
    const catalogMode = this.currentIndex === 3 && this.mediaCatalogItems.length > 0;
    const sourceId = catalogMode ? this.CatalogLocalMediaSource().id : this.selectedMediaSourceId;
    const items = catalogMode ? this.mediaCatalogItems.map((item: MediaCatalogItem): RemoteMediaItem =>
      this.CatalogRemoteItem(item)) : this.mediaItems;
    const validPaths = items.filter((item: RemoteMediaItem) => item.kind === 'image' || item.kind === 'video')
      .map((item: RemoteMediaItem) => item.path);
    const snapshot = this.mediaBrowseViewerSnapshotSourceId === sourceId ?
      this.mediaBrowseViewerSnapshotEntries : [];
    const restored = this.mediaThumbnailRestorePolicy.restore(validPaths, this.mediaBrowseThumbnailEntries, snapshot,
      (uri: string) => this.IsAvailableMediaPreviewUri(uri));
    // 即使URI仍然有效也重新发布数组，强制重建后的图库Image组件恢复绑定。
    this.SetMediaBrowseThumbnailEntries(restored);
    this.mediaBrowseViewerSnapshotSourceId = '';
    this.mediaBrowseViewerSnapshotEntries = [];
    if (!this.authenticated || sourceId.length === 0) return;
    const missingPaths = new Set<string>(this.mediaThumbnailRestorePolicy.missing(validPaths, restored));
    const missing = items.filter((item: RemoteMediaItem) => missingPaths.has(item.path));
    if (missing.length > 0) {
      this.LoadMediaBrowseThumbnails(new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager), sourceId,
        this.mediaDirectoryRevision, missing, false);
    }
  }

  private IsAvailableMediaPreviewUri(uri: string): boolean {
    if (uri.length === 0) return false;
    if (!uri.startsWith('file://')) return true;
    try {
      return fs.accessSync(uri.substring('file://'.length));
    } catch (_) {
      return false;
    }
  }

  private async DeleteCurrentRemoteImage(): Promise<void> {
    if (this.mediaImageDeleting || this.currentMediaKind !== 'image' || !this.authenticated) return;
    const path = this.currentMediaPath;
    const localCatalog = this.IsCatalogMediaPath(path);
    const accountId = localCatalog ? this.CatalogLocalMediaSource().id : this.selectedMediaSourceId;
    const source = localCatalog ? this.CatalogLocalMediaSource() :
      this.mediaSources.find((item: RemoteMediaSource) => item.id === accountId && item.isActive);
    const target = this.CurrentRemoteMediaItem();
    if (source === undefined || target === undefined) {
      this.mediaPlaybackError = '删除目标已不在当前远程图片列表，请返回刷新';
      this.mediaImageDeleteConfirm = false;
      return;
    }
    const revision = this.mediaOpenRevision;
    this.mediaImageDeleting = true;
    try {
      const api = new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager);
      await api.deleteEntry(accountId, path, false);
      if (revision !== this.mediaOpenRevision) return;
      // 服务端明确成功后再同步移除当前目录、收藏和历史投影。
      this.RemoveMediaMutationProjection(target);
      this.mediaFavorites = this.mediaFavorites.filter((item: MediaFavorite) =>
        item.owner !== this.localAccountScope || item.accountId !== accountId || item.path !== path);
      this.PersistMediaFavorites();
      await this.CloseRemoteMedia();
      await this.RefreshMediaAfterMutation(target);
      this.mediaStatus = `已永久删除远程图片：${target.name}`;
    } catch (error) {
      if (revision !== this.mediaOpenRevision) return;
      this.mediaPlaybackError = error instanceof Error ? error.message : '删除远程图片失败';
      this.mediaImageDeleteConfirm = false;
      this.mediaImageDeleting = false;
    }
  }

  private async ShareCurrentRemoteImage(): Promise<void> {
    if (this.mediaImageSharing || this.mediaImageDeleting || this.currentMediaKind !== 'image' || !this.authenticated) {
      return;
    }
    const source = this.mediaSources.find((item: RemoteMediaSource) => item.id === this.selectedMediaSourceId &&
      item.isActive);
    const target = this.mediaItems.find((item: RemoteMediaItem) =>
      item.path === this.currentMediaPath && item.kind === 'image');
    const hostContext = this.getUIContext().getHostContext();
    if (source === undefined || target === undefined || hostContext === undefined) {
      this.mediaPlaybackError = '当前远程图片无法安全分享，请返回刷新';
      return;
    }
    const revision = this.mediaOpenRevision;
    const cancellation = new DownloadCancellationToken();
    this.activeImageShareCancellation = cancellation;
    this.mediaImageSharing = true;
    try {
      const api = new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager);
      const uri = await this.mediaThumbnailCache.cacheShare(hostContext as common.UIAbilityContext, api, source.id,
        target.path, cancellation);
      if (revision !== this.mediaOpenRevision || this.currentMediaPath !== target.path) return;
      const record: systemShare.SharedRecord = {
        utd: uniformTypeDescriptor.UniformDataType.JPEG,
        uri: uri,
        title: target.name,
        description: '来自MyTools远程媒体的安全JPEG预览'
      };
      const controller = new systemShare.ShareController(new systemShare.SharedData(record));
      await controller.show(hostContext as common.UIAbilityContext,
        { selectionMode: systemShare.SelectionMode.SINGLE });
    } catch (error) {
      if (revision === this.mediaOpenRevision) {
        this.mediaPlaybackError = error instanceof Error ? error.message : '打开系统分享失败';
      }
    } finally {
      if (this.activeImageShareCancellation === cancellation) this.activeImageShareCancellation = undefined;
      if (revision === this.mediaOpenRevision) this.mediaImageSharing = false;
    }
  }

  private async LoadMediaThumbnailWindow(api: RemoteMediaApi, source: RemoteMediaSource,
    current: RemoteMediaItem, revision: number): Promise<void> {
    const images = this.MediaViewerSequence().filter((item: RemoteMediaItem) => item.kind === 'image');
    const currentIndex = images.findIndex((item: RemoteMediaItem) => item.path === current.path);
    if (currentIndex < 0) return;
    const start = Math.max(0, currentIndex - 5);
    const window = images.slice(start, Math.min(images.length, currentIndex + 6));
    this.mediaThumbnailItems = window;
    this.mediaThumbnailEntries = [];
    const hostContext = this.getUIContext().getHostContext();
    if (hostContext === undefined) return;
    const cancellation = new DownloadCancellationToken();
    this.activeThumbnailCancellation = cancellation;
    try {
      for (let index = 0; index < window.length; index++) {
        try {
          const uri = await this.mediaThumbnailCache.cache(hostContext as common.UIAbilityContext, api, source.id,
            window[index].path, cancellation, true);
          if (revision !== this.mediaOpenRevision || this.currentMediaKind !== 'image') return;
          this.mediaThumbnailEntries = this.mediaThumbnailEntries.filter((entry: MediaThumbnailEntry) =>
            entry.path !== window[index].path).concat({ path: window[index].path, uri: uri });
        } catch (_) {
          if (cancellation.isCancelled()) return;
          // 单个缩略图失败仅保留占位，不影响原图查看和其他序列项。
        }
      }
    } finally {
      if (this.activeThumbnailCancellation === cancellation) this.activeThumbnailCancellation = undefined;
    }
  }

  private MediaThumbnailUri(path: string): string {
    return this.mediaThumbnailEntries.find((entry: MediaThumbnailEntry) => entry.path === path)?.uri ?? '';
  }

  private BrowseMediaThumbnailUri(path: string): string {
    return this.mediaBrowseThumbnailEntries.find((entry: MediaThumbnailEntry) => entry.path === path)?.uri ?? '';
  }

  private SetMediaBrowseThumbnailEntries(entries: MediaThumbnailEntry[]): void {
    this.mediaBrowseThumbnailEntries = entries;
    this.mediaThumbnailCache.protectBrowseUris(entries.map((entry: MediaThumbnailEntry) => entry.uri));
  }

  private RetryCurrentRemoteMedia(): void {
    const item = this.mediaItems.find((value: RemoteMediaItem) => value.path === this.currentMediaPath &&
      value.kind === this.currentMediaKind);
    if (item === undefined) {
      this.mediaPlaybackError = '当前远程媒体已不在目录中，请返回后刷新';
      return;
    }
    const recoveryPosition = item.kind === 'image' ? 0 : this.mediaRecoveryPolicy.position(
      this.playerTimeMs, this.RestoredMediaPosition(), this.playerDurationMs);
    if (item.kind !== 'image') {
      this.UpdateMediaPlaybackProgress(recoveryPosition, this.playerDurationMs);
      this.PersistMediaPlaybackProgress();
    }
    this.mediaPlaybackError = '';
    this.mediaBufferState = '正在申请新播放票据…';
    this.OpenRemoteMedia(item, recoveryPosition);
  }

  private async LoadRemoteSubtitle(api: RemoteMediaApi, source: RemoteMediaSource, video: RemoteMediaItem,
    revision: number): Promise<void> {
    const tracks = this.subtitlePolicy.findSidecars(this.mediaDirectoryItems, video);
    if (tracks.length === 0) return;
    this.mediaSubtitleTracks = tracks;
    await this.LoadRemoteSubtitleTrack(api, source, video, tracks[0], revision);
  }

  private async LoadRemoteSubtitleTrack(api: RemoteMediaApi, source: RemoteMediaSource, video: RemoteMediaItem,
    subtitle: RemoteMediaItem, revision: number): Promise<void> {
    this.activeSubtitleCancellation?.cancel();
    const cancellation = new DownloadCancellationToken();
    this.activeSubtitleCancellation = cancellation;
    const subtitleRevision = ++this.mediaSubtitleRevision;
    this.mediaSubtitleCues = [];
    this.mediaSubtitleText = '';
    this.mediaSubtitlePath = subtitle.path;
    this.mediaSubtitleStatus = `正在加载字幕 · ${subtitle.name}`;
    try {
      const cues = this.subtitleParser.parse(await api.loadSubtitle(source, subtitle.path, cancellation));
      if (revision !== this.mediaOpenRevision || subtitleRevision !== this.mediaSubtitleRevision ||
        this.currentMediaPath !== video.path) return;
      this.mediaSubtitleCues = cues;
      this.mediaSubtitleText = this.subtitleParser.textAt(cues, this.playerTimeMs);
      this.mediaSubtitleStatus = `字幕 · ${subtitle.name}`;
    } catch (_) {
      if (revision === this.mediaOpenRevision && subtitleRevision === this.mediaSubtitleRevision &&
        this.currentMediaPath === video.path) {
        this.mediaSubtitleStatus = `字幕不可用 · ${subtitle.name}`;
      }
    } finally {
      if (this.activeSubtitleCancellation === cancellation) this.activeSubtitleCancellation = undefined;
    }
  }

  private CycleRemoteSubtitle(): void {
    if (this.mediaSubtitleTracks.length < 2 || this.currentMediaKind !== 'video') return;
    const source = this.mediaSources.find((item: RemoteMediaSource) => item.id === this.selectedMediaSourceId);
    const video = this.mediaItems.find((item: RemoteMediaItem) => item.path === this.currentMediaPath &&
      item.kind === 'video');
    if (source === undefined || video === undefined) return;
    const current = this.mediaSubtitleTracks.findIndex((item: RemoteMediaItem) => item.path === this.mediaSubtitlePath);
    const next = this.mediaSubtitleTracks[(current + 1) % this.mediaSubtitleTracks.length];
    this.mediaSubtitleEnabled = true;
    this.LoadRemoteSubtitleTrack(new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager), source, video, next,
      this.mediaOpenRevision);
  }

  private ClearRemoteSubtitle(): void {
    this.activeSubtitleCancellation?.cancel();
    this.activeSubtitleCancellation = undefined;
    this.mediaSubtitleRevision++;
    this.mediaSubtitleCues = [];
    this.mediaSubtitleTracks = [];
    this.mediaSubtitlePath = '';
    this.mediaSubtitleText = '';
    this.mediaSubtitleStatus = '';
    this.mediaSubtitleEnabled = true;
  }

  private StartMediaMetrics(): void {
    const ticket = this.currentMediaTicket;
    this.StopMediaMetrics();
    if (ticket.length === 0) return;
    this.currentMediaTicket = ticket;
    this.lastMediaMetricBytes = 0;
    this.lastMediaMetricAt = 0;
    this.mediaTransferSpeed = 0;
    this.mediaBufferState = '正在连接远程数据';
    this.PollMediaMetrics();
    this.mediaMetricsTimerId = setInterval(() => this.PollMediaMetrics(), 2000);
  }

  private StopMediaMetrics(): void {
    if (this.mediaMetricsTimerId >= 0) {
      clearInterval(this.mediaMetricsTimerId);
      this.mediaMetricsTimerId = -1;
    }
    this.activeMediaMetricsCancellation?.cancel();
    this.activeMediaMetricsCancellation = undefined;
    this.mediaMetricsPolling = false;
    this.currentMediaTicket = '';
    this.lastMediaMetricBytes = 0;
    this.lastMediaMetricAt = 0;
    this.mediaTransferSpeed = 0;
    this.mediaBufferState = '';
  }

  private async PollMediaMetrics(): Promise<void> {
    const ticket = this.currentMediaTicket;
    if (this.mediaMetricsPolling || ticket.length === 0 || !this.authenticated) return;
    const cancellation = new DownloadCancellationToken();
    this.activeMediaMetricsCancellation = cancellation;
    this.mediaMetricsPolling = true;
    try {
      const metrics = await new RemoteMediaApi(this.ActiveServiceUrl(), this.authManager)
        .getPlaybackMetrics(ticket, cancellation);
      if (ticket !== this.currentMediaTicket) return;
      const now = Date.now();
      if (this.lastMediaMetricAt > 0 && metrics.transferredBytes >= this.lastMediaMetricBytes) {
        const elapsedSeconds = Math.max(0.001, (now - this.lastMediaMetricAt) / 1000);
        this.mediaTransferSpeed = (metrics.transferredBytes - this.lastMediaMetricBytes) / elapsedSeconds;
      }
      this.lastMediaMetricBytes = metrics.transferredBytes;
      this.lastMediaMetricAt = now;
      const transferRecent = metrics.lastTransferTime > 0 && now - metrics.lastTransferTime < 4000;
      const videoStalled = this.currentMediaKind === 'video' && this.videoExpectedPlaying &&
        this.lastVideoProgressAt > 0 && now - this.lastVideoProgressAt > 3000;
      if (videoStalled) {
        this.mediaBufferState = transferRecent && metrics.activeStreams > 0 ? '正在缓冲 · 接收数据' : '等待远程数据';
      } else if (metrics.activeStreams > 0 && transferRecent) {
        this.mediaBufferState = '传输中';
      } else if (this.currentMediaKind === 'video' && this.videoExpectedPlaying) {
        this.mediaBufferState = '播放流畅 · 已缓存';
      } else if (this.currentMediaKind === 'audio' && this.playerState === 'playing') {
        this.mediaBufferState = '播放中 · 已缓存';
      }
    } catch (_) {
      if (!cancellation.isCancelled() && ticket === this.currentMediaTicket) {
        this.mediaBufferState = '网络指标暂不可用';
      }
    } finally {
      if (this.activeMediaMetricsCancellation === cancellation) {
        this.activeMediaMetricsCancellation = undefined;
        this.mediaMetricsPolling = false;
      }
    }
  }

  private CurrentMediaUsesLocalSource(): boolean {
    return this.mediaSources.find((source: RemoteMediaSource) => source.id === this.selectedMediaSourceId)?.type ===
      'MYTOOLS_LOCAL';
  }

  private FormatTransferSpeed(bytesPerSecond: number): string {
    if (bytesPerSecond < 1024) return `${Math.round(bytesPerSecond)} B/s`;
    if (bytesPerSecond < 1024 * 1024) return `${(bytesPerSecond / 1024).toFixed(1)} KB/s`;
    return `${(bytesPerSecond / 1024 / 1024).toFixed(1)} MB/s`;
  }

  private CurrentMediaProgressKey(): string {
    if (this.localAccountScope.length === 0 || this.selectedMediaSourceId.length === 0 ||
      this.currentMediaPath.length === 0) return '';
    return `${this.selectedMediaSourceId.length}:${this.selectedMediaSourceId}${this.currentMediaPath}`;
  }

  private RestoredMediaPosition(): number {
    const key = this.CurrentMediaProgressKey();
    const progress = this.mediaPlaybackProgress.find((value: MediaPlaybackProgress) =>
      value.owner === this.localAccountScope && value.key === key);
    if (progress === undefined || progress.positionMs < 5000) return 0;
    if (progress.durationMs > 0 && progress.positionMs >= progress.durationMs - 10000) return 0;
    return progress.positionMs;
  }

  private UpdateMediaPlaybackProgress(positionMs: number, durationMs: number): void {
    const key = this.CurrentMediaProgressKey();
    if (key.length === 0 || this.currentMediaKind === 'image' || positionMs < 0) return;
    if (durationMs > 0 && positionMs >= durationMs - 10000) {
      const remaining = this.mediaPlaybackProgress.filter((value: MediaPlaybackProgress) =>
        value.owner !== this.localAccountScope || value.key !== key);
      if (remaining.length !== this.mediaPlaybackProgress.length) {
        this.mediaPlaybackProgress = remaining;
        this.PersistMediaPlaybackProgress();
      }
      return;
    }
    const progress: MediaPlaybackProgress = {
      owner: this.localAccountScope,
      key: key,
      accountId: this.selectedMediaSourceId,
      name: this.currentMediaName,
      path: this.currentMediaPath,
      kind: this.currentMediaKind,
      contentType: this.currentMediaContentType,
      size: this.currentMediaSize,
      lastModified: this.currentMediaLastModified,
      positionMs: Math.floor(positionMs),
      durationMs: Math.floor(durationMs),
      updatedAt: Date.now()
    };
    this.mediaPlaybackProgress = this.mediaPlaybackProgress.filter(
      (value: MediaPlaybackProgress) => value.owner !== this.localAccountScope || value.key !== key).concat(progress);
    this.mediaPlaybackProgress = this.BoundedMediaPlaybackProgress(this.mediaPlaybackProgress);
    if (Date.now() - this.lastMediaProgressPersistedAt >= 15000) this.PersistMediaPlaybackProgress();
  }

  private ClearCurrentMediaProgress(): void {
    const key = this.CurrentMediaProgressKey();
    this.mediaPlaybackProgress = this.mediaPlaybackProgress.filter(
      (value: MediaPlaybackProgress) => value.owner !== this.localAccountScope || value.key !== key);
    this.PersistMediaPlaybackProgress();
  }

  private PersistMediaPlaybackProgress(): void {
    try {
      this.settingsPreferences?.putSync('media_playback_progress', JSON.stringify(
        this.BoundedMediaPlaybackProgress(this.mediaPlaybackProgress)));
      this.settingsPreferences?.flush();
      this.lastMediaProgressPersistedAt = Date.now();
    } catch (_) {
    }
  }

  private RestoreMediaPlaybackProgress(value: string): void {
    try {
      if (value.length > 1024 * 1024) return;
      this.mediaPlaybackProgress = this.mediaHistoryPolicy.normalize(JSON.parse(value) as Object);
    } catch (_) {
      this.mediaPlaybackProgress = [];
    }
  }

  private CurrentMediaHistory(): MediaPlaybackProgress[] {
    return this.mediaPlaybackProgress.filter((item: MediaPlaybackProgress) =>
      item.owner === this.localAccountScope && this.mediaSources.some((source: RemoteMediaSource) =>
        source.id === item.accountId && source.isActive)).sort((left: MediaPlaybackProgress,
      right: MediaPlaybackProgress) => right.updatedAt - left.updatedAt).slice(0, 4);
  }

  private MediaHistorySourceName(accountId: string): string {
    return this.mediaSources.find((source: RemoteMediaSource) => source.id === accountId)?.name ?? '远程数据源';
  }

  private OpenMediaHistory(progress: MediaPlaybackProgress): void {
    const source = this.mediaSources.find((item: RemoteMediaSource) => item.id === progress.accountId && item.isActive);
    if (source === undefined) {
      this.mediaStatus = '该播放记录对应的远程数据源当前不可用';
      return;
    }
    this.selectedMediaSourceId = source.id;
    this.OpenRemoteMedia({ name: progress.name, path: progress.path, kind: progress.kind === 'audio' ? 'audio' : 'video',
      contentType: progress.contentType, size: progress.size, lastModified: progress.lastModified });
  }

  private BoundedMediaPlaybackProgress(items: MediaPlaybackProgress[]): MediaPlaybackProgress[] {
    return this.mediaHistoryPolicy.normalize(items as Object);
  }

  private ChangeRemoteImage(offset: number): void {
    if (this.mediaImageDeleting || this.mediaImageSharing) return;
    const direction = this.imageGesturePolicy.normalizeNavigationDirection(offset);
    if (direction === 0) return;
    if (this.mediaOpening) {
      // 只保留加载期间最新一次切图意图，防止并发票据请求和无界队列。
      this.queuedImageNavigationDirection = direction;
      return;
    }
    const images = this.MediaViewerSequence().filter((item: RemoteMediaItem) => item.kind === 'image');
    if (images.length < 2) return;
    const currentIndex = images.findIndex((item: RemoteMediaItem) => item.path === this.currentMediaPath);
    if (currentIndex < 0) return;
    const nextIndex = this.imageGesturePolicy.nextIndex(images.length, currentIndex, direction);
    if (nextIndex < 0) return;
    this.OpenRemoteMedia(images[nextIndex]);
  }

  private async ChangeRemoteMedia(offset: number): Promise<void> {
    if (this.mediaOpening || this.mediaImageDeleting || this.mediaImageSharing) return;
    const direction = !Number.isFinite(offset) || offset === 0 ? 0 : (offset > 0 ? 1 : -1);
    if (direction === 0) return;
    const items = this.currentMediaKind === 'image' || this.currentMediaKind === 'video' ?
      this.VisualMediaViewerSequence() : this.MediaViewerSequence();
    if (items.length < 2) return;
    const currentIndex = items.findIndex((item: RemoteMediaItem) => item.path === this.currentMediaPath);
    if (currentIndex < 0) return;
    const nextIndex = (currentIndex + direction + items.length) % items.length;
    const next = items[nextIndex];
    this.PersistMediaPlaybackProgress();
    if (this.currentMediaKind === 'audio') await this.audioPlayer.release();
    if (this.currentMediaKind === 'video') this.videoController.stop();
    this.OpenRemoteMedia(next);
  }

  private CurrentRemoteMediaItem(): RemoteMediaItem | undefined {
    const remote = this.mediaItems.find((item: RemoteMediaItem) => item.path === this.currentMediaPath &&
      item.kind === this.currentMediaKind);
    if (remote !== undefined) return remote;
    const catalog = this.mediaCatalogItems.concat(this.mediaVideoDirectoryItems)
      .find((item: MediaCatalogItem) => `/media/${item.itemId}` === this.currentMediaPath);
    return catalog === undefined ? undefined : this.CatalogRemoteItem(catalog);
  }

  private CurrentImageSummary(): string {
    const images = this.MediaViewerSequence().filter((item: RemoteMediaItem) => item.kind === 'image');
    const index = images.findIndex((item: RemoteMediaItem) => item.path === this.currentMediaPath);
    const item = this.CurrentRemoteMediaItem();
    if (item === undefined) return index >= 0 ? `第 ${index + 1}/${images.length} 张` : '远程图片';
    const type = item.contentType.length > 0 ? item.contentType : '图片';
    const modified = item.lastModified.length > 0 ? ` · ${item.lastModified.replace('T', ' ')}` : '';
    return `${index >= 0 ? `第 ${index + 1}/${images.length} 张 · ` : ''}${this.FormatMediaSize(item.size)} · ${type}${modified}`;
  }

  private CurrentMediaSequenceLabel(): string {
    const items = this.currentMediaKind === 'image' || this.currentMediaKind === 'video' ?
      this.VisualMediaViewerSequence() : this.MediaViewerSequence();
    const index = items.findIndex((item: RemoteMediaItem) => item.path === this.currentMediaPath);
    if (index >= 0) return `${index + 1} / ${items.length}`;
    if (this.currentMediaKind === 'image') return '图片';
    if (this.currentMediaKind === 'video') return '视频';
    return '媒体';
  }

  private CurrentMediaDisplayName(): string {
    const name = this.currentMediaName.trim();
    const base = name.replace(/\.[A-Za-z0-9]{1,8}$/, '');
    if (/^[A-Fa-f0-9_-]{24,}$/.test(base)) {
      return this.currentMediaKind === 'video' ? '远程视频' : '远程图片';
    }
    return name.length > 0 ? name : (this.currentMediaKind === 'video' ? '远程视频' : '远程图片');
  }

  private CurrentMediaTagSummary(): string {
    const item = this.CurrentRemoteMediaItem();
    return (item?.tags ?? []).slice(0, 3).map((tag: RemoteMediaTag) => `#${tag.name}`).join('  ');
  }

  private MediaViewerDragProgress(): number {
    return Math.min(1, Math.abs(this.mediaViewerDragOffsetY) / 180);
  }

  private DragAdjacentMediaItem(): RemoteMediaItem | undefined {
    if (Math.abs(this.mediaViewerDragOffsetY) < 8) return undefined;
    const items = this.VisualMediaViewerSequence();
    const currentIndex = items.findIndex((item: RemoteMediaItem) => item.path === this.currentMediaPath);
    if (currentIndex < 0 || items.length < 2) return undefined;
    const direction = this.mediaViewerDragOffsetY < 0 ? 1 : -1;
    return items[(currentIndex + direction + items.length) % items.length];
  }

  private DragAdjacentMediaPreviewUri(): string {
    const item = this.DragAdjacentMediaItem();
    return item === undefined ? '' : this.BrowseMediaThumbnailUri(item.path);
  }

  private ToggleMediaViewerChrome(): void {
    if (this.mediaPlaybackError.length > 0 || this.mediaImageDeleteConfirm) return;
    this.mediaViewerChromeVisible = !this.mediaViewerChromeVisible;
    if (this.mediaViewerChromeVisible) this.ScheduleMediaViewerChromeAutoHide();
    else this.ClearMediaViewerChromeTimer();
  }

  private ScheduleMediaViewerChromeAutoHide(): void {
    this.ClearMediaViewerChromeTimer();
    if (!this.mediaViewerVisible || !this.mediaViewerChromeVisible) return;
    this.mediaViewerChromeTimerId = setTimeout((): void => {
      this.mediaViewerChromeTimerId = -1;
      if (!this.mediaViewerVisible || this.mediaPlaybackError.length > 0 || this.mediaImageDeleteConfirm) return;
      this.mediaViewerChromeVisible = false;
    }, 2600);
  }

  private ClearMediaViewerChromeTimer(): void {
    if (this.mediaViewerChromeTimerId >= 0) clearTimeout(this.mediaViewerChromeTimerId);
    this.mediaViewerChromeTimerId = -1;
  }

  private ToggleRemoteImageZoom(): void {
    if (this.imageScale > 1.05) {
      this.ResetRemoteImageTransform();
      return;
    }
    this.imageScale = 2;
    this.ApplyRemoteImageTranslation(0, 0);
    this.ClearMediaViewerChromeTimer();
    this.mediaViewerChromeVisible = false;
  }

  private ApplyMediaViewerSystemBars(immersive: boolean): void {
    const context = this.ApplicationContext();
    if (context === undefined) return;
    const background = immersive ? '#000000' : AppTheme.background;
    const foreground = immersive ? '#FFFFFFFF' : AppTheme.textPrimary;
    window.getLastWindow(context).then((mainWindow: window.Window) => {
      return mainWindow.setWindowSystemBarProperties({
        statusBarColor: background,
        statusBarContentColor: foreground,
        navigationBarColor: background,
        navigationBarContentColor: foreground
      });
    }).catch(() => {
      // 个别模拟器不支持系统栏着色时继续保留页面内的沉浸式布局。
    });
  }

  /** 让阅读背景覆盖系统状态栏与底部导航栏，离开阅读页后恢复应用主题。 */
  private ApplyReaderSystemBars(active: boolean): void {
    const context = this.ApplicationContext();
    if (context === undefined) return;
    const background = active ? this.ReaderBackgroundColor() : AppTheme.background;
    const foreground = active ? this.ReaderTextColor() : AppTheme.textPrimary;
    window.getLastWindow(context).then((mainWindow: window.Window) => {
      return mainWindow.setWindowSystemBarProperties({
        statusBarColor: background,
        statusBarContentColor: foreground,
        navigationBarColor: background,
        navigationBarContentColor: foreground
      });
    }).catch(() => {
      // 个别模拟器不支持动态系统栏着色时继续使用页面内阅读背景。
    });
  }

  private CurrentMediaIsFavorite(): boolean {
    return this.currentMediaPath.length > 0 &&
      this.IsMediaFavorite(this.selectedMediaSourceId, this.currentMediaPath);
  }

  private ToggleCurrentMediaFavorite(): void {
    const item = this.CurrentRemoteMediaItem();
    if (item !== undefined) this.ToggleMediaFavorite(item);
  }

  private BeginRemoteImagePinch(): void {
    this.imageGestureStartScale = this.imageScale;
  }

  private UpdateRemoteImagePinch(scale: number): void {
    this.imageScale = this.imageGesturePolicy.clampScale(this.imageGestureStartScale * scale);
    this.ApplyRemoteImageTranslation(this.imageOffsetX, this.imageOffsetY);
  }

  private BeginRemoteImagePan(): void {
    this.imageGestureStartX = this.imageOffsetX;
    this.imageGestureStartY = this.imageOffsetY;
  }

  private UpdateRemoteImagePan(offsetX: number, offsetY: number): void {
    if (this.imageScale <= 1) return;
    this.ApplyRemoteImageTranslation(this.imageGestureStartX + offsetX, this.imageGestureStartY + offsetY);
  }

  private EndRemoteImagePan(offsetX: number, offsetY: number): void {
    const direction = this.imageGesturePolicy.swipeDirection(offsetX, offsetY, this.imageScale);
    if (direction !== 0) {
      this.ChangeRemoteImage(direction);
      return;
    }
    this.FinishRemoteImageTransform();
  }

  private BeginRemoteViewerVerticalSwipe(): void {
    if (this.currentMediaKind !== 'image') return;
    const scale = this.currentMediaKind === 'image' ? this.imageScale : 1;
    if (scale > 1 || this.mediaOpening || this.mediaImageDeleting || this.mediaImageSharing) return;
    this.mediaViewerDragging = true;
    this.mediaViewerDragOffsetY = 0;
    this.mediaViewerChromeVisible = false;
    this.ClearMediaViewerChromeTimer();
  }

  private UpdateRemoteViewerVerticalSwipe(offsetX: number, offsetY: number): void {
    if (this.currentMediaKind !== 'image') return;
    if (!this.mediaViewerDragging || !Number.isFinite(offsetX) || !Number.isFinite(offsetY)) return;
    const scale = this.currentMediaKind === 'image' ? this.imageScale : 1;
    if (scale > 1 || Math.abs(offsetY) <= Math.abs(offsetX) * 1.05) {
      this.mediaViewerDragOffsetY = 0;
      return;
    }
    this.mediaViewerDragOffsetY = Math.max(-220, Math.min(220, offsetY));
  }

  private EndRemoteViewerVerticalSwipe(offsetX: number, offsetY: number): void {
    if (this.currentMediaKind !== 'image') return;
    const scale = this.currentMediaKind === 'image' ? this.imageScale : 1;
    const direction = this.imageGesturePolicy.verticalSwipeDirection(offsetX, offsetY, scale);
    this.mediaViewerDragging = false;
    this.mediaViewerDragOffsetY = 0;
    if (direction !== 0) this.ChangeRemoteMedia(direction);
  }

  private FinishRemoteImageTransform(): void {
    this.imageScale = this.imageGesturePolicy.clampScale(this.imageScale);
    this.ApplyRemoteImageTranslation(this.imageOffsetX, this.imageOffsetY);
  }

  private AdjustRemoteImageScale(delta: number): void {
    this.imageScale = this.imageGesturePolicy.clampScale(this.imageScale + delta);
    this.ApplyRemoteImageTranslation(this.imageOffsetX, this.imageOffsetY);
  }

  private ApplyRemoteImageTranslation(x: number, y: number): void {
    const translation = this.imageGesturePolicy.clampTranslation(x, y, this.imageScale);
    this.imageOffsetX = translation.x;
    this.imageOffsetY = translation.y;
  }

  private ResetRemoteImageTransform(): void {
    this.imageScale = 1;
    this.imageOffsetX = 0;
    this.imageOffsetY = 0;
    this.imageGestureStartScale = 1;
    this.imageGestureStartX = 0;
    this.imageGestureStartY = 0;
  }

  private ChangeRemoteAudio(offset: number): void {
    const direction = !Number.isFinite(offset) || offset === 0 ? 0 : (offset > 0 ? 1 : -1);
    if (direction === 0) return;
    if (this.mediaOpening) {
      // 系统媒体键和页面按钮共用单槽队列，只保留加载期间最新方向。
      this.queuedAudioNavigationDirection = direction;
      return;
    }
    const audios = this.audioQueuePolicy.queue(this.mediaItems);
    const currentIndex = audios.findIndex((item: RemoteMediaItem) => item.path === this.currentMediaPath);
    const nextIndex = this.audioQueuePolicy.nextIndex(audios.length, currentIndex, direction);
    if (nextIndex < 0 || audios[nextIndex].path === this.currentMediaPath) return;
    this.PersistMediaPlaybackProgress();
    this.OpenRemoteMedia(audios[nextIndex]);
  }

  private AudioQueueItems(): RemoteMediaItem[] {
    return this.audioQueuePolicy.visibleWindow(this.audioQueuePolicy.queue(this.mediaItems), this.currentMediaPath);
  }

  private CurrentAudioQueuePosition(): string {
    const queue = this.audioQueuePolicy.queue(this.mediaItems);
    const index = queue.findIndex((item: RemoteMediaItem) => item.path === this.currentMediaPath);
    return index < 0 ? `${queue.length} 首` : `${index + 1}/${queue.length}`;
  }

  private SeekVideoBy(offsetMs: number): void {
    const target = this.mediaSeekPolicy.offset(this.playerTimeMs, offsetMs, this.playerDurationMs);
    if (target !== undefined) this.SeekVideoTo(target);
  }

  private SeekVideoTo(timeMs: number): void {
    const target = this.mediaSeekPolicy.absolute(timeMs, this.playerDurationMs);
    if (target !== undefined) this.videoController.setCurrentTime(target / 1000);
  }

  private ToggleVideoFullscreen(): void {
    const fullscreen = !this.videoFullscreen;
    this.videoController.requestFullscreen(fullscreen);
    this.ApplyVideoOrientation(fullscreen);
  }

  private ApplyVideoOrientation(landscape: boolean): void {
    const context = this.ApplicationContext();
    if (context === undefined) return;
    window.getLastWindow(context).then((mainWindow: window.Window) =>
      mainWindow.setPreferredOrientation(landscape ? window.Orientation.LANDSCAPE : window.Orientation.UNSPECIFIED))
      .catch(() => {
        this.videoStatus = '屏幕方向切换失败';
      });
  }

  private SeekAudioBy(offsetMs: number): void {
    const target = this.mediaSeekPolicy.offset(this.playerTimeMs, offsetMs, this.playerDurationMs);
    if (target !== undefined) this.audioPlayer.seek(target);
  }

  private CycleMediaPlaybackRate(audio: boolean): void {
    const rates = [0.75, 1, 1.25, 1.5, 1.75, 2];
    const current = rates.indexOf(this.mediaPlaybackRate);
    this.mediaPlaybackRate = rates[(current + 1) % rates.length];
    if (audio) this.audioPlayer.setPlaybackSpeed(this.mediaPlaybackRate);
  }

  private CycleAudioSleepTimer(): void {
    const choices = [0, 15, 30, 60];
    const current = choices.indexOf(this.audioSleepMinutes);
    const minutes = choices[(current + 1) % choices.length];
    this.ClearAudioSleepTimer();
    this.audioSleepMinutes = minutes;
    if (minutes === 0) return;
    this.audioSleepTimerId = setTimeout(() => {
      this.audioSleepTimerId = -1;
      this.audioSleepMinutes = 0;
      this.audioPlayer.release();
      this.playerState = 'idle';
    }, minutes * 60 * 1000);
  }

  private ClearAudioSleepTimer(): void {
    if (this.audioSleepTimerId >= 0) clearTimeout(this.audioSleepTimerId);
    this.audioSleepTimerId = -1;
    this.audioSleepMinutes = 0;
  }

  private FormatDuration(timeMs: number): string {
    const seconds = Math.max(0, Math.floor(timeMs / 1000));
    const minutes = Math.floor(seconds / 60);
    const remain = seconds % 60;
    return `${minutes}:${remain < 10 ? '0' : ''}${remain}`;
  }
}
