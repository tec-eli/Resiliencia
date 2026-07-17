package io.github.teceli.resiliencia.opentelemetry;

import io.github.teceli.resiliencia.metrics.circuitbreaker.CircuitBreakerCounters;
import io.github.teceli.resiliencia.metrics.circuitbreaker.CircuitBreakerSnapshot;
import io.github.teceli.resiliencia.metrics.retry.RetryCounters;
import io.github.teceli.resiliencia.metrics.timeout.TimeoutCounters;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wiring test against the real OpenTelemetry SDK (as opposed to {@link RecordingMeter}), to
 * confirm {@link OpenTelemetryResilienceMetrics} instruments register and aggregate correctly with a
 * live {@link Meter} and are readable back through {@link InMemoryMetricReader} — the SDK's own
 * aggregation/export pipeline is behavior {@code RecordingMeter} cannot exercise since it is a hand-rolled
 * fake, not the real instrument implementations.
 */
class OpenTelemetryResilienceMetricsSdkIntegrationTest {
    private static final AttributeKey<String> KEY_NAME = AttributeKey.stringKey("name");

    private InMemoryMetricReader reader;
    private Meter sdkMeter;

    @BeforeEach
    void setUp() {
        reader = InMemoryMetricReader.create();
        var meterProvider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        sdkMeter = meterProvider.get("resiliencia-opentelemetry-test");
    }

    @Test
    void should_exportLongSum_when_retrySuccessObserved() {
        var metrics = new OpenTelemetryResilienceMetrics(sdkMeter);

        metrics.observe(new RetryCounters.Success("myRetry", 3));

        var metric = findMetric(MetricNames.RETRY_SUCCESS);
        assertThat(metric.getLongSumData().getPoints()).anySatisfy(point -> {
            assertThat(point.getValue()).isEqualTo(1L);
            assertThat(point.getAttributes().get(KEY_NAME)).isEqualTo("myRetry");
        });
    }

    @Test
    void should_exportDoubleGauge_when_circuitBreakerStateObserved() {
        var metrics = new OpenTelemetryResilienceMetrics(sdkMeter);

        metrics.observe(new CircuitBreakerSnapshot.State("myCb", CircuitBreakerSnapshot.Phase.OPEN));

        var metric = findMetric(MetricNames.CIRCUIT_BREAKER_STATE);
        assertThat(metric.getDoubleGaugeData().getPoints()).anySatisfy(point -> {
            assertThat(point.getValue()).isEqualTo(1.0);
            assertThat(point.getAttributes().get(KEY_NAME)).isEqualTo("myCb");
        });
    }

    @Test
    void should_exportCounterPair_notBaseHistogramName_when_timeoutDurationObserved_inSafeMode() {
        var metrics = new OpenTelemetryResilienceMetrics(sdkMeter, DurationInstrumentationMode.SAFE);

        metrics.observe(new TimeoutCounters.Succeeded("myTimeout", Duration.ofMillis(150)));

        var countMetric = findMetric(MetricNames.TIMEOUT_DURATION_COUNT);
        assertThat(countMetric.getLongSumData().getPoints()).anySatisfy(point -> assertThat(point.getValue()).isEqualTo(1L));

        var sumMetric = findMetric(MetricNames.TIMEOUT_DURATION_SUM);
        assertThat(sumMetric.getDoubleSumData().getPoints()).anySatisfy(point -> assertThat(point.getValue()).isEqualTo(150.0));

        assertThat(reader.collectAllMetrics().stream().map(MetricData::getName))
            .doesNotContain(MetricNames.TIMEOUT_DURATION);
    }

    @Test
    void should_exportCounterPair_notBaseHistogramName_when_circuitBreakerCallRecordedObserved_inSafeMode() {
        var metrics = new OpenTelemetryResilienceMetrics(sdkMeter, DurationInstrumentationMode.SAFE);

        metrics.observe(new CircuitBreakerCounters.CallRecorded("myCb", true, Duration.ofMillis(20)));

        var countMetric = findMetric(MetricNames.CIRCUIT_BREAKER_CALLS_COUNT);
        assertThat(countMetric.getLongSumData().getPoints()).anySatisfy(point -> assertThat(point.getValue()).isEqualTo(1L));

        var sumMetric = findMetric(MetricNames.CIRCUIT_BREAKER_CALLS_SUM);
        assertThat(sumMetric.getDoubleSumData().getPoints()).anySatisfy(point -> assertThat(point.getValue()).isEqualTo(20.0));

        assertThat(reader.collectAllMetrics().stream().map(MetricData::getName))
            .doesNotContain(MetricNames.CIRCUIT_BREAKER_CALLS);
    }

    @Test
    void should_exportHistogram_underBaseName_when_timeoutDurationObserved_inDetailedMode() {
        var metrics = new OpenTelemetryResilienceMetrics(sdkMeter, DurationInstrumentationMode.DETAILED);

        metrics.observe(new TimeoutCounters.Succeeded("myTimeout", Duration.ofMillis(150)));

        var metric = findMetric(MetricNames.TIMEOUT_DURATION);
        assertThat(metric.getHistogramData().getPoints()).anySatisfy(point -> {
            assertThat(point.getCount()).isEqualTo(1L);
            assertThat(point.getSum()).isEqualTo(150.0);
        });
    }

    private MetricData findMetric(String name) {
        Collection<MetricData> allMetrics = reader.collectAllMetrics();
        return allMetrics.stream()
            .filter(metric -> metric.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No metric exported with name " + name));
    }
}
