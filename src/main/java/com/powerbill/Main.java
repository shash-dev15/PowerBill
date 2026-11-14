package com.powerbill;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Scanner;
import java.util.UUID;

/** Terminal entry point and console workflows. Permanent business records are stored in MongoDB. */
public final class Main {
    private final Scanner in = new Scanner(System.in);
    private final MongoStore store;
    private final Clock clock;
    Main(MongoStore store) { this(store, Clock.systemDefaultZone()); }
    Main(MongoStore store, Clock clock) { this.store = store; this.clock = Objects.requireNonNull(clock); }

    public static void main(String[] args) {
        Properties properties=new Properties();
        try(InputStream config=Main.class.getResourceAsStream("/application.properties")){if(config!=null)properties.load(config);}catch(IOException e){System.err.println("Could not read application.properties: "+e.getMessage());}
        String uri=System.getenv().getOrDefault("POWERBILL_MONGODB_URI",properties.getProperty("mongodb.uri","mongodb://127.0.0.1:27017"));
        String db=System.getenv().getOrDefault("POWERBILL_DATABASE",properties.getProperty("mongodb.database","powerbill_db"));
        try (MongoStore store = new MongoStore(uri, db)) {
            new Main(store).run();
        } catch (Exception e) {
            System.err.println(MongoStore.connectionMessage(e));
            System.err.println("Check MongoDB is running and POWERBILL_MONGODB_URI / POWERBILL_DATABASE are correct.");
        }
    }
    void run() {
        seedProvidersIfMissing();
        seedDemoTariffIfEmpty();
        recoverPreparedCorrections();
        while (true) {
            System.out.println("\n================================================\n        POWERBILL\nSLAB-BASED UTILITY BILLING ENGINE\n================================================");
            System.out.println("1. Consumer Management\n2. Meter Reading Management\n3. Electricity Bill Generation\n4. Payment Management\n5. Late Payment Management\n6. Yearly Consumption Comparison\n7. Subsidy Management\n8. Consumption Spike Detection\n9. Tariff Management\n10. Reports and Bill History\n11. CSV Import / Export\n12. Load Sample Data\n13. Database Status\n0. Exit");
            System.out.print("Choice: ");
            if (!in.hasNextLine()) {
                System.out.println("End of input. Goodbye.");
                return;
            }
            switch (in.nextLine().trim()) {
                case "1" -> consumers(); case "2" -> readings(); case "3" -> billMenu(); case "4" -> payment(false);
                case "5" -> payment(true); case "6" -> compare(); case "7" -> subsidies(); case "8" -> spikes();
                case "9" -> tariffs(); case "10" -> reports(); case "11" -> csvMenu(); case "12" -> samples();
                case "13" -> status(); case "0" -> { System.out.println("Goodbye."); return; }
                default -> System.out.println("Choose a listed option.");
            }
        }
    }
    private MongoCollection<Document> c(String name) { return store.collection(name); }
    private String ask(String prompt) { System.out.print(prompt); return in.nextLine().trim(); }
    private BigDecimal money(String label) { return decimal(ask(label), label); }
    private BigDecimal decimal(String raw, String label) {
        try { BigDecimal d = new BigDecimal(raw); if (d.signum() < 0) throw new NumberFormatException(); return d; }
        catch (NumberFormatException e) { throw new IllegalArgumentException(label + " must be a non-negative number."); }
    }
    private BigDecimal signedDecimal(String raw,String label){try{return new BigDecimal(raw);}catch(NumberFormatException e){throw new IllegalArgumentException(label+" must be a number.");}}
    private String id() { return UUID.randomUUID().toString(); }
    private LocalDate date(String prompt) {
        try { return LocalDate.parse(ask(prompt)); } catch (DateTimeParseException ex) { throw new IllegalArgumentException("Use date format YYYY-MM-DD."); }
    }
    private YearMonth billingPeriod(String prompt){try{return YearMonth.parse(required(prompt));}catch(DateTimeParseException ex){throw new IllegalArgumentException("Use billing period format YYYY-MM.");}}
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void safe(Work work) { try { work.run(); } catch (Exception e) { if(e instanceof java.util.NoSuchElementException&&!in.hasNextLine())return;System.out.println("Could not complete operation: " + e.getMessage()); } }

