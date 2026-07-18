package com.hamstergroup.evilhamster.service;

import com.hamstergroup.evilhamster.config.HamsterConfigProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Component
public class BotInitializer {

    @Autowired
    private EvilHamsterBot evilHamsterBot;

    @Autowired
    private HamsterConfigProperties properties;

    @EventListener({ContextRefreshedEvent.class})
    public void init() throws TelegramApiException {
        if (!StringUtils.hasText(properties.getBotName()) || !StringUtils.hasText(properties.getBotToken())) {
            System.out.println("Telegram bot is disabled because BOT_NAME or BOT_TOKEN is missing.");
            return;
        }

        TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);

        try {
            telegramBotsApi.registerBot(evilHamsterBot);
        } catch (TelegramApiException e){
            e.printStackTrace();
        }
    }

}
