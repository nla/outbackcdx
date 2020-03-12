package outbackcdx;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * A simple, tiny, nicely embeddable HTTP server in Java
 * <p/>
 * <p/>
 * NanoHTTPD
 * <p></p>Copyright (c) 2012-2013 by Paul S. Hawke, 2001,2005-2013 by Jarno Elonen, 2010 by Konstantinos Togias</p>
 * <p/>
 * <p/>
 * <b>Features + limitations: </b>
 * <ul>
 * <p/>
 * <li>Only one Java file</li>
 * <li>Java 5 compatible</li>
 * <li>Released as open source, Modified BSD licence</li>
 * <li>No fixed config files, logging, authorization etc. (Implement yourself if you need them.)</li>
 * <li>Supports parameter parsing of GET and POST methods <strike>(+ rudimentary PUT support in 1.25)</strike></li>
 * <li>Supports both dynamic content and file serving</li>
 * <li><strike>Supports file upload (since version 1.2, 2010)</strike></li>
 * <li>Supports partial content (streaming)</li>
 * <li>Supports ETags</li>
 * <li>Never caches anything</li>
 * <li>Doesn't limit bandwidth, request time or simultaneous connections</li>
 * <li>Default code serves files and shows all HTTP parameters and headers</li>
 * <li>All header names are converted lowercase so they don't vary between browsers/clients</li>
 * <p/>
 * </ul>
 * <p/>
 * <p/>
 * <b>How to use: </b>
 * <ul>
 * <p/>
 * <li>Subclass and implement serve() and embed to your own program</li>
 * <p/>
 * </ul>
 * <p/>
 * <p/>
 * <p/>
 * Copyright (c) 2012-2013 by Paul S. Hawke, 2001,2005-2013 by Jarno Elonen, 2010 by Konstantinos Togias All rights reserved.
 * <p/>
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 * <p/>
 * Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 * disclaimer.
 * <p/>
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * <p/>
 * Neither the name of the NanoHttpd organization nor the names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written permission.
 * <p/>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * <p/>
 *
 * <p>Portions (CountingOutputStream) Copyright (C) 2007 The Guava Authors
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
* under the License.
 */
public abstract class NanoHTTPD {
    /**
     * Maximum time to wait on Socket.getInputStream().read() (in milliseconds)
     * This is required as the Keep-Alive HTTP connections would otherwise
     * block the socket reading thread forever (or as long the browser is open).
     */
    public static final int SOCKET_READ_TIMEOUT = 5000;
    /**
     * Common mime type for dynamic content: plain text
     */
    public static final String MIME_PLAINTEXT = "text/plain";
    /**
     * Common mime type for dynamic content: html
     */
    public static final String MIME_HTML = "text/html";
    /**
     * Pseudo-Parameter to use to store the actual query string in the parameters map for later re-processing.
     */
    private static final String QUERY_STRING_PARAMETER = "NanoHttpd.QUERY_STRING";
    private final String hostname;
    private final int myPort;
    private ServerSocket myServerSocket;
    private Set<Socket> openConnections = new HashSet<Socket>();
    private Thread myThread;
    /**
     * Pluggable strategy for asynchronously executing requests.
     */
    private Executor asyncRunner;

    /**
     * Constructs an HTTP server on given port.
     */
    public NanoHTTPD(int port) {
        this(null, port);
    }

    /**
     * Constructs an HTTP server on given hostname and port.
     */
    public NanoHTTPD(String hostname, int port) {
        this.hostname = hostname;
        this.myPort = port;
        setAsyncRunner(new DefaultAsyncRunner());
    }

    public NanoHTTPD(ServerSocket serverSocket) {
        if(serverSocket.isBound() && !serverSocket.isClosed()) {
            this.myServerSocket = serverSocket;
            this.myPort = serverSocket.getLocalPort();
            this.hostname = serverSocket.getInetAddress().getHostName();
            this.setAsyncRunner(new DefaultAsyncRunner());
        } else {
            throw new IllegalArgumentException("socket must be open and bound");
        }
    }

