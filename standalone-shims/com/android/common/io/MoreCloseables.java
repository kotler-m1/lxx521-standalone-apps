package com.android.common.io;

import java.io.Closeable;

public final class MoreCloseables {
    private MoreCloseables() {}

    public static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (Exception ignored) {
            // Match the quiet-close behavior of the original utility.
        }
    }
}
