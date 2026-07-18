package com.hamstergroup.evilhamster.service;

import com.hamstergroup.evilhamster.config.HamsterConfigProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.concurrent.CompletableFuture;

@Component
public class BotInitializer {

    @Autowired
    private EvilHamsterBot evilHamsterBot;

    @Autowired
    private HamsterConfigProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!StringUtils.hasText(properties.getBotName()) || !StringUtils.hasText(properties.getBotToken())) {
            System.out.println("Telegram bot is disabled because BOT_NAME or BOT_TOKEN is missing.");
            return;
        }

        CompletableFuture.runAsync(this::registerBot);
    }

    private void registerBot() {
        try {
            TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
            telegramBotsApi.registerBot(evilHamsterBot);
            System.out.println("Telegram bot started: " + properties.getBotName());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

}
