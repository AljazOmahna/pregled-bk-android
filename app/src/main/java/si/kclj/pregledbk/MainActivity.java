package si.kclj.pregledbk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;

public class MainActivity extends Activity implements RFIDHandler.Callback {

    private static final String TAG = "PregledBK";
    private static final int FILE_CHOOSER_REQ = 2001;
    private static final int STORAGE_PERM_REQ = 2002;
    // DataWedge Intent output — app prejme scan direktno (zanesljivo za WebView)
    private static final String DW_SCAN_ACTION = "si.kclj.pregledbk.SCAN";
    private static final String DW_DATA_KEY    = "com.symbol.datawedge.data_string";
    private WebView webView;
    private RFIDHandler rfidHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> filePathCallback;
    private GraphSync graphSync;
    private ZebraPrint zebraPrint;
    private BroadcastReceiver dwReceiver;
    private boolean pageReady = false;
    private String pendingSharedName = null;
    private String pendingSharedJson = null;

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

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                flushPendingShared();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Izberi CSV"), FILE_CHOOSER_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");
        graphSync = new GraphSync(this);
        zebraPrint = new ZebraPrint(this);
        webView.loadUrl("file:///android_asset/pregled_bk.html");

        ensureStoragePermission();
        handleIncomingIntent(getIntent());

        // DataWedge: registriraj Intent receiver in nastavi profil
        dwReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String data = intent.getStringExtra(DW_DATA_KEY);
                if (data == null || data.isEmpty()) return;
                Log.d(TAG, "DW barcode: " + data.replace("", "<GS>"));
                // Posreduj JS — ista pot kot RFID, GS1 separatorji (0x1D) ostanejo za parser
                final String safe = data.replaceAll("['\\\\]", "").replaceAll("[\\r\\n]", "");
                mainHandler.post(() ->
                    webView.evaluateJavascript("if(typeof processRawScan==='function')processRawScan(" + JSONObject.quote(safe) + ",0)", null)
                );
            }
        };
        registerReceiver(dwReceiver, new IntentFilter(DW_SCAN_ACTION));
        setupDataWedgeProfile();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            java.util.List<String> need = new java.util.ArrayList<>();
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                need.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                need.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            // Android 12+ (API 31): Bluetooth tiskanje rabi runtime dovoljenji
            if (Build.VERSION.SDK_INT >= 31) {
                if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    need.add(android.Manifest.permission.BLUETOOTH_CONNECT);
                if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                    need.add(android.Manifest.permission.BLUETOOTH_SCAN);
            }
            if (!need.isEmpty())
                requestPermissions(need.toArray(new String[0]), 1001);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQ) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{ data.getData() };
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    // Called from RFIDHandler (RFID EPC) or DataWedgeHandler (barcode)
    @Override
    public void onTagRead(String epc, int rssi) {
        // Strip only JS-unsafe chars ('  \  newlines) and non-printable; allow all printable ASCII
        final String safe = epc.replaceAll("['\\\\\r\n]", "").replaceAll("[^\\x20-\\x7E]", "");
        if (safe.isEmpty()) return;
        final String limited = safe.length() > 60 ? safe.substring(0, 60) : safe;
        final int r = rssi;
        Log.d(TAG, "EPC: " + limited + " rssi=" + r);
        mainHandler.post(() ->
            webView.evaluateJavascript("if(typeof processRawScan==='function')processRawScan('" + limited + "'," + r + ")", null)
        );
    }

    @Override
    public void onTriggerEvent(boolean pressed) {
        mainHandler.post(() ->
            webView.evaluateJavascript("if(typeof onRfidTriggerEvent==='function')onRfidTriggerEvent(" + pressed + ")", null)
        );
    }

    // Locate Tag — relativna bližina (0–100) izbrane RFID oznake
    @Override
    public void onLocateUpdate(String epc, int proximity) {
        final String safe = (epc == null ? "" : epc.replaceAll("['\\\\\r\n]", ""));
        final int p = proximity;
        mainHandler.post(() ->
            webView.evaluateJavascript("if(typeof onLocateUpdate==='function')onLocateUpdate('" + safe + "'," + p + ")", null)
        );
    }

    // Called from RFIDHandler for status updates
    @Override
    public void onStatus(String msg) {
        if (msg == null) return; // no reader found — silent
        Log.d(TAG, "Status: " + msg);
        mainHandler.post(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            webView.evaluateJavascript("if(typeof onRfidStatus==='function')onRfidStatus(" + (msg.startsWith("Povezan") ? "true" : "false") + ",'" + msg.replace("'", "\\'") + "')", null);
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
        if (dwReceiver != null) try { unregisterReceiver(dwReceiver); } catch (Exception ignored) {}
    }

    /** Nastavi DataWedge profil za PregledBK: Intent output → DW_SCAN_ACTION, Keystroke off. */
    private void setupDataWedgeProfile() {
        try {
            // Barcode plugin
            Bundle barcodeParams = new Bundle();
            barcodeParams.putString("scanner_input_enabled", "true");
            barcodeParams.putString("decoder_datamatrix",    "true");
            barcodeParams.putString("decoder_code128",       "true");
            barcodeParams.putString("decoder_code39",        "true");
            barcodeParams.putString("decoder_ean13",         "true");
            barcodeParams.putString("decoder_ean8",          "true");
            barcodeParams.putString("decoder_upca",          "true");
            barcodeParams.putString("decoder_qrcode",        "true");
            barcodeParams.putString("decoder_pdf417",        "true");
            barcodeParams.putString("decoder_gs1_databar",       "true");
            barcodeParams.putString("decoder_gs1_databar_exp",   "true");
            Bundle barcodePlugin = new Bundle();
            barcodePlugin.putString("PLUGIN_NAME",   "BARCODE");
            barcodePlugin.putString("RESET_CONFIG",  "false");
            barcodePlugin.putBundle("PARAM_LIST",    barcodeParams);

            // Intent output plugin
            Bundle intentParams = new Bundle();
            intentParams.putString("intent_output_enabled", "true");
            intentParams.putString("intent_action",         DW_SCAN_ACTION);
            intentParams.putString("intent_delivery",       "2"); // broadcast
            Bundle intentPlugin = new Bundle();
            intentPlugin.putString("PLUGIN_NAME",  "INTENT");
            intentPlugin.putString("RESET_CONFIG", "true");
            intentPlugin.putBundle("PARAM_LIST",   intentParams);

            // Keystroke output — izklopi da se ne podvajajo vnosi
            Bundle ksParams = new Bundle();
            ksParams.putString("keystroke_output_enabled", "false");
            Bundle ksPlugin = new Bundle();
            ksPlugin.putString("PLUGIN_NAME",  "KEYSTROKE");
            ksPlugin.putString("RESET_CONFIG", "true");
            ksPlugin.putBundle("PARAM_LIST",   ksParams);

            // App association
            Bundle appAssoc = new Bundle();
            appAssoc.putString("PACKAGE_NAME", getPackageName());
            appAssoc.putStringArray("ACTIVITY_LIST", new String[]{"*"});

            // Profil
            Bundle profile = new Bundle();
            profile.putString("PROFILE_NAME",    "PregledBK");
            profile.putString("PROFILE_ENABLED", "true");
            profile.putString("CONFIG_MODE",     "CREATE_IF_NOT_EXIST");
            profile.putParcelableArrayList("PLUGIN_CONFIG",
                new ArrayList<>(Arrays.asList(barcodePlugin, intentPlugin, ksPlugin)));
            profile.putParcelableArray("APP_LIST", new Bundle[]{ appAssoc });

            Intent i = new Intent("com.symbol.datawedge.api.ACTION");
            i.putExtra("com.symbol.datawedge.api.SET_CONFIG", profile);
            sendBroadcast(i);
            Log.d(TAG, "DataWedge profil nastavljen: Intent output → " + DW_SCAN_ACTION);
        } catch (Throwable t) {
            Log.e(TAG, "setupDataWedgeProfile: " + t);
        }
    }

    // ---- Graph sync helpers ----
    private void runJs(final String js) {
        mainHandler.post(() -> webView.evaluateJavascript(js, null));
    }

    // =========================================================
    // Quick Share — receive a shared/opened .json file and import
    // =========================================================
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    /** Ob zagonu zagotovi dovoljenje za branje shrambe — sicer odpiranje .json prek file:// (npr. iz datotečnega upravitelja) tiho ne uspe. */
    private void ensureStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT < 33
                    && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                       != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE_PERM_REQ);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureStoragePermission: " + t.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERM_REQ
                && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Dovoljenje pravkar odobreno — če nas je zagnal VIEW/SEND intent, ga zdaj ponovno obdelaj
            handleIncomingIntent(getIntent());
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        }
        if (uri == null) return;
        final String name = queryName(uri);
        final String json = readUri(uri);
        if (json == null || json.isEmpty()) {
            // Branje ni uspelo — najpogosteje manjka dovoljenje za shrambo (po brisanju podatkov)
            Toast.makeText(this, "Datoteke ni mogoče prebrati. Dovolite dostop do shrambe in poskusite znova "
                    + "(ali uporabite Več → Uvozi iz datoteke).", Toast.LENGTH_LONG).show();
            Log.w(TAG, "handleIncomingIntent: prazna/neberljiva vsebina za uri=" + uri);
            return;
        }
        pendingSharedName = name;
        pendingSharedJson = json;
        if (pageReady) flushPendingShared();
    }

    private void flushPendingShared() {
        if (pendingSharedJson == null) return;
        final String n = pendingSharedName, j = pendingSharedJson;
        pendingSharedName = null; pendingSharedJson = null;
        runJs("if(typeof onSharedImport==='function')onSharedImport(" + jsStr(n) + "," + jsStr(j) + ")");
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        String s = uri.getLastPathSegment();
        return s != null ? s : "";
    }

    private String readUri(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** Varno zakodira niz v JS string literal (vkljucno za predajo JSON-a). */
    private static String jsStr(String s) {
        if (s == null) return "null";
        return JSONObject.quote(s);
    }

    private GraphSync.Cb graphCb() {
        return new GraphSync.Cb() {
            @Override public void deviceCode(String userCode, String verificationUri, String message) {
                runJs("onMsDeviceCode(" + jsStr(userCode) + "," + jsStr(verificationUri) + "," + jsStr(message) + ")");
            }
            @Override public void signedIn(String account) {
                runJs("onMsSignedIn(" + jsStr(account) + ")");
            }
            @Override public void signedOut() {
                runJs("onMsSignedOut()");
            }
            @Override public void error(String msg) {
                runJs("onMsError(" + jsStr(msg) + ")");
            }
            @Override public void uploadDone(boolean ok, String msg) {
                runJs("onMsUploadDone(" + ok + "," + jsStr(msg) + ")");
            }
            @Override public void downloadDone(String jsonOrNull, String msg) {
                runJs("onMsDownloadDone(" + jsStr(jsonOrNull) + "," + jsStr(msg) + ")");
            }
            @Override public void operatorsDone(String jsonOrNull, String msg) {
                runJs("if(typeof onMsOperatorsDownloaded==='function')onMsOperatorsDownloaded(" + jsStr(jsonOrNull) + "," + jsStr(msg) + ")");
            }
        };
    }

    private ZebraPrint.Cb printCb() {
        return new ZebraPrint.Cb() {
            @Override public void printResult(boolean ok, String msg) {
                runJs("if(typeof onPrintResult==='function')onPrintResult(" + ok + "," + jsStr(msg) + ")");
            }
            @Override public void printers(String jsonArray) {
                runJs("if(typeof onBtPrinters==='function')onBtPrinters(" + jsStr(jsonArray) + ")");
            }
        };
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
        public void startLocateTag(String epc) {
            Log.d(TAG, "JS startLocateTag " + epc + " (handler=" + (rfidHandler != null) + ")");
            if (rfidHandler != null) rfidHandler.startLocateTag(epc, 3000);
        }

        @JavascriptInterface
        public void stopLocateTag() {
            if (rfidHandler != null) rfidHandler.stopLocateTag();
        }

        @JavascriptInterface
        public void restartRfidReader() {
            if (rfidHandler != null) {
                rfidHandler.dispose();
                mainHandler.postDelayed(() -> {
                    rfidHandler = new RFIDHandler(MainActivity.this, MainActivity.this);
                    rfidHandler.init();
                }, 1000);
            }
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

        // ---- Quick Share (Nearby) — share a JSON file via system chooser ----
        @JavascriptInterface
        public void quickShare(String filename, String json) {
            mainHandler.post(() -> {
                try {
                    String name = (filename == null || filename.isEmpty()) ? "pregledBK_db.json" : filename;
                    File dir = new File(getCacheDir(), "share");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, name);
                    try (FileOutputStream fos = new FileOutputStream(f);
                         OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8")) {
                        w.write(json == null ? "" : json);
                    }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this,
                            getPackageName() + ".fileprovider", f);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/json");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_TITLE, name);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Intent chooser = Intent.createChooser(send, "Deli prek Quick Share");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(chooser);
                } catch (Throwable t) {
                    Log.e(TAG, "quickShare: " + t);
                    runJs("if(typeof onQuickShareError==='function')onQuickShareError(" + jsStr(t.getMessage()) + ")");
                }
            });
        }

        @JavascriptInterface
        public void saveToDownloads(String filename, String content) {
            mainHandler.post(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && Build.VERSION.SDK_INT <= 28
                        && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                           != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1001);
                        runJs("if(typeof onSaveToDownloadsDone==='function')onSaveToDownloadsDone(null,'perm')");
                        return;
                    }
                    String name = (filename == null || filename.isEmpty()) ? "export.csv" : filename;
                    java.io.File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File f = new java.io.File(dir, name);
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                         java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(fos, "UTF-8")) {
                        w.write(content == null ? "" : content);
                    }
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)));
                    runJs("if(typeof onSaveToDownloadsDone==='function')onSaveToDownloadsDone(" + jsStr(name) + ")");
                    Toast.makeText(MainActivity.this, "Shranjeno: " + name, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Log.e(TAG, "saveToDownloads: " + t);
                    runJs("if(typeof onSaveToDownloadsDone==='function')onSaveToDownloadsDone(null,'err')");
                }
            });
        }

        @JavascriptInterface
        public void savePdfToDownloads(String filename, String base64) {
            mainHandler.post(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && Build.VERSION.SDK_INT <= 28
                        && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                           != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1001);
                        runJs("if(typeof onSaveToDownloadsDone==='function')onSaveToDownloadsDone(null,'perm')");
                        return;
                    }
                    String name = (filename == null || filename.isEmpty()) ? "export.pdf" : filename;
                    byte[] bytes = android.util.Base64.decode(base64 == null ? "" : base64, android.util.Base64.DEFAULT);
                    java.io.File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File f = new java.io.File(dir, name);
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                        fos.write(bytes);
                    }
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)));
                    runJs("if(typeof onSaveToDownloadsDone==='function')onSaveToDownloadsDone(" + jsStr(name) + ")");
                    Toast.makeText(MainActivity.this, "Shranjeno: " + name, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Log.e(TAG, "savePdfToDownloads: " + t);
                    runJs("if(typeof onSaveToDownloadsDone==='function')onSaveToDownloadsDone(null,'err')");
                }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                mainHandler.post(() -> Toast.makeText(MainActivity.this,
                    "Neveljavna povezava", Toast.LENGTH_SHORT).show());
                return;
            }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) {
                Log.e(TAG, "openUrl: " + t);
                mainHandler.post(() -> Toast.makeText(MainActivity.this,
                    "Ni aplikacije za odpiranje povezave", Toast.LENGTH_SHORT).show());
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

        // ---- Microsoft Graph / OneDrive sinhronizacija ----
        @JavascriptInterface
        public void msSignIn() {
            if (graphSync != null) graphSync.signIn(graphCb());
        }

        @JavascriptInterface
        public void msCancelSignIn() {
            if (graphSync != null) graphSync.cancelSignIn();
        }

        @JavascriptInterface
        public void msSignOut() {
            if (graphSync != null) graphSync.signOut(graphCb());
        }

        @JavascriptInterface
        public boolean msIsSignedIn() {
            return graphSync != null && graphSync.isSignedIn();
        }

        @JavascriptInterface
        public String msGetAccount() {
            return graphSync != null ? graphSync.getAccount() : "";
        }

        @JavascriptInterface
        public long msGetLastSync() {
            return graphSync != null ? graphSync.getLastSync() : 0;
        }

        @JavascriptInterface
        public void msSetConfig(String clientId, String tenantId) {
            if (graphSync != null) graphSync.setConfig(clientId, tenantId);
        }

        @JavascriptInterface
        public void msUpload(String json) {
            if (graphSync != null) graphSync.upload(json, graphCb());
        }

        @JavascriptInterface
        public void msDownload() {
            if (graphSync != null) graphSync.download(graphCb());
        }

        @JavascriptInterface
        public void msDownloadOperators() {
            if (graphSync != null) graphSync.downloadOperators(graphCb());
        }

        @JavascriptInterface
        public void msPdfUpload(String filename, String base64) {
            if (graphSync != null) graphSync.uploadPdf(filename, base64, graphCb());
        }

        @JavascriptInterface
        public void msFileUpload(String subfolder, String filename, String contentType, String base64) {
            if (graphSync != null) {
                byte[] bytes = android.util.Base64.decode(base64 == null ? "" : base64, android.util.Base64.DEFAULT);
                graphSync.uploadFile(subfolder, filename, contentType, bytes, graphCb());
            }
        }

        // ---- Bluetooth tiskanje RFID nalepk (Zebra ZT610R) ----
        @JavascriptInterface
        public void btListPrinters() {
            if (zebraPrint != null) zebraPrint.listPrinters(printCb());
        }

        @JavascriptInterface
        public void printZpl(String mac, String zpl) {
            if (zebraPrint != null) zebraPrint.printZpl(mac, zpl, printCb());
        }

        @JavascriptInterface
        public void btPairPrinter(String mac) {
            if (zebraPrint != null) zebraPrint.pairDevice(mac, printCb());
        }
    }
}