    private void consumers() { safe(() -> {
        System.out.println("1 Register  2 List  3 Search  4 Update  5 Delete  6 Billing history");
        switch (ask("Choice: ")) {
            case "1" -> { String name=required("Name: "), address=required("Address: "), phone=required("Phone: ");
                validatePhone(phone);
                String category=category(); String provider=required("Provider (e.g. BESCOM): ").toUpperCase(Locale.ROOT);
                if(c("electricity_providers").countDocuments(Filters.eq("providerId",provider))==0)throw new IllegalArgumentException("Unknown provider.");
                Document d=new Document("consumerId", "PB-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT)).append("name",name).append("address",address).append("phone",phone).append("category",category).append("provider",provider).append("tariffCategory",category).append("registrationDate",LocalDate.now().toString()).append("status","ACTIVE").append("createdAt",LocalDate.now().toString());
                c("consumers").insertOne(d); audit("CONSUMER_REGISTERED", d.getString("consumerId")); System.out.println("Registered consumer " + d.getString("consumerId")); }
            case "2" -> print(c("consumers").find().sort(Sorts.ascending("name")), "consumerId", "name", "category", "provider", "status");
            case "3" -> { String key=ask("Consumer ID or name: "); print(c("consumers").find(Filters.or(Filters.eq("consumerId",key), Filters.regex("name",java.util.regex.Pattern.quote(key),"i"))),"consumerId","name","address","phone","category","provider","status"); }
            case "4" -> { String id=required("Consumer ID: "); consumerOrThrow(id); Document update=new Document("name",required("New name: ")).append("address",required("New address: ")); String phone=required("New phone: "); validatePhone(phone); String cat=category(),provider=required("Provider: ").toUpperCase(Locale.ROOT);if(c("electricity_providers").countDocuments(Filters.eq("providerId",provider))==0)throw new IllegalArgumentException("Unknown provider.");update.append("phone",phone).append("category",cat).append("tariffCategory",cat).append("provider",provider); c("consumers").updateOne(Filters.eq("consumerId",id),new Document("$set",update)); audit("CONSUMER_UPDATED",id); System.out.println("Consumer updated."); }
             case "5" -> { String id=required("Consumer ID: "); if (ask("Type DELETE to confirm: ").equals("DELETE")) { c("consumers").updateOne(Filters.eq("consumerId",id),new Document("$set",new Document("status","INACTIVE"))); audit("CONSUMER_DEACTIVATED",id); System.out.println("Consumer deactivated; financial history retained."); } else System.out.println("Deletion cancelled."); }
            case "6" -> { String id=required("Consumer ID: "); print(c("bills").find(Filters.eq("consumerId",id)).sort(Sorts.descending("period")),"billId","period","units","total","status"); }
            default -> System.out.println("Choose a listed option.");
        }
    }); }
    private String required(String label) { String s=ask(label); if (s.isBlank()) throw new IllegalArgumentException("This field is required."); return s; }
    private void validatePhone(String phone){if(!phone.matches("[+0-9][0-9 -]{7,17}"))throw new IllegalArgumentException("Invalid phone number.");}
    private String category() { String s=required("Category (Domestic/Commercial): "); if (!s.equalsIgnoreCase("Domestic")&&!s.equalsIgnoreCase("Commercial")) throw new IllegalArgumentException("Category must be Domestic or Commercial."); return s.toUpperCase(Locale.ROOT); }

    private void readings() { safe(() -> {
        System.out.println("1 Enter monthly reading  2 View consumer history"); String choice=ask("Choice: ");
        if (!choice.equals("1")&&!choice.equals("2")) throw new IllegalArgumentException("Choose a listed option.");
        String consumer=required("Consumer ID: ");
        if (choice.equals("2")) { print(c("meter_readings").find(Filters.eq("consumerId",consumer)).sort(Sorts.descending("period")),"period","previous","current","units","note"); return; }
        consumerOrThrow(consumer); YearMonth period=billingPeriod("Billing period (YYYY-MM): "); BigDecimal previous=money("Previous meter reading: "), current=money("Current meter reading: ");
        String note=ask("Meter event (blank, replacement, or rollover): ").trim().toLowerCase(Locale.ROOT); BigDecimal units;
        if(note.equals("rollover")){
            String configuredMax=System.getProperty("powerbill.meter.maxReading",System.getenv("POWERBILL_METER_MAX_READING"));if(configuredMax==null||configuredMax.isBlank())throw new IllegalArgumentException("Set POWERBILL_METER_MAX_READING to the configured meter register maximum before recording a rollover.");BigDecimal max=decimal(configuredMax,"configured meter maximum");units=MeterReadingCalculator.rollover(previous,current,max);
        }else if(note.equals("replacement")){
            BigDecimal oldFinal=money("Final reading on the replaced meter: ");units=MeterReadingCalculator.replacement(previous,oldFinal,current);
        }else{
            if(!note.isBlank())throw new IllegalArgumentException("Meter event must be blank, replacement, or rollover.");units=MeterReadingCalculator.normal(previous,current);
        }
        Document r=new Document("readingId",id()).append("consumerId",consumer).append("period",period.toString()).append("previous",previous.toPlainString()).append("current",current.toPlainString()).append("units",units.toPlainString()).append("meterEvent",note.isBlank()?"NONE":note.toUpperCase(Locale.ROOT)).append("note",note).append("createdAt",LocalDate.now().toString());
        c("meter_readings").insertOne(r); c("consumption_history").replaceOne(Filters.and(Filters.eq("consumerId",consumer),Filters.eq("period",period.toString())),new Document(r),new com.mongodb.client.model.ReplaceOptions().upsert(true));
        audit("READING_RECORDED", consumer+" "+period); System.out.println("Units consumed: "+units);
    }); }
    private Document consumerOrThrow(String id) { Document d=c("consumers").find(Filters.eq("consumerId",id)).first(); if(d==null) throw new IllegalArgumentException("Consumer not found."); return d; }

    private void billMenu(){safe(()->{
        System.out.println("1 Generate new bill  2 Correct issued bill  3 View bill");
        switch(ask("Choice: ")){
            case "1" -> generateBill();
            case "2" -> correctBill();
            case "3" -> {String billId=required("Bill ID: ");Document bill=c("bills").find(Filters.eq("billId",billId)).first();if(bill==null)throw new IllegalArgumentException("Bill not found.");printBill(bill);}
            default -> System.out.println("Choose a listed option.");
        }
    });}

    private void generateBill() { safe(() -> {
        String consumerId=required("Consumer ID: "); Document consumer=consumerOrThrow(consumerId); YearMonth period=billingPeriod("Billing period (YYYY-MM): ");
        if (!billingPeriodEnded(period, LocalDate.now(clock))) throw new IllegalArgumentException("A bill can be generated only after its billing period has ended.");
        Document reading=c("meter_readings").find(Filters.and(Filters.eq("consumerId",consumerId),Filters.eq("period",period.toString()))).first(); if(reading==null) throw new IllegalArgumentException("Record the meter reading first.");
        if(c("bills").countDocuments(Filters.and(Filters.eq("consumerId",consumerId),Filters.eq("period",period.toString())))>0) throw new IllegalArgumentException("A bill already exists for this consumer and period.");
        Document tariff=findTariff(consumer.getString("provider"),consumer.getString("category"),period.atEndOfMonth()); if(tariff==null) throw new IllegalArgumentException("No effective tariff found for provider/category/period.");
         List<BillingEngine.Slab> slabs=tariffSlabs(tariff);
        BigDecimal units=new BigDecimal(reading.getString("units")); BigDecimal subsidy=eligibleSubsidy(consumer,units,period,slabs);
        BillingEngine.Result calc=BillingEngine.calculate(units,slabs,new BigDecimal(tariff.getString("fixedCharge")),new BigDecimal(tariff.getString("additionalCharge")),new BigDecimal(Objects.toString(tariff.getString("adjustment"),"0.00")),subsidy);
        LocalDate generated=LocalDate.now(clock), due=calculateDueDate(generated,dueDays()); String billId="BILL-"+UUID.randomUUID().toString().substring(0,10).toUpperCase(Locale.ROOT);
        List<Document> lines=calc.lines().stream().map(x->new Document("slab",x.slabNumber()).append("units",x.units().toPlainString()).append("rate",x.rate().toPlainString()).append("amount",x.amount().toPlainString())).toList();
        Document bill=new Document("billId",billId).append("version",1).append("consumerId",consumerId).append("consumerName",consumer.getString("name")).append("provider",consumer.getString("provider")).append("category",consumer.getString("category")).append("period",period.toString()).append("previousReading",reading.getString("previous")).append("currentReading",reading.getString("current")).append("units",units.toPlainString()).append("slabs",lines).append("energy",calc.energy().toPlainString()).append("fixed",calc.fixed().toPlainString()).append("additional",calc.additional().toPlainString()).append("adjustment",calc.adjustment().toPlainString()).append("subsidy",calc.subsidy().toPlainString()).append("lateFee","0.00").append("total",calc.total().toPlainString()).append("generatedAt",generated.toString()).append("dueDate",due.toString()).append("status","UNPAID").append("tariffSnapshot",tariff);
        c("bills").insertOne(bill);
        if(calc.subsidy().signum()>0)c("subsidy_history").insertOne(new Document("recordId",id()).append("consumerId",consumerId).append("billId",billId).append("period",period.toString()).append("amount",calc.subsidy().toPlainString()).append("createdAt",java.time.Instant.now().toString()));
        printBill(bill); audit("BILL_GENERATED",billId);
    }); }
    private void correctBill(){safe(()->{
        String billId=required("Issued bill ID: ");Document current=c("bills").find(Filters.eq("billId",billId)).first();if(current==null)throw new IllegalArgumentException("Bill not found.");
        String invalidReason=billValidityIssue(current.getString("period"),current.getString("generatedAt"),LocalDate.now(clock));if(invalidReason!=null)throw new IllegalArgumentException("This bill is invalid and cannot be corrected: "+invalidReason);
        if("PAID".equals(current.getString("status"))||c("payments").countDocuments(Filters.eq("billId",billId))>0)throw new IllegalArgumentException("A paid bill or bill with payments cannot be corrected. No changes were made.");
        if(!"0.00".equals(Objects.toString(current.getString("lateFee"),"0.00")))throw new IllegalArgumentException("A bill with an applied late fee cannot be corrected.");
        String reason=required("Correction reason: ");if(!ask("Type CORRECT to archive and apply this correction: ").equals("CORRECT")){System.out.println("Correction cancelled.");return;}
        Document oldReading=c("meter_readings").find(Filters.and(Filters.eq("consumerId",current.getString("consumerId")),Filters.eq("period",current.getString("period")))).first();if(oldReading==null)throw new IllegalArgumentException("The original meter reading is missing.");
        BigDecimal previous=money("Corrected previous reading: "),readingValue=money("Corrected current reading: "),units=MeterReadingCalculator.normal(previous,readingValue);
        Document tariff=current.get("tariffSnapshot",Document.class);List<BillingEngine.Slab> slabs=tariffSlabs(tariff);
        Document subsidyConsumer=new Document("category",current.getString("category"));YearMonth period=YearMonth.parse(current.getString("period"));BigDecimal subsidy=eligibleSubsidy(subsidyConsumer,units,period,slabs);
        BillingEngine.Result calc=BillingEngine.calculate(units,slabs,new BigDecimal(tariff.getString("fixedCharge")),new BigDecimal(tariff.getString("additionalCharge")),new BigDecimal(Objects.toString(tariff.getString("adjustment"),"0.00")),subsidy);
        int oldVersion=Objects.requireNonNullElse(current.getInteger("version"),1);String correctionId=id(),correctionTime=java.time.Instant.now().toString();
        List<Document> oldSubsidyRecords=new ArrayList<>();for(Document record:c("subsidy_history").find(Filters.eq("billId",billId)))oldSubsidyRecords.add(new Document(record));
        c("bill_corrections").insertOne(new Document("correctionId",correctionId).append("billId",billId).append("versionBefore",oldVersion).append("reason",reason).append("state","PREPARED").append("billSnapshot",new Document(current)).append("meterReadingSnapshot",new Document(oldReading)).append("subsidyHistorySnapshot",oldSubsidyRecords).append("createdAt",correctionTime));
        audit("BILL_CORRECTION_PREPARED",billId+" correction="+correctionId+" reason="+reason);
        c("meter_reading_corrections").insertOne(new Document("correctionId",correctionId).append("readingId",oldReading.getString("readingId")).append("billId",billId).append("reason",reason).append("before",new Document(oldReading)).append("createdAt",correctionTime));
        oldReading.put("previous",previous.toPlainString());oldReading.put("current",readingValue.toPlainString());oldReading.put("units",units.toPlainString());oldReading.put("note","Corrected for bill "+billId+": "+reason);oldReading.put("correctedAt",correctionTime);
        c("meter_readings").replaceOne(Filters.eq("readingId",oldReading.getString("readingId")),oldReading);
        c("consumption_history").replaceOne(Filters.and(Filters.eq("consumerId",current.getString("consumerId")),Filters.eq("period",current.getString("period"))),new Document(oldReading),new com.mongodb.client.model.ReplaceOptions().upsert(true));
        List<Document> lines=calc.lines().stream().map(x->new Document("slab",x.slabNumber()).append("units",x.units().toPlainString()).append("rate",x.rate().toPlainString()).append("amount",x.amount().toPlainString())).toList();
        current.put("version",oldVersion+1);current.put("previousReading",previous.toPlainString());current.put("currentReading",readingValue.toPlainString());current.put("units",units.toPlainString());current.put("slabs",lines);current.put("energy",calc.energy().toPlainString());current.put("fixed",calc.fixed().toPlainString());current.put("additional",calc.additional().toPlainString());current.put("adjustment",calc.adjustment().toPlainString());current.put("subsidy",calc.subsidy().toPlainString());current.put("total",calc.total().toPlainString());current.put("correctionReason",reason);current.put("lastCorrectedAt",correctionTime);
        if(LocalDate.now().isAfter(LocalDate.parse(current.getString("dueDate"))))current.put("status","OVERDUE");else current.put("status","UNPAID");
        current.put("correctionId",correctionId);c("bills").replaceOne(Filters.eq("billId",billId),current);c("subsidy_history").deleteMany(Filters.eq("billId",billId));if(calc.subsidy().signum()>0)c("subsidy_history").insertOne(new Document("recordId",id()).append("consumerId",current.getString("consumerId")).append("billId",billId).append("period",current.getString("period")).append("amount",calc.subsidy().toPlainString()).append("createdAt",java.time.Instant.now().toString()));c("bill_corrections").updateOne(Filters.eq("correctionId",correctionId),new Document("$set",new Document("state","APPLIED").append("completedAt",java.time.Instant.now().toString())));audit("BILL_CORRECTED",billId+" correction="+correctionId+" reason="+reason);printBill(current);System.out.println("Corrected bill version "+(oldVersion+1)+". Prior bill and reading snapshots are archived.");
    });}
    private void recoverPreparedCorrections(){
        for(Document correction:c("bill_corrections").find(Filters.eq("state","PREPARED"))){
            Document bill=correction.get("billSnapshot",Document.class),reading=correction.get("meterReadingSnapshot",Document.class);
            if(bill==null||reading==null){System.err.println("Correction "+correction.getString("correctionId")+" is PREPARED but has incomplete snapshots; manual review is required.");continue;}
            c("bills").replaceOne(Filters.eq("billId",bill.getString("billId")),new Document(bill),new com.mongodb.client.model.ReplaceOptions().upsert(true));
            c("meter_readings").replaceOne(Filters.eq("readingId",reading.getString("readingId")),new Document(reading),new com.mongodb.client.model.ReplaceOptions().upsert(true));
            c("consumption_history").replaceOne(Filters.and(Filters.eq("consumerId",reading.getString("consumerId")),Filters.eq("period",reading.getString("period"))),new Document(reading),new com.mongodb.client.model.ReplaceOptions().upsert(true));
            c("subsidy_history").deleteMany(Filters.eq("billId",bill.getString("billId")));
            List<Document> subsidies=correction.getList("subsidyHistorySnapshot",Document.class);
            if(subsidies!=null&&!subsidies.isEmpty())c("subsidy_history").insertMany(subsidies.stream().map(Document::new).toList());
            c("bill_corrections").updateOne(Filters.eq("correctionId",correction.getString("correctionId")),new Document("$set",new Document("state","ROLLED_BACK").append("recoveredAt",java.time.Instant.now().toString())));
            audit("BILL_CORRECTION_RECOVERED",correction.getString("billId")+" correction="+correction.getString("correctionId"));
            System.out.println("Recovered interrupted bill correction "+correction.getString("correctionId")+" from its archived snapshots.");
        }
    }
    private Document findTariff(String provider,String category,LocalDate date) { return c("tariffs").find(Filters.and(Filters.eq("provider",provider),Filters.eq("category",category),Filters.lte("effectiveFrom",date.toString()),Filters.or(Filters.exists("effectiveTo",false),Filters.eq("effectiveTo",null),Filters.gte("effectiveTo",date.toString())))).sort(Sorts.descending("effectiveFrom")).first(); }
    private void printBill(Document b) { System.out.println("\nBill "+b.getString("billId")+" v"+Objects.toString(b.get("version"),"1")+" | "+b.getString("consumerName")+" | Billing Period: "+b.getString("period")+"\nProvider "+b.getString("provider")+" | Units "+b.getString("units")); for(Document l:b.getList("slabs",Document.class))System.out.printf("Slab %s: %s units x %s = ₹%s%n",l.get("slab"),l.get("units"),l.get("rate"),l.get("amount")); System.out.printf("Energy ₹%s | Fixed ₹%s | Other ₹%s | Adjustment ₹%s | First-slab subsidy -₹%s | Late fee ₹%s | Payable ₹%s%n",b.get("energy"),b.get("fixed"),b.get("additional"),Objects.toString(b.getString("adjustment"),"0.00"),b.get("subsidy"),b.get("lateFee"),b.get("total")); System.out.println("Generated On: "+Objects.toString(b.getString("generatedAt"),"Unavailable")+" | Due: "+b.getString("dueDate")+" | "+b.get("status"));String invalidReason=billValidityIssue(b.getString("period"),b.getString("generatedAt"),LocalDate.now(clock));if(invalidReason!=null)System.out.println("Bill Validity: INVALID - "+invalidReason); }

    private void payment(boolean overdueOnly) { safe(() -> {
        if(overdueOnly) {
            boolean foundOverdueBill=false;
            for(Document b:c("bills").find(Filters.and(Filters.ne("status","PAID"),Filters.lt("dueDate",LocalDate.now(clock).toString())))) {
                String invalidReason=billValidityIssue(b.getString("period"),b.getString("generatedAt"),LocalDate.now(clock));if(invalidReason!=null){System.out.println("Skipping invalid bill "+b.getString("billId")+": late fee blocked - "+invalidReason);continue;}
                foundOverdueBill=true;
                applyLateFeeOnce(b);
                if("UNPAID".equals(b.getString("status"))) b.put("status","OVERDUE");else if(List.of("PARTIAL","PARTIALLY_PAID").contains(b.getString("status")))b.put("status","PARTIAL_OVERDUE");
                c("bills").replaceOne(Filters.eq("billId",b.getString("billId")),b);
                System.out.println(b.getString("billId")+" late fee ₹"+b.getString("lateFee")+" outstanding ₹"+outstanding(b)+" due "+b.getString("dueDate"));
            }
            if(!foundOverdueBill) System.out.println("No overdue unpaid or partially paid bills found.");
            return;
        }
        System.out.println("1 Record payment  2 Payment history  3 Outstanding balances");
        String action=ask("Choice: ");
        if(action.equals("2")){String filter=ask("Bill ID or consumer ID (blank for all): ");var query=filter.isBlank()?c("payments").find():c("payments").find(Filters.or(Filters.eq("billId",filter),Filters.eq("consumerId",filter)));printPaymentHistory(query.sort(Sorts.descending("timestamp")));return;}
        if(action.equals("3")){for(Document b:c("bills").find().sort(Sorts.descending("period"))){BigDecimal billTotal=new BigDecimal(b.getString("total")),amountPaid=paid(b.getString("billId"));String invalidReason=billValidityIssue(b.getString("period"),b.getString("generatedAt"),LocalDate.now(clock));String amountLabel=invalidReason==null?"Payable Amount":"Original Bill Amount (not payable)";String balanceLabel=invalidReason==null?"Remaining Balance":"Recorded Balance (not payable; review required)";System.out.println("Bill ID: "+b.getString("billId")+" | Billing Period: "+b.getString("period")+" | "+amountLabel+": ₹"+currency(billTotal)+" | Amount Paid: ₹"+currency(amountPaid)+" | "+balanceLabel+": ₹"+currency(outstanding(b))+" | Payment Status: "+Objects.toString(b.getString("status"),"UNPAID")+(invalidReason==null?"":" | Payment Eligibility: BLOCKED - "+invalidReason));}return;}
        if(!action.equals("1"))throw new IllegalArgumentException("Choose a listed option.");
        String billId=required("Bill ID: "); Document bill=c("bills").find(Filters.eq("billId",billId)).first(); if(bill==null)throw new IllegalArgumentException("Bill not found.");
        LocalDate today=LocalDate.now(clock);String invalidReason=billValidityIssue(bill.getString("period"),bill.getString("generatedAt"),today);if(invalidReason!=null)throw new IllegalArgumentException("Payment rejected: "+invalidReason);
        if(today.isAfter(LocalDate.parse(bill.getString("dueDate")))) { applyLateFeeOnce(bill); c("bills").replaceOne(Filters.eq("billId",billId),bill); }
        BigDecimal balance=outstanding(bill); System.out.println("Bill ID: "+billId);System.out.println("Billing Period: "+bill.getString("period"));System.out.println("Payable Amount: ₹"+currency(balance));BigDecimal amount=money("\nEnter Payment Amount: "); if(amount.signum()==0||amount.compareTo(balance)>0)throw new IllegalArgumentException("Payment must be positive and no more than the outstanding balance.");
        String receiptId=nextPaymentReceiptId();String paymentDate=today.toString();String timestamp=java.time.Instant.now(clock).toString();
        Document p=new Document("paymentId",id()).append("receiptId",receiptId).append("billId",billId).append("consumerId",bill.getString("consumerId")).append("period",bill.getString("period")).append("amount",amount.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString()).append("paymentDate",paymentDate).append("paymentStatus","SUCCESSFUL").append("timestamp",timestamp); c("payments").insertOne(p);
        BigDecimal paid=paid(billId); boolean overdue=today.isAfter(LocalDate.parse(bill.getString("dueDate"))); String state=PaymentCalculator.status(new BigDecimal(bill.getString("total")),paid,overdue); bill.put("status",state); c("bills").replaceOne(Filters.eq("billId",billId),bill); audit("PAYMENT_RECORDED",p.getString("paymentId")); System.out.println("\nPayment Successful!\n");System.out.println("Receipt ID: "+receiptId);System.out.println("Payment Date: "+paymentDate);System.out.println("Remaining Balance: ₹"+currency(outstanding(bill)));
    }); }
    private void applyLateFeeOnce(Document bill) {
        if(!"0.00".equals(bill.getString("lateFee"))) return;
        BigDecimal balance=outstanding(bill);
        BigDecimal rate=new BigDecimal(System.getenv().getOrDefault("POWERBILL_DEMO_LATE_FEE_RATE","0.02"));
        BigDecimal minimum=new BigDecimal(System.getenv().getOrDefault("POWERBILL_DEMO_LATE_FEE_MIN","10.00"));
        BigDecimal maximum=new BigDecimal(System.getenv().getOrDefault("POWERBILL_DEMO_LATE_FEE_MAX","500.00"));
        if(rate.signum()<0||minimum.signum()<0||maximum.compareTo(minimum)<0) throw new IllegalArgumentException("Invalid demonstration late-fee environment configuration.");
        BigDecimal fee=PaymentCalculator.lateFee(balance,rate,minimum,maximum);
        bill.put("lateFee",fee.toPlainString()); bill.put("total",new BigDecimal(bill.getString("total")).add(fee).setScale(2,java.math.RoundingMode.HALF_UP).toPlainString());
    }
    private BigDecimal paid(String id) { BigDecimal n=BigDecimal.ZERO; for(Document p:c("payments").find(Filters.eq("billId",id))) n=n.add(new BigDecimal(p.getString("amount"))); return n; }
    private BigDecimal outstanding(Document bill) { List<BigDecimal> payments=new ArrayList<>();for(Document p:c("payments").find(Filters.eq("billId",bill.getString("billId"))))payments.add(new BigDecimal(p.getString("amount")));return PaymentCalculator.outstanding(new BigDecimal(bill.getString("total")),payments); }
    private String nextPaymentReceiptId(){var counterFilter=Filters.eq("_id","paymentReceipt");if(c("application_counters").find(counterFilter).first()==null){long highest=0;for(Document payment:c("payments").find(Filters.regex("receiptId","^PAY-[0-9]+$"))){try{highest=Math.max(highest,Long.parseLong(payment.getString("receiptId").substring(4)));}catch(NumberFormatException ignored){}}c("application_counters").updateOne(counterFilter,new Document("$setOnInsert",new Document("value",highest)),new UpdateOptions().upsert(true));}Document counter=c("application_counters").findOneAndUpdate(counterFilter,new Document("$inc",new Document("value",1L)),new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));return String.format(Locale.ROOT,"PAY-%06d",((Number)counter.get("value")).longValue());}
    private String currency(BigDecimal amount){return amount.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString();}
    private void printPaymentHistory(Iterable<Document> records){int count=0;for(Document payment:records){String billId=payment.getString("billId");Document bill=c("bills").find(Filters.eq("billId",billId)).first();String period=Objects.toString(payment.getString("period"),bill==null?"Unavailable":bill.getString("period"));String paymentDate=payment.getString("paymentDate");if(paymentDate==null){String legacyTimestamp=payment.getString("timestamp");paymentDate=legacyTimestamp==null?"Unavailable":legacyTimestamp.substring(0,Math.min(10,legacyTimestamp.length()));}String paymentStatus=Objects.toString(payment.getString("paymentStatus"),"RECORDED");if(bill!=null){String invalidReason=billValidityIssue(bill.getString("period"),bill.getString("generatedAt"),LocalDate.now(clock));if(invalidReason!=null)paymentStatus+=" (BILL INVALID: "+invalidReason+")";}System.out.println("Receipt ID: "+Objects.toString(payment.getString("receiptId"),payment.getString("paymentId"))+" | Bill ID: "+billId+" | Billing Period: "+period+" | Payment Amount: ₹"+currency(new BigDecimal(payment.getString("amount")))+" | Payment Date: "+paymentDate+" | Payment Status: "+paymentStatus);count++;}if(count==0)System.out.println("No records found.");}

