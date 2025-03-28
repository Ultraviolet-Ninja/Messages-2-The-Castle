package jasmine.jragon.progress.bar;

import me.tongfei.progressbar.ProgressState;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.DoubleSummaryStatistics;
import java.util.Optional;
import java.util.function.Function;

sealed interface Eta extends Function<ProgressState, Optional<Duration>> {
    static boolean isDefiniteProgress(ProgressState progress) {
        return progress.getMax() > 0L && !progress.isIndefinite() &&
                progress.getCurrent() - progress.getStart() != 0;
    }

    static double computeProgressRate(ProgressState progress) {
        double deltaC = progress.getCurrent() - progress.getStart();
        var elapsedTime = progress.getElapsedAfterStart();

        if (elapsedTime.isZero() || deltaC <= 0) {
            return 0.0; // Avoid division by zero or invalid progress
        }

        return deltaC / elapsedTime.toNanos();
    }

    static Eta generateFullAverageEta() {
        return new FullAverageEta();
    }

    static Eta generateMovingAverageEta() {
        return new MovingAverageEta();
    }

    static Eta generateSmoothingEta() {
        return new ExponentialSmoothingEta();
    }

    static Eta generateBayesianEta(double filesPerMsecMean, double filesPerMsecVariance, double measuredVariance) {
        return new BayesianUpdatingEta(filesPerMsecMean, filesPerMsecVariance, measuredVariance);
    }

    final class FullAverageEta implements Eta {
        private final DoubleSummaryStatistics stats;

        private FullAverageEta() {
            stats = new DoubleSummaryStatistics();
        }

        @Override
        public Optional<Duration> apply(ProgressState progressState) {
            if (isDefiniteProgress(progressState)) {
                double currentRate = computeProgressRate(progressState);

                stats.accept(currentRate);

                // Compute moving average rate
                double avgRate = stats.getAverage();

                // Compute ETA
                if (avgRate <= 0) {
                    return Optional.empty(); // Avoid division by zero
                }

                double currentAverage = (progressState.getMax() - progressState.getCurrent()) / avgRate;

                return Optional.of(Duration.ofNanos((long) currentAverage));
            }
            return Optional.empty();
        }
    }

    final class MovingAverageEta implements Eta {
        private static final int WINDOW_SIZE = 15;

        private final Deque<Double> ratesWindow;

        private MovingAverageEta() {
            ratesWindow = new ArrayDeque<>(WINDOW_SIZE);
        }

        @Override
        public Optional<Duration> apply(ProgressState progressState) {
            if (isDefiniteProgress(progressState)) {
                double currentRate = computeProgressRate(progressState);

                // Maintain moving window
                if (ratesWindow.size() >= WINDOW_SIZE) {
                    ratesWindow.pollFirst(); // Remove the oldest entry
                }
                ratesWindow.addLast(currentRate);

                // Compute moving average rate
                double avgRate = ratesWindow.stream()
                        .mapToDouble(Double::doubleValue)
                        .average()
                        .orElse(0.0);

                // Compute ETA
                if (avgRate <= 0.0) {
                    return Optional.empty(); // Avoid division by zero
                }

                double currentAverage = (progressState.getMax() - progressState.getCurrent()) / avgRate;

                return Optional.of(Duration.ofNanos((long) currentAverage));
            }
            return Optional.empty();
        }
    }

    final class ExponentialSmoothingEta implements Eta {
        private static final double ALPHA = 0.3;

        private double smoothedRate = 0.0;

        @Override
        public Optional<Duration> apply(ProgressState progressState) {
            if (isDefiniteProgress(progressState)) {
                double currentRate = computeProgressRate(progressState);

                if (currentRate > 0.0) {
                    smoothedRate = ALPHA * currentRate + (1 - ALPHA) * smoothedRate;
                }

                if (smoothedRate <= 0.0) {
                    return Optional.empty();
                }

                double currentAverage = (progressState.getMax() - progressState.getCurrent()) / smoothedRate;

                return Optional.of(Duration.ofNanos((long) currentAverage));
            }
            return Optional.empty();
        }
    }

    final class BayesianUpdatingEta implements Eta {
        private final double priorMean;
        private final double priorVariance;
        private final double measurementVariance;
        private double posteriorMean;
        private double posteriorVariance;

        public BayesianUpdatingEta(double initialMean, double initialVariance, double measurementVar) {
            this.priorMean = initialMean;
            this.priorVariance = initialVariance;
            this.measurementVariance = measurementVar;
            this.posteriorMean = initialMean;
            this.posteriorVariance = initialVariance;
        }

        private void update(ProgressState progressState) {
            double observedRate = (progressState.getCurrent() - progressState.getStart()) /
                    (double) progressState.getElapsedAfterStart().toMillis();

            if (posteriorMean == 0 && posteriorVariance == 0) {
                posteriorMean = priorMean;
                posteriorVariance = priorVariance;
            }

            double newVariance = 1 / (1 / posteriorVariance + 1 / measurementVariance);

            posteriorMean = newVariance * (posteriorMean / posteriorVariance + observedRate / measurementVariance);
            posteriorVariance = newVariance;
        }

        @Override
        public Optional<Duration> apply(ProgressState progressState) {
            if (posteriorMean <= 0) {
                return Optional.empty();
            }

            if (isDefiniteProgress(progressState)) {
                update(progressState);
                double etaMillis = (progressState.getMax() - progressState.getCurrent()) / posteriorMean;
                return Optional.of(Duration.ofMillis((long) etaMillis));
            }

            return Optional.empty();
        }
    }
}
