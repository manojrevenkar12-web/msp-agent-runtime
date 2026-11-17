package mspagent.core.domain;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The billing rules of a German IT service provider, in integer cents. Code decides every amount; a model only words
 * the line texts afterwards.
 *
 * <ul>
 *   <li>Labour is charged per started quarter hour, per entry.
 *   <li>A managed-service flat covers incidents and security alerts; other work is charged.
 *   <li>A prepaid pool is drawn in time order; what does not fit is charged.
 *   <li>Each technician's on-site day at a customer adds one travel flat, unless the flat fee covers the work.
 * </ul>
 */
public final class BillingRules {

    public static final int QUARTER = 15;
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private BillingRules() {}

    public static int billedMinutes(int minutes) {
        if (minutes <= 0) {
            return 0;
        }
        return ((minutes + QUARTER - 1) / QUARTER) * QUARTER;
    }

    public static long labourCents(int billedMinutes, long hourlyRateCents) {
        // Billed minutes are whole quarters, so this division is exact.
        return billedMinutes / QUARTER * (hourlyRateCents / 4);
    }

    public static List<InvoiceLine> lines(Contract c, List<BillableEntry> entries) {
        List<InvoiceLine> out = new ArrayList<>();
        if (c.type() == ContractType.MANAGED_FLAT) {
            out.add(new InvoiceLine(LineKind.FLAT_FEE, null, 0, c.flatFeeCents(), "Managed services, monthly fee"));
        }
        List<BillableEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(BillableEntry::start).thenComparing(BillableEntry::ticketId));
        int pool = c.type() == ContractType.PREPAID_HOURS ? c.prepaidMinutes() : 0;
        Set<String> travelDays = new HashSet<>();
        for (BillableEntry e : sorted) {
            boolean covered = c.type() == ContractType.MANAGED_FLAT && e.category().coveredByFlat();
            if (covered) {
                continue;
            }
            int billed = billedMinutes(e.minutes());
            if (pool > 0) {
                int drawn = Math.min(pool, billed);
                pool -= drawn;
                out.add(new InvoiceLine(LineKind.PREPAID_DRAW, e.ticketId(), drawn, 0, null));
                billed -= drawn;
            }
            if (billed > 0) {
                out.add(new InvoiceLine(LineKind.LABOUR, e.ticketId(), billed, labourCents(billed, c.hourlyRateCents()), null));
            }
            if (e.kind() == WorkKind.ONSITE) {
                String day = e.technicianId() + "|" + e.start().atZone(BERLIN).toLocalDate();
                if (travelDays.add(day)) {
                    out.add(new InvoiceLine(LineKind.TRAVEL, e.ticketId(), 0, c.travelFlatCents(), "Travel flat"));
                }
            }
        }
        return out;
    }
}
