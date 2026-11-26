package com.yuyutian.mytools.news.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class OfflineArticleExtractorTest {
    @Test void offlineProcessUsesLiteralArgumentsAndFallsBackOnFailureOrTimeout(@TempDir Path directory) throws Exception {
        Path script = directory.resolve("extractor.py");
        String previousScript = System.getProperty("news.contentExtractor"), previousPython = System.getProperty("news.contentPython");
        System.setProperty("news.contentExtractor", script.toString());
        System.setProperty("news.contentPython", "python3");
        try {
            Files.writeString(script, "import json,sys\nfrom pathlib import Path\nPath(sys.argv[2]).write_text(json.dumps({'text':Path(sys.argv[1]).read_text()+' '+sys.argv[3]}))\n");
            String url = "https://example.com/a?x=$(uname)&y=test";
            assertThat(OfflineArticleExtractor.extract("sample", url, Long.MAX_VALUE)).isEqualTo("sample " + url);
            assertThat(OfflineArticleExtractor.extract("sample", url, 0)).isEmpty();
            Files.writeString(script, "raise RuntimeError('failed')\n");
            assertThat(OfflineArticleExtractor.extract("sample", url, Long.MAX_VALUE)).isEmpty();
            Files.writeString(script, "import time\ntime.sleep(20)\n");
            long start = System.nanoTime();
            assertThat(OfflineArticleExtractor.extract("sample", url, start + Duration.ofMillis(200).toNanos())).isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        } finally {
            if (previousScript == null) System.clearProperty("news.contentExtractor"); else System.setProperty("news.contentExtractor", previousScript);
            if (previousPython == null) System.clearProperty("news.contentPython"); else System.setProperty("news.contentPython", previousPython);
        }
    }
}
