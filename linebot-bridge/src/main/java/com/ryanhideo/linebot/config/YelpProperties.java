package com.ryanhideo.linebot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "yelp")
public class YelpProperties {
    private String apiKey;
    private String locale;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
}
