// (Only the relevant added/changed portion is shown within the full file context — integrate into your existing LauncherActivity)
...
import java.net.InetAddress;
import java.net.SocketException;

// inside class LauncherActivity:
private LocalAssetsServer localServer;

@Override
protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // Try to start server with fallback strategy and detailed logs.
    final int port = 12721;
    // Strategy:
    // 1) try default constructor (super(port)) -> NanoHTTPD will choose address
    // 2) if failed, try binding to IPv6 "::" via reflection/alternate constructor if available (optional)
    // 3) if still failed, try binding to "0.0.0.0"
    boolean started = false;
    Exception lastEx = null;

    try {
        localServer = new LocalAssetsServer(port, getAssets());
        localServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        Log.i(TAG, "LocalAssetsServer started (default bind) on port " + port);
        started = true;
    } catch (Exception e) {
        lastEx = e;
        Log.w(TAG, "LocalAssetsServer default bind failed: " + e);
    }

    if (!started) {
        // Try explicit IPv6 bind if supported by NanoHTTPD constructor (host, port)
        try {
            // attempt using reflection to call new LocalAssetsServer with host if you added that constructor;
            // fallback: construct a NanoHTTPD with host "0.0.0.0" if you implemented such constructor.
            LocalAssetsServer s2 = null;
            try {
                // attempt to use a two-arg constructor LocalAssetsServer(String, int, AssetManager) if you add it
                // If not present, skip to next attempt.
                java.lang.reflect.Constructor<?> ctor = LocalAssetsServer.class.getConstructor(String.class, int.class, android.content.res.AssetManager.class);
                s2 = (LocalAssetsServer) ctor.newInstance("::", port, getAssets());
                s2.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                localServer = s2;
                Log.i(TAG, "LocalAssetsServer started on IPv6 (::) port " + port);
                started = true;
            } catch (NoSuchMethodException nsme) {
                // constructor not present; ignore
            }
        } catch (Exception e2) {
            lastEx = e2;
            Log.w(TAG, "LocalAssetsServer IPv6 bind attempt failed: " + e2);
        }
    }

    if (!started) {
        try {
            // Try binding to 0.0.0.0 by reflection constructor if available
            java.lang.reflect.Constructor<?> ctor = null;
            try {
                ctor = LocalAssetsServer.class.getConstructor(String.class, int.class, android.content.res.AssetManager.class);
            } catch (NoSuchMethodException nsme) {
                ctor = null;
            }
            if (ctor != null) {
                LocalAssetsServer s3 = (LocalAssetsServer) ctor.newInstance("0.0.0.0", port, getAssets());
                s3.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                localServer = s3;
                Log.i(TAG, "LocalAssetsServer started on 0.0.0.0 port " + port);
                started = true;
            }
        } catch (Exception e3) {
            lastEx = e3;
            Log.w(TAG, "LocalAssetsServer 0.0.0.0 bind attempt failed: " + e3);
        }
    }

    if (!started) {
        Log.e(TAG, "LocalAssetsServer failed to start on port " + port + " (see earlier logs). Last error: " + lastEx);
        // continue without server — WebView will fallback to file:///android_asset/gjw.html
    }

    // ... existing WebView setup remains unchanged ...
    // webView.loadUrl("file:///android_asset/gjw.html");
}
...
@Override
protected void onDestroy() {
    super.onDestroy();
    try {
        if (localServer != null) {
            localServer.stop();
            Log.i(TAG, "LocalAssetsServer stopped");
        }
    } catch (Throwable t) {
        Log.w(TAG, "Error stopping LocalAssetsServer", t);
    }
    // existing cleanup...
}
