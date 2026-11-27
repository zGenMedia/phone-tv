package com.zgenmedia.tvhome;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads one large video file over several connections at once.
 *
 * Many hosts cap the speed of a single connection well below what the line can carry, so
 * a film stutters on a connection that is nowhere near full. Asking for the file in
 * pieces, a few at a time, uses the room that is there. The idea and its main safeguards
 * follow Nuvio's parallel range source; this is a small one written for Phone TV.
 *
 * How it goes:
 *  - The first connection is opened exactly as the player would open it, asking for a
 *    byte range. Only a server that answers with the range and the file's full size
 *    gets more connections. Anything else (a playlist, a small file, a server without
 *    ranges) simply carries on over that one connection, as before.
 *  - That first connection serves the opening stretch. Nothing more is fetched until a
 *    megabyte has really been read, so the player's quick looks at the start and end of
 *    a file while it works out the format don't set off a burst of requests.
 *  - After that the file is fetched in 2 MB pieces ahead of the reader: two at a time to
 *    begin with, one more for every piece that arrives, up to the number allowed.
 *  - A host that objects (too many requests, a refused range, a failed piece twice over)
 *    is taken at its word: everything in flight is dropped and the rest of the film
 *    plays over a single connection. That choice sticks for this film.
 *
 * Memory is bounded: never more than one piece per connection, plus the one being read.
 */
