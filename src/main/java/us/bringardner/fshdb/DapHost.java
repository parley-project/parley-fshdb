/**
 *	Copyright 2026 Tony Bringardner
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 *	You may obtain a copy of the License at
 *
 *	http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
 *	the specific language governing permissions and limitations under the License.
 */
package us.bringardner.fshdb;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;

import us.bringardner.parley.dap.DapConnection;

/**
 * fshdb --listen: runs the script in this console (it reads and writes the terminal as usual)
 * and lets a debugger attach to it over the Debug Adapter Protocol, the way a Java program
 * started with -agentlib:jdwp lets Eclipse attach.
 * <ul>
 * <li>suspend: the script waits for a debugger's configurationDone before it starts (stopping
 *     before its first statement if the launch or attach asked for "stopOnEntry"); otherwise it
 *     starts at once and a debugger can attach while it runs.</li>
 * <li>One debugger at a time. When it detaches (disconnect), the script carries on without
 *     breakpoints and another debugger can attach.</li>
 * <li>When the script ends, fshdb tells the debugger (exited, terminated) and exits with the
 *     script's exit code.</li>
 * </ul>
 */
public class DapHost implements FshdbAdapter.Host {

	private final DebugEngine engine;
	private final InetSocketAddress address;
	private final boolean suspend;
	private final PrintStream log;

	private final CountDownLatch configured = new CountDownLatch(1);
	private volatile boolean stopOnEntry;
	private final CountDownLatch ended = new CountDownLatch(1);
	private volatile ServerSocket serverSocket;
	private volatile FshdbAdapter current;
	private volatile Socket currentSocket;
	private volatile DapConnection currentConnection;

	/**
	 * @param log where fshdb's own messages go (the console's standard error), or null for none
	 */
	public DapHost(DebugEngine engine, InetSocketAddress address, boolean suspend, PrintStream log) {
		this.engine = engine;
		this.address = address;
		this.suspend = suspend;
		this.log = log;
		engine.setListener(new DebugEngine.Listener() {
			@Override
			public void stopped(DebugEngine.StopReason reason, int fileLine, Breakpoints.Entry breakpoint) {
				FshdbAdapter s = current;
				if( s == null ) {
					// nobody to tell (a debugger left while a step was in progress): go on
					engine.detach();
				} else {
					s.stopped(reason, fileLine, breakpoint);
				}
			}

			@Override
			public void ended(int exitCode, boolean canceled, Exception error) {
				if( error != null ) {
					log("the script couldn't run: "+Debugger.message(error));
				}
				FshdbAdapter s = current;
				if( s != null ) {
					s.ended(exitCode);
				}
				DapHost.this.ended.countDown();
				// no more debuggers; the one attached is let go once it has had the events
				closeQuietly(serverSocket);
				java.lang.Thread closer = new java.lang.Thread(()->{
					try {
						java.lang.Thread.sleep(200);
					} catch (InterruptedException e) {
						// close now
					}
					closeConnection();
				}, "fshdb close");
				closer.setDaemon(true);
				closer.start();
			}

			@Override
			public void conditionFailed(int fileLine, Exception error) {
				FshdbAdapter s = current;
				if( s != null ) {
					s.output("The condition of the breakpoint on line "+fileLine+" failed ("+Debugger.message(error)+"); stopping there.");
				}
			}
		});
	}

	private void log(String msg) {
		if( log != null ) {
			log.println("[fshdb: "+msg+"]");
			log.flush();
		}
	}

	/** Opens the listening socket (before run, to learn the port when it's 0). @return the port */
	public int bind() throws IOException {
		if( serverSocket == null ) {
			ServerSocket ss = new ServerSocket();
			ss.setReuseAddress(true);
			ss.bind(address);
			serverSocket = ss;
		}
		return serverSocket.getLocalPort();
	}

