package com.nextplay.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int PICK_NATIVE = 1001;
    private static final int PICK_WEB = 1002;
    private static final int BG = Color.BLACK;
    private static final int SURFACE = 0xFF11111C;
    private static final int PURPLE = 0xFF7C3AED;
    private static final int MUTED = 0xFFB0AAC5;

    private FrameLayout root;
    private WebView webView;
    private View libraryPanel;
    private View playerPanel;
    private android.webkit.ValueCallback<Uri[]> fileCallback;
    private MediaPlayer audioPlayer;
    private MediaPlayer preparedVideoPlayer;
    private VideoView videoView;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable audioTick;
    private float originalBrightness = -1f;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        // Respect status/navigation bar cutouts on Android 15+ and older releases.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        createWebView();
    }

    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true); // Only the bundled trusted UI executes as the main page.
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                return handleNavigation(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleNavigation(Uri.parse(url));
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w,
                    android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent pick = params.createIntent();
                    startActivityForResult(pick, PICK_WEB);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    toast("No file picker is available");
                    return true;
                }
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        webView.loadUrl("file:///android_asset/index.html");
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) return true;
        String scheme = uri.getScheme();
        if ("nextplay".equalsIgnoreCase(scheme)) {
            if ("library".equals(uri.getHost())) showLibrary();
            else if ("open".equals(uri.getHost())) {
                String u = uri.getQueryParameter("url");
                if (u != null) openExternal(u);
            }
            return true;
        }
        if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
            openExternal(uri.toString());
            return true;
        }
        return !("file".equalsIgnoreCase(scheme)
                && uri.toString().startsWith("file:///android_asset/"));
    }

    private void openExternal(String raw) {
        try {
            Uri u = Uri.parse(raw);
            if (!"https".equalsIgnoreCase(u.getScheme())
                    && !"http".equalsIgnoreCase(u.getScheme())) return;
            Intent view = new Intent(Intent.ACTION_VIEW, u);
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            toast("No browser is available");
        }
    }

    private void offerDirectDownload() {
        EditText field = new EditText(this);
        field.setHint("https://example.org/media/video.mp4");
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout wrap = column();
        wrap.setPadding(dp(18), dp(4), dp(18), dp(2));
        wrap.addView(field);
        new AlertDialog.Builder(this)
                .setTitle("Download authorized media")
                .setMessage("Requires a direct HTTPS media file URL (not a streaming site page).")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Download", (dialog, which) -> enqueueDirectDownload(field.getText().toString()))
                .show();
    }

    private void enqueueDirectDownload(String raw) {
        Uri link;
        try { link = Uri.parse(raw.trim()); }
        catch (Exception e) { toast("Invalid URL"); return; }
        if (!"https".equalsIgnoreCase(link.getScheme()) || link.getHost() == null) {
            toast("Enter a direct HTTPS link"); return;
        }
        String host = link.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("youtube.com") || host.endsWith(".youtube.com")
                || host.equals("youtu.be") || host.endsWith(".youtu.be")) {
            toast("YouTube stream downloads are not supported"); return;
        }
        String path = link.getLastPathSegment();
        if (path == null || !path.matches("(?i).+\\.(mp3|mp4|m4a|webm|ogg|opus|wav|flac|mkv|aac)")) {
            toast("URL must end with a common audio/video file extension"); return;
        }
        String filename = path.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (filename.length() > 100) filename = filename.substring(filename.length() - 100);
        try {
            DownloadManager.Request request = new DownloadManager.Request(link);
            request.setTitle("NextPlay: " + filename);
            request.setDescription("Authorized direct media download");
            request.setAllowedOverMetered(true);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= 29) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            } else {
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename);
            }
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) { toast("Download service unavailable"); return; }
            manager.enqueue(request);
            toast("Download queued. Check Downloads, then add the file to your library.");
        } catch (Exception e) { toast("Could not queue download: " + e.getMessage()); }
    }

    private void selectPermanentMedia() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_NATIVE);
        } catch (ActivityNotFoundException e) {
            toast("The Android document picker is unavailable");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_WEB) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
                fileCallback = null;
            }
            return;
        }
        if (request == PICK_NATIVE && result == RESULT_OK && data != null) {
            JSONArray items = savedItems();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    storeUri(items, data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                storeUri(items, data.getData());
            }
            getPreferences(0).edit().putString("saved_media", items.toString()).apply();
            showLibrary();
        }
    }

    private JSONArray savedItems() {
        try { return new JSONArray(getPreferences(0).getString("saved_media", "[]")); }
        catch (JSONException ignored) { return new JSONArray(); }
    }

    private void storeUri(JSONArray items, Uri uri) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && uri.toString().equals(item.optString("uri"))) return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            toast("Access to some files may need re-selection later");
        }
        String name = "Media file";
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String title = cursor.getString(0);
                if (title != null) name = title;
            }
        } catch (Exception ignored) { }
        String mime = getContentResolver().getType(uri);
        JSONObject file = new JSONObject();
        try {
            file.put("uri", uri.toString());
            file.put("name", name);
            file.put("mime", mime == null ? "application/octet-stream" : mime);
            items.put(file);
        } catch (JSONException ignored) { }
    }

    private void showLibrary() {
        closePlayer();
        if (libraryPanel != null) root.removeView(libraryPanel);
        webView.setVisibility(View.GONE);
        LinearLayout content = column();
        content.setPadding(dp(17), dp(16), dp(17), dp(24));
        content.addView(heading("📁  Offline Library", 24));
        content.addView(note("Saved references to audio/video files you select. Your media stays on your phone; no upload or broad storage permission is required."));
        LinearLayout actions = row();
        Button add = button("+ Add files", true);
        add.setOnClickListener(v -> selectPermanentMedia());
        actions.addView(add);
        Button done = button("← Back", false);
        done.setOnClickListener(v -> hideLibrary());
        actions.addView(done);
        content.addView(actions);
        LinearLayout downloadActions = row();
        Button direct = button("↓ Direct media URL", false);
        direct.setOnClickListener(v -> offerDirectDownload());
        downloadActions.addView(direct);
        content.addView(downloadActions);
        content.addView(note("Download only media files you own or have permission to save. Direct HTTPS file links only; YouTube downloading is not supported."));
        JSONArray items = savedItems();
        TextView counter = note(items.length() + " saved file" + (items.length() == 1 ? "" : "s"));
        counter.setPadding(0, dp(21), 0, dp(10));
        content.addView(counter);
        if (items.length() == 0) content.addView(note("No files added yet. Choose audio or video stored on your device."));
        for (int i = 0; i < items.length(); i++) {
            JSONObject file = items.optJSONObject(i);
            if (file == null) continue;
            final int index = i;
            String title = file.optString("name", "Media file");
            String mime = file.optString("mime", "");
            Uri uri = Uri.parse(file.optString("uri", ""));
            LinearLayout itemRow = column();
            itemRow.setPadding(dp(14), dp(12), dp(14), dp(12));
            itemRow.setBackground(rounded(SURFACE, 14));
            TextView name = heading((mime.startsWith("audio/") ? "♫ " : "▶ ") + title, 15);
            name.setMaxLines(2);
            itemRow.addView(name);
            itemRow.addView(note(mime + "  ·  tap to play"));
            itemRow.setOnClickListener(v -> playSavedMedia(uri, mime, title));
            itemRow.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Remove from NextPlay?")
                        .setMessage("The original file will not be deleted.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove", (d, which) -> removeSaved(index))
                        .show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(9);
            content.addView(itemRow, lp);
        }
        if (items.length() > 0) {
            TextView reminder = note("Long-press an item to remove it from this list without deleting the file.");
            reminder.setPadding(0, dp(7), 0, 0);
            content.addView(reminder);
        }
        libraryPanel = scroll(content);
        root.addView(libraryPanel, new FrameLayout.LayoutParams(-1, -1));
    }

    private void removeSaved(int index) {
        JSONArray current = savedItems();
        JSONArray next = new JSONArray();
        for (int i = 0; i < current.length(); i++) {
            if (i != index) next.put(current.opt(i));
        }
        getPreferences(0).edit().putString("saved_media", next.toString()).apply();
        showLibrary();
    }

    private void hideLibrary() {
        closePlayer();
        if (libraryPanel != null) root.removeView(libraryPanel);
        libraryPanel = null;
        webView.setVisibility(View.VISIBLE);
    }

    private void playSavedMedia(Uri uri, String mime, String title) {
        closePlayer();
        LinearLayout content = column();
        content.setPadding(dp(14), dp(12), dp(14), dp(20));
        Button back = button("← Library", false);
        back.setOnClickListener(v -> closePlayer());
        LinearLayout backRow = row();
        backRow.addView(back);
        content.addView(backRow);
        TextView heading = heading(title, 20);
        heading.setPadding(0, dp(16), 0, dp(11));
        content.addView(heading);
        if (mime.startsWith("video/")) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            createVideoPlayer(content, uri);
        }
        else if (mime.startsWith("audio/")) createAudioPlayer(content, uri);
        else { content.addView(note("This file type is not supported by the native player.")); }
        content.addView(note("Gestures on local video: swipe vertically on the left for brightness, on the right for volume, or horizontally to seek."));
        playerPanel = scroll(content);
        root.addView(playerPanel, new FrameLayout.LayoutParams(-1, -1));
    }

    private void createVideoPlayer(LinearLayout content, Uri uri) {
        videoView = new VideoView(this);
        videoView.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(230));
        content.addView(videoView, p);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        LinearLayout controls = row();
        Button play = button("▶ Play / Pause", true);
        play.setOnClickListener(v -> {
            if (videoView == null) return;
            if (videoView.isPlaying()) videoView.pause(); else videoView.start();
        });
        controls.addView(play);
        Button previous = button("−10s", false);
        previous.setOnClickListener(v -> videoView.seekTo(Math.max(0, videoView.getCurrentPosition() - 10000)));
        controls.addView(previous);
        Button next = button("+10s", false);
        next.setOnClickListener(v -> videoView.seekTo(Math.min(videoView.getDuration(), videoView.getCurrentPosition() + 10000)));
        controls.addView(next);
        content.addView(controls);
        content.addView(note("Playback speed (local video only):"));
        LinearLayout speeds = row();
        for (float value : new float[]{0.75f, 1f, 1.25f, 1.5f, 2f}) {
            Button b = button(value + "×", false);
            b.setOnClickListener(v -> changeSpeed(preparedVideoPlayer, value));
            speeds.addView(b);
        }
        content.addView(speeds);
        videoView.setOnPreparedListener(mp -> {
            preparedVideoPlayer = mp;
            mp.setOnVideoSizeChangedListener((m, width, height) -> controller.setAnchorView(videoView));
            videoView.start();
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            toast("Could not play file: unsupported codec or file access expired");
            return true;
        });
        enableGestures(videoView);
        videoView.setVideoURI(uri);
        videoView.requestFocus();
    }

    private void createAudioPlayer(LinearLayout content, Uri uri) {
        try {
            audioPlayer = MediaPlayer.create(this, uri);
            if (audioPlayer == null) throw new IllegalArgumentException("No supported decoder");
            final MediaPlayer player = audioPlayer;
            LinearLayout transport = row();
            Button play = button("▶ Play", true);
            play.setOnClickListener(v -> {
                if (player.isPlaying()) { player.pause(); play.setText("▶ Play"); }
                else { player.start(); play.setText("Ⅱ Pause"); }
            });
            transport.addView(play);
            Button prev = button("−10s", false);
            prev.setOnClickListener(v -> player.seekTo(Math.max(0, player.getCurrentPosition() - 10000)));
            transport.addView(prev);
            Button next = button("+10s", false);
            next.setOnClickListener(v -> player.seekTo(Math.min(player.getDuration(), player.getCurrentPosition() + 10000)));
            transport.addView(next);
            content.addView(transport);
            SeekBar progress = new SeekBar(this);
            progress.setMax(Math.max(1, player.getDuration()));
            progress.setProgressTintList(ColorStateList.valueOf(PURPLE));
            progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar b) { }
                public void onStopTrackingTouch(SeekBar b) { player.seekTo(b.getProgress()); }
                public void onProgressChanged(SeekBar b, int p, boolean fromUser) { }
            });
            content.addView(progress);
            LinearLayout speeds = row();
            for (float value : new float[]{0.75f, 1f, 1.25f, 1.5f, 2f}) {
                Button b = button(value + "×", false);
                b.setOnClickListener(v -> changeSpeed(player, value));
                speeds.addView(b);
            }
            content.addView(note("Playback speed:"));
            content.addView(speeds);
            player.setOnCompletionListener(mp -> play.setText("▶ Play"));
            audioTick = new Runnable() {
                @Override public void run() {
                    if (audioPlayer == player) {
                        try { progress.setProgress(player.getCurrentPosition()); }
                        catch (IllegalStateException ignored) { }
                        uiHandler.postDelayed(this, 450);
                    }
                }
            };
            uiHandler.post(audioTick);
            player.start();
            play.setText("Ⅱ Pause");
        } catch (Exception e) {
            content.addView(note("Unable to open the audio file. The format may not be supported or permission may have expired."));
        }
    }

    private void changeSpeed(MediaPlayer player, float speed) {
        if (player == null) { toast("Start playback first"); return; }
        try {
            if (Build.VERSION.SDK_INT >= 23)
                player.setPlaybackParams(new PlaybackParams().setSpeed(speed));
            toast("Speed: " + speed + "×");
        } catch (Exception e) { toast("Speed not supported for this file"); }
    }

    private void enableGestures(VideoView target) {
        final float[] start = new float[2];
        target.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                start[0] = event.getX(); start[1] = event.getY(); return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - start[0];
                float dy = event.getY() - start[1];
                if (Math.abs(dx) > dp(65) && Math.abs(dx) > Math.abs(dy)) {
                    int offset = dx > 0 ? 10000 : -10000;
                    target.seekTo(Math.max(0, Math.min(target.getDuration(), target.getCurrentPosition() + offset)));
                } else if (Math.abs(dy) > dp(40)) {
                    if (start[0] < target.getWidth() / 2f) {
                        WindowManager.LayoutParams attrs = getWindow().getAttributes();
                        float brightness = attrs.screenBrightness < 0 ? 0.55f : attrs.screenBrightness;
                        attrs.screenBrightness = Math.max(0.05f, Math.min(1f, brightness - dy / dp(450)));
                        getWindow().setAttributes(attrs);
                    } else {
                        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
                        int direction = dy < 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
                        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction,
                                AudioManager.FLAG_SHOW_UI);
                    }
                } else {
                    target.start();
                }
                return true;
            }
            return true;
        });
    }

    private void closePlayer() {
        if (audioTick != null) { uiHandler.removeCallbacks(audioTick); audioTick = null; }
        if (audioPlayer != null) { try { audioPlayer.release(); } catch (Exception ignored) { } audioPlayer = null; }
        if (videoView != null) { try { videoView.stopPlayback(); } catch (Exception ignored) { } videoView = null; }
        preparedVideoPlayer = null;
        if (playerPanel != null) root.removeView(playerPanel);
        playerPanel = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.screenBrightness = originalBrightness;
        getWindow().setAttributes(attrs);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL); l.setBackgroundColor(BG);
        return l;
    }
    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, dp(6), 0, dp(10));
        return l;
    }
    private View scroll(LinearLayout content) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setBackgroundColor(BG);
        s.addView(content, new ScrollView.LayoutParams(-1, -2));
        return s;
    }
    private TextView heading(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size);
        t.setTextColor(Color.WHITE); t.setTypeface(null, 1);
        return t;
    }
    private TextView note(String value) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(13);
        t.setTextColor(MUTED); t.setPadding(0, dp(7), 0, dp(10));
        return t;
    }
    private Button button(String value, boolean primary) {
        Button b = new Button(this); b.setText(value); b.setTextColor(Color.WHITE);
        b.setTextSize(12); b.setAllCaps(false);
        b.setBackground(rounded(primary ? PURPLE : SURFACE, 11));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(43), 1);
        p.rightMargin = dp(6); b.setLayoutParams(p);
        return b;
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private int dp(float amount) { return Math.round(amount * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() {
        if (playerPanel != null) closePlayer();
        else if (libraryPanel != null) hideLibrary();
        else super.onBackPressed();
    }
    @Override protected void onPause() {
        super.onPause();
        if (audioPlayer != null && audioPlayer.isPlaying()) audioPlayer.pause();
        if (videoView != null && videoView.isPlaying()) videoView.pause();
    }
    @Override protected void onDestroy() {
        closePlayer();
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