    private List<BillingEngine.Slab> tariffSlabs(Document tariff) { List<BillingEngine.Slab> slabs=new ArrayList<>();for(Document s:tariff.getList("slabs",Document.class))slabs.add(new BillingEngine.Slab(s.get("upperLimit")==null?null:new BigDecimal(s.get("upperLimit").toString()),new BigDecimal(s.get("rate").toString())));return slabs; }
    private BigDecimal eligibleSubsidy(Document consumer,BigDecimal units,YearMonth period,List<BillingEngine.Slab> slabs) { BigDecimal amount=BigDecimal.ZERO; for(Document s:c("subsidies").find(Filters.eq("active",true))) { boolean cat=s.getString("category")==null||s.getString("category").equals(consumer.getString("category")); boolean window=period.atDay(1).toString().compareTo(s.getString("effectiveFrom"))>=0&&period.atEndOfMonth().toString().compareTo(s.getString("effectiveTo"))<=0; BigDecimal limit=new BigDecimal(s.getString("maxUnits")); if(cat&&window&&units.compareTo(limit)<=0) { BigDecimal schemeAmount=new BigDecimal(s.getString("amount")).min(new BigDecimal(s.getString("maximum"))); amount=amount.add(schemeAmount); } } BigDecimal firstSlabEnergy=BillingEngine.firstSlabEnergy(units,slabs);return amount.min(firstSlabEnergy); }
    private int dueDays(){return configuredDueDays(System.getenv("POWERBILL_DUE_DAYS"));}
    static int configuredDueDays(String configuredDays){try{int days=Integer.parseInt(configuredDays==null?"20":configuredDays);if(days<0)throw new NumberFormatException();return days;}catch(NumberFormatException e){throw new IllegalArgumentException("POWERBILL_DUE_DAYS must be a non-negative integer.");}}
    static LocalDate calculateDueDate(LocalDate generated,int dueDays){if(dueDays<0)throw new IllegalArgumentException("POWERBILL_DUE_DAYS must be a non-negative integer.");return generated.plusDays(dueDays);}
    static boolean billingPeriodEnded(YearMonth period,LocalDate onDate){return period.isBefore(YearMonth.from(onDate));}
    static String billValidityIssue(String periodText,String generatedAt,LocalDate today){YearMonth period;try{period=YearMonth.parse(Objects.requireNonNull(periodText));}catch(RuntimeException ex){return "Billing period is missing or invalid.";}if(!billingPeriodEnded(period,today))return "Billing period "+period+" has not ended.";LocalDate generated;try{generated=LocalDate.parse(Objects.requireNonNull(generatedAt));}catch(RuntimeException ex){return "Stored bill generation date is missing or invalid.";}if(!generated.isAfter(period.atEndOfMonth()))return "Bill was generated before billing period "+period+" ended on "+period.atEndOfMonth()+".";if(generated.isAfter(today))return "Bill generation date is in the future.";return null;}
    private void subsidies() { safe(() -> { System.out.println("1 Create demo scheme  2 List schemes  3 Check eligibility");String action=ask("Choice: ");if(action.equals("2")){print(c("subsidies").find(),"name","category","maxUnits","amount","maximum","effectiveFrom","effectiveTo","active");return;}if(action.equals("3")){String consumerId=required("Consumer ID: ");Document consumer=consumerOrThrow(consumerId);YearMonth period=billingPeriod("Billing period (YYYY-MM): ");BigDecimal units=money("Consumption units: ");Document tariff=findTariff(consumer.getString("provider"),consumer.getString("category"),period.atEndOfMonth());if(tariff==null)throw new IllegalArgumentException("No effective tariff found for provider/category/period.");BigDecimal value=eligibleSubsidy(consumer,units,period,tariffSlabs(tariff));System.out.println("Eligible first-slab subsidy for "+consumerId+" in "+period+": ₹"+value.setScale(2,java.math.RoundingMode.HALF_UP));return;}if(!action.equals("1"))throw new IllegalArgumentException("Choose a listed option.");
        String name=required("Scheme name (must be labelled DEMO): "); if(!name.toUpperCase(Locale.ROOT).contains("DEMO")) throw new IllegalArgumentException("Label demonstration schemes DEMO."); String category=ask("Category or blank for all: ").toUpperCase(Locale.ROOT);if(!category.isBlank()&&!List.of("DOMESTIC","COMMERCIAL").contains(category))throw new IllegalArgumentException("Category must be Domestic or Commercial."); BigDecimal max=money("Eligible up to units: "), amount=money("Subsidy amount: "), cap=money("Maximum subsidy amount: "); LocalDate from=date("Effective from YYYY-MM-DD: "),to=date("Effective to YYYY-MM-DD: ");if(to.isBefore(from))throw new IllegalArgumentException("Scheme end date must not precede start date.");
        String schemeId=id();c("subsidies").insertOne(new Document("schemeId",schemeId).append("name",name).append("category",category.isBlank()?null:category).append("maxUnits",max.toPlainString()).append("amount",amount.toPlainString()).append("maximum",cap.toPlainString()).append("effectiveFrom",from.toString()).append("effectiveTo",to.toString()).append("active",true).append("createdAt",LocalDate.now().toString()));audit("SUBSIDY_SCHEME_CREATED",schemeId);System.out.println("Demo scheme created."); }); }

