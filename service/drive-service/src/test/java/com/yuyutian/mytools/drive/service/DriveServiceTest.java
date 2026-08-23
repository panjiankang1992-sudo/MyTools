package com.yuyutian.mytools.drive.service;

import com.yuyutian.mytools.drive.model.DriveModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Drive 索引事务测试。 */
@SpringBootTest
class DriveServiceTest {
    @Autowired private DriveService service;

    @Test
    void shouldResumeBatchesAndDeleteStaleItemsOnlyAfterCompletion() {
        AccountView account=service.register(new RegisterAccountRequest(7L,"legacy-1","Primary","RCLONE",
            "secret://drive/legacy-1","primary",true,true));
        UUID firstRun=UUID.randomUUID();
        service.ingest(account.id(),new IndexBatchRequest(firstRun,"batch-1","cursor-1",false,List.of(
            item("old.txt","",3))));
        IndexBatchView duplicate=service.ingest(account.id(),new IndexBatchRequest(firstRun,"batch-1","cursor-1",false,List.of(
            item("old.txt","",3))));
        assertThat(duplicate.acceptedItems()).isZero();
        service.ingest(account.id(),new IndexBatchRequest(firstRun,"batch-2",null,true,List.of(item("keep.txt","",4))));
        assertThat(service.list(account.id(),7L," ")).extracting(ItemView::displayName).containsExactly("keep.txt","old.txt");

        UUID secondRun=UUID.randomUUID();
        service.ingest(account.id(),new IndexBatchRequest(secondRun,"batch-1",null,true,List.of(item("keep.txt","",5))));
        assertThat(service.list(account.id(),7L,"")).extracting(ItemView::displayName).containsExactly("keep.txt");
        assertThatThrownBy(() -> service.list(account.id(),8L,"")).isInstanceOf(IllegalArgumentException.class);
    }

    private IndexItem item(String path,String parent,long size) {
        return new IndexItem(path,path,parent,path,"text/plain",size,false,Instant.parse("2026-01-01T00:00:00Z"),null);
    }
}
