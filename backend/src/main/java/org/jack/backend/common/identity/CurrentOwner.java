package org.jack.backend.common.identity;

import org.springframework.stereotype.Component;

/** P0 runs locally for one server-managed owner; never read owner identity from a request. */
@Component
public class CurrentOwner {
    public String id() {
        return "local";
    }
}