    private void compare() { safe(() -> { String id=required("Consumer ID: "); consumerOrThrow(id); int month=Integer.parseInt(required("Month number (1-12): ")); int y1=Integer.parseInt(required("Previous year: ")),y2=Integer.parseInt(required("Current year: ")); if(month<1||month>12)throw new IllegalArgumentException("Month must be 1-12.");if((long)y2!=(long)y1+1)throw new IllegalArgumentException("Current year must be exactly one year after previous year."); BigDecimal a=historyUnits(id,YearMonth.of(y1,month)),b=historyUnits(id,YearMonth.of(y2,month)); if(a==null||b==null){System.out.println("Insufficient historical records. Previous="+a+", current="+b);return;} ConsumptionAnalytics.Comparison result=ConsumptionAnalytics.compare(a,b); System.out.println("Consumer "+id+" month "+month+": "+a+" -> "+b+" units; difference "+result.difference()+"; change "+(result.percentageChange()==null?"undefined (previous was zero)":result.percentageChange()+"%")+"; "+result.trend()); }); }
    private BigDecimal historyUnits(String id,YearMonth period){ Document d=c("consumption_history").find(Filters.and(Filters.eq("consumerId",id),Filters.eq("period",period.toString()))).first(); return d==null?null:new BigDecimal(d.getString("units")); }
    private void spikes() { safe(() -> { String input=ask("Increase threshold percent (default 50): ");BigDecimal threshold=input.isBlank()?new BigDecimal("50"):decimal(input,"threshold"); int count=0; for(Document d:c("consumption_history").find()){ YearMonth p=YearMonth.parse(d.getString("period")); BigDecimal old=historyUnits(d.getString("consumerId"),p.minusYears(1)),now=new BigDecimal(d.getString("units")); if(ConsumptionAnalytics.isSpike(old,now,threshold)){BigDecimal pct=BillingEngine.percentageChange(old,now);Document who=c("consumers").find(Filters.eq("consumerId",d.getString("consumerId"))).first();System.out.println("UNUSUAL CONSUMPTION PATTERN: "+d.getString("consumerId")+" "+(who==null?"":who.getString("name"))+" previous="+old+" current="+now+" increase="+pct+"% threshold="+threshold+"%. Investigate.");count++;}} System.out.println("Spike warnings: "+count); }); }

