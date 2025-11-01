package com.hamstergroup.evilhamster.service;

import com.hamstergroup.evilhamster.config.HamsterConfigProperties;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class EvilHamsterBot extends TelegramLongPollingBot {
    private static final String CB_PREFIX = "UPDATE_TOP_N:";
    private final HamsterConfigProperties properties;
    private final FundingTracker tracker = new FundingTracker();

    // notifications
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<Long, ScheduledFuture<?>> notificationTasks = new ConcurrentHashMap<>();

    public EvilHamsterBot(HamsterConfigProperties properties) {
        super(properties.getBotToken());
        this.properties = properties;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) { handleCallback(update); return; }
            if (!(update.hasMessage() && update.getMessage().hasText())) return;

            String text = update.getMessage().getText().trim();
            long chatId = update.getMessage().getChatId();

            if (text.startsWith("/start")) { sendAndPinWelcomeMessage(chatId); return; }

            if (text.startsWith("/update")) {
                int topN = 10;
                String[] parts = text.split("\\s+");
                if (parts.length >= 2) try { topN = Math.max(1, Integer.parseInt(parts[1])); } catch (Exception ignored) {}
                String html = buildFormattedReport(topN);
                sendHtmlWithUpdateButton(chatId, html, topN);
                return;
            }

            if (text.startsWith("/notification_stop")) {
                cancelNotification(chatId);
                sendText(chatId, "🔕 Notifications stopped.");
                return;
            }

            if (text.startsWith("/notification")) {
                String[] parts = text.split("\\s+");
                if (parts.length < 4) {
                    sendText(chatId, "Usage: /notification <window> <percent> <interval>\n" +
                            "Examples: /notification 30m 1% 60m | /notification 120m 0.1% 30m");
                    return;
                }
                try {
                    long windowMin = parseDurationToMinutes(parts[1]);
                    double threshold = parsePercent(parts[2]);
                    long intervalMin = parseDurationToMinutes(parts[3]);
                    scheduleNotification(chatId, windowMin, threshold, intervalMin);
                    sendText(chatId, String.format(Locale.US,
                            "🔔 Enabled: window≤%dm, Δ≥%.4f%%, every %dm",
                            windowMin, threshold, intervalMin));
                } catch (IllegalArgumentException ex) {
                    sendText(chatId, "Bad arguments. Examples:\n" +
                            "/notification 30m 1% 60m\n/notification 120m 0.5% 30m");
                }
                return;
            }

            // fallback
            sendMessageInfo(chatId, update.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ======= CALLBACK: 🔄 Update
    private void handleCallback(Update update) {
        var cb = update.getCallbackQuery();
        String data = cb.getData() == null ? "" : cb.getData();
        if (!data.startsWith(CB_PREFIX)) return;

        int topN = 10;
        try { topN = Integer.parseInt(data.substring(CB_PREFIX.length())); } catch (Exception ignored) {}

        try {
            String html = buildFormattedReport(topN);
            execute(EditMessageText.builder()
                    .chatId(cb.getMessage().getChatId().toString())
                    .messageId(cb.getMessage().getMessageId())
                    .parseMode("HTML")
                    .text(html)
                    .replyMarkup(updateKeyboard(topN))
                    .build());
            execute(AnswerCallbackQuery.builder().callbackQueryId(cb.getId()).build());
        } catch (Exception e) {
            try {
                execute(AnswerCallbackQuery.builder()
                        .callbackQueryId(cb.getId())
                        .text("Update failed: " + e.getMessage())
                        .showAlert(true)
                        .build());
            } catch (TelegramApiException ignored) {}
        }
    }

    // ======= REPORT RENDERING (HTML)
    private String buildFormattedReport(int topN) throws Exception {
        List<FundingTracker.FundingDiff> top = tracker.topDifferences(topN);
        String ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC).format(Instant.now());

        StringBuilder sb = new StringBuilder();
        sb.append("<b>🔎 Funding scan (top ").append(topN).append(")</b>\n")
                .append("<i>UTC: ").append(ts).append("</i>\n\n");

        for (FundingTracker.FundingDiff diff : top) {
            FundingTracker.Funding mx = diff.max(), mn = diff.min();

            sb.append("• <b>").append(esc(diff.base()))
                    .append("</b> — Δ <code>").append(fmt(diff.diffPct())).append("%</code>\n");

            sb.append("  Max: <b>").append(esc(mx.exchange())).append("</b> ")
                    .append("<code>").append(esc(mx.symbol())).append("</code>")
                    .append(" — <code>").append(fmt(mx.rate()*100)).append("%</code>")
                    .append(" (").append(formatEta(mx.nextFundingTimeMs(), mx.nextTimeEstimated())).append(")");
            if (!Double.isNaN(mx.price()))
                sb.append(" • Px: <code>").append(fmt(mx.price())).append("</code>");
            sb.append("\n");

            sb.append("  Min: <b>").append(esc(mn.exchange())).append("</b> ")
                    .append("<code>").append(esc(mn.symbol())).append("</code>")
                    .append(" — <code>").append(fmt(mn.rate()*100)).append("%</code>")
                    .append(" (").append(formatEta(mn.nextFundingTimeMs(), mn.nextTimeEstimated())).append(")");
            if (!Double.isNaN(mn.price()))
                sb.append(" • Px: <code>").append(fmt(mn.price())).append("</code>");
            sb.append("\n\n");
        }
        return sb.toString();
    }

    // ======= NOTIFICATIONS
    private void scheduleNotification(long chatId, long windowMin, double thresholdPct, long intervalMin) {
        cancelNotification(chatId);

        Runnable task = () -> {
            try {
                List<FundingTracker.FundingDiff> top = tracker.topDifferences(10);
                if (top.isEmpty()) return;

                FundingTracker.FundingDiff best = top.get(0);
                double delta = best.diffPct();
                long etaMin = Math.min(
                        etaMinutes(best.max().nextFundingTimeMs()),
                        etaMinutes(best.min().nextFundingTimeMs())
                );

                if (Double.compare(delta, thresholdPct) >= 0 && etaMin >= 0 && etaMin <= windowMin) {
                    sendHtml(chatId, renderAlert(best, thresholdPct, windowMin, etaMin));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        long period = Math.max(1, intervalMin);
        ScheduledFuture<?> f = scheduler.scheduleAtFixedRate(task, 0, period, TimeUnit.MINUTES);
        notificationTasks.put(chatId, f);
    }

    private void cancelNotification(long chatId) {
        Optional.ofNullable(notificationTasks.remove(chatId)).ifPresent(f -> f.cancel(true));
    }

    private String renderAlert(FundingTracker.FundingDiff d, double thr, long window, long eta) {
        FundingTracker.Funding mx = d.max(), mn = d.min();
        StringBuilder sb = new StringBuilder();
        sb.append("<b>⚡ Funding alert</b>\n")
                .append("Δ <code>").append(fmt(d.diffPct())).append("%</code> (≥ ")
                .append(fmt(thr)).append("%)\n")
                .append("Window ≤ ").append(window).append("m, next ~").append(eta).append("m\n\n");

        sb.append("<b>").append(esc(d.base())).append("</b>\n");
        sb.append("Max: <b>").append(esc(mx.exchange())).append("</b> <code>").append(esc(mx.symbol())).append("</code>")
                .append(" — <code>").append(fmt(mx.rate()*100)).append("%</code>")
                .append(" (").append(formatEta(mx.nextFundingTimeMs(), mx.nextTimeEstimated())).append(")");
        if (!Double.isNaN(mx.price())) sb.append(" • Px: <code>").append(fmt(mx.price())).append("</code>");
        sb.append("\n");

        sb.append("Min: <b>").append(esc(mn.exchange())).append("</b> <code>").append(esc(mn.symbol())).append("</code>")
                .append(" — <code>").append(fmt(mn.rate()*100)).append("%</code>")
                .append(" (").append(formatEta(mn.nextFundingTimeMs(), mn.nextTimeEstimated())).append(")");
        if (!Double.isNaN(mn.price())) sb.append(" • Px: <code>").append(fmt(mn.price())).append("</code>");

        return sb.toString();
    }

    // ======= UI helpers
    private void sendHtmlWithUpdateButton(Long chatId, String html, int topN) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .parseMode("HTML")
                    .text(html)
                    .replyMarkup(updateKeyboard(topN))
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private InlineKeyboardMarkup updateKeyboard(int topN) {
        InlineKeyboardButton btn = InlineKeyboardButton.builder()
                .text("🔄 Update")
                .callbackData(CB_PREFIX + topN)
                .build();
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
        kb.setKeyboard(List.of(List.of(btn)));
        return kb;
    }

    private void sendHtml(Long chatId, String html) {
        try {
            execute(SendMessage.builder().chatId(chatId).parseMode("HTML").text(html).build());
        } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendText(Long chatId, String text) {
        try {
            execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    // ======= formatting / parsing utils
    private static String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
    private static String esc(String s) { return s == null ? "" : s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"); }
    private String formatEta(Long ms, boolean estimated) {
        if (ms == null) return "—";
        long delta = ms - System.currentTimeMillis();
        if (delta <= 0) return estimated ? "≈0m" : "0m";
        long m = delta / 60000, h = m / 60, r = m % 60;
        String s = (h > 48) ? (h/24) + "d " + (h%24) + "h" : (h > 0 ? h + "h " + r + "m" : r + "m");
        return estimated ? "≈" + s : s;
    }
    private static long etaMinutes(Long ms) {
        if (ms == null) return -1;
        long d = ms - System.currentTimeMillis();
        return d <= 0 ? 0 : d / 60000;
    }
    private static long parseDurationToMinutes(String token) {
        String t = token.trim().toLowerCase(Locale.ROOT);
        var m = Pattern.compile("^([0-9]+)([mh]?)$").matcher(t);
        if (!m.matches()) throw new IllegalArgumentException("bad duration");
        long v = Long.parseLong(m.group(1));
        String u = m.group(2);
        return "h".equals(u) ? v * 60 : v; // default minutes (also 'm' or empty)
    }
    private static double parsePercent(String token) {
        return Double.parseDouble(token.trim().replace("%","").replace(",", "."));
    }

    // ======= welcome/pin (как было)
    private void sendMessageInfo(Long chatId, Message message){
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId);
        sendMessage.setText(
                Optional.ofNullable(message.getForwardFrom())
                        .map(User::toString)
                        .orElse("empty user")
        );
        try {
            var response = execute(sendMessage);
            execute(PinChatMessage.builder()
                    .chatId(chatId)
                    .messageId(response.getMessageId())
                    .build());
        } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendAndPinWelcomeMessage(Long chatId) {
        try {
            var response = execute(SendMessage.builder()
                    .chatId(chatId)
                    .parseMode("HTML")
                    .text("""
                          <b>Welcome to evil hamster bot 🐹</b>

                          Commands:
                          • <b>/update [N]</b> — показать топ-N по дельте фандинга
                          • <b>/notification &lt;window&gt; &lt;percent&gt; &lt;interval&gt;</b>
                            e.g. <code>/notification 30m 1% 60m</code>
                          • <b>/notification_stop</b> — отключить уведомления
                          """)
                    .replyMarkup(updateKeyboard(10))
                    .build());
            execute(PinChatMessage.builder()
                    .chatId(chatId)
                    .messageId(response.getMessageId())
                    .build());
        } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    @Override
    public String getBotUsername() { return properties.getBotName(); }
}
