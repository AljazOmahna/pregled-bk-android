package si.kclj.pregledbk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

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

        rfidHandler = new RFIDHandler(this, this);
        rfidHandler.init();
    }

    // Called from RFIDHandler when a tag EPC is read
    @Override
    public void onTagRead(String epc) {
        // Sanitize EPC — only hex chars allowed
        final String safe = epc.replaceAll("[^0-9a-fA-F]", "").toUpperCase();
        if (safe.isEmpty()) return;
        Log.d(TAG, "EPC: " + safe);
        mainHandler.post(() ->
            webView.evaluateJavascript("processRawScan('" + safe + "')", null)
        );
    }

    // Called from RFIDHandler for status updates
    @Override
    public void onStatus(String msg) {
        Log.d(TAG, "Status: " + msg);
        mainHandler.post(() ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rfidHandler != null) rfidHandler.init();
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
    }
}
