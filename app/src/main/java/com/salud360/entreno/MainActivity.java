package com.salud360.entreno;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int REQ_FOLDER = 8001;
    private static final int REQ_MIC = 8002;
    private static final String PREFS = "salud360_prefs";
    private static final String KEY_TREE = "drive_tree_uri";
    private static final String CACHE_FILE = "salud360_cache.json";
    private static final String EXEC_LOCAL_FILE = "training_execution.json";
    private static final String VOICE_FOLDER = "voice_pending";
    private static final String DRIVE_VOICE_FOLDER = "05_Sincronizacion_Offline/Pendientes_Voz";
    private static final String DEFAULT_EXEC_PATH = "05_Sincronizacion_Offline/Entrenamiento/ejecucion.json";
    private static final int READ_RETRIES = 5;

    private WebView web;
    private SharedPreferences prefs;
    private boolean pageReady = false;
    private MediaRecorder recorder;
    private File activeAudio;
    private File activeMeta;
    private String activeSession = "";
    private String activeExercise = "";
    private final AtomicBoolean syncInFlight = new AtomicBoolean(false);
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (hasFolder()) syncNow();
                else web.evaluateJavascript("window.showSetup && window.showSetup()", null);
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setKeepScreenOn(true);
        setContentView(web);
        registerConnectivityCallback();
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady && hasFolder()) syncNow();
    }

    @Override
    protected void onDestroy() {
        stopRecorderQuietly();
        unregisterConnectivityCallback();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (pageReady) {
            web.evaluateJavascript("window.handleAndroidBack && window.handleAndroidBack()", null);
        } else {
            super.onBackPressed();
        }
    }

    private void registerConnectivityCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (pageReady && hasFolder()) syncNow();
                }
                @Override public void onLost(Network network) {
                    if (pageReady) runOnUiThread(() ->
                            web.evaluateJavascript(
                                    "document.getElementById('syncLabel')&&(document.getElementById('syncLabel').textContent='OFFLINE');" +
                                    "document.getElementById('syncLabel')&&document.getElementById('syncLabel').classList.add('offline');" +
                                    "document.getElementById('offlineBanner')&&document.getElementById('offlineBanner').classList.remove('hidden');",
                                    null));
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
    }

    private void unregisterConnectivityCallback() {
        if (networkCallback == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
        networkCallback = null;
    }

    private boolean hasFolder() {
        return !prefs.getString(KEY_TREE, "").isEmpty();
    }

    private Uri getTreeUri() {
        String s = prefs.getString(KEY_TREE, "");
        return s.isEmpty() ? null : Uri.parse(s);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null
                    && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int flags = data.getFlags() &
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) {}
            prefs.edit().putString(KEY_TREE, uri.toString()).apply();
            Toast.makeText(this, "Carpeta Salud 360 vinculada", Toast.LENGTH_SHORT).show();
            syncNow();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            notifyVoice(ok ? "ready" : "denied",
                    ok ? "Micrófono preparado" : "Permiso de micrófono denegado");
        }
    }

    private Uri rootDocument(Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    private Uri findChildOnce(Uri treeUri, Uri parent, String name) throws Exception {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor c = getContentResolver().query(children, projection, null, null, null)) {
            if (c == null) return null;
            int idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (c.moveToNext()) {
                if (name.equals(c.getString(nameCol))) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(idCol));
                }
            }
        }
        return null;
    }

    private Uri findChild(Uri treeUri, Uri parent, String name) throws Exception {
        Uri found = null;
        Exception last = null;
        for (int i = 0; i < READ_RETRIES; i++) {
            try {
                found = findChildOnce(treeUri, parent, name);
                if (found != null) return found;
            } catch (Exception e) {
                last = e;
            }
            try { Thread.sleep(250L + (i * 250L)); } catch (InterruptedException ignored) {}
        }
        if (last != null) throw last;
        return null;
    }

    private Uri resolveRelative(String relativePath) throws Exception {
        Uri tree = getTreeUri();
        if (tree == null) return null;
        Uri current = rootDocument(tree);
        for (String part : relativePath.split("/")) {
            if (part.trim().isEmpty()) continue;
            current = findChild(tree, current, part);
            if (current == null) return null;
        }
        return current;
    }

    private String readTextOnce(String relativePath) throws Exception {
        Uri uri = resolveRelative(relativePath);
        if (uri == null) throw new Exception("No encuentro " + relativePath);
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("No puedo abrir " + relativePath);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String readText(String relativePath) throws Exception {
        Exception last = null;
        for (int i = 0; i < READ_RETRIES; i++) {
            try { return readTextOnce(relativePath); }
            catch (Exception e) { last = e; }
            try { Thread.sleep(350L + (i * 300L)); } catch (InterruptedException ignored) {}
        }
        throw last != null ? last : new Exception("No puedo leer " + relativePath);
    }

    private JSONObject readJsonOptional(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) return new JSONObject();
        try { return new JSONObject(readText(relativePath)); }
        catch (Exception e) { return new JSONObject(); }
    }

    private String readImageDataUrl(String relativePath) {
        if (!isOnline()) return "";
        try {
            Uri uri = resolveRelative(relativePath);
            if (uri == null) return "";
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return "";
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                String lower = relativePath.toLowerCase(Locale.ROOT);
                String mime = lower.endsWith(".png") ? "image/png"
                        : lower.endsWith(".webp") ? "image/webp" : "image/jpeg";
                return "data:" + mime + ";base64,"
                        + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            }
        } catch (Exception e) {
            return "";
        }
    }

    private String folderName() {
        Uri tree = getTreeUri();
        if (tree == null) return "";
        try {
            Uri root = rootDocument(tree);
            try (Cursor c = getContentResolver().query(
                    root, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            }
        } catch (Exception ignored) {}
        return "Carpeta seleccionada";
    }

    private String firstPath(JSONObject obj, String... keys) {
        if (obj == null) return "";
        for (String key : keys) {
            String s = obj.optString(key, "");
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    private File executionLocalFile() {
        return new File(getFilesDir(), EXEC_LOCAL_FILE);
    }

    private JSONObject loadLocalExecution() {
        File f = executionLocalFile();
        if (!f.exists()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void saveLocalExecution(JSONObject obj) {
        try (FileOutputStream out = new FileOutputStream(executionLocalFile())) {
            out.write(obj.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private String executionPath(JSONObject config) {
        JSONObject execution = config.optJSONObject("execution");
        if (execution != null) {
            String p = execution.optString("drive_file", "");
            if (!p.isEmpty()) return p;
        }
        JSONObject moduleFiles = config.optJSONObject("module_files");
        if (moduleFiles != null) {
            String p = moduleFiles.optString("training_execution", "");
            if (!p.isEmpty()) return p;
        }
        return DEFAULT_EXEC_PATH;
    }

    private String parentPath(String relativeFilePath) {
        int p = relativeFilePath.lastIndexOf('/');
        return p < 0 ? "" : relativeFilePath.substring(0, p);
    }

    private String fileName(String relativeFilePath) {
        int p = relativeFilePath.lastIndexOf('/');
        return p < 0 ? relativeFilePath : relativeFilePath.substring(p + 1);
    }

    private void writeJsonToDrive(String relativeFilePath, JSONObject obj) throws Exception {
        File tmp = new File(getCacheDir(), fileName(relativeFilePath));
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(obj.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        writeFileToDrive(parentPath(relativeFilePath), tmp, "application/json");
        tmp.delete();
    }

    private JSONObject mergedExecution(JSONObject config) {
        String path = executionPath(config);
        JSONObject drive = readJsonOptional(path);
        JSONObject local = loadLocalExecution();
        long driveTs = drive.optLong("updated_at_ms", 0L);
        long localTs = local.optLong("updated_at_ms", 0L);

        JSONObject chosen;
        if (localTs > driveTs) {
            chosen = local;
            if (isOnline()) {
                try { writeJsonToDrive(path, local); } catch (Exception ignored) {}
            }
        } else {
            chosen = drive.length() > 0 ? drive : local;
            if (driveTs > 0 && driveTs >= localTs) saveLocalExecution(drive);
        }

        if (chosen == null || chosen.length() == 0) {
            chosen = new JSONObject();
            try {
                chosen.put("schema_version", 1);
                chosen.put("module", "training_execution");
                chosen.put("updated_at_ms", 0);
                chosen.put("updated_by", "none");
                chosen.put("sessions", new JSONObject());
            } catch (Exception ignored) {}
        }
        return chosen;
    }

    private JSONObject buildPayload() throws Exception {
        JSONObject config = new JSONObject(readText("00_Core/app_config.json"));
        JSONObject plan = new JSONObject(readText(
                config.optString("plan_file", "01_Plan_Sesiones/plan.json")));
        JSONObject library = new JSONObject(readText(
                config.optString("exercise_library_file",
                        "02_Biblioteca_Ejercicios/ejercicios.json")));

        JSONObject root = new JSONObject();
        root.put("ok", true);
        root.put("offline", false);
        root.put("folder", folderName());
        root.put("config", config);
        root.put("plan", plan);
        root.put("library", library);

        JSONObject modules = new JSONObject();
        JSONObject moduleFiles = config.optJSONObject("module_files");
        if (moduleFiles != null) {
            String p;

            p = firstPath(moduleFiles, "hiit");
            if (!p.isEmpty()) modules.put("hiit", readJsonOptional(p));

            JSONObject food = new JSONObject();
            p = firstPath(moduleFiles, "food_plan", "alimentacion_plan");
            if (!p.isEmpty()) food.put("plan", readJsonOptional(p));
            p = firstPath(moduleFiles, "food_history", "alimentacion_historial");
            if (!p.isEmpty()) food.put("history", readJsonOptional(p));
            p = firstPath(moduleFiles, "food_recipes", "alimentacion_recetas");
            if (!p.isEmpty()) food.put("recipes", readJsonOptional(p));
            p = firstPath(moduleFiles, "food_links", "alimentacion_enlaces");
            if (!p.isEmpty()) food.put("links", readJsonOptional(p));
            modules.put("alimentacion", food);

            p = firstPath(moduleFiles, "pantry");
            if (!p.isEmpty()) modules.put("pantry", readJsonOptional(p));

            p = firstPath(moduleFiles, "shopping");
            if (!p.isEmpty()) modules.put("shopping", readJsonOptional(p));

            p = firstPath(moduleFiles, "consumables");
            if (!p.isEmpty()) modules.put("consumables", readJsonOptional(p));

            p = firstPath(moduleFiles, "sleep");
            if (!p.isEmpty()) modules.put("sleep", readJsonOptional(p));

            p = firstPath(moduleFiles, "supplements");
            if (!p.isEmpty()) modules.put("supplements", readJsonOptional(p));
        }

        JSONObject execution = mergedExecution(config);
        modules.put("training_execution", execution);
        root.put("execution", execution);
        root.put("modules", modules);
        root.put("synced_at", System.currentTimeMillis());
        updateWidgetSnapshot(root);
        return root;
    }

    private void updateWidgetSnapshot(JSONObject root) {
        try {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String training = "Sin sesión prevista hoy";
            JSONObject plan = root.optJSONObject("plan");
            JSONArray ss = plan == null ? null : plan.optJSONArray("sessions");
            if (ss != null) {
                JSONObject next = null;
                for (int i = 0; i < ss.length(); i++) {
                    JSONObject s = ss.optJSONObject(i);
                    if (s == null) continue;
                    String d = s.optString("date", "");
                    if (today.equals(d)) {
                        training = s.optString("title", "Entrenamiento de hoy");
                        next = null;
                        break;
                    }
                    if (next == null && d.compareTo(today) > 0) next = s;
                }
                if (training.equals("Sin sesión prevista hoy") && next != null) {
                    training = "Próxima: " + next.optString("title", "sesión");
                }
            }
            int shopping = 0;
            JSONObject modules = root.optJSONObject("modules");
            JSONObject shop = modules == null ? null : modules.optJSONObject("shopping");
            JSONArray items = shop == null ? null : shop.optJSONArray("items");
            if (items != null) shopping = items.length();

            prefs.edit()
                    .putString("widget_training", training)
                    .putString("widget_secondary", shopping == 0
                            ? "Compra: sin pendientes"
                            : "Compra: " + shopping + " pendiente" + (shopping == 1 ? "" : "s"))
                    .apply();

            AppWidgetManager awm = AppWidgetManager.getInstance(this);
            ComponentName cn = new ComponentName(this, Salud360Widget.class);
            int[] ids = awm.getAppWidgetIds(cn);
            Intent intent = new Intent(this, Salud360Widget.class);
            intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
            sendBroadcast(intent);
        } catch (Exception ignored) {}
    }

    private String isoDay(Calendar c) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    private JSONArray filterDateArray(JSONArray src, int before, int after) {
        JSONArray out = new JSONArray();
        if (src == null) return out;
        Calendar a = Calendar.getInstance(); a.add(Calendar.DAY_OF_YEAR, -before);
        Calendar b = Calendar.getInstance(); b.add(Calendar.DAY_OF_YEAR, after);
        String min = isoDay(a), max = isoDay(b);
        for (int i = 0; i < src.length(); i++) {
            JSONObject x = src.optJSONObject(i);
            if (x == null) continue;
            String d = x.optString("date", "");
            if (!d.isEmpty() && d.compareTo(min) >= 0 && d.compareTo(max) <= 0) out.put(x);
        }
        return out;
    }

    private JSONObject pruneForCache(JSONObject source) throws Exception {
        JSONObject c = new JSONObject(source.toString());
        JSONObject cfg = c.optJSONObject("config");
        int before = 7, after = 7;
        if (cfg != null) {
            JSONObject off = cfg.optJSONObject("offline");
            if (off != null) {
                before = off.optInt("window_days_before", 7);
                after = off.optInt("window_days_after", 7);
            }
        }

        JSONObject plan = c.optJSONObject("plan");
        if (plan != null && plan.has("sessions")) {
            plan.put("sessions", filterDateArray(plan.optJSONArray("sessions"), before, after));
        }

        JSONObject modules = c.optJSONObject("modules");
        JSONObject food = modules == null ? null : modules.optJSONObject("alimentacion");
        if (food != null) {
            JSONObject fp = food.optJSONObject("plan");
            if (fp != null && fp.has("days")) {
                fp.put("days", filterDateArray(fp.optJSONArray("days"), before, after));
            }
            JSONObject fh = food.optJSONObject("history");
            if (fh != null && fh.has("days")) {
                fh.put("days", filterDateArray(fh.optJSONArray("days"), before, after));
            }
        }
        return c;
    }

    private File cacheFile() {
        return new File(getFilesDir(), CACHE_FILE);
    }

    private void saveCache(JSONObject payload) {
        try (FileOutputStream out = new FileOutputStream(cacheFile())) {
            out.write(pruneForCache(payload).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private JSONObject loadCache() {
        File f = cacheFile();
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONObject p = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            p.put("ok", true);
            p.put("offline", true);
            p.put("folder", folderName());

            JSONObject localExec = loadLocalExecution();
            if (localExec.length() > 0) {
                p.put("execution", localExec);
                JSONObject modules = p.optJSONObject("modules");
                if (modules == null) {
                    modules = new JSONObject();
                    p.put("modules", modules);
                }
                modules.put("training_execution", localExec);
            }
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private void sendPayload(JSONObject p) {
        runOnUiThread(() ->
                web.evaluateJavascript(
                        "window.onNativeSync(" + JSONObject.quote(p.toString()) + ")", null));
    }

    private void syncNow() {
        if (!syncInFlight.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                if (!isOnline()) {
                    JSONObject cached = loadCache();
                    if (cached != null) {
                        sendPayload(cached);
                        return;
                    }
                }

                JSONObject payload = buildPayload();
                payload.put("offline", !isOnline());
                if (isOnline()) saveCache(payload);
                sendPayload(payload);

                if (isOnline()) {
                    syncPendingVoiceNotes();
                    JSONObject localExec = loadLocalExecution();
                    if (localExec.length() > 0) {
                        try {
                            JSONObject cfg = payload.optJSONObject("config");
                            writeJsonToDrive(executionPath(cfg == null ? new JSONObject() : cfg),
                                    localExec);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                JSONObject cached = loadCache();
                if (cached != null) {
                    try { cached.put("sync_error", e.getMessage()); } catch (Exception ignored) {}
                    sendPayload(cached);
                    return;
                }
                try {
                    JSONObject err = new JSONObject();
                    err.put("ok", false);
                    err.put("error", e.getMessage());
                    err.put("folder", folderName());
                    sendPayload(err);
                } catch (Exception ignored) {}
            } finally {
                syncInFlight.set(false);
            }
        }).start();
    }

    private void saveTrainingExecution(String json) {
        new Thread(() -> {
            try {
                JSONObject obj = new JSONObject(json);
                if (!obj.has("updated_at_ms") || obj.optLong("updated_at_ms", 0L) <= 0) {
                    obj.put("updated_at_ms", System.currentTimeMillis());
                }
                saveLocalExecution(obj);

                if (isOnline() && hasFolder()) {
                    JSONObject config;
                    try { config = new JSONObject(readText("00_Core/app_config.json")); }
                    catch (Exception e) { config = new JSONObject(); }
                    try { writeJsonToDrive(executionPath(config), obj); }
                    catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void cue(boolean longCue) {
        runOnUiThread(() -> {
            try {
                ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_ALARM, 95);
                tg.startTone(
                        longCue ? ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
                                : ToneGenerator.TONE_PROP_BEEP2,
                        longCue ? 850 : 300);
            } catch (Exception ignored) {}
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null) {
                    long[] pattern = longCue
                            ? new long[]{0,220,120,220,120,450}
                            : new long[]{0,180};
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                }
            } catch (Exception ignored) {}
        });
    }

    private File voiceDir() {
        File d = new File(getFilesDir(), VOICE_FOLDER);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void requestMicIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        } else {
            notifyVoice("ready", "Micrófono preparado");
        }
    }

    private void startVoice(String session, String exercise) {
        if (recorder != null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestMicIfNeeded();
            return;
        }
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            activeAudio = new File(voiceDir(), "voz_" + stamp + ".m4a");
            activeMeta = new File(voiceDir(), "voz_" + stamp + ".json");
            activeSession = session == null ? "" : session;
            activeExercise = exercise == null ? "" : exercise;

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(activeAudio.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            writeVoiceMeta("pending");
            notifyVoice("recording", "Grabando nota de voz");
        } catch (Exception e) {
            stopRecorderQuietly();
            notifyVoice("error", e.getMessage());
        }
    }

    private void writeVoiceMeta(String syncStatus) {
        if (activeMeta == null || activeAudio == null) return;
        try {
            JSONObject m = new JSONObject();
            m.put("schema_version", 1);
            m.put("created_at",
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date()));
            m.put("session", activeSession);
            m.put("exercise", activeExercise);
            m.put("audio_file", activeAudio.getName());
            m.put("sync_status", syncStatus);
            m.put("processing_status", "pending");
            try (FileOutputStream out = new FileOutputStream(activeMeta)) {
                out.write(m.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private void stopVoice() {
        if (recorder == null) return;
        try { recorder.stop(); } catch (Exception ignored) {}
        try { recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        writeVoiceMeta("pending");
        notifyVoice("saved", isOnline()
                ? "Guardada; sincronizando con Drive"
                : "Guardada en el móvil; se subirá al volver Internet");
        if (isOnline() && hasFolder()) new Thread(this::syncPendingVoiceNotes).start();
        activeAudio = null;
        activeMeta = null;
        activeSession = "";
        activeExercise = "";
    }

    private void stopRecorderQuietly() {
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
    }

    private void notifyVoice(String status, String detail) {
        runOnUiThread(() -> {
            if (pageReady) {
                web.evaluateJavascript(
                        "window.onVoiceStatus && window.onVoiceStatus("
                                + JSONObject.quote(status) + ","
                                + JSONObject.quote(detail == null ? "" : detail) + ")",
                        null);
            }
        });
    }

    private void copyFileToUri(File src, Uri dest) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = getContentResolver().openOutputStream(dest, "wt")) {
            if (out == null) throw new Exception("No puedo escribir en Drive");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private void writeFileToDrive(String folderPath, File src, String mime) throws Exception {
        Uri tree = getTreeUri();
        Uri folder = folderPath == null || folderPath.isEmpty()
                ? rootDocument(tree) : resolveRelative(folderPath);
        if (tree == null || folder == null) throw new Exception("No encuentro " + folderPath);
        Uri dest = findChild(tree, folder, src.getName());
        if (dest == null) {
            dest = DocumentsContract.createDocument(
                    getContentResolver(), folder, mime, src.getName());
        }
        if (dest == null) throw new Exception("No puedo crear " + src.getName());
        copyFileToUri(src, dest);
    }

    private void syncPendingVoiceNotes() {
        if (!isOnline() || !hasFolder()) return;
        File[] files = voiceDir().listFiles((dir, name) -> name.endsWith(".m4a"));
        if (files == null) return;

        int synced = 0;
        for (File audio : files) {
            String base = audio.getName().substring(0, audio.getName().length() - 4);
            File meta = new File(voiceDir(), base + ".json");
            try {
                writeFileToDrive(DRIVE_VOICE_FOLDER, audio, "audio/mp4");
                if (meta.exists()) {
                    try {
                        String txt;
                        try (FileInputStream in = new FileInputStream(meta)) {
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            byte[] b = new byte[4096];
                            int n;
                            while ((n = in.read(b)) > 0) out.write(b, 0, n);
                            txt = out.toString(StandardCharsets.UTF_8.name());
                        }
                        JSONObject m = new JSONObject(txt);
                        m.put("sync_status", "synced");
                        try (FileOutputStream out = new FileOutputStream(meta)) {
                            out.write(m.toString(2).getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (Exception ignored) {}
                    writeFileToDrive(DRIVE_VOICE_FOLDER, meta, "application/json");
                }

                audio.delete();
                if (meta.exists()) meta.delete();
                synced++;
            } catch (Exception ignored) {}
        }

        if (synced > 0) {
            notifyVoice("synced",
                    synced + (synced == 1 ? " nota subida a Drive" : " notas subidas a Drive"));
        }
    }

    private int pendingVoiceCount() {
        File[] f = voiceDir().listFiles((dir, name) -> name.endsWith(".m4a"));
        return f == null ? 0 : f.length;
    }

    private void openUrl(String url) {
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme();
            if (!"http".equalsIgnoreCase(scheme)
                    && !"https".equalsIgnoreCase(scheme)) return;
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception ignored) {}
    }

    private class Bridge {
        @JavascriptInterface public void chooseFolder() {
            runOnUiThread(() -> MainActivity.this.chooseFolder());
        }
        @JavascriptInterface public void refresh() {
            syncNow();
        }
        @JavascriptInterface public String getImage(String relativePath) {
            return readImageDataUrl(relativePath);
        }
        @JavascriptInterface public void cue() {
            MainActivity.this.cue(false);
        }
        @JavascriptInterface public void longCue() {
            MainActivity.this.cue(true);
        }
        @JavascriptInterface public boolean hasFolder() {
            return MainActivity.this.hasFolder();
        }
        @JavascriptInterface public boolean isOnline() {
            return MainActivity.this.isOnline();
        }
        @JavascriptInterface public int pendingVoiceCount() {
            return MainActivity.this.pendingVoiceCount();
        }
        @JavascriptInterface public void requestMic() {
            runOnUiThread(() -> MainActivity.this.requestMicIfNeeded());
        }
        @JavascriptInterface public void startVoice(String session, String exercise) {
            runOnUiThread(() -> MainActivity.this.startVoice(session, exercise));
        }
        @JavascriptInterface public void stopVoice() {
            runOnUiThread(() -> MainActivity.this.stopVoice());
        }
        @JavascriptInterface public void syncVoice() {
            new Thread(MainActivity.this::syncPendingVoiceNotes).start();
        }
        @JavascriptInterface public void openUrl(String url) {
            runOnUiThread(() -> MainActivity.this.openUrl(url));
        }
        @JavascriptInterface public void saveTrainingExecution(String json) {
            MainActivity.this.saveTrainingExecution(json);
        }
        @JavascriptInterface public void exitApp() {
            runOnUiThread(MainActivity.this::finish);
        }
        @JavascriptInterface public void forgetFolder() {
            prefs.edit().remove(KEY_TREE).apply();
            runOnUiThread(() ->
                    web.evaluateJavascript(
                            "window.showSetup && window.showSetup()", null));
        }
    }
}