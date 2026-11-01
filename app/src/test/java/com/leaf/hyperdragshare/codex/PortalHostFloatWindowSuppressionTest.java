package com.leaf.hyperdragshare.codex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class PortalHostFloatWindowSuppressionTest {
    @Test
    public void pendingPortalPreviewSuppressesHostFloatWindowBeforeItBecomesActive() {
        DragShareController controller = new DragShareController(
                RuntimeEnvironment.getApplication(),
                OverlayWindowPolicy.systemHosted());

        assertFalse(controller.shouldSuppressPortalHostFloatWindow());

        controller.reservePortalHostFloatWindowSuppression();
        // The reservation stays inert until an input frame proves the stream is live:
        // suppressing Taplus' float window earlier starves its own drag feed.
        assertFalse(controller.shouldSuppressPortalHostFloatWindow());

        controller.noteRootPointer();
        assertTrue(controller.shouldSuppressPortalHostFloatWindow());

        controller.destroy();

        assertFalse(controller.shouldSuppressPortalHostFloatWindow());
    }
}
