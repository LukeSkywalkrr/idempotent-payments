package dev.pratyush.idempotency;

record PaymentRequest(long amountCents, String currency) {
    String canonicalForm() {
        return amountCents + ":" + currency.toLowerCase();
    }
}

record StoredResponse(int statusCode, String body) {}

record PaymentResponse(int statusCode, String body, boolean replayed) {}

record BenchmarkResult(
        String mode,
        int requests,
        int successfulResponses,
        int createdCharges,
        int replayedResponses,
        int conflicts,
        long elapsedMillis) {}
