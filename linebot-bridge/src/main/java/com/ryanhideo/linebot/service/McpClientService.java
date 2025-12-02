package com.ryanhideo.linebot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

@Service
public class McpClientService {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Process mcpProcess;
    private BufferedWriter mcpWriter;
    private BufferedReader mcpReader;
    private int requestId = 0;

    public void connect(String mcpCommand) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(mcpCommand.split(" "));
        pb.redirectErrorStream(false);
        mcpProcess = pb.start();
        
        mcpWriter = new BufferedWriter(new OutputStreamWriter(mcpProcess.getOutputStream()));
        mcpReader = new BufferedReader(new InputStreamReader(mcpProcess.getInputStream()));
        
        System.out.println("[MCP] Connected to MCP server");
    }

    public void disconnect() {
        try {
            if (mcpWriter != null) mcpWriter.close();
            if (mcpReader != null) mcpReader.close();
            if (mcpProcess != null) mcpProcess.destroy();
            System.out.println("[MCP] Disconnected from MCP server");
        } catch (Exception e) {
            System.err.println("[MCP] Error disconnecting: " + e.getMessage());
        }
    }

    public JsonNode callTool(String toolName, Map<String, Object> arguments) throws IOException {
        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", ++requestId);
        request.put("method", "tools/call");
        
        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments);
        request.put("params", params);
        
        String requestJson = objectMapper.writeValueAsString(request);
        System.out.println("[MCP] Sending request: " + requestJson);
        
        mcpWriter.write(requestJson);
        mcpWriter.newLine();
        mcpWriter.flush();
        
        String response = mcpReader.readLine();
        System.out.println("[MCP] Received response: " + response);
        
        JsonNode responseNode = objectMapper.readTree(response);
        return responseNode.get("result");
    }

    public JsonNode listTools() throws IOException {
        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", ++requestId);
        request.put("method", "tools/list");
        
        String requestJson = objectMapper.writeValueAsString(request);
        System.out.println("[MCP] Sending list tools request: " + requestJson);
        
        mcpWriter.write(requestJson);
        mcpWriter.newLine();
        mcpWriter.flush();
        
        String response = mcpReader.readLine();
        System.out.println("[MCP] Received tools list: " + response);
        
        JsonNode responseNode = objectMapper.readTree(response);
        return responseNode.get("result");
    }
}
