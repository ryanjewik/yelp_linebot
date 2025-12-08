package com.ryanhideo.linebot.service;

import com.ryanhideo.linebot.config.Neo4jProperties;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Result;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.*;

@Service
public class Neo4jService {
    
    private final Driver driver;
    private final String database;
    
    public Neo4jService(Neo4jProperties neo4jProperties) {
        this.driver = GraphDatabase.driver(
            neo4jProperties.getUri(),
            AuthTokens.basic(neo4jProperties.getUsername(), neo4jProperties.getPassword())
        );
        this.database = neo4jProperties.getDatabase();
        System.out.println("[NEO4J] Connected to: " + neo4jProperties.getUri());
    }
    
    @PreDestroy
    public void close() {
        if (driver != null) {
            driver.close();
            System.out.println("[NEO4J] Driver closed");
        }
    }
    
    /**
     * Record a LIKE relationship between user and restaurant
     */
    public void recordLike(String userId, String restaurantId, String restaurantName, String cuisine, String priceLevel) {
        String query = """
            MERGE (u:User {userId: $userId})
            MERGE (r:Restaurant {restaurantId: $restaurantId})
            ON CREATE SET r.name = $name, r.cuisine = $cuisine, r.priceLevel = $priceLevel
            WITH u, r
            OPTIONAL MATCH (u)-[dislike:DISLIKES]->(r)
            DELETE dislike
            WITH u, r
            MERGE (u)-[rel:LIKES]->(r)
            ON CREATE SET rel.timestamp = timestamp()
            ON MATCH SET rel.timestamp = timestamp()
            RETURN rel
            """;
        
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            session.executeWrite(tx -> {
                Map<String, Object> params = Map.of(
                    "userId", userId,
                    "restaurantId", restaurantId,
                    "name", restaurantName,
                    "cuisine", cuisine != null ? cuisine : "Unknown",
                    "priceLevel", priceLevel != null ? priceLevel : ""
                );
                return tx.run(query, params).consume();
            });
            System.out.println("[NEO4J] Recorded LIKE: " + userId + " -> " + restaurantName);
        } catch (Exception e) {
            System.err.println("[NEO4J] Error recording like: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Record a DISLIKE relationship between user and restaurant
     */
    public void recordDislike(String userId, String restaurantId, String restaurantName, String cuisine, String priceLevel) {
        String query = """
            MERGE (u:User {userId: $userId})
            MERGE (r:Restaurant {restaurantId: $restaurantId})
            ON CREATE SET r.name = $name, r.cuisine = $cuisine, r.priceLevel = $priceLevel
            WITH u, r
            OPTIONAL MATCH (u)-[like:LIKES]->(r)
            DELETE like
            WITH u, r
            MERGE (u)-[rel:DISLIKES]->(r)
            ON CREATE SET rel.timestamp = timestamp()
            ON MATCH SET rel.timestamp = timestamp()
            RETURN rel
            """;
        
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            session.executeWrite(tx -> {
                Map<String, Object> params = Map.of(
                    "userId", userId,
                    "restaurantId", restaurantId,
                    "name", restaurantName,
                    "cuisine", cuisine != null ? cuisine : "Unknown",
                    "priceLevel", priceLevel != null ? priceLevel : ""
                );
                return tx.run(query, params).consume();
            });
            System.out.println("[NEO4J] Recorded DISLIKE: " + userId + " -> " + restaurantName);
        } catch (Exception e) {
            System.err.println("[NEO4J] Error recording dislike: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Get like/dislike ratio for a restaurant
     */
    public Map<String, Integer> getRestaurantRatio(String restaurantId) {
        String query = """
            MATCH (r:Restaurant {restaurantId: $restaurantId})
            OPTIONAL MATCH (u1:User)-[:LIKES]->(r)
            WITH r, count(u1) as likes
            OPTIONAL MATCH (u2:User)-[:DISLIKES]->(r)
            RETURN likes, count(u2) as dislikes
            """;
        
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            return session.executeRead(tx -> {
                Result result = tx.run(query, Map.of("restaurantId", restaurantId));
                if (result.hasNext()) {
                    org.neo4j.driver.Record record = result.next();
                    int likes = record.get("likes").asInt(0);
                    int dislikes = record.get("dislikes").asInt(0);
                    return Map.of("likes", likes, "dislikes", dislikes);
                }
                return Map.of("likes", 0, "dislikes", 0);
            });
        } catch (Exception e) {
            System.err.println("[NEO4J] Error getting ratio: " + e.getMessage());
            e.printStackTrace();
            return Map.of("likes", 0, "dislikes", 0);
        }
    }
    
    /**
     * Get top cuisines for users in a conversation (for aggregation)
     */
    public List<String> getTopCuisinesForUsers(List<String> userIds, int limit) {
        String query = """
            MATCH (u:User)-[:LIKES]->(r:Restaurant)
            WHERE u.userId IN $userIds AND r.cuisine IS NOT NULL
            RETURN r.cuisine as cuisine, count(*) as count
            ORDER BY count DESC
            LIMIT $limit
            """;
        
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            return session.executeRead(tx -> {
                Result result = tx.run(query, Map.of("userIds", userIds, "limit", limit));
                List<String> cuisines = new ArrayList<>();
                while (result.hasNext()) {
                    org.neo4j.driver.Record record = result.next();
                    cuisines.add(record.get("cuisine").asString());
                }
                return cuisines;
            });
        } catch (Exception e) {
            System.err.println("[NEO4J] Error getting top cuisines: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
    
    /**
     * Get strong avoids (dislikes) for users in a conversation
     */
    public List<String> getStrongAvoidsForUsers(List<String> userIds, int limit) {
        String query = """
            MATCH (u:User)-[:DISLIKES]->(r:Restaurant)
            WHERE u.userId IN $userIds AND r.cuisine IS NOT NULL
            RETURN r.cuisine as cuisine, count(*) as count
            ORDER BY count DESC
            LIMIT $limit
            """;
        
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            return session.executeRead(tx -> {
                Result result = tx.run(query, Map.of("userIds", userIds, "limit", limit));
                List<String> avoids = new ArrayList<>();
                while (result.hasNext()) {
                    org.neo4j.driver.Record record = result.next();
                    avoids.add(record.get("cuisine").asString());
                }
                return avoids;
            });
        } catch (Exception e) {
            System.err.println("[NEO4J] Error getting strong avoids: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
    
    /**
     * Get average price preference for users (based on liked restaurants)
     */
    public Integer getAveragePriceForUsers(List<String> userIds) {
        String query = """
            MATCH (u:User)-[:LIKES]->(r:Restaurant)
            WHERE u.userId IN $userIds AND r.priceLevel IS NOT NULL AND r.priceLevel <> ''
            RETURN toFloat(avg(size(r.priceLevel))) as avgPrice
            """;
        
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            return session.executeRead(tx -> {
                Result result = tx.run(query, Map.of("userIds", userIds));
                if (result.hasNext()) {
                    org.neo4j.driver.Record record = result.next();
                    Value avgValue = record.get("avgPrice");
                    if (!avgValue.isNull()) {
                        return (int) Math.round(avgValue.asDouble());
                    }
                }
                return null;
            });
        } catch (Exception e) {
            System.err.println("[NEO4J] Error getting average price: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}
