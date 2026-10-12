package com.school.party.domain.community;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
public class MutableClock extends Clock {
    private final AtomicReference<Instant> value = new AtomicReference<>();
    public MutableClock(Instant value) { this.value.set(value); }
    public void set(Instant at) { value.set(at); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return value.get(); }
}
