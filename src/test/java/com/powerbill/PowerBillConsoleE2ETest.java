package com.powerbill;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Console-to-Mongo acceptance flows. Every test uses and drops its own randomly named validation database. */
class PowerBillConsoleE2ETest {
    private final String uri = System.getenv().getOrDefault("POWERBILL_MONGODB_URI", "mongodb://127.0.0.1:27017");
    private String databaseName;
    private MongoStore store;

    @BeforeEach void setUp() {
        databaseName = "powerbill_e2e_" + UUID.randomUUID().toString().replace("-", "");
        store = new MongoStore(uri, databaseName);
        System.setProperty("powerbill.meter.maxReading", "100");
    }

    @AfterEach void tearDown() {
        if (store != null) store.close();
        System.clearProperty("powerbill.meter.maxReading");
        try (MongoClient client = MongoClients.create(uri)) { client.getDatabase(databaseName).drop(); }
    }

    @Test void consumerMeterBillingCorrectionAndHistoryMenusWork() {
        String eofOutput = runLines("");
        assertTrue(eofOutput.contains("13. Database Status"), "The full menu should be shown before checking for input.");
        assertTrue(eofOutput.contains("Choice: End of input. Goodbye."), "EOF after the prompt should exit gracefully.");
        assertTrue(runLines("2\n").contains("End of input. Goodbye."));
        assertTrue(run("1", "1", "Bad Provider", "1 Power Way", "9876543200", "Domestic", "UNKNOWN", "0").contains("Unknown provider"));
        String registration = run("1", "1", "Ada Meter", "1 Power Way", "9876543210", "Domestic", "BESCOM", "0");
        String consumerId = match(registration, "Registered consumer (PB-[A-Z0-9-]+)");
        assertNotNull(store.collection("consumers").find(Filters.eq("consumerId", consumerId)).first());
        assertTrue(run("2", "9", "0").contains("Choose a listed option."));
        assertTrue(run("2", "1", consumerId, "bad-period", "0").contains("Use billing period format YYYY-MM."));
        assertTrue(run("2", "1", consumerId, "2025-04", "10", "5", "", "0").contains("Current reading is below previous reading."));
        assertEquals(0, store.collection("meter_readings").countDocuments(Filters.eq("period", "2025-04")));
        assertTrue(run("1", "2", "0").contains(consumerId));
        assertTrue(run("1", "3", "Ada Meter", "0").contains(consumerId));
        assertTrue(run("1", "3", consumerId, "0").contains("Ada Meter"));
        run("1", "4", consumerId, "Ada Updated", "2 Power Way", "9876543211", "Domestic", "BESCOM", "0");
        Document consumer = store.collection("consumers").find(Filters.eq("consumerId", consumerId)).first();
        assertEquals("Ada Updated", consumer.getString("name"));
        assertEquals("9876543211", consumer.getString("phone"));

        assertTrue(run("2", "1", consumerId, "2025-01", "0", "100", "", "0").contains("Units consumed: 100"));
        assertTrue(run("2", "1", consumerId, "2025-02", "100", "20", "replacement", "120", "0").contains("Units consumed: 40"));
        assertTrue(run("2", "1", consumerId, "2025-03", "95", "20", "rollover", "0").contains("Units consumed: 26"));
        assertTrue(run("2", "2", consumerId, "0").contains("2025-03"));
        assertTrue(run("2", "1", consumerId, "2025-01", "0", "100", "", "0").contains("E11000"));
        assertEquals(3, store.collection("meter_readings").countDocuments(Filters.eq("consumerId", consumerId)));

        run("7", "1", "DEMO household subsidy", "DOMESTIC", "500", "25", "30", "2024-01-01", "2026-12-31", "0");
        assertTrue(run("7", "2", "0").contains("DEMO household subsidy"));
        assertTrue(run("7", "3", consumerId, "2025-01", "100", "0").contains("25.00"));
        run("7", "1", "DEMO low use add-on", "DOMESTIC", "120", "20", "20", "2024-01-01", "2026-12-31", "0");

        String billOutput = run("3", "1", consumerId, "2025-01", "0");
        String billId = match(billOutput, "Bill (BILL-[A-Z0-9-]+) v1");
        Document originalBill = store.collection("bills").find(Filters.eq("billId", billId)).first();
        assertTrue(billOutput.contains("Generated On: " + originalBill.getString("generatedAt") + " | Due: " + originalBill.getString("dueDate")));
        assertEquals("205.00", originalBill.getString("total"));
        int configuredDays=Main.configuredDueDays(System.getenv("POWERBILL_DUE_DAYS"));
        assertEquals(LocalDate.parse(originalBill.getString("generatedAt")).plusDays(configuredDays).toString(),originalBill.getString("dueDate"),"An old billing period generated now is due from its generation date.");
        assertTrue(run("3", "1", consumerId, "2025-01", "0").contains("A bill already exists"));
        String correction = run("3", "2", billId, "Corrected transcription from meter inspection", "CORRECT", "0", "150", "0");
        assertTrue(correction.contains("Corrected bill version 2"));
        Document corrected = store.collection("bills").find(Filters.eq("billId", billId)).first();
        assertTrue(correction.contains("Generated On: " + corrected.getString("generatedAt") + " | Due: " + corrected.getString("dueDate")));
        assertEquals(2, corrected.getInteger("version"));
        assertEquals("425.00", corrected.getString("total"));
        assertEquals("25.00", corrected.getString("subsidy"));
        assertEquals("25.00", store.collection("subsidy_history").find(Filters.eq("billId", billId)).first().getString("amount"));
        assertEquals("2.00", corrected.get("tariffSnapshot", Document.class).getList("slabs", Document.class).getFirst().getString("rate"));
        assertEquals(1, store.collection("bill_corrections").countDocuments(Filters.and(Filters.eq("billId", billId), Filters.eq("state", "APPLIED"))));
        assertEquals("205.00", store.collection("bill_corrections").find(Filters.eq("billId", billId)).first().get("billSnapshot", Document.class).getString("total"));
        assertEquals("150", store.collection("meter_readings").find(Filters.and(Filters.eq("consumerId", consumerId), Filters.eq("period", "2025-01"))).first().getString("units"));
        String viewedBill = run("3", "3", billId, "0");
        assertTrue(viewedBill.contains("v2"));
        assertTrue(viewedBill.contains("Generated On: " + corrected.getString("generatedAt") + " | Due: " + corrected.getString("dueDate")));
        assertTrue(run("1", "6", consumerId, "0").contains(billId));

        String second = run("1", "1", "Delete Me", "3 Power Way", "9876543212", "Domestic", "BESCOM", "0");
        String deleteId = match(second, "Registered consumer (PB-[A-Z0-9-]+)");
        run("1", "5", deleteId, "DELETE", "0");
        assertEquals("INACTIVE", store.collection("consumers").find(Filters.eq("consumerId", deleteId)).first().getString("status"));

        Document archivedBill = new Document(corrected), archivedReading = new Document(store.collection("meter_readings").find(Filters.and(Filters.eq("consumerId", consumerId), Filters.eq("period", "2025-01"))).first());
        List<Document> archivedSubsidies = new ArrayList<>();
        for (Document subsidyRecord : store.collection("subsidy_history").find(Filters.eq("billId", billId))) archivedSubsidies.add(new Document(subsidyRecord));
        String interruptedId = UUID.randomUUID().toString();
        store.collection("bill_corrections").insertOne(new Document("correctionId", interruptedId).append("billId", billId).append("versionBefore", 2).append("state", "PREPARED").append("billSnapshot", archivedBill).append("meterReadingSnapshot", archivedReading).append("subsidyHistorySnapshot", archivedSubsidies));
        store.collection("bills").updateOne(Filters.eq("billId", billId), new Document("$set", new Document("total", "9999.00")));
        store.collection("meter_readings").updateOne(Filters.eq("readingId", archivedReading.getString("readingId")), new Document("$set", new Document("units", "9999")));
        store.close(); store = new MongoStore(uri, databaseName);
        assertTrue(run("0").contains("Recovered interrupted bill correction"));
        assertEquals("425.00", store.collection("bills").find(Filters.eq("billId", billId)).first().getString("total"));
        assertEquals("ROLLED_BACK", store.collection("bill_corrections").find(Filters.eq("correctionId", interruptedId)).first().getString("state"));
        assertEquals("150", store.collection("consumption_history").find(Filters.and(Filters.eq("consumerId", consumerId), Filters.eq("period", "2025-01"))).first().getString("units"));
        assertEquals("25.00", store.collection("subsidy_history").find(Filters.eq("billId", billId)).first().getString("amount"));
    }

