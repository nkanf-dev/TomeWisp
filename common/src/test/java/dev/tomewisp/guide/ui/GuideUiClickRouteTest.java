package dev.tomewisp.guide.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class GuideUiClickRouteTest {
    private static final GuideUiLayout.Rect DETAIL = new GuideUiLayout.Rect(100, 50, 220, 160);
    private static final GuideUiLayout.Rect ACTION = new GuideUiLayout.Rect(120, 140, 70, 12);

    @Test
    void routesAnEnabledDetailActionBeforePanelDismissal() {
        GuideUiClickRoute route = GuideUiClickRoute.resolveDetail(DETAIL, List.of(ACTION), 130, 145);

        assertEquals(GuideUiClickRoute.Kind.ACTION, route.kind());
        assertEquals(0, route.actionIndex());
    }

    @Test
    void dismissesOnlyWhenTheDetailBackgroundWasClicked() {
        GuideUiClickRoute route = GuideUiClickRoute.resolveDetail(DETAIL, List.of(ACTION), 300, 190);

        assertEquals(GuideUiClickRoute.Kind.DISMISS_DETAIL, route.kind());
        assertEquals(-1, route.actionIndex());
    }

    @Test
    void leavesClicksOutsideTheDetailPanelForNormalRouting() {
        GuideUiClickRoute route = GuideUiClickRoute.resolveDetail(DETAIL, List.of(ACTION), 40, 40);

        assertEquals(GuideUiClickRoute.Kind.OUTSIDE_DETAIL, route.kind());
    }
}
