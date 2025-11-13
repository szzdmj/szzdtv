package org.xwalk.core;

import android.webkit.ConsoleMessage;

/**
 * Minimal XWalkUIClient stub to satisfy adapters that extend XWalkUIClient.
 */
public class XWalkUIClient {
    protected final XWalkView mView;

    public enum ConsoleMessageType {
        LOG, DEBUG, ERROR, WARNING, TIP, INFO
    }

    public XWalkUIClient(XWalkView view) {
        mView = view;
    }

    public boolean onConsoleMessage(XWalkView view, String message, int lineNumber, String sourceId, ConsoleMessageType messageType) {
        return false;
    }
}
