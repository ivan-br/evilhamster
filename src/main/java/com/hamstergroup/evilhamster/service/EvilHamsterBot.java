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
import java.util.Optional;

@Component
public class EvilHamsterBot extends TelegramLongPollingBot {
    private static final String CB_PREFIX = "UPDATE_TOP_N:";
    private final HamsterConfigProperties properties;
    private final FundingTracker tracker; // <-- use service directly

    public EvilHamsterBot(HamsterConfigProperties properties) {
        super(properties.getBotToken());
        this.properties = properties;
        this.tracker = new FundingTracker(); // or inject via Spring if you prefer
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
                int topN = 10;
                String[] parts = text.split("\\s+");
                if (parts.length >= 2) try {
                    topN = Math.max(1, Integer.parseInt(parts[1]));
                } catch (Exception ignored) {
                }
                String html = buildFormattedReport(topN);
                sendHtmlWithUpdateButton(chatId, html, topN);
                return;
            }

            sendMessageInfo(chatId, update.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // === Callback: 🔄 Update
    private void handleCallback(Update update) {
        var cb = update.getCallbackQuery();
        String data = cb.getData() == null ? "" : cb.getData();
        if (!data.startsWith(CB_PREFIX)) return;

        int topN = 10;
        try {
            topN = Integer.parseInt(data.substring(CB_PREFIX.length()));
        } catch (Exception ignored) {
        }

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
                        .text("Ошибка обновления: " + e.getMessage())
                        .showAlert(true)
                        .build());
            } catch (TelegramApiException ignored) {
            }
        }
    }

    // === Build HTML directly from DTOs (no parsing text)
    private String buildFormattedReport(int topN) throws Exception {
        List<FundingTracker.FundingDiff> top = tracker.topDifferences(topN);
        String ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.now());

        StringBuilder sb = new StringBuilder();
        sb.append("<b>🔎 Funding scan (top ").append(topN).append(")</b>\n")
                .append("<i>UTC: ").append(ts).append("</i>\n\n");

        for (FundingTracker.FundingDiff diff : top) {
            FundingTracker.Funding mx = diff.max();
            FundingTracker.Funding mn = diff.min();

            sb.append("• <b>").append(esc(diff.base()))
                    .append("</b> — Δ <code>").append(fmt(diff.diffPct())).append("%</code>\n");

            sb.append("  Max: <b>").append(esc(mx.exchange()))
                    .append("</b> <code>").append(esc(mx.symbol())).append("</code>")
                    .append(" — <code>").append(fmt(mx.rate() * 100)).append("%</code>")
                    .append(" (").append(formatEta(mx.nextFundingTimeMs(), mx.nextTimeEstimated())).append(")");
            if (!Double.isNaN(mx.price()))
                sb.append(" • Px: <code>").append(fmt(mx.price())).append("</code>");
            sb.append("\n");

            sb.append("  Min: <b>").append(esc(mn.exchange()))
                    .append("</b> <code>").append(esc(mn.symbol())).append("</code>")
                    .append(" — <code>").append(fmt(mn.rate() * 100)).append("%</code>")
                    .append(" (").append(formatEta(mn.nextFundingTimeMs(), mn.nextTimeEstimated())).append(")");
            if (!Double.isNaN(mn.price()))
                sb.append(" • Px: <code>").append(fmt(mn.price())).append("</code>");
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private String formatEta(Long ms, boolean estimated) {
        if (ms == null) return "—";
        long delta = ms - System.currentTimeMillis();
        if (delta <= 0) return estimated ? "≈0m" : "0m";
        long m = delta / 60000, h = m / 60, r = m % 60;
        String s = (h > 48) ? (h / 24) + "d " + (h % 24) + "h" : (h > 0 ? h + "h " + r + "m" : r + "m");
        return estimated ? "≈" + s : s;
    }

    // === Sending helpers ===
    private void sendHtmlWithUpdateButton(Long chatId, String html, int topN) {
        final int MAX = 4096;
        if (html.length() <= MAX) {
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
            return;
        }
        int start = 0;
        while (start < html.length()) {
            int end = Math.min(start + MAX, html.length());
            if (end < html.length()) {
                int lastBreak = html.lastIndexOf("\n\n", end);
                if (lastBreak > start) end = lastBreak + 2;
            }
            boolean last = end >= html.length();
            try {
                execute(SendMessage.builder()
                        .chatId(chatId)
                        .parseMode("HTML")
                        .text(html.substring(start, end))
                        .replyMarkup(last ? updateKeyboard(topN) : null)
                        .build());
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
            start = end;
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

    private static String fmt(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ===== your existing helper methods kept intact =====
    private void sendMessageInfo(Long chatId, Message message) {
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
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendAndPinWelcomeMessage(Long chatId) {
        try {
            var response = execute(SendMessage.builder()
                    .chatId(chatId)
                    .parseMode("HTML")
                    .text("""
                            <b>Welcome to evil hamster bot 🐹</b>
                            
                            Commands:
                            • <b>/update [N]</b> — показать топ-N монет по максимальной разнице фандинга (по умолчанию N=10)
                            """)
                    .replyMarkup(updateKeyboard(10))
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
}




