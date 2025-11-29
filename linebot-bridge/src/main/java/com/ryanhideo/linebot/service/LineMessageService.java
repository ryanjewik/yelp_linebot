package com.ryanhideo.linebot.service;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class LineMessageService {

    private final YelpService yelpService;
    private final MessageInsertService messageInsertService;
    Boolean yelpCall = false;

    public LineMessageService(YelpService yelpService, MessageInsertService messageInsertService) {
        this.yelpService = yelpService;
        this.messageInsertService = messageInsertService;
    }

    public List<String> handleTextMessage(String rawText, String messageId, String conversationId, String userId, String msgType, String replyId) throws Exception {
        List<String> replies = new ArrayList<>();
        System.out.println("Received text message: " + rawText);
        String trimmed = rawText.trim();
        String lower = trimmed.toLowerCase();
        
        // /yelp command
        if (trimmed.startsWith("/yelp")) {
            yelpCall = true;
            String prompt = trimmed.substring("/yelp".length()).trim();
            if (prompt.isEmpty()) {
                replies.add("Usage: /yelp <your question>\nExample: /yelp Best ramen near me");
            } else {
                replies.addAll(yelpService.callYelpChat(prompt));
            }
            // Insert message into database before returning
            messageInsertService.insertMessage(rawText, yelpCall, messageId, conversationId, userId, msgType, replyId);
            // cap at 5 to match LINE behavior
            return replies.size() > 5 ? replies.subList(0, 5) : replies;
        }
    
        // help / ping / echo
        if (lower.equals("/help")) {
            replies.add(
                    "Commands:\n" +
                    "- /help: show this help\n" +
                    "- /ping: test latency\n" +
                    "- /echo <text>: I'll repeat your text\n" +
                    "- /yelp <query>: ask Yelp AI (e.g. /yelp good vegan sushi in SF)"
            );
            // Insert message into database before returning
            messageInsertService.insertMessage(rawText, yelpCall, messageId, conversationId, userId, msgType, replyId);
            return replies;
        } else if (lower.equals("/ping")) {
            replies.add("pong 🏓");
            // Insert message into database before returning
            messageInsertService.insertMessage(rawText, yelpCall, messageId, conversationId, userId, msgType, replyId);
            return replies;
        } else if (lower.startsWith("/echo ")) {
            replies.add(trimmed.substring(6).trim());
            // Insert message into database before returning
            messageInsertService.insertMessage(rawText, yelpCall, messageId, conversationId, userId, msgType, replyId);
            return replies;
        }

        // Insert message into database
        messageInsertService.insertMessage(rawText, yelpCall, messageId, conversationId, userId, msgType, replyId);

        return replies;
    }
}