    private static final void safeClose(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
            }
        }
    }

    private static final void safeClose(Socket closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
            }
        }
    }

    private static final void safeClose(ServerSocket closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
            }
        }
    }

    /**
     * Start the server.
     *
     * @throws IOException if the socket is in use.
     */
    public void start() throws IOException {
        if (myServerSocket == null) {
            myServerSocket = new ServerSocket();
            myServerSocket.setReuseAddress(true);
            myServerSocket.bind((hostname != null) ? new InetSocketAddress(hostname, myPort) : new InetSocketAddress(myPort));
        }

        myThread = new Thread(new Runnable() {
            @Override
            public void run() {
                do {
                    try {
                        final Socket finalAccept = myServerSocket.accept();
                        registerConnection(finalAccept);
                        finalAccept.setSoTimeout(SOCKET_READ_TIMEOUT);
                        final InputStream inputStream = finalAccept.getInputStream();
                        if (asyncRunner instanceof ThreadPoolExecutor) {
                            ThreadPoolExecutor pool = (ThreadPoolExecutor) asyncRunner;
                            int queuedRequests = pool.getQueue().size();
                            if (queuedRequests > 0) {
                                System.err.println(new Date() + " " + queuedRequests
                                        + " requests queued, all " + pool.getMaximumPoolSize()
                                        + " web server threads are busy");
                            }
                        }
                        asyncRunner.execute(new Runnable() {
                            @Override
                            public void run() {
                                OutputStream outputStream = null;
                                try {
                                    outputStream = finalAccept.getOutputStream();
                                    HTTPSession session = new HTTPSession(inputStream, outputStream, finalAccept.getInetAddress());
                                    while (!finalAccept.isClosed()) {
                                        session.execute();
                                    }
                                } catch (Exception e) {
                                    // When the socket is closed by the client, we throw our own SocketException
                                    // to break the  "keep alive" loop above.
                                    if (!(e instanceof SocketException && "NanoHttpd Shutdown".equals(e.getMessage()))) {
                                        e.printStackTrace();
                                    }
                                } finally {
                                    safeClose(outputStream);
                                    safeClose(inputStream);
                                    safeClose(finalAccept);
                                    unRegisterConnection(finalAccept);
                                }
                            }
                        });
                    } catch (IOException e) {
                    }
                } while (!myServerSocket.isClosed());
            }
        });
        myThread.setDaemon(true);
        myThread.setName("NanoHttpd Main Listener");
        myThread.start();
    }

    /**
     * Stop the server.
     */
    public void stop() {
        try {
            safeClose(myServerSocket);
            closeAllConnections();
            if (myThread != null) {
                myThread.join();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Registers that a new connection has been set up.
     *
     * @param socket the {@link Socket} for the connection.
     */
    public synchronized void registerConnection(Socket socket) {
        openConnections.add(socket);
    }

    /**
     * Registers that a connection has been closed
     *
     * @param socket the {@link Socket} for the connection.
     */
    public synchronized void unRegisterConnection(Socket socket) {
        openConnections.remove(socket);
    }

    /**
     * Forcibly closes all connections that are open.
     */
    public synchronized void closeAllConnections() {
        for (Socket socket : openConnections) {
            safeClose(socket);
        }
    }

    public final int getListeningPort() {
        return myServerSocket == null ? -1 : myServerSocket.getLocalPort();
    }

    public final boolean wasStarted() {
        return myServerSocket != null && myThread != null;
    }

    public final boolean isAlive() {
        return wasStarted() && !myServerSocket.isClosed() && myThread.isAlive();
    }


    /**
     * Override this to customize the server.
     *
     * @param session The HTTP session
     * @return HTTP response, see class Response for details
     */
    public abstract Response serve(IHTTPSession session);

    /**
     * Decode percent encoded <code>String</code> values.
     *
     * @param str the percent encoded <code>String</code>
     * @return expanded form of the input, for example "foo%20bar" becomes "foo bar"
     */
    protected String decodePercent(String str) {
        String decoded = null;
        try {
            decoded = URLDecoder.decode(str, "UTF8");
        } catch (UnsupportedEncodingException ignored) {
        }
        return decoded;
    }

    /**
     * Decode parameters from a URL, handing the case where a single parameter name might have been
     * supplied several times, by return lists of values.  In general these lists will contain a single
     * element.
     *
     * @param parms original <b>NanoHttpd</b> parameters values, as passed to the <code>serve()</code> method.
     * @return a map of <code>String</code> (parameter name) to <code>List&lt;String&gt;</code> (a list of the values supplied).
     */
    protected Map<String, List<String>> decodeParameters(Map<String, String> parms) {
        return this.decodeParameters(parms.get(QUERY_STRING_PARAMETER));
    }

    /**
     * Decode parameters from a URL, handing the case where a single parameter name might have been
     * supplied several times, by return lists of values.  In general these lists will contain a single
     * element.
     *
     * @param queryString a query string pulled from the URL.
     * @return a map of <code>String</code> (parameter name) to <code>List&lt;String&gt;</code> (a list of the values supplied).
     */
    protected Map<String, List<String>> decodeParameters(String queryString) {
        Map<String, List<String>> parms = new HashMap<String, List<String>>();
        if (queryString != null) {
            StringTokenizer st = new StringTokenizer(queryString, "&");
            while (st.hasMoreTokens()) {
                String e = st.nextToken();
                int sep = e.indexOf('=');
                String propertyName = (sep >= 0) ? decodePercent(e.substring(0, sep)).trim() : decodePercent(e).trim();
                if (!parms.containsKey(propertyName)) {
                    parms.put(propertyName, new ArrayList<String>());
                }
                String propertyValue = (sep >= 0) ? decodePercent(e.substring(sep + 1)) : null;
                if (propertyValue != null) {
                    parms.get(propertyName).add(propertyValue);
                }
            }
        }
        return parms;
    }

    // ------------------------------------------------------------------------------- //
    //
    // Threading Strategy.
    //
    // ------------------------------------------------------------------------------- //

    /**
     * Pluggable strategy for asynchronously executing requests.
     *
     * @param asyncRunner new strategy for handling threads.
     */
    public void setAsyncRunner(Executor asyncRunner) {
        this.asyncRunner = asyncRunner;
    }

    /**
     * HTTP Request methods, with the ability to decode a <code>String</code> back to its enum value.
     */
    public enum Method {
        GET, PUT, POST, DELETE, HEAD, OPTIONS;

        static Method lookup(String method) {
            for (Method m : Method.values()) {
                if (m.toString().equalsIgnoreCase(method)) {
                    return m;
                }
            }
            return null;
        }
    }


    // ------------------------------------------------------------------------------- //

    /**
     * Default threading strategy for NanoHttpd.
     * <p/>
     * <p>By default, the server spawns a new Thread for every incoming request.  These are set
     * to <i>daemon</i> status, and named according to the request number.  The name is
     * useful when profiling the application.</p>
     */
    public static class DefaultAsyncRunner implements Executor {
        private long requestCount;

        @Override
        public void execute(Runnable code) {
            ++requestCount;
            Thread t = new Thread(code);
            t.setDaemon(true);
            t.setName("NanoHttpd Request Processor (#" + requestCount + ")");
            t.start();
        }
    }

    public interface IStreamer {
        void stream(OutputStream out) throws IOException;
    }

    /**
     * HTTP response. Return one of these from serve().
     */
    public static class Response {
        /**
         * HTTP status code after processing, e.g. "200 OK", HTTP_OK
         */
        private IStatus status;
        /**
         * MIME type of content, e.g. "text/html"
         */
        private String mimeType;
        /**
         * Data of the response, may be null.
         */
        private InputStream data;
        /**
         * Callback for streaming body, may be null.
         */
        private IStreamer streamer;
        /**
         * Headers for the HTTP response. Use addHeader() to add lines.
         */
        private Map<String, String> header = new HashMap<String, String>();
        /**
         * The request method that spawned this response.
         */
        private Method requestMethod;
        /**
         * Use chunkedTransfer
         */
        private boolean chunkedTransfer;

        /**
         * Url that spawned this response.
         */
        public String url;
        public String remoteAddr;

        /**
         * Default constructor: response = HTTP_OK, mime = MIME_HTML and your supplied message
         */
        public Response(String msg) {
            this(Status.OK, MIME_HTML, msg);
        }

        /**
         * Basic constructor.
         */
        public Response(IStatus status, String mimeType, InputStream data) {
            this.status = status;
            this.mimeType = mimeType;
            this.data = data;
        }

        public Response(IStatus status, String mimeType, IStreamer streamer) {
            this.status = status;
            this.mimeType = mimeType;
            this.streamer = streamer;
            chunkedTransfer = true;
        }

        /**
         * Convenience method that makes an InputStream out of given text.
         */
        public Response(IStatus status, String mimeType, String txt) {
            this.status = status;
            this.mimeType = mimeType;
            try {
                this.data = txt != null ? new ByteArrayInputStream(txt.getBytes("UTF-8")) : null;
            } catch (java.io.UnsupportedEncodingException uee) {
                uee.printStackTrace();
            }
        }

        /**
         * Adds given line to the header.
         */
        public void addHeader(String name, String value) {
            header.put(name, value);
        }

        public String getHeader(String name) {
            return header.get(name);
        }

        /**
         * Sends given response to the socket.
         */
        protected void send(OutputStream rawOut) {
            String mime = mimeType;
            SimpleDateFormat gmtFrmt = new SimpleDateFormat("E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            gmtFrmt.setTimeZone(TimeZone.getTimeZone("GMT"));

            try {
                if (status == null) {
                    throw new Error("sendResponse(): Status can't be null.");
                }
                PrintWriter pw = new PrintWriter(rawOut);
                pw.print("HTTP/1.1 " + status.getDescription() + " \r\n");

                if (mime != null) {
                    pw.print("Content-Type: " + mime + "\r\n");
                }

                if (header == null || header.get("Date") == null) {
                    pw.print("Date: " + gmtFrmt.format(new Date()) + "\r\n");
                }

                if (header != null) {
                    for (String key : header.keySet()) {
                        String value = header.get(key);
                        pw.print(key + ": " + value + "\r\n");
                    }
                }

                sendConnectionHeaderIfNotAlreadyPresent(pw, header);

                if (requestMethod != Method.HEAD && chunkedTransfer) {
                    pw.print("Transfer-Encoding: chunked\r\n");
                    pw.print("\r\n");
                    pw.flush();
                    if (streamer != null) {
                        ChunkingOutputStream chunker = new ChunkingOutputStream(rawOut);
                        try {
                            streamer.stream(chunker);
                        } finally {
                            rawOut.write(String.format("0\r\n\r\n").getBytes());
                            rawOut.flush();
                        }
                    } else {
                        sendAsChunked(rawOut);
                    }
                } else {
                    int pending = data != null ? data.available() : 0;
                    sendContentLengthHeaderIfNotAlreadyPresent(pw, header, pending);
                    pw.print("\r\n");
                    pw.flush();
                    sendAsFixedLength(rawOut, pending);
                }
                rawOut.flush();
                safeClose(data);
            } catch (IOException ioe) {
                // Couldn't write? No can do.
            }
        }

        protected void sendContentLengthHeaderIfNotAlreadyPresent(PrintWriter pw, Map<String, String> header, int size) {
            if (!headerAlreadySent(header, "content-length")) {
                pw.print("Content-Length: " + size + "\r\n");
            }
        }

        protected void sendConnectionHeaderIfNotAlreadyPresent(PrintWriter pw, Map<String, String> header) {
            if (!headerAlreadySent(header, "connection")) {
                pw.print("Connection: keep-alive\r\n");
            }
        }

        private boolean headerAlreadySent(Map<String, String> header, String name) {
            boolean alreadySent = false;
            for (String headerName : header.keySet()) {
                alreadySent |= headerName.equalsIgnoreCase(name);
            }
            return alreadySent;
        }

        private void sendAsChunked(OutputStream outputStream) throws IOException {
            int BUFFER_SIZE = 16 * 1024;
            byte[] CRLF = "\r\n".getBytes();
            byte[] buff = new byte[BUFFER_SIZE];
            int read;
            while ((read = data.read(buff)) > 0) {
                outputStream.write(String.format("%x\r\n", read).getBytes());
                outputStream.write(buff, 0, read);
                outputStream.write(CRLF);
            }
            outputStream.write(String.format("0\r\n\r\n").getBytes());
        }

        private void sendAsFixedLength(OutputStream outputStream, int pending) throws IOException {
            if (requestMethod != Method.HEAD && data != null) {
                int BUFFER_SIZE = 16 * 1024;
                byte[] buff = new byte[BUFFER_SIZE];
                while (pending > 0) {
                    int read = data.read(buff, 0, ((pending > BUFFER_SIZE) ? BUFFER_SIZE : pending));
                    if (read <= 0) {
                        break;
                    }
                    outputStream.write(buff, 0, read);
                    pending -= read;
                }
            }
        }

        public IStatus getStatus() {
            return status;
        }

        public void setStatus(Status status) {
            this.status = status;
        }

        public String getMimeType() {
            return mimeType;
        }

        public void setMimeType(String mimeType) {
            this.mimeType = mimeType;
        }

        public InputStream getData() {
            return data;
        }

        public void setData(InputStream data) {
            this.data = data;
        }

        public Method getRequestMethod() {
            return requestMethod;
        }

        public void setRequestMethod(Method requestMethod) {
            this.requestMethod = requestMethod;
        }

        public void setChunkedTransfer(boolean chunkedTransfer) {
            this.chunkedTransfer = chunkedTransfer;
        }

        public IStreamer getStreamer() {
            return streamer;
        }

        public interface IStatus {
            int getRequestStatus();

            String getDescription();
        }

        /**
         * Some HTTP response status codes
         */
        public enum Status implements IStatus {
            SWITCH_PROTOCOL(101, "Switching Protocols"), OK(200, "OK"), CREATED(201, "Created"), ACCEPTED(202, "Accepted"), NO_CONTENT(204, "No Content"), PARTIAL_CONTENT(206, "Partial Content"), REDIRECT(301,
                    "Moved Permanently"), NOT_MODIFIED(304, "Not Modified"), TEMPORARY_REDIRECT(307, "Temporary Redirect"), BAD_REQUEST(400, "Bad Request"), UNAUTHORIZED(401,
                    "Unauthorized"), FORBIDDEN(403, "Forbidden"), NOT_FOUND(404, "Not Found"), METHOD_NOT_ALLOWED(405, "Method Not Allowed"), PAYLOAD_TOO_LARGE(413, "Payload Too Large"), RANGE_NOT_SATISFIABLE(416,
                    "Requested Range Not Satisfiable"), INTERNAL_ERROR(500, "Internal Server Error");
            private final int requestStatus;
            private final String description;

            Status(int requestStatus, String description) {
                this.requestStatus = requestStatus;
                this.description = description;
            }

            @Override
            public int getRequestStatus() {
                return this.requestStatus;
            }

            @Override
            public String getDescription() {
                return "" + this.requestStatus + " " + description;
            }
        }
    }

    public static final class ChunkingOutputStream extends OutputStream {
        OutputStream out;

        public ChunkingOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void write(byte[] bytes, int start, int length) throws IOException {
            out.write(String.format("%x\r\n", length).getBytes(StandardCharsets.US_ASCII));
            out.write(bytes, start, length);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public void write(int i) throws IOException {
            out.write("1\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(i);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    public static final class ResponseException extends Exception {

        private final Response.Status status;

        public ResponseException(Response.Status status, String message) {
            super(message);
            this.status = status;
        }

        public ResponseException(Response.Status status, String message, Exception e) {
            super(message, e);
            this.status = status;
        }

        public Response.Status getStatus() {
            return status;
        }
    }

    /**
     * Handles one session, i.e. parses the HTTP request and returns the response.
     */
    public interface IHTTPSession {
        void execute() throws IOException;

        MultiMap<String, String> getParms();

        Map<String, String> getHeaders();

        /**
         * @return the path part of the URL.
         */
        String getUri();

        String getQueryParameterString();

        Method getMethod();

        InputStream getInputStream();
    }

    protected class HTTPSession implements IHTTPSession {
        public static final int BUFSIZE = 8192;
        private final OutputStream outputStream;
        private CountingInputStream countingInputStream;
        private PushbackInputStream inputStream;
        private BoundedInputStream bodyStream;
        private int splitbyte;
        private int rlen;
        private String uri;
        private Method method;
        private MultiMap<String, String> parms;
        private Map<String, String> headers;
        private String queryParameterString;
        protected String remoteIp;

        public HTTPSession(InputStream inputStream, OutputStream outputStream) {
            this.inputStream = new PushbackInputStream(inputStream, BUFSIZE);
            this.outputStream = outputStream;
        }

        public HTTPSession(InputStream inputStream, OutputStream outputStream, InetAddress inetAddress) {
            countingInputStream = new CountingInputStream(inputStream);
            this.inputStream = new PushbackInputStream(countingInputStream, BUFSIZE);
            this.outputStream = outputStream;

            remoteIp = inetAddress.isLoopbackAddress() || inetAddress.isAnyLocalAddress() ? "127.0.0.1" : inetAddress.getHostAddress().toString();

            headers = new HashMap<String, String>();
            headers.put("remote-addr", remoteIp);
            headers.put("http-client-ip", remoteIp);
        }

        @Override
        public void execute() throws IOException {
            long start = System.currentTimeMillis();

            try {
                // Read the first 8192 bytes.
                // The full header should fit in here.
                // Apache's default header limit is 8KB.
                // Do NOT assume that a single read will get the entire header at once!
                byte[] buf = new byte[BUFSIZE];
                splitbyte = 0;
                rlen = 0;
                {
                    int read = -1;
                    try {
                        read = inputStream.read(buf, 0, BUFSIZE);
                    } catch (Exception e) {
                        safeClose(inputStream);
                        safeClose(outputStream);
                        throw new SocketException("NanoHttpd Shutdown");
                    }
                    if (read == -1) {
                        // socket was been closed
                        safeClose(inputStream);
                        safeClose(outputStream);
                        throw new SocketException("NanoHttpd Shutdown");
                    }
                    while (read > 0) {
                        rlen += read;
                        splitbyte = findHeaderEnd(buf, rlen);
                        if (splitbyte > 0)
                            break;
                        if (rlen >= BUFSIZE) {
                            throw new ResponseException(Response.Status.PAYLOAD_TOO_LARGE, "Request too large");
                        }
                        read = inputStream.read(buf, rlen, BUFSIZE - rlen);
                    }
                }

                if (splitbyte < rlen) {
                    inputStream.unread(buf, splitbyte, rlen - splitbyte);
                }

                parms = new MultiMap<String, String>();
                if (null == headers) {
                    headers = new HashMap<String, String>();
                } else {
                    headers.clear();
                }
                headers.put("remote-addr", remoteIp);
                headers.put("http-client-ip", remoteIp);

                // Create a BufferedReader for parsing the header.
                BufferedReader hin = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(buf, 0, rlen)));

                // Decode the header into parms and header java properties
                Map<String, String> pre = new HashMap<String, String>();
                decodeHeader(hin, pre, parms, headers);

                method = Method.lookup(pre.get("method"));
                if (method == null) {
                    throw new ResponseException(Response.Status.BAD_REQUEST, "BAD REQUEST: Syntax error.");
                }

                uri = pre.get("uri");
                long contentLength =  Long.parseLong(headers.getOrDefault("content-length", "0"));
                bodyStream = new BoundedInputStream(inputStream, contentLength);
                bodyStream.setPropagateClose(false);

                // Ok, now do the serve()
                Response r = serve(this);
                if (getQueryParameterString() != null) {
                    r.url = getUri() + "?" + getQueryParameterString();
                } else {
                    r.url = getUri();
                }
                r.remoteAddr = remoteIp;

                // ensure body is consumed
                if (contentLength > 0) {
                    bodyStream.skip(contentLength);
                }

                CountingOutputStream countingOut = new CountingOutputStream(outputStream);
                if (r == null) {
                    throw new ResponseException(Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: Serve() returned a null response.");
                } else {
                    r.setRequestMethod(method);
                    r.send(countingOut);
                }

                String elapsed = String.format("%.3f", 1.0 * (System.currentTimeMillis() - start) / 1000);
                String msg = new Date() + " " + r.remoteAddr + " " + r.status.getRequestStatus() + " "
                        + (countingInputStream.count + countingOut.count) + " " + elapsed + "s "
                        + r.requestMethod + " " + r.url;
                if (r.getHeader("outbackcdx-urlkey") != null) {
                    msg += " urlkey=" + r.getHeader("outbackcdx-urlkey");
                }
                System.out.println(msg);

            } catch (SocketException e) {
                // throw it out to close socket object (finalAccept)
                throw e;
            } catch (SocketTimeoutException ste) {
                throw ste;
            } catch (IOException ioe) {
                Response r = new Response(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
                r.url = getUri() + "?" + getQueryParameterString();
                r.remoteAddr = remoteIp;
                r.send(outputStream);
                safeClose(outputStream);
            } catch (ResponseException re) {
                Response r = new Response(re.getStatus(), MIME_PLAINTEXT, re.getMessage());
                r.url = getUri() + "?" + getQueryParameterString();
                r.remoteAddr = remoteIp;
                r.send(outputStream);
                safeClose(outputStream);
            }
        }

        /**
         * Decodes the sent headers and loads the data into Key/value pairs
         */
        private void decodeHeader(BufferedReader in, Map<String, String> pre, MultiMap<String, String> parms, Map<String, String> headers)
                throws ResponseException {
            try {
                // Read the request line
                String inLine = in.readLine();
                if (inLine == null) {
                    return;
                }

                StringTokenizer st = new StringTokenizer(inLine);
                if (!st.hasMoreTokens()) {
                    throw new ResponseException(Response.Status.BAD_REQUEST, "BAD REQUEST: Syntax error. Usage: GET /example/file.html");
                }

                pre.put("method", st.nextToken());

                if (!st.hasMoreTokens()) {
                    throw new ResponseException(Response.Status.BAD_REQUEST, "BAD REQUEST: Missing URI. Usage: GET /example/file.html");
                }

                String uri = st.nextToken();

                // Decode parameters from the URI
                int qmi = uri.indexOf('?');
                if (qmi >= 0) {
                    decodeParms(uri.substring(qmi + 1), parms);
                    uri = decodePercent(uri.substring(0, qmi));
                } else {
                    uri = decodePercent(uri);
                }

                // If there's another token, it's protocol version,
                // followed by HTTP headers. Ignore version but parse headers.
                // NOTE: this now forces header names lowercase since they are
                // case insensitive and vary by client.
                if (st.hasMoreTokens()) {
                    String line = in.readLine();
                    while (line != null && line.trim().length() > 0) {
                        int p = line.indexOf(':');
                        if (p >= 0)
                            headers.put(line.substring(0, p).trim().toLowerCase(Locale.US), line.substring(p + 1).trim());
                        line = in.readLine();
                    }
                }

                pre.put("uri", uri);
            } catch (IOException ioe) {
                throw new ResponseException(Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage(), ioe);
            }
        }

        /**
         * Find byte index separating header from body. It must be the last byte of the first two sequential new lines.
         */
        private int findHeaderEnd(final byte[] buf, int rlen) {
            int splitbyte = 0;
            while (splitbyte + 3 < rlen) {
                if (buf[splitbyte] == '\r' && buf[splitbyte + 1] == '\n' && buf[splitbyte + 2] == '\r' && buf[splitbyte + 3] == '\n') {
                    return splitbyte + 4;
                }
                splitbyte++;
            }
            return 0;
        }

        /**
         * Find the byte positions where multipart boundaries start.
         */
        private int[] getBoundaryPositions(ByteBuffer b, byte[] boundary) {
            int matchcount = 0;
            int matchbyte = -1;
            List<Integer> matchbytes = new ArrayList<Integer>();
            for (int i = 0; i < b.limit(); i++) {
                if (b.get(i) == boundary[matchcount]) {
                    if (matchcount == 0)
                        matchbyte = i;
                    matchcount++;
                    if (matchcount == boundary.length) {
                        matchbytes.add(matchbyte);
                        matchcount = 0;
                        matchbyte = -1;
                    }
                } else {
                    i -= matchcount;
                    matchcount = 0;
                    matchbyte = -1;
                }
            }
            int[] ret = new int[matchbytes.size()];
            for (int i = 0; i < ret.length; i++) {
                ret[i] = matchbytes.get(i);
            }
            return ret;
        }

        /**
         * It returns the offset separating multipart file headers from the file's data.
         */
        private int stripMultipartHeaders(ByteBuffer b, int offset) {
            int i;
            for (i = offset; i < b.limit(); i++) {
                if (b.get(i) == '\r' && b.get(++i) == '\n' && b.get(++i) == '\r' && b.get(++i) == '\n') {
                    break;
                }
            }
            return i + 1;
        }

        /**
         * Decodes parameters in percent-encoded URI-format ( e.g. "name=Jack%20Daniels&pass=Single%20Malt" ) and
         * adds them to given Map. NOTE: this doesn't support multiple identical keys due to the simplicity of Map.
         */
        private void decodeParms(String parms, MultiMap<String, String> p) {
            if (parms == null) {
                queryParameterString = "";
                return;
            }

            queryParameterString = parms;
            StringTokenizer st = new StringTokenizer(parms, "&");
            while (st.hasMoreTokens()) {
                String e = st.nextToken();
                int sep = e.indexOf('=');
                if (sep >= 0) {
                    p.add(decodePercent(e.substring(0, sep)).trim(),
                            decodePercent(e.substring(sep + 1)));
                } else {
                    p.add(decodePercent(e).trim(), "");
                }
            }
        }

        @Override
        public final MultiMap<String, String> getParms() {
            return parms;
        }

        public String getQueryParameterString() {
            return queryParameterString;
        }

        @Override
        public final Map<String, String> getHeaders() {
            return headers;
        }

        @Override
        public final String getUri() {
            return uri;
        }

        @Override
        public final Method getMethod() {
            return method;
        }

        @Override
        public final InputStream getInputStream() {
            return bodyStream;
        }
    }

    /**
     * An OutputStream that counts the number of bytes written. Copied from
     * guava.
     *
     * @author Chris Nokleberg
     */
    static final class CountingOutputStream extends FilterOutputStream {
        long count = 0l;

        public CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }
    }

    // copied from guava
    static final class CountingInputStream extends FilterInputStream {
        long count;
        private long mark = -1;

        public CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int result = in.read();
            if (result != -1) {
                count++;
            }
            return result;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int result = in.read(b, off, len);
            if (result != -1) {
                count += result;
            }
            return result;
        }

        @Override
        public long skip(long n) throws IOException {
            long result = in.skip(n);
            count += result;
            return result;
        }

        @Override
        public synchronized void mark(int readlimit) {
            in.mark(readlimit);
            mark = count;
            // it's okay to mark even if mark isn't supported, as reset won't work
        }

        @Override
        public synchronized void reset() throws IOException {
            if (!in.markSupported()) {
                throw new IOException("Mark not supported");
            }
            if (mark == -1) {
                throw new IOException("Mark not set");
            }

            in.reset();
            count = mark;
        }
    }
}