    private void tariffs() { safe(() -> {
        System.out.println("1 List tariffs  2 Add tariff version  3 List configured providers  4 Add provider  5 Retire tariff version  6 Tariff retirement history");
        switch(ask("Choice: ")){
            case "1" -> print(c("tariffs").find().sort(Sorts.descending("effectiveFrom")),"tariffId","provider","category","name","effectiveFrom","effectiveTo","financialYear","source");
            case "3" -> print(c("electricity_providers").find(),"providerId","name","region","integration");
            case "4" -> {String name=required("Provider ID/name: ").toUpperCase(Locale.ROOT);if(!name.matches("[A-Z0-9_-]{2,24}"))throw new IllegalArgumentException("Use 2-24 letters, digits, underscore or hyphen.");String region=required("Region: ");if(c("electricity_providers").countDocuments(Filters.eq("providerId",name))>0)throw new IllegalArgumentException("Provider already exists.");c("electricity_providers").insertOne(new Document("providerId",name).append("name",name).append("region",region).append("integration","No live account integration").append("createdAt",LocalDate.now().toString()));System.out.println("Provider configured. No customer-account integration was created.");}
            case "5" -> retireTariff();
            case "6" -> print(c("tariff_retirements").find().sort(Sorts.descending("retiredAt")),"tariffId","effectiveTo","reason","retiredAt");
            case "2" -> {
                String provider=required("Provider: ").toUpperCase(Locale.ROOT);
                if(c("electricity_providers").countDocuments(Filters.eq("providerId",provider))==0)throw new IllegalArgumentException("Add/configure a provider before its tariff.");
                String category=category(),name=required("Tariff name: ");
                LocalDate from=date("Effective from: "); String toText=ask("Effective to (YYYY-MM-DD, blank for open-ended): "); LocalDate to=toText.isBlank()?null:LocalDate.parse(toText); if(to!=null&&to.isBefore(from))throw new IllegalArgumentException("Expiry date must not be before the effective date.");ensureTariffRangeAvailable(provider,category,from,to);
                BigDecimal fixed=money("Fixed charge: "),additional=money("Additional charge: "),adjustment=signedDecimal(ask("Adjustment (positive or negative; enter 0 for none): "),"adjustment"); List<BillingEngine.Slab> calculatedSlabs=new ArrayList<>(); List<Document> slabs=new ArrayList<>();
                int n=Integer.parseInt(required("Number of slabs: ")); if(n<1||n>20)throw new IllegalArgumentException("Choose 1 to 20 slabs.");
                for(int i=0;i<n;i++){String lim=ask("Slab "+(i+1)+" upper units (blank = unlimited): "); BigDecimal upper=lim.isBlank()?null:decimal(lim,"Upper limit"),rate=money("Rate per unit: "); calculatedSlabs.add(new BillingEngine.Slab(upper,rate)); slabs.add(new Document("upperLimit",upper==null?null:upper.toPlainString()).append("rate",rate.toPlainString()));}
                BillingEngine.calculate(new BigDecimal("1000000000"),calculatedSlabs,fixed,additional,BigDecimal.ZERO);
                String source=ask("Source publication/reference (blank means demo): "); if(source.isBlank()){if(!name.toUpperCase(Locale.ROOT).contains("DEMO"))throw new IllegalArgumentException("Unverified rates must have DEMO in the tariff name.");source="DEMONSTRATION ONLY; NOT OFFICIAL PROVIDER RATES";}
                c("tariffs").insertOne(new Document("tariffId",id()).append("provider",provider).append("category",category).append("name",name).append("type","PROGRESSIVE").append("slabs",slabs).append("fixedCharge",fixed.toPlainString()).append("additionalCharge",additional.toPlainString()).append("adjustment",adjustment.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString()).append("effectiveFrom",from.toString()).append("effectiveTo",to==null?null:to.toString()).append("financialYear",from.getYear()+"-"+(from.getYear()+1)).append("source",source).append("lastUpdated",LocalDate.now().toString()));
                System.out.println("New immutable tariff version added.");
            }
            default -> System.out.println("Choose a listed option.");
        }
    }); }
    private void ensureTariffRangeAvailable(String provider,String category,LocalDate from,LocalDate to){
        LocalDate newEnd=to==null?LocalDate.MAX:to;
        for(Document existing:c("tariffs").find(Filters.and(Filters.eq("provider",provider),Filters.eq("category",category)))){
            LocalDate existingStart=LocalDate.parse(existing.getString("effectiveFrom"));String endText=existing.getString("effectiveTo");LocalDate existingEnd=endText==null?LocalDate.MAX:LocalDate.parse(endText);
            if(!from.isAfter(existingEnd)&&!newEnd.isBefore(existingStart))throw new IllegalArgumentException("Tariff effective dates overlap an existing version for this provider and category.");
        }
    }
    private void retireTariff(){
        String tariffId=required("Tariff ID to retire: ");Document tariff=c("tariffs").find(Filters.eq("tariffId",tariffId)).first();if(tariff==null)throw new IllegalArgumentException("Tariff version not found.");
        LocalDate start=LocalDate.parse(tariff.getString("effectiveFrom")),retirement=date("Last effective date (YYYY-MM-DD): ");if(retirement.isBefore(start))throw new IllegalArgumentException("Retirement date must not precede the effective date.");
        String currentEnd=tariff.getString("effectiveTo");if(currentEnd!=null&&!retirement.isBefore(LocalDate.parse(currentEnd)))throw new IllegalArgumentException("Retirement can shorten an existing effective range; it cannot extend it.");
        String reason=required("Retirement reason: ");if(!ask("Type RETIRE to close this tariff version: ").equals("RETIRE")){System.out.println("Retirement cancelled.");return;}
        String retiredAt=java.time.Instant.now().toString();c("tariff_retirements").insertOne(new Document("retirementId",id()).append("tariffId",tariffId).append("effectiveTo",retirement.toString()).append("reason",reason).append("beforeSnapshot",new Document(tariff)).append("retiredAt",retiredAt));tariff.put("effectiveTo",retirement.toString());tariff.put("retiredAt",retiredAt);tariff.put("retirementReason",reason);c("tariffs").replaceOne(Filters.eq("tariffId",tariffId),tariff);audit("TARIFF_RETIRED",tariffId+" through "+retirement+" reason="+reason);System.out.println("Tariff retired effective "+retirement+". Its rates and existing bill snapshots remain preserved.");
    }
    private void seedProvidersIfMissing(){
        for(String name:List.of("BESCOM","MESCOM","HESCOM","GESCOM","CESC"))
            c("electricity_providers").updateOne(Filters.eq("providerId",name),new Document("$setOnInsert",new Document("providerId",name).append("name",name).append("region","Karnataka").append("integration","No live account integration").append("createdAt",LocalDate.now().toString())),new com.mongodb.client.model.UpdateOptions().upsert(true));
    }
    private void seedDemoTariffIfEmpty(){ if(c("tariffs").countDocuments()==0){ List<Document> slabs=List.of(new Document("upperLimit","100").append("rate","2.00"),new Document("upperLimit","300").append("rate","4.00"),new Document("upperLimit",null).append("rate","6.00")); for(String cat:List.of("DOMESTIC","COMMERCIAL"))c("tariffs").insertOne(new Document("tariffId",id()).append("provider","BESCOM").append("category",cat).append("name","DEMO starter tariff (not official)").append("type","PROGRESSIVE").append("slabs",slabs).append("fixedCharge","50.00").append("additionalCharge","0.00").append("adjustment","0.00").append("effectiveFrom","2000-01-01").append("effectiveTo",null).append("financialYear","DEMO").append("source","DEMONSTRATION ONLY; NOT OFFICIAL BESCOM RATES").append("lastUpdated",LocalDate.now().toString())); } }

