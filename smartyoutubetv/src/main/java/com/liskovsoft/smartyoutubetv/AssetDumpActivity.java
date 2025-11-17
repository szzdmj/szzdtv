package com.liskovsoft.smartyoutubetv;

import android.os.Bundle;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

public class AssetDumpActivity extends AppCompatActivity {
    private static final String TAG = "AssetDump";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Read assets/index.html (if exists) and print first lines to logcat
        try {
            InputStream is = getAssets().open("index.html");
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            int i = 0;
            Log.i(TAG, "=== Begin index.html content (first 80 lines) ===");
            while ((line = br.readLine()) != null && i++ < 80) {
                Log.i(TAG, line);
            }
            Log.i(TAG, "=== End index.html snippet ===");
            br.close();
            is.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read assets/index.html: " + e.getMessage(), e);
        }

        // finish immediately (this activity is only for debug logging)
        finish();
    }
}
