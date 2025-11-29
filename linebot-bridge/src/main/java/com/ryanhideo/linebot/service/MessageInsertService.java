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
                                  String conversationId, String userId, String msgType, String replyId) throws Exception {
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
            if (!conversationExists(conn, conversationId)) {
                insertConversation(conn, conversationId, now);
            }
            
            // 3. Check if user-conversation pair exists in chat_members, if not insert
            if (!chatMemberExists(conn, conversationId, userId)) {
                insertChatMember(conn, conversationId, userId);
            }
            
            // 4. Finally, insert the message
            insertMessageRecord(conn, messageId, msgType, conversationId, userId, 
                              messageContent, now, yelpCall, replyId);
            
            return true;
        }
    }

    public boolean insertYelpMessage(String messageContent, String messageId, 
                                  String conversationId, String userId, String msgType, String replyId) throws Exception {
        return insertMessage(messageContent, true, messageId, conversationId, userId, msgType, replyId);
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
    
    private boolean conversationExists(Connection conn, String conversationId) throws Exception {
        String sql = "SELECT 1 FROM conversations WHERE conversationId = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, conversationId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }
    
    private void insertConversation(Connection conn, String conversationId, Timestamp now) throws Exception {
        String sql = "INSERT INTO conversations (conversationId, conversationCreated) VALUES (?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, conversationId);
            pstmt.setTimestamp(2, now);
            pstmt.executeUpdate();
        }
    }
    
    private boolean chatMemberExists(Connection conn, String conversationId, String userId) throws Exception {
        String sql = "SELECT 1 FROM chat_members WHERE conversationId = ? AND userId = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, conversationId);
            pstmt.setString(2, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }
    
    private void insertChatMember(Connection conn, String conversationId, String userId) throws Exception {
        String sql = "INSERT INTO chat_members (conversationId, userId) VALUES (?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, conversationId);
            pstmt.setString(2, userId);
            pstmt.executeUpdate();
        }
    }
    
    private void insertMessageRecord(Connection conn, String messageId, String messageType, 
                                     String conversationId, String userId, String messageContent,
                                     Timestamp messageDate, Boolean yelpCall, String replyId) throws Exception {
        String sql;
        if (replyId == null || replyId.isEmpty()) {
            sql = "INSERT INTO messages (messageId, messageType, conversationId, userId, " +
                  "messageContent, messageDate, yelpCall) VALUES (?, ?, ?, ?, ?, ?, ?)";
        } else {
            sql = "INSERT INTO messages (messageId, messageType, conversationId, userId, " +
                  "messageContent, messageDate, yelpCall, repliedMessageId) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        }
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, messageId);
            pstmt.setString(2, messageType);
            pstmt.setString(3, conversationId);
            pstmt.setString(4, userId);
            pstmt.setString(5, messageContent);
            pstmt.setTimestamp(6, messageDate);
            pstmt.setBoolean(7, yelpCall);
            if (replyId != null && !replyId.isEmpty()) {
                pstmt.setString(8, replyId);
            }
            pstmt.executeUpdate();
        }
    }
}