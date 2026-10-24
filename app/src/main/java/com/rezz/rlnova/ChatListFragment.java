package com.rezz.rlnova;

import android.app.Fragment;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ChatListFragment (mockup 03, list page): header "Conversations",
 * rows 72dp (judul + preview + waktu), FAB 56dp tombol chat baru.
 * Tanpa dependency — RecyclerView di-flatten ke LinearLayout + ScrollView.
 */
public class ChatListFragment extends Fragment {

    public interface Host {
        void openChatRoom(long conversationId);
        void startNewChat();
    }

    private LinearLayout list;
    private TextView empty;

    @Override
    public View onCreateView(android.view.LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        android.content.Context ctx = getActivity();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0C0F14);
        // title sudah menangani inset status bar

        // ---- toolbar sederhana
        TextView title = new TextView(ctx);
        title.setText("Conversations");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFEDF1F7);
        title.setPadding(dp(16), statusBarInset() + dp(16), dp(16), dp(8));
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- list rows
        FrameLayout body = new FrameLayout(ctx);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(ctx);
        sc.setFillViewport(true);
        sc.addView(list);
        body.addView(sc, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // ---- FAB baru
        TextView fab = new TextView(ctx);
        fab.setText("+");
        fab.setTextSize(26);
        fab.setTypeface(Typeface.DEFAULT_BOLD);
        fab.setTextColor(0xFFFFFFFF);
        fab.setGravity(Gravity.CENTER);
        fab.setBackgroundResource(R.drawable.bg_fab_primary);
        int fabPx = dp(56);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(fabPx, fabPx);
        flp.gravity = Gravity.BOTTOM | Gravity.END;
        flp.rightMargin = dp(16);
        flp.bottomMargin = dp(24);
        fab.setLayoutParams(flp);
        fab.setOnClickListener(v -> ((Host) getActivity()).startNewChat());
        body.addView(fab);

        // ---- empty state
        empty = new TextView(ctx);
        empty.setText("No conversations yet.\nStart a new chat with a downloaded model.");
        empty.setTextSize(14);
        empty.setTextColor(0xFF9AA6B8);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(32), dp(48), dp(32), dp(48));
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        elp.gravity = Gravity.CENTER;
        empty.setLayoutParams(elp);
        empty.setVisibility(View.GONE);
        body.addView(empty);

        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    public void reload() {
        if (!isAdded()) return;
        list.removeAllViews();
        List<ChatDatabase.Conversation> convs = ChatDatabase.get(getActivity()).listConversations();
        if (convs.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            return;
        }
        empty.setVisibility(View.GONE);
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, HH:mm", Locale.US);
        long now = System.currentTimeMillis();
        for (ChatDatabase.Conversation c : convs) {
            list.addView(buildRow(c, fmt, now));
        }
    }

    private View buildRow(final ChatDatabase.Conversation c, SimpleDateFormat fmt, long now) {
        android.content.Context ctx = getActivity();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_row_ripple);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72));
        int margin = dp(8);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView title = new TextView(ctx);
        title.setText(c.title == null || c.title.isEmpty() ? "New conversation" : c.title);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFEDF1F7);
        title.setMaxLines(1);
        row.addView(title);

        LinearLayout sub = new LinearLayout(ctx);
        sub.setOrientation(LinearLayout.HORIZONTAL);
        sub.setGravity(Gravity.CENTER_VERTICAL);
        TextView preview = new TextView(ctx);
        preview.setText(previewText(c.id));
        preview.setTextSize(12);
        preview.setTextColor(0xFF9AA6B8);
        preview.setMaxLines(1);
        sub.addView(preview, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView time = new TextView(ctx);
        time.setText(relativeTime(c.updatedAt, now, fmt));
        time.setTextSize(12);
        time.setTextColor(0xFF7C8AA5);
        sub.addView(time, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        row.addView(sub, slp);

        row.setOnClickListener(v -> ((Host) getActivity()).openChatRoom(c.id));
        row.setOnLongClickListener(v -> {
            new android.app.AlertDialog.Builder(getActivity())
                    .setTitle("Delete conversation?")
                    .setMessage(c.title)
                    .setPositiveButton("Delete", (d, w) -> {
                        ChatDatabase.get(getActivity()).deleteConversation(c.id);
                        reload();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
        return row;
    }

    private String previewText(long convId) {
        List<ChatDatabase.Message> ms = ChatDatabase.get(getActivity()).getMessages(convId);
        for (ChatDatabase.Message m : ms) {
            if (m.role == ChatDatabase.ROLE_USER) return m.content;
        }
        return ms.isEmpty() ? "(empty)" : ms.get(0).content;
    }

    private String relativeTime(long ts, long now, SimpleDateFormat fmt) {
        long diff = now - ts;
        if (diff < 60_000L) return "just now";
        if (diff < 3_600_000L) return (diff / 60_000) + " min ago";
        if (diff < 86_400_000L) return (diff / 3_600_000) + " h ago";
        return fmt.format(new Date(ts));
    }

    private int statusBarInset() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int h = id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
        return dp(8) + h;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
