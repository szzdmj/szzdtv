package com.liskovsoft.smartyoutubetv.flavors.exoplayer.player.support;

/**
 * Minimal SampleHelpers stub. Real project likely provides richer functionality.
 */
public final class SampleHelpers {
    private SampleHelpers() {}

    public static final class Sample {
        public final String name;
        public final String uri;

        public Sample(String name, String uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    // placeholder helper
    public static Sample createSample(String name, String uri) {
        return new Sample(name, uri);
    }
}
