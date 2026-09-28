package com.ltdigor.flashlightfe.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShaderBeamSessionTest {
    @Test void compilationAndUniformRegistrationMustFinishBeforeTakingOverLdl() {
        var session = new ShaderBeamSession();
        Object pipeline = new Object();
        session.begin(pipeline);
        session.patched("gbuffers_terrain");
        assertFalse(session.ready(pipeline));
        session.uniformsRegistered();
        session.finish(pipeline);
        assertTrue(session.ready(pipeline));
        session.destroy(pipeline);
        assertFalse(session.ready(pipeline));
    }

    @Test void failureOrMissingTerrainNeverSuppressesFallback() {
        for (boolean fail : new boolean[]{false, true}) {
            var session = new ShaderBeamSession();
            Object pipeline = new Object();
            session.begin(pipeline);
            session.patched(fail ? "gbuffers_terrain" : "gbuffers_entities");
            session.uniformsRegistered();
            if (fail) session.reject();
            session.finish(pipeline);
            assertFalse(session.ready(pipeline));
        }
    }

    @Test void dimensionPipelinesAndAbortedReloadsDoNotShareReadiness() {
        var session = new ShaderBeamSession();
        Object first = new Object(), second = new Object();
        session.begin(first); session.patched("gbuffers_terrain");
        session.uniformsRegistered(); session.finish(first);
        session.begin(second); session.patched("gbuffers_terrain");
        assertFalse(session.ready(second));
        assertTrue(session.ready(first));
        session.destroy(first);
        assertFalse(session.ready(first));
    }
}
