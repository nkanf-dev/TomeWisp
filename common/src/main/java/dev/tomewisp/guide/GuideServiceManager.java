package dev.tomewisp.guide;

import com.google.gson.Gson;
import dev.tomewisp.client.ClientEventDispatcher;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Creates exactly one connection-scoped GuideService for the current client actor. */
public final class GuideServiceManager {
    private final GuideLocalEndpoint local;
    private final GuideRemoteEndpoint remote;
    private final GuideContextProvider contexts;
    private final ClientEventDispatcher dispatcher;
    private final Clock clock;
    private final Gson gson;
    private GuideService current;

    public GuideServiceManager(
            GuideLocalEndpoint local,
            GuideRemoteEndpoint remote,
            GuideContextProvider contexts,
            ClientEventDispatcher dispatcher,
            Clock clock,
            Gson gson) {
        this.local = local;
        this.remote = Objects.requireNonNull(remote, "remote");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public synchronized GuideService forActor(UUID actor) {
        if (current == null || !current.snapshot().actorId().equals(actor)) {
            if (current != null) {
                current.disconnect();
            }
            current = new GuideService(
                    actor, local, remote, contexts, dispatcher, clock, gson);
        }
        return current;
    }

    public synchronized void disconnect() {
        if (current != null) {
            current.disconnect();
            current = null;
        } else {
            remote.disconnect();
        }
    }

    public synchronized GuideService current() {
        return current;
    }
}