    @Test void futureBillingPeriodIsRejectedUntilItEndsAndDueDateUsesGenerationDate() {
        String registration = run("1", "1", "Future Cycle", "5 Power Way", "9123456789", "Domestic", "BESCOM", "0");
        String consumerId = match(registration, "Registered consumer (PB-[A-Z0-9-]+)");
        assertTrue(run("2", "1", consumerId, "2026-11", "0", "100", "", "0").contains("Units consumed: 100"));
        assertTrue(run("2", "1", consumerId, "2027-02", "0", "100", "", "0").contains("Units consumed: 100"));

        Clock november2026 = Clock.fixed(LocalDate.of(2026, 11, 14).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        assertTrue(runAt(november2026, "3", "1", consumerId, "2026-11", "0").contains("A bill can be generated only after its billing period has ended."));
        String earlyAttempt = runAt(november2026, "3", "1", consumerId, "2027-02", "0");
        assertTrue(earlyAttempt.contains("A bill can be generated only after its billing period has ended."));
        assertEquals(0, store.collection("bills").countDocuments(Filters.and(Filters.eq("consumerId", consumerId), Filters.eq("period", "2026-11"))));
        assertEquals(0, store.collection("bills").countDocuments(Filters.and(Filters.eq("consumerId", consumerId), Filters.eq("period", "2027-02"))));

        Clock march2027 = Clock.fixed(LocalDate.of(2027, 3, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        String generatedOutput = runAt(march2027, "3", "1", consumerId, "2027-02", "0");
        Document bill = store.collection("bills").find(Filters.and(Filters.eq("consumerId", consumerId), Filters.eq("period", "2027-02"))).first();
        assertNotNull(bill);
        assertEquals("2027-03-01", bill.getString("generatedAt"));
        int configuredDays = Main.configuredDueDays(System.getenv("POWERBILL_DUE_DAYS"));
        assertEquals(LocalDate.parse(bill.getString("generatedAt")).plusDays(configuredDays).toString(), bill.getString("dueDate"));
        assertTrue(generatedOutput.contains("Billing Period: 2027-02"));
        assertTrue(generatedOutput.contains("Generated On: 2027-03-01 | Due: " + bill.getString("dueDate")));
    }

    @Test void paymentOverdueComparisonSpikeAndReportMenusWork() {
        String registration = run("1", "1", "Bill Payer", "4 Power Way", "9123456780", "Domestic", "BESCOM", "0");
        String consumerId = match(registration, "Registered consumer (PB-[A-Z0-9-]+)");
        run("2", "1", consumerId, "2025-01", "0", "100", "", "0");
        String output = run("3", "1", consumerId, "2025-01", "0");
        String billId = match(output, "Bill (BILL-[A-Z0-9-]+) v1");
        Document bill = store.collection("bills").find(Filters.eq("billId", billId)).first();
        store.collection("bills").updateOne(Filters.eq("billId", billId), new Document("$set", new Document("dueDate", LocalDate.now().minusDays(1).toString())));

        String overduePaymentOutput = run("4", "1", billId, "20", "0");
        assertTrue(overduePaymentOutput.contains("Payment Successful!"));
        Document afterPartial = store.collection("bills").find(Filters.eq("billId", billId)).first();
        assertEquals("PARTIAL_OVERDUE", afterPartial.getString("status"));
        String totalAfterLateFee = afterPartial.getString("total");
        assertEquals("10.00", afterPartial.getString("lateFee"));
        assertTrue(run("4", "1", billId, "99999", "0").contains("no more than the outstanding balance"));
        assertEquals(1, store.collection("payments").countDocuments(Filters.eq("billId", billId)));
        String secondPartial = run("4", "1", billId, "5", "0");
        assertTrue(secondPartial.contains("Payment Successful!"));
        assertEquals(2, store.collection("payments").countDocuments(Filters.eq("billId", billId)));
        assertTrue(run("5", "0").contains("late fee ₹10.00"));
        assertTrue(run("5", "0").contains("late fee ₹10.00"));
        assertEquals(totalAfterLateFee, store.collection("bills").find(Filters.eq("billId", billId)).first().getString("total"));
        run("2", "1", consumerId, "2025-02", "100", "110", "", "0");
        String feeOnlyOutput = run("3", "1", consumerId, "2025-02", "0");
        String feeOnlyBillId = match(feeOnlyOutput, "Bill (BILL-[A-Z0-9-]+) v1");
        store.collection("bills").updateOne(Filters.eq("billId", feeOnlyBillId), new Document("$set", new Document("dueDate", LocalDate.now().minusDays(1).toString())));
        run("5", "0");
        assertTrue(run("3", "2", feeOnlyBillId, "attempt after late fee", "CORRECT", "0", "0", "200", "0").contains("applied late fee cannot be corrected"));
        BigDecimal balance = new BigDecimal(totalAfterLateFee).subtract(new BigDecimal("25.00"));
        assertTrue(run("4", "3", "0").contains(balance.toPlainString()));
        assertTrue(run("4", "1", billId, balance.toPlainString(), "0").contains("Payment Successful!"));
        assertEquals("PAID", store.collection("bills").find(Filters.eq("billId", billId)).first().getString("status"));
        assertTrue(run("3", "2", billId, "attempt after payment", "CORRECT", "0", "0", "200", "0").contains("with payments cannot be corrected"));
        assertTrue(run("4", "2", billId, "0").contains("PAY-"));

        putHistory(consumerId, "2024-04", "100"); putHistory(consumerId, "2025-04", "170");
        putHistory(consumerId, "2024-06", "0"); putHistory(consumerId, "2025-06", "20"); putHistory(consumerId, "2025-07", "30");
        assertTrue(run("6", consumerId, "4", "2024", "2025", "0").contains("70.00%"));
        assertTrue(run("6", consumerId, "4", "2024", "2026", "0").contains("Current year must be exactly one year after previous year."));
        assertTrue(run("6", consumerId, "6", "2024", "2025", "0").contains("undefined (previous was zero)"));
        assertTrue(run("6", consumerId, "7", "2024", "2025", "0").contains("Insufficient historical records"));
        assertTrue(run("8", "", "0").contains("increase=70.00% threshold=50%"));
        assertTrue(run("8", "50", "0").contains("Spike warnings:"));

        for (int report = 1; report <= 9; report++) {
            String input = report == 8 ? "10\n8\nPAID\n0\n" : "10\n" + report + "\n0\n";
            String reportOutput = runLines(input);
            assertFalse(reportOutput.contains("Choose a listed option."), "Report submenu " + report + " should be implemented");
        }
    }

    @Test void paymentsGeneratePersistentReceiptsAndUpdateHistoryAndOutstandingBalances() {
        String registration = run("1", "1", "Payment Ledger", "9 Payment Road", "9234567890", "Domestic", "BESCOM", "0");
        String consumerId = match(registration, "Registered consumer (PB-[A-Z0-9-]+)");
        run("2", "1", consumerId, "2025-01", "0", "100", "", "0");
        String billOutput = run("3", "1", consumerId, "2025-01", "0");
        String billId = match(billOutput, "Bill (BILL-[A-Z0-9-]+) v1");
        Document bill = store.collection("bills").find(Filters.eq("billId", billId)).first();
        String payable = bill.getString("total");
        Clock paymentClock = Clock.fixed(LocalDate.of(2026, 11, 15).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

        assertTrue(runAt(paymentClock, "4", "1", billId, "0", "0").contains("Payment must be positive"));
        assertTrue(runAt(paymentClock, "4", "1", billId, "-1", "0").contains("must be a non-negative number"));
        assertTrue(runAt(paymentClock, "4", "1", billId, new BigDecimal(payable).add(BigDecimal.ONE).toPlainString(), "0").contains("no more than the outstanding balance"));
        assertEquals(0, store.collection("payments").countDocuments(Filters.eq("billId", billId)), "Rejected amounts must not create payment records.");
        assertEquals(0, store.collection("application_counters").countDocuments(Filters.eq("_id", "paymentReceipt")), "Rejected amounts must not consume receipt numbers.");

        String firstPaymentOutput = runAt(paymentClock, "4", "1", billId, "100", "0");
        assertOrdered(firstPaymentOutput,
                "Bill ID: " + billId,
                "Billing Period: 2025-01",
                "Payable Amount: ₹" + payable,
                "Enter Payment Amount:",
                "Payment Successful!",
                "Receipt ID: PAY-000001",
                "Payment Date: 2026-11-15",
                "Remaining Balance: ₹150.00");
        Document firstPayment = store.collection("payments").find(Filters.eq("receiptId", "PAY-000001")).first();
        assertNotNull(firstPayment);
        assertEquals(billId, firstPayment.getString("billId"));
        assertEquals("2025-01", firstPayment.getString("period"));
        assertEquals("100.00", firstPayment.getString("amount"));
        assertEquals("2026-11-15", firstPayment.getString("paymentDate"));
        assertEquals("SUCCESSFUL", firstPayment.getString("paymentStatus"));
        assertEquals("PARTIALLY_PAID", store.collection("bills").find(Filters.eq("billId", billId)).first().getString("status"));

        String outstanding = runAt(paymentClock, "4", "3", "0");
        assertTrue(outstanding.contains("Payable Amount: ₹" + payable));
        assertTrue(outstanding.contains("Amount Paid: ₹100.00"));
        assertTrue(outstanding.contains("Remaining Balance: ₹150.00"));
        assertTrue(outstanding.contains("Payment Status: PARTIALLY_PAID"));

        store.close();
        store = new MongoStore(uri, databaseName);
        assertNotNull(store.collection("payments").find(Filters.eq("receiptId", "PAY-000001")).first(), "Receipt must persist after reopening MongoDB client.");
        String secondPaymentOutput = runAt(paymentClock, "4", "1", billId, "150", "0");
        assertOrdered(secondPaymentOutput, "Payment Successful!", "Receipt ID: PAY-000002", "Payment Date: 2026-11-15", "Remaining Balance: ₹0.00");
        Document secondPayment = store.collection("payments").find(Filters.eq("receiptId", "PAY-000002")).first();
        assertNotNull(secondPayment);
        assertEquals("2025-01", secondPayment.getString("period"));
        assertNotEquals(firstPayment.getString("receiptId"), secondPayment.getString("receiptId"));
        assertEquals(2, store.collection("payments").countDocuments(Filters.eq("billId", billId)));
        assertEquals("PAID", store.collection("bills").find(Filters.eq("billId", billId)).first().getString("status"));

        String history = runAt(paymentClock, "4", "2", billId, "0");
        assertTrue(history.contains("Receipt ID: PAY-000001 | Bill ID: " + billId + " | Billing Period: 2025-01 | Payment Amount: ₹100.00 | Payment Date: 2026-11-15 | Payment Status: SUCCESSFUL"));
        assertTrue(history.contains("Receipt ID: PAY-000002 | Bill ID: " + billId + " | Billing Period: 2025-01 | Payment Amount: ₹150.00 | Payment Date: 2026-11-15 | Payment Status: SUCCESSFUL"));
        String paidOutstanding = runAt(paymentClock, "4", "3", "0");
        assertTrue(paidOutstanding.contains("Payable Amount: ₹" + payable));
        assertTrue(paidOutstanding.contains("Amount Paid: ₹" + payable));
        assertTrue(paidOutstanding.contains("Remaining Balance: ₹0.00"));
        assertTrue(paidOutstanding.contains("Payment Status: PAID"));
    }

    @Test void invalidLegacyBillCannotBePaidOrCorrectedAndExistingPaymentIsPreserved() {
        Clock novemberFirst = Clock.fixed(LocalDate.of(2026, 11, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        Clock novemberFifteenth = Clock.fixed(LocalDate.of(2026, 11, 15).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        String registration = run("1", "1", "Legacy Review", "10 Audit Road", "9234567891", "Domestic", "BESCOM", "0");
        String consumerId = match(registration, "Registered consumer (PB-[A-Z0-9-]+)");
        run("2", "1", consumerId, "2026-10", "0", "100", "", "0");
        String validBillOutput = runAt(novemberFirst, "3", "1", consumerId, "2026-10", "0");
        String validBillId = match(validBillOutput, "Bill (BILL-[A-Z0-9-]+) v1");
        assertEquals("2026-11-01", store.collection("bills").find(Filters.eq("billId", validBillId)).first().getString("generatedAt"));

        String invalidBillId = "BILL-LEGACY-NOV-001";
        Document invalidBill = new Document("billId", invalidBillId).append("version", 1).append("consumerId", consumerId)
                .append("consumerName", "Legacy Review").append("provider", "BESCOM").append("category", "DOMESTIC")
                .append("period", "2026-11").append("generatedAt", "2026-11-14").append("dueDate", "2026-11-14")
                .append("total", "825.00").append("lateFee", "0.00").append("status", "PARTIALLY_PAID");
        store.collection("bills").insertOne(invalidBill);
        Document oldPayment = new Document("paymentId", "legacy-payment-300").append("receiptId", "PAY-000001")
                .append("billId", invalidBillId).append("consumerId", consumerId).append("period", "2026-11")
                .append("amount", "300.00").append("paymentDate", "2026-11-15").append("paymentStatus", "SUCCESSFUL")
                .append("timestamp", "2026-11-15T00:00:00Z");
        store.collection("payments").insertOne(oldPayment);

        String rejected = runAt(novemberFifteenth, "4", "1", invalidBillId, "100", "0");
        assertTrue(rejected.contains("Payment rejected: Billing period 2026-11 has not ended."));
        assertFalse(rejected.contains("Enter Payment Amount:"));
        assertEquals(1, store.collection("payments").countDocuments(Filters.eq("billId", invalidBillId)));
        Document preservedPayment = store.collection("payments").find(Filters.eq("paymentId", "legacy-payment-300")).first();
        assertEquals("300.00", preservedPayment.getString("amount"));
        assertEquals("PAY-000001", preservedPayment.getString("receiptId"));
        assertEquals(0, store.collection("application_counters").countDocuments(Filters.eq("_id", "paymentReceipt")), "Rejected payment must not allocate a new receipt number.");
        assertEquals("0.00", store.collection("bills").find(Filters.eq("billId", invalidBillId)).first().getString("lateFee"));
        assertTrue(runAt(novemberFifteenth, "5", "0").contains("Skipping invalid bill " + invalidBillId + ": late fee blocked"));
        assertEquals("825.00", store.collection("bills").find(Filters.eq("billId", invalidBillId)).first().getString("total"));
        assertEquals("0.00", store.collection("bills").find(Filters.eq("billId", invalidBillId)).first().getString("lateFee"));

        assertTrue(runAt(novemberFifteenth, "3", "2", invalidBillId, "0").contains("This bill is invalid and cannot be corrected"));
        assertEquals(0, store.collection("bill_corrections").countDocuments(Filters.eq("billId", invalidBillId)));
        String history = runAt(novemberFifteenth, "4", "2", invalidBillId, "0");
        assertTrue(history.contains("Receipt ID: PAY-000001 | Bill ID: " + invalidBillId + " | Billing Period: 2026-11 | Payment Amount: ₹300.00"));
        assertTrue(history.contains("BILL INVALID: Billing period 2026-11 has not ended."));
        String outstanding = runAt(novemberFifteenth, "4", "3", "0");
        assertTrue(outstanding.contains("Original Bill Amount (not payable): ₹825.00"));
        assertTrue(outstanding.contains("Amount Paid: ₹300.00"));
        assertTrue(outstanding.contains("Payment Eligibility: BLOCKED - Billing period 2026-11 has not ended."));

        String accepted = runAt(novemberFifteenth, "4", "1", validBillId, "100", "0");
        assertTrue(accepted.contains("Payment Successful!"));
        assertTrue(accepted.contains("Receipt ID: PAY-000002"), "Receipt numbering must account for preserved historical receipts without consuming a number for the rejected payment.");
        Document validPayment = store.collection("payments").find(Filters.eq("receiptId", "PAY-000002")).first();
        assertNotNull(validPayment);
        assertEquals("2026-10", validPayment.getString("period"));
        assertEquals("PARTIALLY_PAID", store.collection("bills").find(Filters.eq("billId", validBillId)).first().getString("status"));
        assertEquals("300.00", store.collection("payments").find(Filters.eq("paymentId", "legacy-payment-300")).first().getString("amount"));
    }

    @Test void latePaymentMenuReportsWhenNoOverdueBillsExist() {
        String output = run("5", "0");
        assertTrue(output.contains("No overdue unpaid or partially paid bills found."));
    }

    @Test void commercialConsumerBillUsesCommercialTariffCategory() {
        String registration=run("1","1","Commercial Test","8 Business Road","9345678903","Commercial","BESCOM","0");
        String consumerId=match(registration,"Registered consumer (PB-[A-Z0-9-]+)");
        run("2","1",consumerId,"2025-01","0","100","","0");
        String billOutput=run("3","1",consumerId,"2025-01","0");
        String billId=match(billOutput,"Bill (BILL-[A-Z0-9-]+) v1");
        Document bill=store.collection("bills").find(Filters.eq("billId",billId)).first();
        assertEquals("COMMERCIAL",bill.getString("category"));
        assertEquals("COMMERCIAL",bill.get("tariffSnapshot",Document.class).getString("category"));
    }

    @Test void firstSlabSubsidyRespectsConsumptionAndConsumerEligibility() {
        String domesticOutput = run("1", "1", "Subsidy Eligible", "6 Subsidy Road", "9345678901", "Domestic", "BESCOM", "0");
        String domesticId = match(domesticOutput, "Registered consumer (PB-[A-Z0-9-]+)");
        String commercialOutput = run("1", "1", "Subsidy Ineligible", "7 Subsidy Road", "9345678902", "Commercial", "BESCOM", "0");
        String commercialId = match(commercialOutput, "Registered consumer (PB-[A-Z0-9-]+)");

        run("7", "1", "DEMO first-slab support", "DOMESTIC", "500", "500", "500", "2024-01-01", "2026-12-31", "0");
        assertTrue(run("7", "3", domesticId, "2025-01", "50", "0").contains("₹100.00"));
        assertTrue(run("7", "3", domesticId, "2025-01", "100", "0").contains("₹200.00"));
        assertTrue(run("7", "3", domesticId, "2025-01", "150", "0").contains("₹200.00"));
        assertTrue(run("7", "3", commercialId, "2025-01", "50", "0").contains("₹0.00"));

        run("2", "1", domesticId, "2025-01", "0", "50", "", "0");
        String billOutput = run("3", "1", domesticId, "2025-01", "0");
        String billId = match(billOutput, "Bill (BILL-[A-Z0-9-]+) v1");
        assertTrue(billOutput.contains("First-slab subsidy -₹100.00"));
        Document bill = store.collection("bills").find(Filters.eq("billId", billId)).first();
        assertEquals("100.00", bill.getString("energy"));
        assertEquals("100.00", bill.getString("subsidy"));
        assertEquals("50.00", bill.getString("total"));
        assertEquals("100.00", store.collection("subsidy_history").find(Filters.eq("billId", billId)).first().getString("amount"));
    }

    @Test void tariffVersionsCsvSampleDataAndRestartMenusWork(@TempDir Path temp) throws Exception {
        String consumerOut = run("1", "1", "Tariff History", "5 Power Way", "9234567890", "Domestic", "BESCOM", "0");
        String consumerId = match(consumerOut, "Registered consumer (PB-[A-Z0-9-]+)");
        run("2", "1", consumerId, "2025-01", "0", "100", "", "0");
        String billOut = run("3", "1", consumerId, "2025-01", "0");
        String billId = match(billOut, "Bill (BILL-[A-Z0-9-]+) v1");
        Document oldTariff = store.collection("tariffs").find(Filters.and(Filters.eq("provider", "BESCOM"), Filters.eq("category", "DOMESTIC"))).first();
        String oldTariffId = oldTariff.getString("tariffId");
        assertTrue(run("9", "1", "0").contains(oldTariffId));
        run("9", "5", oldTariffId, "2025-02-28", "New rates effective in March", "RETIRE", "0");
        Document retired = store.collection("tariffs").find(Filters.eq("tariffId", oldTariffId)).first();
        assertEquals("2025-02-28", retired.getString("effectiveTo"));
        assertEquals("DEMONSTRATION ONLY; NOT OFFICIAL BESCOM RATES", store.collection("bills").find(Filters.eq("billId", billId)).first().get("tariffSnapshot", Document.class).getString("source"));
        assertEquals(1, store.collection("tariff_retirements").countDocuments(Filters.eq("tariffId", oldTariffId)));
        assertTrue(run("9", "6", "0").contains("New rates effective in March"));

        run("9", "2", "BESCOM", "Domestic", "DEMO March version", "2025-03-01", "", "10", "3", "-5", "1", "", "1", "", "", "0");
        Document newTariff = store.collection("tariffs").find(Filters.and(Filters.eq("provider", "BESCOM"), Filters.eq("category", "DOMESTIC"), Filters.eq("name", "DEMO March version"))).first();
        assertNotNull(newTariff);
        assertTrue(run("9", "2", "BESCOM", "Domestic", "Overlapping DEMO", "2025-02-01", "2025-04-01", "0", "0", "0", "1", "", "1", "Demo source", "0").contains("overlap an existing version"));
        run("2", "1", consumerId, "2025-03", "100", "150", "", "0");
        String marchBill = run("3", "1", consumerId, "2025-03", "0");
        String marchBillId = match(marchBill, "Bill (BILL-[A-Z0-9-]+) v1");
        Document generatedMarch = store.collection("bills").find(Filters.eq("billId", marchBillId)).first();
        assertEquals("DEMO March version", generatedMarch.get("tariffSnapshot", Document.class).getString("name"));
        assertEquals("-5.00", generatedMarch.getString("adjustment"));

        Path consumers = temp.resolve("consumers.csv"), readings = temp.resolve("readings.csv"), tariffs = temp.resolve("tariffs.csv"), bills = temp.resolve("bills.csv"), history = temp.resolve("history.csv");
        assertTrue(run("11", "1", consumers.toString(), "0").contains("Exported consumers"));
        assertTrue(run("11", "3", readings.toString(), "0").contains("Exported meter_readings"));
        assertTrue(run("11", "5", tariffs.toString(), "0").contains("Exported tariff configurations"));
        assertTrue(run("11", "7", bills.toString(), "0").contains("Exported bills"));
        assertTrue(run("11", "8", history.toString(), "0").contains("Exported consumption_history"));
        assertTrue(Files.readString(consumers).contains("Tariff History"));
        assertTrue(Files.readString(readings).contains("2025-01"));
        assertTrue(Files.readString(bills).contains(billId));
        assertTrue(Files.readString(history).contains("2025-01"));
        assertTrue(run("11", "6", tariffs.toString(), "0").contains("Tariff import succeeded: 0; failed: 3"));

        Path consumerImport = temp.resolve("consumer-import.csv");
        Files.writeString(consumerImport,"consumerId,name,address,phone,category,provider,status\nCSV-1,CSV Consumer,9 CSV Road,9345678901,DOMESTIC,BESCOM,ACTIVE\nCSV-2,Unknown Provider,10 CSV Road,9345678902,DOMESTIC,UNKNOWN,ACTIVE\nCSV-3,Invalid Status,11 CSV Road,9345678903,DOMESTIC,BESCOM,SUSPENDED\n",StandardCharsets.UTF_8);
        assertTrue(run("11", "2", consumerImport.toString(), "0").contains("Consumer import succeeded: 1; failed: 2"));
        Path readingImport = temp.resolve("reading-import.csv");
        Files.writeString(readingImport,"consumerId,period,previous,current\nCSV-1,2026-01,0,12\n",StandardCharsets.UTF_8);
        assertTrue(run("11", "4", readingImport.toString(), "0").contains("Meter reading import succeeded: 1; failed: 0"));
        assertEquals(12, new BigDecimal(store.collection("meter_readings").find(Filters.eq("consumerId", "CSV-1")).first().getString("units")).intValueExact());
        Path historyImport = temp.resolve("history-import.csv");
        Files.writeString(historyImport,"consumerId,period,units,note\nCSV-1,2026-02,35,Imported historical record\n",StandardCharsets.UTF_8);
        assertTrue(run("11", "9", historyImport.toString(), "0").contains("Consumption history import succeeded: 1; failed: 0"));
        assertEquals("35", store.collection("consumption_history").find(Filters.and(Filters.eq("consumerId", "CSV-1"), Filters.eq("period", "2026-02"))).first().getString("units"));
        Path summary = temp.resolve("summary.csv");
        assertTrue(run("11", "10", summary.toString(), "7", "0").contains("Exported billing summary"));
        assertTrue(Files.readString(summary).contains("billCount"));
        for (int report = 1; report <= 9; report++) {
            Path reportFile = temp.resolve("report-" + report + ".csv");
            String reportOutput = report == 8 ? run("11", "10", reportFile.toString(), "8", "PAID", "0") : run("11", "10", reportFile.toString(), Integer.toString(report), "0");
            assertTrue(reportOutput.contains("Exported"), "Report CSV option " + report + " should export a file: " + reportOutput);
            assertTrue(Files.size(reportFile) > 0, "Report CSV option " + report + " should write a header or records");
        }

        assertTrue(run("9", "3", "0").contains("BESCOM"));
        assertTrue(run("9", "4", "TEST-UTILITY", "Test region", "0").contains("Provider configured"));
        assertEquals(1, store.collection("electricity_providers").countDocuments(Filters.eq("providerId", "TEST-UTILITY")));
        Path utilityTariff = temp.resolve("utility-tariff.csv");
        Files.writeString(utilityTariff,"provider,category,name,effectiveFrom,effectiveTo,fixedCharge,additionalCharge,adjustment,source,slabsJson\nTEST-UTILITY,DOMESTIC,DEMO imported,2026-01-01,,0,0,0,Demo reference,\"{\"\"slabs\"\":[{\"\"upperLimit\"\":null,\"\"rate\"\":\"\"1.00\"\"}]}\"\n",StandardCharsets.UTF_8);
        assertTrue(run("11", "6", utilityTariff.toString(), "0").contains("Tariff import succeeded: 1; failed: 0"));
        assertTrue(run("12", "0").contains("30 consumers and 720"));
        assertTrue(run("12", "0").contains("already exists"));
        assertEquals(30, store.collection("consumers").countDocuments(Filters.eq("sampleData", true)));
        assertEquals(720, store.collection("meter_readings").countDocuments(Filters.regex("consumerId", "^SIM-")));

        store.close();
        store = new MongoStore(uri, databaseName);
        assertTrue(run("13", "0").contains("consumers: 32"));
        assertTrue(run("13", "0").contains("tariffs: 4"));
    }

    private void putHistory(String consumerId, String period, String units) {
        Document record = new Document("consumerId", consumerId).append("period", period).append("units", units).append("createdAt", java.time.Instant.now().toString());
        store.collection("consumption_history").replaceOne(Filters.and(Filters.eq("consumerId", consumerId), Filters.eq("period", period)), record, new com.mongodb.client.model.ReplaceOptions().upsert(true));
    }

    private String run(String... inputs) { return runLines(String.join("\n", inputs) + "\n"); }
    private String runAt(Clock clock, String... inputs) { return runLines(String.join("\n", inputs) + "\n", clock); }
    private String runLines(String input) {
        return runLines(input, Clock.systemDefaultZone());
    }
    private String runLines(String input, Clock clock) {
        var previousIn = System.in; var previousOut = System.out; var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))); System.setOut(output);
            new Main(store, clock).run();
        } finally { System.setIn(previousIn); System.setOut(previousOut); }
        return bytes.toString(StandardCharsets.UTF_8);
    }
    private String match(String output, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(output);
        assertTrue(matcher.find(), "Could not match /" + regex + "/ in console output:\n" + output);
        return matcher.group(1);
    }
    private void assertOrdered(String output, String... fragments) {
        int previous = -1;
        for (String fragment : fragments) {
            int next = output.indexOf(fragment, previous + 1);
            assertTrue(next > previous, "Expected output fragment in order: " + fragment + "\n" + output);
            previous = next;
        }
    }
}
