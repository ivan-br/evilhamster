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
    private static final String CALLBACK_SET_VOLUME = "SET_VOLUME";
    private static final String CALLBACK_RESET = "RESET";
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
    private final Map<Long, VolumeRange> volumeRanges = new ConcurrentHashMap<>();
    private final Map<Long, Double> pendingMinPrices = new ConcurrentHashMap<>();
    private final Map<Long, Double> pendingMinVolumes = new ConcurrentHashMap<>();
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
            if (inputMode == InputMode.PRICE_MIN) {
                updateMinPrice(chatId, text);
                return;
            }
            if (inputMode == InputMode.PRICE_MAX) {
                updateMaxPrice(chatId, text);
                return;
            }
            if (inputMode == InputMode.VOLUME_MIN) {
                updateMinVolume(chatId, text);
                return;
            }
            if (inputMode == InputMode.VOLUME_MAX) {
                updateMaxVolume(chatId, text);
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
                    sendText(chatId, "Enter percent from 1 to 100. Example: 90");
                }
                case CALLBACK_SET_INTERVAL -> {
                    inputModes.put(chatId, InputMode.INTERVAL);
                    sendText(chatId, "Enter poll interval in minutes. Example: 60");
                }
                case CALLBACK_SET_PRICE -> {
                    inputModes.put(chatId, InputMode.PRICE_MIN);
                    pendingMinPrices.remove(chatId);
                    sendText(chatId, "Min");
                }
                case CALLBACK_SET_VOLUME -> {
                    inputModes.put(chatId, InputMode.VOLUME_MIN);
                    pendingMinVolumes.remove(chatId);
                    sendText(chatId, "Min (M)");
                }
                case CALLBACK_RESET -> resetSettings(chatId);
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
        volumeRanges.putIfAbsent(chatId, VolumeRange.all());
        sendMenu(chatId, "Binance Futures scanner is running.");
        restartPolling(chatId, getPollInterval(chatId));
    }

    private void updateThreshold(long chatId, String text) {
        try {
            double threshold = Double.parseDouble(text.replace("%", "").replace(",", "."));
            if (threshold < 1.0 || threshold > 100.0) {
                sendText(chatId, "Percent must be from 1 to 100.");
                inputModes.put(chatId, InputMode.THRESHOLD);
                return;
            }

            thresholds.put(chatId, threshold);
            sendMenu(chatId, "Percent set to " + FundingTracker.formatPercent(threshold) + ".");
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

    private void updateMinPrice(long chatId, String text) {
        try {
            double min = parsePrice(text);
            pendingMinPrices.put(chatId, min);
            inputModes.put(chatId, InputMode.PRICE_MAX);
            sendText(chatId, "Max");
        } catch (IllegalArgumentException e) {
            sendText(chatId, "Min");
            inputModes.put(chatId, InputMode.PRICE_MIN);
        }
    }

    private void updateMaxPrice(long chatId, String text) {
        try {
            Double min = pendingMinPrices.get(chatId);
            if (min == null) {
                inputModes.put(chatId, InputMode.PRICE_MIN);
                sendText(chatId, "Min");
                return;
            }

            double max = parsePrice(text);
            if (max < min) {
                throw new IllegalArgumentException("max is lower than min");
            }

            PriceRange priceRange = new PriceRange(min, max);
            priceRanges.put(chatId, priceRange);
            pendingMinPrices.remove(chatId);
            sendMenu(chatId, "Price range set to " + priceRange.format() + ".");
            restartPolling(chatId, 0);
        } catch (IllegalArgumentException e) {
            sendText(chatId, "Max");
            inputModes.put(chatId, InputMode.PRICE_MAX);
        }
    }

    private void updateMinVolume(long chatId, String text) {
        try {
            double min = parseVolumeMillions(text);
            pendingMinVolumes.put(chatId, min);
            inputModes.put(chatId, InputMode.VOLUME_MAX);
            sendText(chatId, "Max (M)");
        } catch (IllegalArgumentException e) {
            sendText(chatId, "Min (M)");
            inputModes.put(chatId, InputMode.VOLUME_MIN);
        }
    }

    private void updateMaxVolume(long chatId, String text) {
        try {
            Double min = pendingMinVolumes.get(chatId);
            if (min == null) {
                inputModes.put(chatId, InputMode.VOLUME_MIN);
                sendText(chatId, "Min (M)");
                return;
            }

            double max = parseVolumeMillions(text);
            if (max < min) {
                throw new IllegalArgumentException("max volume is lower than min volume");
            }

            VolumeRange volumeRange = new VolumeRange(min, max);
            volumeRanges.put(chatId, volumeRange);
            pendingMinVolumes.remove(chatId);
            sendMenu(chatId, "Volume range set to " + volumeRange.format() + ".");
            restartPolling(chatId, 0);
        } catch (IllegalArgumentException e) {
            sendText(chatId, "Max (M)");
            inputModes.put(chatId, InputMode.VOLUME_MAX);
        }
    }

    private void resetSettings(long chatId) {
        thresholds.put(chatId, DEFAULT_THRESHOLD_PERCENT);
        pollIntervals.put(chatId, DEFAULT_POLL_INTERVAL_MINUTES);
        priceRanges.put(chatId, PriceRange.all());
        volumeRanges.put(chatId, VolumeRange.all());
        pendingMinPrices.remove(chatId);
        pendingMinVolumes.remove(chatId);
        inputModes.remove(chatId);
        sendMenu(chatId, "Settings reset to defaults.");
        restartPolling(chatId, DEFAULT_POLL_INTERVAL_MINUTES);
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
            VolumeRange volumeRange = getVolumeRange(chatId);
            List<FundingTracker.CoinMove> gainers = tracker.findGainers(
                    threshold,
                    priceRange.min(),
                    priceRange.max(),
                    volumeRange.min(),
                    volumeRange.max()
            );
            sendReport(chatId, "", threshold, priceRange, volumeRange, getPollInterval(chatId), gainers);
        } catch (Exception e) {
            sendText(chatId, "Update failed: " + friendlyError(e));
        }
    }

    private void sendScheduledGainers(long chatId) {
        try {
            System.out.println("Running scheduled poll for chat " + chatId + " every " + getPollInterval(chatId) + " minutes.");
            double threshold = getThreshold(chatId);
            PriceRange priceRange = getPriceRange(chatId);
            VolumeRange volumeRange = getVolumeRange(chatId);
            List<FundingTracker.CoinMove> gainers = tracker.findGainers(
                    threshold,
                    priceRange.min(),
                    priceRange.max(),
                    volumeRange.min(),
                    volumeRange.max()
            );
            sendReport(chatId, "", threshold, priceRange, volumeRange, getPollInterval(chatId), gainers);
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

    private void sendReport(long chatId,
                            String title,
                            double threshold,
                            PriceRange priceRange,
                            VolumeRange volumeRange,
                            int intervalMinutes,
                            List<FundingTracker.CoinMove> moves) {
        if (moves.size() <= MAX_ROWS_PER_MESSAGE) {
            sendHtml(chatId, renderReport(title, threshold, priceRange, volumeRange, intervalMinutes, moves), menuKeyboard());
            return;
        }

        int totalParts = (int) Math.ceil((double) moves.size() / MAX_ROWS_PER_MESSAGE);
        for (int part = 0; part < totalParts; part++) {
            int from = part * MAX_ROWS_PER_MESSAGE;
            int to = Math.min(from + MAX_ROWS_PER_MESSAGE, moves.size());
            String partTitle = title.isBlank() ? "" : title + " (" + (part + 1) + "/" + totalParts + ")";
            InlineKeyboardMarkup keyboard = part == totalParts - 1 ? menuKeyboard() : null;
            sendHtml(chatId, renderReport(partTitle, threshold, priceRange, volumeRange, intervalMinutes, moves.subList(from, to)), keyboard);
        }
    }

    private String renderReport(String title,
                                double threshold,
                                PriceRange priceRange,
                                VolumeRange volumeRange,
                                int intervalMinutes,
                                List<FundingTracker.CoinMove> moves) {
        StringBuilder message = new StringBuilder();
        if (!title.isBlank()) {
            message.append("<b>").append(escape(title)).append("</b>\n");
        }
        message.append("Percent: <code>").append(FundingTracker.formatPercent(threshold)).append("</code>\n");
        message.append("Min price: <code>").append(priceRange.formatMin()).append("</code>\n");
        message.append("Max price: <code>").append(priceRange.formatMax()).append("</code>\n");
        message.append("Min volume: <code>").append(volumeRange.formatMin()).append("</code>\n");
        message.append("Max volume: <code>").append(volumeRange.formatMax()).append("</code>\n");
        message.append("Interval: <code>").append(intervalMinutes).append(" minutes</code>\n\n");

        if (moves.isEmpty()) {
            message.append("<code>No Binance Futures coins are above the percent.</code>");
            return message.toString();
        }

        message.append("<pre><code>");
        message.append(String.format("%-12s | %-12s | %-9s | %-8s | %-10s%n", "Coin", "Price", "Funding", "Percent", "Volume"));
        for (FundingTracker.CoinMove move : moves) {
            message.append(String.format(
                    "%-12s | %-12s | %-9s | %-8s | %-10s%n",
                    move.symbol(),
                    FundingTracker.formatPrice(move.price()),
                    FundingTracker.formatFunding(move.fundingPercent()),
                    FundingTracker.formatPercent(move.priceChangePercent()),
                    FundingTracker.formatVolumeMillions(move.volumeMillions())
            ));
        }
        message.append("</code></pre>");
        return message.toString();
    }

    private void sendMenu(long chatId, String text) {
        String message = text + "\n\n"
                + "Percent: " + FundingTracker.formatPercent(getThreshold(chatId)) + "\n"
                + "Min price: " + getPriceRange(chatId).formatMin() + "\n"
                + "Max price: " + getPriceRange(chatId).formatMax() + "\n"
                + "Min volume: " + getVolumeRange(chatId).formatMin() + "\n"
                + "Max volume: " + getVolumeRange(chatId).formatMax() + "\n"
                + "Poll interval: " + getPollInterval(chatId) + " minutes";
        sendText(chatId, message, menuKeyboard());
    }

    private InlineKeyboardMarkup menuKeyboard() {
        InlineKeyboardButton thresholdButton = InlineKeyboardButton.builder()
                .text("Specify percent")
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
        InlineKeyboardButton volumeButton = InlineKeyboardButton.builder()
                .text("Volume")
                .callbackData(CALLBACK_SET_VOLUME)
                .build();
        InlineKeyboardButton updateButton = InlineKeyboardButton.builder()
                .text("Update")
                .callbackData(CALLBACK_UPDATE)
                .build();
        InlineKeyboardButton resetButton = InlineKeyboardButton.builder()
                .text("Reset")
                .callbackData(CALLBACK_RESET)
                .build();

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(List.of(
                List.of(thresholdButton),
                List.of(priceButton),
                List.of(volumeButton),
                List.of(intervalButton),
                List.of(updateButton),
                List.of(resetButton)
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

    private VolumeRange getVolumeRange(long chatId) {
        return volumeRanges.getOrDefault(chatId, VolumeRange.all());
    }

    private double parsePrice(String text) {
        double price = Double.parseDouble(text.trim().replace(",", "."));
        if (Double.isNaN(price) || price < 0.0) {
            throw new IllegalArgumentException("bad price");
        }
        return price;
    }

    private double parseVolumeMillions(String text) {
        double volumeMillions = Double.parseDouble(text.trim().replace(",", "."));
        if (Double.isNaN(volumeMillions) || volumeMillions < 0.0) {
            throw new IllegalArgumentException("bad volume");
        }
        return volumeMillions;
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
        PRICE_MIN,
        PRICE_MAX,
        VOLUME_MIN,
        VOLUME_MAX
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

        String formatMin() {
            return FundingTracker.formatPrice(min);
        }

        String formatMax() {
            if (max == Double.MAX_VALUE) {
                return "all";
            }
            return FundingTracker.formatPrice(max);
        }
    }

    private record VolumeRange(double min, double max) {
        static VolumeRange all() {
            return new VolumeRange(0.0, Double.MAX_VALUE);
        }

        String format() {
            if (max == Double.MAX_VALUE) {
                return "all";
            }
            return formatMin() + " - " + formatMax();
        }

        String formatMin() {
            return FundingTracker.formatVolumeMillions(min);
        }

        String formatMax() {
            if (max == Double.MAX_VALUE) {
                return "all";
            }
            return FundingTracker.formatVolumeMillions(max);
        }
    }
}
