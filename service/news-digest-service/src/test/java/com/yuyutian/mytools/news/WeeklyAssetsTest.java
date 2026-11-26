package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.config.WeeklyProperties;
import com.yuyutian.mytools.news.service.WeeklyAssets;
import com.yuyutian.mytools.news.common.NewsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class WeeklyAssetsTest {
    @TempDir Path root;

    @Test void onlyBoundedOriginalImagesAreStoredAndJobDirectoriesAreIsolated() throws Exception {
        WeeklyAssets assets=new WeeklyAssets(new WeeklyProperties(true,root.toString()));
        UUID job=UUID.randomUUID();
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output);
        String id=assets.put(job,output.toByteArray());
        assertThat(assets.put(job,output.toByteArray())).isEqualTo(id);
        assertThat(assets.read(job,id).mime()).isEqualTo("image/png");
        assertThat(java.util.Base64.getDecoder().decode(assets.read(job,id).base64())).isEqualTo(output.toByteArray());
        assertThatThrownBy(() -> assets.read(UUID.randomUUID(),id)).isInstanceOf(NewsException.class);
        assertThatThrownBy(() -> assets.read(job,"../"+id)).isInstanceOf(NewsException.class);
        assertThatThrownBy(() -> assets.put(job,"<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(NewsException.class);
        assertThatThrownBy(() -> assets.put(job,new byte[1_048_577])).isInstanceOf(NewsException.class);
        // 同名符号链接不能把读取范围扩展到其他目录。
        Files.delete(root.resolve(job.toString()).resolve(id));
        Path outside=Files.createTempFile(root,"outside-",".png"); Files.write(outside,output.toByteArray());
        Files.createSymbolicLink(root.resolve(job.toString()).resolve(id),outside);
        assertThatThrownBy(() -> assets.read(job,id)).isInstanceOf(NewsException.class);
    }
}
