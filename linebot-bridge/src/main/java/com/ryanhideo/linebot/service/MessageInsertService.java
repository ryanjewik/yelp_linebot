package com.ryanhideo.linebot.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import org.springframework.stereotype.Service;
import com.ryanhideo.linebot.config.PostgresProperties;

@Service
public class MessageInsertService {
    private final PostgresProperties props;

    public MessageInsertService(PostgresProperties props) {
        this.props = props;
    }

    public boolean insertMessage(String messageContent, Boolean yelpCall, String messageId, 
                                  String lineConversationId, String userId, String msgType, String replyId, String yelpConversationId) throws Exception {
        String host = props.getHost();
        int port = props.getPort();
        String dbName = props.getDbName();
        String user = props.getUser();
        String password = props.getPassword();
        
        String url = String.format("jdbc:postgresql://%s:%d/%s", host, port, dbName);
        
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            
            // 1. Check if user exists, if not insert
            if (!userExists(conn, userId)) {
                insertUser(conn, userId);
            }
            
            // 2. Check if conversation exists, if not insert
            if (!conversationExists(conn, lineConversationId)) {
                insertConversation(conn, lineConversationId, now);
            }
            
            // 3. Check if user-conversation pair exists in chat_members, if not insert
            if (!chatMemberExists(conn, lineConversationId, userId)) {
                insertChatMember(conn, lineConversationId, userId);
            }
            
            // 4. Get valid yelpConversationId if not already provided
            if (yelpConversationId == null || yelpConversationId.isEmpty()) {
                yelpConversationId = getValidYelpConversationId(conn, lineConversationId);
            }
            
            // 5. Finally, insert the message
            insertMessageRecord(conn, messageId, msgType, lineConversationId, userId, 
                              messageContent, now, yelpCall, replyId, yelpConversationId);
            
            return true;
        }
    }

    public boolean insertYelpMessage(String messageContent, String messageId, 
                                  String lineConversationId, String userId, String msgType, String replyId, String yelpConversationId) throws Exception {
        return insertMessage(messageContent, true, messageId, lineConversationId, userId, msgType, replyId, yelpConversationId);
    }
    
    private boolean userExists(Connection conn, String userId) throws Exception {
        String sql = "SELECT 1 FROM users WHERE userId = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }
    
    private void insertUser(Connection conn, String userId) throws Exception {
        String sql = "INSERT INTO users (userId) VALUES (?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            pstmt.executeUpdate();
        }
    }
    
    private boolean conversationExists(Connection conn, String lineConversationId) throws Exception {
        String sql = "SELECT 1 FROM conversations WHERE lineConversationId = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, lineConversationId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }
    
    private void insertConversation(Connection conn, String lineConversationId, Timestamp now) throws Exception {
        String sql = "INSERT INTO conversations (lineConversationId, conversationCreated) VALUES (?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, lineConversationId);
            pstmt.setTimestamp(2, now);
            pstmt.executeUpdate();
        }
    }
    
    private boolean chatMemberExists(Connection conn, String lineConversationId, String userId) throws Exception {
        String sql = "SELECT 1 FROM chat_members WHERE lineConversationId = ? AND userId = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, lineConversationId);
            pstmt.setString(2, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }
    
    private void insertChatMember(Connection conn, String lineConversationId, String userId) throws Exception {
        String sql = "INSERT INTO chat_members (lineConversationId, userId) VALUES (?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, lineConversationId);
            pstmt.setString(2, userId);
            pstmt.executeUpdate();
        }
    }
    
    private void insertMessageRecord(Connection conn, String messageId, String messageType, 
                                     String lineConversationId, String userId, String messageContent,
                                     Timestamp messageDate, Boolean yelpCall, String replyId, String yelpConversationId) throws Exception {
        String sql;
        boolean hasReply = replyId != null && !replyId.isEmpty();
        boolean hasYelpConv = yelpConversationId != null && !yelpConversationId.isEmpty();
        
        // Build SQL based on which optional fields are present
        if (!hasReply && !hasYelpConv) {
            sql = "INSERT INTO messages (messageId, messageType, lineConversationId, userId, " +
                  "messageContent, messageDate, yelpCall) VALUES (?, ?, ?, ?, ?, ?, ?)";
        } else if (hasReply && !hasYelpConv) {
            sql = "INSERT INTO messages (messageId, messageType, lineConversationId, userId, " +
                  "messageContent, messageDate, yelpCall, repliedMessageId) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        } else if (!hasReply && hasYelpConv) {
            sql = "INSERT INTO messages (messageId, messageType, lineConversationId, userId, " +
                  "messageContent, messageDate, yelpCall, yelpConversationId) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        } else {
            sql = "INSERT INTO messages (messageId, messageType, lineConversationId, userId, " +
                  "messageContent, messageDate, yelpCall, repliedMessageId, yelpConversationId) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, messageId);
            pstmt.setString(2, messageType);
            pstmt.setString(3, lineConversationId);
            pstmt.setString(4, userId);
            pstmt.setString(5, messageContent);
            pstmt.setTimestamp(6, messageDate);
            pstmt.setBoolean(7, yelpCall);
            
            int paramIndex = 8;
            if (hasReply) {
                pstmt.setString(paramIndex++, replyId);
            }
            if (hasYelpConv) {
                pstmt.setString(paramIndex, yelpConversationId);
            }
            
            pstmt.executeUpdate();
        }
    }
    
    private String getValidYelpConversationId(Connection conn, String lineConversationId) throws Exception {
        String sql = "SELECT yelpconversationid, lastyelpmessageprompt FROM conversations WHERE lineconversationid = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, lineConversationId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String yelpConvId = rs.getString("yelpconversationid");
                    Timestamp lastPrompt = rs.getTimestamp("lastyelpmessageprompt");
                    
                    // Check if we have a yelpConversationId and it's within 6 hours
                    if (yelpConvId != null && !yelpConvId.isEmpty() && lastPrompt != null) {
                        long timeDiff = System.currentTimeMillis() - lastPrompt.getTime();
                        long sixHoursMillis = 6 * 60 * 60 * 1000;
                        if (timeDiff < sixHoursMillis) {
                            System.out.println("Using valid Yelp conversation ID: " + yelpConvId + " (" + (timeDiff / 1000 / 60) + " minutes old)");
                            return yelpConvId;
                        } else {
                            System.out.println("Yelp conversation ID expired (" + (timeDiff / 1000 / 60 / 60) + " hours old)");
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting valid Yelp conversation ID: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }
}