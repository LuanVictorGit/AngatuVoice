package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.gson.GsonAPI;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A headless network node for the cluster test: the real peer engine in its own JVM, driven line by line over stdin.
 *
 * @author Angatu Sistemas
 */
public final class TestNode {

    private static final List<String> NOTICES = new CopyOnWriteArrayList<>();

    private TestNode() {
    }

    public static void main(String[] args) throws Exception {
        Migrations.run();
        Migrations.indexes();
        // Nobody is at the window of a test node, so every notice the person would get reaches this list.
        Notifications.bind((title, body) -> NOTICES.add(body), () -> false);
        PeerNetwork.start();
        for (int attempt = 0; attempt < 200 && PeerNetwork.listenPort() == 0; attempt++) {
            Thread.sleep(25);
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            System.out.println("=" + PeerNetwork.me().id());
            System.out.flush();
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                System.out.println("=" + run(line.split(" ", 2)));
                System.out.flush();
            }
        }
    }

    private static String run(String[] command) {
        String argument = command.length > 1 ? command[1] : "";
        return switch (command[0]) {
            case "connect" -> {
                PeerNetwork.addManual(argument);
                yield "ok";
            }
            case "call" -> {
                PeerNetwork.setCall(argument, true);
                yield "ok";
            }
            case "leave" -> {
                PeerNetwork.setCall(argument, false);
                yield "ok";
            }
            case "decline" -> {
                PeerNetwork.declineCall(argument);
                yield "ok";
            }
            case "quit" -> {
                PeerNetwork.stop();
                Thread.startVirtualThread(() -> System.exit(0));
                yield "ok";
            }
            case "state" -> state();
            default -> "unknown";
        };
    }

    private static String state() {
        JsonObject out = new JsonObject();
        out.add("peers", GsonAPI.get().toJsonTree(PeerNetwork.peers()));
        out.add("calls", GsonAPI.get().toJsonTree(CallService.snapshot()));
        out.add("incoming", GsonAPI.get().toJsonTree(CallService.ringing()));
        out.add("latency", GsonAPI.get().toJsonTree(PeerNetwork.latency()));
        out.add("notices", GsonAPI.get().toJsonTree(NOTICES));
        out.addProperty("links", PeerNetwork.linkCount());
        return GsonAPI.get().toJson(out);
    }
}
