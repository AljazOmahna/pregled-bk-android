package si.kclj.pregledbk;

import android.content.Context;
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
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... v) {
                try {
                    readers = new Readers(context, ENUM_TRANSPORT.SERVICE_SERIAL);
                    readers.attach(RFIDHandler.this);
                    ArrayList<ReaderDevice> list = readers.GetAvailableRFIDReaderList();
                    Log.d(TAG, "GetAvailableRFIDReaderList: " + (list != null ? list.size() : "null"));
                    if (list != null && !list.isEmpty()) {
                        connectReader(list.get(0));
                    } else {
                        callback.onStatus("RFID: čakam na bralnik...");
                    }
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
            try {
                reader = readerDevice.getRFIDReader();
                reader.connect();
                Log.d(TAG, "Connected: " + readerDevice.getName());

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
