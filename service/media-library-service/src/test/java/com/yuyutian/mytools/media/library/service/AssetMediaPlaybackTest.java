package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.model.MediaModels.MediaView;
import com.yuyutian.mytools.media.library.repository.MediaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 验证无旧文件关联的下载视频走存储范围请求，且不会越权或返回错位字节。 */
class AssetMediaPlaybackTest {
    private final UUID id = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private final MediaRepository repo = mock(MediaRepository.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer upstream = MockRestServiceServer.bindTo(builder).build();
    private final DerivedThumbnailContentService assets = new DerivedThumbnailContentService(repo, builder,
            "http://registry", "fixture", "http://storage", "fixture");
    private final LegacyMediaContentService service = new LegacyMediaContentService(repo, null, assets, "fixture");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private void media(String status) {
        when(repo.view(id)).thenReturn(Optional.of(new MediaView(id, 7, asset, "video.mp4", "video/mp4", 10,
                "fixturehash", status, 1, List.of(), "folder", "folder")));
    }

    private void location() {
        upstream.expect(requestTo("http://registry/internal/v1/assets/" + asset))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fixture"))
                .andRespond(withSuccess("""
                        {"sizeBytes":10,"contentSha256":"fixturehash","locations":[
                        {"providerType":"STORAGE_GATEWAY","availability":"AVAILABLE","storageUri":"storage://managed/a%2Bb%20c.mp4"}]}
                        """, MediaType.APPLICATION_JSON));
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL,0,9,false", "bytes=2-4,2,4,false", "bytes=7-,7,9,false",
            "bytes=-3,7,9,false", "bytes=8-999,8,9,false", "bytes=2-4,2,4,true", "NULL,0,9,true"}, nullValues="NULL")
    void sendsExactRequestedBytes(String range, int first, int last, boolean head) {
        media("READY");
        location();
        byte[] bytes = {0,1,2,3,4,5,6,7,8,9};
        var request = upstream.expect(requestTo("http://storage/api/internal/v1/storage/objects/content?rootName=managed&path=a%2Bb%20c.mp4"));
        var subset = Arrays.copyOfRange(bytes, first, last + 1);
        if (head || range != null) {
            int start = head ? 0 : first;
            int end = head ? 0 : last;
            request.andExpect(header(HttpHeaders.RANGE, "bytes=" + start + "-" + end))
                    .andRespond(withStatus(HttpStatus.PARTIAL_CONTENT)
                            .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/10")
                            .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(end-start+1))
                            .body(Arrays.copyOfRange(bytes, start, end+1)));
        } else {
            request.andExpect(headerDoesNotExist(HttpHeaders.RANGE)).andRespond(withSuccess()
                    .header(HttpHeaders.CONTENT_LENGTH,"10").body(bytes));
        }
        assertThat(service.writeManagedOriginal(7,id,range,head,response)).isTrue();
        assertThat(response.getStatus()).isEqualTo(range == null ? 200 : 206);
        assertThat(response.getContentType()).isEqualTo("video/mp4");
        assertThat(response.getContentLengthLong()).isEqualTo(subset.length);
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo(range == null ? null : "bytes " + first + "-" + last + "/10");
        assertThat(response.getContentAsByteArray()).containsExactly(head ? new byte[0] : subset);
        upstream.verify();
    }

    @Test
    void refusesOutOfBoundsWithoutReadingStorage() {
        media("READY");
        location();
        service.writeManagedOriginal(7,id,"bytes=10-",false,response);
        assertThat(response.getStatus()).isEqualTo(416);
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */10");
        upstream.verify();
    }

    @Test
    void refusesIgnoredRangeAndKeepsBodyEmpty() {
        media("READY");
        location();
        upstream.expect(header(HttpHeaders.RANGE,"bytes=2-4")).andRespond(withSuccess()
                .header(HttpHeaders.CONTENT_LENGTH,"10").body(new byte[10]));
        assertThatThrownBy(() -> service.writeManagedOriginal(7,id,"bytes=2-4",false,response))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void rejectsForeignAndMissingItemsBeforeOpeningAssetAndPreservesLegacyPlayback() {
        media("READY");
        assertThatThrownBy(() -> service.writeManagedOriginal(8,id,null,false,response))
                .isInstanceOf(ResponseStatusException.class);
        when(repo.legacyFileId(7,id)).thenReturn(Optional.of(42L));
        assertThat(service.writeManagedOriginal(7,id,null,false,response)).isFalse();
        media("MISSING");
        assertThatThrownBy(() -> service.writeManagedOriginal(7,id,null,false,response))
                .isInstanceOf(ResponseStatusException.class);
        upstream.verify();
    }
}
