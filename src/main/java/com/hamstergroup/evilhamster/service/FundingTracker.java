package com.hamstergroup.evilhamster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
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
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    public record CoinMove(String symbol, double fundingPercent, double priceChangePercent) {
    }

    public List<CoinMove> findGainers(double thresholdPercent) throws Exception {
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

            double fundingPercent = fundingBySymbol.getOrDefault(symbol, Double.NaN);
            moves.add(new CoinMove(symbol, fundingPercent, priceChangePercent));
        }

        moves.sort(Comparator.comparingDouble(CoinMove::priceChangePercent).reversed());
        return moves;
    }

    private MarketSnapshot fetchMarketSnapshot() throws Exception {
        try {
            return fetchMarketSnapshotFromWebSocket();
        } catch (Exception webSocketError) {
            throw new IOException("Binance Futures WebSocket market data is unavailable.", webSocketError);
        }
    }

    private MarketSnapshot fetchMarketSnapshotFromWebSocket() throws Exception {
        JsonNode tickers = readWebSocketJson(BINANCE_FUTURES_WS_BASE_URL + "!ticker@arr");
        JsonNode markPrices = readWebSocketJson(BINANCE_FUTURES_WS_BASE_URL + "!markPrice@arr@1s");
        Map<String, Double> fundingBySymbol = new HashMap<>();

        if (markPrices.isArray()) {
            for (JsonNode markPrice : markPrices) {
                String symbol = markPrice.path("s").asText("");
                double rate = parseDouble(markPrice.path("r").asText(null));
                if (symbol.endsWith("USDT") && !Double.isNaN(rate)) {
                    fundingBySymbol.put(symbol, rate * 100.0);
                }
            }
        }

        return new MarketSnapshot(tickers, fundingBySymbol);
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

    private record MarketSnapshot(JsonNode tickers, Map<String, Double> fundingPercentBySymbol) {
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
