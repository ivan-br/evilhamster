package com.hamstergroup.evilhamster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Lightweight service to fetch funding rates across exchanges and compute
 * top-N symbols with the largest funding-rate spread (max - min) per base asset.
 * <p>
 * No public static void main here. Use topDifferences(N) programmatically.
 */
public class FundingTracker {

    // ===== Model =====
    public record Funding(String exchange,
                          String symbol,
                          double rate,           // as fraction (0.001 == 0.1%)
                          double price,          // last/mark/index price if available, NaN if not
                          Long nextFundingTimeMs, // millis epoch if API provides (or estimated)
                          boolean nextTimeEstimated) {
    }

    public record FundingDiff(String base,
                              Funding max,        // instrument with highest rate for this base
                              Funding min,        // instrument with lowest rate for this base
                              double diffPct) {   // (max.rate - min.rate) * 100
    }

    interface ExchangeAdapter {
        String name();

        List<Funding> fetch() throws Exception;
    }

    // ===== HTTP / JSON =====
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "FundingTracker/1.4")
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url + " body=" + resp.body());
        }
        return JSON.readTree(resp.body());
    }

    private static double d(String s) {
        if (s == null || s.isBlank()) return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static Long l(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== Next funding estimation (if API doesn't provide it) =====
    // Most exchanges: 00:00/08:00/16:00 UTC; KuCoin: offset +4h (04/12/20 UTC)
    private static long nextEightHourBoundaryUtcMillis(int offsetHours) {
        Instant now = Instant.now();
        ZonedDateTime z = now.atZone(ZoneOffset.UTC);
        int hour = z.getHour();
        int mod = Math.floorMod(hour - offsetHours, 8);
        int addH = (8 - mod) % 8;
        if (addH == 0) addH = 8;
        return z.withMinute(0).withSecond(0).withNano(0).plusHours(addH).toInstant().toEpochMilli();
    }

    private static Funding withEstimatedTime(Funding f, int offsetHours) {
        if (f.nextFundingTimeMs != null) return f;
        long est = nextEightHourBoundaryUtcMillis(offsetHours);
        return new Funding(f.exchange, f.symbol, f.rate, f.price, est, true);
    }

    // ===== Adapters =====
    static class Binance implements ExchangeAdapter {
        public String name() {
            return "Binance";
        }

        public List<Funding> fetch() throws Exception {
            JsonNode arr = getJson("https://fapi.binance.com/fapi/v1/premiumIndex");
            List<Funding> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String symbol = n.path("symbol").asText("");
                if (!symbol.endsWith("USDT")) continue;
                double rate = d(n.path("lastFundingRate").asText(null));
                double price = d(n.path("markPrice").asText(null));
                Long next = n.hasNonNull("nextFundingTime") ? n.get("nextFundingTime").asLong() : null;
                if (!Double.isNaN(rate)) out.add(new Funding(name(), symbol, rate, price, next, false));
            }
            return out.stream().map(f -> withEstimatedTime(f, 0)).toList();
        }
    }

    static class Bybit implements ExchangeAdapter {
        public String name() {
            return "Bybit";
        }

        public List<Funding> fetch() throws Exception {
            JsonNode root = getJson("https://api.bybit.com/v5/market/tickers?category=linear");
            JsonNode list = root.path("result").path("list");
            List<Funding> out = new ArrayList<>();
            if (list.isArray()) for (JsonNode n : list) {
                String symbol = n.path("symbol").asText("");
                double rate = d(n.path("fundingRate").asText(null));
                double price = d(n.path("lastPrice").asText(null));
                Long next = null;
                if (n.has("nextFundingTime") && !n.get("nextFundingTime").isNull())
                    next = n.get("nextFundingTime").isNumber() ? n.get("nextFundingTime").asLong() : l(n.get("nextFundingTime").asText());
                if (!Double.isNaN(rate)) out.add(new Funding(name(), symbol, rate, price, next, false));
            }
            return out.stream().map(f -> withEstimatedTime(f, 0)).toList();
        }
    }

    static class KuCoinFutures implements ExchangeAdapter {
        public String name() {
            return "KuCoin";
        }

        public List<Funding> fetch() throws Exception {
            JsonNode root = getJson("https://api-futures.kucoin.com/api/v1/contracts/active");
            JsonNode data = root.path("data");
            List<Funding> out = new ArrayList<>();
            if (data.isArray()) for (JsonNode n : data) {
                String symbol = n.path("symbol").asText("");
                double rate = d(n.path("fundingFeeRate").asText(null));
                double price = d(n.path("indexPrice").asText(null));
                Long next = n.hasNonNull("fundingNextApply") ? n.get("fundingNextApply").asLong() : null;
                if (!Double.isNaN(rate) && symbol.endsWith("USDTM"))
                    out.add(new Funding(name(), symbol, rate, price, next, false));
            }
            return out.stream().map(f -> withEstimatedTime(f, 4)).toList(); // KuCoin offset +4h
        }
    }

    static class GateFutures implements ExchangeAdapter {
        public String name() {
            return "Gate.io";
        }

        public List<Funding> fetch() throws Exception {
            JsonNode arr = getJson("https://api.gateio.ws/api/v4/futures/usdt/tickers");
            List<Funding> out = new ArrayList<>();
            if (arr.isArray()) for (JsonNode n : arr) {
                String symbol = n.path("contract").asText("");
                double rate = d(n.path("funding_rate").asText(null));
                double price = d(n.path("last").asText(null));
                Long next = null;
                if (n.has("funding_next_apply") && !n.get("funding_next_apply").isNull())
                    next = n.get("funding_next_apply").isNumber() ? n.get("funding_next_apply").asLong() : l(n.get("funding_next_apply").asText());
                if (!Double.isNaN(rate)) out.add(new Funding(name(), symbol, rate, price, next, false));
            }
            return out.stream().map(f -> withEstimatedTime(f, 0)).toList();
        }
    }

    static class Bitget implements ExchangeAdapter {
        public String name() {
            return "Bitget";
        }

        public List<Funding> fetch() throws Exception {
            JsonNode root = getJson("https://api.bitget.com/api/mix/v1/market/tickers?productType=umcbl");
            JsonNode data = root.path("data");
            List<Funding> out = new ArrayList<>();
            if (data.isArray()) for (JsonNode n : data) {
                String symbol = n.path("symbol").asText("");
                double rate = d(n.path("fundingRate").asText(null));
                double price = d(n.path("last").asText(null));
                if (Double.isNaN(price)) price = d(n.path("lastPrice").asText(null));
                Long next = null;
                if (n.has("nextFundingTime") && !n.get("nextFundingTime").isNull())
                    next = n.get("nextFundingTime").isNumber() ? n.get("nextFundingTime").asLong() : l(n.get("nextFundingTime").asText());
                if (!Double.isNaN(rate)) out.add(new Funding(name(), symbol, rate, price, next, false));
            }
            return out.stream().map(f -> withEstimatedTime(f, 0)).toList();
        }
    }

    // placeholders if you later add them
    static class Mexc implements ExchangeAdapter {
        public String name() {
            return "MEXC";
        }

        public List<Funding> fetch() {
            return List.of();
        }
    }

    static class LBank implements ExchangeAdapter {
        public String name() {
            return "LBank";
        }

        public List<Funding> fetch() {
            return List.of();
        }
    }

    static class HTX implements ExchangeAdapter {
        public String name() {
            return "HTX";
        }

        public List<Funding> fetch() {
            return List.of();
        }
    }

    static class Ourbit implements ExchangeAdapter {
        public String name() {
            return "Ourbit";
        }

        public List<Funding> fetch() {
            return List.of();
        }
    }

    // ===== Helpers =====
    private static String baseAsset(String exchange, String symbol) {
        symbol = symbol.toUpperCase(Locale.ROOT);
        switch (exchange) {
            case "Gate.io":
                if (symbol.contains("_")) return symbol.split("_")[0];
                break; // BTC_USDT
            case "Bitget":
                if (symbol.contains("_")) symbol = symbol.substring(0, symbol.indexOf('_'));
                if (symbol.endsWith("USDT")) return symbol.substring(0, symbol.length() - 4);
                break;
            case "KuCoin":
                if (symbol.endsWith("USDTM")) return symbol.substring(0, symbol.length() - 5);
                break;
            default:
                if (symbol.endsWith("USDT")) return symbol.substring(0, symbol.length() - 4); // Binance/Bybit
        }
        return symbol.replace("-", "").replace("_", "");
    }

    // ===== Public API =====
    public List<FundingDiff> topDifferences(int topN) throws Exception {
        List<ExchangeAdapter> adapters = List.of(
                new Binance(), new Bybit(), new KuCoinFutures(), new GateFutures(), new Bitget(),
                new Mexc(), new LBank(), new HTX(), new Ourbit()
        );

        ExecutorService pool = Executors.newFixedThreadPool(adapters.size());
        List<Funding> all = new ArrayList<>();
        try {
            List<Future<List<Funding>>> futures = new ArrayList<>();
            for (ExchangeAdapter a : adapters) {
                futures.add(pool.submit(() -> {
                    try {
                        return a.fetch();
                    } catch (Exception e) {
                        System.err.println("[WARN] " + a.name() + " failed: " + e.getMessage());
                        return List.of();
                    }
                }));
            }
            for (Future<List<Funding>> f : futures) all.addAll(f.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        if (all.isEmpty()) return List.of();

        Map<String, List<Funding>> byBase = all.stream()
                .collect(Collectors.groupingBy(f -> baseAsset(f.exchange(), f.symbol())));

        List<FundingDiff> out = new ArrayList<>();
        Comparator<Funding> byRate = Comparator.comparingDouble(Funding::rate);

        for (var e : byBase.entrySet()) {
            List<Funding> list = e.getValue();
            if (list.size() < 2) continue;
            Funding min = list.stream().min(byRate).orElse(null);
            Funding max = list.stream().max(byRate).orElse(null);
            if (min == null || max == null) continue;
            if (Double.compare(min.rate(), max.rate()) == 0) continue;
            if (min.exchange().equals(max.exchange()) && min.symbol().equals(max.symbol())) continue;
            double diffPct = (max.rate() - min.rate()) * 100.0;
            out.add(new FundingDiff(e.getKey(), max, min, diffPct));
        }

        out.sort(Comparator.comparingDouble(FundingDiff::diffPct).reversed());
        return out.stream().limit(Math.max(1, topN)).toList();
    }
}

