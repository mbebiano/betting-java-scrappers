package com.superodds.infrastructure.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * MongoDB configuration.
 */
@Configuration
public class MongoConfig {

    @Value("${mongodb.uri:mongodb://flashscore:flashscore@31.220.90.232:27017/?authSource=admin&connectTimeoutMS=5000&serverSelectionTimeoutMS=5000}")
    private String mongoUri;

    @Value("${mongodb.database:flashscore}")
    private String database;

    @Bean
    public MongoClient mongoClient() {
        ConnectionString connectionString = new ConnectionString(mongoUri);
        MongoClientSettings settings = MongoClientSettings.builder()
            .applyConnectionString(connectionString)
            .build();
        return MongoClients.create(settings);
    }

    @Bean
    public MongoTemplate mongoTemplate(MongoClient mongoClient) {
        return new MongoTemplate(mongoClient, database);
    }
    
    @Bean
    public String mongoCollectionName(@Value("${mongodb.collection:betsv2}") String collection) {
        return collection;
    }
}
