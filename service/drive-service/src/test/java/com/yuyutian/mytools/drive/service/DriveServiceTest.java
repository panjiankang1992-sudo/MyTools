package com.yuyutian.mytools.drive.service;

import com.yuyutian.mytools.drive.model.DriveModels.*;
import com.yuyutian.mytools.drive.repository.DriveRepository;
import com.yuyutian.mytools.drive.connector.StorageGatewayConnector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Drive 索引事务测试。 */
@SpringBootTest
class DriveServiceTest {
    @Autowired private DriveService service;
    @Autowired private DriveRepository repository;
    @MockBean private DriveTaskSchedulerClient schedulerClient;
    @MockBean private StorageGatewayConnector storageConnector;

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
        assertThat(service.ingest(account.id(),new IndexBatchRequest(firstRun,"batch-1","cursor-1",false,List.of(
            item("old.txt","",3)))).acceptedItems()).isZero();
        assertThat(service.list(account.id(),7L," ")).extracting(ItemView::displayName).containsExactly("keep.txt","old.txt");

        UUID secondRun=UUID.randomUUID();
        service.ingest(account.id(),new IndexBatchRequest(secondRun,"batch-1",null,true,List.of(item("keep.txt","",5))));
        assertThat(service.list(account.id(),7L,"")).extracting(ItemView::displayName).containsExactly("keep.txt");
        assertThatThrownBy(() -> service.list(account.id(),8L,"")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowNewRunAfterFailureHook() {
        AccountView account=service.register(new RegisterAccountRequest(9L,"legacy-2","Backup","RCLONE",
            "secret://drive/legacy-2","backup",true,true));
        UUID failedRun=UUID.randomUUID();
        service.ingest(account.id(),new IndexBatchRequest(failedRun,"batch-1",null,false,List.of(item("partial.txt","",1))));
        service.finishRun(account.id(),failedRun,"FAILED");
        UUID recoveryRun=UUID.randomUUID();
        assertThat(service.ingest(account.id(),new IndexBatchRequest(recoveryRun,"complete",null,true,List.of()))
            .status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void shouldExposeOnlySanitizedStorageMigrationFields() {
        AccountView account=service.register(new RegisterAccountRequest(11L,"external-private","Private Display","RCLONE",
            "secret://drive/migration-safe","migration_safe",true,true));

        StorageMigrationAccount migrated=repository.listStorageMigrationAccounts(null,500).items().stream()
            .filter(item -> item.id().equals(account.id())).findFirst().orElseThrow();

        assertThat(migrated.remoteKey()).isEqualTo("migration_safe");
        assertThat(migrated.providerSecretRef()).isEqualTo("secret://drive/migration-safe");
        assertThat(migrated.toString()).doesNotContain("Private Display","external-private");
    }

    @Test
    void shouldProduceTheSharedReconciliationGoldenDigest() {
        AccountView account=service.register(new RegisterAccountRequest(12L,"digest-account","Digest","RCLONE",
            "secret://drive/digest","digest_remote",true,true));
        UUID runId=UUID.randomUUID();
        service.ingest(account.id(),new IndexBatchRequest(runId,"complete",null,true,List.of(item("a.txt","",3))));

        assertThat(repository.indexDigest(account.id()).contentSha256())
            .isEqualTo("8501ff9beb116985f2ad48e3e4417e85c1f0121b8498a344a7fb307b51314879");
    }

    @Test
    void shouldListOnlyAccountsOwnedByTheRequestedOwner() {
        AccountView first=service.register(new RegisterAccountRequest(21L,"owned-a","A Drive","RCLONE",
            "secret://drive/owned-a","owned_a",true,true));
        AccountView second=service.register(new RegisterAccountRequest(22L,"owned-b","B Drive","RCLONE",
            "secret://drive/owned-b","owned_b",true,true));

        assertThat(service.listAccounts(21L)).extracting(AccountView::id).contains(first.id()).doesNotContain(second.id());
    }

    @Test
    void shouldCreateQueryAndCancelOwnerBoundIndexOperation() {
        AccountView account=service.register(new RegisterAccountRequest(13L,"refresh-account","Refresh","RCLONE",
            "secret://drive/refresh","refresh_remote",true,true));
        UUID taskId=UUID.randomUUID();
        when(schedulerClient.createIndexTask(any(),eq(account.id()),startsWith("drive-index:"))).thenReturn(taskId);
        when(schedulerClient.getStatus(taskId)).thenReturn("RUNNING","RUNNING","CANCELLING");

        OperationView created=service.refreshIndex(account.id(),13L,new RefreshIndexRequest("refresh-1"));
        OperationView replayed=service.refreshIndex(account.id(),13L,new RefreshIndexRequest("refresh-1"));
        assertThat(created.id()).isEqualTo(replayed.id());
        assertThat(service.cancelOperation(created.id(),13L).status()).isEqualTo("CANCELLING");
        assertThatThrownBy(() -> service.getOperation(created.id(),14L)).isInstanceOf(IllegalArgumentException.class);
        verify(schedulerClient,times(1)).createIndexTask(any(),eq(account.id()),startsWith("drive-index:"));
        verify(schedulerClient).cancel(taskId);
    }

    @Test
    void shouldDelegateOwnerBoundCopyToStorageGateway() {
        AccountView source = service.register(new RegisterAccountRequest(31L, "copy-source", "Source", "RCLONE",
                "secret://drive/copy-source", "copy_source", true, true));
        AccountView target = service.register(new RegisterAccountRequest(31L, "copy-target", "Target", "S3",
                "secret://drive/copy-target", "copy_target", false, true));
        UUID sourceProvider = UUID.randomUUID();
        UUID targetProvider = UUID.randomUUID();
        service.bindStorageProvider(source.id(), sourceProvider);
        service.bindStorageProvider(target.id(), targetProvider);
        UUID operationId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        StorageOperationView running = new StorageOperationView(operationId, taskId, "COPY_OBJECT", "RUNNING", null);
        StorageOperationView cancelled = new StorageOperationView(operationId, taskId, "COPY_OBJECT", "CANCELLED", null);
        when(storageConnector.copyObject(startsWith("drive-copy:"), eq(sourceProvider), eq("books/a.epub"),
                eq(targetProvider), eq("backup/a.epub"))).thenReturn(running);
        when(storageConnector.operation(operationId)).thenReturn(running, cancelled);
        when(storageConnector.cancel(operationId)).thenReturn(running);

        OperationView created = service.copyObject(source.id(), 31L,
                new CopyObjectRequest("copy-1", target.id(), "/books/a.epub", "backup/a.epub"));
        OperationView cancelledView = service.cancelOperation(created.id(), 31L);

        assertThat(created.operationType()).isEqualTo("COPY_OBJECT");
        assertThat(cancelledView.status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> service.getOperation(created.id(), 32L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(storageConnector).cancel(operationId);
        verify(schedulerClient, never()).cancel(taskId);
    }

    @Test
    void shouldDelegateOwnerBoundTreeCopyToStorageGateway() {
        AccountView source = service.register(new RegisterAccountRequest(41L, "tree-source", "Source", "RCLONE",
                "secret://drive/tree-source", "tree_source", true, true));
        AccountView target = service.register(new RegisterAccountRequest(41L, "tree-target", "Target", "S3",
                "secret://drive/tree-target", "tree_target", false, true));
        UUID sourceProvider = UUID.randomUUID();
        UUID targetProvider = UUID.randomUUID();
        service.bindStorageProvider(source.id(), sourceProvider);
        service.bindStorageProvider(target.id(), targetProvider);
        UUID operationId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        StorageOperationView running = new StorageOperationView(operationId, taskId, "COPY_TREE_NATIVE",
                "RUNNING", null);
        StorageOperationView cancelled = new StorageOperationView(operationId, taskId, "COPY_TREE_NATIVE",
                "CANCELLED", null);
        when(storageConnector.copyTree(startsWith("drive-copy-tree:"), eq(sourceProvider), eq("books"),
                eq(targetProvider), eq("backup"), eq(5000))).thenReturn(running);
        when(storageConnector.operation(operationId)).thenReturn(running, cancelled);
        when(storageConnector.cancel(operationId)).thenReturn(running);

        OperationView created = service.copyTree(source.id(), 41L,
                new CopyTreeRequest("tree-1", target.id(), "/books", "/backup", 5000));
        OperationView cancelledView = service.cancelOperation(created.id(), 41L);

        assertThat(created.operationType()).isEqualTo("COPY_TREE_NATIVE");
        assertThat(cancelledView.status()).isEqualTo("CANCELLED");
        verify(storageConnector).cancel(operationId);
        verify(schedulerClient, never()).cancel(taskId);
    }

    @Test
    void shouldRejectTreeCopyToReadOnlyAccount() {
        AccountView source = service.register(new RegisterAccountRequest(42L, "tree-source-ro", "Source", "RCLONE",
                "secret://drive/tree-source-ro", "tree_source_ro", true, true));
        AccountView target = service.register(new RegisterAccountRequest(42L, "tree-target-ro", "Target", "S3",
                "secret://drive/tree-target-ro", "tree_target_ro", true, true));

        assertThatThrownBy(() -> service.copyTree(source.id(), 42L,
                new CopyTreeRequest("tree-ro", target.id(), "", "backup", 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("drive target account is read only");
        verifyNoInteractions(storageConnector);
    }

    @Test
    void shouldDelegateOwnerBoundTreeMoveToStorageGateway() {
        AccountView source = service.register(new RegisterAccountRequest(51L, "move-source", "Source", "RCLONE",
                "secret://drive/move-source", "move_source", false, true));
        AccountView target = service.register(new RegisterAccountRequest(51L, "move-target", "Target", "S3",
                "secret://drive/move-target", "move_target", false, true));
        UUID sourceProvider = UUID.randomUUID();
        UUID targetProvider = UUID.randomUUID();
        service.bindStorageProvider(source.id(), sourceProvider);
        service.bindStorageProvider(target.id(), targetProvider);
        UUID operationId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        StorageOperationView running = new StorageOperationView(operationId, taskId, "MOVE_TREE", "RUNNING", null);
        StorageOperationView cancelled = new StorageOperationView(operationId, taskId, "MOVE_TREE", "CANCELLED", null);
        when(storageConnector.moveTree(startsWith("drive-copy-move:"), eq(sourceProvider), eq("incoming"),
                eq(targetProvider), eq("library"), eq(10000))).thenReturn(running);
        when(storageConnector.operation(operationId)).thenReturn(running, cancelled);
        when(storageConnector.cancel(operationId)).thenReturn(running);

        OperationView created = service.moveTree(source.id(), 51L,
                new MoveTreeRequest("move-1", target.id(), "/incoming", "/library", 10000));
        OperationView cancelledView = service.cancelOperation(created.id(), 51L);

        assertThat(created.operationType()).isEqualTo("MOVE_TREE");
        assertThat(cancelledView.status()).isEqualTo("CANCELLED");
        verify(storageConnector).cancel(operationId);
        verify(schedulerClient, never()).cancel(taskId);
    }

    @Test
    void shouldDelegateWritableOwnerBoundTreeDeleteToStorageGateway() {
        AccountView account = service.register(new RegisterAccountRequest(61L, "delete-account", "Delete", "RCLONE",
                "secret://drive/delete-account", "delete_account", false, true));
        UUID provider = UUID.randomUUID();
        service.bindStorageProvider(account.id(), provider);
        UUID operationId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        StorageOperationView running = new StorageOperationView(operationId, taskId, "DELETE_TREE", "RUNNING", null);
        StorageOperationView cancelled = new StorageOperationView(operationId, taskId, "DELETE_TREE",
                "CANCELLED", null);
        when(storageConnector.deleteTree(startsWith("drive-delete:"), eq(provider), eq("trash/books"),
                eq(1000))).thenReturn(running);
        when(storageConnector.operation(operationId)).thenReturn(running, cancelled);
        when(storageConnector.cancel(operationId)).thenReturn(running);

        OperationView created = service.deleteTree(account.id(), 61L,
                new DeleteTreeRequest("delete-1", "/trash/books", 1000));
        OperationView cancelledView = service.cancelOperation(created.id(), 61L);

        assertThat(created.operationType()).isEqualTo("DELETE_TREE");
        assertThat(cancelledView.status()).isEqualTo("CANCELLED");
        verify(storageConnector).cancel(operationId);
    }

    @Test
    void shouldRejectDeleteFromReadOnlyAccount() {
        AccountView account = service.register(new RegisterAccountRequest(62L, "delete-read-only", "Read only",
                "RCLONE", "secret://drive/delete-read-only", "delete_read_only", true, true));

        assertThatThrownBy(() -> service.deleteTree(account.id(), 62L,
                new DeleteTreeRequest("delete-ro", "books", 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("drive account is read only");
        verifyNoInteractions(storageConnector);
    }

    private IndexItem item(String path,String parent,long size) {
        return new IndexItem(path,path,parent,path,"text/plain",size,false,Instant.parse("2026-01-01T00:00:00Z"),null);
    }
}
