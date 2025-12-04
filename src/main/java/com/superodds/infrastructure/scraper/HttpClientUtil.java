package com.superodds.infrastructure.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * Utility for making HTTP requests to scraper endpoints.
 */
public class HttpClientUtil {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientUtil.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_LOG_BODY_LENGTH = 500;

    /**
     * Helper method to log response body preview for debugging.
     */
    private static void logResponseBodyPreview(String responseBody) {
        String preview = responseBody.length() > MAX_LOG_BODY_LENGTH 
            ? responseBody.substring(0, MAX_LOG_BODY_LENGTH) + "..." 
            : responseBody;
        logger.error("Response body preview: {}", preview);
    }

    /**
     * Makes a GET request and returns the response as JsonNode.
     */
    public static JsonNode getJson(String url, Map<String, String> headers) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            
            // Add headers
            if (headers != null) {
                headers.forEach(request::addHeader);
            }
            
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getCode();
                String responseBody;
                String contentType = null;
                
                // Get content type from entity before consuming it
                HttpEntity entity = response.getEntity();
                if (entity != null && entity.getContentType() != null) {
                    contentType = entity.getContentType();
                }
                
                try {
                    responseBody = EntityUtils.toString(entity);
                } catch (org.apache.hc.core5.http.ParseException e) {
                    throw new IOException("Failed to parse response", e);
                }
                
                if (statusCode >= 200 && statusCode < 300) {
                    // Check if response is JSON before attempting to parse
                    // If content-type is null or empty, we'll still try to parse as JSON
                    if (contentType != null && !contentType.isEmpty() 
                        && !contentType.toLowerCase().startsWith("application/json")) {
                        logger.error("Expected JSON but received content-type: {}. URL: {}", contentType, url);
                        logResponseBodyPreview(responseBody);
                        throw new IOException("Expected JSON response but received: " + contentType);
                    }
                    
                    try {
                        return objectMapper.readTree(responseBody);
                    } catch (com.fasterxml.jackson.core.JsonParseException e) {
                        logger.error("Failed to parse JSON. URL: {}", url);
                        logResponseBodyPreview(responseBody);
                        throw new IOException("Failed to parse JSON response: " + e.getMessage(), e);
                    }
                } else {
                    logger.error("HTTP request failed with status {}: {}", statusCode, responseBody);
                    throw new IOException("HTTP request failed with status " + statusCode);
                }
            }
        }
    }

    /**
     * Makes a GET request with query parameters and returns the response as JsonNode.
     */
    public static JsonNode getJson(String baseUrl, Map<String, String> params, Map<String, String> headers) throws IOException {
        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        
        if (params != null && !params.isEmpty()) {
            urlBuilder.append("?");
            for (Map.Entry<String, String> entry : params.entrySet()) {
                try {
                    String encodedKey = java.net.URLEncoder.encode(entry.getKey(), "UTF-8");
                    String encodedValue = java.net.URLEncoder.encode(entry.getValue(), "UTF-8");
                    urlBuilder.append(encodedKey).append("=").append(encodedValue).append("&");
                } catch (java.io.UnsupportedEncodingException e) {
                    throw new IOException("Failed to encode URL parameters", e);
                }
            }
            // Remove trailing &
            urlBuilder.setLength(urlBuilder.length() - 1);
        }
        
        return getJson(urlBuilder.toString(), headers);
    }
}