	/**
	 * Runs the script and serves debuggers until it ends.
	 * @return the script's exit code
	 */
	public int run() throws IOException, InterruptedException {
		int port = bind();
		InetAddress a = serverSocket.getInetAddress();
		log("listening for a debugger (DAP) on "+(a.isAnyLocalAddress() ? "*" : a.getHostAddress())+":"+port
				+(suspend ? "; the script starts when one attaches" : ""));
		java.lang.Thread starter = new java.lang.Thread(()->{
			try {
				if( suspend ) {
					configured.await();
				}
				engine.start(true, suspend && stopOnEntry);
			} catch (InterruptedException e) {
				ended.countDown();
			} catch (RuntimeException e) {
				log(Debugger.message(e));
				ended.countDown();
				closeQuietly(serverSocket);
			}
		}, "fshdb start");
		starter.setDaemon(true);
		starter.start();

		while( ended.getCount() > 0 ) {
			Socket s;
			try {
				s = serverSocket.accept();
			} catch (SocketException e) {
				// closed: the script ended
				break;
			}
			serve(s);
		}
		ended.await();
		// let the last events reach the debugger, then close it
		java.lang.Thread.sleep(200);
		closeConnection();
		return engine.getLastExitCode();
	}

	/** Serves one debugger until it disconnects (or the script ends). */
	private void serve(Socket s) throws IOException {
		s.setTcpNoDelay(true);
		FshdbAdapter[] made = new FshdbAdapter[1];
		currentSocket = s;
		DapConnection c = DapConnection.adapter(client -> {
			made[0] = new FshdbAdapter(engine, this, client);
			current = made[0];
			return made[0];
		}, s.getInputStream(), s.getOutputStream());
		currentConnection = c;
		FshdbAdapter adapter = made[0];
		log("debugger attached from "+s.getInetAddress().getHostAddress());
		try {
			c.closed().get();
		} catch (Exception e) {
			// the connection closed
		}
		adapter.connectionLost();
		if( current == adapter ) {
			// the connection dropped without a disconnect: as a detach
			current = null;
			if( engine.isRunning()) {
				engine.getBreakpoints().clear();
				engine.detach();
			}
		}
		closeQuietly(s);
		if( engine.isRunning()) {
			log("debugger detached; the script goes on");
		}
		// a script waiting for configurationDone that never came keeps waiting for the next one
	}

	@Override
	public void configured(boolean stopOnEntry) {
		if( configured.getCount() > 0 ) {
			this.stopOnEntry = stopOnEntry;
		}
		configured.countDown();
	}

	@Override
	public void disconnected(FshdbAdapter adapter) {
		if( current == adapter ) {
			current = null;
			closeConnection();
		}
	}

	private void closeConnection() {
		Socket s = currentSocket;
		currentSocket = null;
		closeQuietly(s);
		DapConnection c = currentConnection;
		if( c != null ) {
			c.close();
		}
	}

	/** Stops serving: closes the socket (the script isn't stopped). */
	public void close() {
		closeQuietly(serverSocket);
		closeConnection();
	}

	private static void closeQuietly(java.io.Closeable c) {
		if( c != null ) {
			try {
				c.close();
			} catch (IOException e) {
				// closed
			}
		}
	}

	/**
	 * Parses [HOST:]PORT. The host defaults to the loopback address: a debugger on another
	 * machine needs an address given explicitly (0.0.0.0 for all), or an SSH tunnel.
	 */
	public static InetSocketAddress parseAddress(String spec) throws IOException {
		String host = null;
		String port = spec.trim();
		int colon = port.lastIndexOf(':');
		if( colon >= 0 ) {
			host = port.substring(0, colon);
			port = port.substring(colon+1);
		}
		int p;
		try {
			p = Integer.parseInt(port);
		} catch (NumberFormatException e) {
			throw new IOException("Not a port: "+spec);
		}
		if( p < 0 || p > 65535 ) {
			throw new IOException("Not a port: "+spec);
		}
		if( host == null || host.isEmpty()) {
			return new InetSocketAddress(InetAddress.getLoopbackAddress(), p);
		}
		if( host.startsWith("[") && host.endsWith("]")) {
			host = host.substring(1, host.length()-1);
		}
		return new InetSocketAddress(InetAddress.getByName(host), p);
	}
}
