package com.hamstergroup.evilhamster.service;

import com.hamstergroup.evilhamster.config.HamsterConfigProperties;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class EvilHamsterBot extends TelegramLongPollingBot {

    private static final String CALLBACK_SET_THRESHOLD = "SET_THRESHOLD";
    private static final String CALLBACK_SET_INTERVAL = "SET_INTERVAL";
    private static final String CALLBACK_SET_PRICE = "SET_PRICE";
    private static final String CALLBACK_UPDATE = "UPDATE";
    private static final double DEFAULT_THRESHOLD_PERCENT = 50.0;
    private static final int DEFAULT_POLL_INTERVAL_MINUTES = 60;
    private static final int MAX_ROWS_PER_MESSAGE = 80;

    private final HamsterConfigProperties properties;
    private final FundingTracker tracker = new FundingTracker();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Map<Long, Double> thresholds = new ConcurrentHashMap<>();
    private final Map<Long, Integer> pollIntervals = new ConcurrentHashMap<>();
    private final Map<Long, PriceRange> priceRanges = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> pollTasks = new ConcurrentHashMap<>();
    private final Map<Long, InputMode> inputModes = new ConcurrentHashMap<>();

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

            if (!update.hasMessage() || !update.getMessage().hasText()) {
                return;
            }

            long chatId = update.getMessage().getChatId();
            String text = update.getMessage().getText().trim();

            if ("/start".equalsIgnoreCase(text)) {
                startChat(chatId);
                return;
            }

            InputMode inputMode = inputModes.remove(chatId);
            if (inputMode == InputMode.THRESHOLD) {
                updateThreshold(chatId, text);
                return;
            }
            if (inputMode == InputMode.INTERVAL) {
                updatePollInterval(chatId, text);
                return;
            }
            if (inputMode == InputMode.PRICE) {
                updatePriceRange(chatId, text);
                return;
            }

            if ("/update".equalsIgnoreCase(text)) {
                sendCurrentGainers(chatId);
                return;
            }

            sendMenu(chatId, "Choose an action.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleCallback(Update update) {
        var callback = update.getCallbackQuery();
        String data = callback.getData() == null ? "" : callback.getData();
        long chatId = callback.getMessage().getChatId();

        try {
            execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callback.getId())
                    .build());

            switch (data) {
                case CALLBACK_SET_THRESHOLD -> {
                    inputModes.put(chatId, InputMode.THRESHOLD);
                    sendText(chatId, "Enter threshold from 1 to 100. Example: 90");
                }
                case CALLBACK_SET_INTERVAL -> {
                    inputModes.put(chatId, InputMode.INTERVAL);
                    sendText(chatId, "Enter poll interval in minutes. Example: 60");
                }
                case CALLBACK_SET_PRICE -> {
                    inputModes.put(chatId, InputMode.PRICE);
                    sendText(chatId, "Enter min and max coin price. Example: 0.01 5");
                }
                case CALLBACK_UPDATE -> sendCurrentGainers(chatId);
                default -> sendMenu(chatId, "Choose an action.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startChat(long chatId) {
        thresholds.putIfAbsent(chatId, DEFAULT_THRESHOLD_PERCENT);
        pollIntervals.putIfAbsent(chatId, DEFAULT_POLL_INTERVAL_MINUTES);
        priceRanges.putIfAbsent(chatId, PriceRange.all());
        sendMenu(chatId, "Binance Futures scanner is running.");
        restartPolling(chatId, getPollInterval(chatId));
    }

    private void updateThreshold(long chatId, String text) {
        try {
            double threshold = Double.parseDouble(text.replace("%", "").replace(",", "."));
            if (threshold < 1.0 || threshold > 100.0) {
                sendText(chatId, "Threshold must be from 1 to 100.");
                inputModes.put(chatId, InputMode.THRESHOLD);
                return;
            }

            thresholds.put(chatId, threshold);
            sendMenu(chatId, "Threshold set to " + FundingTracker.formatPercent(threshold) + ".");
            restartPolling(chatId, 0);
        } catch (NumberFormatException e) {
            sendText(chatId, "Please enter a number from 1 to 100. Example: 90");
            inputModes.put(chatId, InputMode.THRESHOLD);
        }
    }

    private void updatePollInterval(long chatId, String text) {
        try {
            int minutes = Integer.parseInt(text.trim());
            if (minutes < 1) {
                sendText(chatId, "Poll interval must be at least 1 minute.");
                inputModes.put(chatId, InputMode.INTERVAL);
                return;
            }

            pollIntervals.put(chatId, minutes);
            sendMenu(chatId, "Poll interval set to " + minutes + " minutes.");
            restartPolling(chatId, 0);
        } catch (NumberFormatException e) {
            sendText(chatId, "Please enter the poll interval in minutes. Example: 60");
            inputModes.put(chatId, InputMode.INTERVAL);
        }
    }

    private void updatePriceRange(long chatId, String text) {
        try {
            PriceRange priceRange = parsePriceRange(text);
            priceRanges.put(chatId, priceRange);
            sendMenu(chatId, "Price range set to " + priceRange.format() + ".");
            restartPolling(chatId, 0);
        } catch (IllegalArgumentException e) {
            sendText(chatId, "Please enter min and max price. Example: 0.01 5");
            inputModes.put(chatId, InputMode.PRICE);
        }
    }

    private void restartPolling(long chatId, long initialDelayMinutes) {
        ScheduledFuture<?> existingTask = pollTasks.remove(chatId);
        if (existingTask != null) {
            existingTask.cancel(true);
        }

        int intervalMinutes = getPollInterval(chatId);
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(
                () -> sendScheduledGainers(chatId),
                Math.max(0, initialDelayMinutes),
                intervalMinutes,
                TimeUnit.MINUTES
        );
        pollTasks.put(chatId, task);
    }

    private void sendCurrentGainers(long chatId) {
        try {
            double threshold = getThreshold(chatId);
            PriceRange priceRange = getPriceRange(chatId);
            List<FundingTracker.CoinMove> gainers = tracker.findGainers(threshold, priceRange.min(), priceRange.max());
            sendReport(chatId, "Current Binance Futures movers", threshold, priceRange, gainers);
        } catch (Exception e) {
            sendText(chatId, "Update failed: " + friendlyError(e));
        }
    }

    private void sendScheduledGainers(long chatId) {
        try {
            System.out.println("Running scheduled poll for chat " + chatId + " every " + getPollInterval(chatId) + " minutes.");
            double threshold = getThreshold(chatId);
            PriceRange priceRange = getPriceRange(chatId);
            List<FundingTracker.CoinMove> gainers = tracker.findGainers(threshold, priceRange.min(), priceRange.max());
            sendReport(chatId, "Scheduled Binance Futures movers", threshold, priceRange, gainers);
            System.out.println("Scheduled poll sent to chat " + chatId + " with " + gainers.size() + " movers.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String friendlyError(Exception e) {
        String message = e.getMessage();
        if (message != null && message.contains("Binance Futures WebSocket market data is unavailable")) {
            return "Binance Futures WebSocket market data is unavailable.";
        }
        return message == null ? e.getClass().getSimpleName() : message;
    }

    private void sendReport(long chatId, String title, double threshold, PriceRange priceRange, List<FundingTracker.CoinMove> moves) {
        if (moves.size() <= MAX_ROWS_PER_MESSAGE) {
            sendHtml(chatId, renderReport(title, threshold, priceRange, moves), menuKeyboard());
            return;
        }

        int totalParts = (int) Math.ceil((double) moves.size() / MAX_ROWS_PER_MESSAGE);
        for (int part = 0; part < totalParts; part++) {
            int from = part * MAX_ROWS_PER_MESSAGE;
            int to = Math.min(from + MAX_ROWS_PER_MESSAGE, moves.size());
            String partTitle = title + " (" + (part + 1) + "/" + totalParts + ")";
            InlineKeyboardMarkup keyboard = part == totalParts - 1 ? menuKeyboard() : null;
            sendHtml(chatId, renderReport(partTitle, threshold, priceRange, moves.subList(from, to)), keyboard);
        }
    }

    private String renderReport(String title, double threshold, PriceRange priceRange, List<FundingTracker.CoinMove> moves) {
        StringBuilder message = new StringBuilder();
        message.append("<b>").append(escape(title)).append("</b>\n");
        message.append("Threshold: <code>").append(FundingTracker.formatPercent(threshold)).append("</code>\n");
        message.append("Price: <code>").append(priceRange.format()).append("</code>\n\n");

        if (moves.isEmpty()) {
            message.append("<code>No Binance Futures coins are above the threshold.</code>");
            return message.toString();
        }

        message.append("<pre><code>");
        message.append(String.format("%-12s | %-12s | %-9s | %-8s%n", "Coin", "Price", "Funding", "Percent"));
        for (FundingTracker.CoinMove move : moves) {
            message.append(String.format(
                    "%-12s | %-12s | %-9s | %-8s%n",
                    move.symbol(),
                    FundingTracker.formatPrice(move.price()),
                    FundingTracker.formatFunding(move.fundingPercent()),
                    FundingTracker.formatPercent(move.priceChangePercent())
            ));
        }
        message.append("</code></pre>");
        return message.toString();
    }

    private void sendMenu(long chatId, String text) {
        String message = text + "\n\n"
                + "Threshold: " + FundingTracker.formatPercent(getThreshold(chatId)) + "\n"
                + "Price: " + getPriceRange(chatId).format() + "\n"
                + "Poll interval: " + getPollInterval(chatId) + " minutes";
        sendText(chatId, message, menuKeyboard());
    }

    private InlineKeyboardMarkup menuKeyboard() {
        InlineKeyboardButton thresholdButton = InlineKeyboardButton.builder()
                .text("Specify threshold")
                .callbackData(CALLBACK_SET_THRESHOLD)
                .build();
        InlineKeyboardButton intervalButton = InlineKeyboardButton.builder()
                .text("Poll interval")
                .callbackData(CALLBACK_SET_INTERVAL)
                .build();
        InlineKeyboardButton priceButton = InlineKeyboardButton.builder()
                .text("Price")
                .callbackData(CALLBACK_SET_PRICE)
                .build();
        InlineKeyboardButton updateButton = InlineKeyboardButton.builder()
                .text("Update")
                .callbackData(CALLBACK_UPDATE)
                .build();

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(List.of(
                List.of(thresholdButton),
                List.of(priceButton),
                List.of(intervalButton),
                List.of(updateButton)
        ));
        return keyboard;
    }

    private double getThreshold(long chatId) {
        return thresholds.getOrDefault(chatId, DEFAULT_THRESHOLD_PERCENT);
    }

    private int getPollInterval(long chatId) {
        return pollIntervals.getOrDefault(chatId, DEFAULT_POLL_INTERVAL_MINUTES);
    }

    private PriceRange getPriceRange(long chatId) {
        return priceRanges.getOrDefault(chatId, PriceRange.all());
    }

    private PriceRange parsePriceRange(String text) {
        String[] parts = text.trim().replace("-", " ").replace(";", " ").split("\\s+");
        if (parts.length != 2) {
            throw new IllegalArgumentException("bad price range");
        }

        double min = Double.parseDouble(parts[0].replace(",", "."));
        double max = Double.parseDouble(parts[1].replace(",", "."));
        if (Double.isNaN(min) || Double.isNaN(max) || min < 0.0 || max < min) {
            throw new IllegalArgumentException("bad price range");
        }
        return new PriceRange(min, max);
    }

    private void sendText(long chatId, String text) {
        sendText(chatId, text, null);
    }

    private void sendText(long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .replyMarkup(keyboard)
                    .disableWebPagePreview(true)
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendHtml(long chatId, String html, InlineKeyboardMarkup keyboard) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .parseMode("HTML")
                    .text(html)
                    .replyMarkup(keyboard)
                    .disableWebPagePreview(true)
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private static String escape(String value) {
        return value == null
                ? ""
                : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public String getBotUsername() {
        return properties.getBotName();
    }

    private enum InputMode {
        THRESHOLD,
        INTERVAL,
        PRICE
    }

    private record PriceRange(double min, double max) {
        static PriceRange all() {
            return new PriceRange(0.0, Double.MAX_VALUE);
        }

        String format() {
            if (max == Double.MAX_VALUE) {
                return "all";
            }
            return FundingTracker.formatPrice(min) + " - " + FundingTracker.formatPrice(max);
        }
    }
}
