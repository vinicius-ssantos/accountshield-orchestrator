package io.github.viniciusssantos.accountshielddemo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A simple, timestamped, print-as-you-go event log -- issue #55's "displays a simple event timeline". */
final class EventTimeline {

    private final List<String> entries = new ArrayList<>();

    void record(String message) {
        String entry = "[" + Instant.now() + "] " + message;
        entries.add(entry);
        System.out.println(entry);
    }

    void printSummary() {
        System.out.println();
        System.out.println("==== Event timeline (" + entries.size() + " events) ====");
        entries.forEach(System.out::println);
    }
}
