package com.salud360.entreno;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.ToneGenerator;
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

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQ_FOLDER = 8001;
    private static final String PREFS = "salud360_prefs";
    private static final String KEY_TREE = "drive_tree_uri";
    private WebView web;
    private SharedPreferences prefs;
    private boolean pageReady = false;

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
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady && hasFolder()) syncNow();
    }

    private boolean hasFolder() {
        return !prefs.getString(KEY_TREE, "").isEmpty();
    }

    private Uri getTreeUri() {
        String s = prefs.getString(KEY_TREE, "");
        return s.isEmpty() ? null : Uri.parse(s);
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) {}
            prefs.edit().putString(KEY_TREE, uri.toString()).apply();
            Toast.makeText(this, "Carpeta vinculada", Toast.LENGTH_SHORT).show();
            syncNow();
        }
    }

    private Uri rootDocument(Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    private Uri findChild(Uri treeUri, Uri parent, String name) throws Exception {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME };
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

    private String readText(String relativePath) throws Exception {
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

    private String readImageDataUrl(String relativePath) {
        try {
            Uri uri = resolveRelative(relativePath);
            if (uri == null) return "";
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return "";
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                String lower = relativePath.toLowerCase();
                String mime = lower.endsWith(".png") ? "image/png" : lower.endsWith(".webp") ? "image/webp" : "image/jpeg";
                return "data:" + mime + ";base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
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
            try (Cursor c = getContentResolver().query(root, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            }
        } catch (Exception ignored) {}
        return "Carpeta seleccionada";
    }

    private JSONObject buildPayload() throws Exception {
        JSONObject config = new JSONObject(readText("00_Core/app_config.json"));
        JSONObject plan = new JSONObject(readText(config.optString("plan_file", "01_Plan_Sesiones/plan.json")));
        JSONObject library = new JSONObject(readText(config.optString("exercise_library_file", "02_Biblioteca_Ejercicios/ejercicios.json")));
        JSONObject root = new JSONObject();
        root.put("ok", true);
        root.put("folder", folderName());
        root.put("config", config);
        root.put("plan", plan);
        root.put("library", library);
        JSONObject modules = new JSONObject();
        JSONObject moduleFiles = config.optJSONObject("module_files");
        if (moduleFiles != null && moduleFiles.has("hiit")) {
            try { modules.put("hiit", new JSONObject(readText(moduleFiles.getString("hiit")))); } catch (Exception ignored) {}
        }
        root.put("modules", modules);
        root.put("synced_at", System.currentTimeMillis());
        return root;
    }

    private void syncNow() {
        new Thread(() -> {
            try {
                JSONObject payload = buildPayload();
                runOnUiThread(() -> web.evaluateJavascript("window.onNativeSync(" + JSONObject.quote(payload.toString()) + ")", null));
            } catch (Exception e) {
                try {
                    JSONObject err = new JSONObject();
                    err.put("ok", false);
                    err.put("error", e.getMessage());
                    err.put("folder", folderName());
                    runOnUiThread(() -> web.evaluateJavascript("window.onNativeSync(" + JSONObject.quote(err.toString()) + ")", null));
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void cue(boolean longCue) {
        runOnUiThread(() -> {
            try {
                ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_ALARM, 95);
                tg.startTone(longCue ? ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD : ToneGenerator.TONE_PROP_BEEP2, longCue ? 850 : 300);
            } catch (Exception ignored) {}
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null) {
                    long[] pattern = longCue ? new long[]{0,220,120,220,120,450} : new long[]{0,180};
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                }
            } catch (Exception ignored) {}
        });
    }

    private class Bridge {
        @JavascriptInterface public void chooseFolder() { runOnUiThread(() -> MainActivity.this.chooseFolder()); }
        @JavascriptInterface public void refresh() { syncNow(); }
        @JavascriptInterface public String getImage(String relativePath) { return readImageDataUrl(relativePath); }
        @JavascriptInterface public void cue() { MainActivity.this.cue(false); }
        @JavascriptInterface public void longCue() { MainActivity.this.cue(true); }
        @JavascriptInterface public boolean hasFolder() { return MainActivity.this.hasFolder(); }
        @JavascriptInterface public void forgetFolder() {
            prefs.edit().remove(KEY_TREE).apply();
            runOnUiThread(() -> web.evaluateJavascript("window.showSetup && window.showSetup()", null));
        }
    }
}
