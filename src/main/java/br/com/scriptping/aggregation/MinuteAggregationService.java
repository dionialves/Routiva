package br.com.scriptping.aggregation;

import java.util.*;
import org.springframework.stereotype.Service;
import br.com.scriptping.aggregation.MinuteAggregationRepository.Key;
import br.com.scriptping.aggregation.MinuteAggregationRepository.Summary;
import br.com.scriptping.aggregation.MinuteAggregationRepository.Progress;
import br.com.scriptping.aggregation.MinuteAggregationRepository.Batch;

/** Aggregation orchestration; delegates persistence to the repository. */
@Service
public class MinuteAggregationService {

    private final MinuteAggregationRepository repository;

    public MinuteAggregationService(MinuteAggregationRepository repository) {
        this.repository = repository;
    }

    public Progress progress() {
        return repository.progress();
    }

    public Batch aggregate(long boundary, int pageSize, long now) {
        return repository.aggregate(boundary, pageSize, now);
    }

    public Map<Key, Summary> totals(long from, long to, List<br.com.scriptping.Host> hosts) {
        return repository.totals(from, to, hosts);
    }

    public Map<Long, Summary> series(long from, long to, List<br.com.scriptping.Host> hosts) {
        return repository.series(from, to, hosts);
    }
}
