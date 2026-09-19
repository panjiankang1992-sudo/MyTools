package com.yuyutian.mytools.task.executor.runtime.adaptation;

import com.yuyutian.mytools.task.executor.client.adaptation.NovelAdaptationRun;
import com.yuyutian.mytools.task.executor.common.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NovelAdaptationBrokerTest {
    private Path root;
    private NovelAdaptationBroker broker;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void root() throws Exception { root = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "nb-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
    @AfterEach
    void close() throws Exception { if (broker != null) broker.close(); Files.deleteIfExists(root); }

    @Test
    void oneTimeHandleRunsBoundHostWithoutReturningAnyContext() throws Exception {
        broker = create(true);
        Path folder = broker.directory();
        assertThat(Files.getPosixFilePermissions(folder)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(folder.resolve("b.sock"))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(folder.resolve("handle"))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        String response = exchange(valid());
        assertThat(response).isEqualTo("{\"status\":\"SUCCEEDED\",\"errorCode\":null}\n"); assertThat(calls.get()).isEqualTo(1);
        assertThat(broker.result().orElseThrow().status()).isEqualTo("SUCCEEDED"); assertThat(Files.exists(folder.resolve("handle"))).isFalse();
        assertThatThrownBy(() -> exchange("{}\n")).isInstanceOf(java.io.IOException.class);
        broker.close(); assertThat(Files.exists(folder)).isFalse();
    }

    @Test
    void rejectsWrongHandleUnknownRoutesAndArbitraryResourceFields() throws Exception {
        broker = create(true); String valid = valid();
        for (String request : new String[]{"{\"op\":\"run\",\"handle\":\"" + "x".repeat(43) + "\"}\n",
                valid.replace("\"run\"", "\"claim\""), valid.replace("}\n", ",\"adaptationId\":\"another\"}\n")}) {
            assertThat(exchange(request)).contains(ErrorCode.REQUEST_INVALID.code());
        }
        assertThat(calls.get()).isZero(); assertThat(broker.result()).isEmpty();
        assertThat(exchange(valid)).contains("SUCCEEDED"); assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void duplicateKeysOversizedPacketsAndTrailingMessagesAreRejected() throws Exception {
        broker = create(true); String valid = valid();
        for (String request : new String[]{valid.replace("{", "{\"op\":\"claim\","), "x".repeat(2049) + "\n", valid + "{}\n"}) {
            assertThat(exchange(request)).contains(ErrorCode.REQUEST_INVALID.code());
        }
        assertThat(calls.get()).isZero();
    }

    @Test
    void stoppedExecutionCannotStartHost() throws Exception {
        broker = create(false);
        assertThat(exchange(valid())).contains("CANCELLED"); assertThat(calls.get()).isZero();
    }

    @Test
    void hostExceptionStillConsumesHandleAndNeverRunsAgain() throws Exception {
        broker = new NovelAdaptationBroker(root, () -> { calls.incrementAndGet(); throw new IllegalStateException("fixture-private-message"); }, () -> true, Instant.now().plusSeconds(30));
        String request = valid(); assertThat(exchange(request)).doesNotContain("fixture-private-message");
        assertThat(broker.result().orElseThrow().errorCode()).isEqualTo(ErrorCode.UNKNOWN);
        assertThatThrownBy(() -> exchange(request)).isInstanceOf(java.io.IOException.class); assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void insecureRootAndExpiredDeadlineCannotGrantPermission() throws Exception {
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"));
        assertThatThrownBy(() -> create(true)).hasMessage(ErrorCode.DISABLED.code());
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        broker = new NovelAdaptationBroker(root, () -> { calls.incrementAndGet(); return new NovelAdaptationRun.Result("SUCCEEDED", null); }, () -> true, Instant.now().minusSeconds(1));
        assertThat(exchange(valid())).contains(ErrorCode.REQUEST_INVALID.code()); assertThat(calls.get()).isZero();
    }

    private NovelAdaptationBroker create(boolean permitted) { return new NovelAdaptationBroker(root, () -> { calls.incrementAndGet(); return new NovelAdaptationRun.Result("SUCCEEDED", null); }, () -> permitted, Instant.now().plusSeconds(30)); }
    private String valid() throws Exception { return "{\"op\":\"run\",\"handle\":\"" + Files.readString(broker.directory().resolve("handle")) + "\"}\n"; }
    private String exchange(String request) throws Exception {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(broker.directory().resolve("b.sock"))); channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
            channel.configureBlocking(false); ByteBuffer bytes = ByteBuffer.allocate(2048); long deadline = System.nanoTime() + 3_000_000_000L;
            while (System.nanoTime() < deadline) {
                int count = channel.read(bytes);
                if (count < 0 || bytes.position() > 0 && bytes.get(bytes.position() - 1) == '\n') break;
                Thread.sleep(1);
            }
            return new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
        }
    }
}
