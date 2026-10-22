package br.com.scriptping;

import java.util.*;
import static br.com.scriptping.PacketLossAnalysis.*;

/** Pure analysis of completed samples; collection gaps never become packet losses. */
public class PacketLossAnalyzer {
    private final PacketLossPolicy rules;
    private final long interval,maxGap,minuteMs,hourMs,bucketMs,recoveryMs;
    public PacketLossAnalyzer(PacketLossPolicy rules,long intervalMs,long timeoutMs) {
        rules.validate();
        if(intervalMs<1 || timeoutMs<1) throw new IllegalArgumentException("Intervalo e timeout devem ser positivos.");
        this.rules=rules;interval=intervalMs;
        maxGap=Math.max((long)(intervalMs*rules.getMaxGapMultiplier()),intervalMs+timeoutMs);
        minuteMs=rules.getMinuteWindowSeconds()*1000;hourMs=rules.getHourWindowSeconds()*1000;
        bucketMs=rules.getRecurrenceBucketSeconds()*1000;recoveryMs=rules.getRecoverySeconds()*1000;
    }
    private long operationalLookbackMs(){return Math.max(hourMs,Math.max(recoveryMs,bucketMs*(rules.getRecurrenceBuckets()+1)));}
    public long lookbackMs(){return Math.max(8*3600000L,operationalLookbackMs());}
    private class Counts {
        final long start,end;long attempts,losses,successes,all,previous=-1,coverage;double sum;
        Counts(long start,long end){this.start=start;this.end=end;}
        void add(long time,byte outcome,double latency) {
            if(time<=start || time>end) return;
            all++;
            if(outcome==PingSample.ERROR){previous=-1;return;}
            attempts++;if(outcome==PingSample.LOSS)losses++;else{successes++;sum+=latency;}
            long gap=previous<0?interval:time-previous;
            coverage+=gap>=0 && gap<=maxGap?Math.min(gap,time-start):Math.min(interval,time-start);
            previous=time;
        }
        boolean enough(){return attempts>=rules.getMinimumSamples() && Math.min(1.0,coverage/(double)(end-start))>=rules.getMinimumCoverageRatio();}
        Double mean(){return successes==0?null:sum/successes;}
        double equivalentLosses(){return attempts==0?0:losses*(double)rules.getMinuteReferencePackets()/attempts;}
    }
    private Counts counts(RollingPingHistory history,long start,long end) {
        var counts=new Counts(start,end);
        // Histories are ordered, including late results. No database reads are needed.
        int lo=0,hi=history.size();
        while(lo<hi){int mid=(lo+hi)>>>1;if(history.time(mid)<=start)lo=mid+1;else hi=mid;}
        for(int i=lo;i<history.size() && history.time(i)<=end;i++) counts.add(history.time(i),history.outcome(i),history.latency(i));
        return counts;
    }
    private Quality minuteQuality(Counts counts) {
        if(!counts.enough())return Quality.DADOS_INSUFICIENTES;
        if(counts.losses==0)return Quality.NORMAL;
        double value=counts.equivalentLosses();
        if(value>=rules.getMinuteCriticalLosses())return Quality.CRITICO;
        if(value>=rules.getMinuteDegradedLosses())return Quality.DEGRADADO;
        if(value>=rules.getMinuteAttentionLosses())return Quality.ATENCAO;
        return Quality.EVENTO_ISOLADO;
    }
    public Quality hourQuality(long losses,long attempts,boolean sufficient) {
        if(!sufficient || attempts==0)return Quality.DADOS_INSUFICIENTES;
        double percentage=100.0*losses/attempts;
        if(percentage>=rules.getHourCriticalMinPercent())return Quality.CRITICO;
        if(percentage>=rules.getHourDegradedMinPercent())return Quality.DEGRADADO;
        return percentage>rules.getHourNormalMaxPercent()?Quality.ATENCAO:Quality.NORMAL;
    }
    private boolean recurring(Map<Long,Counts> buckets,long nextBucket) {
        int qualifying=0;
        for(int i=1;i<=rules.getRecurrenceBuckets();i++) {
            var counts=buckets.get(nextBucket-i);
            if(counts!=null && counts.enough() && counts.equivalentLosses()>=rules.getRecurrenceBucketLosses())qualifying++;
        }
        return qualifying>=rules.getRecurrenceRequiredBuckets();
    }
    public PacketLossAnalysis analyze(RollingPingHistory history,long now) {
        Counts minute=new Counts(now-minuteMs,now),hour=new Counts(now-hourMs,now);
        Counts eightHours=counts(history,now-8*3600000L,now),latencyHour=counts(history,now-3600000L,now),fiveMinutes=counts(history,now-300000L,now);
        int firstIndex=0;
        while(firstIndex<history.size() && history.time(firstIndex)<now-operationalLookbackMs())firstIndex++;
        Map<Long,Counts> buckets=new HashMap<>();
        int currentRun=0,minuteRun=0,hourRun=0,maxMinute=0,maxHour=0;
        int lastIndex=-1;
        long previousBucketKey=Long.MIN_VALUE;Counts currentBucket=null;
        for(int i=firstIndex;i<history.size();i++) {
            long time=history.time(i);if(time>now)break;lastIndex=i;
            byte outcome=history.outcome(i);double latency=history.latency(i);
            minute.add(time,outcome,latency);hour.add(time,outcome,latency);
            long bucket=Math.floorDiv(time-1,bucketMs);
            if(bucket!=previousBucketKey){currentBucket=buckets.computeIfAbsent(bucket,k->new Counts(k*bucketMs,(k+1)*bucketMs));previousBucketKey=bucket;}
            currentBucket.add(time,outcome,latency);
            currentRun=outcome==PingSample.LOSS?currentRun+1:0;
            hourRun=time>now-hourMs && outcome==PingSample.LOSS?hourRun+1:0;
            minuteRun=time>now-minuteMs && outcome==PingSample.LOSS?minuteRun+1:0;
            maxMinute=Math.max(maxMinute,minuteRun);maxHour=Math.max(maxHour,hourRun);
        }
        Level level=Level.NORMAL;
        Long cleanSince=null;long previous=-1,lastSuccess=-1;
        int run=0;long nextBucket=lastIndex<0?0:Math.floorDiv(history.time(firstIndex)-1,bucketMs)+1;
        for(int i=firstIndex;i<=lastIndex;i++) {
            long time=history.time(i);
            while(nextBucket*bucketMs<=time) {
                if(level==Level.NORMAL && recurring(buckets,nextBucket))level=Level.DEGRADADO;
                nextBucket++;
            }
            byte outcome=history.outcome(i);
            if(outcome==PingSample.SUCCESS) {
                run=0;
                if(cleanSince==null || previous<0 || time-previous>maxGap) cleanSince=time;
                lastSuccess=time;
                if(time-cleanSince>=recoveryMs)level=Level.NORMAL;
            } else {
                cleanSince=null;lastSuccess=-1;
                if(outcome==PingSample.ERROR)run=0;
                else {
                    run++;
                    if(run>=rules.getConsecutiveCritical())level=Level.CRITICO;
                    if(level!=Level.CRITICO) {
                        Counts recent=counts(history,time-minuteMs,time);
                        double expected=Math.max(1.0,minuteMs/(double)interval);
                        boolean countCritical=recent.losses*rules.getMinuteReferencePackets()/expected>=rules.getMinuteCriticalLosses();
                        if(countCritical || minuteQuality(recent)==Quality.CRITICO)level=Level.CRITICO;
                    }
                }
            }
            previous=time;
        }
        // Only closed buckets are examined for recurrence, never the current partial minute.
        long closed=Math.floorDiv(now,bucketMs);
        while(nextBucket<=closed && lastIndex>=0) {
            if(level==Level.NORMAL && recurring(buckets,nextBucket))level=Level.DEGRADADO;
            nextBucket++;
        }
        boolean recurrent=recurring(buckets,closed);
        PingSample last=history.lastAt(now);
        boolean fresh=last!=null && now-last.measuredAt()<=maxGap;
        boolean healthy=fresh && last.outcome()!=PingSample.ERROR;
        boolean recovered=cleanSince!=null && lastSuccess-cleanSince>=recoveryMs && fresh;
        var stats=new SampleStore.Stats(hour.mean(),minute.mean(),last==null?null:last.latency(),last==null?null:last.measuredAt(),last==null?null:last.error(),hour.all,hour.all-hour.successes,hour.attempts,hour.losses,minute.attempts,minute.losses,eightHours.mean(),latencyHour.mean(),fiveMinutes.mean(),eightHours.attempts,eightHours.losses,latencyHour.attempts,latencyHour.losses,fiveMinutes.attempts,fiveMinutes.losses);
        return new PacketLossAnalysis(stats,minuteQuality(minute),hourQuality(hour.losses,hour.attempts,hour.enough()),currentRun,maxMinute,maxHour,recurrent,level,recovered,fresh,healthy);
    }
}
