package dev.openallay.guide.ui;

/** Deterministic responsive layout; rendering code consumes these rectangles. */
public record GuideUiLayout(
        boolean narrow,
        Rect topBar,
        Rect sessionRail,
        Rect transcript,
        Rect progress,
        Rect composer,
        Rect detail,
        boolean detailOverlay) {
    public static GuideUiLayout calculate(int width, int height, boolean detailOpen) {
        if (width < 240 || height < 180) throw new IllegalArgumentException("screen is too small");
        int margin = 8;
        int top = 44;
        int progressHeight = 22;
        int progressGap = 4;
        int composerHeight = Math.min(72, Math.max(54, height / 4));
        boolean narrow = width < 560;
        int railWidth = narrow ? 0 : 128;
        boolean inlineDetail = detailOpen && width >= 760;
        int detailWidth = inlineDetail ? 220 : 0;
        int bodyTop = top + margin;
        int composerTop = height - composerHeight;
        int progressTop = composerTop - progressGap - progressHeight;
        int bodyBottom = progressTop - margin;
        int transcriptLeft = margin + railWidth + (railWidth == 0 ? 0 : margin);
        int transcriptRight = width - margin - detailWidth - (detailWidth == 0 ? 0 : margin);
        Rect transcript = new Rect(
                transcriptLeft, bodyTop, transcriptRight - transcriptLeft, bodyBottom - bodyTop);
        Rect detail = !detailOpen
                ? Rect.EMPTY
                : inlineDetail
                        ? new Rect(width - margin - detailWidth, bodyTop, detailWidth, bodyBottom - bodyTop)
                        : new Rect(
                                Math.max(margin, width / 8),
                                bodyTop + 8,
                                Math.min(width - margin * 2, Math.max(220, width * 3 / 4)),
                                Math.max(80, bodyBottom - bodyTop - 16));
        return new GuideUiLayout(
                narrow,
                new Rect(margin, margin, width - margin * 2, top - margin),
                railWidth == 0 ? Rect.EMPTY : new Rect(margin, bodyTop, railWidth, bodyBottom - bodyTop),
                transcript,
                new Rect(transcriptLeft, progressTop, transcript.width(), progressHeight),
                new Rect(transcriptLeft, composerTop, transcript.width(), composerHeight - margin),
                detail,
                detailOpen && !inlineDetail);
    }

    public record Rect(int x, int y, int width, int height) {
        public static final Rect EMPTY = new Rect(0, 0, 0, 0);
        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }
}
