/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package java.net;

import dalvik.system.CloseGuard;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketImpl;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteOrder;
import java.util.Arrays;
import libcore.io.Streams;
import org.apache.harmony.luni.platform.OSMemory;
import org.apache.harmony.luni.platform.Platform;

/**
 * @hide used in java.nio.
 */
public class PlainSocketImpl extends SocketImpl {

    // For SOCKS support. A SOCKS bind() uses the last
    // host connected to in its request.
    private static InetAddress lastConnectedAddress;

    private static int lastConnectedPort;

    private boolean streaming = true;

    private boolean shutdownInput;

    private Proxy proxy;

    private final CloseGuard guard = CloseGuard.get();

    public PlainSocketImpl(FileDescriptor fd) {
        this.fd = fd;
        if (fd.valid()) {
            guard.open("close");
        }
    }

    public PlainSocketImpl(Proxy proxy) {
        this(new FileDescriptor());
        this.proxy = proxy;
    }

    public PlainSocketImpl() {
        this(new FileDescriptor());
    }

    public PlainSocketImpl(FileDescriptor fd, int localport, InetAddress addr, int port) {
        super();
        this.fd = fd;
        this.localport = localport;
        this.address = addr;
        this.port = port;
        if (fd.valid()) {
            guard.open("close");
        }
    }

    @Override
    protected void accept(SocketImpl newImpl) throws IOException {
        if (usingSocks()) {
            ((PlainSocketImpl) newImpl).socksBind();
            ((PlainSocketImpl) newImpl).socksAccept();
            return;
        }
        Platform.NETWORK.accept(fd, newImpl, newImpl.getFileDescriptor());
    }

    private boolean usingSocks() {
        return proxy != null && proxy.type() == Proxy.Type.SOCKS;
    }

    public void initLocalPort(int localPort) {
        this.localport = localPort;
    }

    public void initRemoteAddressAndPort(InetAddress remoteAddress, int remotePort) {
        this.address = remoteAddress;
        this.port = remotePort;
    }

    private void checkNotClosed() throws IOException {
        if (!fd.valid()) {
            throw new SocketException("Socket is closed");
        }
    }

    @Override
    protected synchronized int available() throws IOException {
        checkNotClosed();
        // we need to check if the input has been shutdown. If so
        // we should return that there is no data to be read
        if (shutdownInput) {
            return 0;
        }
        return Platform.FILE_SYSTEM.ioctlAvailable(fd);
    }

    @Override
    protected void bind(InetAddress address, int port) throws IOException {
        Platform.NETWORK.bind(fd, address, port);
        this.address = address;
        if (port != 0) {
            this.localport = port;
        } else {
            this.localport = Platform.NETWORK.getSocketLocalPort(fd);
        }
    }

    @Override
    protected synchronized void close() throws IOException {
        guard.close();
        Platform.NETWORK.close(fd);
    }

    @Override
    protected void connect(String aHost, int aPort) throws IOException {
        connect(InetAddress.getByName(aHost), aPort);
    }

    @Override
    protected void connect(InetAddress anAddr, int aPort) throws IOException {
        connect(anAddr, aPort, 0);
    }

    /**
     * Connects this socket to the specified remote host address/port.
     *
     * @param anAddr
     *            the remote host address to connect to
     * @param aPort
     *            the remote port to connect to
     * @param timeout
     *            a timeout where supported. 0 means no timeout
     * @throws IOException
     *             if an error occurs while connecting
     */
    private void connect(InetAddress anAddr, int aPort, int timeout) throws IOException {
        InetAddress normalAddr = anAddr.isAnyLocalAddress() ? InetAddress.getLocalHost() : anAddr;
        try {
            if (streaming && usingSocks()) {
                socksConnect(anAddr, aPort, 0);
            } else {
                Platform.NETWORK.connect(fd, normalAddr, aPort, timeout);
            }
        } catch (ConnectException e) {
            throw new ConnectException(anAddr + ":" + aPort + " - " + e.getMessage());
        }
        super.address = normalAddr;
        super.port = aPort;
    }

    @Override
    protected void create(boolean streaming) throws IOException {
        this.streaming = streaming;
        Platform.NETWORK.socket(fd, streaming);
    }

    @Override protected void finalize() throws Throwable {
        try {
            if (guard != null) {
                guard.warnIfOpen();
            }
            close();
        } finally {
            super.finalize();
        }
    }

    @Override protected synchronized InputStream getInputStream() throws IOException {
        checkNotClosed();
        return new PlainSocketInputStream(this);
    }

