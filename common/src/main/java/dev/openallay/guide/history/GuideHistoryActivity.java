package dev.openallay.guide.history;

/** Ordered history-repository activity used for non-queueing deletion gates. */
public record GuideHistoryActivity(int pendingWrites, boolean deleting) {
    public GuideHistoryActivity {
        if (pendingWrites < 0) {
            throw new IllegalArgumentException("pendingWrites must not be negative");
        }
    }

    public boolean idleForDeletion() {
        return pendingWrites == 0 && !deleting;
    }

    public static GuideHistoryActivity idle() {
        return new GuideHistoryActivity(0, false);
    }
}