    private void reports(){safe(() -> {
        System.out.println("1 Consumers 2 Bills 3 Overdue 4 Payments 5 Consumption 6 Subsidy schemes 7 Billing summary 8 Paid/unpaid bills 9 Applied subsidy history");
        switch(ask("Choice: ")){
            case "1" -> print(c("consumers").find(),"consumerId","name","category","provider","status");
            case "2" -> print(c("bills").find().sort(Sorts.descending("period")),"billId","consumerId","period","units","total","status");
            case "3" -> print(c("bills").find(Filters.or(Filters.eq("status","OVERDUE"),Filters.and(Filters.ne("status","PAID"),Filters.lt("dueDate",LocalDate.now().toString())))),"billId","consumerId","dueDate","total","status");
            case "4" -> print(c("payments").find().sort(Sorts.descending("timestamp")),"receiptId","billId","period","amount","paymentDate","paymentStatus");
            case "5" -> print(c("consumption_history").find().sort(Sorts.descending("period")),"consumerId","period","units");
            case "6" -> print(c("subsidies").find(),"name","category","amount","maximum","effectiveFrom","effectiveTo");
            case "7" -> {long bills=c("bills").countDocuments();BigDecimal units=BigDecimal.ZERO,amount=BigDecimal.ZERO,paidTotal=BigDecimal.ZERO;for(Document b:c("bills").find()){units=units.add(new BigDecimal(b.getString("units")));amount=amount.add(new BigDecimal(b.getString("total")));paidTotal=paidTotal.add(paid(b.getString("billId")));}System.out.println("Bills: "+bills+" | Units: "+units+" | Billed: ₹"+amount+" | Payments: ₹"+paidTotal+" | Outstanding: ₹"+amount.subtract(paidTotal).max(BigDecimal.ZERO));}
            case "8" -> {String state=required("Status (PAID, UNPAID, PARTIALLY_PAID, PARTIAL, OVERDUE): ").toUpperCase(Locale.ROOT);print(c("bills").find(state.equals("PARTIALLY_PAID")?Filters.in("status","PARTIALLY_PAID","PARTIAL"):Filters.eq("status",state)),"billId","consumerId","period","total","status");}
            case "9" -> print(c("subsidy_history").find().sort(Sorts.descending("createdAt")),"consumerId","billId","period","amount","createdAt");
            default -> System.out.println("Choose a listed option.");
        }
    });}
    private void status(){System.out.println("MongoDB connected | database="+store.databaseName()); for(String s:List.of("consumers","electricity_providers","tariffs","meter_readings","bills","payments","subsidies","subsidy_history","consumption_history","audit_logs"))System.out.println(s+": "+c(s).countDocuments());}
    private void audit(String action,String entity){c("audit_logs").insertOne(new Document("action",action).append("entity",entity).append("createdAt",java.time.Instant.now().toString()));}
    private void print(Iterable<Document> rows,String...fields){int n=0;for(Document d:rows){StringBuilder b=new StringBuilder();for(String f:fields)b.append(f).append('=').append(d.get(f)).append(" | ");System.out.println(b);n++;}if(n==0)System.out.println("No records found.");}

