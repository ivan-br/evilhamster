package com.hamstergroup.evilhamster.dto;

import lombok.Data;

@Data
public class CoinInfo {
    private String symbol;
    private String priceChange;
    private String priceChangePercent;
    private String lastPrice;
    private String highPrice;
    private String lowPrice;
    private String volume;
    private Long count;
}
