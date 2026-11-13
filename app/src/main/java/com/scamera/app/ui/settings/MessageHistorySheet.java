package com.scamera.app.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.scamera.app.util.Lang;
import com.scamera.app.util.MessageLog;
import com.scamera.app.util.UiMessages;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * P214f (speed-ux.md C3): «Последние сообщения» - the last 20 messages of the camera screen's message channel
 * ({@link UiMessages}), newest first, with the time, the repeat count and the error mark, to read at leisure and copy.
 * Opened from Система → Диагностика and by a long press on the camera screen's message card.
 */
public final class MessageHistorySheet {
    private MessageHistorySheet() {}

    public static BottomSheetDialog show(Context c) {
        List<MessageLog.Entry> entries = UiMessages.history(c);
        LinearLayout body = SettingsStyle.sheetBody(c, Lang.t(c, "Последние сообщения", "Recent messages"), null);
        if (entries.isEmpty()) {
            TextView none = SettingsStyle.text(c, Lang.t(c, "Сообщений пока нет.", "No messages yet."), 15, SettingsStyle.SECTION);
            none.setGravity(android.view.Gravity.CENTER);
            none.setPadding(SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 8), SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 12));
            body.addView(none, new LinearLayout.LayoutParams(-1, -2));
        }
        long now = System.currentTimeMillis();
        // P574: the messages in one #2C2C2E card with hairlines; «Скопировать» a grey capsule, «Очистить» a red-text one
        LinearLayout card = SettingsStyle.card(c, 16);
        for (int i = 0; i < entries.size(); i++) card.addView(row(c, entries.get(i), now));
        if (!entries.isEmpty()) body.addView(card, new LinearLayout.LayoutParams(-1, -2));
        BottomSheetDialog[] dialog = new BottomSheetDialog[1];
        if (!entries.isEmpty()) {
            LinearLayout actions = new LinearLayout(c);
            actions.setPadding(0, SettingsStyle.dp(c, 14), 0, 0);
            TextView copy = SettingsStyle.quietCapsule(c, Lang.t(c, "Скопировать", "Copy"), SettingsStyle.ROW_TITLE);
            copy.setTag("messages_copy");
            copy.setOnClickListener(v -> {
                ClipboardManager clip = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clip != null) clip.setPrimaryClip(ClipData.newPlainText("SCAMERA", plain(entries, now)));
            });
            TextView clear = SettingsStyle.quietCapsule(c, Lang.t(c, "Очистить", "Clear"), SettingsStyle.RED);
            clear.setTag("messages_clear");
            clear.setOnClickListener(v -> {
                UiMessages.clearHistory(c);
                if (dialog[0] != null) dialog[0].dismiss();
            });
            actions.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMarginStart(SettingsStyle.dp(c, 10));
            actions.addView(clear, lp);
            body.addView(actions);
        }
        dialog[0] = SettingsStyle.sheet(c, body);
        dialog[0].show();
        return dialog[0];
    }

    private static View row(Context c, MessageLog.Entry e, long now) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 11), SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 11));
        TextView meta = SettingsStyle.text(c, meta(c, e, now), 12, e.error ? SettingsStyle.WARN : SettingsStyle.SUMMARY);
        meta.setPadding(0, 0, 0, SettingsStyle.dp(c, 4));
        TextView text = SettingsStyle.text(c, e.text, 14, SettingsStyle.ROW_TITLE);
        text.setTextIsSelectable(true);
        text.setLineSpacing(0, 1.15f);
        row.addView(meta);
        row.addView(text);
        return row;
    }

    /** «12:04:05 · ×3 (с 12:01:40) · ошибка» */
    static String meta(Context c, MessageLog.Entry e, long now) {
        StringBuilder out = new StringBuilder(time(e.lastMs, now));
        if (e.count > 1)
            out.append(" · ×").append(e.count).append(Lang.t(c, " (с ", " (since ")).append(time(e.firstMs, now)).append(')');
        if (e.error) out.append(" · ").append(Lang.t(c, "ошибка", "error"));
        return out.toString();
    }

    /** The copied text: one line per message, newest first. */
    static String plain(List<MessageLog.Entry> entries, long now) {
        StringBuilder out = new StringBuilder();
        for (MessageLog.Entry e : entries) {
            out.append(time(e.lastMs, now));
            if (e.count > 1) out.append(" x").append(e.count);
            if (e.error) out.append(" E");
            out.append("  ").append(e.text.replace('\n', ' ')).append('\n');
        }
        return out.toString();
    }

    /** HH:mm:ss today, dd.MM HH:mm:ss on another day. */
    static String time(long ms, long now) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        a.setTimeInMillis(ms);
        b.setTimeInMillis(now);
        boolean today = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
        return new SimpleDateFormat(today ? "HH:mm:ss" : "dd.MM HH:mm:ss", Locale.ROOT).format(new Date(ms));
    }
}
