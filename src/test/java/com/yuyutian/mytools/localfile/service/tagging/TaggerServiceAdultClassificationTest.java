package com.yuyutian.mytools.localfile.service.tagging;

import com.yuyutian.mytools.localfile.entity.FileTag;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.tagging.impl.TaggerServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaggerServiceAdultClassificationTest {

    @Test
    void shouldReplaceAdultTagWithModelConclusion(@TempDir Path tempDirectory) throws Exception {
        Path content = tempDirectory.resolve("book.txt");
        Files.writeString(content, "sample");
        LocalFile file = new LocalFile();
        file.setId(42L);
        file.setFilename("book.txt");
        file.setFilePath(content.toString());
        file.setMimeType("text/plain");
        file.setExtension("txt");

        LocalFileMapper localFileMapper = mock(LocalFileMapper.class);
        FileTagMapper fileTagMapper = mock(FileTagMapper.class);
        TaggerClient taggerClient = mock(TaggerClient.class);
        when(localFileMapper.selectAdultClassificationCandidates(10)).thenReturn(List.of(file));
        when(taggerClient.classifyAdultText(anyString(), anyString(), anyString()))
                .thenReturn(new TaggerClient.AdultResult(true, 0.91));
        TaggerServiceImpl service = new TaggerServiceImpl(localFileMapper, fileTagMapper, taggerClient);

        assertThat(service.processAdultClassifications(10)).isEqualTo(1);
        verify(localFileMapper).updateAdultClassification(42L, 1, true, 0.91);
        verify(fileTagMapper).deleteAdultClassificationByFileId(42L);
        ArgumentCaptor<FileTag> tagCaptor = ArgumentCaptor.forClass(FileTag.class);
        verify(fileTagMapper).insert(tagCaptor.capture());
        assertThat(tagCaptor.getValue().getTagName()).isEqualTo("R18-是");
        assertThat(tagCaptor.getValue().getTagType()).isEqualTo("adult");
    }
}
