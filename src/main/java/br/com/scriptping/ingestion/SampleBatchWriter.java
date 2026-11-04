package br.com.scriptping.ingestion;

import java.util.List;

interface SampleBatchWriter {

    record Row(String hostId, String target, long measuredAt, Double latency, String error,
        double latencyLimit, String collectorId, long attemptId, long batchId, long sequence,
        long scheduledAt, long sentAt, long receivedAt, long rttNs, String status, String errorCode) {}

    void insertSamples(List<Row> rows);
}
