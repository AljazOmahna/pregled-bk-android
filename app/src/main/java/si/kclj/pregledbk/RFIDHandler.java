package si.kclj.pregledbk;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.util.Log;

import com.zebra.rfid.api3.ENUM_TRANSPORT;
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE;
import com.zebra.rfid.api3.InvalidUsageException;
import com.zebra.rfid.api3.OperationFailureException;
import com.zebra.rfid.api3.RFIDReader;
import com.zebra.rfid.api3.ReaderDevice;
import com.zebra.rfid.api3.Readers;
import com.zebra.rfid.api3.RfidEventsListener;
import com.zebra.rfid.api3.RfidReadEvents;
import com.zebra.rfid.api3.RfidStatusEvents;
import com.zebra.rfid.api3.TagData;

import java.util.ArrayList;

class RFIDHandler implements Readers.RFIDReaderEventHandler {

    static final String TAG = "PregledBK_RFID";

    interface Callback {
        void onTagRead(String epc);
        void onStatus(String msg);
    }

    private final Context context;
    private final Callback callback;
    private Readers readers;
    private RFIDReader reader;

    RFIDHandler(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    void init() {
        if (readers != null) return;
        Log.d(TAG, "init v2 rfidapi3lib");
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... v) {
                try {
                    Log.d(TAG, "new Readers SERVICE_SERIAL");
                    readers = new Readers(context, ENUM_TRANSPORT.SERVICE_SERIAL);
                    readers.attach(RFIDHandler.this);
                    for (int i = 0; i < 12; i++) {
                        ArrayList<ReaderDevice> list = readers.GetAvailableRFIDReaderList();
                        Log.d(TAG, "try " + i + " list: " + (list != null ? list.size() : "null"));
                        if (list != null && !list.isEmpty()) {
                            connectReader(list.get(0));
                            return null;
                        }
                        Thread.sleep(1000);
                    }
                    callback.onStatus("RFID: ni bralnika");
                } catch (Throwable e) {
                    Log.e(TAG, "init: " + e.getMessage(), e);
                    callback.onStatus(null);
                }
                return null;
            }
        }.execute();
    }

    @Override
    public void RFIDReaderAppeared(ReaderDevice readerDevice) {
        Log.d(TAG, "RFIDReaderAppeared: " + readerDevice.getName());
        connectReader(readerDevice);
    }

    @Override
    public void RFIDReaderDisappeared(ReaderDevice readerDevice) {
        Log.d(TAG, "RFIDReaderDisappeared: " + readerDevice.getName());
        reader = null;
        callback.onStatus("RFID odklopljen");
    }

    private void connectReader(final ReaderDevice readerDevice) {
        new Thread(() -> {
            for (int attempt = 1; attempt <= 5; attempt++) {
                try {
                    reader = readerDevice.getRFIDReader();
                    reader.connect();
                    Log.d(TAG, "Connected: " + readerDevice.getName() + " (attempt " + attempt + ")");
                    break; // uspešno — nadaljuj z nastavitvami
                } catch (Throwable e) {
                    Log.w(TAG, "connect attempt " + attempt + " failed: " + e.getMessage());
                    reader = null;
                    if (attempt < 5) {
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    Log.e(TAG, "connectReader failed after 5 attempts");
                    callback.onStatus(null);
                    return;
                }
            }
            try {

                reader.Events.addEventsListener(new RfidEventsListener() {
                    @Override
                    public void eventReadNotify(RfidReadEvents e) {
                        TagData[] tags = reader.Actions.getReadTags(100);
                        if (tags == null) return;
                        for (TagData tag : tags) {
                            String epc = tag.getTagID();
                            Log.d(TAG, "Tag: " + epc);
                            if (epc != null && !epc.isEmpty()) callback.onTagRead(epc);
                        }
                    }

                    @Override
                    public void eventStatusNotify(RfidStatusEvents rfidStatusEvents) {
                        Log.d(TAG, "Status: " + rfidStatusEvents.StatusEventData.getStatusEventType());
                    }
                });

                reader.Events.setTagReadEvent(true);
                reader.Events.setHandheldEvent(true);
                reader.Events.setReaderDisconnectEvent(true);
                reader.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true);

                callback.onStatus("Povezan: " + readerDevice.getName());
            } catch (Throwable e) {
                Log.e(TAG, "connectReader: " + e.getMessage(), e);
                callback.onStatus(null);
            }
        }).start();
    }

    // Sprejem: nastavi max moč, onemogoči DataWedge, začni inventory
    void startSprejemInventory() {
        new Thread(() -> {
            try {
                if (reader == null) {
                    Log.w(TAG, "startSprejemInventory: reader null, poskušam reconnect");
                    if (readers != null) {
                        ArrayList<ReaderDevice> list = readers.GetAvailableRFIDReaderList();
                        if (list != null && !list.isEmpty()) connectReader(list.get(0));
                    }
                    Thread.sleep(3000);
                    if (reader == null) { Log.e(TAG, "startSprejemInventory: še vedno ni bralnika"); return; }
                }
                disableDataWedgeScanner();
                int[] levels = reader.ReaderCapabilities.getTransmitPowerLevelValues();
                if (levels != null && levels.length > 0) {
                    int bestIdx = 0, bestDiff = Math.abs(levels[0] - 3000);
                    for (int i = 1; i < levels.length; i++) {
                        int diff = Math.abs(levels[i] - 3000);
                        if (diff < bestDiff) { bestDiff = diff; bestIdx = i; }
                    }
                    var cfg = reader.Config.Antennas.getAntennaRfConfig(1);
                    cfg.setTransmitPowerIndex(bestIdx);
                    reader.Config.Antennas.setAntennaRfConfig(1, cfg);
                    Log.d(TAG, "Sprejem power: " + levels[bestIdx] + " cBm idx=" + bestIdx);
                }
                reader.Actions.Inventory.perform();
                Log.d(TAG, "Sprejem inventory started, čakam trigger");
            } catch (Exception e) {
                Log.e(TAG, "startSprejemInventory: " + e.getMessage());
            }
        }).start();
    }

    void stopSprejemInventory() {
        new Thread(() -> {
            try {
                if (reader != null) reader.Actions.Inventory.stop();
            } catch (Exception e) {
                Log.e(TAG, "stopSprejemInventory: " + e.getMessage());
            } finally {
                enableDataWedgeScanner();
            }
        }).start();
    }

    void performInventory() {
        new Thread(() -> {
            try {
                if (reader != null) reader.Actions.Inventory.perform();
            } catch (Exception e) {
                Log.e(TAG, "performInventory: " + e.getMessage());
            }
        }).start();
    }

    void stopInventory() {
        new Thread(() -> {
            try {
                if (reader != null) reader.Actions.Inventory.stop();
            } catch (Exception e) {
                Log.e(TAG, "stopInventory: " + e.getMessage());
            }
        }).start();
    }

    private void disableDataWedgeScanner() {
        try {
            Intent i = new Intent("com.symbol.datawedge.api.ACTION");
            i.putExtra("com.symbol.datawedge.api.SCANNER_INPUT_PLUGIN", "DISABLE_PLUGIN");
            context.sendBroadcast(i);
            Log.d(TAG, "DataWedge scanner disabled");
        } catch (Exception e) {
            Log.e(TAG, "disableDataWedge: " + e.getMessage());
        }
    }

    private void enableDataWedgeScanner() {
        try {
            Intent i = new Intent("com.symbol.datawedge.api.ACTION");
            i.putExtra("com.symbol.datawedge.api.SCANNER_INPUT_PLUGIN", "ENABLE_PLUGIN");
            context.sendBroadcast(i);
            Log.d(TAG, "DataWedge scanner enabled");
        } catch (Exception e) {
            Log.e(TAG, "enableDataWedge: " + e.getMessage());
        }
    }

    void disconnect() {
        new Thread(() -> {
            try {
                if (reader != null) {
                    reader.disconnect();
                    reader = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "disconnect: " + e.getMessage());
            }
        }).start();
    }

    void dispose() {
        disconnect();
        try {
            if (readers != null) {
                readers.Dispose();
                readers = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "dispose: " + e.getMessage());
        }
    }
}
