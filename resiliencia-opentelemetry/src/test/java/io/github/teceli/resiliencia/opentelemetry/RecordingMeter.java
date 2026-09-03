package io.github.teceli.resiliencia.opentelemetry;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.LongGaugeBuilder;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import io.opentelemetry.api.metrics.LongUpDownCounterBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleCounter;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableLongCounter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.context.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Minimal in-memory {@link Meter} test double recording every instrument call — no OTel SDK
 * dependency needed. Only the instrument kinds {@link OpenTelemetryResilienceMetrics} actually uses
 * (long counter, double counter, double histogram, double gauge) are implemented; anything else
 * throws {@link UnsupportedOperationException}.
 */
final class RecordingMeter implements Meter {

    record Point(Attributes attributes, double value) {
    }

    private final Map<String, List<Point>> longCounterAdds = new ConcurrentHashMap<>();
    private final Map<String, List<Point>> doubleCounterAdds = new ConcurrentHashMap<>();
    private final Map<String, List<Point>> histogramRecords = new ConcurrentHashMap<>();
    private final Map<String, List<Point>> gaugeSets = new ConcurrentHashMap<>();

    double counterTotal(String name, Attributes attributes) {
        return total(longCounterAdds, name, attributes);
    }

    double doubleCounterTotal(String name, Attributes attributes) {
        return total(doubleCounterAdds, name, attributes);
    }

    double lastGauge(String name, Attributes attributes) {
        return last(gaugeSets, name, attributes);
    }

    double lastHistogram(String name, Attributes attributes) {
        return last(histogramRecords, name, attributes);
    }

    List<Point> counterPoints(String name) {
        return longCounterAdds.getOrDefault(name, List.of());
    }

    List<Point> histogramPoints(String name) {
        return histogramRecords.getOrDefault(name, List.of());
    }

    private static double total(Map<String, List<Point>> store, String name, Attributes attributes) {
        return store.getOrDefault(name, List.of()).stream()
            .filter(p -> p.attributes().equals(attributes))
            .mapToDouble(Point::value)
            .sum();
    }

    private static double last(Map<String, List<Point>> store, String name, Attributes attributes) {
        return store.getOrDefault(name, List.of()).stream()
            .filter(p -> p.attributes().equals(attributes))
            .reduce((first, second) -> second)
            .map(Point::value)
            .orElseThrow(() -> new AssertionError("No recording for " + name + " " + attributes));
    }

    @Override
    public LongCounterBuilder counterBuilder(String name) {
        return new LongCounterBuilder() {
            @Override
            public LongCounterBuilder setDescription(String description) {
                return this;
            }

            @Override
            public LongCounterBuilder setUnit(String unit) {
                return this;
            }

            @Override
            public DoubleCounterBuilder ofDoubles() {
                return doubleCounterBuilder(name);
            }

            @Override
            public LongCounter build() {
                return new LongCounter() {
                    @Override
                    public void add(long value) {
                        add(value, Attributes.empty());
                    }

                    @Override
                    public void add(long value, Attributes attributes) {
                        longCounterAdds.computeIfAbsent(name, key -> new ArrayList<>()).add(new Point(attributes, value));
                    }

                    @Override
                    public void add(long value, Attributes attributes, Context context) {
                        add(value, attributes);
                    }
                };
            }

            @Override
            public ObservableLongCounter buildWithCallback(Consumer<ObservableLongMeasurement> callback) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private DoubleCounterBuilder doubleCounterBuilder(String name) {
        return new DoubleCounterBuilder() {
            @Override
            public DoubleCounterBuilder setDescription(String description) {
                return this;
            }

            @Override
            public DoubleCounterBuilder setUnit(String unit) {
                return this;
            }

            @Override
            public DoubleCounter build() {
                return new DoubleCounter() {
                    @Override
                    public void add(double value) {
                        add(value, Attributes.empty());
                    }

                    @Override
                    public void add(double value, Attributes attributes) {
                        doubleCounterAdds.computeIfAbsent(name, key -> new ArrayList<>()).add(new Point(attributes, value));
                    }

                    @Override
                    public void add(double value, Attributes attributes, Context context) {
                        add(value, attributes);
                    }
                };
            }

            @Override
            public ObservableDoubleCounter buildWithCallback(Consumer<ObservableDoubleMeasurement> callback) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public LongUpDownCounterBuilder upDownCounterBuilder(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public DoubleHistogramBuilder histogramBuilder(String name) {
        return new DoubleHistogramBuilder() {
            @Override
            public DoubleHistogramBuilder setDescription(String description) {
                return this;
            }

            @Override
            public DoubleHistogramBuilder setUnit(String unit) {
                return this;
            }

            @Override
            public LongHistogramBuilder ofLongs() {
                throw new UnsupportedOperationException();
            }

            @Override
            public DoubleHistogram build() {
                return new DoubleHistogram() {
                    @Override
                    public void record(double value) {
                        record(value, Attributes.empty());
                    }

                    @Override
                    public void record(double value, Attributes attributes) {
                        histogramRecords.computeIfAbsent(name, key -> new ArrayList<>()).add(new Point(attributes, value));
                    }

                    @Override
                    public void record(double value, Attributes attributes, Context context) {
                        record(value, attributes);
                    }
                };
            }
        };
    }

    @Override
    public DoubleGaugeBuilder gaugeBuilder(String name) {
        return new DoubleGaugeBuilder() {
            @Override
            public DoubleGaugeBuilder setDescription(String description) {
                return this;
            }

            @Override
            public DoubleGaugeBuilder setUnit(String unit) {
                return this;
            }

            @Override
            public LongGaugeBuilder ofLongs() {
                throw new UnsupportedOperationException();
            }

            @Override
            public ObservableDoubleGauge buildWithCallback(Consumer<ObservableDoubleMeasurement> callback) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DoubleGauge build() {
                return new DoubleGauge() {
                    @Override
                    public void set(double value) {
                        set(value, Attributes.empty());
                    }

                    @Override
                    public void set(double value, Attributes attributes) {
                        gaugeSets.computeIfAbsent(name, key -> new ArrayList<>()).add(new Point(attributes, value));
                    }

                    @Override
                    public void set(double value, Attributes attributes, Context context) {
                        set(value, attributes);
                    }
                };
            }
        };
    }
}
