package com.yuyutian.mytools.task.executor.client.adaptation;

import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** 单个已领取执行的有界运行循环；Reader 是终态权威，等待和网络重放不会新建模型调用。 */
public final class NovelAdaptationRun {
    private static final Set<ErrorCode> BUSINESS_FAILURES = Set.of(ErrorCode.INTENT_CONFLICT, ErrorCode.UNAVAILABLE,
            ErrorCode.UNAUTHORIZED, ErrorCode.PROTOCOL, ErrorCode.TOO_LARGE, ErrorCode.CONSTRAINTS, ErrorCode.DISPATCH,
            ErrorCode.CONTEXT, ErrorCode.CONSENT, ErrorCode.CONTENT_REJECTED, ErrorCode.UNKNOWN, ErrorCode.REQUEST_REJECTED, ErrorCode.DEADLINE);
    private final ReaderAdaptationClient reader;
    private final NovelAdaptationWorkflow workflow;
    private final Instant deadline;
    private final BooleanSupplier permitted;
    private final Clock clock;
    private final Pause pause;

    /** 绑定宿主截止时间及当前取消/租约条件；调用方不得从脚本请求读取这些字段。 */
    public NovelAdaptationRun(ReaderAdaptationClient reader, NovelAdaptationWorkflow workflow, Instant deadline, BooleanSupplier permitted) {
        this(reader, workflow, deadline, permitted, Clock.systemUTC(), Thread::sleep);
    }

    NovelAdaptationRun(ReaderAdaptationClient reader, NovelAdaptationWorkflow workflow, Instant deadline,
                        BooleanSupplier permitted, Clock clock, Pause pause) {
        if (reader == null || workflow == null || deadline == null || permitted == null || clock == null || pause == null
                || deadline.isAfter(clock.instant().plusSeconds(900))) throw new ReaderAdaptationException(ErrorCode.CONTEXT, 0);
        this.reader = reader; this.workflow = workflow; this.deadline = deadline;
        this.permitted = permitted; this.clock = clock; this.pause = pause;
    }

    /** 宿主固定结果，不承载小说、意图、能力或外部错误文本。 */
    public record Result(String status, ErrorCode errorCode) {
        /** 结果只能属于 Scheduler 的四个固定执行状态。 */
        public Result {
            if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT").contains(status)
                    || "SUCCEEDED".equals(status) && errorCode != null) throw new ReaderAdaptationException(ErrorCode.CONTEXT, 0);
        }
    }

    /** 运行至 Reader 确认完成、失败或宿主停止；所有退出路径均释放 workflow 的未结算日志。 */
    public Result execute() {
        int transientFailures = 0;
        try (workflow) {
            while (true) {
                if (!clock.instant().isBefore(deadline)) return new Result("TIMED_OUT", ErrorCode.DEADLINE);
                if (Thread.currentThread().isInterrupted() || !allowed()) return new Result("CANCELLED", ErrorCode.FENCED);
                try {
                    var step = workflow.advance();
                    switch (step.action()) {
                        case COMPLETED: return new Result("SUCCEEDED", null);
                        case FAILED: return new Result("FAILED", step.errorCode() == null ? ErrorCode.UNKNOWN : step.errorCode());
                        case STOPPED: return new Result(allowed() ? "FAILED" : "CANCELLED", ErrorCode.FENCED);
                        case WAITING: waitFor(1000); break;
                        case PROGRESSED: break;
                    }
                    transientFailures = 0;
                } catch (ReaderAdaptationException exception) {
                    if (exception.error() == ErrorCode.AUTHORITY_UNAVAILABLE || exception.error() == ErrorCode.BIND_PENDING) {
                        // 同一 workflow 保留原 Ticket/Terminal；退避只重试受权命令或结算，不重做模型。
                        transientFailures = Math.min(transientFailures + 1, 4);
                        waitFor(Math.min(3000, 250L << transientFailures));
                    } else {
                        failBusiness(exception.error());
                        return new Result("FAILED", exception.error());
                    }
                } catch (NovelProviderException exception) {
                    failBusiness(exception.error());
                    return new Result("FAILED", exception.error());
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Result("CANCELLED", ErrorCode.FENCED);
        } catch (RuntimeException exception) {
            // 未知宿主异常不向 Scheduler 传播具体类型、路径、外部正文或异常链。
            return new Result("FAILED", ErrorCode.UNKNOWN);
        }
    }

    private void failBusiness(ErrorCode error) {
        if (!BUSINESS_FAILURES.contains(error) || !allowed() || !reader.active() || !clock.instant().isBefore(deadline)) return;
        try { reader.fail(error); }
        catch (ReaderAdaptationException ignored) { /* Reader 未确认失败时，后续派发观察与取消屏障继续收敛。 */ }
    }

    private void waitFor(long milliseconds) throws InterruptedException {
        long remaining = Math.max(0, Math.min(milliseconds, Duration.between(clock.instant(), deadline).toMillis()));
        while (remaining > 0 && allowed()) {
            long part = Math.min(remaining, 100);
            pause.sleep(part); remaining -= part;
        }
    }
    private boolean allowed() { try { return permitted.getAsBoolean(); } catch (RuntimeException ignored) { return false; } }
    @FunctionalInterface interface Pause { void sleep(long milliseconds) throws InterruptedException; }
}
