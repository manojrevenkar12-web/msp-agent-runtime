package mspagent.core.port;

import java.util.List;

/** The remote monitoring and management tool. */
public interface Rmm {
    List<RemoteSession> sessions(String tenant, String ticketId);
}
