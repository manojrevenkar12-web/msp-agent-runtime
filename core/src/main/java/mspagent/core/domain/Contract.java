package mspagent.core.domain;

/**
 * A customer's service contract as the ERP holds it. Money is in integer euro cents.
 *
 * @param prepaidMinutes the monthly pool for {@link ContractType#PREPAID_HOURS}, otherwise 0
 * @param flatFeeCents the monthly fee for {@link ContractType#MANAGED_FLAT}, otherwise 0
 */
public record Contract(
        String customerId,
        String customerName,
        ContractType type,
        SlaTier sla,
        long hourlyRateCents,
        long flatFeeCents,
        int prepaidMinutes,
        long travelFlatCents) {}
