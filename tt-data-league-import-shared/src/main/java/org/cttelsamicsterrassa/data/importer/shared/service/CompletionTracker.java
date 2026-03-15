package org.cttelsamicsterrassa.data.importer.shared.service;

import java.util.concurrent.atomic.AtomicInteger;

public class CompletionTracker {

    private final int total;
    private AtomicInteger counter;
    private AtomicInteger lastCounted;
    private final int percentageStep;
    private final String taskLabel;
    private final long startTime;

    private CompletionTracker(int total, int percentageStep, String taskLabel) {
        this.total = total;
        this.counter = new AtomicInteger(0);
        this.lastCounted = new AtomicInteger(0);
        this.taskLabel = taskLabel;
        this.startTime = System.currentTimeMillis();
        this.percentageStep = percentageStep;
    }

    public static CompletionTracker buildTracker(int total, int percentageStep, String taskLabel) {
        return new CompletionTracker(total, percentageStep, taskLabel);
    }

    public void trackIncrement() {
        int processed = counter.incrementAndGet();
        int percent = (processed * 100) / total;
        long elapsedTime = System.currentTimeMillis() - startTime;

        if (percent >= lastCounted.get() + percentageStep) {
            int newPercent = (percent / percentageStep) * percentageStep;
            if (lastCounted.getAndSet(newPercent) < newPercent) {
                String trackingBar = taskLabel+": "+newPercent+"% [" + "=".repeat(percent / 2) + " ".repeat(50 - (percent / 2)) + "] (" + processed + "/" + total + ", " + (elapsedTime / 1000) + "s elapsed)";
                System.out.println(trackingBar);
            }
        }
    }
}
