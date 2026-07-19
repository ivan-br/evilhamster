package com.hamstergroup.evilhamster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class FundingTracker {

    private static final String BINANCE_FUTURES_WS_BASE_URL = "wss://fstream.binance.com/market/ws/";
    private static final String[] BINANCE_FUNDING_URLS = {
            "https://fapi.binance.com/fapi/v1/premiumIndex",
            "https://www.binance.com/fapi/v1/premiumIndex"
    };
    private static final Duration SNAPSHOT_CACHE_TTL = Duration.ofSeconds(10);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private volatile CachedSnapshot cachedSnapshot;

    public record CoinMove(String symbol, double price, double fundingPercent, double priceChangePercent, double volumeMillions) {
    }

    public List<CoinMove> findGainers(double thresholdPercent) throws Exception {
        return findGainers(thresholdPercent, 0.0, Double.MAX_VALUE, 0.0, Double.MAX_VALUE);
    }

    public List<CoinMove> findGainers(double thresholdPercent, double minPrice, double maxPrice) throws Exception {
        return findGainers(thresholdPercent, minPrice, maxPrice, 0.0, Double.MAX_VALUE);
    }

    public List<CoinMove> findGainers(double thresholdPercent,
                                      double minPrice,
                                      double maxPrice,
                                      double minVolumeMillions,
                                      double maxVolumeMillions) throws Exception {
        MarketSnapshot snapshot = fetchMarketSnapshot();
        Map<String, Double> fundingBySymbol = snapshot.fundingPercentBySymbol();
        JsonNode tickers = snapshot.tickers();

        List<CoinMove> moves = new ArrayList<>();
        if (!tickers.isArray()) {
            return moves;
        }

        for (JsonNode ticker : tickers) {
            String symbol = firstText(ticker, "symbol", "s");
            if (!symbol.endsWith("USDT")) {
                continue;
            }

            double priceChangePercent = parseDouble(firstText(ticker, "priceChangePercent", "P"));
            if (Double.isNaN(priceChangePercent) || priceChangePercent < thresholdPercent) {
                continue;
            }

            double price = parseDouble(firstText(ticker, "lastPrice", "c"));
            if (Double.isNaN(price) || price < minPrice || price > maxPrice) {
                continue;
            }

            double volumeMillions = parseDouble(firstText(ticker, "quoteVolume", "q")) / 1_000_000.0;
            if (Double.isNaN(volumeMillions) || volumeMillions < minVolumeMillions || volumeMillions > maxVolumeMillions) {
                continue;
            }

            double fundingPercent = fundingBySymbol.getOrDefault(symbol, Double.NaN);
            moves.add(new CoinMove(symbol, price, fundingPercent, priceChangePercent, volumeMillions));
        }

        moves.sort(Comparator.comparingDouble(CoinMove::priceChangePercent).reversed());
        return moves;
    }

    private MarketSnapshot fetchMarketSnapshot() throws Exception {
        CachedSnapshot currentSnapshot = cachedSnapshot;
        if (currentSnapshot != null && currentSnapshot.isFresh()) {
            return currentSnapshot.marketSnapshot();
        }

        synchronized (this) {
            currentSnapshot = cachedSnapshot;
            if (currentSnapshot != null && currentSnapshot.isFresh()) {
                return currentSnapshot.marketSnapshot();
            }

            MarketSnapshot marketSnapshot = readMarketSnapshot();
            cachedSnapshot = new CachedSnapshot(marketSnapshot, Instant.now());
            return marketSnapshot;
        }
    }

    private MarketSnapshot readMarketSnapshot() throws Exception {
        try {
            return fetchMarketSnapshotFromWebSocket();
        } catch (Exception webSocketError) {
            throw new IOException("Binance Futures WebSocket market data is unavailable.", webSocketError);
        }
    }

    private MarketSnapshot fetchMarketSnapshotFromWebSocket() throws Exception {
        CompletableFuture<JsonNode> tickersFuture = CompletableFuture.supplyAsync(() ->
                readWebSocketJsonUnchecked(BINANCE_FUTURES_WS_BASE_URL + "!ticker@arr")
        );
        CompletableFuture<Map<String, Double>> fundingFuture = CompletableFuture.supplyAsync(() ->
                fetchFundingPercentBySymbolUnchecked()
        );

        JsonNode tickers = tickersFuture.get(30, TimeUnit.SECONDS);
        Map<String, Double> fundingBySymbol = fundingFuture.get(30, TimeUnit.SECONDS);

        return new MarketSnapshot(tickers, fundingBySymbol);
    }

    private static Map<String, Double> fetchFundingPercentBySymbolUnchecked() {
        try {
            return fetchFundingPercentBySymbol();
        } catch (Exception e) {
            System.out.println("Binance Futures funding data is unavailable: " + e.getMessage());
            return Map.of();
        }
    }

    private static Map<String, Double> fetchFundingPercentBySymbol() throws Exception {
        Exception lastError = null;
        for (String url : BINANCE_FUNDING_URLS) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("accept", "application/json")
                        .header("user-agent", "Mozilla/5.0 (compatible; EvilHamsterBot/1.0)")
                        .GET()
                        .build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }

                return parseFundingPercentBySymbol(JSON.readTree(response.body()));
            } catch (Exception e) {
                lastError = e;
            }
        }

        throw lastError == null ? new IOException("No Binance funding endpoints configured.") : lastError;
    }

    private static Map<String, Double> parseFundingPercentBySymbol(JsonNode premiumIndex) {
        Map<String, Double> fundingBySymbol = new HashMap<>();

        if (premiumIndex.isArray()) {
            for (JsonNode markPrice : premiumIndex) {
                String symbol = firstText(markPrice, "symbol", "s");
                double rate = parseDouble(firstText(markPrice, "lastFundingRate", "r"));
                if (symbol.endsWith("USDT") && !Double.isNaN(rate)) {
                    fundingBySymbol.put(symbol, rate * 100.0);
                }
            }
        }

        return fundingBySymbol;
    }

    private static JsonNode readWebSocketJsonUnchecked(String url) {
        try {
            return readWebSocketJson(url);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode readWebSocketJson(String url) throws Exception {
        FirstMessageListener listener = new FirstMessageListener();
        WebSocket webSocket = HTTP.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url), listener)
                .get(20, TimeUnit.SECONDS);

        try {
            String message = listener.message().get(25, TimeUnit.SECONDS);
            return JSON.readTree(message);
        } finally {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    private static String firstText(JsonNode node, String firstField, String secondField) {
        String firstValue = node.path(firstField).asText("");
        if (!firstValue.isBlank()) {
            return firstValue;
        }
        return node.path(secondField).asText("");
    }

    private static double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return Double.NaN;
        }

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    public static String formatPercent(double value) {
        if (Double.isNaN(value)) {
            return "n/a";
        }
        return String.format(Locale.US, "%.2f%%", value);
    }

    public static String formatFunding(double value) {
        if (Double.isNaN(value)) {
            return "n/a";
        }
        return String.format(Locale.US, "%.4f%%", value);
    }

    public static String formatPrice(double value) {
        if (Double.isNaN(value)) {
            return "n/a";
        }
        if (value >= 1_000.0) {
            return String.format(Locale.US, "%.2f", value);
        }
        if (value >= 1.0) {
            return String.format(Locale.US, "%.4f", value);
        }
        return String.format(Locale.US, "%.8f", value);
    }

    public static String formatVolumeMillions(double value) {
        if (Double.isNaN(value)) {
            return "n/a";
        }
        return String.format(Locale.US, "%.2fM", value);
    }

    private record MarketSnapshot(JsonNode tickers, Map<String, Double> fundingPercentBySymbol) {
    }

    private record CachedSnapshot(MarketSnapshot marketSnapshot, Instant createdAt) {
        boolean isFresh() {
            return createdAt.plus(SNAPSHOT_CACHE_TTL).isAfter(Instant.now());
        }
    }

    private static final class FirstMessageListener implements WebSocket.Listener {
        private final CompletableFuture<String> message = new CompletableFuture<>();
        private final StringBuilder buffer = new StringBuilder();

        CompletableFuture<String> message() {
            return message;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                message.complete(buffer.toString());
            } else {
                webSocket.request(1);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            message.completeExceptionally(error);
            WebSocket.Listener.super.onError(webSocket, error);
        }
    }
}
