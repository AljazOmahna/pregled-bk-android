package si.kclj.pregledbk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;

public class MainActivity extends Activity implements RFIDHandler.Callback {

    private static final String TAG = "PregledBK";
    private static final String DW_ACTION = "com.symbol.datawedge.api.ACTION";
    private static final String DW_SCAN_ACTION = "si.kclj.pregledbk.SCAN";
    private WebView webView;
    private RFIDHandler rfidHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver dwReceiver;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/pregled_bk.html");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1001);
            }
        }

        try {
            rfidHandler = new RFIDHandler(this, this);
            rfidHandler.init();
        } catch (Throwable t) {
            Log.e(TAG, "RFID init failed: " + t);
        }

        setupDataWedge();
    }

    private void setupDataWedge() {
        // Receive scan data from DataWedge via broadcast
        dwReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String data = intent.getStringExtra("com.symbol.datawedge.data_string");
                if (data != null && !data.isEmpty()) {
                    Log.d(TAG, "DW scan: " + data);
                    onTagRead(data.trim());
                }
            }
        };
        registerReceiver(dwReceiver, new IntentFilter(DW_SCAN_ACTION));

        // Configure DataWedge profile for our app
        mainHandler.postDelayed(this::configureDataWedgeProfile, 2000);
    }

    private void configureDataWedgeProfile() {
        try {
            // Create profile
            Intent create = new Intent(DW_ACTION);
            create.putExtra("com.symbol.datawedge.api.CREATE_PROFILE", "PregledBK");
            sendBroadcast(create);

            // Configure profile settings
            Bundle profileConfig = new Bundle();
            profileConfig.putString("PROFILE_NAME", "PregledBK");
            profileConfig.putString("PROFILE_ENABLED", "true");
            profileConfig.putString("CONFIG_MODE", "UPDATE");

            // Associate with our package
            Bundle appConfig = new Bundle();
            appConfig.putString("PACKAGE_NAME", getPackageName());
            appConfig.putStringArray("ACTIVITY_LIST", new String[]{"*"});
            profileConfig.putParcelableArray("APP_LIST", new Bundle[]{appConfig});

            // Enable barcode scanner
            Bundle barcode = new Bundle();
            barcode.putString("PLUGIN_NAME", "BARCODE");
            barcode.putString("RESET_CONFIG", "true");
            Bundle bParams = new Bundle();
            bParams.putString("scanner_input_enabled", "true");
            bParams.putString("scanner_selection", "auto");
            barcode.putBundle("PARAM_LIST", bParams);

            // Enable Intent output (broadcast to our receiver)
            Bundle intentPlugin = new Bundle();
            intentPlugin.putString("PLUGIN_NAME", "INTENT");
            intentPlugin.putString("RESET_CONFIG", "true");
            Bundle iParams = new Bundle();
            iParams.putString("intent_output_enabled", "true");
            iParams.putString("intent_action", DW_SCAN_ACTION);
            iParams.putString("intent_delivery", "2"); // broadcast
            intentPlugin.putBundle("PARAM_LIST", iParams);

            // Disable keystroke output (we use Intent now)
            Bundle keystroke = new Bundle();
            keystroke.putString("PLUGIN_NAME", "KEYSTROKE");
            keystroke.putString("RESET_CONFIG", "true");
            Bundle kParams = new Bundle();
            kParams.putString("keystroke_output_enabled", "false");
            keystroke.putBundle("PARAM_LIST", kParams);

            profileConfig.putParcelableArray("PLUGIN_CONFIG", new Bundle[]{barcode, intentPlugin, keystroke});

            Intent setConfig = new Intent(DW_ACTION);
            setConfig.putExtra("com.symbol.datawedge.api.SET_CONFIG", profileConfig);
            sendBroadcast(setConfig);

            Log.d(TAG, "DataWedge profile configured");
        } catch (Throwable t) {
            Log.e(TAG, "DataWedge config: " + t);
        }
    }

    // Called from RFIDHandler (RFID EPC) or DataWedgeHandler (barcode)
    @Override
    public void onTagRead(String epc) {
        // Strip only JS-unsafe chars ('  \  newlines) and non-printable; allow all printable ASCII
        final String safe = epc.replaceAll("['\\\\\r\n]", "").replaceAll("[^\\x20-\\x7E]", "");
        if (safe.isEmpty()) return;
        Log.d(TAG, "EPC: " + safe);
        mainHandler.post(() ->
            webView.evaluateJavascript("processRawScan('" + safe + "')", null)
        );
    }

    // Called from RFIDHandler for status updates
    @Override
    public void onStatus(String msg) {
        if (msg == null) return; // no reader found — silent
        Log.d(TAG, "Status: " + msg);
        mainHandler.post(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            webView.evaluateJavascript("onRfidStatus(" + (msg.startsWith("Povezan") ? "true" : "false") + ",'" + msg.replace("'", "\\'") + "')", null);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (rfidHandler != null) rfidHandler.init();
        } catch (Throwable t) {
            Log.e(TAG, "RFID resume failed: " + t);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (rfidHandler != null) rfidHandler.disconnect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rfidHandler != null) rfidHandler.dispose();
        try { if (dwReceiver != null) unregisterReceiver(dwReceiver); } catch (Throwable ignored) {}
    }

    // JavaScript interface — allows HTML to call Android
    class JsBridge {
        @JavascriptInterface
        public void log(String msg) {
            Log.d(TAG, "JS: " + msg);
        }

        @JavascriptInterface
        public String getVersion() {
            return "1.0";
        }

        @JavascriptInterface
        public void startRfidScan() {
            if (rfidHandler != null) rfidHandler.performInventory();
        }

        @JavascriptInterface
        public void stopRfidScan() {
            if (rfidHandler != null) rfidHandler.stopInventory();
        }

        @JavascriptInterface
        public boolean isRfidConnected() {
            return rfidHandler != null;
        }

        @JavascriptInterface
        public String importRfidCsv() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                mainHandler.post(() -> requestPermissions(
                    new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1001));
                Log.w(TAG, "importRfidCsv: no READ_EXTERNAL_STORAGE permission");
                return "NOPERM";
            }
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "inventory");
                Log.d(TAG, "importRfidCsv: dir=" + dir.getAbsolutePath() + " exists=" + dir.exists());
                if (!dir.exists()) return "[]";
                File[] files = dir.listFiles((d, name) -> name.endsWith(".csv"));
                if (files == null || files.length == 0) return "[]";
                Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
                File latest = files[0];
                Log.d(TAG, "importRfidCsv: reading " + latest.getName() + " size=" + latest.length());
                BufferedReader br = new BufferedReader(new FileReader(latest));
                String line;
                boolean headerFound = false;
                ArrayList<String> epcs = new ArrayList<>();
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("TAG ID")) { headerFound = true; continue; }
                    if (!headerFound || line.isEmpty()) continue;
                    String[] parts = line.split(",");
                    if (parts.length < 1) continue;
                    String tagId = parts[0].trim();
                    Log.d(TAG, "importRfidCsv candidate: '" + tagId + "'");
                    if (tagId.matches("[0-9A-Fa-f]{8,}")) epcs.add(tagId.toUpperCase());
                }
                br.close();
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < epcs.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(epcs.get(i)).append("\"");
                }
                sb.append("]");
                Log.d(TAG, "importRfidCsv: " + epcs.size() + " EPCs from " + latest.getName());
                return sb.toString();
            } catch (Throwable t) {
                Log.e(TAG, "importRfidCsv: " + t);
                return "[]";
            }
        }

        @JavascriptInterface
        public void launchApp(String packageName) {
            try {
                Log.d(TAG, "launchApp: " + packageName);
                Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    Log.d(TAG, "launchApp: started " + packageName);
                } else {
                    Log.w(TAG, "launchApp: package not found: " + packageName);
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                        "123RFID ni nameščena", Toast.LENGTH_SHORT).show());
                }
            } catch (Throwable t) {
                Log.e(TAG, "launchApp: " + t);
                mainHandler.post(() -> Toast.makeText(MainActivity.this,
                    "Napaka: " + t.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }
    }
}
