package mspagent.core.port;

import java.util.List;

/** The technicians' mobile app for on-site visits. */
public interface FieldApp {
    List<CheckIn> checkIns(String tenant, String ticketId);
}
