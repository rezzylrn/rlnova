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

        TextView tLib = navItem("Library");
        TextView tDl = navItem("Downloads");
        TextView tChat = navItem("Chat");
        tLib.setOnClickListener(v -> showLibrary());
        tDl.setOnClickListener(v -> showDownloads());
        tChat.setOnClickListener(v -> showChatList());
        tLib.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tDl.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tChat.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(tLib);
        bar.addView(tDl);
        bar.addView(tChat);
        return bar;
    }

    private TextView navItem(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setTextColor(0xFF9AA6B8);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void highlightNav(int activeIndex) {
        for (int i = 0; i < nav.getChildCount(); i++) {
            TextView t = (TextView) nav.getChildAt(i);
            t.setTextColor(i == activeIndex ? 0xFF5B8DEF : 0xFF9AA6B8);
            t.setTypeface(i == activeIndex ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
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

    private void showDownloads() {
        highlightNav(1);
        getFragmentManager().beginTransaction()
                .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
                .replace(R.id.content, new DownloadsFragment())
                .commit();
    }

    private void showChatList() {
        highlightNav(2);
        getFragmentManager().beginTransaction()
                .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
                .replace(R.id.content, new ChatListFragment())
                .commit();
    }

    private void showChatRoom(long convId) {
        highlightNav(2);
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
