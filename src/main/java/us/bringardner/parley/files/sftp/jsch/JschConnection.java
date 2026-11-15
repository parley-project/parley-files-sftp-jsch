package us.bringardner.parley.files.sftp.jsch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import us.bringardner.parley.files.sftp.spi.SftpChannel;
import us.bringardner.parley.files.sftp.spi.SshConnection;

class JschConnection implements SshConnection {

	private final Session session;
	private final int timeoutMs;

	JschConnection(Session session, int timeoutMs) {
		this.session = session;
		this.timeoutMs = timeoutMs;
	}

	@Override
	public boolean isConnected() {
		return session.isConnected();
	}

	/** How long openSftp() keeps retrying when the server refuses a channel. */
	static final long REFUSED_RETRY_MS = 1000;

	/**
	 * Opens an SFTP channel. If the server refuses it, tries again for up to
	 * REFUSED_RETRY_MS: JSch's disconnect() doesn't wait for the server to
	 * confirm a channel is closed, so right after one is closed it may still
	 * count against the server's limit (OpenSSH: 10 per connection).
	 */
	@Override
	public SftpChannel openSftp() throws IOException {
		long giveUp = System.currentTimeMillis() + REFUSED_RETRY_MS;
		long pause = 5;
		while( true ) {
			ChannelSftp channel = null;
			try {
				channel = (ChannelSftp) session.openChannel("sftp");
				channel.connect(timeoutMs);
				return new JschSftpChannel(channel);
			} catch (JSchException e) {
				// a refusal leaves the server's reason code (1-4) as the exit status; a timeout leaves -1
				boolean refused = channel != null && channel.getExitStatus() > 0 && session.isConnected();
				if( channel != null ) {
					channel.disconnect();
				}
				if( !refused || System.currentTimeMillis() + pause > giveUp ) {
					throw new IOException(e.getMessage(), e);
				}
			}
			try {
				Thread.sleep(pause);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while opening an SFTP channel");
			}
			pause = Math.min(pause * 2, 100);
		}
	}

	/** Closes commands that run too long; one daemon thread for every connection. */
	private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "JSch command timer");
		t.setDaemon(true);
		return t;
	});

	/** @param commandTimeoutMs 0 or less: no limit */
	@Override
	public ExecResult exec(String command, long commandTimeoutMs) throws IOException {
		ChannelExec exec = null;
		try {
			// JSch writes stderr into this buffer itself, so a command that
			// fills stderr can't block while stdout is being read.
			ByteArrayOutputStream err = new ByteArrayOutputStream();
			InputStream stdOut;
			InputStream[] output = new InputStream[1];
			exec = openExec(command, channel -> {
				channel.setErrStream(err, true);
				output[0] = channel.getInputStream();
			});
			stdOut = output[0];

			// The time limit is enforced by closing the channel, which ends the
			// read below. Reading until EOF used to come first, with no limit,
			// so a command that hung with its output open blocked forever. (The
			// read has to block: JSch's pipe only wakes its writer when a
			// blocking read finds it empty, so polling it is very slow.)
			AtomicBoolean timedOut = new AtomicBoolean();
			ChannelExec channel = exec;
			ScheduledFuture<?> timer = commandTimeoutMs <= 0 ? null : TIMER.schedule(() -> {
				timedOut.set(true);
				channel.disconnect();
			}, commandTimeoutMs, TimeUnit.MILLISECONDS);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try {
				byte[] buffer = new byte[4096];
				int read;
				while( (read = stdOut.read(buffer)) >= 0 ) {
					out.write(buffer, 0, read);
				}
				while( !exec.isClosed() && !timedOut.get()) {
					Thread.sleep(10);   // EOF comes just before the exit status and the close
				}
			} catch (IOException e) {
				if( !timedOut.get()) {
					throw e;
				}
				// the timer closed the pipe under the read
			} finally {
				if( timer != null ) {
					timer.cancel(false);
				}
			}
			if( timedOut.get()) {
				throw new IOException("Command did not finish within "+commandTimeoutMs/1000.0+" s: "+command);
			}
			return new ExecResult(exec.getExitStatus(),
					out.toString(StandardCharsets.UTF_8.name()),
					err.toString(StandardCharsets.UTF_8.name()));
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for: "+command);
		} finally {
			if( exec != null ) {
				exec.disconnect();
			}
		}
	}

	/** What is done to an exec channel before it is connected: JSch's streams are taken then. */
	private interface BeforeConnect {
		void prepare(ChannelExec channel) throws IOException;
	}

	/**
	 * Opens an exec channel for command and connects it. Like openSftp(), a refused open is tried
	 * again for up to REFUSED_RETRY_MS: a channel closed just before (say, to make room for this
	 * command) may still count against the server's limit.
	 */
	private ChannelExec openExec(String command, BeforeConnect beforeConnect) throws IOException, JSchException, InterruptedException {
		long giveUp = System.currentTimeMillis() + REFUSED_RETRY_MS;
		long pause = 5;
		while( true ) {
			ChannelExec channel = (ChannelExec) session.openChannel("exec");
			channel.setCommand(command);
			beforeConnect.prepare(channel);
			try {
				channel.connect(timeoutMs);
				return channel;
			} catch (JSchException e) {
				// a refusal leaves the server's reason code (1-4) as the exit status
				boolean refused = channel.getExitStatus() > 0 && session.isConnected();
				channel.disconnect();
				if( !refused || System.currentTimeMillis() + pause > giveUp ) {
					throw e;
				}
			}
			Thread.sleep(pause);
			pause = Math.min(pause * 2, 100);
		}
	}

	@Override
	public Process start(String command) throws IOException {
		InputStream[] streams = new InputStream[2];
		ChannelExec channel;
		try {
			channel = openExec(command, c -> {
				streams[0] = c.getInputStream();
				streams[1] = c.getExtInputStream();
			});
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new java.io.InterruptedIOException("Interrupted while starting: "+command);
		}
		try {
			return new JschProcess(channel, channel.getOutputStream(), streams[0], streams[1]);
		} catch (IOException | RuntimeException e) {
			channel.disconnect();
			throw e;
		}
	}

	@Override
	public void close() {
		session.disconnect();
	}
}
