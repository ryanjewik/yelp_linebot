package com.ryanhideo.linebot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "line")
public class LineProperties {
    private String channelSecret;
    private String channelAccessToken;

    public String getChannelSecret() {
        return channelSecret;
    }

    public void setChannelSecret(String channelSecret) {
        this.channelSecret = channelSecret;
    }

    public String getChannelAccessToken() {
        return channelAccessToken;
    }

    public void setChannelAccessToken(String channelAccessToken) {
        this.channelAccessToken = channelAccessToken;
    }
}