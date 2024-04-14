package com.hamstergroup.evilhamster.dto;

import lombok.Data;

@Data
public class CoinInfo {
    private String symbol;
    private String priceChange;
    private String priceChangePercent;
    private String weightedAvgPrice;
    private String lastPrice;
    private String lastQty;
    private String openPrice;
    private String highPrice;
    private String lowPrice;
    private String volume;
    private String quoteVolume;
    private Long openTime;
    private Long closeTime;
    private Long firstId;
    private Long lastId;
    private Long count;
}
