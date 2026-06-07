package si.kclj.pregledbk;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import com.zebra.rfid.api3.Antennas;
import com.zebra.rfid.api3.ENUM_TRANSPORT;
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE;
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE;
import com.zebra.rfid.api3.INVENTORY_STATE;
import com.zebra.rfid.api3.InvalidUsageException;
import com.zebra.rfid.api3.OperationFailureException;
import com.zebra.rfid.api3.RFIDReader;
import com.zebra.rfid.api3.ReaderDevice;
import com.zebra.rfid.api3.Readers;
import com.zebra.rfid.api3.RegulatoryConfig;
import com.zebra.rfid.api3.RegionInfo;
import com.zebra.rfid.api3.RfidEventsListener;
import com.zebra.rfid.api3.RfidReadEvents;
import com.zebra.rfid.api3.RfidStatusEvents;
import com.zebra.rfid.api3.SESSION;
import com.zebra.rfid.api3.SL_FLAG;
import com.zebra.rfid.api3.START_TRIGGER_TYPE;
import com.zebra.rfid.api3.STATUS_EVENT_TYPE;
import com.zebra.rfid.api3.STOP_TRIGGER_TYPE;
import com.zebra.rfid.api3.TagData;
import com.zebra.rfid.api3.TriggerInfo;

import java.util.ArrayList;

class RFIDHandler implements Readers.RFIDReaderEventHandler {

    static final String TAG = "PregledBK_RFID";

    interface Callback {
        void onTagRead(String epc);
        void onStatus(String msg);
    }

    private Readers readers;
    private RFIDReader reader;
    private ReaderDevice readerDevice;
    private final Context context;
    private final Callback callback;
    private EventHandler eventHandler;
    private int maxPower = 270;
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
                // Try transports in order — SERVICE_SERIAL for snap-on RFD2000
                ENUM_TRANSPORT[] transports = {
                    ENUM_TRANSPORT.SERVICE_SERIAL,
                    ENUM_TRANSPORT.SERVICE_USB,
                    ENUM_TRANSPORT.BLUETOOTH,
                    ENUM_TRANSPORT.QC_SERIAL
                };

                ArrayList<ReaderDevice> list = null;
                for (ENUM_TRANSPORT t : transports) {
                    try {
                        if (readers == null)
                            readers = new Readers(context, t);
                        else
                            readers.setTransport(t);
                        list = readers.GetAvailableRFIDReaderList();
                        if (list != null && !list.isEmpty()) {
                            Log.d(TAG, "Found reader via " + t.name());
                            break;
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "Transport " + t.name() + " failed: " + e.getMessage());
                        readers = null;
                    }
                }

                if (list == null || list.isEmpty())
                    return null; // no reader — silent, not an error

                readers.attach(RFIDHandler.this);
                readerDevice = list.get(0);
                reader = readerDevice.getRFIDReader();
                reader.connect();

                if (!reader.isConnected())
                    return "Povezava ni uspela";

