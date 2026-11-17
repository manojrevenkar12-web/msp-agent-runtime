package mspagent.world;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** {@code config/world.json}: everything about the simulated service provider. The runtime never reads it. */
public record WorldSettings(
        String month,
        String tenant,
        int technicians,
        int customers,
        int ticketsPerWorkday,
        Map<String, Double> contractMix,
        Map<String, Double> slaMix,
        List<Integer> hourlyRateEuros,
        List<Integer> flatFeeEuros,
        List<Integer> prepaidMinutes,
        List<Integer> travelFlatEuros,
        Map<String, Double> categoryMix,
        double injectionRate,
        Work work,
        People people,
        Sla sla,
        Model model,
        Delivery delivery,
        List<Long> devSeeds,
        List<Long> heldOutSeeds,
        List<String> heldOutOnly) {

    public record Work(
            List<Integer> sessionsPerTicket,
            double remoteViaRmm,
            double onsiteCheckedIn,
            double noteWritten,
            double noteWithoutDuration,
            double distractorRate,
            double internalNote,
            double secretInInternalNote,
            Map<String, Double> manualEntry,
            List<Double> manualUnderRecord,
            double manualEntryMinutes) {}

    public record People(
            double technicianConfirmsRight,
            double technicianRejectsWrong,
            double dispatcherReadsRight,
            double dispatcherPushedByCustomer,
            double dispatcherCategoryRight,
            double reviewFixesPriority,
            double reviewMinutes,
            double approvalMinutes,
            double confirmMinutes) {}

    public record Sla(Map<String, List<Integer>> queueMinutes, List<Integer> dispatcherDelayMinutes,
            List<Integer> reviewDelayMinutes) {}

    public record Model(
            String name,
            double categoryRight,
            double impactRight,
            double hallucinatedQuote,
            double obeysInjection,
            double durationRight,
            double kindRight,
            double missesInterval,
            double readsDistractorAsWork,
            double unseenFormatRight,
            double leaksInternal,
            double leaksSecret,
            double unavailable,
            int promptTokens) {}

    public record Delivery(double duplicate, double lateRate, List<Integer> lateMinutes, double meanDelayMinutes,
            double crash) {}

    public static WorldSettings load(Path p) {
        try {
            JsonMapper m = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
            return m.readValue(Files.readString(p), WorldSettings.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + p, e);
        }
    }
}
