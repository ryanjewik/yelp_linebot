package com.ryanhideo.linebot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ryanhideo.linebot.config.YelpProperties;
import com.ryanhideo.linebot.config.PostgresProperties;
import com.ryanhideo.linebot.util.FileLogger;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Service
public class YelpService {

    private static final String YELP_URL = "https://api.yelp.com/ai/chat/v2";
    private static final String YELP_LOG_FILE = "yelp.log";
    private static final long SIX_HOURS_MILLIS = 6 * 60 * 60 * 1000;

    private final YelpProperties props;
    private final PostgresProperties postgresProps;
    private final OpenAIService openAIService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public YelpService(YelpProperties props, PostgresProperties postgresProps, OpenAIService openAIService) {
        this.props = props;
        this.postgresProps = postgresProps;
        this.openAIService = openAIService;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public YelpChatResult callYelpChat(String query, String lineConversationId) {
        List<String> messages = new ArrayList<>();
        String yelpConversationId = null;

        try {
            // Check for existing Yelp conversation ID
            String existingYelpConvId = getValidYelpSession(lineConversationId);
            
            if (existingYelpConvId != null && !existingYelpConvId.isEmpty()) {
                System.out.println("Continuing Yelp session with ID: " + existingYelpConvId);
            } else {
                System.out.println("Starting new Yelp session");
            }
            
            // Call OpenAI with yelp_agent tool, passing the yelpConversationId
            OpenAIService.YelpResult result = openAIService.callOpenAIWithYelpTool(query, existingYelpConvId);
            messages = result.getMessages();
            List<List<String>> photos = result.getPhotos();
            yelpConversationId = result.getYelpConversationId();
            
            // Update conversation table with new/updated yelpConversationId
            if (yelpConversationId != null && !yelpConversationId.isEmpty()) {
                System.out.println("Received yelpConversationId: " + yelpConversationId);
                updateYelpSession(lineConversationId, yelpConversationId);
            }
            
            return new YelpChatResult(messages, photos, yelpConversationId);

        } catch (Exception e) {
            messages.add("Error calling OpenAI with Yelp tool: " + e.getMessage());
            e.printStackTrace();
            List<List<String>> emptyPhotos = new ArrayList<>();
            emptyPhotos.add(new ArrayList<>());
            return new YelpChatResult(messages, emptyPhotos, yelpConversationId);
        }
    }

    private List<String> chunkText(String text, int maxLen) {
        List<String> chunks = new ArrayList<>();
        String s = text;

        while (!s.isEmpty()) {
            if (s.length() <= maxLen) {
                chunks.add(s);
                break;
            }

            int splitPos = s.lastIndexOf('\n', maxLen);
            if (splitPos == -1) {
                splitPos = maxLen;
            }

            chunks.add(s.substring(0, splitPos));
            s = s.substring(splitPos);
        }

        return chunks;
    }
    
    private Connection getConnection() throws Exception {
        String url = String.format("jdbc:postgresql://%s:%d/%s", 
            postgresProps.getHost(), postgresProps.getPort(), postgresProps.getDbName());
        return DriverManager.getConnection(url, postgresProps.getUser(), postgresProps.getPassword());
    }
    
    private String getValidYelpSession(String lineConversationId) {
        try (Connection conn = getConnection()) {
            String sql = "SELECT yelpConversationId, lastYelpMessagePrompt FROM conversations WHERE lineConversationId = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, lineConversationId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        String yelpConvId = rs.getString("yelpConversationId");
                        Timestamp lastPrompt = rs.getTimestamp("lastYelpMessagePrompt");
                        
                        // Check if we have a yelpConversationId and it's within 6 hours
                        if (yelpConvId != null && !yelpConvId.isEmpty() && lastPrompt != null) {
                            long timeDiff = System.currentTimeMillis() - lastPrompt.getTime();
                            if (timeDiff < SIX_HOURS_MILLIS) {
                                System.out.println("Found valid Yelp session (" + (timeDiff / 1000 / 60) + " minutes old)");
                                return yelpConvId;
                            } else {
                                System.out.println("Yelp session expired (" + (timeDiff / 1000 / 60 / 60) + " hours old)");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error checking Yelp session: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }
    
    private void updateYelpSession(String lineConversationId, String yelpConversationId) {
        try (Connection conn = getConnection()) {
            String sql = "UPDATE conversations SET yelpConversationId = ?, lastYelpMessagePrompt = ? WHERE lineConversationId = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, yelpConversationId);
                pstmt.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
                pstmt.setString(3, lineConversationId);
                int updated = pstmt.executeUpdate();
                System.out.println("Updated Yelp session for conversation: " + lineConversationId + " (rows: " + updated + ")");
            }
        } catch (Exception e) {
            System.err.println("Error updating Yelp session: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public static class YelpChatResult {
        private final List<String> messages;
        private final List<List<String>> photos;
        private final String chatId;
        
        public YelpChatResult(List<String> messages, List<List<String>> photos, String chatId) {
            this.messages = messages;
            this.photos = photos;
            this.chatId = chatId;
        }
        
        public List<String> getMessages() {
            return messages;
        }
        
        public List<List<String>> getPhotos() {
            return photos;
        }
        
        public String getChatId() {
            return chatId;
        }
    }
}
