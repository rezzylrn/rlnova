package com.rezz.rlnova;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Host activity untuk ModelLibraryFragment, DownloadsFragment, ChatListFragment
 * + BottomNav (Pustaka / Unduhan / Chat). Fragmen dibangun programatik.
 *
 * Fragment container diberi id statis R.id.content (res/values/ids.xml).
 */
public class LibraryActivity extends Activity implements
        ModelLibraryFragment.Host, DownloadsFragment.Host,
        ChatListFragment.Host, ChatRoomFragment.Host {

    private DeviceInfo device;
    private ModelCatalog catalog;
    private LinearLayout nav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.setStatusBarColor(0xFF0C0F14);
        w.setNavigationBarColor(0xFF0C0F14);

        try {
            catalog = ModelCatalog.load(this);
        } catch (Exception e) {
            catalog = new ModelCatalog();
        }
        device = collectDeviceInfo(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0C0F14);

        FrameLayout container = new FrameLayout(this);
        container.setId(R.id.content);
        root.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        nav = buildBottomNav();
        root.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        setContentView(root);
        showLibrary();
        GenerationService.setMainActivityRef(this); // auto-title: refresh list
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v1.1.8: ekstrak espeak-ng-data.zip bila download-nya baru selesai
        // (DownloadService tidak bisa ekstrak zip — dikerjakan di sini,
        // background, sekali idempotent).
        final Context c = this;
        new Thread(() -> {
            boolean before = VoiceCatalog.isTtsReady(c);
            if (TtsEngine.maybeExtractEspeak(c)) {
                android.util.Log.i("LibraryActivity", "espeak-ng-data extracted");
            }
            boolean after = VoiceCatalog.isTtsReady(c);
            if (before != after) DownloadState.bumpVersion(); // refresh kartu voice
        }, "rlnova-espeak-extract").start();
    }

    static DeviceInfo collectDeviceInfo(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        return DeviceInfo.fromDevice(am);
    }

    // ---------- BottomNav ----------

    private LinearLayout buildBottomNav() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF131822);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(0, dp(10), 0, dp(8));

        // v1.1.9: ikon saja (tanpa label teks), contentDescription utk aksesibilitas
        android.widget.ImageView tLib = navItem(R.drawable.ic_book, "Library");
        android.widget.ImageView tRes = navItem(R.drawable.ic_search, "Research");
        android.widget.ImageView tDl = navItem(R.drawable.ic_download, "Downloads");
        android.widget.ImageView tChat = navItem(R.drawable.ic_chat, "Chat");
        tLib.setOnClickListener(v -> showLibrary());
        tRes.setOnClickListener(v -> showResearch());
        tDl.setOnClickListener(v -> showDownloads());
        tChat.setOnClickListener(v -> showChatList());
        tLib.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        tRes.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        tDl.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        tChat.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        bar.addView(tLib);
        bar.addView(tRes);
        bar.addView(tDl);
        bar.addView(tChat);
        return bar;
    }

    private android.widget.ImageView navItem(int iconRes, String desc) {
        android.widget.ImageView t = new android.widget.ImageView(this);
        t.setImageResource(iconRes);
        t.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        t.setContentDescription(desc);
        return t;
    }

    private void highlightNav(int activeIndex) {
        int[] icons = {R.drawable.ic_book, R.drawable.ic_search, R.drawable.ic_download, R.drawable.ic_chat};
        int[] active = {R.drawable.ic_book_active, R.drawable.ic_search_active, R.drawable.ic_download_active, R.drawable.ic_chat_active};
        for (int i = 0; i < nav.getChildCount(); i++) {
            android.widget.ImageView t = (android.widget.ImageView) nav.getChildAt(i);
            t.setImageResource(i == activeIndex ? active[i] : icons[i]);
        }
    }

    // ---------- navigasi ----------

    private void showLibrary() {
        highlightNav(0);
        getFragmentManager().beginTransaction()
                .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
                .replace(R.id.content, new ModelLibraryFragment())
                .commit();
    }

    private void showResearch() {
        highlightNav(1);
        getFragmentManager().beginTransaction()
                .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
                .replace(R.id.content, new ResearchFragment())
                .commit();
    }

    private void showDownloads() {
        highlightNav(2);
        getFragmentManager().beginTransaction()
                .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
                .replace(R.id.content, new DownloadsFragment())
                .commit();
    }

    private void showChatList() {
        highlightNav(3);
        getFragmentManager().beginTransaction()
                .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
                .replace(R.id.content, new ChatListFragment())
                .commit();
    }

    private void showChatRoom(long convId) {
        highlightNav(3);
        getFragmentManager().beginTransaction()
                .replace(R.id.content, ChatRoomFragment.newInstance(convId))
                .addToBackStack(null)
                .commit();
    }

    // ---------- ModelLibraryFragment.Host ----------

    @Override public DeviceInfo deviceInfo() { return device; }

    @Override public ModelCatalog catalog() { return catalog; }

    @Override
    public boolean isModelDownloaded(String modelId) {
        return new File(new File(getFilesDir(), "models"), modelId + ".gguf").exists();
    }

    @Override
    public void startDownload(ModelCatalog.Model m) {
        ensureNotificationPermission();
        Intent i = new Intent(this, DownloadService.class);
        i.setAction(DownloadService.ACTION_START);
        i.putExtra(DownloadService.EXTRA_ID, m.id);
        i.putExtra(DownloadService.EXTRA_URL, m.url());
        i.putExtra(DownloadService.EXTRA_NAME, m.name);
        i.putExtra(DownloadService.EXTRA_SIZE, m.sizeBytes);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        Toast.makeText(this, "Downloading " + m.name, Toast.LENGTH_SHORT).show();
    }

    @Override public void openDownloads() { showDownloads(); }

    @Override public void openLibrary() { showLibrary(); }

    @Override public void gotoChat() { showChatList(); }

    @Override public void gotoOptimization() { showOptimization(); }

    private void showOptimization() {
        highlightNav(-1);
        getFragmentManager().beginTransaction()
                .replace(R.id.content, new OptimizationFragment())
                .commit();
    }

    // ---------- ChatListFragment.Host ----------

    @Override public void openChatRoom(long conversationId) { showChatRoom(conversationId); }

    @Override
    public void startNewChat() {
        // pilih model pertama yang sudah terunduh; belum ada → arahkan ke Pustaka
        ModelCatalog.Model picked = null;
        for (ModelCatalog.Model m : catalog.models) {
            if (isModelDownloaded(m.id)) { picked = m; break; }
        }
        if (picked == null) {
            Toast.makeText(this, "Download a model first in the Library tab", Toast.LENGTH_SHORT).show();
            showLibrary();
            return;
        }
        long id = ChatDatabase.get(this).createConversation(
                picked.id, ChatTemplates.defaultSystemPrompt());
        showChatRoom(id);
    }

    // ---------- ChatRoomFragment.Host ----------

    @Override
    public long createConversation(String modelId, String systemPrompt) {
        return ChatDatabase.get(this).createConversation(modelId, systemPrompt);
    }

    @Override
    public ChatDatabase.Conversation getConversation(long id) {
        return ChatDatabase.get(this).getConversation(id);
    }

    @Override
    public ModelCatalog.Model getModel(String modelId) {
        for (ModelCatalog.Model m : catalog.models) {
            if (m.id.equals(modelId)) return m;
        }
        return null;
    }

    @Override public void onChatClosed() { showChatList(); }

    @Override public void onConversationListChanged() { /* daftar re-render saat onResume */ }

    // ---------- ChatRoomFragment.Host (v1.1.2 Fitur A) ----------

    @Override public java.util.List<ModelCatalog.Model> allModels() { return catalog.models; }

    @Override public void startDownloadFromChat(ModelCatalog.Model m) { startDownload(m); }

    // ---------- VoiceCatalog (v1.1.7: STT on-device) ----------

    /** Download model suara via DownloadService — pola sama dengan LLM. */
    public void startVoiceDownload(VoiceCatalog.VoiceModel m) {
        ensureNotificationPermission();
        Intent i = new Intent(this, DownloadService.class);
        i.setAction(DownloadService.ACTION_START);
        i.putExtra(DownloadService.EXTRA_ID, m.id);
        i.putExtra(DownloadService.EXTRA_URL, m.url);
        i.putExtra(DownloadService.EXTRA_NAME, m.name);
        i.putExtra(DownloadService.EXTRA_SIZE, m.sizeBytes);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        Toast.makeText(this, "Downloading " + m.name, Toast.LENGTH_SHORT).show();
    }

    /** Hapus file model suara (kartu voice di Library). */
    public void deleteVoiceModel(String id) {
        File dir = new File(getFilesDir(), "models");
        if (id.equals(VoiceCatalog.PIPER_ESPEAK_ID)) {
            // folder terekstrak + zip mentahnya
            TtsEngine.deleteEspeakData(this);
        } else {
            new File(dir, id + ".bin").delete();
            new File(dir, id + ".part").delete();
        }
        DownloadState.remove(id);
        DownloadState.bumpVersion();
    }

    /** Model suara siap dipakai (file .bin lengkap). */
    public boolean isVoiceModelReady() {
        return VoiceCatalog.isReady(this, VoiceCatalog.whisperTiny());
    }

    /** v1.1.8: model TTS (onnx + espeak terekstrak) siap dipakai. */
    public boolean isTtsModelReady() {
        return VoiceCatalog.isTtsReady(this);
    }

    // ---------- lainnya ----------

    /** POST_NOTIFICATIONS runtime request on Android 13+ before the first download. */
    public void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return; // pre-T: notification permission is implicit
        try {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) return;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 2001);
        } catch (Exception ignored) {
            // Permission flow failing must never block the download itself.
        }
    }

    public void deleteModel(String modelId) {
        File dir = new File(getFilesDir(), "models");
        new File(dir, modelId + ".gguf").delete();
        new File(dir, modelId + ".part").delete();
        DownloadState.remove(modelId);
        DownloadState.bumpVersion(); // BUG3: pemicu update in-place card (shape DONE → IDLE)
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
