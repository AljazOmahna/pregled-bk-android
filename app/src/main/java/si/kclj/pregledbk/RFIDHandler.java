package si.kclj.pregledbk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.zebra.rfid.RfidServiceMgr;
import com.zebra.rfid.api3.IRFIDDeviceDataCallBack;
import com.zebra.rfid.api3.IRFIDDeviceInterface;

class RFIDHandler {

    static final String TAG = "PregledBK_RFID";

    interface Callback {
        void onTagRead(String epc);
        void onStatus(String msg);
    }

    private final Context context;
    private final Callback callback;
    private RfidServiceMgr rfidMgr;
    private ServiceConnection serviceConnection;

    RFIDHandler(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    void init() {
        if (rfidMgr != null) return;
        try {
            Intent intent = new Intent();
            intent.setClassName("com.zebra.rfid.rfidmanager", "com.zebra.rfid.rfidmanager.RFIDService");
            serviceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    try {
                        IRFIDDeviceInterface deviceInterface = IRFIDDeviceInterface.Stub.asInterface(service);
                        rfidMgr = new RfidServiceMgr(context, deviceInterface);

                        String reader = rfidMgr.GetAvailableReader();
                        Log.d(TAG, "GetAvailableReader: " + reader);

                        if (reader == null || reader.trim().isEmpty()) {
                            callback.onStatus(null);
                            return;
                        }

                        boolean connected = rfidMgr.Connect(reader);
                        Log.d(TAG, "Connect(" + reader + "): " + connected);
                        if (!connected) {
                            callback.onStatus("RFID napaka: " + reader);
                            return;
                        }

                        rfidMgr.addDataListener(new IRFIDDeviceDataCallBack.Stub() {
                            @Override
                            public void onData(String data) throws RemoteException {
                                if (data == null || data.trim().isEmpty()) return;
                                Log.d(TAG, "onData: " + data);
                                String epc = data.trim().replaceAll("[^0-9a-fA-F]", "").toUpperCase();
                                if (!epc.isEmpty()) callback.onTagRead(epc);
                            }

                            @Override
                            public void onStatusChanged(int statusType, String readerName) throws RemoteException {
                                Log.d(TAG, "onStatusChanged: type=" + statusType + " reader=" + readerName);
                                if (statusType == 2) callback.onStatus("RFID odklopljen");
                            }
                        });

                        callback.onStatus("Povezan: " + reader);

                    } catch (Throwable e) {
                        Log.e(TAG, "onServiceConnected: " + e.getMessage(), e);
                        callback.onStatus(null);
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.d(TAG, "Service disconnected");
                    rfidMgr = null;
                    callback.onStatus("RFID odklopljen");
                }
            };

            boolean bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "bindService: " + bound);
            if (!bound) {
                serviceConnection = null;
                callback.onStatus(null);
            }
        } catch (Throwable e) {
            Log.e(TAG, "init: " + e.getMessage(), e);
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
            if (rfidMgr != null) rfidMgr.Disconnect();
        } catch (Exception e) {
            Log.e(TAG, "disconnect: " + e.getMessage());
        }
    }

    void dispose() {
        disconnect();
        try {
            if (rfidMgr != null) rfidMgr.Unbind();
        } catch (Exception e) {
            Log.e(TAG, "dispose unbind: " + e.getMessage());
        }
        try {
            if (serviceConnection != null) {
                context.unbindService(serviceConnection);
                serviceConnection = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "dispose unbindService: " + e.getMessage());
        }
        rfidMgr = null;
    }
}
