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
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public YelpService(YelpProperties props, PostgresProperties postgresProps) {
        this.props = props;
        this.postgresProps = postgresProps;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public YelpChatResult callYelpChat(String query, String lineConversationId) {
        List<String> messages = new ArrayList<>();
        String chatId = null;

        if (props.getApiKey() == null || props.getApiKey().isEmpty()) {
            messages.add("Yelp API key is not configured.\n\n" +
                    "Please set YELP_API_KEY in your environment.");
            return new YelpChatResult(messages, chatId);
        }

        try {
            // Check for existing Yelp session
            String existingChatId = getValidYelpSession(lineConversationId);
            
            // Build request body
            ObjectMapper mapper = objectMapper;
            JsonNode root = mapper.createObjectNode();
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("query", query);
            
            // Add chat_id if we have a valid existing session
            if (existingChatId != null && !existingChatId.isEmpty()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("chat_id", existingChatId);
                System.out.println("Continuing Yelp session with chat_id: " + existingChatId);
            } else {
                System.out.println("Starting new Yelp session");
            }

            com.fasterxml.jackson.databind.node.ObjectNode userContext =
                    mapper.createObjectNode();
            if (props.getLocale() != null && !props.getLocale().isEmpty()) {
                userContext.put("locale", props.getLocale());
            }
            if (props.getLatitude() != null && props.getLongitude() != null) {
                userContext.put("latitude", props.getLatitude());
                userContext.put("longitude", props.getLongitude());
            }
            if (userContext.size() > 0) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) root)
                        .set("user_context", userContext);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.setBearerAuth(props.getApiKey());

            HttpEntity<String> entity =
                    new HttpEntity<>(mapper.writeValueAsString(root), headers);

            ResponseEntity<String> response =
                    restTemplate.exchange(YELP_URL, HttpMethod.POST, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                String text = response.getBody() != null ? response.getBody() : "";
                if (text.length() > 2000) {
                    text = text.substring(0, 2000) + "\n...(truncated)...";
                }
                messages.add("Yelp API error " + response.getStatusCodeValue() + ":\n" + text);
                return new YelpChatResult(messages, chatId);
            } else {
                System.out.println("Yelp API call successful.");
                System.out.println(response.getBody());
            }

            // Parse JSON
            String body = response.getBody();
            if (body == null) {
                messages.add("Yelp API returned empty body.");
                return new YelpChatResult(messages, chatId);
            }

            JsonNode data = mapper.readTree(body);
            
            // Extract chat_id from response
            chatId = data.path("chat_id").asText(null);
            if (chatId != null && !chatId.isEmpty()) {
                System.out.println("Received chat_id from Yelp: " + chatId);
                // Update conversation table with new chat_id and timestamp
                updateYelpSession(lineConversationId, chatId);
            }

            // Log full JSON to yelp.log
            String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            FileLogger.appendToFile(YELP_LOG_FILE, pretty);

            // Base natural-language text
            JsonNode respNode = data.path("response").path("text");
            String baseText = respNode.isMissingNode() || respNode.isNull()
                    ? "Yelp returned a response."
                    : respNode.asText();

            messages.add(baseText +
                    "\n\n(Full raw Yelp JSON logged to yelp.log. Showing first business details below.)");

            // Extract first business
            JsonNode entities = data.path("entities");
            JsonNode firstBusiness = null;

            if (entities.isArray()) {
                for (JsonNode entityNode : entities) {
                    JsonNode businesses = entityNode.path("businesses");
                    if (businesses.isArray() && businesses.size() > 0) {
                        firstBusiness = businesses.get(0);
                        break;
                    }
                }
            }

            if (firstBusiness != null) {
                String bizJson = mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(firstBusiness);
                List<String> chunks = chunkText(bizJson, 3500);

                if (!chunks.isEmpty()) {
                    chunks.set(0, "First business (full JSON):\n\n" + chunks.get(0));
                }

                // 1 (baseText) + up to 4 chunks
                for (int i = 0; i < chunks.size() && i < 4; i++) {
                    messages.add(chunks.get(i));
                }
            } else {
                // fallback: truncated JSON
                String shortPretty = pretty;
                if (shortPretty.length() > 3500) {
                    shortPretty = shortPretty.substring(0, 3500) +
                            "\n\n...(truncated JSON preview)...";
                }
                messages.add("No businesses found in entities.\n\n" + shortPretty);
            }

        } catch (Exception e) {
            messages.add("Error calling Yelp API: " + e.getMessage());
            e.printStackTrace();
        }

        return new YelpChatResult(messages, chatId);
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
        private final String chatId;
        
        public YelpChatResult(List<String> messages, String chatId) {
            this.messages = messages;
            this.chatId = chatId;
        }
        
        public List<String> getMessages() {
            return messages;
        }
        
        public String getChatId() {
            return chatId;
        }
    }
}