    private static class PlainSocketInputStream extends InputStream {
        private final PlainSocketImpl socketImpl;

        public PlainSocketInputStream(PlainSocketImpl socketImpl) {
            this.socketImpl = socketImpl;
        }

        @Override public int available() throws IOException {
            return socketImpl.available();
        }

        @Override public void close() throws IOException {
            socketImpl.close();
        }

        @Override public int read() throws IOException {
            return Streams.readSingleByte(this);
        }

        @Override public int read(byte[] buffer, int offset, int byteCount) throws IOException {
            return socketImpl.read(buffer, offset, byteCount);
        }
    }

    @Override
    public Object getOption(int optID) throws SocketException {
        return Platform.NETWORK.getSocketOption(fd, optID);
    }

    @Override protected synchronized OutputStream getOutputStream() throws IOException {
        checkNotClosed();
        return new PlainSocketOutputStream(this);
    }

    private static class PlainSocketOutputStream extends OutputStream {
        private final PlainSocketImpl socketImpl;

        public PlainSocketOutputStream(PlainSocketImpl socketImpl) {
            this.socketImpl = socketImpl;
        }

        @Override public void close() throws IOException {
            socketImpl.close();
        }

        @Override public void write(int oneByte) throws IOException {
            Streams.writeSingleByte(this, oneByte);
        }

        @Override public void write(byte[] buffer, int offset, int byteCount) throws IOException {
            socketImpl.write(buffer, offset, byteCount);
        }
    }

    @Override
    protected void listen(int backlog) throws IOException {
        if (usingSocks()) {
            // Do nothing for a SOCKS connection. The listen occurs on the
            // server during the bind.
            return;
        }
        Platform.NETWORK.listen(fd, backlog);
    }

    @Override
    public void setOption(int optID, Object val) throws SocketException {
        Platform.NETWORK.setSocketOption(fd, optID, val);
    }

    /**
     * Gets the SOCKS proxy server port.
     */
    private int socksGetServerPort() {
        // get socks server port from proxy. It is unnecessary to check
        // "socksProxyPort" property, since proxy setting should only be
        // determined by ProxySelector.
        InetSocketAddress addr = (InetSocketAddress) proxy.address();
        return addr.getPort();
    }

    /**
     * Gets the InetAddress of the SOCKS proxy server.
     */
    private InetAddress socksGetServerAddress() throws UnknownHostException {
        String proxyName;
        // get socks server address from proxy. It is unnecessary to check
        // "socksProxyHost" property, since all proxy setting should be
        // determined by ProxySelector.
        InetSocketAddress addr = (InetSocketAddress) proxy.address();
        proxyName = addr.getHostName();
        if (proxyName == null) {
            proxyName = addr.getAddress().getHostAddress();
        }
        return InetAddress.getByName(proxyName);
    }

    /**
     * Connect using a SOCKS server.
     */
    private void socksConnect(InetAddress applicationServerAddress,
            int applicationServerPort, int timeout) throws IOException {
        try {
            Platform.NETWORK.connect(fd, socksGetServerAddress(), socksGetServerPort(), timeout);
        } catch (Exception e) {
            throw new SocketException("SOCKS connection failed: " + e);
        }

        socksRequestConnection(applicationServerAddress, applicationServerPort);

        lastConnectedAddress = applicationServerAddress;
        lastConnectedPort = applicationServerPort;
    }

    /**
     * Request a SOCKS connection to the application server given. If the
     * request fails to complete successfully, an exception is thrown.
     */
    private void socksRequestConnection(InetAddress applicationServerAddress,
            int applicationServerPort) throws IOException {
        socksSendRequest(Socks4Message.COMMAND_CONNECT,
                applicationServerAddress, applicationServerPort);
        Socks4Message reply = socksReadReply();
        if (reply.getCommandOrResult() != Socks4Message.RETURN_SUCCESS) {
            throw new IOException(reply.getErrorString(reply.getCommandOrResult()));
        }
    }

    /**
     * Perform an accept for a SOCKS bind.
     */
    public void socksAccept() throws IOException {
        Socks4Message reply = socksReadReply();
        if (reply.getCommandOrResult() != Socks4Message.RETURN_SUCCESS) {
            throw new IOException(reply.getErrorString(reply.getCommandOrResult()));
        }
    }

    /**
     * Shutdown the input portion of the socket.
     */
    @Override
    protected void shutdownInput() throws IOException {
        shutdownInput = true;
        Platform.NETWORK.shutdownInput(fd);
    }

