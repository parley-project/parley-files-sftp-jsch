package us.bringardner.parley.files.sftp.jsch;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import com.jcraft.jsch.ChannelExec;

import us.bringardner.parley.files.sftp.spi.SshExitStatus;

/**
 * A command running on the server over a JSch exec channel (SshConnection.start). JSch reads
 * every channel of a session on one thread, so read both of its outputs, as with any Process: a
 * command whose output nobody reads stops the other channels of the session too once JSch's
 * buffer is full. JSch doesn't report the signal that ended a command (its status is then 255).
 */
final class JschProcess extends Process {

	private final ChannelExec channel;
	private final OutputStream in;
	private final InputStream out;
	private final InputStream err;

	/** @param out and err were taken from the channel before it was connected, as JSch needs */
	JschProcess(ChannelExec channel, OutputStream in, InputStream out, InputStream err) {
		this.channel = channel;
		this.in = in;
		this.out = out;
		this.err = err;
	}

	@Override
	public OutputStream getOutputStream() {
		return in;
	}

	@Override
	public InputStream getInputStream() {
		return out;
	}

	@Override
	public InputStream getErrorStream() {
		return err;
	}

	@Override
	public int waitFor() throws InterruptedException {
		while( !channel.isClosed()) {
			Thread.sleep(10);
		}
		return exitValue();
	}

	@Override
	public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
		long end = System.nanoTime() + unit.toNanos(timeout);
		while( !channel.isClosed()) {
			if( System.nanoTime() - end >= 0 ) {
				return false;
			}
			Thread.sleep(10);
		}
		return true;
	}

	@Override
	public int exitValue() {
		if( !channel.isClosed()) {
			throw new IllegalThreadStateException("The command is still running");
		}
		return SshExitStatus.of(channel.getExitStatus(), null);
	}

	@Override
	public boolean isAlive() {
		return !channel.isClosed();
	}

	@Override
	public void destroy() {
		channel.disconnect();
	}
}
