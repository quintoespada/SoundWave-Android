package com.soundwave.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small, native Android shell around the bundled offline Python runtime. */
public class MainActivity extends Activity {
    private static final int SAVE_AUDIO = 102;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText urlInput;
    private Button pasteButton, searchButton, downloadButton;
    private LinearLayout trackCard;
    private TextView trackTitle, trackArtist, statusLabel, coverPlaceholder;
    private ImageView coverImage;
    private ProgressBar progress;
    private PyObject backend;
    private boolean busy = false;
    private String activeUrl = "";
    private String pendingFilename;
    private File pendingFile;
    private boolean trackingProgress = false;
    private final FileMarker marker = new FileMarker();

    private final class FileMarker { File progressFile; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        urlInput = findViewById(R.id.urlInput);
        pasteButton = findViewById(R.id.pasteButton);
        searchButton = findViewById(R.id.searchButton);
        downloadButton = findViewById(R.id.downloadButton);
        trackCard = findViewById(R.id.trackCard);
        trackTitle = findViewById(R.id.trackTitle);
        trackArtist = findViewById(R.id.trackArtist);
        statusLabel = findViewById(R.id.statusLabel);
        coverImage = findViewById(R.id.coverImage);
        coverPlaceholder = findViewById(R.id.coverPlaceholder);
        progress = findViewById(R.id.progress);

