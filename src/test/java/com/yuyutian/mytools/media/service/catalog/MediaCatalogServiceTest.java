package com.yuyutian.mytools.media.service.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.localfile.entity.FileTag;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaCatalogServiceTest {

    @TempDir
    Path root;

    private LocalDirectoryMapper directoryMapper;
    private LocalFileMapper fileMapper;
    private FileTagMapper tagMapper;
    private MediaCatalogService service;
    private LocalFile video;
    private LocalFile image;

    @BeforeEach
    void setUp() throws Exception {
        directoryMapper = mock(LocalDirectoryMapper.class);
        fileMapper = mock(LocalFileMapper.class);
        tagMapper = mock(FileTagMapper.class);
        service = new MediaCatalogService(directoryMapper, fileMapper, tagMapper, new ObjectMapper());

        Path packageDirectory = Files.createDirectories(root.resolve("20260815_120000_trip"));
        Path videoPath = Files.writeString(packageDirectory.resolve("trip.mp4"), "video");
        Files.writeString(packageDirectory.resolve(".ready"), "");
        Files.createDirectories(packageDirectory.resolve("storyboard"));
        Files.write(packageDirectory.resolve("storyboard/01_000000001000.jpg"), new byte[]{1, 2, 3});
        Files.writeString(packageDirectory.resolve("description.md"), "A detailed video description.");
        Files.writeString(packageDirectory.resolve("metadata.json"), """
                {"analysisStatus":"READY","summary":"Trip summary","descriptionFile":"description.md",
                 "videoMetadata":{"durationMs":120000,"format":"mp4","videoCodec":"h264",
                 "audioCodec":"aac","width":1920,"height":1080},
                 "storyboardFiles":["storyboard/01_000000001000.jpg"]}
                """);
        Path imagePath = Files.write(packageDirectory.resolve("cover.jpg"), new byte[]{1, 2, 3});
        video = file(11L, videoPath, "video/mp4", 5L, LocalDateTime.of(2026, 8, 15, 12, 0));
        image = file(12L, imagePath, "image/jpeg", 3L, LocalDateTime.of(2026, 8, 15, 13, 0));

        LocalDirectory directory = new LocalDirectory();
        directory.setId(1L);
        directory.setDirectoryName("Media");
        directory.setDirectoryPath(root.toString());
        directory.setDirectoryType("LARGE_MEDIA");
        when(directoryMapper.selectAll()).thenReturn(List.of(directory));
        when(fileMapper.selectActiveFilesByDirectory(root.toString())).thenReturn(List.of(video, image));
        FileTag tag1 = tag(11L, "travel");
        FileTag tag2 = tag(12L, "travel");
        when(tagMapper.selectByFileIds(List.of(11L, 12L))).thenReturn(List.of(tag1, tag2));
    }

    @Test
    void shouldBuildAccurateFiltersAndVideoTopItems() {
        var galleryFilters = service.filters("");
        var filters = service.filters("", "video", false);
        var directories = service.videoDirectories("", "travel", "");

        assertThat(galleryFilters.directories()).isEmpty();
        assertThat(filters.directories()).singleElement().satisfies(value -> {
            assertThat(value.fileCount()).isEqualTo(2L);
            assertThat(value.name()).isEqualTo("20260815_120000_trip");
        });
        assertThat(filters.tags()).singleElement().satisfies(value -> assertThat(value.fileCount()).isEqualTo(2L));
        assertThat(directories.list()).singleElement().satisfies(value -> {
            assertThat(value.topItems()).extracting(item -> item.name()).containsExactly("cover.jpg", "trip.mp4");
            assertThat(value.fileCount()).isEqualTo(2L);
        });
    }

    @Test
    void shouldGroupLargeMediaByRootLevelDirectoryOnly() throws Exception {
        Path nestedDirectory = Files.createDirectories(root.resolve("20260815_120000_trip/extras/images"));
        LocalFile nestedImage = file(13L, Files.write(nestedDirectory.resolve("scene.jpg"), new byte[]{1, 2, 3}),
                "image/jpeg", 3L, LocalDateTime.of(2026, 8, 15, 14, 0));
        when(fileMapper.selectActiveFilesByDirectory(root.toString())).thenReturn(List.of(video, image, nestedImage));
        when(tagMapper.selectByFileIds(List.of(11L, 12L, 13L))).thenReturn(List.of());

        var filters = service.filters("", "video", false);
        var directories = service.videoDirectories("", "", "");

        assertThat(filters.directories()).singleElement().satisfies(value -> {
            assertThat(value.name()).isEqualTo("20260815_120000_trip");
            assertThat(value.fileCount()).isEqualTo(3L);
        });
        assertThat(directories.list()).singleElement().satisfies(value -> {
            assertThat(value.name()).isEqualTo("20260815_120000_trip");
            assertThat(value.fileCount()).isEqualTo(3L);
        });
    }

    @Test
    void shouldDisplayFullRelativeDirectoryForMultimedia() throws Exception {
        Path multimediaRoot = Files.createDirectories(root.resolve("media"));
        Path mediaDirectory = Files.createDirectories(multimediaRoot.resolve("202608/20260816/trip"));
        LocalFile multimediaVideo = file(21L, Files.writeString(mediaDirectory.resolve("clip.mp4"), "video"),
                "video/mp4", 5L, LocalDateTime.of(2026, 8, 16, 12, 0));
        LocalDirectory multimedia = new LocalDirectory();
        multimedia.setId(2L);
        multimedia.setDirectoryName("Media");
        multimedia.setDirectoryPath(multimediaRoot.toString());
        multimedia.setDirectoryType("MULTIMEDIA");
        when(directoryMapper.selectAll()).thenReturn(List.of(multimedia));
        when(fileMapper.selectActiveFilesByDirectory(multimediaRoot.toString())).thenReturn(List.of(multimediaVideo));
        when(tagMapper.selectByFileIds(List.of(21L))).thenReturn(List.of());

        var galleryFilters = service.filters("", "gallery", false);
        var videoFilters = service.filters("", "video", false);
        var gallery = service.gallery("", "", "", 1, 30);
        var directories = service.videoDirectories("", "", "");

        assertThat(galleryFilters.directories()).singleElement()
                .satisfies(value -> assertThat(value.name()).isEqualTo("202608/20260816/trip"));
        assertThat(gallery.list()).extracting(value -> value.name()).containsExactly("clip.mp4");
        assertThat(videoFilters.directories()).isEmpty();
        assertThat(directories.list()).isEmpty();
    }

    @Test
    void shouldSortImageDirectoriesByNameDescending() throws Exception {
        Path olderDirectory = Files.createDirectories(root.resolve("202607/20260731/older"));
        Path newerDirectory = Files.createDirectories(root.resolve("202608/20260816/newer"));
        LocalFile older = file(31L, Files.write(olderDirectory.resolve("old.jpg"), new byte[]{1}),
                "image/jpeg", 1L, LocalDateTime.of(2026, 8, 16, 15, 0));
        LocalFile newer = file(32L, Files.write(newerDirectory.resolve("new.jpg"), new byte[]{2}),
                "image/jpeg", 1L, LocalDateTime.of(2026, 7, 31, 10, 0));
        LocalDirectory multimedia = new LocalDirectory();
        multimedia.setId(3L);
        multimedia.setDirectoryName("Media");
        multimedia.setDirectoryPath(root.toString());
        multimedia.setDirectoryType("MULTIMEDIA");
        when(directoryMapper.selectAll()).thenReturn(List.of(multimedia));
        when(fileMapper.selectActiveFilesByDirectory(root.toString())).thenReturn(List.of(older, newer));
        when(tagMapper.selectByFileIds(List.of(31L, 32L))).thenReturn(List.of());

        assertThat(service.filters("").directories()).extracting(value -> value.name())
                .containsExactly("202608/20260816/newer", "202607/20260731/older");
        assertThat(service.gallery("", "", "", 1, 1).list()).extracting(value -> value.name())
                .containsExactly("new.jpg");
        assertThat(service.gallery("", "", "", 2, 1).list()).extracting(value -> value.name())
                .containsExactly("old.jpg");
    }

    @Test
    void shouldReadBoundedVideoPackageDetailAndStoryboard() {
        var detail = service.videoDetail(11L);

        assertThat(detail.summary()).isEqualTo("Trip summary");
        assertThat(detail.durationMs()).isEqualTo(120000L);
        assertThat(detail.storyboard()).singleElement().satisfies(frame -> {
            assertThat(frame.sequence()).isEqualTo(1);
            assertThat(frame.timestampMs()).isEqualTo(1000L);
        });
        assertThat(service.storyboardPath(11L, 1)).hasFileName("01_000000001000.jpg");
    }

    @Test
    void shouldReadLegacyStoryboardFileNameWithoutDirectoryPrefix() throws Exception {
        Path metadata = Path.of(video.getFilePath()).getParent().resolve("metadata.json");
        Files.writeString(metadata, """
                {"analysisStatus":"READY","storyboardFiles":["01_000000001000.jpg"]}
                """);

        assertThat(service.videoDetail(11L).storyboard()).hasSize(1);
        assertThat(service.storyboardPath(11L, 1)).hasFileName("01_000000001000.jpg");
    }

    @Test
    void shouldDeduplicateAndBoundItemTags() {
        List<FileTag> tags = new ArrayList<>();
        for (int index = 0; index < 70; index++) tags.add(tag(11L, "tag-" + index));
        tags.add(tag(11L, "tag-0"));
        tags.add(tag(11L, " "));
        when(tagMapper.selectByFileIds(List.of(11L, 12L))).thenReturn(tags);

        var items = service.videoDirectories("", "", "").list().getFirst().topItems();

        assertThat(items).filteredOn(item -> item.itemId().equals("11")).singleElement()
                .satisfies(item -> assertThat(item.tags()).hasSize(64).doesNotHaveDuplicates());
    }

    @Test
    void shouldKeepGalleryAndVideoDirectoriesSeparate() throws Exception {
        Path galleryRoot = Files.createDirectories(root.resolve("media-root"));
        Path galleryDirectory = Files.createDirectories(galleryRoot.resolve("202608/20260816/gallery"));
        LocalFile galleryImage = file(13L, Files.write(galleryDirectory.resolve("photo.jpg"), new byte[]{4, 5, 6}),
                "image/jpeg", 3L, LocalDateTime.of(2026, 8, 16, 9, 0));
        LocalDirectory largeMedia = directoryMapper.selectAll().getFirst();
        LocalDirectory multimedia = new LocalDirectory();
        multimedia.setId(2L);
        multimedia.setDirectoryName("Media");
        multimedia.setDirectoryPath(galleryRoot.toString());
        multimedia.setDirectoryType("MULTIMEDIA");
        when(directoryMapper.selectAll()).thenReturn(List.of(largeMedia, multimedia));
        when(fileMapper.selectActiveFilesByDirectory(root.toString())).thenReturn(List.of(video, image));
        when(fileMapper.selectActiveFilesByDirectory(galleryRoot.toString())).thenReturn(List.of(galleryImage));
        when(tagMapper.selectByFileIds(List.of(11L, 12L, 13L))).thenReturn(List.of());

        var galleryFilters = service.filters("", "gallery", false);
        var videoFilters = service.filters("", "video", false);
        var gallery = service.gallery("", "", "", 1, 30);

        assertThat(galleryFilters.directories()).extracting(value -> value.name())
                .containsExactly("202608/20260816/gallery");
        assertThat(videoFilters.directories()).extracting(value -> value.name())
                .containsExactly("20260815_120000_trip");
        assertThat(gallery.list()).extracting(item -> item.name()).containsExactly("photo.jpg");
    }

    private LocalFile file(Long id, Path path, String mime, long size, LocalDateTime updatedAt) {
        LocalFile file = new LocalFile();
        file.setId(id);
        file.setFilename(path.getFileName().toString());
        file.setFilePath(path.toString());
        file.setMimeType(mime);
        file.setExtension(path.getFileName().toString().substring(path.getFileName().toString().lastIndexOf('.') + 1));
        file.setFileSize(size);
        file.setUpdateTime(updatedAt);
        file.setDeleted(false);
        return file;
    }

    private FileTag tag(Long fileId, String name) {
        FileTag tag = new FileTag();
        tag.setFileId(fileId);
        tag.setTagName(name);
        return tag;
    }
}
