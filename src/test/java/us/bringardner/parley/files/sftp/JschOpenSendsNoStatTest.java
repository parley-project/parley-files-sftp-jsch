package us.bringardner.parley.files.sftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.sftp.jsch.JschProvider;
import us.bringardner.parley.files.sftp.jsch.JschTestAccess;
import us.bringardner.parley.files.sftp.spi.SftpChannel;
import us.bringardner.parley.files.sftp.spi.SftpFile;

/** JSch's open() did a stat that the read it opens anyway makes unnecessary. */
public class JschOpenSendsNoStatTest {

	private static final String DIR = "JschOpenSendsNoStatTest";
	private static SftpFileSourceFactory connected;

	@BeforeAll
	static void connect() throws IOException {
		connected = TestServer.connect(JschProvider.NAME);
	}

	@AfterAll
	static void disconnect() throws IOException {
		if( connected != null ) {
			SftpRandomAccessTest.deleteAll(connected.createFileSource(DIR));
			connected.disConnect();
		}
	}

	@Test
	void openSendsNoStat() throws IOException {
		FileSource dir = connected.createFileSource(DIR);
		dir.mkdirs();
		String d = dir.getAbsolutePath();
		SftpRegressionTest.write(connected.createFileSource(d+"/x.bin"), new byte[] {1, 2, 3});
		AtomicInteger stats = new AtomicInteger();
		SftpChannel raw = connected.getConnection().openSftp();
		try {
			SftpChannel counting = JschTestAccess.countingStats(raw, stats);
			try (SftpFile file = counting.open(d+"/x.bin", false)) {
				byte[] b = new byte[3];
				assertEquals(3, file.read(0, b, 0, 3));
				assertArrayEquals(new byte[] {1, 2, 3}, b);
			}
			assertEquals(0, stats.get(), "stat calls");
			assertThrows(NoSuchFileException.class, () -> counting.open(d+"/missing.bin", false));
		} finally {
			raw.close();
		}
	}
}
