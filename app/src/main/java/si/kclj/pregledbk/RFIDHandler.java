package si.kclj.pregledbk;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.util.Log;

import com.zebra.rfid.api3.ENUM_TRANSPORT;
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE;
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE;
import com.zebra.rfid.api3.InvalidUsageException;
import com.zebra.rfid.api3.OperationFailureException;
import com.zebra.rfid.api3.RFIDReader;
import com.zebra.rfid.api3.ReaderDevice;
import com.zebra.rfid.api3.Readers;
import com.zebra.rfid.api3.RfidEventsListener;
import com.zebra.rfid.api3.RfidReadEvents;
import com.zebra.rfid.api3.RfidStatusEvents;
import com.zebra.rfid.api3.STATUS_EVENT_TYPE;
import com.zebra.rfid.api3.TagData;

import java.util.ArrayList;

class RFIDHandler implements Readers.RFIDReaderEventHandler {

    static final String TAG = "PregledBK_RFID";

    interface Callback {
        void onTagRead(String epc);
        void onStatus(String msg);
        void onTriggerEvent(boolean pressed);
    }

    private final Context context;
    private final Callback callback;
    private Readers readers;
    private RFIDReader reader;
    private volatile boolean scanArmed = false;

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
                        STATUS_EVENT_TYPE evType = rfidStatusEvents.StatusEventData.getStatusEventType();
                        Log.d(TAG, "Status: " + evType);
                        if (evType == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
                            HANDHELD_TRIGGER_EVENT_TYPE triggerEvent =
                                rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent();
                            Log.d(TAG, "Trigger: " + triggerEvent);
                            boolean pressed = (triggerEvent == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED);
                            if (pressed && scanArmed) {
                                new Thread(() -> {
                                    try { if (reader != null) reader.Actions.Inventory.perform(); }
                                    catch (Exception e) { Log.e(TAG, "trigger perform: " + e.getMessage()); }
                                }).start();
                            } else if (!pressed && scanArmed) {
                                new Thread(() -> {
                                    try { if (reader != null) reader.Actions.Inventory.stop(); }
                                    catch (Exception e) { Log.e(TAG, "trigger stop: " + e.getMessage()); }
                                }).start();
                            }
                            callback.onTriggerEvent(pressed);
                        }
                    }
                });

                reader.Events.setTagReadEvent(true);
                reader.Events.setHandheldEvent(true);
                reader.Events.setReaderDisconnectEvent(true);

                callback.onStatus("Povezan: " + readerDevice.getName());
            } catch (Throwable e) {
                Log.e(TAG, "connectReader: " + e.getMessage(), e);
                callback.onStatus(null);
            }
        }).start();
    }

    // 600 cBm = 6 dBm (sprejem), 3000 cBm = 30 dBm (MultiRFID seja)
    void startRfidInventory(int targetCBm) {
        new Thread(() -> {
            try {
                if (reader == null) {
                    Log.w(TAG, "startRfidInventory: reader null, poskušam reconnect");
                    if (readers != null) {
                        ArrayList<ReaderDevice> list = readers.GetAvailableRFIDReaderList();
                        if (list != null && !list.isEmpty()) connectReader(list.get(0));
                    }
                    Thread.sleep(3000);
                    if (reader == null) { Log.e(TAG, "startRfidInventory: še vedno ni bralnika"); return; }
                }
                reader.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true);
                disableDataWedgeScanner();
                int[] levels = reader.ReaderCapabilities.getTransmitPowerLevelValues();
                if (levels != null && levels.length > 0) {
                    int bestIdx = 0, bestDiff = Math.abs(levels[0] - targetCBm);
                    for (int i = 1; i < levels.length; i++) {
                        int diff = Math.abs(levels[i] - targetCBm);
                        if (diff < bestDiff) { bestDiff = diff; bestIdx = i; }
                    }
                    var cfg = reader.Config.Antennas.getAntennaRfConfig(1);
                    cfg.setTransmitPowerIndex(bestIdx);
                    reader.Config.Antennas.setAntennaRfConfig(1, cfg);
                    Log.d(TAG, "RFID power: " + levels[bestIdx] + " cBm idx=" + bestIdx + " target=" + targetCBm);
                }
                scanArmed = true;
                Log.d(TAG, "RFID armed — čakam trigger");
            } catch (Exception e) {
                Log.e(TAG, "startRfidInventory: " + e.getMessage());
            }
        }).start();
    }

    void stopSprejemInventory() {
        scanArmed = false;
        new Thread(() -> {
            try {
                if (reader != null) {
                    reader.Actions.Inventory.stop();
                    reader.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, false);
                }
            } catch (Exception e) {
                Log.e(TAG, "stopSprejemInventory: " + e.getMessage());
            } finally {
                enableDataWedgeScanner();
            }
        }).start();
    }

    void stopAndDisconnectRfid() {
        scanArmed = false;
        new Thread(() -> {
            try {
                if (reader != null) {
                    reader.Actions.Inventory.stop();
                    reader.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, false);
                    reader.disconnect();
                    reader = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "stopAndDisconnectRfid: " + e.getMessage());
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
