package com.ryanhideo.linebot.service;

import com.ryanhideo.linebot.config.PostgresProperties;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Service
public class ConversationAggregatesService {
    
    private final PostgresProperties postgresProps;
    private final Neo4jService neo4jService;
    
    public ConversationAggregatesService(PostgresProperties postgresProps, Neo4jService neo4jService) {
        this.postgresProps = postgresProps;
        this.neo4jService = neo4jService;
    }
    
    private Connection getConnection() throws SQLException {
        String url = String.format("jdbc:postgresql://%s:%d/%s",
                postgresProps.getHost(),
                postgresProps.getPort(),
                postgresProps.getDbName());
        return DriverManager.getConnection(url, postgresProps.getUser(), postgresProps.getPassword());
    }
    
    /**
     * Get all user IDs that are members of a conversation
     */
    private List<String> getConversationMembers(String lineConversationId) {
        List<String> userIds = new ArrayList<>();
        
        try (Connection conn = getConnection()) {
            String sql = "SELECT DISTINCT userid FROM chat_members WHERE lineconversationid = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, lineConversationId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        userIds.add(rs.getString("userid"));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[AGGREGATES] Error getting conversation members: " + e.getMessage());
            e.printStackTrace();
        }
        
        return userIds;
    }
    
    /**
     * Compute and update conversation aggregates from Neo4j data
     * Called after every like/dislike action
     */
    public void updateConversationAggregates(String lineConversationId) {
        System.out.println("[AGGREGATES] Updating aggregates for conversation: " + lineConversationId);
        
        // Get all members in this conversation
        List<String> userIds = getConversationMembers(lineConversationId);
        
        if (userIds.isEmpty()) {
            System.out.println("[AGGREGATES] No members found for conversation");
            return;
        }
        
        System.out.println("[AGGREGATES] Found " + userIds.size() + " members");
        
        // Query Neo4j for aggregated preferences
        List<String> topCuisines = neo4jService.getTopCuisinesForUsers(userIds, 5);
        List<String> strongAvoids = neo4jService.getStrongAvoidsForUsers(userIds, 5);
        Integer avgPrice = neo4jService.getAveragePriceForUsers(userIds);
        
        System.out.println("[AGGREGATES] Top cuisines: " + topCuisines);
        System.out.println("[AGGREGATES] Strong avoids: " + strongAvoids);
        System.out.println("[AGGREGATES] Avg price: " + avgPrice);
        
        // Update Postgres cache
        try (Connection conn = getConnection()) {
            String sql = """
                INSERT INTO conversations (lineConversationId, top_cuisines, strong_avoids, avg_price)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (lineConversationId) 
                DO UPDATE SET 
                    top_cuisines = EXCLUDED.top_cuisines,
                    strong_avoids = EXCLUDED.strong_avoids,
                    avg_price = EXCLUDED.avg_price
                """;
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, lineConversationId);
                
                // Convert lists to SQL arrays
                Array topCuisinesArray = conn.createArrayOf("text", topCuisines.toArray());
                Array strongAvoidsArray = conn.createArrayOf("text", strongAvoids.toArray());
                
                pstmt.setArray(2, topCuisinesArray);
                pstmt.setArray(3, strongAvoidsArray);
                
                if (avgPrice != null) {
                    pstmt.setInt(4, avgPrice);
                } else {
                    pstmt.setNull(4, Types.INTEGER);
                }
                
                pstmt.executeUpdate();
                System.out.println("[AGGREGATES] Successfully updated cache");
            }
        } catch (Exception e) {
            System.err.println("[AGGREGATES] Error updating cache: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Get cached aggregates for a conversation (used by MCP)
     */
    public ConversationAggregates getConversationAggregates(String lineConversationId) {
        try (Connection conn = getConnection()) {
            String sql = "SELECT top_cuisines, strong_avoids, avg_price FROM conversations WHERE lineConversationId = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, lineConversationId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        Array topCuisinesArray = rs.getArray("top_cuisines");
                        Array strongAvoidsArray = rs.getArray("strong_avoids");
                        Integer avgPrice = rs.getObject("avg_price", Integer.class);
                        
                        List<String> topCuisines = new ArrayList<>();
                        List<String> strongAvoids = new ArrayList<>();
                        
                        if (topCuisinesArray != null) {
                            String[] cuisines = (String[]) topCuisinesArray.getArray();
                            topCuisines = List.of(cuisines);
                        }
                        
                        if (strongAvoidsArray != null) {
                            String[] avoids = (String[]) strongAvoidsArray.getArray();
                            strongAvoids = List.of(avoids);
                        }
                        
                        return new ConversationAggregates(topCuisines, strongAvoids, avgPrice);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[AGGREGATES] Error getting aggregates: " + e.getMessage());
            e.printStackTrace();
        }
        
        return new ConversationAggregates(new ArrayList<>(), new ArrayList<>(), null);
    }
    
    /**
     * Data class for conversation aggregates
     */
    public static class ConversationAggregates {
        private final List<String> topCuisines;
        private final List<String> strongAvoids;
        private final Integer avgPrice;
        
        public ConversationAggregates(List<String> topCuisines, List<String> strongAvoids, Integer avgPrice) {
            this.topCuisines = topCuisines;
            this.strongAvoids = strongAvoids;
            this.avgPrice = avgPrice;
        }
        
        public List<String> getTopCuisines() {
            return topCuisines;
        }
        
        public List<String> getStrongAvoids() {
            return strongAvoids;
        }
        
        public Integer getAvgPrice() {
            return avgPrice;
        }
        
        public String toContextString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Group Preferences:\n");
            
            if (!topCuisines.isEmpty()) {
                sb.append("- Popular cuisines: ").append(String.join(", ", topCuisines)).append("\n");
            }
            
            if (!strongAvoids.isEmpty()) {
                sb.append("- Avoid: ").append(String.join(", ", strongAvoids)).append("\n");
            }
            
            if (avgPrice != null) {
                sb.append("- Typical price range: ").append("$".repeat(avgPrice));
            }
            
            return sb.toString();
        }
    }
}