    private void samples(){safe(() -> {
        if(c("consumers").countDocuments(Filters.eq("sampleData",true))>0){System.out.println("Simulated sample data already exists; no duplicates added.");return;}
        String[] patterns={"STABLE","INCREASING","DECREASING","SEASONAL","SPIKE"}; int latestCompletedYear=LocalDate.now().getYear()-1;
        for(int i=0;i<30;i++){
            String cid="SIM-"+String.format("%03d",i+1), pattern=patterns[i%patterns.length],cat=i%3==0?"COMMERCIAL":"DOMESTIC";
            c("consumers").insertOne(new Document("consumerId",cid).append("name","SIMULATED SAMPLE DATA Consumer "+(i+1)).append("address","Demonstration address "+(i+1)).append("phone","900000"+String.format("%04d",i)).append("category",cat).append("provider","BESCOM").append("tariffCategory",cat).append("registrationDate",LocalDate.now().toString()).append("status","ACTIVE").append("sampleData",true));
            for(int yearOffset=1;yearOffset>=0;yearOffset--) for(int m=1;m<=12;m++){
                boolean latest=yearOffset==0; int units=switch(pattern){case "INCREASING"->latest?150+m*10:100+m*5;case "DECREASING"->latest?180-m*5:300-m*8;case "SEASONAL"->150+(m%3)*80;case "SPIKE"->latest&&m==8?360:100;default->latest?185:180;};
                String period=YearMonth.of(latestCompletedYear-yearOffset,m).toString();
                Document r=new Document("readingId",id()).append("consumerId",cid).append("period",period).append("previous","0").append("current",Integer.toString(units)).append("units",Integer.toString(units)).append("note","SIMULATED SAMPLE DATA: "+pattern).append("createdAt",LocalDate.now().toString());
                c("meter_readings").insertOne(r);c("consumption_history").insertOne(new Document(r));
            }
        }
        System.out.println("Inserted 30 consumers and 720 monthly history records across two completed years. SIMULATED SAMPLE DATA.");
    });}

