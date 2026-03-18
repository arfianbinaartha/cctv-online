package org.webrtc;

/**
 * Compatibility shim for WebRTC distributions that reference Environment
 * but do not package the class in the published artifact.
 */
public final class Environment implements AutoCloseable {
    private long nativeEnvironment;

    private Environment(long nativeEnvironment) {
        this.nativeEnvironment = nativeEnvironment;
    }

    public static Builder builder() {
        return new Builder();
    }

    public long ref() {
        return nativeEnvironment;
    }

    @Override
    public void close() {
        if (nativeEnvironment != 0L) {
            nativeFree(nativeEnvironment);
            nativeEnvironment = 0L;
        }
    }

    public static final class Builder {
        private String fieldTrials = "";

        public Builder setFieldTrials(String fieldTrials) {
            this.fieldTrials = fieldTrials == null ? "" : fieldTrials;
            return this;
        }

        public Environment build() {
            return new Environment(nativeCreate(fieldTrials));
        }
    }

    private static native long nativeCreate(String fieldTrials);

    private static native void nativeFree(long nativeEnvironment);
}
