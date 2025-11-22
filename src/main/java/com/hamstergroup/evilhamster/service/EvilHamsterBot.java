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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class EvilHamsterBot extends TelegramLongPollingBot {
    private static final String CB_PREFIX = "UPDATE_TOP_N:";
    private static final int DEFAULT_TOP = 10;

    private final HamsterConfigProperties properties;
    private final FundingTracker tracker = new FundingTracker();

    // ===== Per-chat settings =====
    private final Map<Long, Integer> scanTopN = new ConcurrentHashMap<>();
    private int getTopN(long chatId) { return scanTopN.getOrDefault(chatId, DEFAULT_TOP); }

    // ===== Scheduling state per chat =====
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Map<Long, ScheduledFuture<?>> hourlyTasks = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> preNotifyTasks = new ConcurrentHashMap<>();
    private final Set<Long> notificationsEnabled =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());

    // constants
    private static final long WINDOW_MIN = 30;        // notify exactly 30 minutes before
    private static final double THRESHOLD_PCT = 1.0;  // Δ ≥ 1%

    public EvilHamsterBot(HamsterConfigProperties properties) {
        super(properties.getBotToken());
        this.properties = properties;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallback(update);
                return;
            }
            if (!(update.hasMessage() && update.getMessage().hasText())) return;

            String text = update.getMessage().getText().trim();
            long chatId = update.getMessage().getChatId();

            if (text.startsWith("/start")) {
                sendAndPinWelcomeMessage(chatId);
                return;
            }

            if (text.startsWith("/update")) {
                int topN = DEFAULT_TOP;
                String[] parts = text.split("\\s+");
                if (parts.length >= 2) {
                    try { topN = Math.max(1, Integer.parseInt(parts[1])); } catch (Exception ignored) {}
                }
                scanTopN.put(chatId, topN); // remember user choice for auto checks
                String html = buildFormattedReport(topN);
                sendHtmlWithUpdateButton(chatId, html, topN);
                return;
            }

            if (text.equals("/notification_stop")) {
                disableNotifications(chatId);
                sendText(chatId, "🔕 Уведомления отключены.");
                return;
            }

            if (text.startsWith("/notification")) {
                enableNotifications(chatId);
                sendText(chatId,
                        "🔔 Уведомления включены: бот пришлёт алёрт ровно за 30 минут до фандинга,\n" +
                                "если Δ ≥ 1% и выполняется правило приоритета по времени/величине.\n" +
                                "Текущее N для авто-проверки: " + getTopN(chatId));
                // immediate scan so we don’t wait for the next tick
                schedulePreNotifyFromScan(chatId);
                return;
            }

            // default
            sendMessageInfo(chatId, update.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ===== CALLBACK: 🔄 Update
    private void handleCallback(Update update) {
        var cb = update.getCallbackQuery();
        String data = cb.getData() == null ? "" : cb.getData();
        if (!data.startsWith(CB_PREFIX)) return;

        long chatId = cb.getMessage().getChatId();
        int topN = getTopN(chatId);
        try { topN = Integer.parseInt(data.substring(CB_PREFIX.length())); } catch (Exception ignored) {}
        scanTopN.put(chatId, topN);

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

    // ===== PUBLIC REPORT UI (compact tables per coin) =====
    private String buildFormattedReport(int topN) throws Exception {
        List<FundingTracker.FundingDiff> top = tracker.topDifferences(topN);

        StringBuilder sb = new StringBuilder();
        // убрали общий заголовок и строку UTC

        for (FundingTracker.FundingDiff diff : top) {
            FundingTracker.Funding mx = diff.max(), mn = diff.min();

            // Заголовок монеты с дельтой
            sb.append("<b>").append(esc(diff.base()))
                    .append("</b>: Δ <code>").append(fmt(diff.diffPct())).append("%</code>\n");

            // Компактная таблица: колонки Exch|Price|Fund|ETA
            String head = String.format("%-6s|%s|%s|%s%n", "Exch", "Price", "Fund", "ETA");
            String row1 = String.format("%-6s|%s|%s|%s%n",
                    cut(mx.exchange(),6), fmt(mx.price()), fmt(mx.rate()*100) + "%", fmtCountdown(mx.nextFundingTimeMs()));
            String row2 = String.format("%-6s|%s|%s|%s%n",
                    cut(mn.exchange(),6), fmt(mn.price()), fmt(mn.rate()*100) + "%", fmtCountdown(mn.nextFundingTimeMs()));

            sb.append("<pre><code>")
                    .append(head)
                    .append(row1)
                    .append(row2)
                    .append("</code></pre>\n");
        }
        return sb.toString();
    }

    private void sendHtmlWithUpdateButton(Long chatId, String html, int topN) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .parseMode("HTML")
                    .text(html)
                    .replyMarkup(updateKeyboard(topN))
                    .disableWebPagePreview(true)
                    .build());
        } catch (TelegramApiException e) { e.printStackTrace(); }
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

    // ===== NOTIFICATION ENGINE =====
    private void enableNotifications(long chatId) {
        notificationsEnabled.add(chatId);
        restartHourly(chatId);
    }

    private void disableNotifications(long chatId) {
        notificationsEnabled.remove(chatId);
        Optional.ofNullable(hourlyTasks.remove(chatId)).ifPresent(f -> f.cancel(true));
        Optional.ofNullable(preNotifyTasks.remove(chatId)).ifPresent(f -> f.cancel(true));
    }

    /** Запуск авто-проверок каждые 60 минут С СОСДВИГОМ на :20 (16:20, 17:20, ...). */
    private void restartHourly(long chatId) {
        Optional.ofNullable(hourlyTasks.remove(chatId)).ifPresent(f -> f.cancel(true));

        // найти ближайшую отметку HH:20 локального времени
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime nextTick;
        if (now.getMinute() < 20) {
            nextTick = now.withMinute(20).withSecond(0).withNano(0);
        } else {
            nextTick = now.plusHours(1).withMinute(20).withSecond(0).withNano(0);
        }
        long initialDelayMin = Math.max(0, (nextTick.toInstant().toEpochMilli() - System.currentTimeMillis()) / 60000);

        ScheduledFuture<?> f = scheduler.scheduleAtFixedRate(() -> {
            if (!notificationsEnabled.contains(chatId)) return;
            schedulePreNotifyFromScan(chatId);
        }, initialDelayMin, 60, TimeUnit.MINUTES);

        hourlyTasks.put(chatId, f);
    }

    /** Скан: если ближайший фандинг уже ≤30 мин — отправляем алёрт немедленно; иначе ставим one-shot на ETA−30. */
    private void schedulePreNotifyFromScan(long chatId) {
        try {
            var list = tracker.topDifferences(getTopN(chatId));
            List<Candidate> candidates = new ArrayList<>();

            for (var d : list) {
                long etaMax = etaMinutesCeil(d.max().nextFundingTimeMs()); // для планирования — ceil
                long etaMin = etaMinutesCeil(d.min().nextFundingTimeMs());
                if (etaMax < 0 && etaMin < 0) continue;

                long eMax = etaMax < 0 ? Long.MAX_VALUE : etaMax;
                long eMin = etaMin < 0 ? Long.MAX_VALUE : etaMin;
                long earliest = Math.min(eMax, eMin);

                if (Double.compare(d.diffPct(), THRESHOLD_PCT) < 0) continue;
                if (!qualifiesByTimingRule(d, eMax, eMin)) continue;

                // если уже ≤30 минут — отправляем сразу
                if (earliest <= WINDOW_MIN) {
                    Optional.ofNullable(preNotifyTasks.remove(chatId)).ifPresent(s -> s.cancel(true));
                    sendHtml(chatId, renderAlertCard(d, Math.max(0, earliest)));
                    return;
                }

                candidates.add(new Candidate(d, earliest));
            }

            if (candidates.isEmpty()) return;
            candidates.sort(Comparator.comparingLong(c -> c.earliestEtaMin));
            Candidate best = candidates.get(0);

            long delayMin = best.earliestEtaMin - WINDOW_MIN;
            long delayMs = Math.max(0, delayMin * 60_000L - 20_000L); // чуть раньше 30-мин отметки

            Optional.ofNullable(preNotifyTasks.remove(chatId)).ifPresent(s -> s.cancel(true));
            ScheduledFuture<?> oneShot = scheduler.schedule(() -> firePreNotify(chatId), delayMs, TimeUnit.MILLISECONDS);
            preNotifyTasks.put(chatId, oneShot);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void firePreNotify(long chatId) {
        try {
            var list = tracker.topDifferences(getTopN(chatId));

            for (var d : list) {
                long etaMax = etaMinutesCeil(d.max().nextFundingTimeMs());
                long etaMin = etaMinutesCeil(d.min().nextFundingTimeMs());
                if (etaMax < 0 && etaMin < 0) continue;

                long eMax = etaMax < 0 ? Long.MAX_VALUE : etaMax;
                long eMin = etaMin < 0 ? Long.MAX_VALUE : etaMin;
                long earliest = Math.min(eMax, eMin);

                if (Double.compare(d.diffPct(), THRESHOLD_PCT) < 0) continue;
                if (!qualifiesByTimingRule(d, eMax, eMin)) continue;

                // если стало снова далеко (>33 мин) — перепланируем
                if (earliest > WINDOW_MIN + 3) {
                    schedulePreNotifyFromScan(chatId);
                    break;
                }

                long etaToShow = Math.max(0, earliest);
                sendHtml(chatId, renderAlertCard(d, etaToShow));
                break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            preNotifyTasks.remove(chatId);
        }
    }

    private boolean qualifiesByTimingRule(FundingTracker.FundingDiff d, long eMax, long eMin) {
        if (Math.abs(eMax - eMin) <= 2) return true; // почти одновременно

        boolean maxSooner = eMax <= eMin;
        double rMax = d.max().rate() * 100.0;
        double rMin = d.min().rate() * 100.0;
        double rSooner = maxSooner ? rMax : rMin;
        double rLater  = maxSooner ? rMin : rMax;

        if (rSooner < 0 && rLater < 0) {
            return Math.abs(rSooner) >= Math.abs(rLater);
        } else {
            return rSooner >= rLater;
        }
    }

    // ===== ALERT RENDERING =====
    private String renderAlertCard(FundingTracker.FundingDiff d, long etaSoonestMin) {
        FundingTracker.Funding mx = d.max(), mn = d.min();

        ZoneId zone = ZoneId.systemDefault();
        String ts = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")
                .withZone(zone).format(Instant.now());

        StringBuilder sb = new StringBuilder();
        sb.append("<b>🛑⚡ Алерт (Осталось ~").append(etaSoonestMin).append(" мин): ")
                .append(esc(d.base())).append("</b>\n")
                .append("Δ: <code>").append(fmt(d.diffPct())).append("%</code>  |  Порог: 1.0%  |  Окно: 30 мин\n")
                .append("Дата: ").append(ts).append("\n\n");

        String head = String.format("%-8s | %-12s | %-10s | %-8s%n", "Exch.", "Price", "Funding", "ETA");
        String row1 = String.format("%-8s | %-12s | %-10s | %-8s%n",
                cut(mx.exchange(),8), fmt(mx.price()), fmt(mx.rate()*100)+"%", fmtCountdown(mx.nextFundingTimeMs()));
        String row2 = String.format("%-8s | %-12s | %-10s | %-8s%n",
                cut(mn.exchange(),8), fmt(mn.price()), fmt(mn.rate()*100)+"%", fmtCountdown(mn.nextFundingTimeMs()));

        sb.append("<pre><code>")
                .append(head)
                .append(row1)
                .append(row2)
                .append("</code></pre>");

        return sb.toString();
    }

    private static String cut(String s, int n){ return s == null ? "" : (s.length()<=n ? s : s.substring(0,n)); }
    private static String fmtCountdown(Long ms) {
        if (ms == null) return "--:--";
        long m = etaMinutesFloor(ms);
        if (m < 0) return "--:--";
        long h = m / 60, r = m % 60;
        return String.format("%02d:%02d", h, r);
    }

    // ===== MISC UI/UTILS =====
    private void sendHtml(Long chatId, String html) {
        try {
            execute(SendMessage.builder().chatId(chatId).parseMode("HTML").text(html).disableWebPagePreview(true).build());
        } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendText(Long chatId, String text) {
        try {
            execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (TelegramApiException e) { e.printStackTrace(); }
    }

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

    // ---- ETA helpers: floor/ceil variants ----
    private static long etaMinutesFloor(Long ms) {
        if (ms == null) return -1;
        long d = ms - System.currentTimeMillis();
        return d <= 0 ? 0 : d / 60000; // для отображения
    }
    private static long etaMinutesCeil(Long ms) {
        if (ms == null) return -1;
        long d = ms - System.currentTimeMillis();
        return d <= 0 ? 0 : (d + 59_999) / 60_000; // для планирования
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

    // ===== welcome/pin helpers =====
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
            String instruction = """
                Commands:
                • /update [N] — show top-N pairs by funding spread (also sets N for auto checks)
                • /notification — enable alerts (Δ≥1%) exactly 30 minutes before funding
                • /notification_stop — disable alerts

                Notes:
                • Auto-scan runs every hour at HH:20 (e.g. 16:20, 17:20, ...), and schedules a one-shot at (ETA − 30m).
                • Right before alert time, data is refreshed again to ensure it's still valid.
                """;

            var response = execute(SendMessage.builder()
                    .chatId(chatId)
                    .parseMode("HTML")
                    .disableWebPagePreview(true)
                    .text(instruction)
                    .build());

            execute(PinChatMessage.builder()
                    .chatId(chatId)
                    .messageId(response.getMessageId())
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getBotUsername() {
        return properties.getBotName();
    }

    // ===== Internal holder =====
    private static final class Candidate {
        final FundingTracker.FundingDiff diff;
        final long earliestEtaMin;
        Candidate(FundingTracker.FundingDiff d, long eta){ this.diff = d; this.earliestEtaMin = eta; }
    }
}
