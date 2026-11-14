package com.powerbill;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

/** Owns the MongoDB client and creates collection indexes on startup. */
public final class MongoStore implements AutoCloseable {
    private final MongoClient client;
    private final MongoDatabase database;
    public MongoStore(String uri, String databaseName) {
        client = MongoClients.create(uri); database = client.getDatabase(databaseName);
        try {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            ensureIndexes();
        } catch (RuntimeException failure) {
            client.close();
            throw failure;
        }
    }
    public MongoCollection<Document> collection(String name) { return database.getCollection(name); }
    public String databaseName() { return database.getName(); }
    private void ensureIndexes() {
        collection("consumers").createIndex(Indexes.ascending("consumerId"), new IndexOptions().unique(true));
        collection("meter_readings").createIndex(Indexes.ascending("consumerId", "period"), new IndexOptions().unique(true));
        collection("bills").createIndex(Indexes.ascending("consumerId", "period"), new IndexOptions().unique(true));
        collection("payments").createIndex(Indexes.ascending("paymentId"), new IndexOptions().unique(true));
        collection("payments").createIndex(Indexes.ascending("receiptId"), new IndexOptions().unique(true).sparse(true));
        collection("payments").createIndex(Indexes.ascending("paymentReference"), new IndexOptions().unique(true).sparse(true));
        collection("electricity_providers").createIndex(Indexes.ascending("providerId"), new IndexOptions().unique(true));
        collection("tariffs").createIndex(Indexes.ascending("tariffId"), new IndexOptions().unique(true));
        collection("subsidies").createIndex(Indexes.ascending("schemeId"), new IndexOptions().unique(true));
        collection("consumption_history").createIndex(Indexes.ascending("consumerId", "period"), new IndexOptions().unique(true));
        collection("bill_corrections").createIndex(Indexes.ascending("correctionId"), new IndexOptions().unique(true));
        collection("bill_corrections").createIndex(Indexes.ascending("billId", "versionBefore"), new IndexOptions().unique(true));
        collection("meter_reading_corrections").createIndex(Indexes.ascending("correctionId"), new IndexOptions().unique(true));
        collection("tariff_retirements").createIndex(Indexes.ascending("retirementId"), new IndexOptions().unique(true));
        collection("tariffs").createIndex(Indexes.ascending("provider", "category", "effectiveFrom"));
        for (String name : new String[]{"electricity_providers", "subsidies", "subsidy_history", "consumption_history", "bill_corrections", "meter_reading_corrections", "tariff_retirements", "audit_logs"})
            collection(name).createIndex(Indexes.ascending("createdAt"));
    }
    @Override public void close() { client.close(); }
    public static String connectionMessage(Throwable error) {
        if (error instanceof MongoException) return "MongoDB is unavailable or rejected the request: " + error.getMessage();
        return "Database error: " + error.getMessage();
    }
}
