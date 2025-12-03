package com.superodds.infrastructure.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
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
                try {
                    responseBody = EntityUtils.toString(response.getEntity());
                } catch (org.apache.hc.core5.http.ParseException e) {
                    throw new IOException("Failed to parse response", e);
                }
                
                if (statusCode >= 200 && statusCode < 300) {
                    return objectMapper.readTree(responseBody);
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
            params.forEach((key, value) -> 
                urlBuilder.append(key).append("=").append(value).append("&")
            );
            // Remove trailing &
            urlBuilder.setLength(urlBuilder.length() - 1);
        }
        
        return getJson(urlBuilder.toString(), headers);
    }
}
