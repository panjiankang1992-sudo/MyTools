package com.yuyutian.mytools.media.service.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.media.model.VideoMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoProbeServiceTest {

    @Test
    void shouldParseVideoAndAudioMetadata() throws Exception {
        MediaCommandRunner runner = mock(MediaCommandRunner.class);
        when(runner.runForOutput(any(), any(), anyInt())).thenReturn("""
                {"format":{"duration":"123.456","format_name":"mov,mp4","bit_rate":"8000000"},
                 "streams":[
                   {"codec_type":"video","codec_name":"h264","width":1920,"height":1080,"avg_frame_rate":"30000/1001"},
                   {"codec_type":"audio","codec_name":"aac"}]}
                """);
        VideoProbeService service = new VideoProbeService(runner, new ObjectMapper());

        VideoMetadata result = service.probe(Path.of("video.mp4"));

        assertThat(result.durationMs()).isEqualTo(123456L);
        assertThat(result.format()).isEqualTo("mov,mp4");
        assertThat(result.videoCodec()).isEqualTo("h264");
        assertThat(result.audioCodec()).isEqualTo("aac");
        assertThat(result.width()).isEqualTo(1920);
        assertThat(result.height()).isEqualTo(1080);
        assertThat(result.frameRate()).isCloseTo(29.97D, within(0.01D));
        assertThat(result.bitRate()).isEqualTo(8_000_000L);
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
