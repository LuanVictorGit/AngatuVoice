package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.console.Console;
import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The system sound for a screen share, minus everything this app plays. Chromium's loopback hears the whole mix, call
 * voices and ringtones included; Windows process loopback can leave one process tree out, and the embedded Chromium
 * (its audio service included) runs as children of this process. 48 kHz stereo 16-bit PCM goes to the page as binary
 * WebSocket frames, one capture per open socket. Needs Windows 10 2004 or newer; the socket closes with
 * {@link #UNSUPPORTED} where the API is missing, and the page falls back to the plain loopback.
 *
 * @author Angatu Sistemas
 */
public final class SystemAudio {

    public static final int UNSUPPORTED = 4501;
    private static final int RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int BLOCK = CHANNELS * 2;
    private static final int POLL_MS = 10;
    private static final long BUFFER_HNS = 2_000_000L;
    private static final int ACTIVATION_TYPE_PROCESS_LOOPBACK = 1;
    private static final int LOOPBACK_EXCLUDE_TREE = 1;
    private static final short VT_BLOB = 65;
    private static final int STREAMFLAGS = 0x00020000 | 0x80000000 | 0x08000000;
    private static final int BUFFERFLAGS_SILENT = 0x2;
    private static final int S_OK = 0;
    private static final int E_NOINTERFACE = 0x80004002;
    private static final String PROCESS_LOOPBACK = "VAD\\Process_Loopback";
    private static final GUID IID_UNKNOWN = new GUID("{00000000-0000-0000-C000-000000000046}");
    private static final GUID IID_AGILE = new GUID("{94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90}");
    private static final GUID IID_COMPLETION = new GUID("{41D949AB-9862-444A-80F6-C261334DA5EB}");
    private static final GUID IID_AUDIO_CLIENT = new GUID("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}");
    private static final GUID IID_CAPTURE_CLIENT = new GUID("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}");
    private static final Map<WsContext, Thread> CAPTURES = new ConcurrentHashMap<>();

    private SystemAudio() {
    }

    public static void accept(WsConfig ws) {
        ws.onConnect(ctx -> {
            if (!SessionService.valid(ctx.cookie(SessionService.COOKIE), ctx.header("Host"))) {
                ctx.closeSession(LiveHub.SESSION_EXPIRED, "session expired");
                return;
            }
            Thread capture = Thread.ofPlatform().daemon().name("system-audio").start(() -> capture(ctx));
            CAPTURES.put(ctx, capture);
        });
        ws.onClose(ctx -> stop(ctx));
        ws.onError(ctx -> stop(ctx));
    }

    private static void stop(WsContext ctx) {
        Thread capture = CAPTURES.remove(ctx);
        if (capture != null) {
            capture.interrupt();
        }
    }

    private interface Mmdevapi extends StdCallLibrary {
        int ActivateAudioInterfaceAsync(WString path, GUID.ByReference riid, Pointer params, Pointer handler,
                PointerByReference operation);
    }

    private interface QueryInterface extends Callback {
        int invoke(Pointer self, Pointer riid, Pointer out);
    }

    private interface RefCount extends Callback {
        int invoke(Pointer self);
    }

    private interface Completed extends Callback {
        int invoke(Pointer self, Pointer operation);
    }

    /* The completion handler Windows calls back on; it must also claim IAgileObject or activation fails. */
    private static final class Handler {
        final CountDownLatch done = new CountDownLatch(1);
        final Memory vtable = new Memory(4L * Native.POINTER_SIZE);
        final Memory object = new Memory(Native.POINTER_SIZE);
        final QueryInterface query = (self, riid, out) -> {
            GUID asked = new GUID(riid);
            boolean known = asked.equals(IID_UNKNOWN) || asked.equals(IID_AGILE) || asked.equals(IID_COMPLETION);
            out.setPointer(0, known ? self : Pointer.NULL);
            return known ? S_OK : E_NOINTERFACE;
        };
        final RefCount addRef = self -> 1;
        final RefCount release = self -> 1;
        final Completed completed = (self, operation) -> {
            done.countDown();
            return S_OK;
        };

        Handler() {
            vtable.setPointer(0, CallbackReference.getFunctionPointer(query));
            vtable.setPointer(Native.POINTER_SIZE, CallbackReference.getFunctionPointer(addRef));
            vtable.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(release));
            vtable.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(completed));
            object.setPointer(0, vtable);
        }
    }

    private static int call(Pointer com, int slot, Object... args) {
        Pointer vtable = com.getPointer(0);
        Object[] all = new Object[args.length + 1];
        all[0] = com;
        System.arraycopy(args, 0, all, 1, args.length);
        return Function.getFunction(vtable.getPointer((long) slot * Native.POINTER_SIZE)).invokeInt(all);
    }

    private static void release(Pointer com) {
        if (com != null) {
            call(com, 2);
        }
    }

    private static void check(int hr, String step) {
        if (hr != S_OK) {
            throw new IllegalStateException(step + " failed: 0x" + Integer.toHexString(hr));
        }
    }

    private static void capture(WsContext ctx) {
        Pointer operation = null;
        Pointer client = null;
        Pointer reader = null;
        Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED);
        try {
            try {
                Handler handler = new Handler();
                Memory params = new Memory(12);
                params.setInt(0, ACTIVATION_TYPE_PROCESS_LOOPBACK);
                params.setInt(4, (int) ProcessHandle.current().pid());
                params.setInt(8, LOOPBACK_EXCLUDE_TREE);
                Memory variant = new Memory(24);
                variant.clear();
                variant.setShort(0, VT_BLOB);
                variant.setInt(8, 12);
                variant.setPointer(16, params);
                PointerByReference op = new PointerByReference();
                Mmdevapi mmdevapi = Native.load("Mmdevapi", Mmdevapi.class);
                check(mmdevapi.ActivateAudioInterfaceAsync(new WString(PROCESS_LOOPBACK),
                        new GUID.ByReference(IID_AUDIO_CLIENT), variant, handler.object, op), "activation");
                operation = op.getValue();
                if (!handler.done.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("activation timed out");
                }
                IntByReference result = new IntByReference();
                PointerByReference unknown = new PointerByReference();
                check(call(operation, 3, result, unknown), "activation result");
                check(result.getValue(), "activation");
                client = unknown.getValue();
                Memory format = new Memory(18);
                format.setShort(0, (short) 1);
                format.setShort(2, (short) CHANNELS);
                format.setInt(4, RATE);
                format.setInt(8, RATE * BLOCK);
                format.setShort(12, (short) BLOCK);
                format.setShort(14, (short) 16);
                format.setShort(16, (short) 0);
                check(call(client, 3, 0, STREAMFLAGS, BUFFER_HNS, 0L, format, Pointer.NULL), "initialize");
                PointerByReference service = new PointerByReference();
                check(call(client, 14, new GUID.ByReference(IID_CAPTURE_CLIENT), service), "capture client");
                reader = service.getValue();
                check(call(client, 10), "start");
            } catch (LinkageError | IllegalStateException e) {
                Console.debug("process loopback unavailable: " + e.getMessage());
                ctx.closeSession(UNSUPPORTED, "process loopback unavailable");
                return;
            }
            ctx.send("ready");
            pump(ctx, reader);
            call(client, 11);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Console.debug("system audio stopped: " + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            release(reader);
            release(client);
            release(operation);
            Ole32.INSTANCE.CoUninitialize();
        }
    }

    private static void pump(WsContext ctx, Pointer reader) throws InterruptedException {
        IntByReference packet = new IntByReference();
        PointerByReference data = new PointerByReference();
        IntByReference frames = new IntByReference();
        IntByReference flags = new IntByReference();
        while (!Thread.currentThread().isInterrupted() && ctx.session.isOpen()) {
            check(call(reader, 5, packet), "packet size");
            while (packet.getValue() > 0) {
                check(call(reader, 3, data, frames, flags, Pointer.NULL, Pointer.NULL), "read");
                int bytes = frames.getValue() * BLOCK;
                byte[] chunk = (flags.getValue() & BUFFERFLAGS_SILENT) != 0 ? new byte[bytes]
                        : data.getValue().getByteArray(0, bytes);
                check(call(reader, 4, frames.getValue()), "release");
                ctx.send(ByteBuffer.wrap(chunk));
                check(call(reader, 5, packet), "packet size");
            }
            Thread.sleep(POLL_MS);
        }
    }
}