        coverImage.setClipToOutline(true);
        pasteButton.setOnClickListener(v -> pasteLink());
        searchButton.setOnClickListener(v -> searchTrack());
        downloadButton.setOnClickListener(v -> downloadTrack());
        urlInput.setOnEditorActionListener((v, action, event) -> {
            searchTrack();
            return true;
        });
        urlInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                if (!s.toString().trim().equals(activeUrl)) clearCard();
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        // Share a link from another app into SoundWave; no background service needed.
        Intent intent = getIntent();
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            String share = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (share != null) urlInput.setText(findSoundCloudLink(share));
        }
    }

    private static String findSoundCloudLink(String text) {
        for (String word : text.split("\\s+")) {
            if (word.contains("soundcloud.com/")) return word.trim();
        }
        return text.trim();
    }

    private void pasteLink() {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb == null || !cb.hasPrimaryClip()) { status("В буфере обмена нет ссылки."); return; }
        ClipData clip = cb.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence value = clip.getItemAt(0).coerceToText(this);
        if (value != null) {
            urlInput.setText(findSoundCloudLink(value.toString()));
            urlInput.setSelection(urlInput.length());
            status("Ссылка вставлена — нажми «Найти».");
        }
    }

    private PyObject backend() {
        if (backend == null) backend = Python.getInstance().getModule("soundwave_backend");
        return backend;
    }

    private void searchTrack() {
        if (busy) return;
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) { status("Вставь ссылку SoundCloud."); return; }
        clearCard();
        setBusy(true, true);
        status("Ищем трек…");
        hideKeyboard();
        worker.execute(() -> {
            try {
                String response = backend().callAttr("inspect", url).toString();
                JSONObject result = new JSONObject(response);
                ui.post(() -> {
                    activeUrl = url;
                    trackTitle.setText(result.optString("title", "Track"));
                    trackArtist.setText(result.optString("artist", "Unknown Artist"));
                    trackCard.setVisibility(View.VISIBLE);
                    setBusy(false, true);
                    status("Трек найден. Можно сохранить аудио.");
                    showCover(result.optString("thumbnail"), url);
                });
            } catch (Exception ex) {
                ui.post(() -> { setBusy(false, true); status("Ошибка поиска: " + shortError(ex)); });
            }
        });
    }

    private void showCover(String imageUrl, String expectedUrl) {
        coverImage.setImageDrawable(null);
        coverPlaceholder.setVisibility(View.VISIBLE);
        if (!imageUrl.startsWith("https://")) return;
        worker.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(imageUrl).openConnection();
                c.setConnectTimeout(7000);
                c.setReadTimeout(7000);
                try (InputStream s = c.getInputStream()) {
                    Bitmap bitmap = BitmapFactory.decodeStream(s);
                    if (bitmap != null) ui.post(() -> {
                        if (expectedUrl.equals(activeUrl)) {
                            coverImage.setImageBitmap(bitmap);
                            coverPlaceholder.setVisibility(View.GONE);
                        }
                    });
                }
            } catch (Exception ignored) {
                // Cover is optional; downloading the track still works.
            } finally { if (c != null) c.disconnect(); }
        });
    }

    private void downloadTrack() {
        if (busy || activeUrl.isEmpty()) return;
        String url = activeUrl;
        setBusy(true, false);
        status("Загружаем аудио…");
        marker.progressFile = new File(getCacheDir(), "soundwave_progress.json");
        if (marker.progressFile.exists()) marker.progressFile.delete();
        startProgress();
        worker.execute(() -> {
            try {
                String response = backend().callAttr("download", url,
                        getCacheDir().getAbsolutePath(), marker.progressFile.getAbsolutePath()).toString();
                JSONObject result = new JSONObject(response);
                pendingFile = new File(result.getString("path"));
                pendingFilename = result.getString("filename");
                ui.post(() -> {
                    stopProgress();
                    status("Загружено. Выбери, куда сохранить файл.");
                    Intent pick = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("audio/*");
                    pick.putExtra(Intent.EXTRA_TITLE, pendingFilename);
                    try { startActivityForResult(pick, SAVE_AUDIO); }
                    catch (Exception ex) { setBusy(false, true); status("Не удалось открыть выбор папки."); }
                });
            } catch (Exception ex) {
                ui.post(() -> {
                    stopProgress();
                    setBusy(false, true);
                    status("Ошибка загрузки: " + shortError(ex));
                });
            }
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SAVE_AUDIO) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            cleanupTemporaryFile();
            setBusy(false, true);
            status("Сохранение отменено.");
            return;
        }
        Uri destination = data.getData();
        status("Сохраняем файл…");
        worker.execute(() -> {
            try (InputStream source = new FileInputStream(pendingFile);
                 OutputStream dest = getContentResolver().openOutputStream(destination)) {
                if (dest == null) throw new IllegalStateException("Не удалось открыть файл назначения.");
                byte[] buffer = new byte[65536];
                int n;
                while ((n = source.read(buffer)) != -1) dest.write(buffer, 0, n);
                dest.flush();
                ui.post(() -> { setBusy(false, true); status("Готово! Трек сохранён на телефон."); });
            } catch (Exception ex) {
                ui.post(() -> { setBusy(false, true); status("Ошибка сохранения: " + shortError(ex)); });
            } finally { cleanupTemporaryFile(); }
        });
    }

    private void cleanupTemporaryFile() {
        File f = pendingFile;
        pendingFile = null;
        pendingFilename = null;
        if (f != null) {
            f.delete();
            File folder = f.getParentFile();
            if (folder != null && folder.getName().startsWith("sw_")) folder.delete();
        }
    }

    private void startProgress() {
        trackingProgress = true;
        progress.setIndeterminate(false);
        progress.setProgress(0);
        Runnable readProgress = new Runnable() {
            @Override public void run() {
                if (!trackingProgress) return;
                try {
                    File path = marker.progressFile;
                    if (path != null && path.isFile()) {
                        byte[] bytes = new byte[(int) Math.min(1024, path.length())];
                        try (InputStream in = new FileInputStream(path)) { int ignored = in.read(bytes); }
                        int pct = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                                .optInt("percent", 0);
                        progress.setProgress(pct);
                        status("Загружаем: " + pct + "%");
                    }
                } catch (Exception ignored) { /* status updates are best-effort */ }
                ui.postDelayed(this, 450);
            }
        };
        ui.post(readProgress);
    }

    private void stopProgress() {
        trackingProgress = false;
        progress.setVisibility(View.GONE);
    }

    private void clearCard() {
        activeUrl = "";
        trackCard.setVisibility(View.GONE);
        coverImage.setImageDrawable(null);
        coverPlaceholder.setVisibility(View.VISIBLE);
    }

    private void setBusy(boolean isBusy, boolean spinner) {
        busy = isBusy;
        urlInput.setEnabled(!isBusy);
        pasteButton.setEnabled(!isBusy);
        searchButton.setEnabled(!isBusy);
        downloadButton.setEnabled(!isBusy);
        progress.setIndeterminate(spinner);
        progress.setVisibility(isBusy ? View.VISIBLE : View.GONE);
    }

    private String shortError(Exception ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String message = t.getMessage();
        if (message == null || message.isEmpty()) return "неизвестная ошибка";
        return message.length() > 160 ? message.substring(0, 160) + "…" : message;
    }

    private void status(String s) { statusLabel.setText(s); }
    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
        urlInput.clearFocus();
    }

    @Override protected void onDestroy() {
        trackingProgress = false;
        worker.shutdown();
        super.onDestroy();
    }
}
