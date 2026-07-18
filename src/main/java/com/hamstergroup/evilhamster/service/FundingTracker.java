package com.hamstergroup.evilhamster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FundingTracker {

    private static final String BINANCE_FUTURES_BASE_URL = "https://fapi.binance.com";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    public record CoinMove(String symbol, double fundingPercent, double priceChangePercent) {
    }

    public List<CoinMove> findGainers(double thresholdPercent) throws Exception {
        Map<String, Double> fundingBySymbol = fetchFundingPercentBySymbol();
        JsonNode tickers = getJson(BINANCE_FUTURES_BASE_URL + "/fapi/v1/ticker/24hr");

        List<CoinMove> moves = new ArrayList<>();
        if (!tickers.isArray()) {
            return moves;
        }

        for (JsonNode ticker : tickers) {
            String symbol = ticker.path("symbol").asText("");
            if (!symbol.endsWith("USDT")) {
                continue;
            }

            double priceChangePercent = parseDouble(ticker.path("priceChangePercent").asText(null));
            if (Double.isNaN(priceChangePercent) || priceChangePercent < thresholdPercent) {
                continue;
            }

            double fundingPercent = fundingBySymbol.getOrDefault(symbol, Double.NaN);
            moves.add(new CoinMove(symbol, fundingPercent, priceChangePercent));
        }

        moves.sort(Comparator.comparingDouble(CoinMove::priceChangePercent).reversed());
        return moves;
    }

    private Map<String, Double> fetchFundingPercentBySymbol() throws Exception {
        JsonNode premiumIndexes = getJson(BINANCE_FUTURES_BASE_URL + "/fapi/v1/premiumIndex");
        Map<String, Double> result = new HashMap<>();

        if (!premiumIndexes.isArray()) {
            return result;
        }

        for (JsonNode premiumIndex : premiumIndexes) {
            String symbol = premiumIndex.path("symbol").asText("");
            double rate = parseDouble(premiumIndex.path("lastFundingRate").asText(null));
            if (symbol.endsWith("USDT") && !Double.isNaN(rate)) {
                result.put(symbol, rate * 100.0);
            }
        }

        return result;
    }

    private static JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "EvilHamster/2.0")
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }

        return JSON.readTree(response.body());
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
}
