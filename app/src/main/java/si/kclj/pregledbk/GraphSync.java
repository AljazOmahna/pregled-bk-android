package si.kclj.pregledbk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Microsoft Graph sinhronizacija (OneDrive for Business) prek OAuth2 Device Code Flow.
 * Brez zunanjih knjiznic — HttpURLConnection + org.json.
 *
 * Tok:
 *  1) signIn()  -> devicecode endpoint -> Cb.deviceCode(userCode, verificationUri)
 *                  -> polling token endpoint -> shrani refresh_token -> Cb.signedIn(account)
 *  2) upload(json) / download() uporabita access_token (osvezen iz refresh_token po potrebi)
 *
 * Vsi klici tecejo na ozadnji niti; rezultati se vrnejo prek Cb (klicatelj sam poskrbi za UI nit).
 */
public class GraphSync {

    private static final String TAG = "PregledBK.Graph";

    // consumers = osebni Microsoft računi (Hotmail/Outlook). Za device code flow je to pravi
    // endpoint za osebne račune (common se pri osebnih računih obesi). Za kclj.si org račune
    // zamenjaj v Več → Napredno na "organizations" ali kclj tenant ID.
    private static final String DEFAULT_TENANT = "consumers";
    // BIO Alinity Sync app registration (82b539ce-...) — ista registracija za tablico in Zebro
    private static final String DEFAULT_CLIENT = "82b539ce-e064-4643-a0df-41dde15b39a4";

    private static final String SCOPE = "offline_access Files.ReadWrite User.Read";
    // Mapa DigiLab v OneDrive — isti ključ kot BIO Alinity tablica (pregledBK_db.json)
    private static final String FILE_PATH = "/me/drive/root:/DigiLab/pregledBK_db.json";
    // Imenik operaterjev — vir resnice je BIO Alinity (samo bere se sem)
    private static final String OPERATORS_PATH = "/me/drive/root:/DigiLab/operators.json";

    private static final String PREFS = "ms_auth";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_ACCOUNT = "account";
    private static final String K_CLIENT = "client_id";
    private static final String K_TENANT = "tenant";
    private static final String K_LASTSYNC = "last_sync";

    public interface Cb {
        void deviceCode(String userCode, String verificationUri, String message);
        void signedIn(String account);
        void signedOut();
        void error(String msg);
        void uploadDone(boolean ok, String msg);
        void downloadDone(String jsonOrNull, String msg); // jsonOrNull == null ob 404 (se ni nalozeno)
        void operatorsDone(String jsonOrNull, String msg); // imenik operaterjev iz BIO Alinity
    }

    private final Context ctx;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private volatile boolean cancelLogin = false;

