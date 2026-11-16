package br.com.scriptping.scheduling;

import java.time.*;
import java.util.*;
import org.springframework.scheduling.support.CronExpression;

/** Only guided rules are accepted; cron is an internal implementation detail. */
public record ScheduleRule(String frequency, String time, List<Integer> weekdays, int monthDay,
                           int intervalMinutes, String zone) {
    public ScheduleRule {
        if (frequency == null || zone == null || zone.length() > 80)
            throw new IllegalArgumentException("Informe a frequência e o fuso horário.");
        try { ZoneId.of(zone); } catch (DateTimeException e) {
            throw new IllegalArgumentException("Fuso horário inválido.");
        }
        if (weekdays != null && weekdays.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Dia da semana inválido.");
        weekdays = weekdays == null ? List.of() : weekdays.stream().distinct().sorted().toList();
        if (!Set.of("DAILY", "WEEKLY", "MONTHLY", "INTERVAL").contains(frequency))
            throw new IllegalArgumentException("Frequência inválida.");
        if (!frequency.equals("INTERVAL")) {
            if (time == null || !time.matches("\\d{2}:\\d{2}"))
                throw new IllegalArgumentException("Informe um horário válido.");
            try { LocalTime.parse(time); } catch (DateTimeException e) {
                throw new IllegalArgumentException("Informe um horário válido.");
            }
        } else if (!Set.of(5, 10, 15, 30, 60).contains(intervalMinutes)) {
            throw new IllegalArgumentException("Selecione um intervalo disponível.");
        }
        if (frequency.equals("WEEKLY") && (weekdays.isEmpty() || weekdays.stream().anyMatch(d -> d < 1 || d > 7)))
            throw new IllegalArgumentException("Selecione ao menos um dia da semana.");
        if (frequency.equals("MONTHLY") && (monthDay < 1 || monthDay > 31))
            throw new IllegalArgumentException("O dia do mês deve estar entre 1 e 31.");
        if (frequency.equals("INTERVAL")) time = "02:00";
        else intervalMinutes = 30;
        if (!frequency.equals("WEEKLY")) weekdays = List.of();
        if (!frequency.equals("MONTHLY")) monthDay = 1;
    }
    private CronExpression expression() {
        if (frequency.equals("INTERVAL")) return CronExpression.parse(intervalMinutes == 60
            ? "0 0 * * * *" : "0 */" + intervalMinutes + " * * * *");
        var at = LocalTime.parse(time);
        String days = frequency.equals("WEEKLY")
            ? String.join(",", weekdays.stream().map(String::valueOf).toList()) : "*";
        return CronExpression.parse("0 " + at.getMinute() + " " + at.getHour() + " "
            + (frequency.equals("MONTHLY") ? monthDay : "*") + " * " + days);
    }
    public long next(long after) {
        var next = expression().next(Instant.ofEpochMilli(after).atZone(ZoneId.of(zone)));
        if (next == null) throw new IllegalArgumentException("A programação não possui próximas execuções.");
        return next.toInstant().toEpochMilli();
    }
    public List<Long> preview(long after) {
        var result = new ArrayList<Long>();
        for (int i = 0; i < 5; i++) { after = next(after); result.add(after); }
        return List.copyOf(result);
    }
    public String description() {
        return switch (frequency) {
            case "DAILY" -> "Todos os dias às " + time;
            case "WEEKLY" -> String.join(", ", weekdays.stream()
                .map(d -> List.of("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom").get(d - 1)).toList()) + " às " + time;
            case "MONTHLY" -> "Dia " + monthDay + " às " + time + (monthDay > 28 ? " (somente nos meses com esse dia)" : "");
            default -> intervalMinutes == 60 ? "A cada hora, no minuto 00" : "A cada " + intervalMinutes + " minutos, a partir do minuto 00";
        };
    }
}
