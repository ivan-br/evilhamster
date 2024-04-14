package com.hamstergroup.evilhamster.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstergroup.evilhamster.config.HamsterConfigProperties;
import com.hamstergroup.evilhamster.dto.CoinInfo;
import jakarta.ws.rs.core.HttpHeaders;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class EvilHamsterBot extends TelegramLongPollingBot {
    private final HamsterConfigProperties properties;
    private ObjectMapper objectMapper = new ObjectMapper();

    public EvilHamsterBot(final HamsterConfigProperties properties) {
        super(properties.getBotToken());
        this.properties = properties;
    }


    @Override
    public void onUpdateReceived(Update update) {
        WebClient client = WebClient.builder()
                .baseUrl("https://fapi.binance.com/fapi/v1/ticker/24hr")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        if (update.hasMessage() && update.getMessage().hasText()) {
            var messageText = update.getMessage().getText();
            var chatId = update.getMessage().getChatId();
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

            if (messageText.contains("/start")) {
                executor = Executors.newSingleThreadScheduledExecutor();
                var sentCoinsSymbols = new ArrayList<String>();
                String percentText = messageText.replace("/start:", "");

                Runnable periodicTask = () -> sendWithPeriod(client, chatId, percentText, sentCoinsSymbols);
                executor.scheduleAtFixedRate(periodicTask, 0, 10, TimeUnit.SECONDS);
                executor.scheduleAtFixedRate(sentCoinsSymbols::clear, 3, 3, TimeUnit.HOURS);
            } else if (messageText.contains("/stop")){
                executor.shutdown();
            }
        }
    }

    private void sendWithPeriod(WebClient client, Long chatId, String percentText, List<String> sentCoinsSymbols) {

        client.get().retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<CoinInfo>>() {
                })
                .subscribe(coinInfoResponse -> {
                    var messages = new ArrayList<SendMessage>();
                    var sortedCoinInfos = coinInfoResponse.stream()
                            .sorted(Comparator.comparing(coinInfo -> new BigDecimal(coinInfo.getPriceChangePercent())))
                            .filter(coinInfo -> {
                                if (percentText.matches("-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)")) {
                                    return new BigDecimal(coinInfo.getPriceChangePercent()).compareTo(new BigDecimal(percentText)) >= 1;
                                } else {
                                    return new BigDecimal(coinInfo.getPriceChangePercent()).compareTo(new BigDecimal(40)) >= 1;
                                }
                            })
                            .toList();

                    sortedCoinInfos.forEach(coinInfo -> {
                        if (sentCoinsSymbols.contains(coinInfo.getSymbol())) {
                            return;
                        }

                        SendMessage sendMessage = new SendMessage();
                        sendMessage.setChatId(String.valueOf(chatId));
                        try {
                            sendMessage.setText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(coinInfo));
                        } catch (JsonProcessingException e) {
                            e.printStackTrace();
                        }
                        messages.add(sendMessage);
                        sentCoinsSymbols.add(coinInfo.getSymbol());
                    });

                    messages.forEach(message -> {
                        try {
                            execute(message);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    });

                });
    }

    @Override
    public String getBotUsername() {
        return properties.getBotName();
    }
}
