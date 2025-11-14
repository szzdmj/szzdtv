 url=https://github.com/szzdmj/szzdtv/blob/82e9ad38a6bcca57ad71bbaaf72a244609f5e5fd/smartyoutubetv/src/main/java/com/liskovsoft/smartyoutubetv/flavors/exoplayer/player/support/SampleHelpers.java
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
