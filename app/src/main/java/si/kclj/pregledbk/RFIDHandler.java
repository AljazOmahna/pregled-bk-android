package si.kclj.pregledbk;

import android.content.Context;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.util.Log;

import com.zebra.rfid.RfidServiceMgr;
import com.zebra.rfid.api3.IRFIDDeviceDataCallBack;

class RFIDHandler {

    static final String TAG = "PregledBK_RFID";

    interface Callback {
        void onTagRead(String epc);
        void onStatus(String msg);
    }

    private final Context context;
    private final Callback callback;
    private RfidServiceMgr rfidMgr;
    private volatile boolean initializing = false;

    RFIDHandler(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    void init() {
        if (initializing) return;
        initializing = true;
        new InitTask().execute();
    }

    private class InitTask extends AsyncTask<Void, Void, String> {
        @Override
        protected String doInBackground(Void... v) {
            try {
                rfidMgr = new RfidServiceMgr(context);
                String reader = rfidMgr.GetAvailableReader();
                Log.d(TAG, "GetAvailableReader: " + reader);

                if (reader == null || reader.trim().isEmpty()) return null;

                boolean connected = rfidMgr.Connect(reader);
                Log.d(TAG, "Connect(" + reader + "): " + connected);
                if (!connected) return "Napaka povezave: " + reader;

                rfidMgr.addDataListener(new IRFIDDeviceDataCallBack.Stub() {
                    @Override
                    public void onData(String data) throws RemoteException {
                        if (data == null || data.trim().isEmpty()) return;
                        Log.d(TAG, "onData: " + data);
                        // Strip non-hex chars, extract EPC
                        String epc = data.trim().replaceAll("[^0-9a-fA-F]", "").toUpperCase();
                        if (!epc.isEmpty()) callback.onTagRead(epc);
                    }

                    @Override
                    public void onStatusChanged(int statusType, String readerName) throws RemoteException {
                        Log.d(TAG, "onStatusChanged: type=" + statusType + " reader=" + readerName);
                        if (statusType == 2) callback.onStatus("RFID odklopljen");
                    }
                });

                return "Povezan: " + reader;

            } catch (Throwable e) {
                Log.e(TAG, "Init error: " + e.getMessage(), e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(String result) {
            initializing = false;
            if (result != null) callback.onStatus(result);
        }
    }

    void performInventory() {
        try {
            if (rfidMgr != null) rfidMgr.Write("START");
        } catch (Exception e) {
            Log.e(TAG, "performInventory: " + e.getMessage());
        }
    }

    void stopInventory() {
        try {
            if (rfidMgr != null) rfidMgr.Write("STOP");
        } catch (Exception e) {
            Log.e(TAG, "stopInventory: " + e.getMessage());
        }
    }

    void disconnect() {
        try {
            if (rfidMgr != null) rfidMgr.Disconnect(rfidMgr.GetAvailableReader());
        } catch (Exception e) {
            Log.e(TAG, "disconnect: " + e.getMessage());
        }
    }

    void dispose() {
        try {
            if (rfidMgr != null) rfidMgr.Unbind();
        } catch (Exception e) {
            Log.e(TAG, "dispose: " + e.getMessage());
        }
    }
}