                // Handle region not configured error
                configureRegion();
                configureReader();
                return "Povezan: " + readerDevice.getName();

            } catch (Throwable e) {
                Log.e(TAG, "Init error: " + e.getMessage(), e);
                return "RFID ni podprt: " + e.getMessage();
            }
        }

        @Override
        protected void onPostExecute(String result) {
            initializing = false;
            callback.onStatus(result);
        }
    }

    private void configureRegion() {
        try {
            RegulatoryConfig regCfg = reader.Config.getRegulatoryConfig();
            if (regCfg != null && (regCfg.getRegion() == null || regCfg.getRegion().isEmpty())) {
                RegionInfo region = reader.ReaderCapabilities.SupportedRegions.getRegionInfo(0);
                regCfg.setRegion(region.getRegionCode());
                regCfg.setIsHoppingOn(region.isHoppingConfigurable());
                regCfg.setEnabledChannels(region.getSupportedChannels());
                reader.Config.setRegulatoryConfig(regCfg);
            }
        } catch (Exception e) {
            Log.w(TAG, "Region config: " + e.getMessage());
        }
    }

    private void configureReader() throws InvalidUsageException, OperationFailureException {
        eventHandler = new EventHandler();
        reader.Events.addEventsListener(eventHandler);
        reader.Events.setHandheldEvent(true);
        reader.Events.setTagReadEvent(true);
        reader.Events.setAttachTagDataWithReadEvent(false);

        // Trigger: start/stop controlled via handheld button events
        TriggerInfo ti = new TriggerInfo();
        ti.StartTrigger.setTriggerType(START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE);
        ti.StopTrigger.setTriggerType(STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE);
        reader.Config.setStartTrigger(ti.StartTrigger);
        reader.Config.setStopTrigger(ti.StopTrigger);

        // Max power
        maxPower = reader.ReaderCapabilities.getTransmitPowerLevelValues().length - 1;
        Antennas.AntennaRfConfig ant = reader.Config.Antennas.getAntennaRfConfig(1);
        ant.setTransmitPowerIndex(maxPower);
        ant.setrfModeTableIndex(0);
        ant.setTari(0);
        reader.Config.Antennas.setAntennaRfConfig(1, ant);

        // Session S0 — reads all tags on each trigger press
        Antennas.SingulationControl sc = reader.Config.Antennas.getSingulationControl(1);
        sc.setSession(SESSION.SESSION_S0);
        sc.Action.setInventoryState(INVENTORY_STATE.INVENTORY_STATE_A);
        sc.Action.setSLFlag(SL_FLAG.SL_ALL);
        reader.Config.Antennas.setSingulationControl(1, sc);

        reader.Actions.PreFilters.deleteAll();
    }

    synchronized void performInventory() {
        try {
            if (reader != null && reader.isConnected())
                reader.Actions.Inventory.perform();
        } catch (Exception e) {
            Log.e(TAG, "perform: " + e.getMessage());
        }
    }

    synchronized void stopInventory() {
        try {
            if (reader != null && reader.isConnected())
                reader.Actions.Inventory.stop();
        } catch (Exception e) {
            Log.e(TAG, "stop: " + e.getMessage());
        }
    }

    synchronized void disconnect() {
        try {
            if (reader != null) {
                if (eventHandler != null)
                    reader.Events.removeEventsListener(eventHandler);
                reader.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, "disconnect: " + e.getMessage());
        }
    }

    synchronized void dispose() {
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

    @Override
    public void RFIDReaderAppeared(ReaderDevice device) {
        Log.d(TAG, "Reader appeared: " + device.getName());
        init();
    }

    @Override
    public void RFIDReaderDisappeared(ReaderDevice device) {
        Log.d(TAG, "Reader disappeared: " + device.getName());
        callback.onStatus("RFID odklopljen");
    }

    class EventHandler implements RfidEventsListener {

        @Override
        public void eventReadNotify(RfidReadEvents e) {
            try {
                TagData[] tags = reader.Actions.getReadTags(100);
                if (tags == null) return;
                for (TagData tag : tags) {
                    String epc = tag.getTagID();
                    if (epc != null && !epc.isEmpty())
                        callback.onTagRead(epc);
                }
            } catch (Exception ex) {
                Log.e(TAG, "read: " + ex.getMessage());
            }
        }

        @Override
        public void eventStatusNotify(RfidStatusEvents e) {
            STATUS_EVENT_TYPE type = e.StatusEventData.getStatusEventType();
            Log.d(TAG, "Status: " + type);

            if (type == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
                HANDHELD_TRIGGER_EVENT_TYPE ev =
                    e.StatusEventData.HandheldTriggerEventData.getHandheldEvent();
                if (ev == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED)
                    new AsyncTask<Void,Void,Void>(){
                        protected Void doInBackground(Void... v){ performInventory(); return null; }
                    }.execute();
                else if (ev == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED)
                    new AsyncTask<Void,Void,Void>(){
                        protected Void doInBackground(Void... v){ stopInventory(); return null; }
                    }.execute();
            }

            if (type == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
                callback.onStatus("RFID odklopljen");
                new AsyncTask<Void,Void,Void>(){
                    protected Void doInBackground(Void... v){ disconnect(); return null; }
                }.execute();
            }
        }
    }
}
