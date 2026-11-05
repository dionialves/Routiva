package br.com.scriptping;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Calendar periods and chart boundaries share one explicit business timezone. */
public record AvailabilityPeriod(int minutes, String label, int bucketMinutes, int months) {
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    public static final List<AvailabilityPeriod> OPTIONS = List.of(
        new AvailabilityPeriod(5, "Últimos 5 minutos", 1, 0),
        new AvailabilityPeriod(15, "Últimos 15 minutos", 1, 0),
        new AvailabilityPeriod(30, "Últimos 30 minutos", 1, 0),
        new AvailabilityPeriod(60, "Última 1 hora", 2, 0),
        new AvailabilityPeriod(180, "Últimas 3 horas", 5, 0),
        new AvailabilityPeriod(360, "Últimas 6 horas", 10, 0),
        new AvailabilityPeriod(720, "Últimas 12 horas", 15, 0),
        new AvailabilityPeriod(1440, "Último 1 dia", 30, 0),
        new AvailabilityPeriod(2880, "Últimos 2 dias", 60, 0),
        new AvailabilityPeriod(10080, "Últimos 7 dias", 240, 0),
        new AvailabilityPeriod(43200, "Últimos 30 dias", 1440, 0),
        new AvailabilityPeriod(129600, "Últimos 3 meses", 10080, 3),
        new AvailabilityPeriod(259200, "Últimos 6 meses", 10080, 6),
        new AvailabilityPeriod(525600, "Último 1 ano", 0, 12),
        new AvailabilityPeriod(1051200, "Últimos 2 anos", 0, 24));

    public static AvailabilityPeriod of(int minutes) {
        // Preserve existing host-detail links that use the former eight-hour period.
        if (minutes == 480) return new AvailabilityPeriod(480, "Últimas 8 horas", 15, 0);
        return OPTIONS.stream().filter(p -> p.minutes == minutes).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Período inválido"));
    }

    public long from(long to) {
        return months == 0 ? to - minutes * 60000L
            : Instant.ofEpochMilli(to).atZone(ZONE).minusMonths(months).toInstant().toEpochMilli();
    }

    public String grouping() {
        return switch (bucketMinutes) {
            case 0 -> "Mensal";
            case 10080 -> "Semanal";
            case 1440 -> "Diário";
            case 60 -> "1 hora";
            case 240 -> "4 horas";
            case 1 -> "1 minuto";
            default -> bucketMinutes + " minutos";
        };
    }

    public record Interval(long from, long to, boolean partial) {}
    public List<Interval> intervals(long from, long to) {
        var result = new ArrayList<Interval>();
        var start = Instant.ofEpochMilli(from).atZone(ZONE);
        if (bucketMinutes == 0) start = start.withDayOfMonth(1).toLocalDate().atStartOfDay(ZONE);
        else if (bucketMinutes == 10080) start = start.toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZONE);
        else if (bucketMinutes == 1440) start = start.toLocalDate().atStartOfDay(ZONE);
        else {
            var midnight = start.toLocalDate().atStartOfDay(ZONE);
            long elapsed = Duration.between(midnight, start).toMinutes();
            start = midnight.plusMinutes(elapsed / bucketMinutes * bucketMinutes);
        }
        while (start.toInstant().toEpochMilli() < to) {
            var next = bucketMinutes == 0 ? start.plusMonths(1)
                : bucketMinutes == 10080 ? start.plusWeeks(1)
                : bucketMinutes == 1440 ? start.plusDays(1) : start.plusMinutes(bucketMinutes);
            long a = start.toInstant().toEpochMilli(), b = next.toInstant().toEpochMilli();
            result.add(new Interval(Math.max(a, from), Math.min(b, to), a < from || b > to));
            start = next;
        }
        return List.copyOf(result);
    }
}
