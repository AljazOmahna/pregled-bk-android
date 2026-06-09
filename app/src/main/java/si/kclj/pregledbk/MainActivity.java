package si.kclj.pregledbk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
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
    private WebView webView;
    private RFIDHandler rfidHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
            // Odklopi po 5s — DataWedge mora biti aktiven za barcode skeniranje
            mainHandler.postDelayed(() -> {
                if (rfidHandler != null) {
                    rfidHandler.disconnect();
                    Toast.makeText(this, "V mirovanju: RFD2000", Toast.LENGTH_SHORT).show();
                }
            }, 5000);
        } catch (Throwable t) {
            Log.e(TAG, "RFID init failed: " + t);
        }
    }

    // Called from RFIDHandler (RFID EPC) or DataWedgeHandler (barcode)
    @Override
    public void onTagRead(String epc) {
        // Strip only JS-unsafe chars ('  \  newlines) and non-printable; allow all printable ASCII
        final String safe = epc.replaceAll("['\\\\\r\n]", "").replaceAll("[^\\x20-\\x7E]", "");
        if (safe.isEmpty()) return;
        final String limited = safe.length() > 60 ? safe.substring(0, 60) : safe;
        Log.d(TAG, "EPC: " + limited);
        mainHandler.post(() ->
            webView.evaluateJavascript("processRawScan('" + limited + "')", null)
        );
    }

    @Override
    public void onTriggerEvent(boolean pressed) {
        mainHandler.post(() ->
            webView.evaluateJavascript("onRfidTriggerEvent(" + pressed + ")", null)
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
            if (rfidHandler != null) {
                rfidHandler.enableDataWedgeScanner();  // vedno zagotovi aktiven barcode ob vrnitvi v app
                rfidHandler.init();
            }
        } catch (Throwable t) {
            Log.e(TAG, "RFID resume failed: " + t);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (rfidHandler != null) {
            rfidHandler.enableDataWedgeScanner();
            rfidHandler.disconnect();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rfidHandler != null) rfidHandler.dispose();
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
        public void startRfidSprejemScan() {
            if (rfidHandler != null) rfidHandler.startRfidInventory(600);
        }

        @JavascriptInterface
        public void startMultiRfidScan() {
            if (rfidHandler != null) rfidHandler.startRfidInventory(3000);
        }

        @JavascriptInterface
        public void stopRfidScan() {
            if (rfidHandler != null) rfidHandler.stopSprejemInventory();
        }

        @JavascriptInterface
        public void startRfidWithPower(int cBm) {
            if (rfidHandler != null) rfidHandler.startRfidInventory(cBm);
        }

        @JavascriptInterface
        public void stopAndDisconnectRfid() {
            if (rfidHandler != null) rfidHandler.stopAndDisconnectRfid();
        }

        @JavascriptInterface
        public void enableDataWedge() {
            if (rfidHandler != null) rfidHandler.enableDataWedgeScanner();
        }

        @JavascriptInterface
        public void disableDataWedge() {
            if (rfidHandler != null) rfidHandler.disableDataWedgeScanner();
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
