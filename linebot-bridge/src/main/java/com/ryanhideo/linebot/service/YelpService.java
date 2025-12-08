package com.ryanhideo.linebot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ryanhideo.linebot.config.YelpProperties;
import com.ryanhideo.linebot.config.PostgresProperties;
import com.ryanhideo.linebot.model.RestaurantData;
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

    public YelpChatResult callYelpChat(String query, String lineConversationId, String userId) {
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
            
            // Get chat history if we have an existing session
            String chatHistory = "";
            if (existingYelpConvId != null && !existingYelpConvId.isEmpty()) {
                chatHistory = getChatHistory(existingYelpConvId);
            }
            
            // Call OpenAI with yelp_agent tool, passing the yelpConversationId, chat history, and LINE conversation ID
            OpenAIService.YelpResult result = openAIService.callOpenAIWithYelpTool(query, existingYelpConvId, chatHistory, lineConversationId);
            messages = result.getMessages();
            List<List<String>> photos = result.getPhotos();
            List<RestaurantData> restaurants = result.getRestaurants();
            yelpConversationId = result.getYelpConversationId();
            
            // Update conversation table with new/updated yelpConversationId
            if (yelpConversationId != null && !yelpConversationId.isEmpty()) {
                System.out.println("Received yelpConversationId: " + yelpConversationId);
                updateYelpSession(lineConversationId, yelpConversationId);
            }
            
            return new YelpChatResult(messages, photos, yelpConversationId, restaurants);

        } catch (Exception e) {
            messages.add("Error calling OpenAI with Yelp tool: " + e.getMessage());
            e.printStackTrace();
            List<List<String>> emptyPhotos = new ArrayList<>();
            emptyPhotos.add(new ArrayList<>());
            return new YelpChatResult(messages, emptyPhotos, yelpConversationId, new ArrayList<>());
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
    
    private String getChatHistory(String yelpConversationId) {
        StringBuilder history = new StringBuilder();
        try (Connection conn = getConnection()) {
            // Only get user messages and assistant header responses (skip detailed business cards)
            String sql = "SELECT messageid, messagetype, messagecontent, messagedate FROM messages " +
                        "WHERE yelpconversationid = ? " +
                        "ORDER BY messagedate DESC LIMIT 15";  // Reduced to last 15 messages
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, yelpConversationId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    List<String> messages = new ArrayList<>();
                    while (rs.next()) {
                        String messageId = rs.getString("messageid");
                        String content = rs.getString("messagecontent");
                        
                        // User messages have numeric messageIds
                        boolean isUser = !messageId.startsWith("push-");
                        
                        if (isUser) {
                            // Skip status messages (🔍, ⏳, etc.)
                            if (!content.startsWith("🔍") && !content.startsWith("⏳") && !content.startsWith("✅")) {
                                messages.add("User: " + content);
                            }
                        } else {
                            // For assistant messages, only include if it's a summary (doesn't start with emoji)
                            // Skip individual business card messages (they start with 📍)
                            if (!content.startsWith("📍") && !content.startsWith("🔍")) {
                                // Truncate long assistant responses to first 200 chars
                                String truncated = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                                messages.add("Assistant: " + truncated);
                            }
                        }
                    }
                    
                    // Reverse to get chronological order (oldest first)
                    for (int i = messages.size() - 1; i >= 0; i--) {
                        history.append(messages.get(i)).append("\n");
                    }
                    
                    if (history.length() > 0) {
                        System.out.println("Retrieved chat history with " + messages.size() + " relevant messages");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error retrieving chat history: " + e.getMessage());
            e.printStackTrace();
        }
        return history.toString().trim();
    }
    
    public static class YelpChatResult {
        private final List<String> messages;
        private final List<List<String>> photos;
        private final String chatId;
        private final List<RestaurantData> restaurants;
        
        public YelpChatResult(List<String> messages, List<List<String>> photos, String chatId, List<RestaurantData> restaurants) {
            this.messages = messages;
            this.photos = photos;
            this.chatId = chatId;
            this.restaurants = restaurants;
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
        
        public List<RestaurantData> getRestaurants() {
            return restaurants;
        }
    }
}
