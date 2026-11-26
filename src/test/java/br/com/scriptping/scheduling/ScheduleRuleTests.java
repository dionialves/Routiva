package br.com.scriptping.scheduling;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ScheduleRuleTests {
    private long at(String instant) { return Instant.parse(instant).toEpochMilli(); }
    @Test void dailyUsesSelectedZoneAndStrictlyFutureTimes() {
        var rule = new ScheduleRule("DAILY", "02:00", List.of(), 1, 30, "America/Sao_Paulo");
        assertThat(rule.next(at("2026-11-16T04:59:59Z"))).isEqualTo(at("2026-11-16T05:00:00Z"));
        assertThat(rule.next(at("2026-11-16T05:00:00Z"))).isEqualTo(at("2026-11-17T05:00:00Z"));
        assertThat(rule.preview(at("2026-11-16T04:59:59Z"))).hasSize(5).isSorted();
    }
    @Test void weeklyHandlesSundayAndDeduplicatesDays() {
        var rule = new ScheduleRule("WEEKLY", "02:00", List.of(7, 7), 1, 30, "UTC");
        assertThat(rule.next(at("2026-11-16T00:00:00Z"))).isEqualTo(at("2026-11-22T02:00:00Z"));
        assertThat(rule.weekdays()).containsExactly(7);
    }
    @Test void monthlySkipsMonthsWithoutSelectedDay() {
        var rule = new ScheduleRule("MONTHLY", "02:00", List.of(), 31, 30, "UTC");
        assertThat(rule.next(at("2026-04-01T00:00:00Z"))).isEqualTo(at("2026-05-31T02:00:00Z"));
    }
    @Test void intervalsAreAlignedToClock() {
        var rule = new ScheduleRule("INTERVAL", "", List.of(), 1, 30, "UTC");
        assertThat(rule.next(at("2026-11-16T12:07:00Z"))).isEqualTo(at("2026-11-16T12:30:00Z"));
        assertThat(new ScheduleRule("INTERVAL", "", List.of(), 1, 60, "UTC").next(at("2026-11-16T12:07:00Z")))
            .isEqualTo(at("2026-11-16T13:00:00Z"));
    }
    @Test void rejectsInvalidRulesAndCronInput() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ScheduleRule("CRON", "", null, 1, 30, "UTC"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ScheduleRule("DAILY", "25:00", null, 1, 30, "UTC"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ScheduleRule("DAILY", "02:00", null, 1, 30, "invalid"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ScheduleRule("WEEKLY", "02:00", List.of(), 1, 30, "UTC"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ScheduleRule("WEEKLY", "02:00", List.of(0), 1, 30, "UTC"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ScheduleRule("MONTHLY", "02:00", null, 32, 30, "UTC"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ScheduleRule("INTERVAL", "", null, 1, 1, "UTC"));
    }
    @Test void handlesDaylightSavingGapWithoutUsingServerZone() {
        var rule = new ScheduleRule("DAILY", "02:30", null, 1, 30, "America/New_York");
        assertThat(rule.next(at("2026-03-08T05:00:00Z"))).isEqualTo(at("2026-03-09T06:30:00Z"));
    }
    @Test void cronUsesFiveFieldsAndRunsAtSecondZeroInTheConfiguredZone() {
        var rule = ScheduleRule.cron("00 01 * * *", "America/Sao_Paulo");
        assertThat(rule.cron()).isEqualTo("0 1 * * *");
        assertThat(rule.next(at("2026-11-16T03:59:59Z"))).isEqualTo(at("2026-11-16T04:00:00Z"));
        assertThat(ScheduleRule.cron("* * * * *", "UTC").next(at("2026-11-16T04:00:00Z")))
            .isEqualTo(at("2026-11-16T04:01:00Z"));
    }

    @Test void cronRequiresBothCalendarDaysToMatchAndUsesZeroForSunday() {
        var rule = ScheduleRule.cron("0 1 15 * 1", "UTC");
        assertThat(rule.next(at("2026-11-16T00:00:00Z"))).isEqualTo(at("2027-02-15T01:00:00Z"));
        assertThat(ScheduleRule.cron("59 23 * 11 0", "UTC").next(at("2026-11-16T00:00:00Z")))
            .isEqualTo(at("2026-11-22T23:59:00Z"));
        assertThat(ScheduleRule.cron("0 1 29 2 1", "UTC").next(at("2026-11-16T00:00:00Z")))
            .isEqualTo(at("2044-02-29T01:00:00Z"));
    }

    @Test void cronRejectsOutOfRangeFieldsAndUnrepresentableExpressions() {
        for (String cron : List.of("60 * * * *", "* 24 * * *", "* * 0 * *", "* * 32 * *",
            "* * * 0 *", "* * * 13 *", "* * * * 7", "* * * * -1", "* * 31 2 *",
            "0.5 * * * *", "*/5 * * * *", "0 * * * 1,2", "0 * * * * *", "0 * * *", "0 * *  *")) {
            assertThatIllegalArgumentException().as(cron).isThrownBy(() -> ScheduleRule.cron(cron, "UTC"));
        }
    }
}
