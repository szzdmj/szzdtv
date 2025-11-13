package org.xwalk.core;

import android.webkit.ConsoleMessage;

/**
 * Minimal XWalkUIClient stub to satisfy adapters that extend XWalkUIClient.
 * Implementations can be kept lightweight: methods return safe defaults.
 */
public class XWalkUIClient {
    protected final XWalkView mView;

    public enum ConsoleMessageType {
        LOG, DEBUG, ERROR, WARNING, TIP, INFO
    }

    public XWalkUIClient(XWalkView view) {
        mView = view;
    }

    /**
     * Compatibility method used by XWalkUIClientAdapter. Returns false by default.
     * Subclasses (adapters) may call super.onConsoleMessage(...).
     */
    public boolean onConsoleMessage(XWalkView view, String message, int lineNumber, String sourceId, ConsoleMessageType messageType) {
        // Default behavior: no further handling.
        return false;
    }
}
