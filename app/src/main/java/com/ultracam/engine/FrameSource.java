package com.ultracam.engine;

import java.io.IOException;

/** Supplies the burst frames one at a time so only a few live in memory at once. */
public interface FrameSource {
    int count();
    YuvFrame load(int index) throws IOException;
}
