package ma.mystix.shared.metrics;

import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Time spent per processing stage ({@code mystix.stage}, tags {@code pipeline} and {@code stage}), so a slowdown
 * points at the stage that causes it (plan de débit, step 1). Failed runs are timed too, with {@code outcome=error}.
 */
@Component
public class StageMetrics {

    private final MeterRegistry meters;

    public StageMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    public <T> T time(String pipeline, String stage, Supplier<T> work) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "error";
        try {
            T result = work.get();
            outcome = "ok";
            return result;
        } finally {
            sample.stop(meters.timer("mystix.stage", "pipeline", pipeline, "stage", stage, "outcome", outcome));
        }
    }

    public void time(String pipeline, String stage, Runnable work) {
        time(pipeline, stage, () -> {
            work.run();
            return null;
        });
    }
}