    private void csvMenu(){safe(() -> {
        System.out.println("1 Export consumers  2 Import consumers  3 Export readings  4 Import readings  5 Export tariffs  6 Import tariffs  7 Export bills  8 Export consumption history  9 Import consumption history  10 Export report CSV");
        String action=ask("Choice: "); Path path=Path.of(required("CSV path: "));
        switch(action){case "1"->exportCsv(path,"consumers",List.of("consumerId","name","address","phone","category","provider","status"));case "3"->exportCsv(path,"meter_readings",List.of("consumerId","period","previous","current","units","note"));case "5"->exportTariffs(path);case "7"->exportCsv(path,"bills",List.of("billId","consumerId","consumerName","provider","category","period","units","energy","fixed","additional","adjustment","subsidy","lateFee","total","dueDate","status"));case "8"->exportCsv(path,"consumption_history",List.of("consumerId","period","units","note"));case "2"->importConsumers(path);case "4"->importReadings(path);case "6"->importTariffs(path);case "9"->importConsumptionHistory(path);case "10"->exportReportCsv(path);default->throw new IllegalArgumentException("Choose a listed option.");}
    });}
    private void exportReportCsv(Path path)throws IOException{
        System.out.println("1 Consumers 2 Bills 3 Overdue 4 Payments 5 Consumption 6 Subsidy schemes 7 Billing summary 8 Paid/unpaid bills 9 Applied subsidy history");String choice=required("Report number to export: ");
        switch(choice){
            case "1"->exportCsv(path,"consumers",List.of("consumerId","name","address","phone","category","provider","status"));
            case "2"->exportCsv(path,"bills",List.of("billId","consumerId","consumerName","provider","category","period","units","energy","fixed","additional","adjustment","subsidy","lateFee","total","dueDate","status"));
            case "3"->exportRows(path,"overdue bills",c("bills").find(Filters.or(Filters.eq("status","OVERDUE"),Filters.eq("status","PARTIAL_OVERDUE"))),List.of("billId","consumerId","period","total","dueDate","status"));
            case "4"->exportCsv(path,"payments",List.of("paymentId","receiptId","billId","consumerId","period","amount","paymentDate","paymentStatus","timestamp","paymentReference"));
            case "5"->exportCsv(path,"consumption_history",List.of("consumerId","period","units","note"));
            case "6"->exportCsv(path,"subsidies",List.of("schemeId","name","category","maxUnits","amount","maximum","effectiveFrom","effectiveTo","active"));
            case "7"->{long billCount=c("bills").countDocuments();BigDecimal units=BigDecimal.ZERO,amount=BigDecimal.ZERO,paidAmount=BigDecimal.ZERO;for(Document bill:c("bills").find()){units=units.add(new BigDecimal(bill.getString("units")));amount=amount.add(new BigDecimal(bill.getString("total")));paidAmount=paidAmount.add(paid(bill.getString("billId")));}Document summary=new Document("billCount",Long.toString(billCount)).append("units",units.toPlainString()).append("billed",amount.toPlainString()).append("paid",paidAmount.toPlainString()).append("outstanding",amount.subtract(paidAmount).max(BigDecimal.ZERO).toPlainString());exportRows(path,"billing summary",List.of(summary),List.of("billCount","units","billed","paid","outstanding"));}
            case "8"->{String status=required("Status (PAID, UNPAID, PARTIAL, OVERDUE): ").toUpperCase(Locale.ROOT);exportRows(path,"bills with status "+status,c("bills").find(Filters.eq("status",status)),List.of("billId","consumerId","period","total","status"));}
            case "9"->exportCsv(path,"subsidy_history",List.of("recordId","consumerId","billId","period","amount","createdAt"));
            default->throw new IllegalArgumentException("Choose a listed report.");
        }
    }
    private void exportCsv(Path path,String collection,List<String> fields)throws IOException{
        exportRows(path,collection,c(collection).find(),fields);
    }
    private void exportRows(Path path,String label,Iterable<Document> rows,List<String> fields)throws IOException{try(BufferedWriter writer=Files.newBufferedWriter(path)){writer.write(csvRow(fields));writer.newLine();for(Document doc:rows){List<String> values=new ArrayList<>();for(String field:fields)values.add(doc.get(field)==null?"":doc.get(field).toString());writer.write(csvRow(values));writer.newLine();}}System.out.println("Exported "+label+" to "+path);}
    private void exportTariffs(Path path)throws IOException{
        List<String> fields=List.of("provider","category","name","effectiveFrom","effectiveTo","fixedCharge","additionalCharge","adjustment","source","slabsJson");
        try(BufferedWriter writer=Files.newBufferedWriter(path)){writer.write(csvRow(fields));writer.newLine();for(Document d:c("tariffs").find()){String slabs=new Document("slabs",d.getList("slabs",Document.class)).toJson();writer.write(csvRow(List.of(d.getString("provider"),d.getString("category"),d.getString("name"),d.getString("effectiveFrom"),Objects.toString(d.getString("effectiveTo"),""),d.getString("fixedCharge"),d.getString("additionalCharge"),Objects.toString(d.getString("adjustment"),"0.00"),Objects.toString(d.getString("source"),""),slabs)));writer.newLine();}}
        System.out.println("Exported tariff configurations to "+path);
    }
    private List<String> readCsv(Path path)throws IOException{return Files.readAllLines(path);}
    private List<String> parseCsvRow(String line){List<String> cells=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;for(int i=0;i<line.length();i++){char ch=line.charAt(i);if(ch=='"'){if(quoted&&i+1<line.length()&&line.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}else if(ch==','&&!quoted){cells.add(cell.toString());cell.setLength(0);}else cell.append(ch);}if(quoted)throw new IllegalArgumentException("CSV row has an unmatched quote.");cells.add(cell.toString());return cells;}
    private String csvRow(List<String> values){return values.stream().map(v->"\""+Objects.toString(v,"").replace("\"","\"\"")+"\"").collect(java.util.stream.Collectors.joining(","));}
    private List<String> csvDataRows(Path path,String expectedHeader)throws IOException{List<String> lines=readCsv(path);if(lines.isEmpty())throw new IllegalArgumentException("CSV file is empty.");List<String> actual=parseCsvRow(lines.getFirst());boolean valid=actual.equals(parseCsvRow(expectedHeader));if(expectedHeader.equals("consumerId,period,previous,current"))valid|=actual.equals(parseCsvRow("consumerId,period,previous,current,units,note"));if(!valid)throw new IllegalArgumentException("CSV header must be: "+expectedHeader);return lines.subList(1,lines.size());}
    private void importConsumers(Path path)throws IOException{
        int ok=0,failed=0,row=1;for(String line:csvDataRows(path,"consumerId,name,address,phone,category,provider,status")){row++;try{List<String>x=parseCsvRow(line);if(x.size()!=7)throw new IllegalArgumentException("expected 7 columns");String consumerId=x.get(0).trim(),name=x.get(1).trim(),address=x.get(2).trim(),phone=x.get(3).trim(),category=x.get(4).trim().toUpperCase(Locale.ROOT),provider=x.get(5).trim().toUpperCase(Locale.ROOT),status=x.get(6).trim().toUpperCase(Locale.ROOT);if(List.of(consumerId,name,address,phone,provider).stream().anyMatch(String::isBlank))throw new IllegalArgumentException("required consumer fields are blank");validatePhone(phone);if(!List.of("DOMESTIC","COMMERCIAL").contains(category))throw new IllegalArgumentException("invalid category");if(c("electricity_providers").countDocuments(Filters.eq("providerId",provider))==0)throw new IllegalArgumentException("unknown provider");if(!status.isBlank()&&!List.of("ACTIVE","INACTIVE").contains(status))throw new IllegalArgumentException("invalid account status");if(c("consumers").countDocuments(Filters.eq("consumerId",consumerId))>0)throw new IllegalArgumentException("duplicate consumer ID");c("consumers").insertOne(new Document("consumerId",consumerId).append("name",name).append("address",address).append("phone",phone).append("category",category).append("provider",provider).append("tariffCategory",category).append("registrationDate",LocalDate.now().toString()).append("status",status.isBlank()?"ACTIVE":status).append("imported",true));ok++;}catch(Exception e){failed++;System.out.println("Row "+row+" failed: "+e.getMessage());}}System.out.println("Consumer import succeeded: "+ok+"; failed: "+failed);
    }
    private void importReadings(Path path)throws IOException{
        int ok=0,failed=0,row=1;for(String line:csvDataRows(path,"consumerId,period,previous,current")){row++;try{List<String>x=parseCsvRow(line);if(x.size()!=4&&x.size()!=6)throw new IllegalArgumentException("expected 4 import columns or 6 exported reading columns");String consumer=x.get(0).trim();consumerOrThrow(consumer);YearMonth period=YearMonth.parse(x.get(1).trim());BigDecimal previous=decimal(x.get(2).trim(),"previous"),current=decimal(x.get(3).trim(),"current");if(x.size()==4&&current.compareTo(previous)<0)throw new IllegalArgumentException("current reading is below previous reading");BigDecimal units=x.size()==6?decimal(x.get(4).trim(),"units"):current.subtract(previous);String note=x.size()==6?x.get(5):"CSV import";Document d=new Document("readingId",id()).append("consumerId",consumer).append("period",period.toString()).append("previous",previous.toPlainString()).append("current",current.toPlainString()).append("units",units.toPlainString()).append("note",note).append("createdAt",LocalDate.now().toString());c("meter_readings").insertOne(d);c("consumption_history").replaceOne(Filters.and(Filters.eq("consumerId",consumer),Filters.eq("period",period.toString())),new Document(d),new com.mongodb.client.model.ReplaceOptions().upsert(true));ok++;}catch(Exception e){failed++;System.out.println("Row "+row+" failed: "+e.getMessage());}}System.out.println("Meter reading import succeeded: "+ok+"; failed: "+failed);
    }
    private void importConsumptionHistory(Path path)throws IOException{
        int ok=0,failed=0,row=1;for(String line:csvDataRows(path,"consumerId,period,units,note")){row++;try{List<String>x=parseCsvRow(line);if(x.size()!=4)throw new IllegalArgumentException("expected 4 columns");String consumer=x.get(0).trim();consumerOrThrow(consumer);YearMonth period=YearMonth.parse(x.get(1).trim());BigDecimal units=decimal(x.get(2).trim(),"units");if(c("consumption_history").countDocuments(Filters.and(Filters.eq("consumerId",consumer),Filters.eq("period",period.toString())))>0)throw new IllegalArgumentException("duplicate consumer and period history");c("consumption_history").insertOne(new Document("consumerId",consumer).append("period",period.toString()).append("units",units.toPlainString()).append("note",x.get(3)).append("imported",true).append("createdAt",java.time.Instant.now().toString()));ok++;}catch(Exception e){failed++;System.out.println("Row "+row+" failed: "+e.getMessage());}}System.out.println("Consumption history import succeeded: "+ok+"; failed: "+failed);
    }
    private void importTariffs(Path path)throws IOException{
        int ok=0,failed=0,row=1;String header="provider,category,name,effectiveFrom,effectiveTo,fixedCharge,additionalCharge,adjustment,source,slabsJson";
        for(String line:csvDataRows(path,header)){row++;try{List<String>x=parseCsvRow(line);if(x.size()!=10)throw new IllegalArgumentException("expected 10 columns");String provider=x.get(0).trim().toUpperCase(Locale.ROOT),category=x.get(1).trim().toUpperCase(Locale.ROOT),name=x.get(2).trim();if(c("electricity_providers").countDocuments(Filters.eq("providerId",provider))==0)throw new IllegalArgumentException("unknown provider");if(!List.of("DOMESTIC","COMMERCIAL").contains(category))throw new IllegalArgumentException("invalid category");List<Document> slabs=Document.parse(x.get(9)).getList("slabs",Document.class);List<BillingEngine.Slab> domain=new ArrayList<>();for(Document s:slabs)domain.add(new BillingEngine.Slab(s.get("upperLimit")==null?null:new BigDecimal(s.get("upperLimit").toString()),new BigDecimal(s.get("rate").toString())));LocalDate from=LocalDate.parse(x.get(3).trim());String toText=x.get(4).trim();LocalDate to=toText.isBlank()?null:LocalDate.parse(toText);if(to!=null&&to.isBefore(from))throw new IllegalArgumentException("invalid effective range");ensureTariffRangeAvailable(provider,category,from,to);BigDecimal fixed=decimal(x.get(5).trim(),"fixed charge"),additional=decimal(x.get(6).trim(),"additional charge"),adjustment=signedDecimal(x.get(7).trim(),"adjustment");BillingEngine.calculate(new BigDecimal("1000000000"),domain,fixed,additional,adjustment,BigDecimal.ZERO);String source=x.get(8).trim();if(source.isBlank())throw new IllegalArgumentException("source reference is required");Document tariff=new Document("tariffId",id()).append("provider",provider).append("category",category).append("name",name).append("type","PROGRESSIVE").append("slabs",slabs).append("fixedCharge",fixed.toPlainString()).append("additionalCharge",additional.toPlainString()).append("adjustment",adjustment.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString()).append("effectiveFrom",from.toString()).append("effectiveTo",to==null?null:to.toString()).append("financialYear",from.getYear()+"-"+(from.getYear()+1)).append("source",source).append("lastUpdated",LocalDate.now().toString());c("tariffs").insertOne(tariff);ok++;}catch(Exception e){failed++;System.out.println("Row "+row+" failed: "+e.getMessage());}}System.out.println("Tariff import succeeded: "+ok+"; failed: "+failed);
    }
}
