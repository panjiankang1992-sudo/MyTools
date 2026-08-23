package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.MarkerStateRequest;
import com.yuyutian.mytools.reader.model.ProgressStateRequest;
import com.yuyutian.mytools.reader.model.ShelfStateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reader_state;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class ReaderStateServiceTest {

    @Autowired
    private ReaderStateService service;

    @Test
    void shouldSynchronizeShelfProgressAndMarkersWithOptimisticVersions() {
        var shelf = service.saveShelf(new ShelfStateRequest(81L, "book-a", Map.of("title", "Book A"),
                false, null));
        var progress = service.saveProgress(new ProgressStateRequest(81L, "book-a", 2, "chapter://2",
                Map.of("offset", 20), false, null));
        UUID markerId = UUID.randomUUID();
        var marker = service.saveMarker(new MarkerStateRequest(markerId, 81L, "book-a", "BOOKMARK", 2,
                Map.of("offset", 21), "note", false, null));

        var updatedShelf = service.saveShelf(new ShelfStateRequest(81L, "book-a", Map.of("title", "Renamed"),
                false, shelf.version()));
        var deletedProgress = service.saveProgress(new ProgressStateRequest(81L, "book-a", 2, "chapter://2",
                Map.of("offset", 20), true, progress.version()));
        var updatedMarker = service.saveMarker(new MarkerStateRequest(markerId, 81L, "book-a", "NOTE", 3,
                Map.of("offset", 30), "changed", false, marker.version()));

        assertThat(updatedShelf.version()).isEqualTo(2);
        assertThat(deletedProgress.deleted()).isTrue();
        assertThat(updatedMarker.version()).isEqualTo(2);
        assertThat(service.progress(81L, false)).isEmpty();
        assertThat(service.progress(81L, true)).hasSize(1);
        assertThat(service.markers(81L, false)).extracting("markerType").containsExactly("NOTE");
        assertThatThrownBy(() -> service.saveShelf(new ShelfStateRequest(81L, "book-a", Map.of(),
                false, shelf.version()))).isInstanceOf(ReaderStateConflictException.class);
        assertThatThrownBy(() -> service.saveProgress(new ProgressStateRequest(81L, "missing", 0, null,
                Map.of(), false, null))).isInstanceOf(ReaderStateNotFoundException.class);
    }

    @Test
    void shouldRejectDuplicateCreateWithoutOverwritingExistingState() {
        service.saveShelf(new ShelfStateRequest(82L, "book-a", Map.of("title", "Original"), false, null));

        assertThatThrownBy(() -> service.saveShelf(new ShelfStateRequest(82L, "book-a",
                Map.of("title", "Replacement"), false, null)))
                .isInstanceOf(ReaderStateConflictException.class);
        assertThat(service.shelves(82L, false).getFirst().metadata()).containsEntry("title", "Original");
    }
}
