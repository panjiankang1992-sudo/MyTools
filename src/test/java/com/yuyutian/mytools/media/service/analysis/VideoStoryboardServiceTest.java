package com.yuyutian.mytools.media.service.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoStoryboardServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldGenerateTwelveFramesAndThumbnail() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        MediaCommandRunner runner = new MediaCommandRunner() {
            @Override
            public String runForOutput(List<String> command, Duration timeout, int maxOutputBytes) {
                return "";
            }

            @Override
            public void runForFile(List<String> command, Duration timeout, Path output) throws java.io.IOException {
                commands.add(command);
                Files.write(output, new byte[]{1, 2, 3});
            }
        };
        VideoStoryboardService service = new VideoStoryboardService(
                runner, new MediaPackageFileWriter(new ObjectMapper()));
        Path video = Files.write(temporaryDirectory.resolve("video.mp4"), new byte[]{1, 2, 3});

        List<Path> result = service.generate(video, temporaryDirectory, 130_000L);

        assertThat(result).hasSize(12).allMatch(Files::isRegularFile);
        assertThat(commands).hasSize(12);
        assertThat(temporaryDirectory.resolve("thumbnail.jpg")).exists();
        assertThat(result.getFirst().getFileName().toString()).isEqualTo("01_000000010000.jpg");
    }

    @Test
    void shouldSeekForwardWhenGeneratedFrameIsBlack() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        MediaCommandRunner runner = new MediaCommandRunner() {
            private int invocation;

            @Override
            public String runForOutput(List<String> command, Duration timeout, int maxOutputBytes) {
                return "";
            }

            @Override
            public void runForFile(List<String> command, Duration timeout, Path output) throws java.io.IOException {
                commands.add(command);
                BufferedImage image = new BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                graphics.setColor(invocation++ % 2 == 0 ? Color.BLACK : Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.dispose();
                ImageIO.write(image, "jpg", output.toFile());
            }
        };
        VideoStoryboardService service = new VideoStoryboardService(
                runner, new MediaPackageFileWriter(new ObjectMapper()));
        Path video = Files.write(temporaryDirectory.resolve("black-intro.mp4"), new byte[]{1, 2, 3});

        List<Path> result = service.generate(video, temporaryDirectory, 130_000L);

        assertThat(result).hasSize(12);
        assertThat(commands.size()).isGreaterThan(12);
        assertThat(commands.get(1)).contains("13.900");
    }
}
