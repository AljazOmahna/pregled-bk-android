package si.kclj.pregledbk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;

class DataWedgeHandler {

    static final String TAG = "PregledBK_DW";
    private static final String ACTION_DW_API = "com.symbol.datawedge.api.ACTION";
    private static final String ACTION_SCAN = "si.kclj.pregledbk.SCAN";
    private static final String PROFILE_NAME = "PregledBK";

    private final Context context;
    private final RFIDHandler.Callback callback;
    private BroadcastReceiver receiver;

    DataWedgeHandler(Context context, RFIDHandler.Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    void init() {
        setupProfile();
        registerReceiver();
    }

    void setupProfile() {
        Intent create = new Intent(ACTION_DW_API);
        create.putExtra("com.symbol.datawedge.api.CREATE_PROFILE", PROFILE_NAME);
        context.sendBroadcast(create);

        Bundle barcodePlugin = new Bundle();
        barcodePlugin.putString("PLUGIN_NAME", "BARCODE");
        barcodePlugin.putString("RESET_CONFIG", "true");
        Bundle barcodeParams = new Bundle();
        barcodeParams.putString("scanner_selection", "auto");
        barcodeParams.putString("scanner_input_enabled", "true");
        barcodePlugin.putBundle("PARAM_LIST", barcodeParams);

        Bundle keystrokePlugin = new Bundle();
        keystrokePlugin.putString("PLUGIN_NAME", "KEYSTROKE");
        keystrokePlugin.putString("RESET_CONFIG", "true");
        Bundle keystrokeParams = new Bundle();
        keystrokeParams.putString("keystroke_output_enabled", "false");
        keystrokePlugin.putBundle("PARAM_LIST", keystrokeParams);

        Bundle intentPlugin = new Bundle();
        intentPlugin.putString("PLUGIN_NAME", "INTENT");
        intentPlugin.putString("RESET_CONFIG", "true");
        Bundle intentParams = new Bundle();
        intentParams.putString("intent_output_enabled", "true");
        intentParams.putString("intent_action", ACTION_SCAN);
        intentParams.putString("intent_delivery", "2");
        intentPlugin.putBundle("PARAM_LIST", intentParams);

        ArrayList<Bundle> plugins = new ArrayList<>();
        plugins.add(barcodePlugin);
        plugins.add(keystrokePlugin);
        plugins.add(intentPlugin);

        Bundle appEntry = new Bundle();
        appEntry.putString("PACKAGE_NAME", context.getPackageName());
        appEntry.putStringArray("ACTIVITY_LIST", new String[]{"*"});
        ArrayList<Bundle> appList = new ArrayList<>();
        appList.add(appEntry);

        Bundle config = new Bundle();
        config.putString("PROFILE_NAME", PROFILE_NAME);
        config.putString("PROFILE_ENABLED", "true");
        config.putString("CONFIG_MODE", "UPDATE");
        config.putParcelableArrayList("PLUGIN_CONFIG", plugins);
        config.putParcelableArrayList("APP_LIST", appList);

        Intent setConfig = new Intent(ACTION_DW_API);
        setConfig.putExtra("com.symbol.datawedge.api.SET_CONFIG", config);
        context.sendBroadcast(setConfig);

        Log.d(TAG, "Profile configured");
    }

    private void registerReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String data = intent.getStringExtra("com.symbol.datawedge.data_string");
                Log.d(TAG, "Scan: " + data);
                if (data != null && !data.isEmpty()) {
                    callback.onTagRead(data.trim());
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_SCAN);
        context.registerReceiver(receiver, filter);
        Log.d(TAG, "Receiver registered");
        callback.onStatus("Čitalnik črtnih kod aktiven");
    }

    void dispose() {
        if (receiver != null) {
            try { context.unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiver = null;
        }
    }
}
