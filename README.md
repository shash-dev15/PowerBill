# CP-071 PowerBill: Slab-Based Utility Billing Engine

# Team
- Shashank S (1AT24CS201)
- Sujan PS (1AT24CS220)

# How to run
**Requirements:** Java 21, Apache Maven 3.10 or compatible, and MongoDB Community Server.

1. Clone the repository and enter the project directory:

   ```bash
   git clone https://github.com/shash-dev15/PowerBill.git
   cd PowerBill
   ```

2. Start MongoDB. By default, the application connects to `mongodb://127.0.0.1:27017` and uses the `powerbill_db` database.

3. Run the automated tests and package the application:

   ```bash
   mvn test
   mvn package
   ```

   MongoDB must be running for the MongoDB connectivity, CRUD, and end-to-end tests.

4. Start the application:

   ```bash
   java -jar target/powerbill-1.0.0.jar
   ```

To use a separate database, set the connection values before starting the application. In PowerShell:

```powershell
$env:POWERBILL_MONGODB_URI = 'mongodb://127.0.0.1:27017'
$env:POWERBILL_DATABASE = 'powerbill_demo'
java -jar target/powerbill-1.0.0.jar
```

The application also reads `src/main/resources/application.properties` if present. Environment variables take precedence over its `mongodb.uri` and `mongodb.database` values. Copy `application.properties.example` to that location to configure local defaults.

# Features that work

- **Consumer management:** Register, list, search, update, deactivate, and view bill history for consumers.
- **Meter readings:** Record monthly readings, view history, calculate normal, meter-replacement, and configured rollover usage, and reject duplicate consumer-period readings.
- **Bill generation and correction:** Calculate progressive tariff slabs with fixed charges, additional charges, signed adjustments, and eligible first-slab subsidies. View bills and correct eligible issued bills with a reason, confirmation, and audit history. Bills retain the tariff used for their calculation.
- **Payments and late fees:** Record full or partial payments, track balances and receipt numbers, view payment history, and apply configurable demonstration late fees once to overdue bills.
- **Consumption analysis:** Compare the same month across consecutive years and flag increases above a configurable spike threshold.
- **Subsidies:** Create and check demonstration schemes with consumer-category, unit, amount, and effective-date limits.
- **Tariffs and providers:** List and configure providers, add effective-dated tariff versions, retire versions, and view retirement history.
- **Reports and CSV:** View billing and payment reports; import consumers, readings, tariffs, and consumption history; export those records and supported billing, payment, subsidy, and consumption reports.
- **Sample data and database status:** Load tagged simulated data (30 consumers and 720 monthly records) and view MongoDB collection counts.
- **Data integrity and calculations:** MongoDB indexes prevent duplicate consumer IDs, readings, bills, payment receipts, and other keyed records. Billing calculations use `BigDecimal` and round currency to two decimal places.

These workflows are covered by the project's billing, business-rule, MongoDB, and console end-to-end tests. MongoDB is required to run the database-dependent tests. Tariff rates, subsidy schemes, and late-fee settings supplied for demonstration are not official provider policies.

# Sample run

The following is the application's menu output from `Main` when MongoDB is available. Entering `0` exits.

```text
================================================
        POWERBILL
SLAB-BASED UTILITY BILLING ENGINE
================================================
1. Consumer Management
2. Meter Reading Management
3. Electricity Bill Generation
4. Payment Management
5. Late Payment Management
6. Yearly Consumption Comparison
7. Subsidy Management
8. Consumption Spike Detection
9. Tariff Management
10. Reports and Bill History
11. CSV Import / Export
12. Load Sample Data
13. Database Status
0. Exit
Choice: 0
Goodbye.
```
