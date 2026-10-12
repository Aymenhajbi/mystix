package ma.mystix.queue;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import ma.mystix.shared.error.MystixException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Polls the queue and runs jobs on virtual threads, at most {@code mystix.queue.concurrency} at a time in this process
 * (ADR-0003). Each run is timed per kind and outcome ({@code mystix.queue.job}). Off with
 * {@code mystix.queue.worker-enabled=false}; {@link #runOnce()} then drives the queue by hand (tests, tools).
 */
@Component
public class QueueWorker implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(QueueWorker.class);
    private static final int RELEASE_EVERY_POLLS = 20;

    private final MessageQueue queue;
    private final Map<String, JobHandler> handlers;
    private final QueueProperties properties;
    private final MeterRegistry meters;
    private final String id;
    private final Semaphore slots;
    private ScheduledExecutorService poller;
    private ExecutorService runners;
    private volatile boolean running;
    private int polls;

    QueueWorker(MessageQueue queue, List<JobHandler> handlers, QueueProperties properties, MeterRegistry meters) {
        this.queue = queue;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::kind, Function.identity()));
        this.properties = properties;
        this.meters = meters;
        this.id = ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID().toString().substring(0, 8);
        this.slots = new Semaphore(properties.concurrency());
    }

    /** The name this process claims jobs under. */
    public String id() {
        return id;
    }

    @Override
    public void start() {
        if (!properties.workerEnabled()) {
            LOG.info("Queue worker disabled (mystix.queue.worker-enabled=false)");
            return;
        }
        runners = Executors.newVirtualThreadPerTaskExecutor();
        poller = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("queue-poller").daemon()
                .unstarted(r));
        running = true;
        poller.scheduleWithFixedDelay(this::poll, 0, properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("Queue worker {} started: {} slots, kinds {}", id, properties.concurrency(), handlers.keySet());
    }

    @Override
    public void stop() {
        running = false;
        if (poller != null) {
            poller.shutdownNow();
        }
        if (runners != null) {
            runners.shutdown();
            try {
                // Running jobs get a moment to finish; the others come back when their lease expires.
                runners.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void poll() {
        try {
            if (++polls % RELEASE_EVERY_POLLS == 1) {
                int released = queue.releaseExpired();
                if (released > 0) {
                    LOG.warn("Queue: {} jobs released after an expired lease", released);
                }
            }
            // Keep claiming while the queue has work and this process has free slots.
            while (running) {
                int free = slots.availablePermits();
                if (free == 0) {
                    return;
                }
                List<Job> jobs = queue.claim(id, free);
                if (jobs.isEmpty()) {
                    return;
                }
                for (Job job : jobs) {
                    slots.acquireUninterruptibly();
                    runners.submit(() -> {
                        try {
                            run(job);
                        } finally {
                            slots.release();
                        }
                    });
                }
                if (jobs.size() < free) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            // The database may be briefly unreachable: log and try again at the next poll.
            LOG.error("Queue poll failed", e);
        }
    }

    /** Claims and runs, in the calling thread, what is due now. Returns the number of jobs run. */
    public int runOnce() {
        queue.releaseExpired();
        List<Job> jobs = queue.claim(id, properties.concurrency());
        jobs.forEach(this::run);
        return jobs.size();
    }

    private void run(Job job) {
        JobHandler handler = handlers.get(job.kind());
        Timer.Sample sample = Timer.start(meters);
        String outcome;
        MDC.put("company", job.companyId().toString());
        MDC.put("job", job.id().toString());
        try {
            if (handler == null) {
                outcome = queue.fail(job, id, new Job.Failure("QUEUE_NO_HANDLER", "QUEUE", false,
                        "No handler for kind " + job.kind())).name();
            } else {
                handler.handle(job);
                outcome = queue.complete(job, id) ? "DONE" : "LOST";
            }
        } catch (MystixException e) {
            outcome = queue.fail(job, id, new Job.Failure(e.errorCode().name(), e.errorCode().stage().name(),
                    e.errorCode().retryable(), e.getMessage())).name();
            LOG.info("Job {} ({}) failed with {}: {}", job.id(), job.kind(), e.errorCode(), outcome);
        } catch (RuntimeException e) {
            // Unknown failures are treated as transient: the attempts bound sends them to DEAD in the end.
            outcome = queue.fail(job, id, new Job.Failure("INTERNAL_ERROR", "QUEUE", true,
                    e.getClass().getSimpleName() + ": " + e.getMessage())).name();
            LOG.warn("Job {} ({}) failed, attempt {}: {}", job.id(), job.kind(), job.attempts(), outcome, e);
        } finally {
            MDC.remove("company");
            MDC.remove("job");
        }
        sample.stop(meters.timer("mystix.queue.job", "kind", job.kind(), "outcome", outcome));
    }
}
