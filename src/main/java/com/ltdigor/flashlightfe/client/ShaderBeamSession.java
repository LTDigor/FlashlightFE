package com.ltdigor.flashlightfe.client;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Render-thread lifecycle; an aborted shader compilation never commits readiness. */
public final class ShaderBeamSession {
    private final Set<Object> ready = Collections.newSetFromMap(new WeakHashMap<>());
    private Object building;
    private boolean terrain, uniforms, rejected;

    public void begin(Object pipeline) {
        building = pipeline;
        terrain = uniforms = rejected = false;
        ready.remove(pipeline);
    }
    public void patched(String program) { if (program.equals("gbuffers_terrain")) terrain = true; }
    public void uniformsRegistered() { uniforms = true; }
    public void reject() { rejected = true; }
    public void finish(Object pipeline) {
        if (building == pipeline && terrain && uniforms && !rejected) ready.add(pipeline);
        building = null;
    }
    public boolean ready(Object pipeline) { return ready.contains(pipeline); }
    public void destroy(Object pipeline) {
        ready.remove(pipeline);
        if (building == pipeline) building = null;
    }
}
