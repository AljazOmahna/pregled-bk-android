package si.kclj.pregledbk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

class RFIDHandler {

    static final String TAG = "PregledBK_RFID";

    private static final String DW_API_ACTION = "com.symbol.datawedge.api.ACTION";
    private static final String PROFILE_NAME = "PregledBK";
    private static final String SCAN_ACTION = "si.kclj.pregledbk.SCAN";

    interface Callback {
        void onTagRead(String epc);
        void onStatus(String msg);
    }

    private final Context context;
    private final Callback callback;
    private BroadcastReceiver scanReceiver;
    private boolean initialized = false;

    RFIDHandler(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    void init() {
        if (initialized) return;
        try {
            createDataWedgeProfile();
            registerScanReceiver();
            initialized = true;
            Log.d(TAG, "init: DataWedge profile configured");
            callback.onStatus("RFID: pripravljen");
        } catch (Throwable e) {
            Log.e(TAG, "init: " + e.getMessage(), e);
            callback.onStatus(null);
        }
    }

    private void createDataWedgeProfile() {
        // Associate profile with our app
        Bundle appConfig = new Bundle();
        appConfig.putString("PACKAGE_NAME", context.getPackageName());
        appConfig.putStringArray("ACTIVITY_LIST", new String[]{"*"});

        // RFID input plugin
        Bundle rfidParams = new Bundle();
        rfidParams.putString("rfid_input_enabled", "true");
        rfidParams.putString("rfid_beeper_enable", "true");
        rfidParams.putString("rfid_unique_tag_enable", "true");
        Bundle rfidPlugin = new Bundle();
        rfidPlugin.putString("PLUGIN_NAME", "RFID");
        rfidPlugin.putString("RESET_CONFIG", "false");
        rfidPlugin.putBundle("PARAM_LIST", rfidParams);

        // Intent output plugin — broadcast to SCAN_ACTION
        Bundle intentParams = new Bundle();
        intentParams.putString("intent_output_enabled", "true");
        intentParams.putString("intent_action", SCAN_ACTION);
        intentParams.putString("intent_category", "android.intent.category.DEFAULT");
        intentParams.putInt("intent_delivery", 2); // broadcast
        Bundle intentPlugin = new Bundle();
        intentPlugin.putString("PLUGIN_NAME", "INTENT");
        intentPlugin.putString("RESET_CONFIG", "false");
        intentPlugin.putBundle("PARAM_LIST", intentParams);

        Bundle profileConfig = new Bundle();
        profileConfig.putString("PROFILE_NAME", PROFILE_NAME);
        profileConfig.putString("PROFILE_ENABLED", "true");
        profileConfig.putString("CONFIG_MODE", "UPDATE");
        profileConfig.putParcelableArray("APP_LIST", new Bundle[]{appConfig});
        profileConfig.putParcelableArray("PLUGIN_CONFIG", new Bundle[]{rfidPlugin, intentPlugin});

        Intent dwIntent = new Intent(DW_API_ACTION);
        dwIntent.putExtra("com.symbol.datawedge.api.SET_CONFIG", profileConfig);
        context.sendBroadcast(dwIntent);
        Log.d(TAG, "createDataWedgeProfile: sent SET_CONFIG for " + PROFILE_NAME);
    }

    private void registerScanReceiver() {
        scanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String source = intent.getStringExtra("com.symbol.datawedge.source");
                String data = intent.getStringExtra("com.symbol.datawedge.data_string");
                String labelType = intent.getStringExtra("com.symbol.datawedge.label_type");
                Log.d(TAG, "onReceive: source=" + source + " labelType=" + labelType + " data=" + data);

                if (data == null || data.trim().isEmpty()) return;
                String epc = data.trim().replaceAll("[^0-9a-fA-F]", "").toUpperCase();
                if (!epc.isEmpty()) callback.onTagRead(epc);
            }
        };
        context.registerReceiver(scanReceiver, new IntentFilter(SCAN_ACTION));
        Log.d(TAG, "registerScanReceiver: registered for " + SCAN_ACTION);
    }

    void performInventory() {
        Intent intent = new Intent(DW_API_ACTION);
        intent.putExtra("com.symbol.datawedge.api.SOFT_SCAN_TRIGGER", "START_SCANNING");
        context.sendBroadcast(intent);
        Log.d(TAG, "performInventory: START_SCANNING");
    }

    void stopInventory() {
        Intent intent = new Intent(DW_API_ACTION);
        intent.putExtra("com.symbol.datawedge.api.SOFT_SCAN_TRIGGER", "STOP_SCANNING");
        context.sendBroadcast(intent);
        Log.d(TAG, "stopInventory: STOP_SCANNING");
    }

    void disconnect() {
        // DataWedge manages the connection; nothing to do here
    }

    void dispose() {
        if (scanReceiver != null) {
            try { context.unregisterReceiver(scanReceiver); } catch (Exception ignored) {}
            scanReceiver = null;
        }
        initialized = false;
        Log.d(TAG, "dispose");
    }
}
