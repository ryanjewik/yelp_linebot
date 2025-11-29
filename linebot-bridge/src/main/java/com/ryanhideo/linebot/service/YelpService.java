package com.ryanhideo.linebot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ryanhideo.linebot.config.YelpProperties;
import com.ryanhideo.linebot.util.FileLogger;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Service
public class YelpService {

    private static final String YELP_URL = "https://api.yelp.com/ai/chat/v2";
    private static final String YELP_LOG_FILE = "yelp.log";

    private final YelpProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public YelpService(YelpProperties props) {
        this.props = props;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public List<String> callYelpChat(String query) {
        List<String> messages = new ArrayList<>();

        if (props.getApiKey() == null || props.getApiKey().isEmpty()) {
            messages.add("Yelp API key is not configured.\n\n" +
                    "Please set YELP_API_KEY in your environment.");
            return messages;
        }

        try {
            // Build request body
            ObjectMapper mapper = objectMapper;
            JsonNode root = mapper.createObjectNode();
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("query", query);

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
                return messages;
            } else {
                System.out.println("Yelp API call successful.");
                System.out.println(response.getBody());
            }

            // Parse JSON
            String body = response.getBody();
            if (body == null) {
                messages.add("Yelp API returned empty body.");
                return messages;
            }

            JsonNode data = mapper.readTree(body);

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
        }

        return messages;
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
}