    /**
     * Shutdown the output portion of the socket.
     */
    @Override
    protected void shutdownOutput() throws IOException {
        Platform.NETWORK.shutdownOutput(fd);
    }

    /**
     * Bind using a SOCKS server.
     */
    private void socksBind() throws IOException {
        try {
            Platform.NETWORK.connect(fd, socksGetServerAddress(), socksGetServerPort(), 0);
        } catch (Exception e) {
            throw new IOException("Unable to connect to SOCKS server: " + e);
        }

        // There must be a connection to an application host for the bind to
        // work.
        if (lastConnectedAddress == null) {
            throw new SocketException("Invalid SOCKS client");
        }

        // Use the last connected address and port in the bind request.
        socksSendRequest(Socks4Message.COMMAND_BIND, lastConnectedAddress,
                lastConnectedPort);
        Socks4Message reply = socksReadReply();

        if (reply.getCommandOrResult() != Socks4Message.RETURN_SUCCESS) {
            throw new IOException(reply.getErrorString(reply.getCommandOrResult()));
        }

        // A peculiarity of socks 4 - if the address returned is 0, use the
        // original socks server address.
        if (reply.getIP() == 0) {
            address = socksGetServerAddress();
        } else {
            // IPv6 support not yet required as
            // currently the Socks4Message.getIP() only returns int,
            // so only works with IPv4 4byte addresses
            byte[] replyBytes = new byte[4];
            OSMemory.pokeInt(replyBytes, 0, reply.getIP(), ByteOrder.BIG_ENDIAN);
            address = InetAddress.getByAddress(replyBytes);
        }
        localport = reply.getPort();
    }

    /**
     * Send a SOCKS V4 request.
     */
    private void socksSendRequest(int command, InetAddress address, int port) throws IOException {
        Socks4Message request = new Socks4Message();
        request.setCommandOrResult(command);
        request.setPort(port);
        request.setIP(address.getAddress());
        request.setUserId("default");

        getOutputStream().write(request.getBytes(), 0, request.getLength());
    }

    /**
     * Read a SOCKS V4 reply.
     */
    private Socks4Message socksReadReply() throws IOException {
        Socks4Message reply = new Socks4Message();
        int bytesRead = 0;
        while (bytesRead < Socks4Message.REPLY_LENGTH) {
            int count = getInputStream().read(reply.getBytes(), bytesRead,
                    Socks4Message.REPLY_LENGTH - bytesRead);
            if (count == -1) {
                break;
            }
            bytesRead += count;
        }
        if (Socks4Message.REPLY_LENGTH != bytesRead) {
            throw new SocketException("Malformed reply from SOCKS server");
        }
        return reply;
    }

    @Override
    protected void connect(SocketAddress remoteAddr, int timeout) throws IOException {
        InetSocketAddress inetAddr = (InetSocketAddress) remoteAddr;
        connect(inetAddr.getAddress(), inetAddr.getPort(), timeout);
    }

    @Override
    protected boolean supportsUrgentData() {
        return true;
    }

    @Override
    protected void sendUrgentData(int value) throws IOException {
        Platform.NETWORK.sendUrgentData(fd, (byte) value);
    }

    /**
     * For PlainSocketInputStream.
     */
    private int read(byte[] buffer, int offset, int byteCount) throws IOException {
        if (byteCount == 0) {
            return 0;
        }
        Arrays.checkOffsetAndCount(buffer.length, offset, byteCount);
        if (shutdownInput) {
            return -1;
        }
        int read = Platform.NETWORK.read(fd, buffer, offset, byteCount);
        // Return of zero bytes for a blocking socket means a timeout occurred
        if (read == 0) {
            throw new SocketTimeoutException();
        }
        // Return of -1 indicates the peer was closed
        if (read == -1) {
            shutdownInput = true;
        }
        return read;
    }

    /**
     * For PlainSocketOutputStream.
     */
    private void write(byte[] buffer, int offset, int byteCount) throws IOException {
        Arrays.checkOffsetAndCount(buffer.length, offset, byteCount);
        if (streaming) {
            while (byteCount > 0) {
                int bytesWritten = Platform.NETWORK.write(fd, buffer, offset, byteCount);
                byteCount -= bytesWritten;
                offset += bytesWritten;
            }
        } else {
            int bytesWritten = Platform.NETWORK.send(fd, buffer, offset, byteCount, port, address);
        }
    }
}
