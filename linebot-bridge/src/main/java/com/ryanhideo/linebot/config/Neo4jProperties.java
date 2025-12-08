package com.ryanhideo.linebot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Neo4jProperties {
    
    @Value("${neo4j.uri}")
    private String uri;
    
    @Value("${neo4j.username}")
    private String username;
    
    @Value("${neo4j.password}")
    private String password;
    
    @Value("${neo4j.database}")
    private String database;
    
    public String getUri() {
        return uri;
    }
    
    public String getUsername() {
        return username;
    }
    
    public String getPassword() {
        return password;
    }
    
    public String getDatabase() {
        return database;
    }
}