    public GraphSync(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String clientId() {
        String c = prefs().getString(K_CLIENT, "");
        return (c == null || c.trim().isEmpty()) ? DEFAULT_CLIENT : c.trim();
    }

    private String tenant() {
        String t = prefs().getString(K_TENANT, "");
        return (t == null || t.trim().isEmpty()) ? DEFAULT_TENANT : t.trim();
    }

    public boolean isSignedIn() {
        String rt = prefs().getString(K_REFRESH, null);
        return rt != null && !rt.isEmpty();
    }

    public String getAccount() {
        return prefs().getString(K_ACCOUNT, "");
    }

    public long getLastSync() {
        return prefs().getLong(K_LASTSYNC, 0);
    }

    public void setConfig(String clientId, String tenantId) {
        SharedPreferences.Editor e = prefs().edit();
        if (clientId != null) e.putString(K_CLIENT, clientId.trim());
        if (tenantId != null) e.putString(K_TENANT, tenantId.trim());
        e.apply();
    }

    public void signOut(Cb cb) {
        prefs().edit().remove(K_REFRESH).remove(K_ACCOUNT).remove(K_LASTSYNC).apply();
        if (cb != null) cb.signedOut();
    }

    public void cancelSignIn() {
        cancelLogin = true;
    }

    // ---------- DEVICE CODE FLOW ----------

    public void signIn(final Cb cb) {
        cancelLogin = false;
        exec.execute(() -> {
            try {
                String tokenUrl = "https://login.microsoftonline.com/" + tenant() + "/oauth2/v2.0/token";
                String dcUrl = "https://login.microsoftonline.com/" + tenant() + "/oauth2/v2.0/devicecode";

                String body = "client_id=" + enc(clientId()) + "&scope=" + enc(SCOPE);
                String resp = httpPostForm(dcUrl, body);
                JSONObject j = new JSONObject(resp);
                String deviceCode = j.getString("device_code");
                String userCode = j.getString("user_code");
                String verUri = j.optString("verification_uri", "https://microsoft.com/link");
                String message = j.optString("message", "");
                int interval = j.optInt("interval", 5);
                int expiresIn = j.optInt("expires_in", 900);

                cb.deviceCode(userCode, verUri, message);

                long deadline = System.currentTimeMillis() + (expiresIn * 1000L);
                while (System.currentTimeMillis() < deadline) {
                    if (cancelLogin) { cb.error("Prijava preklicana"); return; }
                    Thread.sleep(Math.max(2, interval) * 1000L);
                    if (cancelLogin) { cb.error("Prijava preklicana"); return; }

                    String pollBody = "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                            + "&client_id=" + enc(clientId())
                            + "&device_code=" + enc(deviceCode);
                    String[] pr = httpPostFormRaw(tokenUrl, pollBody);
                    int code = Integer.parseInt(pr[0]);
                    JSONObject pj = new JSONObject(pr[1]);

                    if (code == 200) {
                        saveTokens(pj);
                        String account = fetchAccountName(pj.getString("access_token"));
                        prefs().edit().putString(K_ACCOUNT, account).apply();
                        cb.signedIn(account);
                        return;
                    }
                    String err = pj.optString("error", "");
                    if ("authorization_pending".equals(err)) continue;
                    if ("slow_down".equals(err)) { interval += 5; continue; }
                    if ("expired_token".equals(err) || "code_expired".equals(err)) {
                        cb.error("Koda je potekla — poskusi znova"); return;
                    }
                    if ("authorization_declined".equals(err)) {
                        cb.error("Prijava zavrnjena"); return;
                    }
                    // ostalo = napaka
                    cb.error(pj.optString("error_description", err.isEmpty() ? "Napaka prijave" : err));
                    return;
                }
                cb.error("Koda je potekla — poskusi znova");
            } catch (Throwable t) {
                Log.e(TAG, "signIn: " + t);
                cb.error("Napaka prijave: " + t.getMessage());
            }
        });
    }

    private void saveTokens(JSONObject tok) {
        String rt = tok.optString("refresh_token", "");
        if (!rt.isEmpty()) prefs().edit().putString(K_REFRESH, rt).apply();
    }

    /** Vrne svez access_token iz shranjenega refresh_token (ali null ob napaki). */
    private String freshAccessToken() throws Exception {
        String rt = prefs().getString(K_REFRESH, null);
        if (rt == null || rt.isEmpty()) return null;
        String tokenUrl = "https://login.microsoftonline.com/" + tenant() + "/oauth2/v2.0/token";
        String body = "grant_type=refresh_token"
                + "&client_id=" + enc(clientId())
                + "&refresh_token=" + enc(rt)
                + "&scope=" + enc(SCOPE);
        String[] pr = httpPostFormRaw(tokenUrl, body);
        int code = Integer.parseInt(pr[0]);
        JSONObject pj = new JSONObject(pr[1]);
        if (code == 200) {
            saveTokens(pj); // refresh token se lahko zavrti
            return pj.getString("access_token");
        }
        // refresh ne deluje vec -> odjavi
        prefs().edit().remove(K_REFRESH).apply();
        throw new Exception("Seja je potekla — prijavi se znova");
    }

    private String fetchAccountName(String accessToken) {
        try {
            String r = httpGet("https://graph.microsoft.com/v1.0/me", accessToken);
            JSONObject j = new JSONObject(r);
            String name = j.optString("displayName", "");
            String upn = j.optString("userPrincipalName", j.optString("mail", ""));
            if (!name.isEmpty() && !upn.isEmpty()) return name + " (" + upn + ")";
            if (!upn.isEmpty()) return upn;
            return name;
        } catch (Throwable t) {
            return "OneDrive racun";
        }
    }

    // ---------- UPLOAD / DOWNLOAD ----------

    public void upload(final String json, final Cb cb) {
        cancelLogin = true; // prekini morebitno aktivno signIn polling zanko
        exec.execute(() -> {
            cancelLogin = false;
            try {
                String at = freshAccessToken();
                if (at == null) { cb.uploadDone(false, "Niste prijavljeni"); return; }
                String url = "https://graph.microsoft.com/v1.0" + FILE_PATH + ":/content";
                String[] r = httpPut(url, at, json, "application/json");
                int code = Integer.parseInt(r[0]);
                if (code == 200 || code == 201) {
                    prefs().edit().putLong(K_LASTSYNC, System.currentTimeMillis()).apply();
                    cb.uploadDone(true, "Naloženo v OneDrive");
                } else {
                    cb.uploadDone(false, "Napaka nalaganja (" + code + ")");
                }
            } catch (Throwable t) {
                Log.e(TAG, "upload: " + t);
                cb.uploadDone(false, t.getMessage());
            }
        });
    }

    public void download(final Cb cb) {
        cancelLogin = true; // prekini morebitno aktivno signIn polling zanko
        exec.execute(() -> {
            cancelLogin = false;
            try {
                String at = freshAccessToken();
                if (at == null) { cb.downloadDone(null, "Niste prijavljeni"); return; }
                String url = "https://graph.microsoft.com/v1.0" + FILE_PATH + ":/content";
                String[] r = httpGetRaw(url, at);
                int code = Integer.parseInt(r[0]);
                if (code == 200) {
                    prefs().edit().putLong(K_LASTSYNC, System.currentTimeMillis()).apply();
                    cb.downloadDone(r[1], "Preneseno iz OneDrive");
                } else if (code == 404) {
                    cb.downloadDone(null, "V OneDrive še ni shranjenih podatkov");
                } else {
                    cb.downloadDone(null, "Napaka prenosa (" + code + ")");
                }
            } catch (Throwable t) {
                Log.e(TAG, "download: " + t);
                cb.downloadDone(null, t.getMessage());
            }
        });
    }

    /** Prenese imenik operaterjev (DigiLab/operators.json) — samo bere; vir resnice je BIO Alinity. */
    public void downloadOperators(final Cb cb) {
        cancelLogin = true; // prekini morebitno aktivno signIn polling zanko
        exec.execute(() -> {
            cancelLogin = false;
            try {
                String at = freshAccessToken();
                if (at == null) { cb.operatorsDone(null, "Niste prijavljeni"); return; }
                String url = "https://graph.microsoft.com/v1.0" + OPERATORS_PATH + ":/content";
                String[] r = httpGetRaw(url, at);
                int code = Integer.parseInt(r[0]);
                if (code == 200) {
                    cb.operatorsDone(r[1], "ok");
                } else if (code == 404) {
                    cb.operatorsDone(null, "Imenika operaterjev še ni v OneDrive");
                } else {
                    cb.operatorsDone(null, "Napaka prenosa operaterjev (" + code + ")");
                }
            } catch (Throwable t) {
                Log.e(TAG, "downloadOperators: " + t);
                cb.operatorsDone(null, t.getMessage());
            }
        });
    }

    // ---------- HTTP helpers ----------

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private String httpPostForm(String urlStr, String body) throws Exception {
        String[] r = httpPostFormRaw(urlStr, body);
        if (Integer.parseInt(r[0]) >= 400) throw new Exception("HTTP " + r[0] + ": " + r[1]);
        return r[1];
    }

    /** Vrne [statusCode, body]. */
    private String[] httpPostFormRaw(String urlStr, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setDoOutput(true);
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) { os.write(out); }
        int code = c.getResponseCode();
        String resp = readStream(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        return new String[]{ String.valueOf(code), resp };
    }

    private String httpGet(String urlStr, String bearer) throws Exception {
        String[] r = httpGetRaw(urlStr, bearer);
        if (Integer.parseInt(r[0]) >= 400) throw new Exception("HTTP " + r[0]);
        return r[1];
    }

    private String[] httpGetRaw(String urlStr, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Authorization", "Bearer " + bearer);
        int code = c.getResponseCode();
        String resp = readStream(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        return new String[]{ String.valueOf(code), resp };
    }

    private String[] httpPut(String urlStr, String bearer, String body, String contentType) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestMethod("PUT");
        c.setRequestProperty("Authorization", "Bearer " + bearer);
        c.setRequestProperty("Content-Type", contentType);
        c.setDoOutput(true);
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        try (DataOutputStream os = new DataOutputStream(c.getOutputStream())) { os.write(out); }
        int code = c.getResponseCode();
        String resp = readStream(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        return new String[]{ String.valueOf(code), resp };
    }

    private String[] httpPutBytes(String urlStr, String bearer, byte[] body, String contentType) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestMethod("PUT");
        c.setRequestProperty("Authorization", "Bearer " + bearer);
        c.setRequestProperty("Content-Type", contentType);
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) { os.write(body); }
        int code = c.getResponseCode();
        String resp = readStream(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        return new String[]{ String.valueOf(code), resp };
    }

    public void uploadFile(final String subfolder, final String filename, final String contentType, final byte[] data, final Cb cb) {
        cancelLogin = true;
        exec.execute(() -> {
            cancelLogin = false;
            try {
                String at = freshAccessToken();
                if (at == null) { cb.uploadDone(false, "Niste prijavljeni"); return; }
                String safe = (filename == null || filename.isEmpty()) ? "file.bin"
                            : filename.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
                String sf = (subfolder == null || subfolder.isEmpty()) ? ""
                            : subfolder.replaceAll("[^a-zA-Z0-9_\\-]", "_") + "/";
                String url = "https://graph.microsoft.com/v1.0/me/drive/root:/DigiLab/" + sf + safe + ":/content";
                String[] r = httpPutBytes(url, at, data, contentType);
                int code = Integer.parseInt(r[0]);
                if (code == 200 || code == 201) {
                    cb.uploadDone(true, "Datoteka naložena v OneDrive");
                } else {
                    cb.uploadDone(false, "Napaka nalaganja (" + code + ")");
                }
            } catch (Throwable t) {
                Log.e(TAG, "uploadFile: " + t);
                cb.uploadDone(false, t.getMessage());
            }
        });
    }

    public void uploadPdf(final String filename, final String base64, final Cb cb) {
        cancelLogin = true;
        exec.execute(() -> {
            cancelLogin = false;
            try {
                String at = freshAccessToken();
                if (at == null) { cb.uploadDone(false, "Niste prijavljeni"); return; }
                byte[] bytes = android.util.Base64.decode(base64 == null ? "" : base64, android.util.Base64.DEFAULT);
                String safe = (filename == null || filename.isEmpty()) ? "PregledBK.pdf"
                            : filename.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
                String url = "https://graph.microsoft.com/v1.0/me/drive/root:/DigiLab/Pregled_BK_PDF/" + safe + ":/content";
                String[] r = httpPutBytes(url, at, bytes, "application/pdf");
                int code = Integer.parseInt(r[0]);
                if (code == 200 || code == 201) {
                    cb.uploadDone(true, "PDF naložen v OneDrive");
                } else {
                    cb.uploadDone(false, "Napaka PDF nalaganja (" + code + ")");
                }
            } catch (Throwable t) {
                Log.e(TAG, "uploadPdf: " + t);
                cb.uploadDone(false, t.getMessage());
            }
        });
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }
}
