package com.rezz.rlnova;

import android.app.Fragment;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * SettingsFragment (mockup 06) — programmatic, no dependencies.
 *
 * Sections: GENERAL (Language: English — the only option, disabled; Theme:
 * Dark), CONVERSATIONS (Export all conversations -> .txt to Download/
 * (public, via MediaScanner), Delete all conversations -> red-text confirm
 * dialog), DATA (Delete downloaded models + size, Clear benchmark cache),
 * ABOUT (Version 1.1.0, Privacy policy -> short dialog).
 *
 * Row 56dp, divider 1dp #232B3A.
 */
public class SettingsFragment extends Fragment {

    private static final int COL_BG      = 0xFF0C0F14;
    private static final int COL_ROW     = 0xFF131822;
    private static final int COL_DIV     = 0xFF232B3A;
    private static final int COL_TEXT    = 0xFFEDF1F7;
    private static final int COL_SUB     = 0xFF9AA6B8;
    private static final int COL_VALUE   = 0xFF5B6678;
    private static final int COL_RED     = 0xFFE5484D;

    private TextView exportStatus;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle s) {
        Context ctx = getActivity();

        ScrollView scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(COL_BG);
        scroll.setFillViewport(true);
        scroll.setPadding(0, statusBarInset(), 0, 0);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(88));
        scroll.addView(root);

        TextView title = new TextView(ctx);
        title.setText("Settings");
        title.setTextColor(COL_TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        // ---------------- UMUM ----------------
        sectionLabel(root, "GENERAL");

        row(ctx, root, "Language", "English", null); // disabled: only option
        row(ctx, root, "Theme", "Dark", null);

        // ---------------- PERCAKAPAN ----------------
        sectionLabel(root, "CONVERSATIONS");

        exportStatus = new TextView(ctx);
        exportStatus.setTextColor(COL_SUB);
        exportStatus.setTextSize(12);
        exportStatus.setPadding(dp(4), 0, 0, 0);
        exportStatus.setVisibility(View.GONE);

        row(ctx, root, "Export all conversations", "Export", v -> exportAllChats());
        root.addView(exportStatus);

        row(ctx, root, "Delete all conversations", "Delete", v -> confirmDeleteAllChats(), COL_RED);

        // ---------------- DATA ----------------
        sectionLabel(root, "DATA");

        row(ctx, root, "Downloaded models", modelUsageText(), v -> confirmDeleteModels());
        row(ctx, root, "Clear benchmark cache & history", "Clear", v -> confirmClearCache());

        // ---------------- TENTANG ----------------
        sectionLabel(root, "ABOUT");

        row(ctx, root, "Version", "1.1.0", null);
        row(ctx, root, "Privacy policy", "", v -> showPrivacyDialog());

        return scroll;
    }

    // ------------------------------------------------------------ rows

    private void sectionLabel(LinearLayout parent, String label) {
        TextView t = new TextView(getActivity());
        t.setText(label);
        t.setTextSize(11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setTextColor(COL_SUB);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(6);
        parent.addView(t, lp);
    }

    /** Row standar 56dp: label + value kanan, divider 1dp di bawah. Return row; tag = TextView value. */
    private LinearLayout row(Context ctx, LinearLayout parent, String label,
                             String value, View.OnClickListener action) {
        return row(ctx, parent, label, value, action, COL_TEXT);
    }

    private LinearLayout row(Context ctx, LinearLayout parent, String label,
                             String value, View.OnClickListener action, int labelColor) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(56));
        r.setPadding(dp(12), 0, dp(12), 0);
        r.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        rlp.topMargin = dp(6);
        r.setLayoutParams(rlp);

        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(14);
        t.setTextColor(labelColor);
        r.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextSize(13);
        v.setTextColor(value.isEmpty() ? COL_SUB : COL_VALUE);
        if (action != null) {
            r.setOnClickListener(action);
        } else {
            v.setTextColor(COL_VALUE);
            r.setEnabled(false);
        }
        r.addView(v);
        r.setTag(v);

        View div = new View(ctx);
        div.setBackgroundColor(COL_DIV);
        parent.addView(div, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return r;
    }

    // ------------------------------------------------------------ aksi

    private void exportAllChats() {
        Context ctx = getActivity();
        if (ctx == null) return;
        try {
            File downloads = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (downloads == null) downloads = new File(ctx.getFilesDir(), "Download");
            if (!downloads.exists()) downloads.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new Date());
            File out = new File(downloads, "rlnova-chat-" + stamp + ".txt");
            ChatDatabase db = ChatDatabase.get(ctx);
            java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(out));
            w.println("Rlnova — Conversation history export");
            w.println("Exported: " + new SimpleDateFormat(
                    "dd MMM yyyy HH:mm", Locale.US).format(new Date()));
            w.println();
            int convCount = 0;
            for (ChatDatabase.Conversation c : db.listConversations()) {
                convCount++;
                w.println("=== " + c.title + " (model: " + c.modelId + ") ===");
                for (ChatDatabase.Message m : db.getMessages(c.id)) {
                    String who = m.role == ChatDatabase.ROLE_USER ? "User"
                            : m.role == ChatDatabase.ROLE_ASSISTANT ? "AI" : "System";
                    w.println("[" + who + "] " + m.content);
                    if (m.tokPerSec != null) w.println("   (" + m.tokens + " token, "
                            + String.format(Locale.US, "%.1f", m.tokPerSec) + " tok/s)");
                    w.println();
                }
            }
            w.close();
            exportStatus.setVisibility(View.VISIBLE);
            exportStatus.setText("Saved: " + out.getAbsolutePath());
            Toast.makeText(ctx, "Exported (" + convCount + " conversations) to Download/",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            exportStatus.setVisibility(View.VISIBLE);
            exportStatus.setText("Export failed: " + e.getMessage());
        }
    }

    private void confirmDeleteAllChats() {
        new android.app.AlertDialog.Builder(getActivity())
                .setTitle("Delete all conversations")
                .setMessage("All conversations and their messages will be permanently deleted. Continue?")
                .setPositiveButton("Delete", (d, w) -> {
                    ChatDatabase.get(getActivity()).deleteAllConversations();
                    Toast.makeText(getActivity(), "All conversations deleted",
                            Toast.LENGTH_SHORT).show();
                })
                     .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteModels() {
        Context ctx = getActivity();
        if (ctx == null) return;
        File dir = new File(ctx.getFilesDir(), "models");
        long total = 0;
        java.util.List<String> names = new java.util.ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".gguf")) {
                    long size = f.length();
                    total += size;
                    names.add(f.getName().replace(".gguf", "") + " ("
                            + DeviceInfo.formatBytes(size) + ")");
                }
            }
        }
        if (names.isEmpty()) {
            Toast.makeText(ctx, "No models downloaded", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append("• ").append(n).append('\n');
        sb.append("\nTotal: ").append(DeviceInfo.formatBytes(total));
        new android.app.AlertDialog.Builder(ctx)
                .setTitle("Delete all models?")
                .setMessage(sb.toString() + "\nModels must be downloaded again to be used.")
                .setPositiveButton("Delete", (d, w) -> {
                    for (File f : files) {
                        if (f.getName().endsWith(".gguf") || f.getName().endsWith(".part")) f.delete();
                    }
                    DownloadState.clearAll();
                    Toast.makeText(ctx, "All models deleted", Toast.LENGTH_SHORT).show();
                })
                     .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmClearCache() {
        Context ctx = getActivity();
        if (ctx == null) return;
        PerfPrefs.clearHistory(ctx);
        Toast.makeText(ctx, "Benchmark cache & history cleared", Toast.LENGTH_SHORT).show();
    }

    private void showPrivacyDialog() {
        new android.app.AlertDialog.Builder(getActivity())
                .setTitle("Privacy Policy")
                .setMessage("Rlnova runs fully offline. All data, conversation "
                        + "history, and language models stay on this device only. "
                        + "Nothing is ever sent off the device.")
                .setPositiveButton("Got it", null)
                .show();
    }

    private String modelUsageText() {
        Context ctx = getActivity();
        File dir = new File(ctx.getFilesDir(), "models");
        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (f.getName().endsWith(".gguf")) total += f.length();
        return total > 0 ? DeviceInfo.formatBytes(total) : "None";
    }

    private int statusBarInset() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
