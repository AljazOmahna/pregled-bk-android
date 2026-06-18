package si.kclj.pregledbk;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pošiljanje ZPL na Zebra tiskalnik prek Bluetooth Classic (SPP).
 * Brez Zebra SDK — surov RFCOMM socket (sklad s slogom GraphSync).
 *
 * Vse operacije tečejo na ozadnji niti; rezultati se vrnejo prek Cb
 * (klicatelj sam poskrbi za UI nit).
 */
public class ZebraPrint {

    private static final String TAG = "PregledBK.Print";
    // Standardni Serial Port Profile UUID — Zebra tiskalniki ga uporabljajo za BT Classic.
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public interface Cb {
        void printResult(boolean ok, String msg);
        void printers(String jsonArray); // [{name, mac}]
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    public ZebraPrint(Context ctx) { /* brez stanja — adapter pridobimo ob klicu */ }

    /**
     * Sproži pariranje z napravo po MAC. Deluje tudi brez Discoverable načina —
     * dovolj je, da ima tiskalnik vklopljen BT. Rezultat pride prek printResult().
     */
    public void pairDevice(final String mac, final Cb cb) {
        exec.execute(() -> {
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad == null) { cb.printResult(false, "Naprava nima Bluetooth"); return; }
                if (!ad.isEnabled()) { cb.printResult(false, "Vklopite Bluetooth"); return; }
                if (mac == null || mac.isEmpty()) { cb.printResult(false, "Manjka MAC naslov"); return; }
                BluetoothDevice dev = ad.getRemoteDevice(mac);
                if (dev.getBondState() == BluetoothDevice.BOND_BONDED) {
                    cb.printResult(true, "Tiskalnik je že seznanjen");
                    return;
                }
                boolean started = dev.createBond();
                if (started) {
                    cb.printResult(true, "Pariranje začeto — potrdite na tiskalniku (PIN: 0000)");
                } else {
                    cb.printResult(false, "Pariranje ni uspelo — preverite BT na tiskalniku");
                }
            } catch (SecurityException se) {
                Log.e(TAG, "pairDevice perm: " + se);
                cb.printResult(false, "Manjka dovoljenje za Bluetooth");
            } catch (Throwable t) {
                Log.e(TAG, "pairDevice: " + t);
                cb.printResult(false, "Napaka pariranja: " + t.getMessage());
            }
        });
    }

    /** Vrne seznam seznanjenih (bonded) BT naprav kot JSON [{name, mac}]. */
    public void listPrinters(final Cb cb) {
        exec.execute(() -> {
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad == null) { cb.printResult(false, "Naprava nima Bluetooth"); return; }
                if (!ad.isEnabled()) { cb.printResult(false, "Vklopite Bluetooth"); return; }
                JSONArray arr = new JSONArray();
                Set<BluetoothDevice> bonded = ad.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice d : bonded) {
                        JSONObject o = new JSONObject();
                        String name;
                        try { name = d.getName(); } catch (SecurityException se) { name = null; }
                        o.put("name", name == null ? d.getAddress() : name);
                        o.put("mac", d.getAddress());
                        arr.put(o);
                    }
                }
                cb.printers(arr.toString());
            } catch (SecurityException se) {
                Log.e(TAG, "listPrinters perm: " + se);
                cb.printResult(false, "Manjka dovoljenje za Bluetooth");
            } catch (Throwable t) {
                Log.e(TAG, "listPrinters: " + t);
                cb.printResult(false, "Napaka iskanja tiskalnikov: " + t.getMessage());
            }
        });
    }

    /** Poveže se na tiskalnik po MAC, pošlje ZPL in zapre povezavo. */
    public void printZpl(final String mac, final String zpl, final Cb cb) {
        exec.execute(() -> {
            BluetoothSocket sock = null;
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad == null) { cb.printResult(false, "Naprava nima Bluetooth"); return; }
                if (!ad.isEnabled()) { cb.printResult(false, "Vklopite Bluetooth"); return; }
                if (mac == null || mac.isEmpty()) { cb.printResult(false, "Manjka naslov tiskalnika"); return; }
                if (zpl == null || zpl.isEmpty()) { cb.printResult(false, "Prazen ZPL"); return; }

                BluetoothDevice dev = ad.getRemoteDevice(mac);
                ad.cancelDiscovery(); // odkrivanje upočasni povezavo

                sock = dev.createRfcommSocketToServiceRecord(SPP_UUID);
                sock.connect();
                OutputStream os = sock.getOutputStream();
                os.write(zpl.getBytes(StandardCharsets.UTF_8));
                os.flush();
                // počakaj, da tiskalnik prebere buffer pred zaprtjem
                try { Thread.sleep(400); } catch (InterruptedException ignore) {}
                os.close();
                cb.printResult(true, "Poslano na tiskalnik");
            } catch (SecurityException se) {
                Log.e(TAG, "printZpl perm: " + se);
                cb.printResult(false, "Manjka dovoljenje za Bluetooth");
            } catch (Throwable t) {
                Log.e(TAG, "printZpl: " + t);
                cb.printResult(false, "Napaka tiska: " + t.getMessage());
            } finally {
                if (sock != null) try { sock.close(); } catch (Throwable ignore) {}
            }
        });
    }
}
