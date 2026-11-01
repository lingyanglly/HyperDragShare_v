package com.leaf.hyperdragshare.codex;

import java.util.List;

/** Applies the documented editable/text/image selection priority at a point. */
final class AccessibilityCandidateSelector {
    private AccessibilityCandidateSelector() {}

    static Selection select(AccessibilityNodeClassifier.Buckets buckets, float x, float y) {
        if (buckets == null) {
            return null;
        }
        AccessibilityCandidate hit = firstContaining(buckets.webEditable, x, y);
        if (hit != null) return new Selection(hit);
        hit = firstContaining(buckets.nativeEditable, x, y);
        if (hit != null) return new Selection(hit);

        // Text and image nodes can both contain the point: a list container reports a text
        // node covering the whole screen while the image inside it is the real target.
        // Pick the more specific (smaller) node so that image wins over the container.
        AccessibilityCandidate nativePick = smaller(
                firstContaining(buckets.nativeText, x, y),
                firstContaining(buckets.nativeNonText, x, y));
        AccessibilityCandidate webPick = smaller(
                firstContaining(buckets.webText, x, y),
                firstContaining(buckets.webNonText, x, y));
        AccessibilityCandidate pick = nativePick != null ? nativePick : webPick;
        return pick == null ? null : new Selection(pick);
    }

    private static AccessibilityCandidate smaller(
            AccessibilityCandidate first,
            AccessibilityCandidate second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return second.area() < first.area() ? second : first;
    }

    private static AccessibilityCandidate firstContaining(
            List<AccessibilityCandidate> candidates,
            float x,
            float y) {
        if (candidates == null) {
            return null;
        }
        for (AccessibilityCandidate candidate : candidates) {
            if (candidate.contains(x, y)) {
                return candidate;
            }
        }
        return null;
    }

    static final class Selection {
        final AccessibilityCandidate candidate;

        Selection(AccessibilityCandidate candidate) {
            this.candidate = candidate;
        }

        boolean isImage() {
            return candidate != null && candidate.kind == AccessibilityCandidate.Kind.IMAGE_REGION;
        }
    }
}
