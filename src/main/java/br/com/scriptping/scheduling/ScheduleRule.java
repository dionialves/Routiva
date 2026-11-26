package br.com.scriptping.scheduling;

import java.time.*;
import java.util.*;
import org.springframework.scheduling.support.CronExpression;

/** Five-field cron for new rules, with support for stored frequency-based rules. */
public record ScheduleRule(String frequency, String time, List<Integer> weekdays, int monthDay,
                           int intervalMinutes, String zone, String cron) {
    private static final List<String> CRON_FIELDS = List.of("Minuto", "Hora", "Dia do mês", "Mês", "Dia da semana");
    private static final int[] MINIMUM = {0, 0, 1, 1, 0};
    private static final int[] MAXIMUM = {59, 23, 31, 12, 6};

    public ScheduleRule(String frequency, String time, List<Integer> weekdays, int monthDay,
                        int intervalMinutes, String zone) {
        this(frequency, time, weekdays, monthDay, intervalMinutes, zone, null);
    }

    public static ScheduleRule cron(String expression, String zone) {
        if (expression == null) throw new IllegalArgumentException("Informe a programação.");
        return new ScheduleRule(null, null, null, 0, 0, zone, expression);
    }

    private static String validateCron(String expression) {
        var fields = expression.split(" ", -1);
        if (fields.length != CRON_FIELDS.size())
            throw new IllegalArgumentException("Informe os cinco parâmetros da programação.");
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].equals("*")) continue;
            if (!fields[i].matches("[0-9]{1,2}") || Integer.parseInt(fields[i]) < MINIMUM[i]
                || Integer.parseInt(fields[i]) > MAXIMUM[i])
                throw new IllegalArgumentException(CRON_FIELDS.get(i) + " deve ser um inteiro entre "
                    + MINIMUM[i] + " e " + MAXIMUM[i] + ".");
            fields[i] = Integer.toString(Integer.parseInt(fields[i]));
        }
        if (!fields[2].equals("*") && !fields[3].equals("*")
            && Integer.parseInt(fields[2]) > Month.of(Integer.parseInt(fields[3])).maxLength())
            throw new IllegalArgumentException("O dia informado não existe no mês selecionado.");
        return String.join(" ", fields);
    }

    public ScheduleRule {
        if (zone == null || zone.length() > 80)
            throw new IllegalArgumentException("Informe um fuso horário válido.");
        try { ZoneId.of(zone); } catch (DateTimeException e) {
            throw new IllegalArgumentException("Fuso horário inválido.");
        }
        if (cron != null) {
            cron = validateCron(cron);
            frequency = null;
            time = null;
            weekdays = List.of();
            monthDay = 0;
            intervalMinutes = 0;
        } else {
            if (frequency == null) throw new IllegalArgumentException("Informe a frequência.");
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
    }
    private CronExpression expression() {
        // Spring matches both day fields when both are restricted.
        if (cron != null) return CronExpression.parse("0 " + cron);
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
        if (cron != null) return cron;
        return switch (frequency) {
            case "DAILY" -> "Todos os dias às " + time;
            case "WEEKLY" -> String.join(", ", weekdays.stream()
                .map(d -> List.of("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom").get(d - 1)).toList()) + " às " + time;
            case "MONTHLY" -> "Dia " + monthDay + " às " + time + (monthDay > 28 ? " (somente nos meses com esse dia)" : "");
            default -> intervalMinutes == 60 ? "A cada hora, no minuto 00" : "A cada " + intervalMinutes + " minutos, a partir do minuto 00";
        };
    }
}