// Media3 marks its data-source classes "unstable"; the version is pinned in build.gradle.
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class ParallelSource implements DataSource {
    static final int PIECE = 2 * 1024 * 1024;
    /** Bytes that must be read before any piece is fetched ahead. */
    private static final long EARNED = 1024 * 1024;
    /** Below this much left to read, extra connections aren't worth opening. */
    private static final long WORTH_IT = 8L * PIECE;

    /** Workers are shared by every film played; idle ones go away after a minute. */
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "phonetv-piece");
        t.setDaemon(true);
        return t;
    });

    static final class Factory implements DataSource.Factory {
        private final HttpDataSource.Factory upstream;
        private final Uri film;
        private final int connections;

        /**
         * @param film the one address this applies to; subtitles and anything else the
         *             player fetches go straight through, untouched
         */
        Factory(HttpDataSource.Factory upstream, Uri film, int connections) {
            this.upstream = upstream;
            this.film = film;
            this.connections = Math.max(2, Math.min(8, connections));
        }

        @Override public DataSource createDataSource() { return new ParallelSource(upstream, film, connections); }
    }

    /** One 2 MB stretch of the file, being fetched or waiting to be read. */
    private static final class Piece {
        final long index;
        final int length;
        volatile boolean dropped;
        Future<byte[]> arriving;

        Piece(long index, int length) { this.index = index; this.length = length; }
    }

    /** The host said no in a way that retrying won't fix. */
    private static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;
        Refused(String why) { super(why); }
    }

    private final HttpDataSource.Factory upstream;
    private final Uri film;
    private final int connections;
    private final List<TransferListener> listeners = new ArrayList<>();
    private final ConcurrentLinkedDeque<byte[]> spare = new ConcurrentLinkedDeque<>();
    private final AtomicInteger allowed = new AtomicInteger(2);

    private HttpDataSource lead;      // the connection open() made: the opening stretch, or the whole film
    private boolean parallel;         // pieces are in play for this open
    private boolean gaveUp;           // the host objected once: single connection from here on
    private DataSpec asked;
    private Uri resolved;             // where the film really lives, after redirects
    private Map<String, List<String>> responseHeaders = Collections.emptyMap();
    private long position, end, leadEnd, served;
    private final Map<Long, Piece> pieces = new HashMap<>();

    private ParallelSource(HttpDataSource.Factory upstream, Uri film, int connections) {
        this.upstream = upstream;
        this.film = film;
        this.connections = connections;
    }

    @Override public void addTransferListener(TransferListener listener) { listeners.add(listener); }
    @Override public Uri getUri() { return lead != null ? lead.getUri() : resolved; }
    @Override public Map<String, List<String>> getResponseHeaders() { return responseHeaders; }

    @Override
    public long open(DataSpec spec) throws IOException {
        close();
        asked = spec;
        position = spec.position;
        served = 0;

        boolean candidate = !gaveUp && spec.uri.equals(film) && spec.httpMethod == DataSpec.HTTP_METHOD_GET
                && (spec.length == C.LENGTH_UNSET || spec.length >= WORTH_IT);
        lead = connect();
        if (!candidate) {
            long length = lead.open(spec);
            responseHeaders = lead.getResponseHeaders();
            return length;
        }

        // Ask for a range even from the very start: the answer says whether the server
        // does ranges at all, and how big the whole file is.
        boolean fromStart = spec.position == 0 && spec.length == C.LENGTH_UNSET;
        long length = lead.open(fromStart ? spec.withAdditionalHeaders(Collections.singletonMap("Range", "bytes=0-")) : spec);
        responseHeaders = lead.getResponseHeaders();
        long total = lead.getResponseCode() == 206 ? totalSize(responseHeaders) : -1;
        long left = length != C.LENGTH_UNSET ? length : total > 0 ? total - position : C.LENGTH_UNSET;
        if (total <= 0 || left < WORTH_IT) return length;

        parallel = true;
        resolved = lead.getUri();
        end = position + left;
        // The opening connection carries on for a few megabytes, to a piece boundary. Pieces
        // are asked for once the first megabyte is in, so they have that long to arrive
        // and the hand-over to them isn't felt.
        leadEnd = Math.min(((position + EARNED) / PIECE + 2) * PIECE, end);
        return left;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (!parallel) {
            if (lead == null) throw new IOException("read before open");
            int n = lead.read(buffer, offset, length);
            if (n > 0) position += n;
            return n;
        }
        if (position >= end) return C.RESULT_END_OF_INPUT;

        if (lead != null) {
            int n = lead.read(buffer, offset, (int) Math.min(length, leadEnd - position));
            if (n == C.RESULT_END_OF_INPUT) return n;
            position += n;
            served += n;
            fetchAhead();
            if (position >= leadEnd) { lead.close(); lead = null; }
            return n;
        }

        long index = position / PIECE;
        fetchAhead();
        byte[] data;
        try {
            data = await(pieces.get(index));
        } catch (IOException failed) {
            if (failed instanceof InterruptedIOException) throw failed;
            carryOnAlone();
            return read(buffer, offset, length);
        }
        Piece piece = pieces.get(index);
        int at = (int) (position - index * PIECE);
        int n = (int) Math.min(Math.min(length, piece.length - at), end - position);
        System.arraycopy(data, at, buffer, offset, n);
        position += n;
        served += n;
        if (at + n >= piece.length) {
            pieces.remove(index);
            if (data.length == PIECE && spare.size() <= connections) spare.push(data);
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        for (Piece p : pieces.values()) drop(p);
        pieces.clear();
        parallel = false;
        if (lead != null) {
            HttpDataSource open = lead;
            lead = null;
            open.close();
        }
    }

    // ------------------------------------------------------------------ pieces

    /** Makes sure the pieces just ahead of the reader are on their way. */
    private void fetchAhead() {
        long first = Math.max(position, lead != null ? leadEnd : position) / PIECE;
        // Until a megabyte has been read, fetch only what the reader is about to need.
        int depth = served >= EARNED ? Math.min(connections, allowed.get()) : 0;
        if (lead == null) depth = Math.max(depth, 1);
        for (int i = 0; i < depth; i++) {
            final long index = first + i;
            long start = index * PIECE;
            if (start >= end) break;
            if (pieces.containsKey(index)) continue;
            final Piece piece = new Piece(index, (int) (Math.min(start + PIECE, end) - start));
            piece.arriving = WORKERS.submit(() -> fetch(piece, start));
            pieces.put(index, piece);
        }
    }

    /** Runs on a worker: one piece, tried twice unless the host plainly refuses. */
    private byte[] fetch(Piece piece, long from) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2 && !piece.dropped; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(400); } catch (InterruptedException e) { throw new InterruptedIOException(); }
            }
            HttpDataSource source = upstream.createDataSource();
            try {
                source.open(new DataSpec.Builder().setUri(resolved).setHttpRequestHeaders(asked.httpRequestHeaders)
                        .setPosition(from).setLength(piece.length).build());
                // A server that ignores the range would send the film from the top for every piece.
                if (source.getResponseCode() != 206) throw new Refused("range ignored");
                byte[] data = piece.length == PIECE ? spare.poll() : null;
                if (data == null) data = new byte[piece.length];
                int got = 0;
                while (got < piece.length) {
                    if (piece.dropped) throw new InterruptedIOException();
                    int n = source.read(data, got, piece.length - got);
                    if (n == C.RESULT_END_OF_INPUT) throw new EOFException("piece ended early");
                    got += n;
                }
                if (allowed.get() < connections) allowed.incrementAndGet();
                return data;
            } catch (HttpDataSource.InvalidResponseCodeException code) {
                // 429 and friends: this host counts connections. Don't argue.
                throw new Refused("HTTP " + code.responseCode);
            } catch (Refused | InterruptedIOException stop) {
                throw stop;
            } catch (IOException e) {
                last = e;
            } catch (OutOfMemoryError tight) {
                throw new Refused("no memory for another piece");
            } finally {
                try { source.close(); } catch (IOException ignored) { /* already failing, or done */ }
            }
        }
        throw last != null ? last : new InterruptedIOException();
    }

    private static byte[] await(Piece piece) throws IOException {
        if (piece == null) throw new IOException("piece was never asked for");
        try {
            return piece.arriving.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        } catch (ExecutionException e) {
            Throwable why = e.getCause();
            throw why instanceof IOException ? (IOException) why : new IOException(why);
        } catch (java.util.concurrent.CancellationException e) {
            throw new IOException("piece dropped");
        }
    }

    private static void drop(Piece piece) {
        piece.dropped = true;
        piece.arriving.cancel(true);
    }

    /** The host objected: drop what's in flight and finish over one ordinary connection. */
    private void carryOnAlone() throws IOException {
        for (Piece p : pieces.values()) drop(p);
        pieces.clear();
        spare.clear();
        gaveUp = true;
        parallel = false;
        lead = connect();
        // Asked the same way the film was first asked for: open-ended, unless the player set a length.
        long length = asked.length == C.LENGTH_UNSET ? C.LENGTH_UNSET : asked.position + asked.length - position;
        lead.open(asked.buildUpon().setPosition(position).setLength(length).build());
    }

    private HttpDataSource connect() {
        HttpDataSource source = upstream.createDataSource();
        for (TransferListener l : listeners) source.addTransferListener(l);
        return source;
    }

    /** The whole file's size from "Content-Range: bytes 0-99/12345", or -1 when the server doesn't say. */
    static long totalSize(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> h : headers.entrySet()) {
            if (h.getKey() == null || !h.getKey().equalsIgnoreCase("Content-Range") || h.getValue().isEmpty()) continue;
            String value = h.getValue().get(0);
            int slash = value.lastIndexOf('/');
            if (slash < 0) return -1;
            try { return Long.parseLong(value.substring(slash + 1).trim()); }
            catch (NumberFormatException unknown) { return -1; }   // "bytes 0-99/*"
        }
        return -1;
    }
}
