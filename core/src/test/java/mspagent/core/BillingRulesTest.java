package mspagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import mspagent.core.domain.BillableEntry;
import mspagent.core.domain.BillingRules;
import mspagent.core.domain.Category;
import mspagent.core.domain.Contract;
import mspagent.core.domain.ContractType;
import mspagent.core.domain.InvoiceLine;
import mspagent.core.domain.LineKind;
import mspagent.core.domain.SlaTier;
import mspagent.core.domain.WorkKind;
import org.junit.jupiter.api.Test;

class BillingRulesTest {
    static final Instant MON = Instant.parse("2026-10-05T08:00:00Z");

    static Contract contract(ContractType type, int prepaid) {
        return new Contract("c1", "Kanzlei Weber", type, SlaTier.SILVER, 120_00, 900_00, prepaid, 45_00);
    }

    static BillableEntry e(int minutes, WorkKind kind, Category cat, Instant at) {
        return new BillableEntry("T1", "tech1", at, minutes, kind, cat);
    }

    static long total(List<InvoiceLine> lines) {
        return lines.stream().mapToLong(InvoiceLine::amountCents).sum();
    }

    @Test
    void everyStartedQuarterHourIsCharged() {
        assertEquals(0, BillingRules.billedMinutes(0));
        assertEquals(15, BillingRules.billedMinutes(1));
        assertEquals(15, BillingRules.billedMinutes(15));
        assertEquals(30, BillingRules.billedMinutes(16));
        List<InvoiceLine> l = BillingRules.lines(contract(ContractType.TIME_AND_MATERIAL, 0),
                List.of(e(16, WorkKind.REMOTE, Category.INCIDENT, MON)));
        assertEquals(60_00, total(l));
    }

    @Test
    void theFlatCoversIncidentsButNotInstallations() {
        Contract c = contract(ContractType.MANAGED_FLAT, 0);
        List<InvoiceLine> l = BillingRules.lines(c, List.of(
                e(45, WorkKind.REMOTE, Category.INCIDENT, MON),
                e(60, WorkKind.ONSITE, Category.INSTALLATION, MON.plusSeconds(7200))));
        assertEquals(900_00 + 120_00 + 45_00, total(l));
        assertEquals(1, l.stream().filter(x -> x.kind() == LineKind.LABOUR).count());
    }

    @Test
    void thePrepaidPoolIsDrawnInTimeOrderAndTheRestCharged() {
        Contract c = contract(ContractType.PREPAID_HOURS, 60);
        List<InvoiceLine> l = BillingRules.lines(c, List.of(
                e(50, WorkKind.REMOTE, Category.INCIDENT, MON.plusSeconds(3600)),
                e(30, WorkKind.REMOTE, Category.INCIDENT, MON)));
        // 30 drawn, then 60 billed of which 30 drawn and 30 charged.
        assertEquals(30 * 120_00 / 60, total(l));
        assertEquals(60, l.stream().filter(x -> x.kind() == LineKind.PREPAID_DRAW).mapToInt(InvoiceLine::minutes).sum());
    }

    @Test
    void oneTravelFlatPerTechnicianAndDay() {
        Contract c = contract(ContractType.TIME_AND_MATERIAL, 0);
        List<InvoiceLine> l = BillingRules.lines(c, List.of(
                e(30, WorkKind.ONSITE, Category.INCIDENT, MON),
                e(30, WorkKind.ONSITE, Category.INCIDENT, MON.plusSeconds(3600)),
                e(30, WorkKind.ONSITE, Category.INCIDENT, MON.plusSeconds(86_400))));
        assertEquals(2, l.stream().filter(x -> x.kind() == LineKind.TRAVEL).count());
    }
}
