package com.yuyutian.mytools.task.executor.runtime.adaptation;

import com.yuyutian.mytools.task.executor.client.adaptation.ReaderAdaptationException;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 专用最小只读 Python rootfs；不把宿主根目录、凭据、网络或 PID 视图挂给任务包。 */
public final class NovelAdaptationSandbox {
    private final Path executable;
    private final Path root;
    private final List<Path> privatePaths;

    /** 接受已验收的专用 rootfs，并拒绝把任何宿主凭据或结算目录包含在可见根中。 */
    public NovelAdaptationSandbox(Path executable, Path root, List<Path> privatePaths) {
        try {
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) throw unavailable();
            this.executable = real(executable); this.root = real(root);
            if (!Files.isExecutable(this.executable) || !Files.isRegularFile(this.executable, LinkOption.NOFOLLOW_LINKS)
                    || this.root.getNameCount() < 2 || !Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)
                    || privatePaths == null || privatePaths.isEmpty()) throw unavailable();
            this.privatePaths = privatePaths.stream().map(NovelAdaptationSandbox::real).toList();
            exclude(this.root);
            for (String path : new String[]{"work", "package", "proc", "dev", "tmp"}) {
                Path mount = this.root.resolve(path);
                if (!Files.isDirectory(mount, LinkOption.NOFOLLOW_LINKS) || !real(mount).startsWith(this.root)) throw unavailable();
            }
            if (!Files.isExecutable(this.root.resolve("usr/bin/python3"))) throw unavailable();
        } catch (RuntimeException exception) { throw unavailable(); }
    }

    /** 生成固定隔离参数，不接受模板、任意挂载点、外部网络或脚本自报工作目录。 */
    public List<String> command(Path packageRoot, Path directory) {
        Path published = real(packageRoot); Path work = real(directory); exclude(published); exclude(work);
        List<String> command = base();
        command.addAll(List.of("--ro-bind", published.toString(), "/package", "--bind", work.toString(), "/work", "--chdir", "/work",
                "--", "/usr/bin/python3", "-I", "-S", "/package/scripts/main.py"));
        return List.copyOf(command);
    }

    /** 在注册可运行能力前验证内核确实建立独立网络和 PID 命名空间，不以配置布尔值替代运行探测。 */
    public void validate() {
        Process process = null;
        try {
            String network = Files.readSymbolicLink(Path.of("/proc/self/ns/net")).toString();
            String pid = Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString();
            List<String> command = base();
            command.addAll(List.of("--", "/usr/bin/python3", "-I", "-S", "-c",
                    "import os,sys,socket,json; socket.socket(socket.AF_UNIX,socket.SOCK_STREAM).close(); assert os.readlink('/proc/self/ns/net') != sys.argv[1]; assert os.readlink('/proc/self/ns/pid') != sys.argv[2]", network, pid));
            ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear(); process = builder.start();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) throw unavailable();
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception exception) { throw unavailable(); }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }

    private List<String> base() {
        return new ArrayList<>(List.of(executable.toString(), "--unshare-all", "--unshare-user", "--disable-userns", "--die-with-parent", "--new-session", "--cap-drop", "ALL",
                "--ro-bind", root.toString(), "/", "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp",
                "--clearenv", "--setenv", "PATH", "/usr/bin:/bin", "--setenv", "LANG", "C.UTF-8"));
    }
    private void exclude(Path visible) {
        for (Path secret : privatePaths) if (secret.startsWith(visible) || visible.startsWith(secret)) throw unavailable();
    }
    private static Path real(Path path) {
        try { if (path == null || !path.isAbsolute() || !path.normalize().equals(path.toRealPath())) throw unavailable(); return path; }
        catch (Exception exception) { throw unavailable(); }
    }
    private static ReaderAdaptationException unavailable() { return new ReaderAdaptationException(ErrorCode.DISABLED, 0); }
}
